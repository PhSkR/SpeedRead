package com.speedread.rsvp.data.document

import com.speedread.rsvp.data.bookmark.BookmarkDao
import com.speedread.rsvp.data.bookmark.BookmarkSource
import com.speedread.rsvp.data.document.DocumentJson.encodeToJson
import com.speedread.rsvp.pdf.FigureImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Date
import javax.inject.Inject
import com.speedread.rsvp.data.bookmark.BookmarkRepository
import javax.inject.Singleton

@Singleton
class SavedDocumentRepository @Inject constructor(
    private val savedDocumentDao: SavedDocumentDao,
    private val documentStorageManager: DocumentStorageManager,
    private val figureImageStore: FigureImageStore,
    private val bookmarkDao: BookmarkDao,
    private val bookmarkRepository: BookmarkRepository
) {
    
    fun getAllDocuments(): Flow<List<SavedDocument>> {
        return savedDocumentDao.getAllDocuments()
    }
    
    fun getFavoriteDocuments(): Flow<List<SavedDocument>> {
        return savedDocumentDao.getFavoriteDocuments()
    }
    
    fun getDocumentsSorted(sortOrder: DocumentSortOrder): Flow<List<SavedDocument>> {
        return when (sortOrder) {
            DocumentSortOrder.TITLE_ASC -> savedDocumentDao.getDocumentsSortedByTitleAsc()
            DocumentSortOrder.TITLE_DESC -> savedDocumentDao.getDocumentsSortedByTitleDesc()
            DocumentSortOrder.DATE_CREATED_ASC -> savedDocumentDao.getDocumentsSortedByDateCreatedAsc()
            DocumentSortOrder.DATE_CREATED_DESC -> savedDocumentDao.getDocumentsSortedByDateCreatedDesc()
            DocumentSortOrder.FAVORITES_FIRST -> savedDocumentDao.getDocumentsSortedByFavoritesFirst()
            else -> savedDocumentDao.getAllDocuments()
        }
    }
    
    suspend fun getDocumentById(id: Long): SavedDocument? {
        return withContext(Dispatchers.IO) {
            savedDocumentDao.getDocumentById(id)
        }
    }
    
    suspend fun getDocumentByContentHash(contentHash: String): SavedDocument? {
        return withContext(Dispatchers.IO) {
            savedDocumentDao.getDocumentByContentHash(contentHash)
        }
    }
    
    suspend fun getMostRecentDocument(): SavedDocument? {
        return withContext(Dispatchers.IO) {
            savedDocumentDao.getMostRecentDocument()
        }
    }
    
    suspend fun loadDocumentContent(document: SavedDocument): String {
        return withContext(Dispatchers.IO) {
            documentStorageManager.loadDocumentContent(document)
        }
    }
    
    fun searchDocuments(query: String): Flow<List<SavedDocument>> {
        val searchQuery = "%$query%"
        return savedDocumentDao.searchDocuments(searchQuery)
    }
    
    suspend fun saveDocument(
        title: String,
        content: String,
        source: BookmarkSource,
        originalFileName: String? = null,
        originalUri: String? = null,
        mimeType: String? = null,
        author: String? = null,
        language: String? = null,
        pageCount: Int? = null,
        pageBoundaries: List<PageBoundary>? = null,
        figureRegions: List<FigureRegion>? = null
    ): Long {
        return withContext(Dispatchers.IO) {
            val contentHash = generateContentHash(content)

            // Check if document already exists
            val existingDocument = savedDocumentDao.getDocumentByContentHash(contentHash)
            if (existingDocument != null) {
                // Re-import of known content: adopt the fresh parse's metadata. The importer
                // is the authority on boundaries/figures/source, and this is the only path by
                // which documents imported before those features existed (or mislabeled by
                // historical bugs) can be repaired without losing positions/bookmarks.
                // Null metadata means "this import flow doesn't produce it" (e.g. EPUB) and
                // preserves the stored value; a non-null (possibly empty) list overwrites.
                if (pageBoundaries != null || figureRegions != null || existingDocument.source != source) {
                    savedDocumentDao.updateImportMetadata(
                        id = existingDocument.id,
                        pageBoundariesJson = pageBoundaries?.encodeToJson()
                            ?: existingDocument.pageBoundariesJson,
                        figureRegionsJson = figureRegions?.encodeToJson()
                            ?: existingDocument.figureRegionsJson,
                        pageCount = pageCount ?: existingDocument.pageCount,
                        source = source,
                        accessTime = Date()
                    )
                } else {
                    savedDocumentDao.updateLastAccessed(existingDocument.id, Date())
                }
                return@withContext existingDocument.id
            }
            
            // Determine storage strategy
            val storageResult = documentStorageManager.prepareDocumentForStorage(
                title = title,
                content = content,
                originalFileName = originalFileName,
                contentHash = contentHash
            )
            
            val wordCount = countWords(content)
            val now = Date()
            
            val document = when (storageResult) {
                is DocumentStorageResult.Inline -> {
                    SavedDocument(
                        title = title,
                        content = storageResult.content,
                        contentHash = contentHash,
                        isContentExternal = false,
                        contentFilePath = null,
                        originalFileName = originalFileName,
                        fileSize = storageResult.sizeBytes.toLong(),
                        wordCount = wordCount,
                        source = source,
                        originalUri = originalUri,
                        mimeType = mimeType,
                        author = author,
                        language = language,
                        createdAt = now,
                        lastAccessedAt = now,
                        pageCount = pageCount,
                        pageBoundariesJson = pageBoundaries?.encodeToJson(),
                        figureRegionsJson = figureRegions?.encodeToJson()
                    )
                }
                is DocumentStorageResult.External -> {
                    SavedDocument(
                        title = title,
                        content = "", // Empty for external files
                        contentHash = contentHash,
                        isContentExternal = true,
                        contentFilePath = storageResult.filePath,
                        originalFileName = originalFileName,
                        fileSize = storageResult.sizeBytes.toLong(),
                        wordCount = wordCount,
                        source = source,
                        originalUri = originalUri,
                        mimeType = mimeType,
                        author = author,
                        language = language,
                        createdAt = now,
                        lastAccessedAt = now,
                        pageCount = pageCount,
                        pageBoundariesJson = pageBoundaries?.encodeToJson(),
                        figureRegionsJson = figureRegions?.encodeToJson()
                    )
                }
            }

            savedDocumentDao.insertDocument(document)
        }
    }
    
    suspend fun updateDocument(document: SavedDocument) {
        withContext(Dispatchers.IO) {
            savedDocumentDao.updateDocument(document)
        }
    }
    
    suspend fun deleteDocument(document: SavedDocument) {
        withContext(Dispatchers.IO) {
            // Cascade cleanup: figures and bookmarks
            figureImageStore.deleteFiguresForDocument(document.contentHash)

            // Delete bookmarks using modern SHA-256 textHash if content is loadable,
            // plus legacy MD5 document.contentHash fallback.
            try {
                val content = documentStorageManager.loadDocumentContent(document)
                if (content.isNotEmpty()) {
                    val sha256Hash = bookmarkRepository.generateTextHash(content)
                    bookmarkDao.deleteBookmarksByTextHash(sha256Hash)
                }
            } catch (e: Exception) {
                // Ignore load error (e.g. missing external file), still purge by MD5
            }
            bookmarkDao.deleteBookmarksByTextHash(document.contentHash)

            // Delete external content file if it exists
            documentStorageManager.deleteDocumentContent(document)
            // Delete database record
            savedDocumentDao.deleteDocument(document)
        }
    }
    
    suspend fun markAsAccessed(documentId: Long) {
        withContext(Dispatchers.IO) {
            savedDocumentDao.updateLastAccessed(documentId, Date())
        }
    }
    
    suspend fun updateFavoriteStatus(documentId: Long, isFavorite: Boolean) {
        withContext(Dispatchers.IO) {
            savedDocumentDao.updateFavoriteStatus(documentId, isFavorite)
        }
    }
    
    suspend fun updateTags(documentId: Long, tags: List<String>) {
        withContext(Dispatchers.IO) {
            val tagsString = tags.joinToString(",")
            savedDocumentDao.updateTags(documentId, tagsString)
        }
    }
    
    suspend fun updateReadingPosition(documentId: Long, position: Int, totalWords: Int) {
        withContext(Dispatchers.IO) {
            val progress = if (totalWords > 0) (position.toFloat() / totalWords) * 100f else 0f
            savedDocumentDao.updateReadingPosition(documentId, position, progress, Date())
        }
    }
    
    suspend fun getStorageStats(): DocumentStorageStats {
        return withContext(Dispatchers.IO) {
            val totalDocuments = savedDocumentDao.getDocumentCount()
            val totalSize = savedDocumentDao.getTotalStorageSize()
            DocumentStorageStats(totalDocuments, totalSize)
        }
    }
    
    suspend fun isDocumentSaved(content: String): Boolean {
        return withContext(Dispatchers.IO) {
            val contentHash = generateContentHash(content)
            savedDocumentDao.countDocumentsWithHash(contentHash) > 0
        }
    }
    
    suspend fun cleanupOrphanedFiles(): Int {
        return withContext(Dispatchers.IO) {
            // Get all valid file paths from database
            val allDocuments = savedDocumentDao.getAllDocuments().first()
            val validFilePaths = allDocuments
                .filter { it.isContentExternal && !it.contentFilePath.isNullOrBlank() }
                .map { it.contentFilePath!! }
                .toSet()
            
            // Clean up orphaned files
            documentStorageManager.cleanupOrphanedFiles(validFilePaths)
        }
    }
    
    suspend fun getExtendedStorageStats(): ExtendedDocumentStorageStats {
        return withContext(Dispatchers.IO) {
            val totalDocuments = savedDocumentDao.getDocumentCount()
            val totalDbSize = savedDocumentDao.getTotalStorageSize()
            val fileStats = documentStorageManager.getStorageStats()
            
            ExtendedDocumentStorageStats(
                totalDocuments = totalDocuments,
                inlineSizeBytes = totalDbSize - fileStats.externalFileSizeBytes,
                externalFileCount = fileStats.externalFileCount,
                externalFileSizeBytes = fileStats.externalFileSizeBytes,
                documentsDirectory = fileStats.documentsDirectory
            )
        }
    }
    
    private fun generateContentHash(content: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(content.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun countWords(text: String): Int {
        return text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
    }
}

data class DocumentStorageStats(
    val totalDocuments: Int,
    val totalSizeBytes: Long
) {
    val totalSizeMB: Float get() = totalSizeBytes / (1024f * 1024f)
    val averageDocumentSize: Long get() = if (totalDocuments > 0) totalSizeBytes / totalDocuments else 0
}

data class ExtendedDocumentStorageStats(
    val totalDocuments: Int,
    val inlineSizeBytes: Long, // Size stored in database
    val externalFileCount: Int, // Number of external files
    val externalFileSizeBytes: Long, // Size stored as files
    val documentsDirectory: String // Path to external files directory
) {
    val totalSizeBytes: Long get() = inlineSizeBytes + externalFileSizeBytes
    val totalSizeMB: Float get() = totalSizeBytes / (1024f * 1024f)
    val inlineDocuments: Int get() = totalDocuments - externalFileCount
    val averageDocumentSize: Long get() = if (totalDocuments > 0) totalSizeBytes / totalDocuments else 0
}
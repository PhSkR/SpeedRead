package com.speedread.rsvp.data.document

import com.speedread.rsvp.data.bookmark.BookmarkRepository
import com.speedread.rsvp.data.bookmark.BookmarkSource
import com.speedread.rsvp.data.bookmark.BookmarkWithProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepository @Inject constructor(
    private val savedDocumentRepository: SavedDocumentRepository,
    private val bookmarkRepository: BookmarkRepository
) {

    fun getAllDocuments(): Flow<List<SavedDocument>> {
        return savedDocumentRepository.getAllDocuments()
    }

    suspend fun getMostRecentDocument(): SavedDocument? {
        return savedDocumentRepository.getMostRecentDocument()
    }

    /**
     * Full row by id. List/search queries return metadata projections (content truncated to
     * 200 chars, pageBoundariesJson NULL — see SavedDocumentDao.SELECT_METADATA); any code
     * path that needs the real content MUST re-fetch through here.
     */
    suspend fun getDocumentById(id: Long): SavedDocument? {
        return savedDocumentRepository.getDocumentById(id)
    }

    /**
     * Locate the saved document whose FULL text hashes (SHA-256, the bookmark keying hash)
     * to [textHash]. SavedDocument.contentHash is MD5, so no direct column lookup exists;
     * each candidate's full content is loaded and hashed, most recently accessed first, with
     * an early exit on match. Bookmark jumps are rare user actions, so the per-candidate
     * content read is acceptable.
     */
    suspend fun findDocumentByTextHash(textHash: String): SavedDocument? = withContext(Dispatchers.Default) {
        val candidates = savedDocumentRepository.getAllDocuments().first()
        for (candidate in candidates) {
            val full = savedDocumentRepository.getDocumentById(candidate.id) ?: continue
            val content = try {
                if (full.isContentExternal) {
                    savedDocumentRepository.loadDocumentContent(full)
                } else {
                    full.content
                }
            } catch (e: Exception) {
                continue
            }
            if (content.isNotEmpty() && bookmarkRepository.generateTextHash(content) == textHash) {
                return@withContext full
            }
        }
        null
    }

    suspend fun saveDocument(document: SavedDocument) {
        val pageBoundaries = DocumentJson.decodePageBoundaries(document.pageBoundariesJson)
        savedDocumentRepository.saveDocument(
            title = document.title,
            content = document.content,
            source = document.source,
            originalFileName = document.originalFileName,
            originalUri = document.originalUri,
            mimeType = document.mimeType,
            author = document.author,
            language = document.language,
            pageCount = document.pageCount,
            pageBoundaries = pageBoundaries
        )
    }

    suspend fun deleteDocument(document: SavedDocument) {
        savedDocumentRepository.deleteDocument(document)
    }

    suspend fun updateFavoriteStatus(documentId: Long, isFavorite: Boolean) {
        savedDocumentRepository.updateFavoriteStatus(documentId, isFavorite)
    }

    suspend fun updateReadingPosition(documentId: Long, position: Int, totalWords: Int) {
        savedDocumentRepository.updateReadingPosition(documentId, position, totalWords)
    }

    suspend fun isDocumentSaved(text: String): Boolean {
        return savedDocumentRepository.isDocumentSaved(text)
    }

    suspend fun cleanupOrphanedFiles() {
        savedDocumentRepository.cleanupOrphanedFiles()
    }

    fun getAllBookmarks(): Flow<List<BookmarkWithProgress>> {
        return bookmarkRepository.getAllBookmarks()
    }

    suspend fun getBookmarksForText(text: String): Flow<List<BookmarkWithProgress>> {
        return bookmarkRepository.getBookmarksForText(text)
    }

    suspend fun createBookmark(title: String, fullText: String, wordPosition: Int, pageNumber: Int, wpm: Int, source: com.speedread.rsvp.data.bookmark.BookmarkSource, sourceUri: String?, isAutoBookmark: Boolean) {
        bookmarkRepository.createBookmark(title, fullText, wordPosition, pageNumber, wpm, source, sourceUri, isAutoBookmark)
    }

    suspend fun createAutoBookmarkWithCleanup(title: String, fullText: String, wordPosition: Int, pageNumber: Int, wpm: Int, source: com.speedread.rsvp.data.bookmark.BookmarkSource, sourceUri: String?) {
        bookmarkRepository.createAutoBookmarkWithCleanup(title, fullText, wordPosition, pageNumber, wpm, source, sourceUri)
    }

    suspend fun deleteBookmark(bookmark: com.speedread.rsvp.data.bookmark.Bookmark) {
        bookmarkRepository.deleteBookmark(bookmark)
    }

    suspend fun deleteBookmarks(bookmarks: List<BookmarkWithProgress>) {
        bookmarks.forEach { bookmarkRepository.deleteBookmark(it.bookmark) }
    }

    suspend fun markBookmarkAsAccessed(bookmarkId: Long) {
        bookmarkRepository.markBookmarkAsAccessed(bookmarkId)
    }

    suspend fun cleanupOldAutoBookmarks() {
        bookmarkRepository.cleanupOldAutoBookmarks()
    }

    suspend fun generateTextHash(text: String): String {
        return bookmarkRepository.generateTextHash(text)
    }

    /**
     * Opportunistic migration of pre-1.13.18 MD5-hashed bookmarks to SHA-256.
     * Call once per document load so the Flow-based bookmark list surfaces legacy rows.
     */
    suspend fun migrateLegacyBookmarkHashes(text: String) {
        bookmarkRepository.migrateLegacyHashIfPresent(text)
    }

    suspend fun cleanupAllDuplicateAutoBookmarks() {
        bookmarkRepository.cleanupAllDuplicateAutoBookmarks()
    }

    suspend fun getBookmarkForText(fullText: String): BookmarkWithProgress? {
        val bookmark = bookmarkRepository.getBookmarkForText(fullText)
        return bookmark?.let { BookmarkWithProgress.from(it) }
    }

    suspend fun saveNewDocument(
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
        return savedDocumentRepository.saveDocument(
            title = title,
            content = content,
            source = source,
            originalFileName = originalFileName,
            originalUri = originalUri,
            mimeType = mimeType,
            author = author,
            language = language,
            pageCount = pageCount,
            pageBoundaries = pageBoundaries,
            figureRegions = figureRegions
        )
    }

    suspend fun loadDocumentContent(document: SavedDocument): String {
        return savedDocumentRepository.loadDocumentContent(document)
    }

    suspend fun markDocumentAsAccessed(documentId: Long) {
        savedDocumentRepository.markAsAccessed(documentId)
    }
}
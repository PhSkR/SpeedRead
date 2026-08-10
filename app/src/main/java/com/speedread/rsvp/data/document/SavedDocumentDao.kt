package com.speedread.rsvp.data.document

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface SavedDocumentDao {

    companion object {
        const val SELECT_METADATA = "SELECT id, title, SUBSTR(content, 1, 200) AS content, contentHash, isContentExternal, contentFilePath, originalFileName, fileSize, wordCount, source, originalUri, mimeType, author, language, createdAt, lastAccessedAt, lastReadPosition, readingProgress, isFavorite, tags, category, pageCount, NULL AS pageBoundariesJson, NULL AS figureRegionsJson FROM saved_documents"
    }
    
    @Query(SELECT_METADATA + " ORDER BY lastAccessedAt DESC")
    fun getAllDocuments(): Flow<List<SavedDocument>>
    
    @Query(SELECT_METADATA + " WHERE isFavorite = 1 ORDER BY lastAccessedAt DESC")
    fun getFavoriteDocuments(): Flow<List<SavedDocument>>
    
    @Query("SELECT * FROM saved_documents WHERE id = :id")
    suspend fun getDocumentById(id: Long): SavedDocument?
    
    @Query("SELECT * FROM saved_documents WHERE contentHash = :contentHash LIMIT 1")
    suspend fun getDocumentByContentHash(contentHash: String): SavedDocument?
    
    @Query(SELECT_METADATA + " WHERE title LIKE :query OR author LIKE :query OR tags LIKE :query ORDER BY lastAccessedAt DESC")
    fun searchDocuments(query: String): Flow<List<SavedDocument>>
    
    @Query(SELECT_METADATA + " WHERE source = :source ORDER BY lastAccessedAt DESC")
    fun getDocumentsBySource(source: com.speedread.rsvp.data.bookmark.BookmarkSource): Flow<List<SavedDocument>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: SavedDocument): Long
    
    @Update
    suspend fun updateDocument(document: SavedDocument)
    
    @Delete
    suspend fun deleteDocument(document: SavedDocument)
    
    @Query("DELETE FROM saved_documents WHERE id = :id")
    suspend fun deleteDocumentById(id: Long)
    
    @Query("UPDATE saved_documents SET lastAccessedAt = :accessTime WHERE id = :id")
    suspend fun updateLastAccessed(id: Long, accessTime: Date)

    // Refreshes import-derived metadata on an existing row without touching content — used
    // by the dedup path so re-importing a document upgrades it with boundaries/figures
    // parsed by a newer importer. Source is refreshed too: the fresh parse is authoritative,
    // and this is the repair path for rows mislabeled by the historical loadText save race.
    @Query(
        "UPDATE saved_documents SET pageBoundariesJson = :pageBoundariesJson, " +
            "figureRegionsJson = :figureRegionsJson, pageCount = :pageCount, " +
            "source = :source, lastAccessedAt = :accessTime WHERE id = :id"
    )
    suspend fun updateImportMetadata(
        id: Long,
        pageBoundariesJson: String?,
        figureRegionsJson: String?,
        pageCount: Int?,
        source: com.speedread.rsvp.data.bookmark.BookmarkSource,
        accessTime: Date
    )
    
    @Query("UPDATE saved_documents SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavoriteStatus(id: Long, isFavorite: Boolean)
    
    @Query("UPDATE saved_documents SET tags = :tags WHERE id = :id")
    suspend fun updateTags(id: Long, tags: String)
    
    @Query("UPDATE saved_documents SET lastReadPosition = :position, readingProgress = :progress, lastAccessedAt = :accessTime WHERE id = :id")
    suspend fun updateReadingPosition(id: Long, position: Int, progress: Float, accessTime: Date)
    
    @Query("SELECT COUNT(*) FROM saved_documents")
    suspend fun getDocumentCount(): Int
    
    @Query("SELECT COUNT(*) FROM saved_documents WHERE contentHash = :contentHash")
    suspend fun countDocumentsWithHash(contentHash: String): Int
    
    @Query("SELECT SUM(fileSize) FROM saved_documents")
    suspend fun getTotalStorageSize(): Long
    
    // Get documents with sorting
    @Query(SELECT_METADATA + " ORDER BY title ASC")
    fun getDocumentsSortedByTitleAsc(): Flow<List<SavedDocument>>
    
    @Query(SELECT_METADATA + " ORDER BY title DESC")
    fun getDocumentsSortedByTitleDesc(): Flow<List<SavedDocument>>
    
    @Query(SELECT_METADATA + " ORDER BY createdAt ASC")
    fun getDocumentsSortedByDateCreatedAsc(): Flow<List<SavedDocument>>
    
    @Query(SELECT_METADATA + " ORDER BY createdAt DESC")
    fun getDocumentsSortedByDateCreatedDesc(): Flow<List<SavedDocument>>
    
    @Query(SELECT_METADATA + " ORDER BY isFavorite DESC, lastAccessedAt DESC")
    fun getDocumentsSortedByFavoritesFirst(): Flow<List<SavedDocument>>
    
    // Externally-stored documents keep content = '' in the row (the text lives in a file),
    // so they must be admitted explicitly or large imports silently stop auto-resuming.
    @Query("SELECT * FROM saved_documents WHERE (content IS NOT NULL AND content != '') OR isContentExternal = 1 ORDER BY lastAccessedAt DESC LIMIT 1")
    suspend fun getMostRecentDocument(): SavedDocument?
}
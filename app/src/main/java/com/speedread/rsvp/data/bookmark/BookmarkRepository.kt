package com.speedread.rsvp.data.bookmark

import com.speedread.rsvp.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepository @Inject constructor(
    private val bookmarkDao: BookmarkDao
) {
    companion object {
        const val WORDS_PER_PAGE = Constants.WORDS_PER_PAGE_ESTIMATION // Same as MainViewModel

        // Document identity hashing. Bookmarks don't store the original text, only the
        // hash, so we cannot do a schema-side backfill: existing MD5 rows are migrated
        // opportunistically at read time (see migrateLegacyHashIfPresent).
        const val HASH_ALGORITHM = "SHA-256"
        const val LEGACY_HASH_ALGORITHM = "MD5"
        private const val HASH_STREAMING_THRESHOLD_BYTES = 1_000_000 // 1 MB
        private const val HASH_STREAMING_CHUNK_BYTES = 100_000       // 100 KB
        private const val PREVIEW_WINDOW_CHARS = 500
        private const val CHARS_PER_WORD_ESTIMATION = 6
        private const val PREVIEW_WORDS_BEFORE = 5
        private const val PREVIEW_WORDS_AFTER = 10
        private const val PREVIEW_MAX_LENGTH = 97
    }
    
    fun getAllBookmarks(): Flow<List<BookmarkWithProgress>> {
        return bookmarkDao.getAllBookmarks().map { bookmarks ->
            bookmarks.map { BookmarkWithProgress.from(it) }
        }
    }
    
    fun getManualBookmarks(): Flow<List<BookmarkWithProgress>> {
        return bookmarkDao.getManualBookmarks().map { bookmarks ->
            bookmarks.map { BookmarkWithProgress.from(it) }
        }
    }
    
    fun getAutoBookmarks(): Flow<List<BookmarkWithProgress>> {
        return bookmarkDao.getAutoBookmarks().map { bookmarks ->
            bookmarks.map { BookmarkWithProgress.from(it) }
        }
    }
    
    fun getBookmarksForText(text: String): Flow<List<BookmarkWithProgress>> {
        // Flow callers (UI observing the list) get the SHA-256 view directly. Legacy
        // MD5 rows are upgraded by migrateLegacyHashIfPresent, called once per load
        // from ReadingViewModel, not here — running the upgrade inside the flow would
        // re-fire on every collector restart.
        val textHash = generateTextHash(text)
        return bookmarkDao.getBookmarksForText(textHash).map { bookmarks ->
            bookmarks.map { BookmarkWithProgress.from(it) }
        }
    }

    fun getBookmarksForHash(textHash: String): Flow<List<BookmarkWithProgress>> {
        return bookmarkDao.getBookmarksForText(textHash).map { bookmarks ->
            bookmarks.map { BookmarkWithProgress.from(it) }
        }
    }
    
    suspend fun getBookmarkById(id: Long): Bookmark? {
        return withContext(Dispatchers.IO) {
            bookmarkDao.getBookmarkById(id)
        }
    }
    
    suspend fun getBookmarkForText(text: String): Bookmark? {
        return withContext(Dispatchers.IO) {
            val textHash = generateTextHash(text)
            bookmarkDao.getBookmarkByTextHash(textHash)
                ?: migrateLegacyHashIfPresent(text)?.let { bookmarkDao.getBookmarkByTextHash(textHash) }
        }
    }
    
    suspend fun createBookmark(
        title: String,
        fullText: String,
        wordPosition: Int,
        pageNumber: Int,
        wpm: Int,
        source: BookmarkSource,
        sourceUri: String? = null,
        isAutoBookmark: Boolean = false
    ): Long {
        return withContext(Dispatchers.IO) {
            val textHash = generateTextHash(fullText)
            val preview = generatePreview(fullText, wordPosition)
            val totalWords = countWords(fullText)
            val totalPages = ((totalWords - 1) / WORDS_PER_PAGE) + 1
            val now = Date()
            
            val bookmark = Bookmark(
                title = title,
                content = preview,
                textHash = textHash,
                wordPosition = wordPosition,
                pageNumber = pageNumber,
                totalWords = totalWords,
                totalPages = totalPages,
                wpm = wpm,
                createdAt = now,
                lastAccessedAt = now,
                source = source,
                sourceUri = sourceUri,
                isAutoBookmark = isAutoBookmark
            )
            
            bookmarkDao.insertBookmark(bookmark)
        }
    }
    
    suspend fun updateBookmark(bookmark: Bookmark) {
        withContext(Dispatchers.IO) {
            bookmarkDao.updateBookmark(bookmark)
        }
    }
    
    suspend fun updateBookmarkProgress(
        bookmarkId: Long,
        wordPosition: Int,
        wpm: Int
    ) {
        withContext(Dispatchers.IO) {
            val bookmark = bookmarkDao.getBookmarkById(bookmarkId)
            bookmark?.let {
                val updatedBookmark = it.copy(
                    wordPosition = wordPosition,
                    wpm = wpm,
                    lastAccessedAt = Date()
                )
                bookmarkDao.updateBookmark(updatedBookmark)
            }
        }
    }
    
    suspend fun deleteBookmark(bookmark: Bookmark) {
        withContext(Dispatchers.IO) {
            bookmarkDao.deleteBookmark(bookmark)
        }
    }
    
    suspend fun deleteBookmarkById(id: Long) {
        withContext(Dispatchers.IO) {
            bookmarkDao.deleteBookmarkById(id)
        }
    }
    
    suspend fun markBookmarkAsAccessed(id: Long) {
        withContext(Dispatchers.IO) {
            bookmarkDao.updateLastAccessed(id, Date())
        }
    }
    
    suspend fun cleanupDuplicateAutoBookmarks(textHash: String) {
        withContext(Dispatchers.IO) {
            val autoBookmarks = bookmarkDao.getAutoBookmarksForText(textHash)
            
            // Keep only the most recent auto-bookmark, delete the rest
            if (autoBookmarks.size > 1) {
                val bookmarksToDelete = autoBookmarks.drop(1) // Skip the first (most recent) one
                val idsToDelete = bookmarksToDelete.map { it.id }
                if (idsToDelete.isNotEmpty()) {
                    bookmarkDao.deleteBookmarksByIds(idsToDelete)
                }
            }
        }
    }
    
    suspend fun createAutoBookmarkWithCleanup(
        title: String,
        fullText: String,
        wordPosition: Int,
        pageNumber: Int,
        wpm: Int,
        source: BookmarkSource,
        sourceUri: String? = null
    ): Long {
        return withContext(Dispatchers.IO) {
            val textHash = generateTextHash(fullText)
            
            // Create the new auto-bookmark
            val bookmarkId = createBookmark(
                title = title,
                fullText = fullText,
                wordPosition = wordPosition,
                pageNumber = pageNumber,
                wpm = wpm,
                source = source,
                sourceUri = sourceUri,
                isAutoBookmark = true
            )
            
            // Clean up duplicate auto-bookmarks for the same document
            cleanupDuplicateAutoBookmarks(textHash)
            
            bookmarkId
        }
    }
    
    suspend fun cleanupOldAutoBookmarks(daysToKeep: Int = 7) {
        withContext(Dispatchers.IO) {
            val cutoffDate = Date(System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L))
            bookmarkDao.deleteOldAutoBookmarks(cutoffDate)
        }
    }
    
    suspend fun cleanupAllDuplicateAutoBookmarks() {
        withContext(Dispatchers.IO) {
            // Get a one-time snapshot of all auto-bookmarks
            val allAutoBookmarksList = bookmarkDao.getAutoBookmarks().first()
            val groupedByText = allAutoBookmarksList.groupBy { it.textHash }
            
            // For each group, keep only the most recent and delete the rest
            groupedByText.forEach { (_, bookmarks) ->
                if (bookmarks.size > 1) {
                    val sortedByCreated = bookmarks.sortedByDescending { it.createdAt }
                    val bookmarksToDelete = sortedByCreated.drop(1) // Skip the most recent one
                    val idsToDelete = bookmarksToDelete.map { it.id }
                    if (idsToDelete.isNotEmpty()) {
                        bookmarkDao.deleteBookmarksByIds(idsToDelete)
                    }
                }
            }
        }
    }
    
    suspend fun hasBookmarkForText(text: String): Boolean {
        return withContext(Dispatchers.IO) {
            val textHash = generateTextHash(text)
            if (bookmarkDao.countBookmarksForText(textHash) > 0) return@withContext true
            migrateLegacyHashIfPresent(text)
            bookmarkDao.countBookmarksForText(textHash) > 0
        }
    }

    /**
     * Rehashes any bookmarks stored under the legacy MD5 of [text] to the current
     * SHA-256 hash, so future lookups hit. Safe to call on every document load; if no
     * legacy rows exist it's effectively a no-op (one DAO count query).
     *
     * Returns the number of rows migrated (0 when nothing to do), so callers can use
     * the `?.let` trick to trigger a re-query on a cache miss.
     */
    suspend fun migrateLegacyHashIfPresent(text: String): Int? {
        return withContext(Dispatchers.IO) {
            val legacyHash = generateLegacyTextHash(text)
            val newHash = generateTextHash(text)
            if (legacyHash == newHash) return@withContext null // identical algos — nothing to do
            val legacyRows = bookmarkDao.getBookmarksForTextOnce(legacyHash)
            if (legacyRows.isEmpty()) return@withContext null
            legacyRows.forEach { row ->
                bookmarkDao.updateBookmark(row.copy(textHash = newHash))
            }
            legacyRows.size
        }
    }
    
    fun generateTextHash(text: String): String = hashWithAlgorithm(text, HASH_ALGORITHM)

    /**
     * Produces the pre-1.13.18 MD5 hex digest. Used only for opportunistic migration
     * of older bookmark rows; no new writes should call this.
     */
    fun generateLegacyTextHash(text: String): String = hashWithAlgorithm(text, LEGACY_HASH_ALGORITHM)

    private fun hashWithAlgorithm(text: String, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)

        // For large texts, feed the digest in chunks so a multi-megabyte document
        // doesn't allocate a single giant byte array.
        if (text.length > HASH_STREAMING_THRESHOLD_BYTES) {
            var offset = 0
            while (offset < text.length) {
                val endOffset = minOf(offset + HASH_STREAMING_CHUNK_BYTES, text.length)
                val chunk = text.substring(offset, endOffset)
                digest.update(chunk.toByteArray(Charsets.UTF_8))
                offset = endOffset
            }
        } else {
            digest.update(text.toByteArray(Charsets.UTF_8))
        }

        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun generatePreview(text: String, currentPosition: Int): String {
        if (text.isBlank()) return ""

        // Estimate character position from word position (average 6 chars per word)
        // and slice a window of ±500 chars to avoid tokenizing full documents on autosave
        val charPosition = (currentPosition * CHARS_PER_WORD_ESTIMATION).coerceIn(0, text.length)
        val start = (charPosition - PREVIEW_WINDOW_CHARS).coerceAtLeast(0)
        val end = (charPosition + PREVIEW_WINDOW_CHARS).coerceAtMost(text.length)
        val sample = text.substring(start, end)

        val words = sample.replace('\n', ' ').replace('\r', ' ').split(' ')
            .filter { it.isNotBlank() }

        if (words.isEmpty()) return ""

        val sampleOffsetChars = charPosition - start
        val relativeWordPos = (sampleOffsetChars / CHARS_PER_WORD_ESTIMATION).coerceIn(0, words.size - 1)
        val startPos = (relativeWordPos - PREVIEW_WORDS_BEFORE).coerceAtLeast(0)
        val endPos = (relativeWordPos + PREVIEW_WORDS_AFTER).coerceAtMost(words.size)
        val preview = words.subList(startPos, endPos).joinToString(" ")
        return if (preview.length > PREVIEW_MAX_LENGTH) {
            preview.take(PREVIEW_MAX_LENGTH) + "..."
        } else preview
    }
    
    private fun countWords(text: String): Int {
        if (text.isBlank()) return 0
        
        // Memory-efficient word counting for large texts
        if (text.length > 1_000_000) {
            // Sample-based counting for large documents
            val sampleSize = 100_000
            val sample = text.substring(0, minOf(sampleSize, text.length))
            val sampleWords = countWordsSample(sample)
            // Extrapolate to full document
            return (sampleWords.toDouble() / sample.length * text.length).toInt()
        } else {
            return countWordsSample(text)
        }
    }
    
    private fun countWordsSample(text: String): Int {
        var wordCount = 0
        var inWord = false
        
        for (char in text) {
            if (char.isWhitespace()) {
                if (inWord) {
                    wordCount++
                    inWord = false
                }
            } else {
                inWord = true
            }
        }
        
        // Count the last word if text doesn't end with whitespace
        if (inWord) {
            wordCount++
        }
        
        return wordCount
    }
}
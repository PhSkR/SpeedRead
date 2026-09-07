package com.speedread.rsvp.data.bookmark

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface BookmarkDao {
    
    @Query("SELECT * FROM bookmarks ORDER BY lastAccessedAt DESC")
    fun getAllBookmarks(): Flow<List<Bookmark>>
    
    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun getBookmarkById(id: Long): Bookmark?
    
    @Query("SELECT * FROM bookmarks WHERE textHash = :textHash ORDER BY createdAt DESC LIMIT 1")
    suspend fun getBookmarkByTextHash(textHash: String): Bookmark?
    
    @Query("SELECT * FROM bookmarks WHERE isAutoBookmark = 1 ORDER BY lastAccessedAt DESC")
    fun getAutoBookmarks(): Flow<List<Bookmark>>
    
    @Query("SELECT * FROM bookmarks WHERE isAutoBookmark = 0 ORDER BY createdAt DESC")
    fun getManualBookmarks(): Flow<List<Bookmark>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark): Long
    
    @Update
    suspend fun updateBookmark(bookmark: Bookmark)
    
    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)
    
    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: Long)
    
    @Query("DELETE FROM bookmarks WHERE isAutoBookmark = 1 AND lastAccessedAt < :cutoffDate")
    suspend fun deleteOldAutoBookmarks(cutoffDate: Date)
    
    @Query("UPDATE bookmarks SET lastAccessedAt = :accessTime WHERE id = :id")
    suspend fun updateLastAccessed(id: Long, accessTime: Date)
    
    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun getBookmarkCount(): Int
    
    @Query("SELECT COUNT(*) FROM bookmarks WHERE textHash = :textHash")
    suspend fun countBookmarksForText(textHash: String): Int
    
    @Query("SELECT * FROM bookmarks WHERE textHash = :textHash AND isAutoBookmark = 1 ORDER BY createdAt DESC")
    suspend fun getAutoBookmarksForText(textHash: String): List<Bookmark>
    
    @Query("DELETE FROM bookmarks WHERE id IN (:bookmarkIds)")
    suspend fun deleteBookmarksByIds(bookmarkIds: List<Long>)

    @Query("DELETE FROM bookmarks WHERE textHash = :textHash")
    suspend fun deleteBookmarksByTextHash(textHash: String)
    
    @Query("SELECT * FROM bookmarks WHERE textHash = :textHash ORDER BY createdAt DESC")
    fun getBookmarksForText(textHash: String): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE textHash = :textHash ORDER BY createdAt DESC")
    suspend fun getBookmarksForTextOnce(textHash: String): List<Bookmark>
}
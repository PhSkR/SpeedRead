package com.speedread.rsvp.data.bookmark

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String, // First few words of the text for preview
    val textHash: String, // Hash of the full text to identify the same document
    val wordPosition: Int, // Current word index (kept for backward compatibility)
    val pageNumber: Int, // Page number (simplified bookmark reference)
    val totalWords: Int,
    val totalPages: Int, // Total pages in document
    val wpm: Int, // Reading speed when bookmarked
    val createdAt: Date,
    val lastAccessedAt: Date,
    val source: BookmarkSource, // Where the text came from
    val sourceUri: String? = null, // File URI if from file
    val isAutoBookmark: Boolean = false // True for auto-save on pause/stop
)

enum class BookmarkSource {
    MANUAL_TEXT,
    CLIPBOARD,
    FILE_PDF,
    FILE_EPUB,
    FILE_TXT,
    SHARED_TEXT,
    URL
}

data class BookmarkWithProgress(
    val bookmark: Bookmark,
    val progressPercentage: Float
) {
    companion object {
        fun from(bookmark: Bookmark): BookmarkWithProgress {
            val progress = if (bookmark.totalPages > 0) {
                (bookmark.pageNumber.toFloat() / bookmark.totalPages) * 100f
            } else 0f
            return BookmarkWithProgress(bookmark, progress)
        }
    }
}
package com.speedread.rsvp.data.document

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.speedread.rsvp.data.bookmark.BookmarkSource
import kotlinx.serialization.Serializable
import java.util.Date

enum class DocumentViewMode {
    TEXT,    // Always use text extraction
    PDF,     // Always use PDF rendering (if available)
    AUTO     // Try PDF first, fallback to text
}

@Serializable
data class PageBoundary(
    val pageNumber: Int,        // Original page number (1-based)
    val startWordIndex: Int,    // Word index where this page starts (0-based)
    val endWordIndex: Int,      // Word index where this page ends (0-based, inclusive)
    val wordCount: Int         // Number of words on this page
)

/**
 * Persisted mirror of the importer's FigureRegion (see data-importers FileParser.kt): a
 * figure detected on a source page, with a crop box normalized to the page (0..1, top-left
 * origin) and word indices in the same import-time counting as [PageBoundary].
 */
@Serializable
data class FigureRegion(
    val pageNumber: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val aspectRatio: Float,
    val anchorWordIndex: Int,
    val labelStartWordIndex: Int = -1,
    val labelEndWordIndex: Int = -1
)

@Entity(
    tableName = "saved_documents",
    indices = [Index(value = ["contentHash"])]
)
data class SavedDocument(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String = "", // Text content (empty for external files)
    val contentHash: String, // Hash for duplicate detection
    val isContentExternal: Boolean = false, // True if content stored as file
    val contentFilePath: String? = null, // Path to external content file
    val originalFileName: String? = null,
    val fileSize: Long = 0, // Size in bytes
    val wordCount: Int = 0,
    val source: BookmarkSource,
    val originalUri: String? = null,
    val mimeType: String? = null,
    val author: String? = null,
    val language: String? = null,
    val createdAt: Date,
    val lastAccessedAt: Date,
    val lastReadPosition: Int = 0, // Last word position when reading
    val readingProgress: Float = 0f, // Progress percentage (0-100)
    val isFavorite: Boolean = false,
    val tags: String = "", // Comma-separated tags (deprecated, use tags table instead)
    val category: String? = null, // Category for organization (e.g., "Work", "Personal", "Study")
    val pageCount: Int? = null, // Number of pages for PDF documents
    val pageBoundariesJson: String? = null, // JSON representation of List<PageBoundary> for PDFs
    val figureRegionsJson: String? = null // JSON representation of List<FigureRegion> for PDFs
    // Note: New fields below will be added in future database migration
    // val preferredViewMode: DocumentViewMode = DocumentViewMode.AUTO, // User's preferred viewing mode
    // val supportsPdfRendering: Boolean = false // Whether original PDF is available for rendering
)

data class SavedDocumentWithStats(
    val document: SavedDocument,
    val bookmarkCount: Int = 0,
    val lastReadPosition: Int = 0,
    val readingProgress: Float = 0f
)

enum class DocumentSortOrder {
    TITLE_ASC,
    TITLE_DESC,
    DATE_CREATED_ASC,
    DATE_CREATED_DESC,
    DATE_ACCESSED_ASC,
    DATE_ACCESSED_DESC,
    SIZE_ASC,
    SIZE_DESC,
    FAVORITES_FIRST
}

// Helper methods for working with page boundaries
fun SavedDocument.getPageBoundaries(): List<PageBoundary>? =
    DocumentJson.decodePageBoundaries(pageBoundariesJson)

fun SavedDocument.hasPageBoundaries(): Boolean = pageBoundariesJson != null

fun SavedDocument.getPageForWordIndex(wordIndex: Int): Int? {
    return getPageBoundaries()?.find { boundary ->
        wordIndex >= boundary.startWordIndex && wordIndex <= boundary.endWordIndex
    }?.pageNumber
}

fun SavedDocument.getWordIndexForPage(pageNumber: Int): Int? {
    return getPageBoundaries()?.find { it.pageNumber == pageNumber }?.startWordIndex
}
package com.speedread.rsvp.data.parser

import android.net.Uri
import kotlinx.serialization.Serializable
import java.io.InputStream

interface FileParser {
    fun canParse(mimeType: String?, fileExtension: String?): Boolean
    suspend fun parseFile(inputStream: InputStream): FileParseResult
    suspend fun parseFile(uri: Uri): FileParseResult
    fun getSupportedMimeTypes(): List<String>
    fun getSupportedExtensions(): List<String>
}

sealed class FileParseResult {
    data class Success(
        val text: String,
        val metadata: FileMetadata
    ) : FileParseResult()
    
    data class Error(
        val exception: Throwable,
        val message: String = exception.message ?: "Unknown error"
    ) : FileParseResult()
}

data class FileMetadata(
    val title: String? = null,
    val author: String? = null,
    val wordCount: Int = 0,
    val pageCount: Int? = null,
    val fileSize: Long = 0,
    val mimeType: String? = null,
    val language: String? = null,
    val pageBoundaries: List<PageBoundary>? = null,
    val figureRegions: List<FigureRegion>? = null
)

@Serializable
data class PageBoundary(
    val pageNumber: Int,        // Original page number (1-based)
    val startWordIndex: Int,    // Word index where this page starts (0-based)
    val endWordIndex: Int,      // Word index where this page ends (0-based, inclusive)
    val wordCount: Int         // Number of words on this page
)

/**
 * A figure (diagram, plate, illustration) detected on a source page during import. The
 * bounding box is normalized to the page crop box (0..1, top-left origin) so the region can
 * be re-rendered from the original PDF at any resolution. Word indices use the same
 * import-time counting as [PageBoundary], so consumers can anchor the figure in the text
 * flow and suppress the stray label tokens the figure contributed to the extracted text.
 */
@Serializable
data class FigureRegion(
    val pageNumber: Int,               // Source page (1-based), matches PageBoundary.pageNumber
    val left: Float,                   // Normalized bounding box, top-left origin, 0..1
    val top: Float,
    val width: Float,
    val height: Float,
    val aspectRatio: Float,            // Box height / width in page points — display sizing
    val anchorWordIndex: Int,          // Figure appears after this absolute word index (-1 = document start)
    val labelStartWordIndex: Int = -1, // Absolute range of stray label tokens inside the region
    val labelEndWordIndex: Int = -1    // (inclusive); -1/-1 when the figure contributed no tokens
)
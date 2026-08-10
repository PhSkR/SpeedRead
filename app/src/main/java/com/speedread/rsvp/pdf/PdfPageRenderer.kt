package com.speedread.rsvp.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.speedread.rsvp.Constants
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

data class RenderedPdfPage(
    val pageNumber: Int,
    val bitmap: Bitmap,
    val width: Int,
    val height: Int
)

@Singleton
class PdfPageRenderer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var currentPdfRenderer: PdfRenderer? = null
    private var currentPdfFile: ParcelFileDescriptor? = null
    private var currentTempFile: File? = null
    
    // Limit concurrent rendering to prevent memory issues
    private val renderingSemaphore = Semaphore(Constants.PDF_CONCURRENT_RENDERS)
    
    /**
     * Initialize PDF renderer with a PDF file
     */
    suspend fun initializePdf(inputStream: InputStream): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // Close any existing renderer
            closePdf()
            
            // Create temporary file for PDF rendering
            val tempFile = File.createTempFile("pdf_render", ".pdf", context.cacheDir)
            tempFile.deleteOnExit()
            
            // Copy input stream to temporary file
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            
            // Validate file was created successfully
            if (!tempFile.exists() || tempFile.length() == 0L) {
                tempFile.delete()
                throw IllegalStateException("Failed to create temporary PDF file")
            }
            
            // Open PDF with PdfRenderer
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            
            currentPdfFile = pfd
            currentPdfRenderer = renderer
            currentTempFile = tempFile
            
            Result.success(renderer.pageCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Render a specific PDF page to bitmap
     */
    suspend fun renderPage(pageIndex: Int, targetWidth: Int = Constants.PDF_RENDER_TARGET_WIDTH): Result<RenderedPdfPage> = withContext(Dispatchers.IO) {
        // Limit concurrent rendering
        renderingSemaphore.acquire()
        try {
            val renderer = currentPdfRenderer ?: return@withContext Result.failure(
                IllegalStateException("PDF not initialized")
            )
            
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                return@withContext Result.failure(
                    IndexOutOfBoundsException("Page $pageIndex out of range (0-${renderer.pageCount - 1})")
                )
            }
            
            // Validate targetWidth
            val safeTargetWidth = targetWidth.coerceIn(Constants.PDF_RENDER_MIN_WIDTH, Constants.PDF_RENDER_MAX_WIDTH)
            
            renderer.openPage(pageIndex).use { page ->
                // Calculate bitmap dimensions maintaining aspect ratio
                val aspectRatio = page.width.toFloat() / page.height.toFloat()
                val bitmapWidth = safeTargetWidth
                val bitmapHeight = (safeTargetWidth / aspectRatio).toInt()
                
                // Validate bitmap dimensions to prevent memory issues
                val totalPixels = bitmapWidth * bitmapHeight
                if (totalPixels > Constants.PDF_MAX_BITMAP_PIXELS) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Rendered page would be too large: ${bitmapWidth}x${bitmapHeight}")
                    )
                }
                
                // Create bitmap and render page. Uses Constants.PDF_RENDER_BITMAP_CONFIG
                // (RGB_565) for half the per-page memory of ARGB_8888 — PDF pages are
                // opaque, so the alpha channel is unused. The PdfRenderer documentation
                // states it expects a fully-opaque white-initialised bitmap, so we paint
                // the canvas white before rendering. Skipping this fill produces visible
                // artefacts under RGB_565 because that config cannot represent the
                // transparent default state ARGB_8888 happens to start in.
                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Constants.PDF_RENDER_BITMAP_CONFIG)
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                
                Result.success(
                    RenderedPdfPage(
                        pageNumber = pageIndex + 1,
                        bitmap = bitmap,
                        width = bitmapWidth,
                        height = bitmapHeight
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            renderingSemaphore.release()
        }
    }
    
    /**
     * Get the number of pages in the current PDF
     */
    fun getPageCount(): Int {
        return currentPdfRenderer?.pageCount ?: 0
    }
    
    /**
     * Check if a PDF is currently loaded
     */
    fun isPdfLoaded(): Boolean {
        return currentPdfRenderer != null
    }
    
    /**
     * Close the current PDF and free resources
     */
    fun closePdf() {
        try {
            currentPdfRenderer?.close()
        } catch (e: Exception) {
            Logger.w("PdfPageRenderer", "Error closing PDF renderer", e)
        }
        
        try {
            currentPdfFile?.close()
        } catch (e: Exception) {
            Logger.w("PdfPageRenderer", "Error closing PDF file descriptor", e)
        }
        
        // Clean up temporary file
        currentTempFile?.let { file ->
            try {
                if (file.exists()) {
                    val deleted = file.delete()
                    if (deleted) {
                        Logger.d("PdfPageRenderer", "Cleaned up temporary PDF file: ${file.name}")
                    } else {
                        Logger.w("PdfPageRenderer", "Failed to delete temporary PDF file: ${file.name}")
                    }
                } else {
                    Logger.d("PdfPageRenderer", "Temporary PDF file already deleted or doesn't exist")
                }
            } catch (e: Exception) {
                Logger.w("PdfPageRenderer", "Error deleting temporary PDF file", e)
            }
            Unit // Explicitly return Unit from let block
        }
        
        currentPdfRenderer = null
        currentPdfFile = null
        currentTempFile = null
    }
}

@HiltViewModel
class PdfPageViewModel @Inject constructor(
    private val pdfRenderer: PdfPageRenderer
) : ViewModel() {
    
    private val _renderedPages = MutableStateFlow<Map<Int, RenderedPdfPage>>(emptyMap())
    val renderedPages: StateFlow<Map<Int, RenderedPdfPage>> = _renderedPages.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount.asStateFlow()
    
    fun initializePdf(inputStream: InputStream) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            val result = pdfRenderer.initializePdf(inputStream)
            result.fold(
                onSuccess = { pageCount ->
                    _pageCount.value = pageCount
                    _isLoading.value = false
                },
                onFailure = { exception ->
                    _error.value = "Failed to load PDF: ${exception.message}"
                    _isLoading.value = false
                }
            )
        }
    }
    
    fun renderPage(pageIndex: Int, targetWidth: Int = Constants.PDF_RENDER_TARGET_WIDTH) {
        viewModelScope.launch {
            val result = pdfRenderer.renderPage(pageIndex, targetWidth)
            result.fold(
                onSuccess = { renderedPage ->
                    val currentPages = _renderedPages.value.toMutableMap()
                    currentPages[pageIndex] = renderedPage
                    _renderedPages.value = currentPages
                },
                onFailure = { exception ->
                    _error.value = "Failed to render page ${pageIndex + 1}: ${exception.message}"
                }
            )
        }
    }
    
    fun preloadPages(startIndex: Int, count: Int = 3, targetWidth: Int = Constants.PDF_RENDER_TARGET_WIDTH) {
        viewModelScope.launch {
            val endIndex = (startIndex + count).coerceAtMost(_pageCount.value)
            
            for (i in startIndex until endIndex) {
                if (!_renderedPages.value.containsKey(i)) {
                    renderPage(i, targetWidth)
                }
            }
        }
    }
    
    fun clearError() {
        _error.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        pdfRenderer.closePdf()
    }
}
package com.speedread.rsvp.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.speedread.rsvp.Constants
import com.speedread.rsvp.data.document.FigureRegion
import com.speedread.rsvp.data.document.SavedDocument
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders and caches inline-figure bitmaps for Page View.
 *
 * Layers: an in-memory LRU (shared across holders, byte-bounded), a WebP disk cache under
 * `filesDir/figures/<contentHash>/`, and — on a full miss — a render of the figure's crop
 * box from the ORIGINAL PDF via android.graphics.pdf.PdfRenderer. A figure therefore
 * renders once per install; after that the disk cache serves it even if the original PDF's
 * URI permission has lapsed. Render failures return null and the caller keeps its
 * placeholder — never an exception into the UI.
 *
 * Independent of [PdfPageRenderer] on purpose: that singleton holds the single document
 * open for the native PDF page mode, while this store opens the PDF transiently per cache
 * miss (misses are once-ever per figure) so text-mode reading never contends with it.
 */
@Singleton
class FigureImageStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val memoryCache = object : LruCache<String, Bitmap>(Constants.FIGURE_MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // Serializes PdfRenderer open/render/close; the framework renderer is not thread-safe
    // and figure misses for one document tend to arrive in a burst on first open.
    private val renderMutex = Mutex()

    /**
     * Figure bitmap for [region] (identified by its [figureIndex] in the document's region
     * list). Memory hit is synchronous-fast; disk/render run on IO. Null when the figure
     * cannot be produced (no original PDF access and no cached file).
     */
    suspend fun getFigureBitmap(
        document: SavedDocument,
        region: FigureRegion,
        figureIndex: Int
    ): Bitmap? {
        val key = "${document.contentHash}:$figureIndex"
        memoryCache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val file = figureFile(document.contentHash, figureIndex)
            val fromDisk = if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else {
                null
            }
            val bitmap = fromDisk ?: renderAndPersist(document, region, file)
            if (bitmap != null) memoryCache.put(key, bitmap)
            bitmap
        }
    }

    /**
     * Full source page render for the tap-to-zoom viewer. Not disk-cached (one-off view);
     * null when the original PDF is inaccessible.
     */
    suspend fun renderFullPage(document: SavedDocument, pageNumber: Int): Bitmap? {
        return withContext(Dispatchers.IO) {
            renderMutex.withLock {
                withRenderer(document) { renderer ->
                    val pageIndex = pageNumber - 1
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withRenderer null
                    renderer.openPage(pageIndex).use { page ->
                        val width = Constants.FIGURE_PAGE_RENDER_WIDTH
                        val height = (width.toFloat() * page.height / page.width).toInt()
                        if (width.toLong() * height > Constants.PDF_MAX_BITMAP_PIXELS) return@withRenderer null
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }
    }

    /**
     * Purges cached figures for [contentHash]: deletes cached PDF copies in cacheDir,
     * figure WebP crops directory in filesDir, and clears in-memory bitmaps.
     */
    fun deleteFiguresForDocument(contentHash: String) {
        try {
            val hashPrefix = contentHash.take(HASH_PREFIX_LENGTH)
            val pdfCopy = File(context.cacheDir, "${Constants.FIGURE_CACHE_DIR}_src_$hashPrefix.pdf")
            if (pdfCopy.exists()) {
                pdfCopy.delete()
            }
            val cropsDir = File(File(context.filesDir, Constants.FIGURE_CACHE_DIR), hashPrefix)
            if (cropsDir.exists()) {
                cropsDir.deleteRecursively()
            }
            memoryCache.snapshot().keys.forEach { key ->
                if (key.startsWith("$contentHash:")) {
                    memoryCache.remove(key)
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to delete figures for document $contentHash", e)
        }
    }

    private suspend fun renderAndPersist(
        document: SavedDocument,
        region: FigureRegion,
        file: File
    ): Bitmap? {
        return renderMutex.withLock {
            // Another miss for the same figure may have rendered while we waited.
            if (file.exists()) return@withLock BitmapFactory.decodeFile(file.absolutePath)

            val bitmap = withRenderer(document) { renderer ->
                val pageIndex = region.pageNumber - 1
                if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withRenderer null
                renderer.openPage(pageIndex).use { page ->
                    renderRegion(page, region)
                }
            } ?: return@withLock null

            try {
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { out ->
                    bitmap.compress(webpFormat(), Constants.FIGURE_WEBP_QUALITY, out)
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to persist figure cache ${file.name}", e)
                file.delete()
            }
            bitmap
        }
    }

    /**
     * Renders just the region's crop box: the Matrix scales page points so the crop lands at
     * [Constants.FIGURE_RENDER_TARGET_WIDTH] px wide and translates its top-left corner to
     * the bitmap origin — no full-page intermediate bitmap.
     */
    private fun renderRegion(page: PdfRenderer.Page, region: FigureRegion): Bitmap? {
        val regionWidthPts = region.width * page.width
        val regionHeightPts = region.height * page.height
        if (regionWidthPts <= 0f || regionHeightPts <= 0f) return null

        var scale = Constants.FIGURE_RENDER_TARGET_WIDTH / regionWidthPts
        var outWidth = (regionWidthPts * scale).toInt().coerceAtLeast(1)
        var outHeight = (regionHeightPts * scale).toInt().coerceAtLeast(1)
        if (outWidth.toLong() * outHeight > Constants.FIGURE_MAX_BITMAP_PIXELS) {
            val shrink = kotlin.math.sqrt(
                Constants.FIGURE_MAX_BITMAP_PIXELS.toDouble() / (outWidth.toLong() * outHeight)
            ).toFloat()
            scale *= shrink
            outWidth = (regionWidthPts * scale).toInt().coerceAtLeast(1)
            outHeight = (regionHeightPts * scale).toInt().coerceAtLeast(1)
        }

        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(-region.left * page.width * scale, -region.top * page.height * scale)
        }
        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    /**
     * Opens the document's ORIGINAL PDF transiently. The content stream is copied once per
     * document to a cache file keyed by contentHash (so repeated misses don't re-copy a
     * large PDF), then opened via ParcelFileDescriptor for the duration of [block].
     */
    private inline fun <T> withRenderer(document: SavedDocument, block: (PdfRenderer) -> T?): T? {
        val uriString = document.originalUri ?: run {
            Logger.w(TAG, "No original PDF URI for '${document.title}'; cannot render figures")
            return null
        }
        return try {
            val pdfFile = localPdfCopy(document.contentHash, uriString) ?: return null
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer -> block(renderer) }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Figure render failed for '${document.title}'", e)
            null
        }
    }

    private fun localPdfCopy(contentHash: String, uriString: String): File? {
        val target = File(context.cacheDir, "${Constants.FIGURE_CACHE_DIR}_src_${contentHash.take(HASH_PREFIX_LENGTH)}.pdf")
        if (target.exists() && target.length() > 0) return target
        return try {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
                target
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Cannot open original PDF ($uriString)", e)
            target.delete()
            null
        }
    }

    private fun figureFile(contentHash: String, figureIndex: Int): File =
        File(File(context.filesDir, Constants.FIGURE_CACHE_DIR), "${contentHash.take(HASH_PREFIX_LENGTH)}/f$figureIndex.webp")

    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

    private companion object {
        const val TAG = "FigureImageStore"
        const val HASH_PREFIX_LENGTH = 16
    }
}

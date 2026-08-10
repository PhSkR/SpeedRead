package com.speedread.rsvp.data.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfFileParser @Inject constructor(
    @ApplicationContext private val context: Context
) : FileParser {
    
    init {
        // Initialize PDFBox for Android
        PDFBoxResourceLoader.init(context)
    }
    
    override fun canParse(mimeType: String?, fileExtension: String?): Boolean {
        return mimeType == "application/pdf" || fileExtension == "pdf"
    }
    
    override suspend fun parseFile(inputStream: InputStream): FileParseResult {
        return withContext(Dispatchers.IO) {
            try {
                // Configure PDFBox for memory efficiency
                System.setProperty("org.apache.pdfbox.rendering.UsePureJavaCMYKConversion", "true")
                
                val document = try {
                    PDDocument.load(inputStream, createMemoryUsageSetting())
                } catch (e: OutOfMemoryError) {
                    // Fallback: try loading without memory settings.
                    PDDocument.load(inputStream)
                }
                val result = extractTextAndMetadata(document)
                document.close()
                result
            } catch (e: OutOfMemoryError) {
                FileParseResult.Error(e, "PDF file is too large for available memory. Try a smaller file or restart the app.")
            } catch (e: Exception) {
                FileParseResult.Error(
                    e,
                    ImportErrorTranslator.translate(
                        throwable = e,
                        fallback = "Failed to parse PDF: ${e.message}"
                    )
                )
            }
        }
    }

    override suspend fun parseFile(uri: Uri): FileParseResult {
        return withContext(Dispatchers.IO) {
            try {
                // Pre-check file size to avoid loading huge files
                val fileSize = try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { 
                        it.length 
                    } ?: -1
                } catch (e: Exception) {
                    -1
                }
                
                // Reject files larger than 100MB to prevent OOM
                if (fileSize > 100_000_000) {
                    return@withContext FileParseResult.Error(
                        IllegalArgumentException("File too large"),
                        "PDF file is too large (${fileSize / 1_000_000}MB). Maximum supported size is 100MB."
                    )
                }
                
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    parseFile(inputStream)
                } ?: FileParseResult.Error(
                    IllegalArgumentException("Cannot open file"),
                    "Unable to open file from URI"
                )
            } catch (e: OutOfMemoryError) {
                FileParseResult.Error(e, "PDF file is too large for available memory. Try a smaller file or restart the app.")
            } catch (e: Exception) {
                FileParseResult.Error(
                    e,
                    ImportErrorTranslator.translate(
                        throwable = e,
                        sourceUriString = uri.toString(),
                        fallback = "Failed to open PDF file: ${e.message}"
                    )
                )
            }
        }
    }

    override fun getSupportedMimeTypes(): List<String> {
        return listOf("application/pdf")
    }
    
    override fun getSupportedExtensions(): List<String> {
        return listOf("pdf")
    }
    
    /**
     * Create memory usage settings optimized for mobile devices
     */
    private fun createMemoryUsageSetting(): MemoryUsageSetting {
        return MemoryUsageSetting.setupTempFileOnly()
    }
    
    /**
     * Best-effort figure detection for one page. Never fails the import: any exception (or
     * a word-count mismatch between line geometry and the page word counting) degrades to
     * "no figures on this page". Pages with a non-zero rotation are skipped — the normalized
     * box math assumes an upright page and a wrong crop is worse than no figure.
     *
     * Returned word indices are absolute (document-wide), converted from the detector's
     * page-relative offsets via [startWordIndex]: `anchorWordIndex` is the index of the word
     * the figure follows (-1 when the figure precedes the document's first word).
     */
    private fun detectFiguresOnPage(
        document: PDDocument,
        pageNum: Int,
        collectedLines: List<LineGeometryTextStripper.RawLine>,
        pageWordCount: Int,
        startWordIndex: Int
    ): List<FigureRegion> {
        return try {
            val page = document.getPage(pageNum - 1)
            if (page.rotation != 0) return emptyList()
            val cropBox = page.cropBox
            if (cropBox.width <= 0f || cropBox.height <= 0f) return emptyList()

            val lines = collectedLines.map { raw ->
                FigureRegionDetector.TextLine(
                    left = raw.left,
                    top = raw.top,
                    right = raw.right,
                    bottom = raw.bottom,
                    wordCount = raw.text.trim()
                        .replace('\r', ' ')
                        .replace('\n', ' ')
                        .split(' ')
                        .count { it.isNotBlank() },
                    gibberish = FigureRegionDetector.isGibberish(raw.text),
                    numericOnly = FigureRegionDetector.isBareNumber(raw.text)
                )
            }
            // Geometry-to-text alignment guard: label/anchor word offsets are only valid
            // when the per-line counting reproduces the page counting exactly.
            if (lines.sumOf { it.wordCount } != pageWordCount) return emptyList()

            val images = try {
                val collector = ImageRegionCollector(
                    pageHeight = cropBox.height,
                    pageOffsetX = cropBox.lowerLeftX,
                    pageOffsetY = cropBox.lowerLeftY
                )
                collector.processPage(page)
                collector.images.map {
                    FigureRegionDetector.ImageBox(it.left, it.top, it.right, it.bottom)
                }
            } catch (e: Exception) {
                Log.w("PdfFileParser", "Image scan failed on page $pageNum; text-only detection", e)
                emptyList()
            }

            FigureRegionDetector.detect(cropBox.width, cropBox.height, lines, images).map { fig ->
                FigureRegion(
                    pageNumber = pageNum,
                    left = fig.left,
                    top = fig.top,
                    width = fig.width,
                    height = fig.height,
                    aspectRatio = fig.aspectRatio,
                    anchorWordIndex = startWordIndex + fig.anchorWordOffset - 1,
                    labelStartWordIndex = if (fig.labelWordStart >= 0) startWordIndex + fig.labelWordStart else -1,
                    labelEndWordIndex = if (fig.labelWordEnd >= 0) startWordIndex + fig.labelWordEnd else -1
                )
            }
        } catch (e: Exception) {
            Log.w("PdfFileParser", "Figure detection failed on page $pageNum", e)
            emptyList()
        }
    }

    private suspend fun extractTextAndMetadata(document: PDDocument): FileParseResult {
        return withContext(Dispatchers.Default) {
            try {
                val totalPages = document.numberOfPages
                
                // Limit page processing for very large documents to prevent OOM
                val maxPages = if (totalPages > 1000) {
                    // For very large documents, only process first 1000 pages
                    1000
                } else {
                    totalPages
                }
                
                val allText = StringBuilder()
                val pageBoundaries = mutableListOf<PageBoundary>()
                val figureRegions = mutableListOf<FigureRegion>()
                var currentWordIndex = 0

                // Extract text page by page to track boundaries
                for (pageNum in 1..maxPages) {
                    try {
                        // Subclass keeps the output text byte-identical to PDFTextStripper
                        // while also collecting per-line geometry for figure detection.
                        val stripper = LineGeometryTextStripper()
                        stripper.sortByPosition = true // Enable visual coordinate sorting & column detection for multi-column layouts
                        stripper.paragraphEnd = "\n\n"
                        stripper.startPage = pageNum
                        stripper.endPage = pageNum

                        val pageText = stripper.getText(document)

                        // Skip empty pages to save memory
                        if (pageText.isBlank()) continue

                        val pageWords = pageText.trim()
                            .replace('\r', ' ')
                            .replace('\n', ' ')
                            .split(' ')
                            .filter { it.isNotBlank() }

                        if (pageWords.isNotEmpty()) {
                            val startWordIndex = currentWordIndex
                            val endWordIndex = currentWordIndex + pageWords.size - 1

                            pageBoundaries.add(
                                PageBoundary(
                                    pageNumber = pageNum,
                                    startWordIndex = startWordIndex,
                                    endWordIndex = endWordIndex,
                                    wordCount = pageWords.size
                                )
                            )

                            figureRegions.addAll(
                                detectFiguresOnPage(
                                    document = document,
                                    pageNum = pageNum,
                                    collectedLines = stripper.collectedLines,
                                    pageWordCount = pageWords.size,
                                    startWordIndex = startWordIndex
                                )
                            )

                            currentWordIndex += pageWords.size
                        }
                        
                        // Add page text to full text with memory management
                        if (allText.isNotEmpty() && pageText.isNotBlank()) {
                            allText.append(" ")
                        }
                        allText.append(pageText.trim())

                    } catch (e: OutOfMemoryError) {
                        Log.w("PdfFileParser", "OOM on page $pageNum; returning partial text", e)
                        break
                    } catch (e: Exception) {
                        Log.w("PdfFileParser", "Skipping page $pageNum due to extraction error", e)
                        continue
                    }
                }

                val fullText = allText.toString()

                // Try to extract document metadata
                val documentInfo = try {
                    document.documentInformation
                } catch (e: Exception) {
                    Log.w("PdfFileParser", "Could not read PDF document information", e)
                    null
                }
                
                val metadata = FileMetadata(
                    title = documentInfo?.title?.takeIf { it.isNotBlank() },
                    author = documentInfo?.author?.takeIf { it.isNotBlank() },
                    wordCount = currentWordIndex,
                    pageCount = totalPages,
                    mimeType = "application/pdf",
                    pageBoundaries = pageBoundaries,
                    figureRegions = figureRegions.takeIf { it.isNotEmpty() }
                )
                
                if (fullText.isBlank()) {
                    FileParseResult.Error(
                        IllegalStateException("PDF contains no readable text"),
                        "The PDF file appears to contain no text or only images"
                    )
                } else {
                    FileParseResult.Success(fullText.trim(), metadata)
                }
            } catch (e: OutOfMemoryError) {
                FileParseResult.Error(e, "PDF file is too large for available memory. Try a smaller file or restart the app.")
            } catch (e: Exception) {
                FileParseResult.Error(e, "Failed to extract text from PDF: ${e.message}")
            }
        }
    }
}
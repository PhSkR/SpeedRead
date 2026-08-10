package com.speedread.rsvp.data

import android.content.Context
import android.net.Uri
import com.speedread.rsvp.data.parser.FileParseResult
import com.speedread.rsvp.data.parser.FileParserRegistry
import com.speedread.rsvp.data.parser.ImportErrorTranslator
import com.speedread.rsvp.data.parser.UrlFileParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileTextProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileParserRegistry: FileParserRegistry,
    private val urlFileParser: UrlFileParser
) : TextProvider {

    override suspend fun getText(source: TextSource): Result<String> {
        return when (source) {
            is TextSource.File -> parseFile(source.uri)
            is TextSource.Url -> when (val result = parseUrl(source.url)) {
                is FileParseResult.Success -> Result.success(result.text)
                is FileParseResult.Error -> Result.failure(result.exception)
            }
            else -> Result.failure(IllegalArgumentException("FileTextProvider only supports File and Url sources"))
        }
    }

    override fun getSupportedSources(): List<TextSource> {
        return listOf(TextSource.File(Uri.EMPTY)) // Placeholder, actual URIs provided at runtime
    }

    /**
     * Fetch and extract readable text from a web URL. Delegates to UrlFileParser, which
     * owns scheme validation, timeouts, and content-type branching (HTML via Readability4J,
     * text/plain verbatim, PDF delegated to PdfFileParser).
     */
    suspend fun parseUrl(url: String): FileParseResult {
        return withContext(Dispatchers.IO) {
            try {
                urlFileParser.parseFile(Uri.parse(url))
            } catch (e: Exception) {
                FileParseResult.Error(
                    e,
                    ImportErrorTranslator.translate(
                        throwable = e,
                        sourceUriString = url,
                        fallback = "Failed to fetch URL: ${e.message}"
                    )
                )
            }
        }
    }
    
    suspend fun parseFile(uri: Uri): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val mimeType = context.contentResolver.getType(uri)
                val extension = getFileExtension(uri)
                
                val parser = fileParserRegistry.findParser(mimeType, extension)
                    ?: return@withContext Result.failure(
                        UnsupportedOperationException("Unsupported file format: $mimeType, .$extension")
                    )
                
                when (val result = parser.parseFile(uri)) {
                    is FileParseResult.Success -> Result.success(result.text)
                    is FileParseResult.Error -> Result.failure(result.exception)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    suspend fun parseFileWithMetadata(uri: Uri): FileParseResult {
        return withContext(Dispatchers.IO) {
            try {
                val mimeType = context.contentResolver.getType(uri)
                val extension = getFileExtension(uri)
                
                val parser = fileParserRegistry.findParser(mimeType, extension)
                    ?: return@withContext FileParseResult.Error(
                        UnsupportedOperationException("Unsupported file format: $mimeType, .$extension"),
                        "Unsupported file format"
                    )
                
                parser.parseFile(uri)
            } catch (e: Exception) {
                FileParseResult.Error(
                    e,
                    ImportErrorTranslator.translate(
                        throwable = e,
                        sourceUriString = uri.toString(),
                        fallback = "Failed to parse file: ${e.message}"
                    )
                )
            }
        }
    }
    
    fun getSupportedFileTypes(): List<String> {
        return fileParserRegistry.getSupportedExtensions()
    }
    
    fun getSupportedMimeTypes(): List<String> {
        return fileParserRegistry.getSupportedMimeTypes()
    }
    
    private fun getFileExtension(uri: Uri): String? {
        val path = uri.path ?: return null
        val lastDot = path.lastIndexOf('.')
        return if (lastDot != -1 && lastDot < path.length - 1) {
            path.substring(lastDot + 1).lowercase()
        } else null
    }
}
package com.speedread.rsvp.data.parser

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject

class TxtFileParser @Inject constructor(
    @ApplicationContext private val context: Context
) : FileParser {
    
    override fun canParse(mimeType: String?, fileExtension: String?): Boolean {
        return mimeType == "text/plain" || 
               mimeType == "text/txt" || 
               fileExtension?.lowercase() == "txt"
    }
    
    override fun getSupportedMimeTypes(): List<String> {
        return listOf("text/plain", "text/txt")
    }
    
    override fun getSupportedExtensions(): List<String> {
        return listOf("txt")
    }
    
    override suspend fun parseFile(inputStream: InputStream): FileParseResult {
        return withContext(Dispatchers.IO) {
            try {
                val text = inputStream.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                        reader.readText()
                    }
                }
                
                if (text.isBlank()) {
                    return@withContext FileParseResult.Error(
                        Exception("File appears to be empty"),
                        "File appears to be empty"
                    )
                }
                
                val wordCount = countWords(text)
                val fileSize = text.length.toLong()
                val title = extractTitleFromContent(text)
                
                val metadata = FileMetadata(
                    title = title,
                    author = null,
                    wordCount = wordCount,
                    pageCount = null,
                    fileSize = fileSize,
                    mimeType = "text/plain",
                    language = detectLanguage(text)
                )
                
                FileParseResult.Success(text, metadata)

            } catch (e: Exception) {
                FileParseResult.Error(
                    e,
                    ImportErrorTranslator.translate(
                        throwable = e,
                        fallback = "Error reading TXT file: ${e.message}"
                    )
                )
            }
        }
    }

    override suspend fun parseFile(uri: Uri): FileParseResult {
        return withContext(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                val inputStream = contentResolver.openInputStream(uri)
                    ?: return@withContext FileParseResult.Error(
                        Exception("Unable to open file"),
                        "Unable to open file"
                    )

                return@withContext parseFile(inputStream)

            } catch (e: Exception) {
                FileParseResult.Error(
                    e,
                    ImportErrorTranslator.translate(
                        throwable = e,
                        sourceUriString = uri.toString(),
                        fallback = "Error reading TXT file: ${e.message}"
                    )
                )
            }
        }
    }
    
    private fun extractTitleFromContent(text: String): String {
        // Try to use the first non-empty line as title if it's short enough
        val firstLine = text.lines().firstOrNull { it.trim().isNotEmpty() }?.trim()
        
        return when {
            // If first line is reasonable length for a title, use it
            firstLine != null && firstLine.length in 1..100 && !firstLine.contains('\n') -> firstLine
            // Fallback
            else -> "Text Document"
        }
    }
    
    private fun countWords(text: String): Int {
        return text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
    }
    
    private fun detectLanguage(text: String): String? {
        // Simple language detection based on common patterns
        // This is very basic - you could integrate a proper language detection library
        val sample = text.take(1000).lowercase()
        
        return when {
            sample.contains(Regex("\\b(the|and|or|but|in|on|at|to|for|of|with|by)\\b")) -> "en"
            sample.contains(Regex("\\b(der|die|das|und|oder|aber|in|an|zu|für|von|mit|bei)\\b")) -> "de"
            sample.contains(Regex("\\b(le|la|les|et|ou|mais|dans|sur|à|pour|de|avec|par)\\b")) -> "fr"
            sample.contains(Regex("\\b(el|la|los|las|y|o|pero|en|sobre|a|para|de|con|por)\\b")) -> "es"
            else -> null // Unknown language
        }
    }
}
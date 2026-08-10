package com.speedread.rsvp.data.parser

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileParserRegistry @Inject constructor(
    private val parsers: Set<@JvmSuppressWildcards FileParser>
) {
    
    fun findParser(mimeType: String?, fileExtension: String?): FileParser? {
        return parsers.firstOrNull { parser ->
            parser.canParse(mimeType, fileExtension)
        }
    }
    
    fun findParser(uri: Uri): FileParser? {
        val extension = getFileExtension(uri)
        return findParser(null, extension)
    }
    
    fun getSupportedMimeTypes(): List<String> {
        return parsers.flatMap { it.getSupportedMimeTypes() }.distinct()
    }
    
    fun getSupportedExtensions(): List<String> {
        return parsers.flatMap { it.getSupportedExtensions() }.distinct()
    }
    
    private fun getFileExtension(uri: Uri): String? {
        val path = uri.path ?: return null
        val lastDot = path.lastIndexOf('.')
        return if (lastDot != -1 && lastDot < path.length - 1) {
            path.substring(lastDot + 1).lowercase()
        } else null
    }
}
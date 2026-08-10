package com.speedread.rsvp.data

import android.net.Uri

sealed class TextSource {
    object Clipboard : TextSource()
    data class ShareIntent(val text: String) : TextSource()
    data class File(val uri: Uri) : TextSource()
    data class Url(val url: String) : TextSource()
}

interface TextProvider {
    suspend fun getText(source: TextSource): Result<String>
    fun getSupportedSources(): List<TextSource>
}

sealed class TextImportResult {
    data class Success(val text: String, val metadata: TextMetadata? = null) : TextImportResult()
    data class Error(val exception: Throwable) : TextImportResult()
}

data class TextMetadata(
    val title: String? = null,
    val wordCount: Int,
    val estimatedReadingTime: Int // in minutes
)
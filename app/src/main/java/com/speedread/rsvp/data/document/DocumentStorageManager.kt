package com.speedread.rsvp.data.document

import android.content.Context
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentStorageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val INLINE_SIZE_LIMIT = 1_048_576 // 1MB - store in database
        const val FILE_SIZE_LIMIT = 104_857_600   // 100MB - store as file (very generous limit)
        private const val DOCUMENTS_DIR = "documents"
    }
    
    private val documentsDir: File by lazy {
        File(context.filesDir, DOCUMENTS_DIR).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    /**
     * Determines storage strategy and prepares document for saving
     */
    suspend fun prepareDocumentForStorage(
        @Suppress("UNUSED_PARAMETER") title: String,
        content: String,
        @Suppress("UNUSED_PARAMETER") originalFileName: String? = null,
        contentHash: String
    ): DocumentStorageResult = withContext(Dispatchers.IO) {
        val textSizeBytes = content.toByteArray(Charsets.UTF_8).size
        
        when {
            textSizeBytes <= INLINE_SIZE_LIMIT -> {
                // Small files: store directly in database
                DocumentStorageResult.Inline(content)
            }
            textSizeBytes <= FILE_SIZE_LIMIT -> {
                // Large files: store as external file
                val fileId = generateFileId(contentHash)
                val filePath = saveContentToFile(content, fileId)
                DocumentStorageResult.External(filePath, textSizeBytes)
            }
            else -> {
                val sizeMB = textSizeBytes / (1024.0 * 1024.0)
                throw DocumentTooLargeException("Document exceeds 100MB limit (${String.format(Locale.US, "%.1f", sizeMB)}MB)")
            }
        }
    }
    
    /**
     * Load document content from storage (database or file)
     */
    suspend fun loadDocumentContent(document: SavedDocument): String = withContext(Dispatchers.IO) {
        if (document.isContentExternal && !document.contentFilePath.isNullOrBlank()) {
            loadContentFromFile(document.contentFilePath)
        } else {
            document.content
        }
    }
    
    /**
     * Delete document content file if it exists
     */
    suspend fun deleteDocumentContent(document: SavedDocument): Boolean = withContext(Dispatchers.IO) {
        if (document.isContentExternal && !document.contentFilePath.isNullOrBlank()) {
            try {
                val file = File(document.contentFilePath)
                file.delete()
            } catch (e: Exception) {
                Logger.e("DocumentStorageManager", "Failed to delete content file ${document.contentFilePath}", e)
                false
            }
        } else {
            true // No file to delete for inline content
        }
    }
    
    /**
     * Get storage statistics
     */
    suspend fun getStorageStats(): ExternalStorageStats = withContext(Dispatchers.IO) {
        val files = documentsDir.listFiles() ?: emptyArray()
        val totalFileSize = files.sumOf { it.length() }
        val fileCount = files.size
        
        ExternalStorageStats(
            externalFileCount = fileCount,
            externalFileSizeBytes = totalFileSize,
            documentsDirectory = documentsDir.absolutePath
        )
    }
    
    /**
     * Clean up orphaned files (files without corresponding database entries)
     */
    suspend fun cleanupOrphanedFiles(validFilePaths: Set<String>): Int = withContext(Dispatchers.IO) {
        val files = documentsDir.listFiles() ?: return@withContext 0
        var deletedCount = 0
        
        files.forEach { file ->
            if (file.isFile && file.absolutePath !in validFilePaths) {
                try {
                    if (file.delete()) {
                        deletedCount++
                    }
                } catch (e: Exception) {
                    Logger.w("DocumentStorageManager", "Failed to delete orphaned file ${file.name}; continuing cleanup", e)
                }
            }
        }
        
        deletedCount
    }
    
    private suspend fun saveContentToFile(content: String, fileId: String): String = withContext(Dispatchers.IO) {
        val file = File(documentsDir, "${fileId}.txt")
        
        try {
            file.writeText(content, Charsets.UTF_8)
            file.absolutePath
        } catch (e: IOException) {
            throw DocumentStorageException("Failed to save document to file: ${e.message}", e)
        }
    }
    
    private suspend fun loadContentFromFile(filePath: String): String = withContext(Dispatchers.IO) {
        try {
            File(filePath).readText(Charsets.UTF_8)
        } catch (e: IOException) {
            throw DocumentStorageException("Failed to load document from file: ${e.message}", e)
        }
    }
    
    private fun generateFileId(contentHash: String): String {
        // Use first 8 characters of content hash + UUID for uniqueness
        val shortHash = contentHash.take(8)
        val uuid = UUID.randomUUID().toString().replace("-", "").take(8)
        return "doc_${shortHash}_${uuid}"
    }
}

/**
 * Result of preparing document for storage
 */
sealed class DocumentStorageResult {
    data class Inline(val content: String) : DocumentStorageResult()
    data class External(val filePath: String, val sizeBytes: Int) : DocumentStorageResult()
}

/**
 * External file storage statistics for monitoring
 */
data class ExternalStorageStats(
    val externalFileCount: Int,
    val externalFileSizeBytes: Long,
    val documentsDirectory: String
) {
    val externalFileSizeMB: Float get() = externalFileSizeBytes / (1024f * 1024f)
}

/**
 * Exception thrown when document is too large to store
 */
class DocumentTooLargeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Exception thrown when file storage operations fail
 */
class DocumentStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)
package com.speedread.rsvp.data

import com.speedread.rsvp.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles chunking large documents into smaller pieces to avoid memory issues
 */
class DocumentChunker {
    
    companion object {
        // Constants for document chunking
        const val DEFAULT_CHUNK_SIZE = Constants.DEFAULT_CHUNK_SIZE_CHARS
        const val MAX_CHUNK_SIZE = Constants.MAX_CHUNK_SIZE_CHARS
        const val MIN_CHUNK_SIZE = Constants.MIN_CHUNK_SIZE_CHARS

        private val DELIMITERS = charArrayOf(' ', '\n', '.', '?', '!', ',', ';', ':', '\t')
    }
    
    data class DocumentChunk(
        val text: String,
        val startIndex: Int,
        val endIndex: Int,
        val chunkIndex: Int,
        val totalChunks: Int
    )
    
    /**
     * Splits a large document into chunks to avoid memory issues
     */
    suspend fun chunkDocument(document: String, chunkSize: Int = DEFAULT_CHUNK_SIZE): List<DocumentChunk> = withContext(Dispatchers.Default) {
        if (document.isEmpty()) return@withContext emptyList()
        
        // Ensure chunk size is within valid bounds
        val safeChunkSize = chunkSize.coerceIn(MIN_CHUNK_SIZE, MAX_CHUNK_SIZE)
        
        val chunks = mutableListOf<DocumentChunk>()
        var startIndex = 0
        var chunkIndex = 0
        
        while (startIndex < document.length) {
            // Determine end index, but try to break at a word boundary
            var endIndex = (startIndex + safeChunkSize).coerceAtMost(document.length)
            
            // If we're not at the end, try to find a word boundary
            if (endIndex < document.length) {
                // Look for a good break point (space, period, newline, etc.)
                var breakIndex = -1
                for (i in endIndex downTo startIndex + (safeChunkSize / 2)) {
                    if (i < document.length && document[i] in DELIMITERS) {
                        breakIndex = i + 1 // Include the delimiter
                        break
                    }
                }
                
                // If we found a good break point, use it; otherwise just break at the original index
                if (breakIndex > startIndex) {
                    endIndex = breakIndex
                }
            }
            
            val chunkText = document.substring(startIndex, endIndex)
            chunks.add(
                DocumentChunk(
                    text = chunkText,
                    startIndex = startIndex,
                    endIndex = endIndex,
                    chunkIndex = chunkIndex,
                    totalChunks = -1 // Will be set after all chunks are created
                )
            )
            
            startIndex = endIndex
            chunkIndex++
        }
        
        // Set the total chunks count for all chunks
        return@withContext chunks.map { it.copy(totalChunks = chunks.size) }
    }
    
    /**
     * Joins document chunks back into the full document
     */
    fun joinChunks(chunks: List<DocumentChunk>): String {
        return chunks.joinToString(separator = "") { it.text }
    }
    
    /**
     * Gets the chunk index for a specific character position in the document
     */
    fun getChunkIndexForPosition(chunks: List<DocumentChunk>, position: Int): Int {
        return chunks.indexOfFirst { chunk ->
            position >= chunk.startIndex && position < chunk.endIndex
        }.coerceAtLeast(0)
    }
    
    /**
     * Converts a global document position to a chunk-relative position
     */
    fun getChunkRelativePosition(chunks: List<DocumentChunk>, globalPosition: Int): Pair<Int, Int>? {
        val chunkIndex = getChunkIndexForPosition(chunks, globalPosition)
        if (chunkIndex >= 0 && chunkIndex < chunks.size) {
            val chunk = chunks[chunkIndex]
            val relativePosition = globalPosition - chunk.startIndex
            return Pair(chunkIndex, relativePosition)
        }
        return null
    }
    
    /**
     * Converts a chunk-relative position back to a global document position
     */
    fun getGlobalPosition(chunkIndex: Int, relativePosition: Int, chunks: List<DocumentChunk>): Int {
        if (chunkIndex < 0 || chunkIndex >= chunks.size) return -1
        val chunk = chunks[chunkIndex]
        return chunk.startIndex + relativePosition
    }
}
package com.speedread.rsvp.data.tags

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepository @Inject constructor(
    private val tagDao: TagDao
) {
    
    fun getAllTags(): Flow<List<Tag>> = tagDao.getAllTags()
    
    fun getTagsWithUsageCount(): Flow<List<TagWithUsageCount>> = tagDao.getTagsWithUsageCount()
    
    fun getTagsForDocument(documentId: Long): Flow<List<Tag>> = tagDao.getTagsForDocument(documentId)
    
    fun getDocumentsByTags(tagIds: List<Long>): Flow<List<com.speedread.rsvp.data.document.SavedDocument>> = 
        tagDao.getDocumentsByTags(tagIds)
    
    suspend fun createTag(name: String, color: String = "#2196F3"): Tag? {
        val existingTag = tagDao.getTagByName(name)
        if (existingTag != null) {
            return existingTag
        }
        
        val tag = Tag(name = name.trim(), color = color)
        val tagId = tagDao.insertTag(tag)
        return tagDao.getTagById(tagId)
    }
    
    suspend fun addTagToDocument(documentId: Long, tagName: String, tagColor: String? = null): Boolean {
        return tagDao.addTagToDocument(documentId, tagName.trim(), tagColor)
    }
    
    suspend fun removeTagFromDocument(documentId: Long, tagId: Long) {
        tagDao.removeTagFromDocumentAndUpdateCount(documentId, tagId)
    }
    
    suspend fun removeAllTagsFromDocument(documentId: Long) {
        // Remove all tags from the document directly
        tagDao.removeAllTagsFromDocument(documentId)
    }
    
    suspend fun updateTag(tag: Tag) {
        tagDao.updateTag(tag)
    }
    
    suspend fun deleteTag(tag: Tag) {
        tagDao.deleteTag(tag)
    }
    
    suspend fun searchTags(query: String): List<Tag> = tagDao.searchTags(query.trim())
    
    suspend fun getTagByName(name: String): Tag? = tagDao.getTagByName(name.trim())
    
    suspend fun isDocumentTagged(documentId: Long, tagId: Long): Boolean {
        return tagDao.isDocumentTagged(documentId, tagId) > 0
    }
    
    // Helper method to create multiple tags and add them to a document
    suspend fun addTagsToDocument(documentId: Long, tagNames: List<String>): List<String> {
        val failedTags = mutableListOf<String>()
        
        for (tagName in tagNames) {
            val success = addTagToDocument(documentId, tagName)
            if (!success) {
                failedTags.add(tagName)
            }
        }
        
        return failedTags
    }
    
    // Parse comma-separated tag string and add to document
    suspend fun addTagsToDocumentFromString(documentId: Long, tagsString: String): List<String> {
        val tagNames = tagsString.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        
        return addTagsToDocument(documentId, tagNames)
    }
    
    // Get documents by category (using the existing category field)
    fun getDocumentsByCategory(@Suppress("UNUSED_PARAMETER") category: String): Flow<List<com.speedread.rsvp.data.document.SavedDocument>> {
        // This would need to be implemented in the SavedDocumentDao
        throw NotImplementedError("Category filtering should be implemented in SavedDocumentRepository")
    }
    
    // Helper method to get popular tags (most used)
    suspend fun getPopularTags(@Suppress("UNUSED_PARAMETER") limit: Int = 10): List<Tag> {
        return tagDao.searchTags("") // Gets all tags, but we'd need a proper query for popular ones
    }
}
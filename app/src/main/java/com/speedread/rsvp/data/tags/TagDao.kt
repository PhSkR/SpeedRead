package com.speedread.rsvp.data.tags

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: Tag): Long
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDocumentTag(documentTag: DocumentTag)
    
    @Update
    suspend fun updateTag(tag: Tag)
    
    @Delete
    suspend fun deleteTag(tag: Tag)
    
    @Query("DELETE FROM document_tags WHERE documentId = :documentId AND tagId = :tagId")
    suspend fun removeTagFromDocument(documentId: Long, tagId: Long)
    
    @Query("DELETE FROM document_tags WHERE documentId = :documentId")
    suspend fun removeAllTagsFromDocument(documentId: Long)
    
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAllTags(): Flow<List<Tag>>
    
    @Query("SELECT * FROM tags WHERE id = :tagId")
    suspend fun getTagById(tagId: Long): Tag?
    
    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun getTagByName(name: String): Tag?
    
    @Query("""
        SELECT t.* FROM tags t 
        INNER JOIN document_tags dt ON t.id = dt.tagId 
        WHERE dt.documentId = :documentId 
        ORDER BY t.name ASC
    """)
    fun getTagsForDocument(documentId: Long): Flow<List<Tag>>
    
    @Query("""
        SELECT DISTINCT sd.* FROM saved_documents sd
        INNER JOIN document_tags dt ON sd.id = dt.documentId
        WHERE dt.tagId IN (:tagIds)
        ORDER BY sd.title ASC
    """)
    fun getDocumentsByTags(tagIds: List<Long>): Flow<List<com.speedread.rsvp.data.document.SavedDocument>>
    
    @Query("""
        SELECT t.*, COUNT(dt.documentId) as documentCount
        FROM tags t
        LEFT JOIN document_tags dt ON t.id = dt.tagId
        GROUP BY t.id
        ORDER BY t.name ASC
    """)
    fun getTagsWithUsageCount(): Flow<List<TagWithUsageCount>>
    
    @Query("""
        SELECT COUNT(*) FROM document_tags 
        WHERE documentId = :documentId AND tagId = :tagId
    """)
    suspend fun isDocumentTagged(documentId: Long, tagId: Long): Int
    
    @Query("SELECT * FROM tags WHERE name LIKE :searchQuery || '%' ORDER BY name ASC LIMIT 10")
    suspend fun searchTags(searchQuery: String): List<Tag>
    
    @Transaction
    suspend fun addTagToDocument(documentId: Long, tagName: String, tagColor: String? = null): Boolean {
        // Check if tag already exists
        var tag = getTagByName(tagName)
        
        if (tag == null) {
            // Create new tag
            val newTag = Tag(
                name = tagName,
                color = tagColor ?: "#2196F3",
                usageCount = 1
            )
            val tagId = insertTag(newTag)
            tag = getTagById(tagId) ?: return false
        } else {
            // Check if document is already tagged
            if (isDocumentTagged(documentId, tag.id) > 0) {
                return false // Already tagged
            }
            
            // Update usage count
            updateTag(tag.copy(usageCount = tag.usageCount + 1))
        }
        
        // Create document-tag relationship
        insertDocumentTag(DocumentTag(documentId, tag.id))
        return true
    }
    
    @Transaction
    suspend fun removeTagFromDocumentAndUpdateCount(documentId: Long, tagId: Long) {
        removeTagFromDocument(documentId, tagId)
        
        // Update usage count
        val tag = getTagById(tagId)
        if (tag != null && tag.usageCount > 0) {
            updateTag(tag.copy(usageCount = tag.usageCount - 1))
        }
    }
    
    @Query("UPDATE tags SET usageCount = usageCount - 1 WHERE id = :tagId AND usageCount > 0")
    suspend fun decrementTagUsage(tagId: Long)
}
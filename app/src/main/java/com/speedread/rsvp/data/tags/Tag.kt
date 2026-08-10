package com.speedread.rsvp.data.tags

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)]
)
data class Tag(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val color: String = "#2196F3", // Default blue color
    val createdAt: Long = System.currentTimeMillis(),
    val usageCount: Int = 0
)

@Entity(
    tableName = "document_tags",
    primaryKeys = ["documentId", "tagId"],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = com.speedread.rsvp.data.document.SavedDocument::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        ),
        androidx.room.ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [
        androidx.room.Index(value = ["documentId"]),
        androidx.room.Index(value = ["tagId"])
    ]
)
data class DocumentTag(
    val documentId: Long,
    val tagId: Long
)

data class DocumentWithTags(
    val document: com.speedread.rsvp.data.document.SavedDocument,
    val tags: List<Tag>
)

data class TagWithUsageCount(
    @androidx.room.Embedded val tag: Tag,
    val documentCount: Int
)
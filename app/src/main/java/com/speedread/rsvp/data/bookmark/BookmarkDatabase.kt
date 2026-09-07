package com.speedread.rsvp.data.bookmark

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import android.content.Context
import com.speedread.rsvp.data.document.SavedDocument
import com.speedread.rsvp.data.document.SavedDocumentDao
import com.speedread.rsvp.data.tags.Tag
import com.speedread.rsvp.data.tags.DocumentTag
import com.speedread.rsvp.data.tags.TagDao

@Database(
    entities = [Bookmark::class, SavedDocument::class, Tag::class, DocumentTag::class],
    version = 13,
    exportSchema = true
)
@TypeConverters(DateConverter::class, BookmarkSourceConverter::class)
abstract class BookmarkDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun savedDocumentDao(): SavedDocumentDao
    abstract fun tagDao(): TagDao
    
    companion object {
        const val DATABASE_NAME = "speedread_data.db"
    }
}
package com.speedread.rsvp.data.bookmark

import androidx.room.TypeConverter
import java.util.Date

class DateConverter {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
}

class BookmarkSourceConverter {
    @TypeConverter
    fun fromBookmarkSource(source: BookmarkSource): String {
        return source.name
    }

    @TypeConverter
    fun toBookmarkSource(source: String): BookmarkSource {
        return BookmarkSource.valueOf(source)
    }
}
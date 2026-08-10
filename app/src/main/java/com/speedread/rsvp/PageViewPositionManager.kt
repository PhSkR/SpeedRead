package com.speedread.rsvp

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PageViewPositionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = Constants.PAGE_VIEW_POSITIONS_PREFS_NAME
        private const val KEY_PREFIX = "doc_"
        private const val NO_SAVED_POSITION = -1
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSavedPosition(documentId: Long): Int {
        return prefs.getInt("$KEY_PREFIX$documentId", NO_SAVED_POSITION)
    }

    fun savePosition(documentId: Long, wordPosition: Int) {
        prefs.edit()
            .putInt("$KEY_PREFIX$documentId", wordPosition)
            .apply()
    }

    fun hasSavedPosition(documentId: Long): Boolean {
        return prefs.contains("$KEY_PREFIX$documentId")
    }

    fun clearPosition(documentId: Long) {
        prefs.edit()
            .remove("$KEY_PREFIX$documentId")
            .apply()
    }
}

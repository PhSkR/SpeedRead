package com.speedread.rsvp.data.bookmark

import android.content.Context
import android.content.SharedPreferences
import com.speedread.rsvp.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether the local database was destructively reset during an app upgrade
 * so the UI can surface a one-time notice on the next launch.
 */
@Singleton
class SchemaResetNotifier @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.PREF_NAME_SCHEMA_STATE, Context.MODE_PRIVATE)

    fun markReset() {
        prefs.edit().putBoolean(Constants.PREF_KEY_DATA_RESET_NOTICE, true).apply()
    }

    fun consumeResetNotice(): Boolean {
        val wasSet = prefs.getBoolean(Constants.PREF_KEY_DATA_RESET_NOTICE, false)
        if (wasSet) {
            prefs.edit().remove(Constants.PREF_KEY_DATA_RESET_NOTICE).apply()
        }
        return wasSet
    }
}

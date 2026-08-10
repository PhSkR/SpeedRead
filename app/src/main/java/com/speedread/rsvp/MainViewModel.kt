package com.speedread.rsvp

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Activity-scoped ViewModel for MainActivity.
 *
 * Startup cleanup (duplicate auto-bookmarks, orphaned content files, stale auto-bookmarks)
 * is owned by [ReadingViewModel] so it runs once on reader-tab creation rather than twice.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel()

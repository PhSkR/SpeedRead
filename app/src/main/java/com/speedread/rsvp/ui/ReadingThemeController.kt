package com.speedread.rsvp.ui

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.core.content.ContextCompat
import com.speedread.rsvp.ThemeManager
import com.speedread.rsvp.databinding.FragmentReadingBinding
import com.speedread.rsvp.engine.RsvpState
import com.speedread.rsvp.util.Logger

/**
 * ThemeController specifically for the ReadingFragment
 * This handles theming for the RSVP reading display
 */
class ReadingThemeController(
    private val activity: Activity,
    private val binding: FragmentReadingBinding,
    private val themeManager: ThemeManager
) {
    
    fun forceStatusBarBlack() {
        try {
            // Multiple approaches to force black status bar
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = Color.BLACK
            
            // Use modern WindowInsetsController
            val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            windowInsetsController.isAppearanceLightStatusBars = false
            
            // Force it with additional window attributes
            val attributes = activity.window.attributes
            attributes.flags = attributes.flags or android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            activity.window.attributes = attributes
            
            // Post with multiple delays to ensure it sticks
            activity.window.decorView.post {
                @Suppress("DEPRECATION")
                activity.window.statusBarColor = Color.BLACK
                androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = false
            }
            
        } catch (e: Exception) {
            Logger.w("ReadingThemeController", "Status bar styling failed; applying minimal black fallback", e)
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = Color.BLACK
        }
    }
    
    fun dimReadingViewForPlayMode() {
        // For reading fragment, we'll focus on dimming the background around the word display
        // and making the word display more prominent
        
        // Make background darker while keeping the word display clearly visible
        binding.root.post {
            // The background is already the theme background, so we'll just make sure status bar is black
            forceStatusBarBlack()
        }
    }
    
    fun restoreReadingViewForNormalMode() {
        // Restore normal appearance for paused/normal state
        binding.root.post {
            forceStatusBarBlack() // Keep status bar black as requested in requirements
        }
    }
    
    fun updateForRsvpState(state: RsvpState) {
        when (state) {
            com.speedread.rsvp.engine.RsvpState.Playing -> {
                dimReadingViewForPlayMode()
            }
            else -> {
                restoreReadingViewForNormalMode()
            }
        }
    }
    
    fun applyThemeSettings() {
        // Force status bar black as requested
        forceStatusBarBlack()
    }
}
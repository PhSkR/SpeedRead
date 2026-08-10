package com.speedread.rsvp.ui

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.speedread.rsvp.ThemeManager
import com.speedread.rsvp.databinding.ActivityMainBinding
import com.speedread.rsvp.engine.ThemeSettings
import com.speedread.rsvp.util.Logger

class ThemeController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val themeManager: ThemeManager
) {
    
    fun forceStatusBarBlack() {
        try {
            // Multiple approaches to force black status bar
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = Color.BLACK
            
            // Use modern WindowInsetsController
            val windowInsetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            windowInsetsController.isAppearanceLightStatusBars = false
            
            // Force it with additional window attributes
            val attributes = activity.window.attributes
            attributes.flags = attributes.flags or android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            activity.window.attributes = attributes
            
            // Post with multiple delays to ensure it sticks
            activity.window.decorView.post {
                @Suppress("DEPRECATION")
                activity.window.statusBarColor = Color.BLACK
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = false
            }
            
        } catch (e: Exception) {
            Logger.w("ThemeController", "Status bar styling failed; applying minimal black fallback", e)
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = Color.BLACK
        }
    }
    
    fun dimReadingTabBackground() {
//        // Make entire background completely black during play
//        binding.readingTabContent.setBackgroundColor(Color.BLACK)
//        
//        // Ensure the RSVP display card remains bright by explicitly setting its background
//        binding.rsvpDisplayCard.setCardBackgroundColor(
//            androidx.core.content.ContextCompat.getColor(activity, android.R.color.white)
//        )
//        
//        // Make status bar black during play mode
//        forceStatusBarBlack()
//        
//        // Hide all controls except pause button and text display
//        hideControlsForPlayMode()
    }
    
    fun restoreReadingTabBackground() {
//        // Restore normal background (transparent)
//        binding.readingTabContent.setBackgroundColor(Color.TRANSPARENT)
//        
//        // Restore RSVP card to theme default
//        val typedValue = android.util.TypedValue()
//        activity.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
//        binding.rsvpDisplayCard.setCardBackgroundColor(typedValue.data)
//        
//        // Restore status bar to black (keep it black in all themes as requested)
//        forceStatusBarBlack()
//        
//        // Show all controls again
//        showControlsForNormalMode()
    }
    
    fun hideControlsForPlayMode() {
//        with(binding) {
//            // Make elements invisible to maintain layout but hide them visually
//            documentTitleDisplay.visibility = View.INVISIBLE
//            progressBar.visibility = View.INVISIBLE
//            pageCounter.visibility = View.INVISIBLE
//            navigationLayout.visibility = View.INVISIBLE
//            
//            // Hide all control buttons except pause
//            btnPlay.visibility = View.INVISIBLE
//            btnRestartParagraph.visibility = View.INVISIBLE
//            btnBookmark.visibility = View.INVISIBLE
//            btnBookmarks.visibility = View.INVISIBLE
//            btnSaveDocument.visibility = View.INVISIBLE
//            
//            // Hide FABs
//            fabHoldToPlay.visibility = View.INVISIBLE
//            fabImportText.visibility = View.INVISIBLE
//            
//            // Hide import status if visible
//            importStatusCard.visibility = View.INVISIBLE
//        }
    }
    
    fun showControlsForNormalMode() {
//        with(binding) {
//            // Show all elements
//            documentTitleDisplay.visibility = View.VISIBLE
//            progressBar.visibility = View.VISIBLE
//            pageCounter.visibility = View.VISIBLE
//            navigationLayout.visibility = View.VISIBLE
//            
//            // Show all control buttons
//            btnPlay.visibility = View.VISIBLE
//            btnRestartParagraph.visibility = View.VISIBLE
//            btnBookmark.visibility = View.VISIBLE
//            btnBookmarks.visibility = View.VISIBLE
//            btnSaveDocument.visibility = View.VISIBLE
//            
//            // Show FABs
//            fabHoldToPlay.visibility = View.VISIBLE
//            fabImportText.visibility = View.VISIBLE
//            
//            // Only show import status if it was previously visible
//            // Don't automatically show it
//        }
    }
    
    fun applyThemeSettings(themeSettings: ThemeSettings) {
        // Save theme settings but don't apply immediately during initialization
        themeManager.saveThemeSettings(themeSettings)
        
        // Only apply theme mode change immediately (no restart needed for this)
        themeManager.applyThemeMode(themeSettings.mode)
        
        // Force status bar to stay black regardless of theme
        forceStatusBarBlack()
    }
}
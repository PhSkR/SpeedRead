package com.speedread.rsvp

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.speedread.rsvp.engine.PageViewScrollMode
import com.speedread.rsvp.engine.ThemeMode
import com.speedread.rsvp.engine.ThemeSettings
import com.speedread.rsvp.engine.ThemeStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private var statusBarCallback: (() -> Unit)? = null
    
    /**
     * Set a callback to be called after theme changes to fix status bar
     */
    fun setStatusBarCallback(callback: () -> Unit) {
        statusBarCallback = callback
    }
    companion object {
        private const val THEME_PREFS = "theme_preferences"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_STYLE = "theme_style"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_PAGE_VIEW_FONT_SIZE = "page_view_font_size"
        private const val KEY_PAGE_VIEW_TEXT_COLOR = "page_view_text_color"
        private const val KEY_PAGE_VIEW_BG_COLOR = "page_view_background_color"
        private const val KEY_PAGE_VIEW_SCROLL_MODE = "page_view_scroll_mode"
        private const val KEY_PAGE_VIEW_ONLY_MODE = "page_view_only_mode"
        private const val KEY_PAGE_VIEW_CONTENT_MODE = "page_view_content_mode"
        private const val DEFAULT_FONT_SIZE = Constants.DEFAULT_FONT_SIZE
        private const val DEFAULT_PAGE_VIEW_FONT_SIZE = Constants.DEFAULT_PAGE_VIEW_FONT_SIZE
        private const val DEFAULT_PAGE_VIEW_TEXT_COLOR = Constants.DEFAULT_TEXT_COLOR
        private const val DEFAULT_PAGE_VIEW_BG_COLOR = Constants.DEFAULT_BACKGROUND_COLOR
        private const val DEFAULT_PAGE_VIEW_SCROLL_MODE = Constants.DEFAULT_PAGE_VIEW_SCROLL_MODE
        private const val DEFAULT_PAGE_VIEW_ONLY_MODE = Constants.DEFAULT_PAGE_VIEW_ONLY_MODE
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)

    // RSVP reader font size
    private val _fontSize = MutableStateFlow(prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE))
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()

    // Page View font size (independent from RSVP)
    private val _pageViewFontSize = MutableStateFlow(
        prefs.getFloat(KEY_PAGE_VIEW_FONT_SIZE, DEFAULT_PAGE_VIEW_FONT_SIZE)
    )
    val pageViewFontSize: StateFlow<Float> = _pageViewFontSize.asStateFlow()

    // Page View text + background color (independent from RSVP colors stored in RsvpSettingsManager)
    private val _pageViewColors = MutableStateFlow(
        PageViewColors(
            textColor = prefs.getInt(KEY_PAGE_VIEW_TEXT_COLOR, DEFAULT_PAGE_VIEW_TEXT_COLOR),
            backgroundColor = prefs.getInt(KEY_PAGE_VIEW_BG_COLOR, DEFAULT_PAGE_VIEW_BG_COLOR)
        )
    )
    val pageViewColors: StateFlow<PageViewColors> = _pageViewColors.asStateFlow()

    // Page View scroll mode. Defaults to CONTINUOUS so the reading area always fills the
    // screen — paged mode could leave whitespace whenever a page chunk ended before the
    // viewport did. Stored as a string under KEY_PAGE_VIEW_SCROLL_MODE; unparseable values
    // fall back to the default rather than crashing.
    private val _pageViewScrollMode = MutableStateFlow(loadScrollModePref())
    val pageViewScrollMode: StateFlow<PageViewScrollMode> = _pageViewScrollMode.asStateFlow()

    private val _pageViewOnlyMode = MutableStateFlow(
        prefs.getBoolean(KEY_PAGE_VIEW_ONLY_MODE, DEFAULT_PAGE_VIEW_ONLY_MODE)
    )
    val pageViewOnlyMode: StateFlow<Boolean> = _pageViewOnlyMode.asStateFlow()

    private fun loadScrollModePref(): PageViewScrollMode {
        val raw = prefs.getString(KEY_PAGE_VIEW_SCROLL_MODE, DEFAULT_PAGE_VIEW_SCROLL_MODE)
            ?: DEFAULT_PAGE_VIEW_SCROLL_MODE
        return try {
            PageViewScrollMode.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            PageViewScrollMode.valueOf(DEFAULT_PAGE_VIEW_SCROLL_MODE)
        }
    }

    /**
     * Get current theme settings from preferences
     */
    fun getCurrentThemeSettings(): ThemeSettings {
        val modeString = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM_DEFAULT.name)
        val styleString = prefs.getString(KEY_THEME_STYLE, ThemeStyle.MATERIAL_DEFAULT.name)
        
        val mode = try {
            ThemeMode.valueOf(modeString ?: ThemeMode.SYSTEM_DEFAULT.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM_DEFAULT
        }
        
        val style = try {
            ThemeStyle.valueOf(styleString ?: ThemeStyle.MATERIAL_DEFAULT.name)
        } catch (e: IllegalArgumentException) {
            ThemeStyle.MATERIAL_DEFAULT
        }
        
        return ThemeSettings(mode, style)
    }

    /**
     * Save theme settings to preferences
     */
    fun saveThemeSettings(settings: ThemeSettings) {
        prefs.edit()
            .putString(KEY_THEME_MODE, settings.mode.name)
            .putString(KEY_THEME_STYLE, settings.style.name)
            .apply()
    }

    /**
     * Apply theme mode to the app delegate
     */
    fun applyThemeMode(mode: ThemeMode) {
        val nightMode = when (mode) {
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.SYSTEM_DEFAULT -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
        
        // Call status bar fix callback after theme change
        statusBarCallback?.invoke()
    }

    /**
     * Apply theme settings immediately
     */
    fun applyThemeSettings(settings: ThemeSettings) {
        saveThemeSettings(settings)
        applyThemeMode(settings.mode)
    }

    /**
     * Get the appropriate theme resource ID based on settings
     */
    fun getThemeResourceId(settings: ThemeSettings): Int {
        return when (settings.style) {
            ThemeStyle.MATERIAL_DEFAULT -> R.style.Theme_SpeedRead
            ThemeStyle.MATERIAL_DYNAMIC -> {
                // Dynamic theming is only available on Android 12+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    R.style.Theme_SpeedRead_Dynamic
                } else {
                    R.style.Theme_SpeedRead
                }
            }
        }
    }

    /**
     * Check if dynamic theming is available on this device
     */
    fun isDynamicThemingAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    /**
     * Initialize theme on app startup
     */
    fun initializeTheme() {
        val settings = getCurrentThemeSettings()
        applyThemeMode(settings.mode)
    }

    /**
     * Get current font size from preferences
     */
    fun getCurrentFontSize(): Float = _fontSize.value

    /**
     * Save font size to preferences and notify observers
     */
    fun saveFontSize(fontSize: Float) {
        prefs.edit()
            .putFloat(KEY_FONT_SIZE, fontSize)
            .apply()
        _fontSize.value = fontSize
    }

    /**
     * Reset font size to default
     */
    fun resetFontSize() {
        saveFontSize(DEFAULT_FONT_SIZE)
    }

    // --- Page View (independent from RSVP reader) ---

    fun getCurrentPageViewFontSize(): Float = _pageViewFontSize.value

    fun savePageViewFontSize(size: Float) {
        prefs.edit()
            .putFloat(KEY_PAGE_VIEW_FONT_SIZE, size)
            .apply()
        _pageViewFontSize.value = size
    }

    fun resetPageViewFontSize() {
        savePageViewFontSize(DEFAULT_PAGE_VIEW_FONT_SIZE)
    }

    fun getCurrentPageViewColors(): PageViewColors = _pageViewColors.value

    fun savePageViewColors(textColor: Int, backgroundColor: Int) {
        prefs.edit()
            .putInt(KEY_PAGE_VIEW_TEXT_COLOR, textColor)
            .putInt(KEY_PAGE_VIEW_BG_COLOR, backgroundColor)
            .apply()
        _pageViewColors.value = PageViewColors(textColor, backgroundColor)
    }

    fun resetPageViewColors() {
        savePageViewColors(DEFAULT_PAGE_VIEW_TEXT_COLOR, DEFAULT_PAGE_VIEW_BG_COLOR)
    }

    fun getCurrentPageViewScrollMode(): PageViewScrollMode = _pageViewScrollMode.value

    fun savePageViewScrollMode(mode: PageViewScrollMode) {
        prefs.edit()
            .putString(KEY_PAGE_VIEW_SCROLL_MODE, mode.name)
            .apply()
        _pageViewScrollMode.value = mode
    }

    fun resetPageViewScrollMode() {
        savePageViewScrollMode(PageViewScrollMode.valueOf(DEFAULT_PAGE_VIEW_SCROLL_MODE))
    }

    /**
     * Sticky Page View content mode for PDF documents: AUTO (default, PDF-first with text
     * fallback), TEXT, or PDF. Persisted from the toolbar toggle so a reader who prefers
     * reflowed text does not land in native PDF page mode on every open.
     */
    fun getCurrentPageViewContentMode(): String =
        prefs.getString(KEY_PAGE_VIEW_CONTENT_MODE, Constants.DEFAULT_PAGE_VIEW_CONTENT_MODE)
            ?: Constants.DEFAULT_PAGE_VIEW_CONTENT_MODE

    fun savePageViewContentMode(mode: String) {
        prefs.edit()
            .putString(KEY_PAGE_VIEW_CONTENT_MODE, mode)
            .apply()
    }

    fun isPageViewOnlyMode(): Boolean = _pageViewOnlyMode.value

    fun savePageViewOnlyMode(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_PAGE_VIEW_ONLY_MODE, enabled)
            .apply()
        _pageViewOnlyMode.value = enabled
    }

    fun resetPageViewOnlyMode() {
        savePageViewOnlyMode(DEFAULT_PAGE_VIEW_ONLY_MODE)
    }
}

data class PageViewColors(
    val textColor: Int,
    val backgroundColor: Int
)
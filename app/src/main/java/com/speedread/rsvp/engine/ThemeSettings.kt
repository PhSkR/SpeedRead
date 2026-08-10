package com.speedread.rsvp.engine

/**
 * Theme mode options for the application
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM_DEFAULT
}

/**
 * Theme style options for the application
 */
enum class ThemeStyle {
    MATERIAL_DEFAULT,
    MATERIAL_DYNAMIC
}

/**
 * How the Page View advances through content. CONTINUOUS shows the whole document in a
 * single vertically-scrollable view (no per-page chrome or end-of-page whitespace). PAGED
 * keeps the original swipe-per-page behavior. CONTINUOUS is the default — paged mode left
 * trailing whitespace whenever a source page boundary or chunk ended before the screen did.
 */
enum class PageViewScrollMode {
    CONTINUOUS,
    PAGED
}

/**
 * Theme settings configuration
 */
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM_DEFAULT,
    val style: ThemeStyle = ThemeStyle.MATERIAL_DEFAULT
) {
    companion object {
        fun default() = ThemeSettings()
    }
}
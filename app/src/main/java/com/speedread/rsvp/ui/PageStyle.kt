package com.speedread.rsvp.ui

/**
 * Visual style applied to text pages in [PageViewActivity]. Mirrors the user's font size + color
 * selections from Settings so that page view honors the same look as the RSVP display.
 */
data class PageStyle(
    val fontSizeSp: Float,
    val textColor: Int,
    val backgroundColor: Int
)

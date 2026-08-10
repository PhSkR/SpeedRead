package com.speedread.rsvp.ui

import android.content.Context
import com.speedread.rsvp.R

/**
 * The 8 color presets shown in both the Options screen's Page View Appearance card and the
 * in-place Page View settings BottomSheet. Defined once here so a preset change never drifts
 * between the two entry points. Palette order is load-bearing: the dropdown's first entry
 * (Black on White) is used as the fallback when no preset matches the stored colors.
 */
object PageViewColorPresets {
    fun build(context: Context): List<ColorPreset> = listOf(
        ColorPreset(context.getString(R.string.color_preset_black_white), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
        ColorPreset(context.getString(R.string.color_preset_white_black), 0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
        ColorPreset(context.getString(R.string.color_preset_sepia), 0xFF2D1810.toInt(), 0xFFF4ECD8.toInt()),
        ColorPreset(context.getString(R.string.color_preset_dark_blue), 0xFFE3F2FD.toInt(), 0xFF0D47A1.toInt()),
        ColorPreset(context.getString(R.string.color_preset_green_black), 0xFF4CAF50.toInt(), 0xFF000000.toInt()),
        ColorPreset(context.getString(R.string.color_preset_amber_dark), 0xFFFFC107.toInt(), 0xFF212121.toInt()),
        ColorPreset(context.getString(R.string.color_preset_purple_light), 0xFF6A1B9A.toInt(), 0xFFF3E5F5.toInt()),
        ColorPreset(context.getString(R.string.color_preset_high_contrast), 0xFF00E5FF.toInt(), 0xFF001A35.toInt())
    )
}

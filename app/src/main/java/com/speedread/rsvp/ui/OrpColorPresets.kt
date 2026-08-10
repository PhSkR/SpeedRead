package com.speedread.rsvp.ui

import android.content.Context
import com.speedread.rsvp.R

data class OrpColorPreset(
    val name: String,
    val color: Int
)

object OrpColorPresets {
    fun build(context: Context): List<OrpColorPreset> = listOf(
        OrpColorPreset(context.getString(R.string.orp_color_orange), 0xFFFF6B35.toInt()),
        OrpColorPreset(context.getString(R.string.orp_color_red), 0xFFE53935.toInt()),
        OrpColorPreset(context.getString(R.string.orp_color_blue), 0xFF1E88E5.toInt()),
        OrpColorPreset(context.getString(R.string.orp_color_green), 0xFF43A047.toInt()),
        OrpColorPreset(context.getString(R.string.orp_color_purple), 0xFF8E24AA.toInt()),
        OrpColorPreset(context.getString(R.string.orp_color_cyan), 0xFF00ACC1.toInt()),
        OrpColorPreset(context.getString(R.string.orp_color_yellow), 0xFFFDD835.toInt()),
        OrpColorPreset(context.getString(R.string.orp_color_pink), 0xFFD81B60.toInt())
    )
}

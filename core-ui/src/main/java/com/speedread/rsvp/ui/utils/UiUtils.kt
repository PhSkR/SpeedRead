package com.speedread.rsvp.ui.utils

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.core.content.res.use

/**
 * Helper functions for UI utilities
 */
object UiUtils {
    
    /**
     * Get color from theme attribute
     */
    fun Context.getColorFromAttr(@AttrRes attr: Int): Int {
        return this.theme.obtainStyledAttributes(intArrayOf(attr)).use { 
            it.getColor(0, android.graphics.Color.TRANSPARENT) 
        }
    }
    
    /**
     * Convert DP to pixels
     */
    fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
    }
    
    /**
     * Convert SP to pixels
     */
    fun spToPx(context: Context, sp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)
    }
}
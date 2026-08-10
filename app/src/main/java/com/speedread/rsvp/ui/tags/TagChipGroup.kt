package com.speedread.rsvp.ui.tags

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.speedread.rsvp.R
import com.speedread.rsvp.data.tags.Tag

class TagChipGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.chipGroupStyle
) : ChipGroup(context, attrs, defStyleAttr) {
    
    private var onTagClickListener: ((Tag) -> Unit)? = null
    private var onTagRemoveListener: ((Tag) -> Unit)? = null
    private var isEditable: Boolean = false
    
    fun setTags(tags: List<Tag>, editable: Boolean = false) {
        removeAllViews()
        this.isEditable = editable
        
        tags.forEach { tag ->
            addTagChip(tag)
        }
    }
    
    fun addTag(tag: Tag) {
        addTagChip(tag)
    }
    
    fun removeTag(tag: Tag) {
        val chipToRemove = findChipByTag(tag)
        if (chipToRemove != null) {
            removeView(chipToRemove)
        }
    }
    
    fun setOnTagClickListener(listener: (Tag) -> Unit) {
        this.onTagClickListener = listener
    }
    
    fun setOnTagRemoveListener(listener: (Tag) -> Unit) {
        this.onTagRemoveListener = listener
    }
    
    private fun addTagChip(tag: Tag) {
        val chip = Chip(context).apply {
            text = tag.name
            
            // Set chip colors
            try {
                val tagColor = Color.parseColor(tag.color)
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(tagColor)
                setTextColor(getContrastingTextColor(tagColor))
            } catch (e: IllegalArgumentException) {
                // Fallback to default colors if tag color is invalid
                chipBackgroundColor = ContextCompat.getColorStateList(context, com.google.android.material.R.color.design_default_color_primary)
                setTextColor(ContextCompat.getColor(context, android.R.color.white))
            }
            
            // Configure chip behavior
            isClickable = true
            isFocusable = true
            
            if (isEditable) {
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    onTagRemoveListener?.invoke(tag)
                    removeTag(tag)
                }
            } else {
                isCloseIconVisible = false
            }
            
            setOnClickListener {
                onTagClickListener?.invoke(tag)
            }
            
            // Store tag as tag for later retrieval
            this.tag = tag
            
            // Set margins
            val layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams.setMargins(8, 4, 8, 4)
            this.layoutParams = layoutParams
        }
        
        addView(chip)
    }
    
    private fun findChipByTag(tag: Tag): Chip? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is Chip && child.tag == tag) {
                return child
            }
        }
        return null
    }
    
    private fun getContrastingTextColor(backgroundColor: Int): Int {
        // Calculate luminance to determine if text should be light or dark
        val red = Color.red(backgroundColor)
        val green = Color.green(backgroundColor)
        val blue = Color.blue(backgroundColor)
        
        // Calculate relative luminance
        val luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255
        
        return if (luminance > 0.5) {
            Color.BLACK // Dark text for light backgrounds
        } else {
            Color.WHITE // Light text for dark backgrounds
        }
    }
    
    fun getSelectedTags(): List<Tag> {
        val tags = mutableListOf<Tag>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is Chip) {
                val tag = child.tag as? Tag
                if (tag != null) {
                    tags.add(tag)
                }
            }
        }
        return tags
    }
}
package com.speedread.rsvp.ui.tags

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.speedread.rsvp.R
import com.speedread.rsvp.data.tags.Tag
import com.speedread.rsvp.databinding.ItemSelectedTagBinding

class SelectedTagsAdapter(
    private val onTagRemove: (Tag) -> Unit
) : ListAdapter<Tag, SelectedTagsAdapter.SelectedTagViewHolder>(SelectedTagDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectedTagViewHolder {
        val binding = ItemSelectedTagBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SelectedTagViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: SelectedTagViewHolder, position: Int) {
        holder.bind(getItem(position), onTagRemove)
    }
    
    fun setTags(tags: List<Tag>) {
        submitList(tags.toList())
    }
    
    fun addTag(tag: Tag) {
        val updatedList = currentList.toMutableList()
        if (!updatedList.contains(tag)) {
            updatedList.add(tag)
            submitList(updatedList.sortedBy { it.name })
        }
    }
    
    fun removeTag(tag: Tag) {
        val updatedList = currentList.toMutableList()
        updatedList.remove(tag)
        submitList(updatedList)
    }
    
    class SelectedTagViewHolder(
        private val binding: ItemSelectedTagBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(tag: Tag, onTagRemove: (Tag) -> Unit) {
            binding.tagChip.apply {
                text = tag.name
                
                // Set chip colors
                try {
                    val tagColor = Color.parseColor(tag.color)
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(tagColor)
                    setTextColor(getContrastingTextColor(tagColor))
                } catch (e: IllegalArgumentException) {
                    // Fallback to default colors
                    chipBackgroundColor = ContextCompat.getColorStateList(context, com.google.android.material.R.color.design_default_color_primary)
                    setTextColor(ContextCompat.getColor(context, android.R.color.white))
                }
                
                // Enable close icon for removal
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    onTagRemove(tag)
                }
                
                // Optional: Allow clicking the chip itself to remove
                setOnClickListener {
                    onTagRemove(tag)
                }
            }
        }
        
        private fun getContrastingTextColor(backgroundColor: Int): Int {
            val red = Color.red(backgroundColor)
            val green = Color.green(backgroundColor)
            val blue = Color.blue(backgroundColor)
            
            val luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255
            
            return if (luminance > 0.5) {
                Color.BLACK
            } else {
                Color.WHITE
            }
        }
    }
    
    private class SelectedTagDiffCallback : DiffUtil.ItemCallback<Tag>() {
        override fun areItemsTheSame(oldItem: Tag, newItem: Tag): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: Tag, newItem: Tag): Boolean {
            return oldItem == newItem
        }
    }
}
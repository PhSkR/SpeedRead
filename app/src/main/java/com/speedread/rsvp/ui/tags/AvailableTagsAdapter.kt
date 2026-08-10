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
import com.speedread.rsvp.databinding.ItemAvailableTagBinding

class AvailableTagsAdapter(
    private val onTagClick: (Tag) -> Unit
) : ListAdapter<AvailableTagsAdapter.TagItem, AvailableTagsAdapter.TagViewHolder>(TagDiffCallback()) {
    
    data class TagItem(
        val tag: Tag,
        val isSelected: Boolean = false
    )
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagViewHolder {
        val binding = ItemAvailableTagBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TagViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: TagViewHolder, position: Int) {
        holder.bind(getItem(position), onTagClick)
    }
    
    fun setTags(tags: List<Tag>, selectedTags: Set<Tag>) {
        val tagItems = tags.map { tag ->
            TagItem(tag, selectedTags.contains(tag))
        }
        submitList(tagItems)
    }
    
    fun updateTagSelection(tag: Tag, isSelected: Boolean) {
        val updatedList = currentList.map { tagItem ->
            if (tagItem.tag.id == tag.id) {
                tagItem.copy(isSelected = isSelected)
            } else {
                tagItem
            }
        }
        submitList(updatedList)
    }
    
    fun addNewTag(tag: Tag, isSelected: Boolean) {
        val updatedList = currentList.toMutableList()
        updatedList.add(TagItem(tag, isSelected))
        submitList(updatedList.sortedBy { it.tag.name })
    }
    
    class TagViewHolder(
        private val binding: ItemAvailableTagBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(tagItem: TagItem, onTagClick: (Tag) -> Unit) {
            val tag = tagItem.tag
            
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
                
                // Update selection state
                alpha = if (tagItem.isSelected) 0.5f else 1.0f
                isEnabled = !tagItem.isSelected
                
                setOnClickListener {
                    if (!tagItem.isSelected) {
                        onTagClick(tag)
                    }
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
    
    private class TagDiffCallback : DiffUtil.ItemCallback<TagItem>() {
        override fun areItemsTheSame(oldItem: TagItem, newItem: TagItem): Boolean {
            return oldItem.tag.id == newItem.tag.id
        }
        
        override fun areContentsTheSame(oldItem: TagItem, newItem: TagItem): Boolean {
            return oldItem == newItem
        }
    }
}
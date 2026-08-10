package com.speedread.rsvp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class FolderFile(
    val file: File,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val extension: String
)

class FolderFileAdapter(
    private val onFileClick: (FolderFile) -> Unit
) : ListAdapter<FolderFile, FolderFileAdapter.FileViewHolder>(FileDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_folder_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.fileName)
        private val detailsText: TextView = itemView.findViewById(R.id.fileDetails)
        private val typeIcon: ImageView = itemView.findViewById(R.id.fileTypeIcon)

        fun bind(folderFile: FolderFile) {
            nameText.text = folderFile.name
            
            // Format file size
            val sizeKB = folderFile.size / 1024.0
            val sizeText = when {
                sizeKB < 1024 -> "%.1f KB".format(sizeKB)
                else -> "%.1f MB".format(sizeKB / 1024)
            }
            
            // Format date
            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            val dateText = dateFormat.format(Date(folderFile.lastModified))
            
            detailsText.text = "$sizeText • $dateText"
            
            // Set appropriate icon based on file type
            val iconRes = when (folderFile.extension.lowercase()) {
                "pdf" -> R.drawable.ic_picture_as_pdf
                "epub" -> R.drawable.ic_book
                "txt" -> R.drawable.ic_text_snippet
                else -> R.drawable.ic_description
            }
            typeIcon.setImageResource(iconRes)
            
            // Set click listener
            itemView.setOnClickListener { onFileClick(folderFile) }
        }
    }
}

class FileDiffCallback : DiffUtil.ItemCallback<FolderFile>() {  
    override fun areItemsTheSame(oldItem: FolderFile, newItem: FolderFile): Boolean {
        return oldItem.file.absolutePath == newItem.file.absolutePath
    }

    override fun areContentsTheSame(oldItem: FolderFile, newItem: FolderFile): Boolean {
        return oldItem == newItem
    }
}
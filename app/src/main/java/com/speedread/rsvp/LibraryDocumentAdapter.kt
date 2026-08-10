package com.speedread.rsvp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.speedread.rsvp.data.document.SavedDocument
import com.speedread.rsvp.data.bookmark.BookmarkSource

class LibraryDocumentAdapter(
    private val onDocumentClick: (SavedDocument) -> Unit,
    private val onDocumentLongClick: (SavedDocument) -> Unit
) : ListAdapter<SavedDocument, LibraryDocumentAdapter.DocumentViewHolder>(DocumentDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_library_document, parent, false)
        return DocumentViewHolder(view)
    }

    override fun onBindViewHolder(holder: DocumentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DocumentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.documentTitle)
        private val subtitleText: TextView = itemView.findViewById(R.id.documentSubtitle)
        private val progressText: TextView = itemView.findViewById(R.id.documentProgress)
        private val favoriteIcon: ImageView = itemView.findViewById(R.id.favoriteIcon)
        private val typeIcon: ImageView = itemView.findViewById(R.id.typeIcon)

        fun bind(document: SavedDocument) {
            titleText.text = document.title

            // Use cached wordCount / fileSize (populated on the IO dispatcher at save
            // time — see SavedDocumentRepository.saveDocument). Regex-splitting the full
            // content on every bind/scroll was a main-thread stutter source for large
            // documents (hundreds of KB).
            val sizeKB = document.fileSize / 1024.0
            subtitleText.text = "${document.wordCount} words • %.1f KB".format(sizeKB)
            
            // Show reading progress
            val progressPercent = document.readingProgress.toInt()
            progressText.text = if (progressPercent > 0) {
                "$progressPercent% complete"
            } else {
                "Not started"
            }
            
            // Show favorite status
            favoriteIcon.visibility = if (document.isFavorite) View.VISIBLE else View.GONE
            
            // Show file type icon
            val typeIconRes = when (document.source) {
                BookmarkSource.FILE_PDF -> R.drawable.ic_picture_as_pdf
                BookmarkSource.FILE_EPUB -> R.drawable.ic_book
                BookmarkSource.FILE_TXT -> R.drawable.ic_text_snippet
                else -> R.drawable.ic_description
            }
            typeIcon.setImageResource(typeIconRes)
            
            // Set click listeners
            itemView.setOnClickListener { onDocumentClick(document) }
            itemView.setOnLongClickListener { 
                onDocumentLongClick(document)
                true
            }
        }
    }
}

class DocumentDiffCallback : DiffUtil.ItemCallback<SavedDocument>() {
    override fun areItemsTheSame(oldItem: SavedDocument, newItem: SavedDocument): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: SavedDocument, newItem: SavedDocument): Boolean {
        return oldItem == newItem
    }
}
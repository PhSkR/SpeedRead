package com.speedread.rsvp.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.speedread.rsvp.R
import com.speedread.rsvp.databinding.ItemPdfPageBinding
import com.speedread.rsvp.pdf.RenderedPdfPage

data class PdfPageItem(
    val pageIndex: Int,
    val renderedPage: RenderedPdfPage? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

class PdfPageAdapter(
    private val onPageClick: (Int) -> Unit,
    private val onPageVisible: (Int) -> Unit
) : ListAdapter<PdfPageItem, PdfPageAdapter.PdfPageViewHolder>(PdfPageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfPageViewHolder {
        val binding = ItemPdfPageBinding.inflate(
            LayoutInflater.from(parent.context), 
            parent, 
            false
        )
        return PdfPageViewHolder(binding, onPageClick, onPageVisible)
    }

    override fun onBindViewHolder(holder: PdfPageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PdfPageViewHolder(
        private val binding: ItemPdfPageBinding,
        private val onPageClick: (Int) -> Unit,
        private val onPageVisible: (Int) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PdfPageItem) {
            with(binding) {
                // Precedence: error > renderedPage > isLoading > else (initial bind, trigger
                // render). renderedPage is intentionally checked BEFORE isLoading so a slot
                // that already has a bitmap stays visually rendered even if a redundant
                // `renderPdfPage` call somehow flipped `isLoading=true` on the same slot.
                // The VM-side idempotence guard in PageViewViewModel.renderPdfPage already
                // prevents that condition under normal flow; this ordering is defensive
                // belt-and-suspenders so a future code path that adds an explicit refresh
                // does not regress into a spinner-flicker on every page turn.
                when {
                    item.error != null -> showError(item.error)
                    item.renderedPage != null -> showPdfPage(item.renderedPage)
                    item.isLoading -> showLoading()
                    else -> {
                        showLoading()
                        onPageVisible(item.pageIndex)
                    }
                }

                root.setOnClickListener { onPageClick(item.pageIndex) }
            }
        }

        private fun showPdfPage(renderedPage: RenderedPdfPage) {
            with(binding) {
                if (pdfPageImage.drawable != null) pdfPageImage.setImageBitmap(null)
                pdfPageImage.setImageBitmap(renderedPage.bitmap)
                pdfPageImage.isVisible = true
                loadingIndicator.isVisible = false
                errorLayout.isVisible = false
            }
        }

        private fun showLoading() {
            with(binding) {
                if (pdfPageImage.drawable != null) pdfPageImage.setImageBitmap(null)
                pdfPageImage.isVisible = false
                loadingIndicator.isVisible = true
                errorLayout.isVisible = false
            }
        }

        private fun showError(error: String) {
            with(binding) {
                if (pdfPageImage.drawable != null) pdfPageImage.setImageBitmap(null)
                pdfPageImage.isVisible = false
                loadingIndicator.isVisible = false
                errorLayout.isVisible = true
                errorText.text = error
            }
        }
    }

    private class PdfPageDiffCallback : DiffUtil.ItemCallback<PdfPageItem>() {
        override fun areItemsTheSame(oldItem: PdfPageItem, newItem: PdfPageItem): Boolean {
            return oldItem.pageIndex == newItem.pageIndex
        }

        override fun areContentsTheSame(oldItem: PdfPageItem, newItem: PdfPageItem): Boolean {
            return oldItem == newItem
        }
    }
}
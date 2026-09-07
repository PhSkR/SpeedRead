package com.speedread.rsvp.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.text.Spannable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.util.SparseIntArray
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.speedread.rsvp.Constants
import com.speedread.rsvp.DocumentPage
import com.speedread.rsvp.PageFigure
import com.speedread.rsvp.R
import com.speedread.rsvp.engine.PageViewScrollMode

class PageAdapter(
    private val onPageClick: (Int) -> Unit,
    // Long-press on a word in a page reports the absolute word index back so the activity
    // can mark it as the RSVP continuation point. Null = no handler wired (back-compat).
    private val onWordLongPress: ((wordIndex: Int) -> Unit)? = null,
    // Double-tap on a word in a page reports the absolute word index back so the activity
    // can drop a quick bookmark at that position. Null = gesture not wired (back-compat).
    private val onWordDoubleTap: ((wordIndex: Int) -> Unit)? = null,
    // Single tap on an inline figure opens the full-page viewer for it. Null = figures are
    // shown (if any) but not tappable.
    private val onFigureTap: ((figureIndex: Int) -> Unit)? = null,
    // Async bitmap source for inline figures. Implementations must invoke the callback on
    // the main thread; a null bitmap keeps the neutral placeholder. Null loader = figures
    // render as placeholders only.
    private val figureLoader: ((page: DocumentPage, figure: PageFigure, onLoaded: (Bitmap?) -> Unit) -> Unit)? = null
) : ListAdapter<DocumentPage, PageAdapter.PageViewHolder>(PageDiffCallback()) {

    private var scrollMode: PageViewScrollMode = PageViewScrollMode.PAGED
    private var style: PageStyle? = null
    private var highlightedWordIndex: Int? = null
    private var highlightColor: Int = 0xFFFF6B35.toInt() // orange fallback; activity overrides

    // Activity-provided index of the page the user is currently viewing. Auto-scroll is gated
    // on this so offscreen recycled holders don't run smoothScrollTo per spoken word — the lag
    // amplifier from changelog v1.14.21's continuous-mode fix, applied here for paged mode.
    private var currentPageIndex: Int = -1

    // Last absolute word index we auto-scrolled the active page to. Single global field (not
    // per-holder) because only one page is the active scroll target at a time. Cleared on
    // page-list submit so a new document starts fresh.
    private var lastAutoScrolledWord: Int? = null

    // Per-page (keyed by DocumentPage.pageNumber) line index the highlight last centered on.
    // Lets bind() skip smoothScrollTo when the highlight only hops across words within the
    // same visual line — the common case during TTS narration of a long line.
    private val lastAutoScrolledLine = SparseIntArray()

    fun setScrollMode(mode: PageViewScrollMode) {
        if (scrollMode == mode) return
        scrollMode = mode
        if (itemCount > 0) notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (scrollMode == PageViewScrollMode.CONTINUOUS) VIEW_TYPE_CONTINUOUS else VIEW_TYPE_PAGED

    /** Push a new visual style and redraw any bound holders. */
    fun setStyle(newStyle: PageStyle) {
        if (style == newStyle) return
        style = newStyle
        // Style change touches text color and background — needs a full bind, not a payload.
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    /**
     * Set the absolute word index to highlight (where RSVP left off, or what TTS is currently
     * speaking). The highlight lives on at most one page; updating it is a targeted partial
     * notify so a 300-page document doesn't re-bind every holder per spoken word — see
     * changelog v1.14.21 for the parallel continuous-mode fix.
     *
     * Page-index resolution uses [findPageIndexForAbsoluteWord], a binary search over the
     * current list's `startWordIndex` values. When the page list isn't loaded yet (initial
     * Page View entry calls `setHighlight` before `submitList` streams pages in), the search
     * returns `-1`, no notify is issued, and the latched fields below get applied via the
     * full-bind path on the first DiffUtil-driven submit.
     */
    fun setHighlight(wordIndex: Int?, color: Int) {
        if (highlightedWordIndex == wordIndex && highlightColor == color) return
        val prev = highlightedWordIndex
        val colorChanged = highlightColor != color
        highlightedWordIndex = wordIndex
        highlightColor = color
        if (itemCount == 0) return

        if (colorChanged) {
            // Color change is rare (theme flip / dark-mode toggle) — full rebind so every
            // currently-highlighted holder picks up the new ForegroundColorSpan color. Cheap
            // because it happens once per theme change, not per spoken word.
            notifyItemRangeChanged(0, itemCount, PAYLOAD_HIGHLIGHT)
            return
        }

        val oldIdx = if (prev != null) findPageIndexForAbsoluteWord(prev) else -1
        val newIdx = if (wordIndex != null) findPageIndexForAbsoluteWord(wordIndex) else -1
        if (oldIdx >= 0 && oldIdx != newIdx) notifyItemChanged(oldIdx, PAYLOAD_HIGHLIGHT)
        if (newIdx >= 0) notifyItemChanged(newIdx, PAYLOAD_HIGHLIGHT)
    }

    /**
     * Activity-driven update of which page is the "active" (visible) one. Called from manual
     * snap detection, programmatic [PageViewActivity.scrollToPage], and `autoFlipPagedToWord`.
     * Auto-scroll-to-highlight only runs for the page matching this index so offscreen
     * holders (recycled or off-screen during a flip) don't waste a layout pass animating
     * their internal ScrollView.
     */
    fun setCurrentPage(index: Int) {
        currentPageIndex = index
    }

    override fun onCurrentListChanged(
        previousList: MutableList<DocumentPage>,
        currentList: MutableList<DocumentPage>
    ) {
        super.onCurrentListChanged(previousList, currentList)
        // New document or boundary recompute — drop per-page line memo so the next highlight
        // re-evaluates against fresh page geometry (font size, page break locations, etc.).
        lastAutoScrolledWord = null
        lastAutoScrolledLine.clear()
    }

    /**
     * Locate the page that contains [absoluteWordIndex] via binary search over `startWordIndex`.
     * Returns -1 when out of range or when the list is empty. Pages are contiguous in word
     * space: page i covers [startWordIndex_i, endWordIndex_i] and endWordIndex_i + 1 ==
     * startWordIndex_{i+1} (modulo holes from `buildBoundaryPages` which skips blank
     * boundaries — handled by clamping to the last page whose start is <= the index).
     */
    private fun findPageIndexForAbsoluteWord(absoluteWordIndex: Int): Int {
        val list = currentList
        if (list.isEmpty()) return -1
        var lo = 0
        var hi = list.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val page = list[mid]
            if (page.startWordIndex <= absoluteWordIndex) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best < 0) return -1
        val page = list[best]
        return if (absoluteWordIndex <= page.endWordIndex) best else -1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val (itemView, pageContent, pageScroll) = if (viewType == VIEW_TYPE_CONTINUOUS) {
            val tv = inflater.inflate(R.layout.item_page_continuous, parent, false) as TextView
            Triple(tv, tv, null)
        } else {
            val root = inflater.inflate(R.layout.item_page, parent, false)
            Triple(root, root.findViewById(R.id.pageContent), root.findViewById<ScrollView>(R.id.pageScroll))
        }
        return PageViewHolder(
            itemView, pageContent, pageScroll,
            onPageClick, onWordLongPress, onWordDoubleTap, onFigureTap, figureLoader
        ).also { it.wireWordGesturesOnce() }
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(
            getItem(position),
            position,
            style,
            highlightedWordIndex,
            highlightColor,
            isActivePage = position == currentPageIndex,
            lastAutoScrolledWord = lastAutoScrolledWord,
            lastAutoScrolledLineForPage = lastAutoScrolledLineForPage,
            recordAutoScroll = ::recordAutoScroll
        )
    }

    override fun onBindViewHolder(
        holder: PageViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        // A non-empty payload list means this is a partial update. We only emit
        // PAYLOAD_HIGHLIGHT, so any other / mixed payload set falls through to full bind for
        // safety (e.g., DefaultItemAnimator can synthesize empty payload calls during
        // animations even though we disable change animations on the RV).
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_HIGHLIGHT }) {
            holder.updateHighlight(
                getItem(position),
                highlightedWordIndex,
                highlightColor,
                isActivePage = position == currentPageIndex,
                lastAutoScrolledWord = lastAutoScrolledWord,
                lastAutoScrolledLineForPage = lastAutoScrolledLineForPage,
                recordAutoScroll = ::recordAutoScroll
            )
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private val lastAutoScrolledLineForPage: (Int) -> Int = { pageNumber ->
        lastAutoScrolledLine.get(pageNumber, NO_LINE)
    }

    private fun recordAutoScroll(pageNumber: Int, wordIndex: Int, line: Int) {
        lastAutoScrolledWord = wordIndex
        lastAutoScrolledLine.put(pageNumber, line)
    }

    class PageViewHolder(
        itemView: View,
        private val pageContent: TextView,
        private val pageScroll: ScrollView?,
        private val onPageClick: (Int) -> Unit,
        private val onWordLongPress: ((Int) -> Unit)?,
        private val onWordDoubleTap: ((Int) -> Unit)?,
        private val onFigureTap: ((Int) -> Unit)?,
        private val figureLoader: ((DocumentPage, PageFigure, (Bitmap?) -> Unit) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {

        // Most recent ACTION_DOWN coordinates on the page TextView, captured so the
        // OnLongClick callback can resolve a touch position to a character offset (the
        // long-click callback itself doesn't carry coordinates).
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var boundPage: DocumentPage? = null

        // Continuous holders render the furniture-blanked page variant; paged holders keep
        // the raw content. All char-offset work in this holder (highlight spans, touch
        // resolution) must use the SAME variant that was set on the TextView.
        private val isContinuous: Boolean get() = pageScroll == null

        private fun displayContentOf(page: DocumentPage): String =
            if (isContinuous) page.continuousContent else page.content

        private fun displayWordStartsOf(page: DocumentPage): IntArray =
            if (isContinuous) page.continuousWordStarts else page.wordStarts

        // Reusable highlight span. Reallocated only when the color changes (theme flip),
        // never per highlight position update — TextView's SpanWatcher invalidates only the
        // affected lines when the same instance is removed and re-set at a new range, so the
        // full-document layout pass that the pre-fix `SpannableString(...)` rebuild forced is
        // gone. Mirrors `PageViewActivity.continuousHighlightSpan`.
        private var highlightSpan: ForegroundColorSpan? = null
        private var highlightSpanColor: Int = 0

        fun bind(
            page: DocumentPage,
            position: Int,
            style: PageStyle?,
            highlightedWordIndex: Int?,
            highlightColor: Int,
            isActivePage: Boolean,
            lastAutoScrolledWord: Int?,
            lastAutoScrolledLineForPage: (Int) -> Int,
            recordAutoScroll: (pageNumber: Int, wordIndex: Int, line: Int) -> Unit
        ) {
            boundPage = page

            // BufferType.SPANNABLE makes TextView wrap the String in a SpannableString it owns
            // (and keep it as Spannable across subsequent setText calls), so updateHighlight's
            // `text as Spannable` cast is total.
            pageContent.setText(displayContentOf(page), TextView.BufferType.SPANNABLE)

            if (style != null) {
                pageContent.textSize = style.fontSizeSp
                pageContent.setTextColor(style.textColor)
                pageScroll?.setBackgroundColor(style.backgroundColor)
                pageContent.setBackgroundColor(style.backgroundColor)
            }

            // Continuous holders only (pageScroll == null): even out the line gap at page
            // seams. Must run after the style block so the padding is derived from the
            // final text size.
            if (pageScroll == null) applyContinuousSeamSpacing()

            applyFigureSpans(page)

            applyHighlightInPlace(page, highlightedWordIndex, highlightColor)

            maybeAutoScrollToHighlight(
                page = page,
                highlightedWordIndex = highlightedWordIndex,
                isActivePage = isActivePage,
                lastAutoScrolledWord = lastAutoScrolledWord,
                lastAutoScrolledLineForPage = lastAutoScrolledLineForPage,
                recordAutoScroll = recordAutoScroll
            )
        }

        /**
         * Partial-bind fast path: mutates the existing Spannable in place. Caller has
         * already verified the payload type. No `setText`, no `SpannableString` allocation,
         * no full layout pass — TextView's SpanWatcher invalidates only the affected line(s).
         */
        fun updateHighlight(
            page: DocumentPage,
            highlightedWordIndex: Int?,
            highlightColor: Int,
            isActivePage: Boolean,
            lastAutoScrolledWord: Int?,
            lastAutoScrolledLineForPage: (Int) -> Int,
            recordAutoScroll: (pageNumber: Int, wordIndex: Int, line: Int) -> Unit
        ) {
            boundPage = page
            applyHighlightInPlace(page, highlightedWordIndex, highlightColor)
            maybeAutoScrollToHighlight(
                page = page,
                highlightedWordIndex = highlightedWordIndex,
                isActivePage = isActivePage,
                lastAutoScrolledWord = lastAutoScrolledWord,
                lastAutoScrolledLineForPage = lastAutoScrolledLineForPage,
                recordAutoScroll = recordAutoScroll
            )
        }

        // Monotonic guard for async figure loads: bumped per bind so a bitmap arriving for
        // a recycled holder (or an older bind of the same holder) is dropped instead of
        // spanning the wrong page's text.
        private var figureGeneration = 0

        /**
         * Mount an ImageSpan at each figure placeholder of THIS holder's display variant:
         * first a neutral placeholder box sized width-fitted from the figure's aspect ratio
         * (so the text reserves final space immediately), then the rendered bitmap swapped
         * in-place when the async loader delivers. Identical bounds on both spans mean the
         * swap needs only an invalidate, not a relayout.
         */
        private fun applyFigureSpans(page: DocumentPage) {
            figureGeneration++
            if (page.figures.isEmpty()) return
            val generation = figureGeneration
            if (pageContent.width > 0) {
                applyFigureSpansForWidth(page, generation)
            } else {
                // First-ever bind: view not measured yet. doOnLayout fires after the initial
                // measure/layout pass; the guard drops the callback if the holder rebound.
                pageContent.doOnLayout {
                    if (boundPage === page && figureGeneration == generation) {
                        applyFigureSpansForWidth(page, generation)
                    }
                }
            }
        }

        private fun applyFigureSpansForWidth(page: DocumentPage, generation: Int) {
            val spannable = pageContent.text as? Spannable ?: return
            val available = pageContent.width - pageContent.paddingLeft - pageContent.paddingRight
            if (available <= 0) return
            for (figure in page.figures) {
                val offset = figureOffsetOf(figure)
                if (offset < 0 || offset + 1 > spannable.length) continue
                val height = (available * figure.aspectRatio).toInt().coerceAtLeast(1)
                val placeholder = ColorDrawable(Constants.FIGURE_PLACEHOLDER_COLOR).apply {
                    setBounds(0, 0, available, height)
                }
                setFigureSpan(spannable, offset, ImageSpan(placeholder, ImageSpan.ALIGN_BOTTOM))
                requestFigureBitmap(page, figure, offset, available, height, generation)
            }
            // Placeholder spans change line metrics; ImageSpan does not implement
            // UpdateLayout, so force the pass explicitly.
            pageContent.requestLayout()
            pageContent.invalidate()
        }

        private fun requestFigureBitmap(
            page: DocumentPage,
            figure: PageFigure,
            offset: Int,
            width: Int,
            height: Int,
            generation: Int
        ) {
            val loader = figureLoader ?: return
            loader(page, figure) { bitmap ->
                if (bitmap == null) return@loader
                if (boundPage !== page || figureGeneration != generation) return@loader
                val spannable = pageContent.text as? Spannable ?: return@loader
                if (offset + 1 > spannable.length) return@loader
                val drawable = BitmapDrawable(pageContent.resources, bitmap).apply {
                    setBounds(0, 0, width, height)
                }
                setFigureSpan(spannable, offset, ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM))
                // Same bounds as the placeholder — no metric change, repaint only.
                pageContent.invalidate()
            }
        }

        private fun setFigureSpan(spannable: Spannable, offset: Int, span: ImageSpan) {
            spannable.getSpans(offset, offset + 1, ImageSpan::class.java).forEach {
                spannable.removeSpan(it)
            }
            spannable.setSpan(span, offset, offset + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private fun figureOffsetOf(figure: PageFigure): Int =
            if (isContinuous) figure.continuousOffset else figure.contentOffset

        /**
         * Figure whose placeholder char sits at the tapped position, or null. Used by the
         * single-tap gesture to route figure taps to the viewer instead of the page-click
         * overlay toggle.
         */
        private fun resolveFigureAt(touchX: Float, touchY: Float): PageFigure? {
            val page = boundPage ?: return null
            if (page.figures.isEmpty()) return null
            val layout = pageContent.layout ?: return null
            val xInLayout = (touchX - pageContent.totalPaddingLeft + pageContent.scrollX).coerceAtLeast(0f)
            val yInLayout = (touchY - pageContent.totalPaddingTop + pageContent.scrollY).coerceAtLeast(0f)
            val line = layout.getLineForVertical(yInLayout.toInt())
            val charOffset = layout.getOffsetForHorizontal(line, xInLayout)
            return page.figures.firstOrNull {
                val offset = figureOffsetOf(it)
                offset >= 0 && charOffset in offset..(offset + 1)
            }
        }

        /**
         * Continuous mode stacks one TextView per page with zero inter-item gap, but
         * StaticLayout never applies the lineSpacingMultiplier extra below a layout's LAST
         * line — so the visual gap at every page seam was one line-spacing extra tighter
         * than the in-page line gap. Compensate with bottom padding equal to lineHeight
         * minus the raw font height (exactly the omitted extra, whatever multiplier the
         * layout XML declares), recomputed per bind because the user-adjustable font size
         * changes both terms.
         */
        private fun applyContinuousSeamSpacing() {
            val fontHeight = pageContent.paint.getFontMetricsInt(null)
            val seamPadding = (pageContent.lineHeight - fontHeight).coerceAtLeast(0)
            if (pageContent.paddingBottom != seamPadding) {
                pageContent.updatePadding(bottom = seamPadding)
            }
        }

        private fun applyHighlightInPlace(
            page: DocumentPage,
            highlightedWordIndex: Int?,
            highlightColor: Int
        ) {
            // Defensive: full bind always uses BufferType.SPANNABLE so this cast is total in
            // the partial-bind path. Fall through to a full re-set if a recycled holder somehow
            // arrives with a non-Spannable text (e.g., a future code path that mis-uses
            // `TextView.text =`).
            val spannable = pageContent.text as? Spannable ?: run {
                pageContent.setText(displayContentOf(page), TextView.BufferType.SPANNABLE)
                pageContent.text as? Spannable ?: return
            }

            val existing = highlightSpan
            if (existing != null) spannable.removeSpan(existing)

            val range = pageCharRange(page, highlightedWordIndex) ?: run {
                // Word isn't on this page — leave the span detached.
                return
            }

            val span = if (existing == null || highlightSpanColor != highlightColor) {
                ForegroundColorSpan(highlightColor).also {
                    highlightSpan = it
                    highlightSpanColor = highlightColor
                }
            } else existing
            spannable.setSpan(span, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private fun maybeAutoScrollToHighlight(
            page: DocumentPage,
            highlightedWordIndex: Int?,
            isActivePage: Boolean,
            lastAutoScrolledWord: Int?,
            lastAutoScrolledLineForPage: (Int) -> Int,
            recordAutoScroll: (pageNumber: Int, wordIndex: Int, line: Int) -> Unit
        ) {
            val scroll = pageScroll ?: return
            if (!isActivePage) return
            if (highlightedWordIndex == null) return
            if (highlightedWordIndex == lastAutoScrolledWord) return
            val range = pageCharRange(page, highlightedWordIndex) ?: return

            pageContent.doOnLayout {
                val layout = pageContent.layout ?: return@doOnLayout
                val line = layout.getLineForOffset(range.first)
                if (line == lastAutoScrolledLineForPage(page.pageNumber)) {
                    recordAutoScroll(page.pageNumber, highlightedWordIndex, line)
                    return@doOnLayout
                }
                val lineTop = layout.getLineTop(line)
                val targetY = (lineTop - scroll.height / 2 + pageContent.lineHeight / 2)
                    .coerceAtLeast(0)
                scroll.smoothScrollTo(0, targetY)
                recordAutoScroll(page.pageNumber, highlightedWordIndex, line)
            }
        }

        /**
         * Wire the touch / long-press / double-tap listeners exactly once at holder creation.
         * Closures read `boundPage` and the latch fields lazily, so they stay correct across
         * recycles without re-attaching listeners per bind.
         *
         * Single OnTouchListener feeds BOTH the lastTouchX/Y latch (used by long-press) AND
         * the GestureDetector (used by double-tap). Returning false from the listener lets
         * View.onTouchEvent run, which is what powers the long-press timer (TextView is
         * longClickable as soon as setOnLongClickListener is attached). The double-tap
         * detector and the long-press timer don't conflict: each tap of a double-tap is
         * shorter than the long-press threshold, so long-press never fires on a double-tap.
         */
        fun wireWordGesturesOnce() {
            val gestureDetector = GestureDetector(itemView.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (onFigureTap != null) {
                        val figure = resolveFigureAt(e.x, e.y)
                        if (figure != null) {
                            onFigureTap.invoke(figure.figureIndex)
                            return true
                        }
                    }
                    onPageClick(bindingAdapterPosition)
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val wordIndex = resolveWordIndexAt(e.x, e.y) ?: return false
                    onWordDoubleTap?.invoke(wordIndex)
                    return onWordDoubleTap != null
                }
            })

            pageContent.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                gestureDetector.onTouchEvent(event)
                false
            }

            if (onWordLongPress != null) {
                pageContent.setOnLongClickListener {
                    val wordIndex = resolveWordIndexAt(lastTouchX, lastTouchY)
                        ?: return@setOnLongClickListener false
                    onWordLongPress.invoke(wordIndex)
                    true
                }
            }
        }

        /**
         * Map a touch (x, y) within this page's body TextView to an absolute word index, or
         * null if the layout isn't ready or the touch lands outside any tokenized word range.
         * Shared by long-press and double-tap so both gestures resolve coordinates identically.
         */
        private fun resolveWordIndexAt(touchX: Float, touchY: Float): Int? {
            val page = boundPage ?: return null
            val tv = pageContent
            val layout = tv.layout ?: return null
            val xInLayout = (touchX - tv.totalPaddingLeft + tv.scrollX).coerceAtLeast(0f)
            val yInLayout = (touchY - tv.totalPaddingTop + tv.scrollY).coerceAtLeast(0f)
            val line = layout.getLineForVertical(yInLayout.toInt())
            val charOffset = layout.getOffsetForHorizontal(line, xInLayout)
            return absoluteWordIndexAt(page, charOffset)
        }

        private fun absoluteWordIndexAt(page: DocumentPage, charOffset: Int): Int? =
            Companion.absoluteWordIndexAt(page, charOffset, isContinuous)

        /**
         * Maps an absolute word index to a character range within the page content using the
         * pre-computed word starts of THIS holder's display variant. O(1). Returns null if
         * the word isn't on this page, the index is out of range, or the word is blanked
         * page furniture in the continuous variant (zero-length: nothing to highlight).
         */
        private fun pageCharRange(page: DocumentPage, absoluteWordIndex: Int?): IntRange? {
            if (absoluteWordIndex == null) return null
            if (absoluteWordIndex < page.startWordIndex || absoluteWordIndex > page.endWordIndex) return null
            val rel = absoluteWordIndex - page.startWordIndex
            val starts = displayWordStartsOf(page)
            if (rel < 0 || rel >= starts.size) return null
            val start = starts[rel]
            var end = start
            val content = displayContentOf(page)
            while (end < content.length && !content[end].isWhitespace()) end++
            if (end <= start) return null
            return start until end
        }
    }

    private class PageDiffCallback : DiffUtil.ItemCallback<DocumentPage>() {
        override fun areItemsTheSame(oldItem: DocumentPage, newItem: DocumentPage): Boolean {
            return oldItem.pageNumber == newItem.pageNumber
        }

        override fun areContentsTheSame(oldItem: DocumentPage, newItem: DocumentPage): Boolean {
            // Equality is hand-written on DocumentPage to exclude `wordStarts` (a derived
            // IntArray whose default reference equality would otherwise force a full re-bind
            // every time the same logical page list is rebuilt). See DocumentPage equals().
            return oldItem == newItem
        }
    }

    companion object {
        private val PAYLOAD_HIGHLIGHT = Any()
        private const val NO_LINE = -1
        const val VIEW_TYPE_PAGED = 0
        const val VIEW_TYPE_CONTINUOUS = 1

        /**
         * [continuous] selects which display variant's word starts to search — callers must
         * pass the variant matching the TextView the char offset came from (continuous
         * holders render [DocumentPage.continuousContent], paged holders the raw content).
         */
        fun absoluteWordIndexAt(page: DocumentPage, charOffset: Int, continuous: Boolean = false): Int? {
            val starts = if (continuous) page.continuousWordStarts else page.wordStarts
            if (starts.isEmpty() || charOffset < 0) return null
            var lo = 0
            var hi = starts.size - 1
            var best = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (starts[mid] <= charOffset) {
                    best = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return page.startWordIndex + best
        }
    }
}

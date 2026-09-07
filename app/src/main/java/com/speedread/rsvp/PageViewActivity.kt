package com.speedread.rsvp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.speedread.rsvp.databinding.ActivityPageViewBinding
import com.speedread.rsvp.engine.PageViewScrollMode
import com.speedread.rsvp.engine.RsvpState
import com.speedread.rsvp.tts.PlaybackCoordinator
import com.speedread.rsvp.tts.PlaybackMode
import com.speedread.rsvp.ui.PageAdapter
import com.speedread.rsvp.ui.PageScrollState
import com.speedread.rsvp.ui.PageStyle
import com.speedread.rsvp.ui.PageViewSettingsSheet
import com.speedread.rsvp.ui.PdfPageAdapter
import com.speedread.rsvp.ui.ZoomableImageView
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PageViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPageViewBinding
    private val viewModel: PageViewViewModel by viewModels()
    private lateinit var pageAdapter: PageAdapter
    private lateinit var pdfPageAdapter: PdfPageAdapter

    @Inject lateinit var themeManager: ThemeManager
    @Inject lateinit var playbackCoordinator: PlaybackCoordinator
    @Inject lateinit var pageViewPositionManager: PageViewPositionManager
    private var snapHelper = PagerSnapHelper()

    // Word index most recently emitted via PlaybackCoordinator.currentWord. Drives the
    // live highlight while playback (RSVP or TTS) is active. Null = no playback-driven
    // highlight; the static RSVP-resume highlight (viewModel.highlightedWordIndex) wins
    // through resolveActiveHighlightIndex(). Cleared whenever playback reaches Finished/Idle
    // or the active mode changes so the resume highlight reappears.
    private var livePlaybackWordIndex: Int? = null

    // Cached coordinator state used to drive the menu-icon swap (Listen <-> Pause) and to
    // decide play/pause action. Updated by the activeMode/state observers so onCreateOptionsMenu
    // can render the right glyph without race conditions against the unified state flow.
    private var coordinatorMode: PlaybackMode = PlaybackMode.RSVP
    private var coordinatorState: RsvpState = RsvpState.Idle
    private var _currentPageIndex = 0 // Track current page independently
    private var currentPageIndex: Int
        get() = _currentPageIndex
        set(value) {
            Logger.d("PageViewActivity", "currentPageIndex changed from $_currentPageIndex to $value")
            _currentPageIndex = value
            // Mirror the active page index into the adapter so it can gate auto-scroll-to-
            // highlight on the visible page only. Lazily guarded with `::pageAdapter.isInitialized`
            // because this setter can fire (via `_currentPageIndex` defaults / early init code)
            // before `setupRecyclerView` has constructed the adapter.
            if (::pageAdapter.isInitialized) pageAdapter.setCurrentPage(value)
            // Page changes flip the "highlight is on the visible page" answer in paged mode,
            // so re-evaluate the scroll-to-highlight FAB. Guarded with `::binding.isInitialized`
            // because this setter fires from `_currentPageIndex`'s default-value initialization
            // before onCreate runs.
            if (::binding.isInitialized) refreshScrollToHighlightFab()
        }
    private var isPdfMode = false

    // Cached current scroll mode. CONTINUOUS renders all pages in a single vertical
    // RecyclerView scroll; PAGED uses the horizontal snap-paging RecyclerView. PDF native
    // render always falls back to the paged RecyclerView regardless of this setting.
    // Driven by ThemeManager.pageViewScrollMode.
    private var isPageViewOnlyMode = false
    private var currentScrollMode: PageViewScrollMode = PageViewScrollMode.CONTINUOUS

    // Suppresses the continuous-scroll listener while we programmatically jump to the
    // RSVP-resume word position, so the initial seek isn't immediately overwritten by an
    // onScrollChanged callback firing with the same scroll Y.
    private var suppressContinuousScrollTracking = false

    // Authoritative RSVP-continuation word when playback is not live and passive scroll/page
    // should not decide the exit position. Set by:
    //   - long-press on a word (explicit user pick),
    //   - playback leaving active state (Finished / Idle / mode flip) via
    //     persistLivePositionBeforeClear, so the last played word survives the clear.
    // Takes precedence over passive scroll tracking and paged-page fallbacks in
    // resolveExitWordPosition. Cleared when the next word is emitted by either engine (the
    // live playback position becomes the new truth).
    private var userPickedContinuationWord: Int? = null


    // Explicit scroll lifecycle — see PageScrollState for the transition contract. The
    // Initializing -> Programmatic -> Idle progression replaces the old two-Boolean + 1000ms
    // settling-timer approach so state changes are driven by RecyclerView events rather than
    // wall-clock timing.
    private var pageScrollState: PageScrollState = PageScrollState.Initializing

    companion object {
        private const val EXTRA_DOCUMENT = "extra_document"
        private const val EXTRA_DOCUMENT_TEXT = "extra_document_text"
        private const val EXTRA_DOCUMENT_TITLE = "extra_document_title"
        private const val EXTRA_CURRENT_POSITION = "extra_current_position"

        // Temporary storage to avoid TransactionTooLargeException
        private var temporaryText: String? = null
        private var temporaryTitle: String? = null

        fun createIntent(
            context: Context,
            documentId: Long? = null,
            documentText: String? = null,
            documentTitle: String? = null,
            currentPosition: Int = 0
        ): Intent {
            return Intent(context, PageViewActivity::class.java).apply {
                documentId?.let { putExtra(EXTRA_DOCUMENT, it) }
                documentText?.let { putExtra(EXTRA_DOCUMENT_TEXT, it) }
                documentTitle?.let { putExtra(EXTRA_DOCUMENT_TITLE, it) }
                putExtra(EXTRA_CURRENT_POSITION, currentPosition)
            }
        }
        
        fun setTemporaryText(text: String, title: String) {
            temporaryText = text
            temporaryTitle = title
        }
        
        private fun getAndClearTemporaryText(): Pair<String?, String?> {
            val text = temporaryText
            val title = temporaryTitle
            temporaryText = null
            temporaryTitle = null
            return Pair(text, title)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPageViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentScrollMode = themeManager.getCurrentPageViewScrollMode()
        isPageViewOnlyMode = themeManager.isPageViewOnlyMode()

        setupUI()
        setupRecyclerView()
        observeViewModel()
        registerBackPressedHandler()
        handleIntent()
    }

    private val overlayAutoHide = Runnable { hideOverlay() }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        // Immersive: draw edge-to-edge and hide status/nav bars. Users swipe from the edge
        // to reveal system bars (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, binding.root)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding.pageIndicator.setOnClickListener {
            cancelOverlayAutoHide()
            showPageJumpDialog()
        }

        if (isPageViewOnlyMode) {
            binding.ttsFab.visibility = View.GONE
            binding.scrollToHighlightFab.visibility = View.GONE
            binding.bookmarksFab.setOnClickListener {
                showBookmarksDialog()
            }
        } else {
            binding.ttsFab.setOnClickListener {
                togglePlayback()
            }
            refreshPlaybackFab()

            binding.scrollToHighlightFab.setOnClickListener {
                scrollToActiveHighlight()
            }
            refreshScrollToHighlightFab()
        }

    }

    private fun toggleOverlay() {
        if (binding.toolbar.visibility == View.VISIBLE) hideOverlay() else showOverlay()
    }

    private fun showOverlay() {
        binding.toolbar.visibility = View.VISIBLE
        binding.pageIndicator.visibility = View.VISIBLE
        if (isPageViewOnlyMode) {
            binding.bookmarksFab.visibility = View.VISIBLE
        }
        WindowInsetsControllerCompat(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
        scheduleOverlayAutoHide()
    }

    private fun hideOverlay() {
        binding.toolbar.visibility = View.GONE
        binding.pageIndicator.visibility = View.GONE
        binding.bookmarksFab.visibility = View.GONE
        WindowInsetsControllerCompat(window, binding.root)
            .hide(WindowInsetsCompat.Type.systemBars())
        cancelOverlayAutoHide()
    }

    private fun scheduleOverlayAutoHide() {
        cancelOverlayAutoHide()
        binding.root.postDelayed(overlayAutoHide, Constants.PAGE_VIEW_OVERLAY_AUTO_HIDE_MS)
    }

    private fun cancelOverlayAutoHide() {
        binding.root.removeCallbacks(overlayAutoHide)
    }

    private fun setupRecyclerView() {
        // Setup text page adapter
        pageAdapter = PageAdapter(
            onPageClick = { _ ->
                toggleOverlay()
            },
            onWordLongPress = { absoluteWordIndex ->
                // Long-press on a word in paged mode marks it as the RSVP continuation point
                // (mirrors the continuous-mode handler in PageViewActivity).
                applyLongPressContinuation(absoluteWordIndex)
            },
            onWordDoubleTap = { absoluteWordIndex ->
                // Double-tap on a word in paged mode drops a quick bookmark at that word
                // (mirrors the continuous-mode handler in PageViewActivity).
                applyDoubleTapBookmark(absoluteWordIndex)
            },
            onFigureTap = { figureIndex ->
                showFigureViewer(figureIndex)
            },
            figureLoader = { _, figure, onLoaded ->
                // lifecycleScope launches on Main, so the callback lands on the main thread
                // as the adapter contract requires.
                lifecycleScope.launch {
                    val bitmap = try {
                        viewModel.getFigureBitmap(figure.figureIndex)
                    } catch (e: Exception) {
                        Logger.w("PageViewActivity", "Figure load failed (index=${figure.figureIndex})", e)
                        null
                    }
                    onLoaded(bitmap)
                }
            }
        )

        // Setup PDF page adapter
        pdfPageAdapter = PdfPageAdapter(
            onPageClick = { _ ->
                // Handle PDF page clicks if needed
            },
            onPageVisible = { pageIndex ->
                // Render PDF page when it becomes visible
                viewModel.renderPdfPage(pageIndex)
                // Preload nearby pages
                viewModel.preloadPdfPages(pageIndex)
            }
        )

        // Apply initial page style from user settings so page view matches RSVP appearance.
        pageAdapter.setStyle(currentPageStyle())
        if (!isPageViewOnlyMode) {
            pageAdapter.setHighlight(resolveActiveHighlightIndex(), orpHighlightColor())
        }
        // Push the (possibly already-set via deep-link / resume) currentPageIndex into the
        // adapter at wire-up time so the first bind on the active page knows it's active even
        // before the setter runs again. Subsequent updates flow through `currentPageIndex`'s
        // setter automatically.
        pageAdapter.setCurrentPage(currentPageIndex)

        with(binding.pagesRecyclerView) {
            adapter = pageAdapter
            itemAnimator = null

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (currentScrollMode != PageViewScrollMode.CONTINUOUS) return
                    refreshScrollToHighlightFab()
                    if (suppressContinuousScrollTracking) return
                    val wordIndex = computeContinuousWordIndexAtTopRV() ?: return
                    viewModel.setContinuousWordPosition(wordIndex)
                    updatePageIndicatorImmediate()
                    savePageViewPosition(wordIndex)
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (currentScrollMode == PageViewScrollMode.CONTINUOUS) return
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) return

                    when (pageScrollState) {
                        PageScrollState.Programmatic -> {
                            pageScrollState = PageScrollState.Idle
                        }
                        PageScrollState.Initializing -> {}
                        PageScrollState.Idle -> {
                            val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                            val visiblePosition = lm.findFirstCompletelyVisibleItemPosition()
                            if (visiblePosition != RecyclerView.NO_POSITION &&
                                visiblePosition != currentPageIndex
                            ) {
                                currentPageIndex = visiblePosition
                                updatePageIndicatorImmediate()
                                updateCurrentPosition()
                                savePageViewPosition(resolveExitWordPosition())
                                if (isPdfMode) {
                                    viewModel.preloadPdfPages(currentPageIndex)
                                }
                            }
                        }
                    }
                }
            })
        }

        applyRecyclerViewMode()
    }

    private fun applyRecyclerViewMode() {
        val rv = binding.pagesRecyclerView
        rv.onFlingListener = null
        if (currentScrollMode == PageViewScrollMode.CONTINUOUS && !isPdfMode) {
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
            pageAdapter.setScrollMode(PageViewScrollMode.CONTINUOUS)
        } else {
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            snapHelper.attachToRecyclerView(rv)
            pageAdapter.setScrollMode(PageViewScrollMode.PAGED)
        }
    }

    private fun currentPageStyle(): PageStyle {
        val colors = themeManager.getCurrentPageViewColors()
        return PageStyle(
            fontSizeSp = themeManager.getCurrentPageViewFontSize(),
            textColor = colors.textColor,
            backgroundColor = colors.backgroundColor
        )
    }

    // Same orange used for the ORP highlight in RSVP, picked per light/dark mode for contrast.
    // Used for both the static RSVP-resume highlight and the live TTS-spoken-word highlight,
    // so the visual rule "the highlighted word is the current word" stays consistent across
    // Page View modes and matches RSVP. Theme-aware: light vs dark uses different shades for
    // contrast against the user-selected page background.
    private fun orpHighlightColor(): Int {
        val nightModeFlags = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            android.graphics.Color.parseColor("#FF8C42")
        } else {
            android.graphics.Color.parseColor("#FF6B35")
        }
    }

    /**
     * The word index that should currently be drawn highlighted. While playback (RSVP or
     * TTS) is active, the live word index wins; otherwise we fall back to the static
     * RSVP-resume position the VM owns.
     */
    private fun resolveActiveHighlightIndex(): Int? =
        livePlaybackWordIndex ?: viewModel.highlightedWordIndex.value

    /**
     * Push the resolved highlight to whichever rendering layer is visible. Called from every
     * upstream observer (RSVP-resume changes, TTS word advance, mode/state flips) so neither
     * source has to know which adapter is mounted.
     */
    private fun refreshHighlight() {
        if (isPageViewOnlyMode) return
        val index = resolveActiveHighlightIndex()
        if (!isPdfMode) {
            pageAdapter.setHighlight(index, orpHighlightColor())
        }
        if (isContinuousActive()) updatePageIndicatorImmediate()
        refreshScrollToHighlightFab()
    }

    /**
     * Show the "scroll to highlighted word" mini-FAB only when the user would benefit from it:
     *   - There is an active highlight (TTS live word OR static RSVP-resume position).
     *   - The document is not in PDF native render (no per-word position data, no useful jump).
     *   - The highlight is currently OFF-screen — if the user can already see the highlighted
     *     word, the button would be a redundant distraction. "On-screen" is computed against
     *     the active rendering layer:
     *       * Continuous mode: the RecyclerView page containing the highlighted word is
     *         within the visible item range.
     *       * Paged mode: the page that contains the highlight equals the currently visible
     *         RecyclerView page.
     * Called whenever the highlight, scroll position, current page, or rendering mode changes.
     */
    private fun refreshScrollToHighlightFab() {
        if (isPageViewOnlyMode) return
        val shouldShow = !isPdfMode &&
            resolveActiveHighlightIndex() != null &&
            !isActiveHighlightOnScreen()
        binding.scrollToHighlightFab.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    /**
     * Returns true when the active highlight's word is rendered within the currently visible
     * viewport. Returns true (i.e. "no need to scroll") when the highlight is null, the active
     * mode is PDF, or the layout hasn't been measured yet — these are all cases where the FAB
     * should stay hidden, so true is the safer default.
     */
    private fun isActiveHighlightOnScreen(): Boolean {
        val index = resolveActiveHighlightIndex() ?: return true
        if (isPdfMode) return true
        val pageIdx = viewModel.findPageIndexForWord(index)
        if (isContinuousActive()) {
            val lm = binding.pagesRecyclerView.layoutManager as? LinearLayoutManager ?: return true
            val first = lm.findFirstVisibleItemPosition()
            val last = lm.findLastVisibleItemPosition()
            return pageIdx in first..last
        }
        return pageIdx == currentPageIndex
    }

    /**
     * Scroll/jump the visible content layer to whichever word is currently highlighted.
     * Routed by the active rendering mode: continuous uses [seekContinuousToWordRV];
     * paged uses [autoFlipPagedToWord] which animates a smooth page swap. PDF mode is
     * filtered out by the FAB's visibility — but guarded here too in case the click
     * somehow lands.
     */
    private fun scrollToActiveHighlight() {
        val index = resolveActiveHighlightIndex() ?: return
        if (isPdfMode) return
        if (isContinuousActive()) {
            seekContinuousToWordRV(index)
        } else {
            autoFlipPagedToWord(index)
        }
    }

    /**
     * Paged-mode auto page-flip: when TTS speaks a word that lives on a different page than
     * the one currently displayed, smooth-scroll the RecyclerView to that page so listeners
     * always see the right page without manual swiping. Continuous mode does NOT call this —
     * its single-scroll TextView intentionally lets the user read freely while TTS plays.
     * No-op for PDF native render (no per-word position data there).
     */
    private fun autoFlipPagedToWord(wordIndex: Int) {
        val targetPage = viewModel.findPageIndexForWord(wordIndex)
        if (targetPage == currentPageIndex) return
        currentPageIndex = targetPage
        scrollToPage(targetPage, smooth = true)
        updatePageIndicatorImmediate()
    }

    /**
     * Toggle TTS playback for the current document. Idempotent — safe to call repeatedly.
     * Loads the document into the shared coordinator if it isn't already loaded (the engine
     * tokenizes via the same TextProcessor PageViewViewModel uses, so word indices align),
     * seeks to the resume position on first play, and flips active mode to TTS so currentWord
     * starts emitting. Subsequent taps pause/resume in place.
     */
    private fun togglePlayback() {
        val text = viewModel.getRawText()
        if (text.isBlank()) {
            Toast.makeText(this, "No document text to read", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                if (coordinatorState is RsvpState.Playing) {
                    playbackCoordinator.pause()
                    return@launch
                }

                // Compare in the SAME unit: getAbsoluteWordCount() is the single-word token
                // count regardless of chunking. getTotalWords() is the CHUNK count, which
                // never matches the single-token count when chunkSize > 1 — that mismatch
                // forced a full document re-tokenize on every play tap here.
                val currentDoc = viewModel.currentDocument.value
                val targetWordCount = viewModel.getTotalWordCount()
                val coordinatorCount = playbackCoordinator.getAbsoluteWordCount()
                val docChanged = currentDoc?.id != playbackCoordinator.getCurrentDocumentId()
                val hashChanged = currentDoc?.contentHash != playbackCoordinator.getCurrentContentHash()

                if (targetWordCount == 0 || coordinatorCount != targetWordCount || docChanged || hashChanged) {
                    playbackCoordinator.loadText(
                        text = text,
                        documentId = currentDoc?.id,
                        contentHash = currentDoc?.contentHash
                    )
                }

                val resumeWord = resolveExitWordPosition()
                playbackCoordinator.seekToAbsoluteWordPosition(resumeWord)
                playbackCoordinator.play()
            } catch (e: Exception) {
                Logger.e("PageViewActivity", "togglePlayback failed", e)
                Toast.makeText(
                    this@PageViewActivity,
                    "Playback error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun observeViewModel() {
        // All collectors below are view-touching state syncs. They MUST pause while the
        // activity is stopped (in the back stack) — otherwise an offscreen PageViewActivity
        // re-runs full-document `setText`/layout passes whenever the user toggles
        // continuous/paged or changes colors/font in the foreground Options screen, which
        // can ANR the app and (with rapid toggling) lock up the system. `repeatOnLifecycle`
        // cancels every child collector at onStop and resubscribes at onStart; StateFlows
        // re-emit their latest value on resubscribe so the UI is back in sync immediately.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Keep page style in sync with Page View font size changes made in Options.
                launch {
                    themeManager.pageViewFontSize.collect {
                        pageAdapter.setStyle(currentPageStyle())
                    }
                }

                launch {
                    themeManager.pageViewColors.collect {
                        pageAdapter.setStyle(currentPageStyle())
                    }
                }

                // React to scroll-mode toggles from the settings sheet without restarting the
                // activity. Switching mid-document carries the active word position across so the
                // user lands on the same content in either layer.
                launch {
                    themeManager.pageViewScrollMode.collect { mode ->
                        if (mode == currentScrollMode) return@collect
                        val carryWordIndex = resolveExitWordPosition()
                        currentScrollMode = mode
                        applyRecyclerViewMode()
                        pageAdapter.submitList(viewModel.pages.value)
                        when (mode) {
                            PageViewScrollMode.CONTINUOUS -> {
                                seekContinuousToWordRV(carryWordIndex)
                            }
                            PageViewScrollMode.PAGED -> {
                                if (!isPdfMode) {
                                    val pageIdx = viewModel.findPageIndexForWord(carryWordIndex)
                                    currentPageIndex = pageIdx
                                    scrollToPage(pageIdx, smooth = false)
                                }
                            }
                        }
                        if (!isPageViewOnlyMode) {
                            pageAdapter.setHighlight(resolveActiveHighlightIndex(), orpHighlightColor())
                        }
                        updatePageIndicatorImmediate()
                        refreshScrollToHighlightFab()
                    }
                }

                // Keep the highlighted word (where RSVP left off) in sync with the VM.
                launch {
                    viewModel.highlightedWordIndex.collect { _ ->
                        refreshHighlight()
                    }
                }

                // TTS observers — when listening is active in this activity, currentWord drives the
                // live highlight per spoken word. activeMode + state guard which engine owns
                // currentWord (RSVP fragment may also be subscribed) and whether to keep the
                // highlight pinned (Paused) or fall back to the resume position (Idle/Finished).
                launch {
                    playbackCoordinator.activeMode.collect { mode ->
                        val previousMode = coordinatorMode
                        coordinatorMode = mode
                        if (mode != previousMode && livePlaybackWordIndex != null) {
                            persistLivePositionBeforeClear()
                            livePlaybackWordIndex = null
                        }
                        refreshHighlight()
                        invalidateOptionsMenu()
                        refreshPlaybackFab()
                    }
                }

                launch {
                    playbackCoordinator.state.collect { state ->
                        coordinatorState = state
                        if (state is RsvpState.Finished || state is RsvpState.Idle) {
                            persistLivePositionBeforeClear()
                            livePlaybackWordIndex = null
                        }
                        refreshHighlight()
                        invalidateOptionsMenu()
                        refreshPlaybackFab()
                    }
                }

                launch {
                    playbackCoordinator.currentWord.collect { word ->
                        val position = word?.absoluteStartIndex ?: return@collect
                        livePlaybackWordIndex = position
                        userPickedContinuationWord = null
                        refreshHighlight()
                        if (!isPdfMode) {
                            if (isContinuousActive()) {
                                if (coordinatorMode == PlaybackMode.RSVP &&
                                    !isActiveHighlightOnScreen()
                                ) {
                                    seekContinuousToWordRV(position)
                                }
                            } else {
                                autoFlipPagedToWord(position)
                            }
                        }
                    }
                }

                // Observe PDF mode changes
                launch {
                    viewModel.isPdfMode.collect { pdfMode ->
                        if (pdfMode != isPdfMode) {
                            isPdfMode = pdfMode
                            switchAdapter()
                            applyScrollModeVisibility()

                            // Update menu item when mode changes
                            invalidateOptionsMenu()
                            // PDF mode has no per-word position data; entering it forces the
                            // scroll-to-highlight FAB hidden, leaving it un-flips the visibility.
                            refreshScrollToHighlightFab()
                        }
                    }
                }

                // Observe text pages
                launch {
                    viewModel.pages.collect { pages ->
                        if (!isPdfMode && pages.isNotEmpty()) {
                            Logger.d("PageViewActivity", "Text pages loaded: ${pages.size} pages")
                            pageAdapter.submitList(pages) {
                                if (isContinuousActive()) {
                                    seekContinuousToWordRV(viewModel.continuousWordPosition.value)
                                } else {
                                    updatePageFromViewModel()
                                }
                            }
                        }
                    }
                }

                // Observe PDF pages
                launch {
                    viewModel.pdfPages.collect { pdfPages ->
                        if (isPdfMode && pdfPages.isNotEmpty()) {
                            // pdfPages emits on EVERY per-page state change (render complete,
                            // preload, bitmap eviction), not just the initial population.
                            // Only the first population may position the viewport — re-running
                            // updatePageFromViewModel on later emissions force-scrolled back
                            // to the ViewModel's page on every render completion, snapping the
                            // viewport to page 0 while the user swiped forward (each snap
                            // evicted/re-rendered pages, emitting again: an endless loop).
                            // VM-driven navigation is handled by the currentPageIndex
                            // collector below.
                            val isFirstPopulation = pdfPageAdapter.itemCount == 0
                            if (isFirstPopulation) {
                                Logger.d("PageViewActivity", "PDF pages loaded: ${pdfPages.size} pages")
                            }
                            pdfPageAdapter.submitList(pdfPages) {
                                if (isFirstPopulation) updatePageFromViewModel()
                            }
                        }
                    }
                }

                launch {
                    viewModel.documentTitle.collect { title ->
                        supportActionBar?.title = title ?: "Document"
                    }
                }

                launch {
                    viewModel.currentPageIndex.collect { pageIndex ->
                        Logger.d("PageViewActivity", "ViewModel currentPageIndex changed: $pageIndex, current local index: $currentPageIndex")
                        if (pageIndex >= 0 && pageIndex != currentPageIndex) {
                            Logger.d("PageViewActivity", "ViewModel overriding currentPageIndex from $currentPageIndex to $pageIndex")
                            currentPageIndex = pageIndex
                            // Only auto-scroll during the initial load. Once the activity is Idle,
                            // the ViewModel's currentPageIndex has already been pushed TO by the
                            // scroll listener — auto-scrolling here would create a feedback loop.
                            if (pageScrollState == PageScrollState.Initializing) {
                                scrollToPage(currentPageIndex, smooth = false)
                            }
                            updatePageIndicatorImmediate()

                            // If in PDF mode, start rendering the current page
                            if (isPdfMode) {
                                viewModel.renderPdfPage(currentPageIndex)
                                viewModel.preloadPdfPages(currentPageIndex)
                            }
                        }
                    }
                }

                launch {
                    viewModel.errorMessage.collect { message ->
                        message?.let {
                            Toast.makeText(this@PageViewActivity, it, Toast.LENGTH_LONG).show()
                            viewModel.clearErrorMessage()
                        }
                    }
                }
            }
        }
    }
    
    private fun switchAdapter() {
        if (isPdfMode) {
            binding.pagesRecyclerView.adapter = pdfPageAdapter
        } else {
            binding.pagesRecyclerView.adapter = pageAdapter
        }
    }
    
    private fun updatePageFromViewModel() {
        val targetPageIndex = viewModel.currentPageIndex.value
        Logger.d("PageViewActivity", "updatePageFromViewModel: targetPageIndex=$targetPageIndex")
        if (targetPageIndex >= 0) {
            currentPageIndex = targetPageIndex
            scrollToPage(currentPageIndex, smooth = false)
            updatePageIndicatorImmediate()
        }
    }

    private fun handleIntent() {
        val documentId = intent.getLongExtra(EXTRA_DOCUMENT, -1L)
        val documentText = intent.getStringExtra(EXTRA_DOCUMENT_TEXT)
        val documentTitle = intent.getStringExtra(EXTRA_DOCUMENT_TITLE)
        val currentPosition = intent.getIntExtra(EXTRA_CURRENT_POSITION, 0)

        // Check for temporary text first
        val (tempText, tempTitle) = getAndClearTemporaryText()

        when {
            documentId != -1L -> {
                val restoredPosition = if (currentPosition == 0 &&
                    pageViewPositionManager.hasSavedPosition(documentId)
                ) {
                    pageViewPositionManager.getSavedPosition(documentId)
                } else {
                    currentPosition
                }
                viewModel.loadDocumentById(
                    documentId,
                    restoredPosition,
                    preferTextMode = themeManager.getCurrentPageViewContentMode() ==
                        Constants.PAGE_VIEW_CONTENT_MODE_TEXT
                )
            }
            tempText != null -> {
                viewModel.loadTextDocument(tempText, tempTitle, currentPosition)
            }
            documentText != null -> {
                viewModel.loadTextDocument(documentText, documentTitle, currentPosition)
            }
            else -> {
                Toast.makeText(this, "No document to display", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun updatePageIndicator() {
        val layoutManager = binding.pagesRecyclerView.layoutManager as? LinearLayoutManager
        layoutManager?.let {
            val visiblePosition = it.findFirstCompletelyVisibleItemPosition()
            if (visiblePosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                currentPageIndex = visiblePosition
                updatePageIndicatorImmediate()
            }
        }
    }
    
    private fun updatePageIndicatorImmediate() {
        val pageIndex = if (isContinuousActive()) {
            val lm = binding.pagesRecyclerView.layoutManager as? LinearLayoutManager
            lm?.findFirstVisibleItemPosition()?.takeIf { it != RecyclerView.NO_POSITION } ?: currentPageIndex
        } else {
            currentPageIndex
        }
        val info = viewModel.getDisplayPageInfo(pageIndex)
        binding.pageIndicator.text = "${info.current} / ${info.total}"
    }

    private fun scrollToPage(pageIndex: Int, smooth: Boolean = true) {
        val adapterCount = if (isPdfMode) pdfPageAdapter.itemCount else pageAdapter.itemCount
        Logger.d("PageViewActivity", "scrollToPage: pageIndex=$pageIndex, adapterCount=$adapterCount, smooth=$smooth, isPdfMode=$isPdfMode")

        if (pageIndex < 0 || pageIndex >= adapterCount) {
            Logger.w("PageViewActivity", "Cannot scroll to page $pageIndex, invalid index (adapter has $adapterCount items)")
            return
        }

        // Claim the scroll lifecycle as activity-driven until the RecyclerView reaches IDLE;
        // the scroll listener will transition back to Idle at that point.
        pageScrollState = PageScrollState.Programmatic

        if (smooth) {
            binding.pagesRecyclerView.smoothScrollToPosition(pageIndex)
        } else {
            // Post so the RecyclerView is laid out before we jump; scrollToPosition on an
            // unmeasured view silently no-ops.
            binding.pagesRecyclerView.post {
                Logger.d("PageViewActivity", "Actually scrolling to page $pageIndex")
                binding.pagesRecyclerView.scrollToPosition(pageIndex)
            }
        }
    }

    private fun showPageJumpDialog() {
        // The page concept still maps to the underlying paged pages list even in continuous
        // mode, so the user can jump by source page number; we just translate the target to
        // a scroll-to-word in continuous mode instead of a RecyclerView snap.
        val pages = viewModel.pages.value
        if (pages.isEmpty()) {
            Toast.makeText(this, "No pages available to jump to", Toast.LENGTH_SHORT).show()
            return
        }
        val totalPages = pages.maxOf { it.pageNumber }
        val currentDisplayPage = if (isContinuousActive()) {
            // Find the page whose word range contains the current continuous-scroll word.
            val wordIdx = viewModel.continuousWordPosition.value
            val pageIdx = viewModel.findPageIndexForWord(wordIdx).coerceIn(0, pages.size - 1)
            pages[pageIdx].pageNumber
        } else {
            viewModel.getDisplayPageInfo(currentPageIndex).current
        }

        val editText = android.widget.EditText(this).apply {
            hint = "Enter page number (1-$totalPages)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentDisplayPage.toString())
            selectAll()
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Jump to Page")
            .setMessage("Current page: $currentDisplayPage / $totalPages")
            .setView(editText)
            .setPositiveButton("Jump") { _, _ ->
                val page = editText.text.toString().toIntOrNull()
                if (page != null && page in 1..totalPages) {
                    val targetIdx = viewModel.findIndexForDisplayPageNumber(page)
                    if (isContinuousActive()) {
                        // Translate to the page's first word and scroll the continuous view
                        // there; track it as the new continuation position.
                        val wordIndex = viewModel.pages.value
                            .getOrNull(targetIdx)?.startWordIndex ?: 0
                        viewModel.setContinuousWordPosition(wordIndex)
                        seekContinuousToWordRV(wordIndex)
                    } else {
                        currentPageIndex = targetIdx
                        scrollToPage(currentPageIndex)
                    }
                    updatePageIndicatorImmediate()
                    updateCurrentPosition()
                } else {
                    Toast.makeText(this, "Invalid page number. Enter a number between 1 and $totalPages", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun updateCurrentPosition() {
        // Get the word position for the current view (continuous scroll position or paged
        // page index) to keep RSVP in sync.
        val wordPosition = resolveExitWordPosition()
        Logger.d("PageViewActivity", "Position sync: mode=$currentScrollMode, isPdfMode=$isPdfMode -> word position $wordPosition")

        // Store in result intent for when we return to MainActivity
        val resultIntent = Intent().apply {
            putExtra("word_position", wordPosition)
            putExtra("current_page_changed", true)
        }
        setResult(RESULT_OK, resultIntent)
    }

    /**
     * Word the user is currently parked on, in whichever rendering layer is active. Used by
     * back, switch-to-RSVP, and page-jump exit paths so RSVP-continuation lands in the right
     * place regardless of scroll mode. While playback (RSVP or TTS) is active, the live word
     * position wins; then an explicit long-press; then passive scroll / page fallbacks.
     */
    private fun resolveExitWordPosition(): Int {
        livePlaybackWordIndex?.let { return it }
        userPickedContinuationWord?.let { return it }
        return if (isContinuousActive()) {
            viewModel.continuousWordPosition.value
        } else {
            viewModel.getWordPositionForPage(currentPageIndex)
        }
    }

    /**
     * Snapshot the live playback word position into the ViewModel + activity before the caller
     * clears [livePlaybackWordIndex]. Without this, leaving playback (Finished / Idle / mode flip)
     * drops the last spoken word and the exit position falls back to a stale pre-TTS value.
     */
    private fun persistLivePositionBeforeClear() {
        val lastSpoken = livePlaybackWordIndex ?: return
        viewModel.setContinuationWord(lastSpoken)
        userPickedContinuationWord = lastSpoken
    }

    private fun isContinuousActive(): Boolean =
        currentScrollMode == PageViewScrollMode.CONTINUOUS && !isPdfMode
    
    private fun savePageViewPosition(wordPosition: Int) {
        val docId = viewModel.currentDocument.value?.id ?: return
        pageViewPositionManager.savePosition(docId, wordPosition)
    }

    private fun finishWithPositionSync() {
        // Ensure the final position is synced before closing
        updateCurrentPosition()
        finish()
    }

    private fun registerBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWithPositionSync()
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.page_view_menu, menu)

        if (isPageViewOnlyMode) {
            menu?.findItem(R.id.action_switch_to_rsvp)?.isVisible = false
            menu?.findItem(R.id.action_toggle_playback)?.isVisible = false
        }

        // Show PDF toggle only for PDF documents
        menu?.findItem(R.id.action_toggle_pdf_mode)?.let { item ->
            lifecycleScope.launch {
                val isSourcePdf = viewModel.currentDocument.value?.source == com.speedread.rsvp.data.bookmark.BookmarkSource.FILE_PDF
                item.isVisible = isSourcePdf

                // Update menu item based on current mode
                updatePdfToggleMenuItem(item)
            }
        }

        if (!isPageViewOnlyMode) {
            menu?.findItem(R.id.action_toggle_playback)?.let { item ->
                updatePlaybackMenuItem(item)
            }
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finishWithPositionSync()
                true
            }
            R.id.action_switch_to_rsvp -> {
                switchToRsvpMode()
                true
            }
            R.id.action_view_bookmarks -> {
                showBookmarksDialog()
                true
            }
            R.id.action_bookmark -> {
                createBookmark()
                true
            }
            R.id.action_toggle_pdf_mode -> {
                togglePdfMode()
                true
            }
            R.id.action_toggle_playback -> {
                togglePlayback()
                true
            }
            R.id.action_settings -> {
                openSettings()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun switchToRsvpMode() {
        // Calculate current word position from whichever scroll mode is active so RSVP
        // resumes from exactly where the user is reading.
        val wordPosition = resolveExitWordPosition()

        // Close this activity and return to MainActivity with the word position
        val resultIntent = Intent().apply {
            putExtra("word_position", wordPosition)
            putExtra("switch_to_rsvp", true)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun createBookmark() {
        // Default title differs by mode: paged uses page number, continuous uses approximate
        // word position so the user can tell two same-doc bookmarks apart.
        val continuous = isContinuousActive()
        val defaultTitle = if (continuous) {
            "Word ${viewModel.continuousWordPosition.value}"
        } else {
            "Page ${currentPageIndex + 1}"
        }

        val editText = android.widget.EditText(this).apply {
            hint = "Bookmark title"
            setText(defaultTitle)
            selectAll()
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Create Bookmark")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val title = editText.text.toString().ifBlank { defaultTitle }
                if (continuous) {
                    viewModel.createBookmarkAtWord(title, viewModel.continuousWordPosition.value)
                } else {
                    viewModel.createBookmark(title, currentPageIndex)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBookmarksDialog() {
        cancelOverlayAutoHide()
        lifecycleScope.launch {
            val bookmarks = viewModel.getBookmarksForCurrentDocument()
            if (bookmarks.isEmpty()) {
                Toast.makeText(this@PageViewActivity, R.string.no_bookmarks, Toast.LENGTH_SHORT).show()
                scheduleOverlayAutoHide()
                return@launch
            }

            val titles = bookmarks.map { bwp ->
                val progress = getString(R.string.progress_format, bwp.progressPercentage.toInt())
                val autoLabel = if (bwp.bookmark.isAutoBookmark) " (${getString(R.string.auto_bookmark)})" else ""
                "${bwp.bookmark.title}$autoLabel - $progress"
            }.toTypedArray()

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@PageViewActivity)
                .setTitle(R.string.bookmarks)
                .setItems(titles) { _, which ->
                    val selected = bookmarks[which]
                    jumpToBookmarkPosition(selected.bookmark.wordPosition)
                }
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener { scheduleOverlayAutoHide() }
                .show()
        }
    }

    private fun jumpToBookmarkPosition(wordPosition: Int) {
        if (isContinuousActive()) {
            viewModel.setContinuousWordPosition(wordPosition)
            seekContinuousToWordRV(wordPosition)
        } else {
            val pageIndex = viewModel.findPageForWordPosition(wordPosition)
            if (pageIndex != currentPageIndex) {
                currentPageIndex = pageIndex
                binding.pagesRecyclerView.scrollToPosition(pageIndex)
            }
        }
        savePageViewPosition(wordPosition)
        updatePageIndicatorImmediate()
    }

    private fun togglePdfMode() {
        Logger.d("PageViewActivity", "Toggle PDF mode requested - current mode: isPdfMode=$isPdfMode")
        
        if (isPdfMode) {
            // Switch from PDF to text mode
            viewModel.switchToTextMode()
            themeManager.savePageViewContentMode(Constants.PAGE_VIEW_CONTENT_MODE_TEXT)
        } else {
            // Switch from text to PDF mode
            viewModel.switchToPdfMode()
            themeManager.savePageViewContentMode(Constants.PAGE_VIEW_CONTENT_MODE_PDF)
        }
    }
    
    private fun updatePdfToggleMenuItem(item: MenuItem) {
        if (isPdfMode) {
            item.title = "Switch to Text Mode"
            item.setIcon(R.drawable.ic_text_fields)
        } else {
            item.title = "Switch to PDF Mode"
            item.setIcon(R.drawable.ic_picture_as_pdf)
        }
    }

    private fun updatePlaybackMenuItem(item: MenuItem) {
        val isPlaying = coordinatorState is RsvpState.Playing
        if (isPlaying) {
            item.setTitle(R.string.page_view_pause)
            item.setIcon(R.drawable.ic_pause)
        } else if (coordinatorMode == PlaybackMode.TTS) {
            item.setTitle(R.string.page_view_listen)
            item.setIcon(R.drawable.ic_volume_up)
        } else {
            item.setTitle(R.string.page_view_play)
            item.setIcon(R.drawable.ic_play_arrow)
        }
    }

    /**
     * Sync the always-visible TTS FAB with the coordinator's mode + state. Mirrors
     * [updatePlaybackMenuItem] for the menu item — both controls invoke
     * [togglePlayback], so they must render the same icon/contentDescription. Called
     * from setupUI on first inflation and from the activeMode/state observers as the
     * coordinator changes.
     */
    private fun refreshPlaybackFab() {
        if (!::binding.isInitialized || isPageViewOnlyMode) return
        val isPlaying = coordinatorState is RsvpState.Playing
        if (isPlaying) {
            binding.ttsFab.setImageResource(R.drawable.ic_pause)
            binding.ttsFab.contentDescription = getString(R.string.page_view_pause)
        } else if (coordinatorMode == PlaybackMode.TTS) {
            binding.ttsFab.setImageResource(R.drawable.ic_volume_up)
            binding.ttsFab.contentDescription = getString(R.string.page_view_listen)
        } else {
            binding.ttsFab.setImageResource(R.drawable.ic_play_arrow)
            binding.ttsFab.contentDescription = getString(R.string.page_view_play)
        }
    }

    private fun openSettings() {
        // Show the appearance editor as an in-place BottomSheet so the user stays on the
        // page they're reading. ThemeManager's pageViewFontSize / pageViewColors StateFlows
        // are already observed in observeViewModel(), so slider/dropdown changes re-style
        // the visible pages live without needing to close and reopen this activity.
        if (supportFragmentManager.findFragmentByTag(PageViewSettingsSheet.TAG) == null) {
            PageViewSettingsSheet().show(supportFragmentManager, PageViewSettingsSheet.TAG)
        }
    }

    // --- Continuous (endless-scroll) mode plumbing ---

    private fun applyScrollModeVisibility() {
        applyRecyclerViewMode()
        if (!isPageViewOnlyMode && !isPdfMode) {
            pageAdapter.setHighlight(resolveActiveHighlightIndex(), orpHighlightColor())
        }
    }

    /**
     * O(1) char-range lookup via the VM cache. Replaces a previous activity-side linear
     * walk over the full continuousText. Kept as a thin wrapper so callsites (the
     * long-press handler) stay readable.
     */
    private fun findCharRangeForWord(text: String, wordIndex: Int): IntRange? {
        // `text` arg retained for source compatibility with prior callsites; the VM owns the
        // cache and is the single source of truth.
        if (text.isEmpty()) return null
        return viewModel.getWordCharRange(wordIndex)
    }

    private fun computeContinuousWordIndexAtTopRV(): Int? {
        val lm = binding.pagesRecyclerView.layoutManager as? LinearLayoutManager ?: return null
        val firstPos = lm.findFirstVisibleItemPosition()
        if (firstPos == RecyclerView.NO_POSITION) return null
        val page = pageAdapter.currentList.getOrNull(firstPos) ?: return null
        val child = lm.findViewByPosition(firstPos) ?: return page.startWordIndex
        val tv = child.findViewById<TextView>(R.id.pageContent) ?: return page.startWordIndex
        val layout = tv.layout ?: return page.startWordIndex
        val scrollOffset = (-child.top).coerceAtLeast(0)
        val line = layout.getLineForVertical(scrollOffset)
        val charOffset = layout.getOffsetForHorizontal(line, 0f).coerceAtLeast(0)
        return PageAdapter.absoluteWordIndexAt(page, charOffset, continuous = true) ?: page.startWordIndex
    }

    private fun seekContinuousToWordRV(wordIndex: Int) {
        val pages = viewModel.pages.value
        if (pages.isEmpty()) return
        val pageIdx = viewModel.findPageIndexForWord(wordIndex)
        if (pageIdx < 0 || pageIdx >= pages.size) return
        val page = pages[pageIdx]
        val relWord = wordIndex - page.startWordIndex
        // Continuous holders render the furniture-blanked variant, so the seek offset must
        // come from the matching starts array.
        val charOffset = if (relWord >= 0 && relWord < page.continuousWordStarts.size) {
            page.continuousWordStarts[relWord]
        } else 0
        val lm = binding.pagesRecyclerView.layoutManager as? LinearLayoutManager ?: return
        suppressContinuousScrollTracking = true
        lm.scrollToPositionWithOffset(pageIdx, 0)
        binding.pagesRecyclerView.post {
            val child = lm.findViewByPosition(pageIdx)
            val tv = child?.findViewById<TextView>(R.id.pageContent)
            val layout = tv?.layout
            if (layout != null) {
                val line = layout.getLineForOffset(charOffset)
                val lineTop = layout.getLineTop(line)
                lm.scrollToPositionWithOffset(pageIdx, -lineTop)
            }
            binding.pagesRecyclerView.post {
                suppressContinuousScrollTracking = false
                updatePageIndicatorImmediate()
            }
        }
    }

    /**
     * Tap-to-zoom viewer for an inline figure: renders the figure's FULL source page (the
     * figure crop is the fallback when the original PDF is no longer accessible) into a
     * pinch-zoomable dialog. Serves both as the detail view and as the safety net for an
     * imperfect crop — the user can always see the complete original page.
     */
    private fun showFigureViewer(figureIndex: Int) {
        val pageNumber = viewModel.getFigureSourcePageNumber(figureIndex)
        lifecycleScope.launch {
            val bitmap = try {
                viewModel.renderFigureSourcePage(figureIndex) ?: viewModel.getFigureBitmap(figureIndex)
            } catch (e: Exception) {
                Logger.w("PageViewActivity", "Figure viewer render failed (index=$figureIndex)", e)
                null
            }
            if (bitmap == null) {
                Toast.makeText(
                    this@PageViewActivity,
                    "Figure unavailable - original PDF cannot be opened",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val imageView = ZoomableImageView(this@PageViewActivity).apply {
                minimumHeight =
                    (resources.displayMetrics.heightPixels * Constants.FIGURE_VIEWER_HEIGHT_FRACTION).toInt()
                setImageBitmap(bitmap)
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@PageViewActivity)
                .setTitle(if (pageNumber != null) "Page $pageNumber" else "Figure")
                .setView(imageView)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    /**
     * Apply a long-press continuation pick from either rendering mode. Updates the activity's
     * authoritative `userPickedContinuationWord`, the VM's resume highlight, the page indicator,
     * AND seeks the shared `PlaybackCoordinator` so:
     *   - while TTS is playing in TTS mode, speech jumps to the picked word immediately,
     *   - while TTS is paused (or in RSVP mode), the next play()/resume starts from the pick
     *     instead of wherever the engine was last parked.
     * Without the coordinator seek, a long-press during TTS-Paused was silently ignored — the
     * Page View `userPickedContinuationWord` was set but `playbackCoordinator.play()` resumed
     * from the TTS engine's stale `currentPosition`. Toast confirms the chosen word so the
     * user can verify the right word was hit.
     */
    private fun applyLongPressContinuation(wordIndex: Int) {
        userPickedContinuationWord = wordIndex
        viewModel.setContinuationWord(wordIndex)
        lifecycleScope.launch {
            try {
                playbackCoordinator.seekToAbsoluteWordPosition(wordIndex)
            } catch (e: Exception) {
                Logger.e("PageViewActivity", "Long-press seek failed", e)
            }
        }
        val text = viewModel.continuousText.value
        val range = if (text.isNotEmpty()) findCharRangeForWord(text, wordIndex) else null
        val word = if (range != null) text.substring(range.first, range.last + 1) else "word"
        Toast.makeText(this, "Continuation set: \"$word\"", Toast.LENGTH_SHORT).show()
    }

    /**
     * Shared "quick bookmark" entry point for double-tap from either rendering mode. Title
     * mirrors the RSVP screen's double-tap-to-bookmark convention (`"Quick Bookmark - Page
     * <n>: \"<word>\""`) so both surfaces produce identically-shaped bookmark titles. The
     * page number is the activity's currently-tracked page (the page near the top of the
     * viewport in continuous mode, or the snapped page in paged mode); the bookmark itself
     * is stored against the absolute word index, so the displayed title is just a hint and
     * the bookmark navigates back to the exact word regardless. A confirming Toast helps
     * users build the gesture mental model — the gesture is otherwise invisible.
     */
    private fun applyDoubleTapBookmark(wordIndex: Int) {
        val text = viewModel.continuousText.value
        val range = if (text.isNotEmpty()) findCharRangeForWord(text, wordIndex) else null
        val word = if (range != null) text.substring(range.first, range.last + 1) else "word"
        val pageNumber = if (isContinuousActive()) {
            val pages = viewModel.pages.value
            if (pages.isNotEmpty()) {
                val pageIdx = viewModel.findPageIndexForWord(wordIndex)
                    .coerceIn(0, pages.size - 1)
                pages[pageIdx].pageNumber
            } else {
                currentPageIndex + 1
            }
        } else {
            currentPageIndex + 1
        }
        val title = "${Constants.BOOKMARK_QUICK_TITLE_PREFIX} - Page $pageNumber: \"$word\""
        viewModel.createBookmarkAtWord(title, wordIndex)
        Toast.makeText(this, "Bookmark added: \"$word\"", Toast.LENGTH_SHORT).show()
    }
}

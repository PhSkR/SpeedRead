package com.speedread.rsvp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import android.content.pm.ActivityInfo
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.speedread.rsvp.util.Logger
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.speedread.rsvp.Constants
import com.speedread.rsvp.DocumentsManager
import com.speedread.rsvp.ReadingViewModel
import com.speedread.rsvp.PageViewActivity
import com.speedread.rsvp.R
import com.speedread.rsvp.ThemeManager
import com.speedread.rsvp.TimingSettingsManager
import com.speedread.rsvp.engine.LandscapePlayButtonCorner
import com.speedread.rsvp.data.bookmark.BookmarkSource
import com.speedread.rsvp.data.bookmark.BookmarkWithProgress
import com.speedread.rsvp.data.document.SavedDocument
import com.speedread.rsvp.databinding.FragmentReadingBinding
import com.speedread.rsvp.engine.DefaultTimingCalculator
import com.speedread.rsvp.engine.RsvpState
import com.speedread.rsvp.engine.RsvpWord
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ReadingFragment : Fragment() {

    private var _binding: FragmentReadingBinding? = null
    private val binding get() = _binding!!

    private val readingViewModel: ReadingViewModel by activityViewModels()
    private var isUpdatingSliderProgrammatically = false
    private var pageViewOnlyLaunchPending = false

    @Inject
    lateinit var documentsManager: DocumentsManager

    @Inject
    lateinit var themeManager: ThemeManager

    @Inject
    lateinit var savedDocumentRepository: com.speedread.rsvp.data.document.SavedDocumentRepository

    private lateinit var timingSettingsManager: TimingSettingsManager
    private lateinit var textImportController: TextImportController

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        textImportController.handleFilePickerResult(uri)
    }

    // One-shot POST_NOTIFICATIONS permission request, fired the first time the user toggles
    // into TTS mode on API 33+. Result is advisory only — listen mode still works without the
    // permission, but the lock-screen / notification-shade media controls will silently not
    // render. A denied result shows a Snackbar pointing the user at system settings.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            _binding?.let { bind ->
                com.google.android.material.snackbar.Snackbar
                    .make(bind.root, R.string.tts_notification_permission_denied,
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .show()
            }
        }
    }

    private val pageViewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            result.data?.let { data ->
                val shouldSwitchToRsvp = data.getBooleanExtra("switch_to_rsvp", false)
                val pageChanged = data.getBooleanExtra("current_page_changed", false)
                val wordPosition = data.getIntExtra("word_position", 0)

                if ((pageChanged || shouldSwitchToRsvp) && wordPosition >= 0) {
                    // Page View reports an ABSOLUTE word index. seekToAbsoluteWordPosition maps
                    // it to the containing chunk; the chunk-index API (seekToPosition) would
                    // overshoot by ~chunkSize whenever chunk size > 1.
                    readingViewModel.seekToAbsoluteWordPosition(wordPosition)
                    Logger.d("ReadingFragment", "Position synced from page view: word position $wordPosition")
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        timingSettingsManager = TimingSettingsManager()
        textImportController = TextImportController(
            requireActivity(),
            binding,
            readingViewModel,
            lifecycleScope,
            { filePickerLauncher.launch("*/*") }
        )

        setupUI()
        observeViewModel()
        applyPageViewOnlyMode()
        textImportController.handleIntent(requireActivity().intent)

        // Auto-load most recent document if this is a normal app launch (not from intent).
        // Skip when Page View Only mode is active — the RSVP engine isn't used in that mode
        // and the user opens documents from the Library tab directly into PageView.
        if (shouldAutoLoadDocument(requireActivity().intent) && !themeManager.isPageViewOnlyMode()) {
            autoLoadRecentDocument()
        }
    }

    fun handleNewIntent(intent: Intent) {
        textImportController.handleIntent(intent)
    }

    private fun setupUI() {
        // Handle window insets for edge-to-edge display. Landscape uses a fullscreen RSVP
        // variant (layout-land/fragment_reading.xml); padding the root by systemBars.top there
        // would leave a status-bar-height dead strip above the card. In portrait the chrome
        // stack still needs the inset so the title doesn't hide behind the status bar.
        //
        // Applied as documentTitleDisplay.topMargin (NOT as ScrollView root padding) so the
        // ScrollView's content area extends edge-to-edge under the status bar. The dim
        // overlay is now activity-level (DimMaskView), which covers the inset area
        // independently — but keeping the ScrollView edge-to-edge avoids a visible gap when
        // the status bar appears transiently via swipe.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val isLandscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val topInset = if (isLandscape) 0 else systemBars.top
            val baseTitleMargin = resources.getDimensionPixelSize(R.dimen.reading_title_margin_top)
            val params = binding.documentTitleDisplay.layoutParams as ViewGroup.MarginLayoutParams
            val targetMargin = topInset + baseTitleMargin
            if (params.topMargin != targetMargin) {
                params.topMargin = targetMargin
                binding.documentTitleDisplay.layoutParams = params
            }
            insets
        }

        // Card position/size feeds DimMaskView's exclude rect. Layout shifts (title wrap,
        // status-bar inset margin update, configuration change) trigger this so the cutout
        // tracks the card without polling.
        binding.rsvpDisplayCard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyDimMask()
        }

        with(binding) {
            btnRestartParagraph.setOnClickListener { readingViewModel.restartParagraph() }

            btnBookmarks.setOnClickListener { showBookmarksDialog() }
            btnSaveDocument.setOnClickListener { showSaveDocumentDialog() }

            // Word navigation buttons
            btnPreviousWord.setOnClickListener { navigateToPreviousWord() }
            btnNextWord.setOnClickListener { navigateToNextWord() }

            // Initially disable position slider until text is loaded
            positionSlider.isEnabled = false
            positionSlider.value = 1f

            positionSlider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    // User started dragging - pause updates from readingViewModel
                    isUpdatingSliderProgrammatically = true
                }

                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    // User finished dragging - seek to page and resume updates
                    try {
                        if (slider.isEnabled) {
                            readingViewModel.seekToPage(slider.value.toInt())
                        }
                    } catch (e: Exception) {
                        Logger.w("ReadingFragment", "Slider seek failed", e)
                    }
                    isUpdatingSliderProgrammatically = false
                }
            })

            fabImportText.setOnClickListener {
                showTextImportDialog()
            }

            fabPageView.setOnClickListener {
                openPageView()
            }

            fabEnterLandscape.setOnClickListener {
                val isLandscape = resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                if (isLandscape) {
                    exitLandscapeMode()
                } else {
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    registerUnforceOrientationBackCallback()
                }
            }

            // fabListenToggle is absent from layout-land/fragment_reading.xml (landscape is the
             // immersive fullscreen RSVP reader). ViewBinding types it as nullable here; safe
             // calls skip wiring in landscape, where the toggle isn't shown anyway.
            fabListenToggle?.setOnClickListener {
                maybeRequestNotificationPermission()
                readingViewModel.togglePlaybackMode()
            }

            // ±speed selector under fabHoldToPlay — portrait-only, same nullable-binding
            // story as fabListenToggle. Each tap nudges TtsSettings.speechRate by
            // Constants.TTS_RATE_STEP and clamps to the rate bounds; the button enable/disable
            // state is driven by the ttsSettings observer below so a tap at the limit is
            // already prevented at the UI layer.
            btnTtsRateDecrease?.setOnClickListener {
                val current = readingViewModel.ttsSettings.value.speechRate
                val next = (current - Constants.TTS_RATE_STEP)
                    .coerceIn(Constants.TTS_MIN_RATE, Constants.TTS_MAX_RATE)
                if (next != current) readingViewModel.updateTtsSpeechRate(next)
            }
            btnTtsRateIncrease?.setOnClickListener {
                val current = readingViewModel.ttsSettings.value.speechRate
                val next = (current + Constants.TTS_RATE_STEP)
                    .coerceIn(Constants.TTS_MIN_RATE, Constants.TTS_MAX_RATE)
                if (next != current) readingViewModel.updateTtsSpeechRate(next)
            }

            readingWpmSlider?.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val wpm = value.toInt()
                    readingWpmValue?.text = getString(R.string.wpm_format, wpm)
                    readingViewModel.updateWpm(wpm)
                }
            }

            pageCounter.setOnClickListener {
                showPageJumpDialog()
            }

            // Setup double-tap to bookmark for RSVP word display
            setupDoubleTapToBookmark()

            // Setup hold-to-play button
            setupHoldToPlayButton()
        }

        // Rotation recreates the fragment. If the activity is still landscape-forced from a
        // prior tap on fabEnterLandscape, re-install the back callback so the user can still
        // exit fullscreen via back/gesture after rotation swapped the fragment in.
        when (requireActivity().requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> registerUnforceOrientationBackCallback()
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> {
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private var unforceOrientationCallback: OnBackPressedCallback? = null

    private fun exitLandscapeMode() {
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        unforceOrientationCallback?.isEnabled = false
        unforceOrientationCallback = null
    }

    private fun registerUnforceOrientationBackCallback() {
        if (unforceOrientationCallback != null) return
        val cb = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitLandscapeMode()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, cb)
        unforceOrientationCallback = cb
    }

    // Scrub-gesture state. Captured on the first onScroll event of a gesture so the user can
    // drag horizontally to seek: left→right advances, right→left rewinds. Reset on UP/CANCEL.
    // Null when no scrub is in flight.
    private var scrubStartPosition: Int? = null
    private var scrubLastSeekedPosition: Int = -1
    private var wasPlayingBeforeScrub = false

    private fun setupDoubleTapToBookmark() {
        val gestureDetector = android.view.GestureDetector(requireContext(), object : android.view.GestureDetector.SimpleOnGestureListener() {
            // SimpleOnGestureListener.onDown defaults to `false`, which propagates through
            // GestureDetector.onTouchEvent as the return value for ACTION_DOWN. Since the
            // rsvpWordDisplay TextView isn't `clickable`, a false return on DOWN causes the
            // parent ViewGroup to stop delivering subsequent MOVE/UP events — which is
            // exactly why onScroll never fired and landscape scrubbing was broken. Claiming
            // the DOWN here keeps the full stream flowing.
            override fun onDown(e: android.view.MotionEvent): Boolean = true

            // Landscape-only tap-to-navigate. A confirmed single-tap (i.e. NOT the first half
            // of a double-tap, which is reserved for the bookmark gesture above) on the left
            // side of the word display steps back one word; on the right side, forward one
            // word. Coexists with horizontal-drag scrubbing on the same view because GestureDetector
            // routes any drag through onScroll instead of onSingleTapConfirmed. There is the
            // standard ~300ms double-tap-detection delay before this fires; that is the
            // unavoidable cost of keeping the existing double-tap-to-bookmark working.
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                val isLandscape = resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                if (!isLandscape) return false
                val viewWidth = binding.rsvpWordDisplay.width
                if (viewWidth <= 0) return false
                val splitPx = viewWidth * com.speedread.rsvp.Constants.LANDSCAPE_TAP_NAV_LEFT_ZONE_FRACTION
                if (e.x < splitPx) {
                    navigateToPreviousWord()
                } else {
                    navigateToNextWord()
                }
                return true
            }

            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                // Create an automatic bookmark with double-tap indication
                launchOnView {
                    val currentWordValue = readingViewModel.currentWord.value
                    val pageInfo = readingViewModel.pageInfo.value

                    if (currentWordValue != null) {
                        val quickTitle = "${Constants.BOOKMARK_QUICK_TITLE_PREFIX} - Page ${pageInfo.currentPage}: \"${currentWordValue.text}\""
                        readingViewModel.createManualBookmark(quickTitle)

                        // Show visual feedback
                        binding.rsvpWordDisplay.animate()
                            .scaleX(1.2f)
                            .scaleY(1.2f)
                            .setDuration(100)
                            .withEndAction {
                                binding.rsvpWordDisplay.animate()
                                    .scaleX(1.0f)
                                    .scaleY(1.0f)
                                    .setDuration(100)
                                    .start()
                            }
                            .start()
                    }
                }
                return true
            }

            // Horizontal drag in landscape scrubs through words. Compute total x-delta since
            // gesture start (e1 is the ACTION_DOWN that began this scroll sequence), map to a
            // word offset via SCRUB_PIXELS_PER_WORD, and seek. Vertical drags are ignored by
            // requiring |dx| > |dy| so the gesture is unambiguously horizontal.
            override fun onScroll(
                e1: android.view.MotionEvent?,
                e2: android.view.MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                val isLandscape = resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                if (!isLandscape) return false
                val down = e1 ?: return false
                val dx = e2.x - down.x
                val dy = e2.y - down.y
                if (kotlin.math.abs(dx) < kotlin.math.abs(dy)) return false

                if (scrubStartPosition == null) {
                    val startPos = readingViewModel.currentWord.value?.absoluteStartIndex ?: return false
                    scrubStartPosition = startPos
                    scrubLastSeekedPosition = startPos
                    wasPlayingBeforeScrub = readingViewModel.state.value == RsvpState.Playing
                    readingViewModel.pause()
                }

                val totalWords = readingViewModel.pageInfo.value.totalWords
                if (totalWords <= 0) return false
                // User-configurable via OptionsFragment scrub-sensitivity slider. Read fresh
                // from settings on every scroll event so a mid-gesture slider change (rare but
                // possible via quick settings round-trip) is honored immediately.
                val pixelsPerWord = readingViewModel.settings.value.scrubPixelsPerWord
                val wordDelta = (dx / pixelsPerWord).toInt()
                val target = (scrubStartPosition!! + wordDelta).coerceIn(0, totalWords - 1)
                if (target != scrubLastSeekedPosition) {
                    readingViewModel.seekToAbsoluteWordPosition(target)
                    scrubLastSeekedPosition = target
                }
                return true
            }
        })

        binding.rsvpWordDisplay.setOnTouchListener { _, event ->
            val handled = gestureDetector.onTouchEvent(event)
            // Reset scrub state once the gesture ends. onScroll is never called for UP/CANCEL,
            // so the GestureDetector itself can't clear this for us.
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                if (scrubStartPosition != null && wasPlayingBeforeScrub) {
                    readingViewModel.play()
                }
                scrubStartPosition = null
                wasPlayingBeforeScrub = false
            }
            // Must return the detector's result (not false) so the full DOWN→MOVE→UP stream
            // reaches the GestureDetector. Returning false on DOWN causes the ViewGroup to stop
            // delivering later events to this listener, stranding the detector's pointer state —
            // which manifests as InputDispatcher "device already down" / hover-event races.
            handled
        }
    }

    // Word-hold overshoot diagnostic. For every word transition during playback, compares
    // the wall-clock time the PREVIOUS word actually spent on screen against the delay the
    // timing calculator scheduled for it, and logs any hold that exceeds schedule by more
    // than WORD_HOLD_OVERSHOOT_LOG_THRESHOLD_MS. "actual >> scheduled" points at main-thread
    // stalls; "scheduled itself large" points at timing settings (word-length timing, chunk
    // scaling, punctuation/break pauses). Filter logcat on the RsvpDiag tag.
    private val diagnosticTimingCalculator = DefaultTimingCalculator()
    private var diagLastWord: RsvpWord? = null
    private var diagLastWordUptimeMs = 0L

    // Samples the main thread's stack whenever it stops responding, so word-hold overshoots
    // reported by logWordHoldOvershoot can be attributed to the actual blocking code.
    // Debuggable builds only; scoped to this screen via onStart/onStop.
    private val stallWatchdog = com.speedread.rsvp.util.MainThreadStallWatchdog()

    private fun isDebuggableBuild(): Boolean {
        val appInfo = context?.applicationInfo ?: return false
        return (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun logWordHoldOvershoot(word: RsvpWord?) {
        val now = android.os.SystemClock.uptimeMillis()
        val previous = diagLastWord
        if (word != null && previous != null && word != previous &&
            readingViewModel.state.value == RsvpState.Playing &&
            // RSVP mode only: in TTS (Listen) mode the display tracks the SPOKEN word, so
            // hold times follow speech cadence (300ms+ per word, longer at sentence breaks)
            // and comparing them against the RSVP schedule reports normal speech as stalls.
            readingViewModel.activeMode.value == com.speedread.rsvp.tts.PlaybackMode.RSVP
        ) {
            val scheduled = diagnosticTimingCalculator.calculateDelay(
                previous, readingViewModel.settings.value
            )
            val actual = now - diagLastWordUptimeMs
            if (actual > scheduled + Constants.WORD_HOLD_OVERSHOOT_LOG_THRESHOLD_MS) {
                Logger.w(
                    "RsvpDiag",
                    "held \"${previous.text}\" for ${actual}ms, scheduled ${scheduled}ms " +
                        "(pos=${previous.position}, len=${previous.text.length}, " +
                        "break=${previous.trailingBreak})"
                )
            }
        }
        if (word != previous) {
            diagLastWord = word
            diagLastWordUptimeMs = now
        }
    }

    private fun observeViewModel() {
        collectOnView(readingViewModel.currentWord) {
            logWordHoldOvershoot(it)
            if (it != null) {
                val settings = readingViewModel.settings.value
                val tv = binding.rsvpWordDisplay
                when {
                    settings.enableOrp && settings.centerOrp -> {
                        tv.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        // Size first (may shrink for long words), then padding, then text —
                        // all three must use the same font size and batch into one layout pass.
                        applyAdaptiveWordSizing(it.text, it.orpIndex, centeredOrp = true)
                        applyOrpCenteringPadding(it.text, it.orpIndex)
                        tv.text = createOrpStyledText(it.text, it.orpIndex, bold = true)
                    }
                    settings.enableOrp -> {
                        tv.gravity = Gravity.CENTER
                        clearOrpCenteringPadding()
                        applyAdaptiveWordSizing(it.text, it.orpIndex, centeredOrp = false)
                        tv.text = createOrpStyledText(it.text, it.orpIndex, bold = false)
                    }
                    else -> {
                        tv.gravity = Gravity.CENTER
                        clearOrpCenteringPadding()
                        applyAdaptiveWordSizing(it.text, it.orpIndex, centeredOrp = false)
                        tv.text = it.text
                    }
                }
            } else {
                binding.rsvpWordDisplay.text = ""
                clearOrpCenteringPadding()
            }
            if (it != null && !binding.positionSlider.isEnabled) {
                binding.positionSlider.isEnabled = true
            }
        }

        collectOnView(readingViewModel.progress) {
            try {
                if (it.isFinite() && it >= 0f && it <= 1f) {
                    val progressPercent = (it * 100).toInt().coerceIn(0, 100)
                    // animate=false: progress emissions arrive once per word, so the natural
                    // cadence already supplies smoothness. animate=true would schedule a fresh
                    // ObjectAnimator on every call (~10 Hz at 600 WPM) while the previous one
                    // is still mid-flight, producing Choreographer churn for sub-pixel deltas.
                    binding.progressBar.setProgressCompat(progressPercent, false)
                }
            } catch (e: Exception) {
                Logger.w("ReadingFragment", "Progress bar update failed", e)
            }
        }

        collectOnView(readingViewModel.pageInfo) {
            try {
                binding.pageCounter.text = if (it.totalPages > 0) {
                    getString(R.string.page_counter_format, it.currentPage, it.totalPages)
                } else {
                    getString(R.string.page_counter_empty)
                }
                binding.pageCounter.contentDescription = "${getString(R.string.page_counter_description)}: ${binding.pageCounter.text}"

                if (it.totalPages > 0 && !isUpdatingSliderProgrammatically && binding.positionSlider.isEnabled) {
                    val maxPages = maxOf(it.totalPages, 2)
                    binding.positionSlider.valueTo = maxPages.toFloat()
                    binding.positionSlider.value = it.currentPage.toFloat()
                } else if (it.totalPages > 0) {
                    val maxPages = maxOf(it.totalPages, 2)
                    binding.positionSlider.valueTo = maxPages.toFloat()
                }
            } catch (e: Exception) {
                Logger.w("ReadingFragment", "Page info UI update failed", e)
            }
        }

        collectOnView(readingViewModel.state) {
            if (it != RsvpState.Playing) {
                // Reset the word-hold diagnostic baseline: an interval spanning a pause is
                // user idle time, not playback overshoot, and must not be logged as a stall.
                diagLastWord = null
            }
            updateControlsForState(it)
            applyDimMask()
            applyBottomNavVisibility()
            applyKeepScreenOn()
        }

        collectOnView(readingViewModel.settings) {
            applyDimMask()
            applyBottomNavVisibility()
            binding.rsvpWordDisplay.setTextColor(it.textColor)
            binding.rsvpWordDisplay.setBackgroundColor(it.backgroundColor)
        }

        textImportController.observeFileImportResult()

        collectOnView(readingViewModel.bookmarkSaved) {
            it?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                readingViewModel.clearBookmarkSavedMessage()
            }
        }

        collectOnView(readingViewModel.currentDocumentTitle) {
            binding.documentTitleDisplay.text = it ?: getString(R.string.no_document_loaded)
        }

        collectOnView(readingViewModel.documentSaved) {
            it?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                readingViewModel.clearDocumentSavedMessage()
            }
        }

        collectOnView(readingViewModel.documentLoadStatus) { status ->
            if (status is com.speedread.rsvp.DocumentLoadStatus.Truncated) {
                Toast.makeText(
                    requireContext(),
                    getString(
                        R.string.document_truncated_warning,
                        status.loadedWords,
                        status.limit
                    ),
                    Toast.LENGTH_LONG
                ).show()
                readingViewModel.clearDocumentLoadStatus()
            }
        }

        collectOnView(themeManager.fontSize) {
            binding.rsvpWordDisplay.textSize = effectiveFontSize(it)
        }

        // Speaker-toggle visual state: higher alpha + different content description when
        // TTS is the active engine, so the button reads as "on" without needing a second icon.
        // fabListenToggle is nullable because layout-land doesn't include it — landscape is
        // the fullscreen immersive reader. Safe call skips the update there.
        collectOnView(readingViewModel.activeMode) { mode ->
            val isTts = mode == com.speedread.rsvp.tts.PlaybackMode.TTS
            binding.fabListenToggle?.alpha = if (isTts) 1.0f else 0.65f
            binding.fabListenToggle?.contentDescription = getString(
                if (isTts) R.string.listen_toggle_cd_stop else R.string.listen_toggle_cd_start
            )
            // Inline ±speed selector follows the active mode: visible only while TTS is
            // synthesizing, hidden in plain RSVP mode (where speechRate has no effect).
            binding.ttsRateSelector?.visibility = if (isTts) View.VISIBLE else View.GONE
            // Mode flips while state stays Playing (e.g. RSVP→TTS via the listen toggle)
            // must also retoggle the keep-screen-on flag and dim mask, so re-evaluate here.
            applyKeepScreenOn()
            applyDimMask()
        }

        // Drive the rate label + button enable-state off TtsSettings so a programmatic update
        // (e.g. the Options screen rate slider) immediately reflects on the Reading screen.
        // Epsilon on the limit comparison avoids floating-point edge cases — repeated +0.10
        // nudges from 0.5 land at 1.9000001 / 2.0000001, where a strict `< MAX` would re-enable
        // the increase button at the cap.
        collectOnView(readingViewModel.ttsSettings) { settings ->
            val rate = settings.speechRate
            binding.ttsRateValue?.text = getString(R.string.tts_rate_value_format, rate)
            binding.btnTtsRateDecrease?.isEnabled =
                rate > Constants.TTS_MIN_RATE + Constants.TTS_RATE_LIMIT_EPSILON
            binding.btnTtsRateIncrease?.isEnabled =
                rate < Constants.TTS_MAX_RATE - Constants.TTS_RATE_LIMIT_EPSILON
        }

        collectOnView(readingViewModel.wpm) { wpm ->
            binding.readingWpmValue?.text = getString(R.string.wpm_format, wpm)
            binding.readingWpmSlider?.let { slider ->
                if (slider.value.toInt() != wpm) {
                    slider.value = wpm.toFloat()
                        .coerceIn(Constants.WPM_SLIDER_MIN, Constants.WPM_SLIDER_MAX)
                }
            }
        }

        // Surface TTS notices (e.g. "Neural backend unavailable — dropping to System") as
        // Toasts. Using Toast here instead of Snackbar for consistency with the fragment's
        // existing transient-message pattern (bookmarkSaved / documentSaved above).
        collectOnView(readingViewModel.ttsNotices) { message ->
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    // Activity-level dim that frames the rsvpDisplayCard. The cutout is the card's
    // screen-space bounds expressed in DimMaskView local coordinates (both share the
    // activity ConstraintLayout, so getLocationInWindow values are directly comparable).
    // Card position depends on the title's wrapped height + the status-bar inset margin,
    // both of which can change after first measure — applyDimMask is therefore called
    // both from the state/settings collectors AND from the card's OnLayoutChangeListener
    // (installed once in setupUI). Skipped in landscape: the layout-land variant has the
    // card filling the screen, leaving nothing to dim.
    private fun applyPlayButtonZOrder(isDimmed: Boolean) {
        if (_binding == null) return
        val fab = binding.fabHoldToPlay
        val density = resources.displayMetrics.density
        if (isDimmed) {
            val targetZ = com.speedread.rsvp.Constants.HOLD_TO_PLAY_DIMMED_BASE_TRANSLATION_Z_DP * density
            if (fab.translationZ != targetZ) {
                fab.translationZ = targetZ
            }
        } else {
            if (fab.translationZ != 0f) {
                fab.translationZ = 0f
            }
        }
    }

    private fun applyDimMask() {
        if (_binding == null) return
        val activity = activity ?: return
        val mask = activity.findViewById<DimMaskView>(R.id.dimMask) ?: return
        val isLandscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val dim = !isLandscape &&
            readingViewModel.settings.value.enableScreenDimming &&
            readingViewModel.state.value == RsvpState.Playing &&
            readingViewModel.activeMode.value == com.speedread.rsvp.tts.PlaybackMode.RSVP
        
        applyPlayButtonZOrder(dim)

        if (!dim) {
            mask.visibility = View.GONE
            mask.clearExcludeRect()
            mask.clearBlockTouchRect()
            return
        }
        // Compute geometry BEFORE flipping visibility. If the card hasn't measured yet
        // (width/height == 0 — common when this is invoked from a flow emission during
        // fragment startup, an orientation change, or a TTS-mode toggle that happens to
        // land between layout passes), an empty excludeRect would otherwise leave
        // DimMaskView painting over the entire screen including the rsvpDisplayCard
        // (DimMaskView.onDraw paints the full view when excludeRect.isEmpty). Buttons
        // remain tappable because the mask passes touches through outside blockTouchRect,
        // but the user sees a frozen-looking dim overlay covering the word display. The
        // OnLayoutChangeListener installed in setupUI re-runs applyDimMask once the card
        // has bounds, at which point this branch succeeds and the mask becomes visible
        // with the correct cutout.
        if (!updateDimMaskGeometry(mask)) {
            mask.visibility = View.GONE
            return
        }
        mask.visibility = View.VISIBLE
    }

    private fun updateDimMaskGeometry(mask: DimMaskView): Boolean {
        val card = _binding?.rsvpDisplayCard ?: return false
        if (card.width == 0 || card.height == 0) return false
        val maskLoc = IntArray(2)
        mask.getLocationInWindow(maskLoc)
        val cardLoc = IntArray(2)
        card.getLocationInWindow(cardLoc)
        val left = (cardLoc[0] - maskLoc[0]).toFloat()
        val top = (cardLoc[1] - maskLoc[1]).toFloat()

        val regions = mutableListOf<ExcludeRegion>()

        // Card cutout matches the exact card.radius for its rounded corners
        val cardRadius = card.radius.toFloat()
        regions.add(ExcludeRegion(android.graphics.RectF(left, top, left + card.width, top + card.height), cardRadius))

        mask.setExcludeRegions(regions)

        val nav = activity?.findViewById<View>(R.id.bottom_navigation)
        if (nav != null && nav.width > 0 && nav.height > 0 &&
            nav.visibility == View.VISIBLE
        ) {
            val navLoc = IntArray(2)
            nav.getLocationInWindow(navLoc)
            val navLeft = (navLoc[0] - maskLoc[0]).toFloat()
            val navTop = (navLoc[1] - maskLoc[1]).toFloat()
            mask.setBlockTouchRect(navLeft, navTop, navLeft + nav.width, navTop + nav.height)
        } else {
            mask.clearBlockTouchRect()
        }
        return true
    }

    // RSVP is a purely visual format with no touch input during a session, so without
    // FLAG_KEEP_SCREEN_ON the system display timeout sleeps the screen mid-paragraph and
    // users respond by setting the device-wide timeout to "never" (wrecking battery in every
    // other app). Scoped to RSVP-Playing only — TTS (Listen) mode is meant to keep playing
    // screen-off (foreground service + PARTIAL_WAKE_LOCK in TtsPlaybackService cover it) and
    // setting this flag there would defeat that. Re-evaluated from both the state collector
    // and the activeMode collector so either transition (Playing↔Paused, RSVP↔TTS) lands
    // immediately. Cleared in onPause/onDestroyView for defense in depth.
    private fun applyKeepScreenOn() {
        val activity = activity ?: return
        val isRsvpPlaying = readingViewModel.state.value == RsvpState.Playing &&
            readingViewModel.activeMode.value == com.speedread.rsvp.tts.PlaybackMode.RSVP
        if (isRsvpPlaying) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateControlsForState(state: RsvpState) {
        with(binding) {
            when (state) {
                RsvpState.Idle -> {
                    btnRestartParagraph.isEnabled = false
                    // Reset slider if no text loaded
                    if (!positionSlider.isEnabled) {
                        positionSlider.valueTo = 2f
                        positionSlider.value = 1f
                    }
                }
                RsvpState.Playing,
                RsvpState.Paused,
                RsvpState.Finished -> {
                    btnRestartParagraph.isEnabled = true
                }
            }
        }
    }

    private fun showTextImportDialog() {
        val options = arrayOf(
            getString(R.string.paste_from_clipboard),
            getString(R.string.enter_text_manually),
            getString(R.string.import_from_file),
            getString(R.string.import_from_url)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.import_text))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> readingViewModel.importFromClipboard()
                    1 -> showTextInputDialog()
                    2 -> openFilePicker()
                    3 -> showUrlInputDialog()
                }
            }
            .show()
    }

    private fun openFilePicker() {
        filePickerLauncher.launch("*/*")
    }

    private fun showTextInputDialog() {
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.enter_text_hint)
            minLines = 3
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.enter_text))
            .setView(editText)
            .setPositiveButton(getString(R.string.load)) { _, _ ->
                val text = editText.text.toString()
                if (text.isNotBlank()) {
                    readingViewModel.loadText(text, BookmarkSource.MANUAL_TEXT)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showUrlInputDialog() {
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.enter_url_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            // Prefill from clipboard if it currently holds an http(s) URL. Saves a paste
            // step in the common flow (copy link in browser -> open app -> import URL).
            val clipUrl = readClipboardUrl()
            if (clipUrl != null) setText(clipUrl)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.enter_url))
            .setView(editText)
            .setPositiveButton(getString(R.string.load)) { _, _ ->
                val url = editText.text.toString()
                if (url.isNotBlank()) {
                    readingViewModel.importFromUrl(url)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun readClipboardUrl(): String? {
        val clipboard = requireContext()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).text?.toString()?.trim() ?: return null
        return text.takeIf { it.matches(Regex("^https?://\\S+$")) }
    }

    private fun shouldAutoLoadDocument(intent: Intent): Boolean {
        return when (intent.action) {
            null, Intent.ACTION_MAIN -> true // Normal app launch
            Intent.ACTION_PROCESS_TEXT, Intent.ACTION_SEND, Intent.ACTION_VIEW -> false // Intent-driven launch
            else -> false
        }
    }

    // Lazily request POST_NOTIFICATIONS the first time the user toggles into listen mode.
    // No-op on API <33 (runtime permission doesn't exist) and when already granted. We gate
    // the prompt on the TTS toggle instead of app start so users who never use listen mode
    // are never asked.
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun autoLoadRecentDocument() {
        launchOnView {
            try {
                // Add a small delay to ensure the activity is fully initialized
                kotlinx.coroutines.delay(500)
                val loaded = readingViewModel.loadMostRecentDocument()
                if (loaded) {
                    // Optional: Show a subtle toast or status message
                    // Toast.makeText(this@MainActivity, "Resumed reading", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Logger.e("ReadingFragment", "Auto-load of recent document failed", e)
            }
        }
    }

    private fun showBookmarksDialog() {
        launchOnView {
            // Get bookmarks only for the current document
            val bookmarks = readingViewModel.getCurrentDocumentBookmarks()

            if (bookmarks.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.no_bookmarks), Toast.LENGTH_SHORT).show()
                return@launchOnView
            }

            val bookmarkTitles = bookmarks.map {
                val progress = getString(R.string.progress_format, it.progressPercentage.toInt())
                val autoLabel = if (it.bookmark.isAutoBookmark) " (${getString(R.string.auto_bookmark)})" else ""
                "${it.bookmark.title}$autoLabel - $progress"
            }.toTypedArray()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("${getString(R.string.bookmarks)} - Current Document")
                .setItems(bookmarkTitles) { _, which ->
                    val selectedBookmark = bookmarks[which]
                    // Jump directly to bookmark
                    readingViewModel.jumpToBookmark(selectedBookmark)
                    // TODO: Navigate to Reading tab if not already there
                }
                .setNeutralButton("Manage") { _, _ ->
                    // Show all bookmarks with management options
                    showBookmarkManagementDialog(bookmarks)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun showBookmarkManagementDialog(bookmarks: List<BookmarkWithProgress>) {
        if (bookmarks.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_bookmarks), Toast.LENGTH_SHORT).show()
            return
        }

        val selectedBookmarks = mutableSetOf<Int>()
        val bookmarkTitles = bookmarks.mapIndexed { _, bookmark ->
            val progress = getString(R.string.progress_format, bookmark.progressPercentage.toInt())
            val autoLabel = if (bookmark.bookmark.isAutoBookmark) " (${getString(R.string.auto_bookmark)})" else ""
            "${bookmark.bookmark.title}$autoLabel - $progress"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Manage Bookmarks")
            .setMultiChoiceItems(bookmarkTitles, null) { _, which, isChecked ->
                if (isChecked) {
                    selectedBookmarks.add(which)
                } else {
                    selectedBookmarks.remove(which)
                }
            }
            .setPositiveButton("Delete Selected") { _, _ ->
                if (selectedBookmarks.isNotEmpty()) {
                    val bookmarksToDelete = selectedBookmarks.map { bookmarks[it] }
                    showDeleteConfirmationDialog(bookmarksToDelete)
                } else {
                    Toast.makeText(requireContext(), "No bookmarks selected", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Select All") { _, _ ->
                // Close current dialog and show new one with all selected
                showBookmarkManagementDialogWithAllSelected(bookmarks)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showBookmarkManagementDialogWithAllSelected(bookmarks: List<BookmarkWithProgress>) {
        val selectedBookmarks = mutableSetOf<Int>()
        val bookmarkTitles = bookmarks.mapIndexed { index, bookmark ->
            selectedBookmarks.add(index) // Pre-select all
            val progress = getString(R.string.progress_format, bookmark.progressPercentage.toInt())
            val autoLabel = if (bookmark.bookmark.isAutoBookmark) " (${getString(R.string.auto_bookmark)})" else ""
            "${bookmark.bookmark.title}$autoLabel - $progress"
        }.toTypedArray()

        val checkedItems = BooleanArray(bookmarks.size) { true } // All checked initially

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Manage Bookmarks")
            .setMultiChoiceItems(bookmarkTitles, checkedItems) { _, which, isChecked ->
                if (isChecked) {
                    selectedBookmarks.add(which)
                } else {
                    selectedBookmarks.remove(which)
                }
            }
            .setPositiveButton("Delete Selected") { _, _ ->
                if (selectedBookmarks.isNotEmpty()) {
                    val bookmarksToDelete = selectedBookmarks.map { bookmarks[it] }
                    showDeleteConfirmationDialog(bookmarksToDelete)
                } else {
                    Toast.makeText(requireContext(), "No bookmarks selected", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Clear All") { _, _ ->
                // Close and show dialog with nothing selected
                showBookmarkManagementDialog(bookmarks)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showDeleteConfirmationDialog(bookmarksToDelete: List<BookmarkWithProgress>) {
        val count = bookmarksToDelete.size
        val message = if (count == 1) {
            "Are you sure you want to delete this bookmark?"
        } else {
            "Are you sure you want to delete these $count bookmarks?"
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Bookmarks")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                readingViewModel.deleteBookmarks(bookmarksToDelete)
                Toast.makeText(requireContext(), "Deleted $count bookmark${if (count > 1) "s" else ""}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBookmarkActionsDialog(bookmark: BookmarkWithProgress) {
        val actions = arrayOf(
            getString(R.string.jump_to_bookmark),
            getString(R.string.delete_bookmark)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(bookmark.bookmark.title)
            .setMessage("Progress: ${bookmark.progressPercentage.toInt()}%\n${bookmark.bookmark.content}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> readingViewModel.jumpToBookmark(bookmark)
                    1 -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Delete Bookmark")
                            .setMessage("Are you sure you want to delete this bookmark?")
                            .setPositiveButton("Delete") { _, _ ->
                                readingViewModel.deleteBookmark(bookmark)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    else -> {} // Handle any other cases
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showSaveDocumentDialog() {
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.document_title_hint)
            setText(readingViewModel.getSuggestedDocumentTitle())
            selectAll()
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.save_document))
            .setView(editText)
            .setPositiveButton(getString(R.string.save_document)) { _, _ ->
                val title = editText.text.toString()
                readingViewModel.saveCurrentDocument(title)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showPageJumpDialog() {
        launchOnView {
            val pageInfo = readingViewModel.pageInfo.value
            if (pageInfo.totalPages <= 1) {
                Toast.makeText(requireContext(), getString(R.string.single_page_document), Toast.LENGTH_SHORT).show()
                return@launchOnView
            }

            val editText = EditText(requireContext()).apply {
                hint = getString(R.string.page_jump_hint, pageInfo.totalPages)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(pageInfo.currentPage.toString())
                selectAll()
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.jump_to_page))
                .setMessage(getString(R.string.current_page_info, pageInfo.currentPage, pageInfo.totalPages))
                .setView(editText)
                .setPositiveButton(getString(R.string.jump)) { _, _ ->
                    try {
                        val page = editText.text.toString().toIntOrNull()
                        if (page != null && page in 1..pageInfo.totalPages) {
                            readingViewModel.seekToPage(page)
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.invalid_page_number, pageInfo.totalPages), Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), getString(R.string.invalid_page_input), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun getOrpHighlightColor(): Int {
        return readingViewModel.settings.value.orpColor
    }

    private fun createOrpStyledText(text: String, orpIndex: Int, bold: Boolean): SpannableString {
        val spannableString = SpannableString(text)
        if (orpIndex in text.indices) {
            val highlightColor = getOrpHighlightColor()
            spannableString.setSpan(
                ForegroundColorSpan(highlightColor),
                orpIndex,
                orpIndex + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (bold) {
                spannableString.setSpan(
                    StyleSpan(Typeface.BOLD),
                    orpIndex,
                    orpIndex + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return spannableString
    }

    // Pixel-accurate ORP centering: proportional fonts require measuring actual glyph widths
    // rather than relying on space-padded character counts, which drift under variable letter widths.
    // Runs synchronously so padding and text updates batch into a single layout pass; otherwise
    // the new word flashes one frame at the prior word's padding, creating a sliding glitch.
    private fun applyOrpCenteringPadding(text: String, orpIndex: Int) {
        val tv = binding.rsvpWordDisplay
        if (tv.width > 0) {
            computeAndSetOrpPadding(text, orpIndex)
        } else {
            // First frame before layout: defer once; subsequent words take the sync path.
            tv.post {
                if (_binding == null) return@post
                computeAndSetOrpPadding(text, orpIndex)
            }
        }
    }

    private fun computeAndSetOrpPadding(text: String, orpIndex: Int) {
        val tv = binding.rsvpWordDisplay
        val viewWidth = tv.width
        if (viewWidth <= 0 || text.isEmpty()) return
        val safeOrpIndex = orpIndex.coerceIn(0, text.length - 1)
        val paint = tv.paint
        val widthUpToOrp = if (safeOrpIndex > 0) paint.measureText(text, 0, safeOrpIndex) else 0f
        val orpCharWidth = paint.measureText(text, safeOrpIndex, safeOrpIndex + 1)
        val anchorPx = viewWidth * com.speedread.rsvp.Constants.ORP_ANCHOR_FRACTION
        val desiredStartPadding = (anchorPx - widthUpToOrp - orpCharWidth / 2f)
            .toInt()
            .coerceAtLeast(0)
        if (tv.paddingStart != desiredStartPadding || tv.paddingEnd != 0) {
            tv.setPaddingRelative(desiredStartPadding, tv.paddingTop, 0, tv.paddingBottom)
        }
    }

    // Shrink the font size for any word that would exceed available width at the user's configured
    // size, so it stays on one line. Always resets to configured size first so prior scaling
    // does not carry forward.
    private fun applyAdaptiveWordSizing(text: String, orpIndex: Int, centeredOrp: Boolean) {
        val tv = binding.rsvpWordDisplay
        val configuredSp = effectiveFontSize(themeManager.getCurrentFontSize())
        tv.textSize = configuredSp

        val viewWidth = tv.width
        if (viewWidth <= 0 || text.isEmpty()) return

        val paint = tv.paint
        val requiredPx = if (centeredOrp && orpIndex in text.indices) {
            val safeOrp = orpIndex.coerceIn(0, text.length - 1)
            val leftPx = if (safeOrp > 0) paint.measureText(text, 0, safeOrp) else 0f
            val orpPx = paint.measureText(text, safeOrp, safeOrp + 1)
            val rightPx = if (safeOrp + 1 < text.length) paint.measureText(text, safeOrp + 1, text.length) else 0f
            // Pixel-anchored ORP at fraction `a` of view width: the left half (text before ORP
            // center) must fit within `a * viewWidth`, the right half within `(1 - a) * viewWidth`.
            // The minimum viewWidth that satisfies both is max(leftHalf / a, rightHalf / (1 - a)).
            val leftHalf = leftPx + orpPx / 2f
            val rightHalf = rightPx + orpPx / 2f
            val anchor = com.speedread.rsvp.Constants.ORP_ANCHOR_FRACTION
            maxOf(leftHalf / anchor, rightHalf / (1f - anchor))
        } else {
            paint.measureText(text)
        }

        if (requiredPx > viewWidth) {
            val scaled = (configuredSp * (viewWidth / requiredPx) * com.speedread.rsvp.Constants.ADAPTIVE_FIT_SAFETY_MARGIN)
                .coerceAtLeast(com.speedread.rsvp.Constants.MIN_FONT_SIZE)
            tv.textSize = scaled
        }
    }

    // Applies the landscape 2x multiplier without mutating the user-configured value — the
    // configured size remains canonical in ThemeManager, and orientation toggles recompute
    // the effective size on the fly.
    private fun effectiveFontSize(baseSp: Float): Float {
        val isLandscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return if (isLandscape) baseSp * com.speedread.rsvp.Constants.LANDSCAPE_FONT_SIZE_MULTIPLIER else baseSp
    }

    private fun clearOrpCenteringPadding() {
        val tv = binding.rsvpWordDisplay
        if (tv.paddingStart != 0 || tv.paddingEnd != 0) {
            tv.setPaddingRelative(0, tv.paddingTop, 0, tv.paddingBottom)
        }
    }

    private fun navigateToPreviousWord() {
        launchOnView {
            val currentWordValue = readingViewModel.currentWord.value
            if (currentWordValue != null && currentWordValue.position > 0) {
                readingViewModel.seekToPosition(currentWordValue.position - 1)
            }
        }
    }

    private fun navigateToNextWord() {
        launchOnView {
            val currentWordValue = readingViewModel.currentWord.value
            val pageInfo = readingViewModel.pageInfo.value
            if (currentWordValue != null && currentWordValue.position < pageInfo.totalWords - 1) {
                readingViewModel.seekToPosition(currentWordValue.position + 1)
            }
        }
    }

    private fun openPageView() {
        launchOnView {
            val currentWordValue = readingViewModel.currentWord.value
            if (currentWordValue == null) {
                Toast.makeText(requireContext(), "Load a document first to view in page mode", Toast.LENGTH_SHORT).show()
                return@launchOnView
            }

            val currentWordPosition = currentWordValue.absoluteStartIndex
            val docId = readingViewModel.getCurrentDocumentId()

            if (docId != null) {
                val intent = PageViewActivity.createIntent(
                    context = requireContext(),
                    documentId = docId,
                    currentPosition = currentWordPosition
                )
                pageViewLauncher.launch(intent)
            } else {
                val documentTitle = readingViewModel.currentDocumentTitle.value
                val documentText = readingViewModel.getCurrentDocumentText()
                if (documentText.isBlank()) {
                    Toast.makeText(requireContext(), "No document loaded", Toast.LENGTH_SHORT).show()
                    return@launchOnView
                }
                PageViewActivity.setTemporaryText(documentText, documentTitle ?: "Document")
                val intent = PageViewActivity.createIntent(
                    context = requireContext(),
                    documentId = null,
                    documentText = null,
                    documentTitle = null,
                    currentPosition = currentWordPosition
                )
                pageViewLauncher.launch(intent)
            }
        }
    }

    private fun openPageViewWithDocument(document: SavedDocument) {
        val intent = PageViewActivity.createIntent(
            context = requireContext(),
            documentId = document.id,
            currentPosition = 0 // Start from beginning for saved documents
        )

        // Start PageViewActivity and listen for result
        pageViewLauncher.launch(intent)
    }

    private fun setupHoldToPlayButton() {
        // Both the big portrait FAB and the compact landscape corner FABs share the same
        // dynamic tap-vs-hold semantics: tap toggles play/pause, press-and-hold plays while
        // held and pauses on release. Visual feedback differs — portrait uses a scale pulse,
        // landscape compact corners fade to alpha 0 so the FAB does not cover the word display
        // while the user is actively reading.
        attachDynamicHoldToPlayListener(binding.fabHoldToPlay, hideWhileHeld = false)
        // Compact corner FABs only exist in layout-land/fragment_reading.xml — ViewBinding
        // generates nullable fields for variant-only views. In portrait the list is empty
        // so the loops below short-circuit.
        for ((fab, _) in compactCornerFabs()) {
            attachDynamicHoldToPlayListener(fab, hideWhileHeld = true)
        }

        collectOnView(readingViewModel.state) {
            val iconRes = when (it) {
                RsvpState.Playing -> R.drawable.ic_pause
                else -> R.drawable.ic_play_arrow
            }
            binding.fabHoldToPlay.setImageResource(iconRes)
            for ((fab, _) in compactCornerFabs()) {
                fab.setImageResource(iconRes)
            }
        }

        // Observe the user's landscape-corner preference (additive set) and toggle each
        // corner FAB's visibility to match. Portrait has no compact FABs, so the loop body
        // never runs there.
        collectOnView(readingViewModel.settings) { settings ->
            val active = settings.landscapePlayButtonCorners
            for ((fab, corner) in compactCornerFabs()) {
                fab.visibility = if (corner in active) View.VISIBLE else View.GONE
            }
        }
    }

    // Pair each nullable compact-FAB binding field with the corner it represents. listOfNotNull
    // drops any that the current layout variant doesn't declare (portrait declares none).
    private fun compactCornerFabs(): List<Pair<FloatingActionButton, LandscapePlayButtonCorner>> =
        listOfNotNull(
            binding.fabHoldToPlayCompactTopStart?.let { it to LandscapePlayButtonCorner.TOP_START },
            binding.fabHoldToPlayCompactTopEnd?.let { it to LandscapePlayButtonCorner.TOP_END },
            binding.fabHoldToPlayCompactBottomStart?.let { it to LandscapePlayButtonCorner.BOTTOM_START },
            binding.fabHoldToPlayCompactBottomEnd?.let { it to LandscapePlayButtonCorner.BOTTOM_END }
        )

    // Dynamic tap-vs-hold wiring shared by the big portrait FAB and the landscape compact
    // corner FABs. Behavior:
    //   ACTION_DOWN → start playing immediately (so a hold feels responsive, with no
    //                 wait-for-threshold lag) and snapshot whether the engine was already
    //                 Playing before the press.
    //   ACTION_UP   → if elapsed < HOLD_TO_PLAY_TAP_THRESHOLD_MS it's a tap (toggle):
    //                   - was playing before press → pause (completes the toggle)
    //                   - was not playing before press → leave it playing (DOWN already toggled)
    //                 if elapsed ≥ threshold it's a hold → pause (release ends the hold).
    // Snapshotting state on DOWN (rather than re-reading on UP) is what lets a tap behave as
    // a toggle even though play() fires on every press.
    //
    // hideWhileHeld switches the visual feedback channel:
    //   false (portrait big FAB) → scale pulse animated via fab.animate(). The FAB stays
    //                              fully opaque so it remains a visible target.
    //   true  (landscape compact corner FABs) → instant alpha toggle on every active corner
    //                              (NOT visibility — changing visibility mid-gesture can break
    //                              touch dispatch on some OEMs; alpha keeps the view attached
    //                              so ACTION_UP/CANCEL still reaches this listener). Fading
    //                              all active corners on a single press prevents an unpressed
    //                              corner from continuing to cover the word display while the
    //                              user reads.
    private fun attachDynamicHoldToPlayListener(
        fab: FloatingActionButton,
        hideWhileHeld: Boolean
    ) {
        var pressStartMs = 0L
        var wasPlayingBeforePress = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var releaseRunnable: Runnable? = null

        fab.setOnTouchListener { _, event ->
            val density = fab.resources.displayMetrics.density
            val isLandscape = fab.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val dim = !isLandscape &&
                readingViewModel.settings.value.enableScreenDimming &&
                readingViewModel.state.value == RsvpState.Playing &&
                readingViewModel.activeMode.value == com.speedread.rsvp.tts.PlaybackMode.RSVP

            val restZ = if (dim) {
                com.speedread.rsvp.Constants.HOLD_TO_PLAY_DIMMED_BASE_TRANSLATION_Z_DP * density
            } else {
                0f
            }
            val pressZ = if (dim) {
                com.speedread.rsvp.Constants.HOLD_TO_PLAY_DIMMED_PRESS_TRANSLATION_Z_DP * density
            } else {
                com.speedread.rsvp.Constants.HOLD_TO_PLAY_PRESS_TRANSLATION_Z * density
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    releaseRunnable?.let { handler.removeCallbacks(it) }
                    releaseRunnable = null

                    pressStartMs = System.currentTimeMillis()
                    wasPlayingBeforePress = readingViewModel.state.value == RsvpState.Playing
                    readingViewModel.play()
                    fab.isPressed = true
                    if (hideWhileHeld) {
                        setCompactCornerFabsAlpha(com.speedread.rsvp.Constants.HOLD_TO_PLAY_HIDDEN_ALPHA)
                    } else {
                        fab.animate()
                            .scaleX(com.speedread.rsvp.Constants.HOLD_TO_PLAY_PRESS_SCALE)
                            .scaleY(com.speedread.rsvp.Constants.HOLD_TO_PLAY_PRESS_SCALE)
                            .translationZ(pressZ)
                            .setDuration(com.speedread.rsvp.Constants.HOLD_TO_PLAY_ANIM_DURATION_MS)
                            .start()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val elapsed = System.currentTimeMillis() - pressStartMs
                    val isTap = elapsed < com.speedread.rsvp.Constants.HOLD_TO_PLAY_TAP_THRESHOLD_MS
                    if (isTap) {
                        if (wasPlayingBeforePress) {
                            readingViewModel.pause()
                        }
                    } else {
                        readingViewModel.pause()
                    }

                    val delay = (com.speedread.rsvp.Constants.HOLD_TO_PLAY_MIN_PRESS_DURATION_MS - elapsed).coerceAtLeast(0)
                    val doRelease = Runnable {
                        fab.isPressed = false
                        if (hideWhileHeld) {
                            setCompactCornerFabsAlpha(com.speedread.rsvp.Constants.HOLD_TO_PLAY_VISIBLE_ALPHA)
                        } else {
                            fab.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .translationZ(restZ)
                                .setDuration(com.speedread.rsvp.Constants.HOLD_TO_PLAY_ANIM_DURATION_MS)
                                .start()
                        }
                    }

                    releaseRunnable = doRelease
                    if (delay > 0) {
                        handler.postDelayed(doRelease, delay)
                    } else {
                        doRelease.run()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // Set alpha on every compact corner FAB so a single press/release fades all currently-
    // active corners together. Hidden (GONE) FABs are included — the alpha write is a cheap
    // no-op on them and it keeps the bookkeeping trivial when the user toggles corners on
    // mid-session.
    private fun setCompactCornerFabsAlpha(alpha: Float) {
        for ((fab, _) in compactCornerFabs()) {
            fab.alpha = alpha
        }
    }

    override fun onResume() {
        super.onResume()
        applyPageViewOnlyMode()
    }

    private fun applyPageViewOnlyMode() {
        val enabled = themeManager.isPageViewOnlyMode()
        val rsvpVisibility = if (enabled) View.GONE else View.VISIBLE
        val placeholderVisibility = if (enabled) View.VISIBLE else View.GONE

        with(binding) {
            pageViewOnlyPlaceholder.visibility = placeholderVisibility
            rsvpDisplayCard.visibility = rsvpVisibility
            progressBar.visibility = rsvpVisibility
            pageCounter.visibility = rsvpVisibility
            navigationLayout.visibility = rsvpVisibility
            controlsLayout.visibility = rsvpVisibility
            wpmSliderLayout.visibility = rsvpVisibility
            fabHoldToPlay.visibility = rsvpVisibility
            val isLandscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            fabEnterLandscape.visibility = if (isLandscape) View.VISIBLE else rsvpVisibility
            fabPageView.visibility = rsvpVisibility
            fabImportText.visibility = rsvpVisibility
            fabListenToggle?.visibility = rsvpVisibility
            ttsRateSelector?.visibility = if (enabled) View.GONE else ttsRateSelector?.visibility ?: View.GONE
        }

        if (enabled) {
            readingViewModel.pause()
            if (!pageViewOnlyLaunchPending) {
                pageViewOnlyLaunchPending = true
                launchOnView {
                    val recent = savedDocumentRepository.getMostRecentDocument()
                    if (recent != null) {
                        val intent = PageViewActivity.createIntent(
                            context = requireContext(),
                            documentId = recent.id,
                            currentPosition = 0
                        )
                        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(intent)
                    }
                    activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                        R.id.bottom_navigation
                    )?.selectedItemId = R.id.libraryFragment
                }
            }
        } else {
            pageViewOnlyLaunchPending = false
        }
    }

    // Manage the BottomNavigationView (which lives in MainActivity, outside this fragment's
    // view tree). Landscape hides it so the fullscreen RSVP card has the whole screen; in
    // portrait it stays visible. The "Dim Surrounding Screen + Playing" case is handled
    // separately by applyDimMask — DimMaskView paints over the nav and consumes its taps,
    // so the nav stays in the layout (avoids reflowing the play/pause controls) but reads
    // as part of the dim strip. onStart/onStop bracket nav restoration so sibling fragments
    // (Library / Options) never inherit a hidden bar.
    override fun onStart() {
        super.onStart()
        applyBottomNavVisibility()
        applyDimMask()
        if (isDebuggableBuild()) {
            stallWatchdog.start()
        }
    }

    private fun applyBottomNavVisibility() {
        val isLandscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val activity = requireActivity()
        activity.findViewById<View>(R.id.bottom_navigation)?.visibility =
            if (isLandscape) View.GONE else View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        // Only auto-pause in RSVP mode — RSVP is purely visual, so backgrounding the app means
        // the user can no longer see the words. In TTS (Listen) mode the foreground service +
        // MediaSession keep playback alive in the background; the user controls it via the
        // notification / lock-screen / BT headset, so we must NOT pause here.
        if (readingViewModel.activeMode.value == com.speedread.rsvp.tts.PlaybackMode.RSVP) {
            readingViewModel.pause()
        }
        // Drop the keep-screen-on flag the moment we lose foreground, regardless of whether
        // the state collector has yet observed the pause above. The flag lives on the shared
        // window so a stale set would otherwise carry into whatever resumes next.
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        super.onStop()
        stallWatchdog.stop()
        val activity = requireActivity()
        applyPlayButtonZOrder(false)
        activity.findViewById<View>(R.id.bottom_navigation)?.visibility = View.VISIBLE
        activity.findViewById<DimMaskView>(R.id.dimMask)?.let {
            it.visibility = View.GONE
            it.clearExcludeRect()
            it.clearBlockTouchRect()
        }
        lifecycleScope.launch {
            readingViewModel.saveCurrentReadingPosition()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pageViewOnlyLaunchPending = false
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        _binding = null
    }
}
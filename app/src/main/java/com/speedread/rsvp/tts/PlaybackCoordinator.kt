package com.speedread.rsvp.tts

import com.speedread.rsvp.Constants
import com.speedread.rsvp.RsvpSettingsManager
import com.speedread.rsvp.data.settings.TtsSettingsRepository
import com.speedread.rsvp.engine.RsvpEngine
import com.speedread.rsvp.engine.RsvpState
import com.speedread.rsvp.engine.RsvpWord
import com.speedread.rsvp.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fronts the RSVP engine and the TTS engine behind a single API, so ReadingViewModel can
 * stay engine-agnostic and UI observers never notice when playback switches backends.
 *
 * Design:
 *   - Mode is represented by [activeMode] (RSVP or TTS). Mode switches are mutex-serialized
 *     so play/pause callers never race with a simultaneous toggle.
 *   - Unified flows use [flatMapLatest] on [activeMode] so [currentWord], [progress], and
 *     [state] transparently follow whichever engine is active.
 *   - [state] maps TtsState onto RsvpState for backwards compatibility — every existing
 *     state-dependent UI path (play/pause button icon, progress-bar enable, etc.) continues
 *     to work without case-branching on the backend. TTS-only states (Initializing,
 *     BackendUnavailable, Error) surface as transient [notices] for Snackbar display.
 *   - Seek debouncing is applied ONLY to the TTS path (queue teardown + re-synthesis is
 *     expensive). RSVP seek stays immediate — it's just an integer assignment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PlaybackCoordinator @Inject constructor(
    private val rsvpEngine: RsvpEngine,
    private val engineProvider: TtsEngineProvider,
    private val ttsSettings: TtsSettingsRepository,
    private val serviceController: TtsServiceController,
    // Persisted-mode source. The coordinator seeds [_activeMode] from this on construction
    // so a user who closed the app in TTS mode reopens in TTS, and writes back here on every
    // mode toggle so the persisted value stays current. Kept as the singleton instance — same
    // SharedPreferences-backed manager the rest of the app uses for reader prefs.
    private val rsvpSettingsManager: RsvpSettingsManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    private val _activeMode = MutableStateFlow(rsvpSettingsManager.getLastPlaybackMode())
    val activeMode: StateFlow<PlaybackMode> = _activeMode.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    // Current document title, set by ReadingViewModel.updateDocumentTitle. The background TTS
    // service reads this to label the foreground notification / lock-screen metadata. Nullable
    // because a fresh session (clipboard / manual text) has no title until the user assigns one.
    private val _currentTitle = MutableStateFlow<String?>(null)
    val currentTitle: StateFlow<String?> = _currentTitle.asStateFlow()

    fun setCurrentTitle(title: String?) {
        _currentTitle.value = title
    }

    // Page boundary cache, set by ReadingViewModel to resolve word indices to page numbers.
    @Volatile private var pageBoundaries: List<com.speedread.rsvp.data.document.PageBoundary>? = null
    @Volatile private var pageCount: Int? = null

    fun setPageBoundaries(boundaries: List<com.speedread.rsvp.data.document.PageBoundary>?, count: Int?) {
        pageBoundaries = boundaries
        pageCount = count
    }

    fun getPageInfoForPosition(position: Int): Pair<Int, Int> {
        val boundaries = pageBoundaries
        val count = pageCount
        val absWordCount = getAbsoluteWordCount()
        return if (boundaries != null && count != null) {
            val currentPage = boundaries.find { boundary ->
                position >= boundary.startWordIndex && position <= boundary.endWordIndex
            }?.pageNumber ?: 1
            currentPage to count
        } else {
            val currentPage = (position / Constants.WORDS_PER_PAGE_ESTIMATION) + 1
            val totalPages = ((absWordCount - 1) / Constants.WORDS_PER_PAGE_ESTIMATION) + 1
            currentPage to totalPages.coerceAtLeast(1)
        }
    }

    // Engine-selection key for the unified flows. Keyed on BOTH mode and TTS backend: a
    // backend radio change while in TTS mode must re-subscribe the flows to the new engine,
    // otherwise the UI keeps tracking the old engine while controls drive the new one.
    // distinctUntilChanged suppresses re-subscription on unrelated TtsSettings edits
    // (rate, pitch, voice) — only a real (mode, backend) change re-keys.
    private data class EngineKey(val mode: PlaybackMode, val backend: TtsBackend)

    private val engineKey: Flow<EngineKey> =
        combine(_activeMode, ttsSettings.settings) { mode, settings ->
            EngineKey(mode, settings.backend)
        }.distinctUntilChanged()

    // Unified observers. flatMapLatest unsubscribes from the previous engine and subscribes
    // to the new one on every mode or backend switch — no manual bookkeeping.
    val currentWord: Flow<RsvpWord?> = engineKey.flatMapLatest { key ->
        when (key.mode) {
            PlaybackMode.RSVP -> rsvpEngine.observeCurrentWord()
            PlaybackMode.TTS -> engineProvider.resolve(key.backend).observeCurrentWord()
        }
    }

    val progress: Flow<Float> = engineKey.flatMapLatest { key ->
        when (key.mode) {
            PlaybackMode.RSVP -> rsvpEngine.observeProgress()
            PlaybackMode.TTS -> engineProvider.resolve(key.backend).observeProgress()
        }
    }

    val state: Flow<RsvpState> = engineKey.flatMapLatest { key ->
        when (key.mode) {
            PlaybackMode.RSVP -> rsvpEngine.observeState()
            PlaybackMode.TTS -> engineProvider.resolve(key.backend).observeState().map { ttsToRsvp(it) }
        }
    }

    // Eager collectors so toggleMode can read the latest state synchronously. Scope is
    // Singleton-lived; no risk of leaking the subscription.
    private val latestRsvpState: StateFlow<RsvpState> = rsvpEngine.observeState()
        .stateIn(scope, SharingStarted.Eagerly, RsvpState.Idle)
    private val latestTtsState: StateFlow<TtsState> = engineKey
        .flatMapLatest { key -> engineProvider.resolve(key.backend).observeState() }
        .stateIn(scope, SharingStarted.Eagerly, TtsState.Idle)

    init {
        // Single, always-on collector for TTS state side effects (notices + foreground
        // service teardown). These used to live inside ttsToRsvp's map lambda, which runs
        // once per active collector of [state] — duplicating snackbars when both the
        // ViewModel and the playback service were subscribed, and never running at all when
        // no collector was active.
        scope.launch {
            engineKey.flatMapLatest { key ->
                if (key.mode == PlaybackMode.TTS) {
                    engineProvider.resolve(key.backend).observeState()
                } else {
                    emptyFlow()
                }
            }.collect { ttsState ->
                when (ttsState) {
                    is TtsState.Finished -> serviceController.stop()
                    is TtsState.Error -> {
                        _notices.emit(ttsState.message)
                        serviceController.stop()
                    }
                    TtsState.BackendUnavailable -> {
                        _notices.emit(NOTICE_NEURAL_FALLBACK)
                        serviceController.stop()
                    }
                    else -> Unit
                }
            }
        }
    }

    private var seekDebounceJob: Job? = null

    // Monotonic counter bumped on every document load into this coordinator. Lets owners
    // (ReadingViewModel) detect that another screen (Page View) has since loaded a different
    // document into the shared singleton, so stale observers stop persisting positions and
    // bookmarks against the wrong document.
    private val loadGeneration = AtomicLong(0L)

    fun getLoadGeneration(): Long = loadGeneration.get()

    @Volatile private var currentDocumentId: Long? = null
    @Volatile private var currentContentHash: String? = null

    fun getCurrentDocumentId(): Long? = currentDocumentId
    fun getCurrentContentHash(): String? = currentContentHash

    /** Source of truth: RSVP engine tokenizes and holds the word list. TTS consumes its output. */
    suspend fun loadText(
        text: String,
        documentId: Long? = null,
        contentHash: String? = null
    ) = mutex.withLock {
        currentDocumentId = documentId
        currentContentHash = contentHash
        loadGeneration.incrementAndGet()
        rsvpEngine.loadText(text)
        // If TTS mode was active, sync the new word list to the TTS engine immediately so a
        // subsequent play doesn't speak stale content.
        if (_activeMode.value == PlaybackMode.TTS) {
            activeTtsEngine().loadWords(rsvpEngine.getWords())
        }
    }

    suspend fun loadPreTokenized(
        singleWordTokens: List<RsvpWord>,
        documentId: Long? = null,
        contentHash: String? = null
    ) = mutex.withLock {
        currentDocumentId = documentId
        currentContentHash = contentHash
        loadGeneration.incrementAndGet()
        rsvpEngine.loadPreTokenized(singleWordTokens)
        if (_activeMode.value == PlaybackMode.TTS) {
            activeTtsEngine().loadWords(rsvpEngine.getWords())
        }
    }

    fun getSingleWordTokens(): List<RsvpWord> = rsvpEngine.getSingleWordTokens()

    /** Progress-reporting variant, mirrors RsvpEngine.loadTextWithProgress. */
    suspend fun loadTextWithProgress(
        text: String,
        onProgress: (Float) -> Unit
    ) = loadTextWithProgress(text, null, null, onProgress)

    suspend fun loadTextWithProgress(
        text: String,
        documentId: Long? = null,
        contentHash: String? = null,
        onProgress: (Float) -> Unit
    ) = mutex.withLock {
        currentDocumentId = documentId
        currentContentHash = contentHash
        loadGeneration.incrementAndGet()
        rsvpEngine.loadTextWithProgress(text, onProgress)
        if (_activeMode.value == PlaybackMode.TTS) {
            activeTtsEngine().loadWords(rsvpEngine.getWords())
        }
    }

    // play/pause/stop share the same mutex as toggleMode so a concurrent mode toggle cannot
    // flip _activeMode between the branch check and the engine call (previously a pause racing
    // a toggle could yank the freshly-started RSVP playback back to the TTS engine's old
    // position via the trailing seek below).
    suspend fun play() = mutex.withLock {
        when (_activeMode.value) {
            PlaybackMode.RSVP -> rsvpEngine.play()
            PlaybackMode.TTS -> {
                // Start the foreground service BEFORE the engine begins speaking so the process
                // has an active foreground attachment before the user backgrounds the app. Idempotent
                // — safe to call on each play/resume.
                serviceController.startIfNeeded()
                // Do NOT reprime from rsvpEngine.getCurrentPosition() here — the RSVP engine's
                // position is stale during TTS playback (it only advances when RSVP's own timer
                // runs). Re-priming would reset every hold-release cycle to the position that
                // TTS originally seeded from, effectively losing progress on each replay. The
                // TTS engine's internal currentPosition is already up-to-date (onRangeStart
                // updates it per word); tts.play() from Paused state resumes where it left off.
                // Priming happens exclusively in toggleMode() (on mode switch) and loadText()
                // (on new document), which are the only times the TTS word list can become stale.
                activeTtsEngine().play()
            }
        }
    }

    suspend fun pause() = mutex.withLock {
        when (_activeMode.value) {
            PlaybackMode.RSVP -> rsvpEngine.pause()
            PlaybackMode.TTS -> {
                // A pending debounced scrub-seek must not fire AFTER this pause syncs
                // positions — it would set the TTS engine to the scrub target while RSVP
                // keeps the pre-scrub position, desyncing the two engines. Apply it now
                // instead so the pause lands on the position the user actually chose.
                seekDebounceJob?.let {
                    it.cancel()
                    seekDebounceJob = null
                }
                val tts = activeTtsEngine()
                tts.pause()
                // Sync TTS's final position back into the RSVP engine so anything that still
                // asks RSVP for "current position" (toggleMode, getCurrentPosition, saved
                // reading position) sees where the user actually stopped — not where TTS
                // originally started. This is the correct single source of truth across both
                // engines: RSVP's getCurrentPosition after this sync equals TTS's.
                rsvpEngine.seekToPosition(tts.getCurrentPosition())
            }
        }
    }

    suspend fun stop() = mutex.withLock {
        when (_activeMode.value) {
            PlaybackMode.RSVP -> rsvpEngine.stop()
            PlaybackMode.TTS -> {
                seekDebounceJob?.let {
                    it.cancel()
                    seekDebounceJob = null
                }
                activeTtsEngine().stop()
                // Explicit stop tears down the foreground service; the user has signalled the
                // end of this listening session, so the notification should disappear.
                serviceController.stop()
            }
        }
    }

    // --- document-metadata passthroughs ---
    // The RSVP engine owns tokenization, so it remains the source of truth for word list,
    // total count, position, and truncation state regardless of which engine is playing.

    fun getWords(): List<RsvpWord> = rsvpEngine.getWords()
    fun getTotalWords(): Int = rsvpEngine.getTotalWords()
    fun getCurrentPosition(): Int = rsvpEngine.getCurrentPosition()
    fun wasLastLoadTruncated(): Boolean = rsvpEngine.wasLastLoadTruncated()

    fun getAbsoluteWordCount(): Int {
        val words = rsvpEngine.getWords()
        return if (words.isNotEmpty()) words.last().absoluteEndIndex + 1 else 0
    }

    /**
     * Seeks to the chunk containing the given absolute word index.
     * This ensures Page View highlight and bookmarks (which use absolute indices)
     * correctly map to the RSVP engine's chunk indices regardless of chunk size.
     */
    suspend fun seekToAbsoluteWordPosition(absoluteWordIndex: Int, isScrubbing: Boolean = false) =
        mutex.withLock {
            val words = rsvpEngine.getWords()
            if (words.isEmpty()) return@withLock

            var low = 0
            var high = words.size - 1
            var targetChunk = -1

            while (low <= high) {
                val mid = (low + high) ushr 1
                val midWord = words[mid]
                if (absoluteWordIndex < midWord.absoluteStartIndex) {
                    high = mid - 1
                } else if (absoluteWordIndex > midWord.absoluteEndIndex) {
                    low = mid + 1
                } else {
                    targetChunk = mid
                    break
                }
            }

            if (targetChunk == -1) {
                targetChunk = if (absoluteWordIndex < words.first().absoluteStartIndex) 0 else words.size - 1
            }

            seekToPositionLocked(targetChunk, isScrubbing)
        }

    suspend fun seekToPosition(position: Int, isScrubbing: Boolean = false) = mutex.withLock {
        seekToPositionLocked(position, isScrubbing)
    }

    /**
     * Seek implementation; caller must hold [mutex]. Split from the public wrapper so
     * [seekToAbsoluteWordPosition] (which also locks) can delegate without self-deadlocking
     * on the non-reentrant mutex.
     *
     * @param isScrubbing true when the call is part of a continuous scrub gesture. Debounces
     *   the TTS path by [Constants.TTS_SEEK_DEBOUNCE_MS] so finger-drag doesn't thrash the
     *   synthesis queue. RSVP seeks always apply immediately.
     */
    private suspend fun seekToPositionLocked(position: Int, isScrubbing: Boolean) {
        rsvpEngine.seekToPosition(position)  // always cheap
        if (_activeMode.value != PlaybackMode.TTS) return

        if (!isScrubbing) {
            seekDebounceJob?.let {
                it.cancel()
                seekDebounceJob = null
            }
            activeTtsEngine().seekToPosition(position)
            return
        }

        seekDebounceJob?.cancel()
        seekDebounceJob = scope.launch {
            delay(Constants.TTS_SEEK_DEBOUNCE_MS)
            activeTtsEngine().seekToPosition(position)
        }
    }

    /**
     * Seamless handoff between RSVP and TTS modes. The target engine loads the current word
     * list, seeks to the pre-toggle position, and resumes playing iff the previous engine
     * was playing. Mutex serializes concurrent toggles.
     */
    suspend fun toggleMode() = mutex.withLock {
        val previousMode = _activeMode.value
        val nextMode = if (previousMode == PlaybackMode.RSVP) PlaybackMode.TTS else PlaybackMode.RSVP

        val wasPlaying = when (previousMode) {
            PlaybackMode.RSVP -> latestRsvpState.value is RsvpState.Playing
            PlaybackMode.TTS -> latestTtsState.value is TtsState.Playing
        }

        // Pause the departing engine AND read its final position from the departing engine
        // itself — not from rsvpEngine blindly. When leaving TTS, RSVP's position is stale
        // (RSVP's timer wasn't running); reading from the TTS engine gives the word the user
        // actually stopped on. After pause, seed RSVP to that same position so it becomes the
        // shared source of truth again.
        val currentPos: Int = when (previousMode) {
            PlaybackMode.RSVP -> {
                rsvpEngine.pause()
                rsvpEngine.getCurrentPosition()
            }
            PlaybackMode.TTS -> {
                val tts = activeTtsEngine()
                tts.pause()
                val pos = tts.getCurrentPosition()
                rsvpEngine.seekToPosition(pos)
                pos
            }
        }

        // Guard against toggling to TTS when neural is selected but no model exists. Fall
        // back silently to system; surface a notice so the user knows to drop a model in.
        val requestedBackend = ttsSettings.settings.value.backend
        if (nextMode == PlaybackMode.TTS &&
            requestedBackend == TtsBackend.NEURAL &&
            engineProvider.neuralWouldFallBack()
        ) {
            scope.launch {
                _notices.emit(NOTICE_NEURAL_FALLBACK)
            }
        }

        _activeMode.value = nextMode
        // Persist the new mode so the next cold launch resumes in this mode rather than
        // defaulting to RSVP. Cheap commit — SharedPreferences applies asynchronously.
        rsvpSettingsManager.saveLastPlaybackMode(nextMode)

        // Prime the target engine: same word list, same position. No-op for empty docs.
        if (rsvpEngine.getTotalWords() > 0) {
            when (nextMode) {
                PlaybackMode.TTS -> {
                    val tts = activeTtsEngine()
                    tts.loadWords(rsvpEngine.getWords())
                    tts.seekToPosition(currentPos)
                    if (wasPlaying) {
                        serviceController.startIfNeeded()
                        tts.play()
                    }
                }
                PlaybackMode.RSVP -> {
                    rsvpEngine.seekToPosition(currentPos)
                    if (wasPlaying) rsvpEngine.play()
                    // RSVP is visual; background playback has no meaning. Always release the
                    // foreground service on transitions into RSVP mode, regardless of whether
                    // TTS was playing — covers the "paused TTS + toggle to RSVP" path too.
                    serviceController.stop()
                }
            }
        }
    }

    /**
     * Handoff when the user changes the TTS backend radio while in TTS mode. The DEPARTING
     * engine must be paused first — it would otherwise keep speaking with no reachable
     * controls (activeTtsEngine() already resolves the new backend) — and it, not the RSVP
     * engine, holds the true current position (RSVP's position is stale during TTS playback).
     * The new engine is then primed with the same word list and position, resuming playback
     * iff the old engine was playing.
     */
    suspend fun onTtsBackendChanged(previousBackend: TtsBackend) = mutex.withLock {
        if (_activeMode.value != PlaybackMode.TTS) return@withLock
        val oldEngine = engineProvider.resolve(previousBackend)
        val newEngine = activeTtsEngine()
        if (oldEngine === newEngine) return@withLock

        val wasPlaying = oldEngine.observeState().first() is TtsState.Playing
        try {
            oldEngine.pause()
        } catch (e: Exception) {
            Logger.e(TAG, "Backend-change pause of departing engine failed", e)
        }
        val currentPos = oldEngine.getCurrentPosition()
        rsvpEngine.seekToPosition(currentPos)
        try {
            newEngine.loadWords(rsvpEngine.getWords())
            newEngine.seekToPosition(currentPos)
            if (wasPlaying) {
                serviceController.startIfNeeded()
                newEngine.play()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Backend-change sync failed", e)
        }
    }

    private fun activeTtsEngine(): TtsEngine =
        engineProvider.resolve(ttsSettings.settings.value.backend)

    // Pure mapping — all side effects (notices, foreground service teardown) live in the
    // single eager collector in init, NOT here: this runs once per collector of [state].
    private fun ttsToRsvp(state: TtsState): RsvpState = when (state) {
        is TtsState.Playing -> RsvpState.Playing
        is TtsState.Paused -> RsvpState.Paused
        is TtsState.Finished -> RsvpState.Finished
        // Idle, Initializing, BackendUnavailable, Error all map to Idle for the state flow;
        // Error / BackendUnavailable are surfaced separately via [notices].
        is TtsState.Error -> RsvpState.Idle
        TtsState.BackendUnavailable -> RsvpState.Idle
        TtsState.Initializing -> RsvpState.Idle
        TtsState.Idle -> RsvpState.Idle
    }

    companion object {
        private const val TAG = "PlaybackCoordinator"
        const val NOTICE_NEURAL_FALLBACK =
            "No Neural voices installed. Drop a Piper voice folder into the app model directory."
    }
}

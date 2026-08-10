package com.speedread.rsvp.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.speedread.rsvp.Constants
import com.speedread.rsvp.engine.RsvpWord
import com.speedread.rsvp.engine.TtsChunker
import com.speedread.rsvp.engine.TtsUtterance
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System-backed TTS engine wrapping [android.speech.tts.TextToSpeech].
 *
 * Design highlights:
 *   - Held as a @Singleton so the service connection survives fragment/activity churn. The
 *     actual native TTS init is deferred until first use so cold-start isn't bogged down.
 *   - Every UtteranceProgressListener callback carries an utterance ID prefixed with a
 *     session GUID. When a seek or stop bumps the session, stale callbacks from the old
 *     session are ignored — this is how we prevent a late onDone from auto-advancing into
 *     the wrong position after the user scrubbed.
 *   - `onRangeStart` fires per-word on modern Google/Samsung engines. If the first utterance
 *     completes without any onRangeStart fire, the voice is flagged as non-word-capable and
 *     we fall back to time-proportional word advancement for the remainder of the session.
 *   - StateFlow writes hop to Dispatchers.Main.immediate via [engineScope] to keep emission
 *     ordering identical to DefaultRsvpEngine's observer contract.
 */
@Singleton
class NativeTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioFocusHelper: AudioFocusHelper
) : TtsEngine {

    // Mirrors the DefaultRsvpEngine.engineScope contract: same-thread emissions stay
    // synchronous; one failing child can't cascade into sibling jobs.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Serializes play/pause/stop/seek against TTS callbacks that also mutate state.
    private val mutex = Mutex()

    // Serializes [recoverFromDisconnect]. Held independently of [mutex] because the
    // onDone-driven refill path calls recovery without holding the engine mutex (callbacks
    // fire on the TTS binder thread, far from any caller-held lock). Acquisition order
    // when both are needed is always: [mutex] then [recoveryMutex].
    private val recoveryMutex = Mutex()

    // Guards auto-advance in onDone/onStop/onError. Flipped true on pause/stop/seek so the
    // very next callback from the now-cancelled utterance does not enqueue more speech.
    private val isPausing = AtomicBoolean(false)

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    private val _currentWord = MutableStateFlow<RsvpWord?>(null)
    private val _progress = MutableStateFlow(0f)

    private var tts: TextToSpeech? = null
    @Volatile private var isInitialized: Boolean = false

    // Voice list cache, populated on init. Pure data copies so callers don't drag
    // android.speech.tts.Voice into their layer. Exposed as a StateFlow so the Options UI
    // can react to the empty→populated transition that happens when init completes — without
    // this, the voice dropdown stays stuck on its empty initial value until something else
    // forces a re-render.
    private val _voices = MutableStateFlow<List<TtsVoice>>(emptyList())
    val voices: StateFlow<List<TtsVoice>> = _voices.asStateFlow()

    // Playback state. Words are owned by the engine once loadWords is called; utterances
    // are re-chunked from currentPosition on every play/seek so the queue is always a
    // truthful mirror of "what will be spoken next".
    private var words: List<RsvpWord> = emptyList()
    private var currentPosition: Int = 0
    private var utterances: List<TtsUtterance> = emptyList()

    // Index of the chunk the TTS engine is currently speaking. Updated in onStart so
    // that onRangeStart can map char offsets to the right TtsUtterance even when the
    // queue has lookahead chunks ahead of it.
    @Volatile private var playingChunkIndex: Int = 0

    // High-watermark of chunk indices passed to engine.speak(). Drives the lookahead
    // top-up: we keep the TTS service's queue saturated TTS_LOOKAHEAD_CHUNKS ahead of
    // playingChunkIndex so synthesis pre-warms and inter-chunk audio gaps disappear.
    @Volatile private var enqueuedThroughIndex: Int = -1

    // Per-session GUID: utteranceId = "{sessionGuid}_{chunkIndex}". Bumped on stop/seek.
    @Volatile private var sessionGuid: String = UUID.randomUUID().toString()

    // Per-voice memo: did onRangeStart ever fire during the first utterance? If no,
    // subsequent utterances use time-proportional advancement instead.
    private val voiceRangeSupportMemo = HashMap<String, Boolean>()

    // Set of chunk indices that have received at least one onRangeStart in this session.
    // Populated in onRangeStart, drained in onDone — the lookup decides whether to fall
    // back to proportional advancement for the just-finished chunk. Concurrent because
    // TTS callbacks fire on the TTS callback thread while top-up reads on engineScope.
    private val chunksThatSawRange = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    // Mutable settings — also captured per-session so a mid-playback rate change takes effect
    // on the NEXT utterance, not retroactively (prevents mid-word pitch jumps).
    private var settings: TtsSettings = TtsSettings()

    override fun backendId(): TtsBackend = TtsBackend.SYSTEM

    override fun isAvailable(): Boolean = true  // System TTS is assumed present on any Android device

    override fun observeCurrentWord(): Flow<RsvpWord?> = _currentWord.asStateFlow()
    override fun observeProgress(): Flow<Float> = _progress.asStateFlow()
    override fun observeState(): Flow<TtsState> = _state.asStateFlow()

    override fun updateSettings(settings: TtsSettings) {
        val previous = this.settings
        this.settings = settings
        applyVoiceRateAndPitch()

        // If voice/rate/pitch changed during active playback, flush the lookahead queue and
        // re-enqueue from the current position so the new settings become audible immediately.
        // Without this, chunks pre-synthesized at TTS_LOOKAHEAD_CHUNKS depth ago continue in
        // the old voice for several seconds and the user perceives the change as not taking
        // effect at all. seekToPosition() does the flush + rebuild atomically under [mutex],
        // so concurrent play/pause callers still serialize correctly.
        val voiceChanged = previous.voiceId != settings.voiceId
        val rateChanged = previous.speechRate != settings.speechRate
        val pitchChanged = previous.pitch != settings.pitch
        if ((voiceChanged || rateChanged || pitchChanged) && _state.value is TtsState.Playing) {
            engineScope.launch {
                try { seekToPosition(currentPosition) } catch (e: Exception) {
                    Logger.e(TAG, "Mid-playback settings restart failed", e)
                }
            }
        }
    }

    override fun getAvailableVoices(): List<TtsVoice> = _voices.value

    /**
     * Force the system TTS service to bind so [voices] populates without requiring a play()
     * call. Used by Options to fill the voice picker dropdown immediately on screen entry.
     * Holds the engine mutex like every other lifecycle entry point so a concurrent play()
     * cannot race the listener init that writes [_voices]. Idempotent — [initIfNeeded] checks
     * [isInitialized] and short-circuits on subsequent calls.
     */
    suspend fun ensureInitialized() = mutex.withLock {
        initIfNeeded()
    }

    override fun getCurrentPosition(): Int = currentPosition

    override suspend fun loadWords(words: List<RsvpWord>) = mutex.withLock {
        cancelPlaybackAndResetQueue()
        this.words = words
        this.currentPosition = 0
        this.utterances = emptyList()
        this.playingChunkIndex = 0
        this.enqueuedThroughIndex = -1
        chunksThatSawRange.clear()
        _currentWord.value = words.firstOrNull()
        _progress.value = 0f
        _state.value = TtsState.Idle
    }

    override suspend fun play(): Unit = mutex.withLock {
        if (words.isEmpty()) return@withLock
        // Idempotence guard: a second play while already Playing (BT media key, stale
        // notification tap) must not rebuild the utterance queue mid-flight. Without this,
        // the rebuild resets playingChunkIndex/enqueuedThroughIndex WITHOUT bumping the
        // session, so in-flight utterances' onRangeStart callbacks resolve their old chunk
        // indices against the NEW list — mapping char offsets to arbitrary wrong words —
        // while QUEUE_ADD appends duplicate chunks behind the still-queued old ones.
        if (_state.value is TtsState.Playing) return@withLock
        // Ensure the TTS service is connected before we enqueue anything.
        initIfNeeded()

        if (!audioFocusHelper.request(::onAudioFocusLoss)) {
            _state.value = TtsState.Error("Could not acquire audio focus")
            return@withLock
        }

        isPausing.set(false)

        // Pressing Play after the last utterance finished restarts from word 0 — matches
        // RSVP engine's restart-from-end behavior so the two modes feel the same.
        if (currentPosition >= words.size) {
            currentPosition = 0
        }

        // TtsChunker walks the entire word list; on a novel-sized document it can take
        // hundreds of ms. Move the CPU-bound chunking to Default so play() doesn't block
        // the dispatcher of whichever caller (PageViewActivity / ReadingFragment via
        // lifecycleScope, both Main by default) invoked it. Mutex stays held across the
        // dispatcher hop so concurrent play/pause/seek callers still serialize correctly.
        withContext(Dispatchers.Default) { rebuildUtteranceQueue() }
        _state.value = TtsState.Playing
        topUpQueueWithRecovery()
    }

    override suspend fun pause() = mutex.withLock {
        if (_state.value !is TtsState.Playing) return@withLock
        isPausing.set(true)
        // Bump session BEFORE tts.stop() so any late onDone/onRangeStart from the in-flight
        // utterance OR pre-enqueued lookahead chunk (queued to the TTS callback thread before
        // stop() takes effect) fails the matchesSession guard. Without this, a stale onDone
        // arriving after play() resumes would refill the queue past the new playback head.
        sessionGuid = UUID.randomUUID().toString()
        tts?.stop()
        audioFocusHelper.release()
        _state.value = TtsState.Paused
        // currentPosition is updated incrementally by onRangeStart; whatever was last set
        // is the pause position. No extra work needed here.
    }

    override suspend fun stop() = mutex.withLock {
        isPausing.set(true)
        // Bump session before tts.stop() for the same reason as pause(): late callbacks
        // from the cancelled utterance must not be able to mutate currentPosition or
        // the chunk cursors.
        sessionGuid = UUID.randomUUID().toString()
        tts?.stop()
        audioFocusHelper.release()
        currentPosition = 0
        playingChunkIndex = 0
        enqueuedThroughIndex = -1
        chunksThatSawRange.clear()
        utterances = emptyList()
        _currentWord.value = words.firstOrNull()
        _progress.value = 0f
        _state.value = TtsState.Idle
    }

    override suspend fun seekToPosition(position: Int) = mutex.withLock {
        if (words.isEmpty()) return@withLock
        val clamped = position.coerceIn(0, words.size - 1)
        val wasPlaying = _state.value is TtsState.Playing

        isPausing.set(true)
        // Session bump before tts.stop() so stale callbacks from the cancelled utterance
        // (or any pre-enqueued lookahead chunk) can't land on the new position via
        // onRangeStart/onDone.
        sessionGuid = UUID.randomUUID().toString()
        tts?.stop()
        currentPosition = clamped
        playingChunkIndex = 0
        enqueuedThroughIndex = -1
        chunksThatSawRange.clear()
        _currentWord.value = words[clamped]
        _progress.value = clamped.toFloat() / words.size

        if (wasPlaying) {
            isPausing.set(false)
            // Same dispatcher hop as play(): on a long doc chunking from a fresh seek
            // position can be heavy enough to block the caller's dispatcher.
            withContext(Dispatchers.Default) { rebuildUtteranceQueue() }
            topUpQueueWithRecovery()
        } else {
            utterances = emptyList()
        }
    }

    override suspend fun shutdown() = mutex.withLock {
        isPausing.set(true)
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        audioFocusHelper.release()
        _state.value = TtsState.Idle
    }

    // --- internals ---

    private fun rebuildUtteranceQueue() {
        utterances = TtsChunker.chunk(
            words = words,
            startPosition = currentPosition,
            maxWordsPerChunk = Constants.TTS_CHUNK_WORDS,
            maxCharsPerChunk = Constants.TTS_MAX_UTTERANCE_CHARS
        )
        playingChunkIndex = 0
        enqueuedThroughIndex = -1
        chunksThatSawRange.clear()
    }

    private fun cancelPlaybackAndResetQueue() {
        isPausing.set(true)
        // Session bump must precede tts.stop() so callbacks queued before stop() but
        // dispatched after cannot match the new session.
        sessionGuid = UUID.randomUUID().toString()
        tts?.stop()
        audioFocusHelper.release()
        utterances = emptyList()
        playingChunkIndex = 0
        enqueuedThroughIndex = -1
        chunksThatSawRange.clear()
    }

    private suspend fun initIfNeeded() = recoveryMutex.withLock {
        initIfNeededLocked()
    }

    /**
     * Internal init body. Caller must hold [recoveryMutex] — both this and
     * [recoverFromDisconnect] create [TextToSpeech] instances, so they must serialize or
     * a concurrent play()/seek() can race a disconnect-recovery and produce two parallel
     * binds, leaking one of the resulting instances.
     */
    private suspend fun initIfNeededLocked() {
        if (isInitialized) return
        _state.value = TtsState.Initializing
        // Force init onto the IO dispatcher — TextToSpeech() constructor triggers a bind to
        // the system TTS service which can block for hundreds of ms on first launch.
        withContext(Dispatchers.IO) {
            awaitInit()
        }
    }

    private suspend fun awaitInit() {
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            val listener = TextToSpeech.OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@OnInitListener
                    engine.language = Locale.getDefault()
                    engine.setOnUtteranceProgressListener(utteranceListener)
                    _voices.value = snapshotVoices(engine)
                    applyVoiceRateAndPitch()
                    isInitialized = true
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    // On many Android builds (Google TTS in particular), [engine.voices] returns
                    // an incomplete or empty set during the synchronous OnInit callback because
                    // the engine populates its internal voice list asynchronously after the
                    // service binds. The Options voice picker then renders with just the
                    // "Default" sentinel and the user has no way to pick a specific voice.
                    // Retry the snapshot a few times after init to catch the late-arriving list.
                    if (_voices.value.isEmpty()) scheduleVoicesRetry()
                } else {
                    if (cont.isActive) {
                        cont.resumeWith(Result.failure(IllegalStateException("TTS init failed: $status")))
                    }
                }
            }
            tts = TextToSpeech(context, listener)
        }
    }

    /**
     * Re-snapshot the engine's voice list a few times after init in case the engine populated
     * it asynchronously. Stops as soon as a non-empty snapshot lands or the retry budget is
     * exhausted; once a successful snapshot is observed, future settings changes will use it
     * via [applyVoiceRateAndPitch]'s lookup. Idempotent — only schedules when [_voices] is
     * still empty at scheduling time.
     */
    private fun scheduleVoicesRetry() {
        engineScope.launch {
            for (attempt in 1..Constants.TTS_VOICES_RETRY_ATTEMPTS) {
                delay(Constants.TTS_VOICES_RETRY_DELAY_MS)
                val engine = tts ?: return@launch
                val snapshot = snapshotVoices(engine)
                if (snapshot.isNotEmpty()) {
                    _voices.value = snapshot
                    // Re-apply voice/rate/pitch now that the voice ID lookup can succeed —
                    // the persisted voiceId may have been ignored on first apply because the
                    // voice list was empty.
                    applyVoiceRateAndPitch()
                    return@launch
                }
            }
            Logger.w(TAG, "Voice list still empty after ${Constants.TTS_VOICES_RETRY_ATTEMPTS} retries")
        }
    }

    private fun snapshotVoices(engine: TextToSpeech): List<TtsVoice> {
        val raw: Set<Voice> = try { engine.voices ?: emptySet() } catch (e: Exception) {
            Logger.w(TAG, "Could not enumerate voices: ${e.message}")
            return emptyList()
        }
        return raw.map { v ->
            TtsVoice(
                id = v.name,
                displayName = formatVoiceDisplayName(v),
                localeTag = v.locale.toLanguageTag(),
                isNetworkRequired = v.isNetworkConnectionRequired,
                qualityTier = v.quality
            )
        }.sortedWith(compareBy({ it.localeTag }, { it.displayName }))
    }

    private fun formatVoiceDisplayName(v: Voice): String {
        val base = v.name.replace('_', ' ').replace('-', ' ')
        val locale = v.locale.displayName
        return "$base ($locale)"
    }

    private fun applyVoiceRateAndPitch() {
        val engine = tts ?: return
        engine.setSpeechRate(settings.speechRate)
        engine.setPitch(settings.pitch)
        val voiceId = settings.voiceId
        if (voiceId != null) {
            val target = try { engine.voices } catch (e: Exception) {
                Logger.w(TAG, "Could not enumerate voices for voice apply: ${e.message}")
                null
            }?.firstOrNull { it.name == voiceId }
            if (target != null) {
                val result = engine.setVoice(target)
                if (result == TextToSpeech.ERROR) {
                    Logger.w(TAG, "setVoice($voiceId) returned ERROR")
                }
            } else {
                Logger.w(TAG, "Saved voiceId '$voiceId' not found in engine voice list — keeping current engine voice")
            }
        } else {
            // Picking "Default" in the picker writes voiceId=null. Without this branch the
            // engine.voice setter was never called, so the engine kept using whichever voice
            // was last applied — picking Default appeared to have no effect.
            val default = try { engine.defaultVoice } catch (e: Exception) {
                Logger.w(TAG, "Could not read defaultVoice: ${e.message}")
                null
            }
            if (default != null) engine.setVoice(default)
        }
    }

    /**
     * Pass a single chunk to the system TTS service. Idempotent against the cursor —
     * callers must guard against double-enqueueing via [enqueuedThroughIndex] (handled by
     * [ensureQueueCovers]).
     *
     * Returns true when the system TTS service accepted the utterance, false when
     * speak() returned [TextToSpeech.ERROR] — which happens when the bound TTS service
     * has died (e.g. the engine package was REPLACED while we held the binding).
     * Callers use the false return to drive [recoverFromDisconnect].
     */
    private fun enqueueChunkAt(index: Int): Boolean {
        val engine = tts ?: return false
        val u = utterances.getOrNull(index) ?: return false
        val uttId = "${sessionGuid}_${index}"
        val params = Bundle()
        val result = engine.speak(u.text, TextToSpeech.QUEUE_ADD, params, uttId)
        if (result == TextToSpeech.ERROR) return false
        if (index > enqueuedThroughIndex) enqueuedThroughIndex = index
        return true
    }

    /**
     * Saturate the system TTS queue with [TTS_LOOKAHEAD_CHUNKS] chunks ahead of the
     * chunk currently producing audio. Pre-queueing is what eliminates inter-chunk audio
     * gaps: while chunk N plays, chunks N+1..N+LOOKAHEAD are already queued and the TTS
     * engine pre-synthesizes them, so the transition between utterances is seamless.
     *
     * Called from play() / seekToPosition() (initial fill) and from onDone (refill after
     * a chunk drains). [ensureQueueCovers] anchors against the chunk that just finished
     * in onDone (so we look ahead from the *next* chunk, not the one already past), and
     * against [playingChunkIndex] elsewhere.
     */
    private suspend fun topUpQueueWithRecovery() = ensureQueueCoversWithRecovery(playingChunkIndex)

    /**
     * Wraps [ensureQueueCovers] with single-shot recovery: if the first pass detects a
     * disconnected TTS service (any speak() returns ERROR), tear the dead instance down,
     * rebind, and re-enqueue from scratch. The retry is attempted exactly once because
     * if the rebind also fails, hammering Android's bind path can pin the TTS service
     * in a restart loop on the user's device — better to surface Error and let the user
     * retry.
     */
    private suspend fun ensureQueueCoversWithRecovery(playbackIndex: Int) {
        if (ensureQueueCovers(playbackIndex)) return
        Logger.w(TAG, "TTS speak failed (engine disconnected); attempting recovery")
        val recovered = recoverFromDisconnect()
        if (!recovered) {
            _state.value = TtsState.Error("TTS engine disconnected and could not recover")
            audioFocusHelper.release()
            return
        }
        if (!ensureQueueCovers(playbackIndex)) {
            Logger.e(TAG, "TTS speak failed even after reconnect; giving up")
            _state.value = TtsState.Error("TTS engine disconnected and could not recover")
            audioFocusHelper.release()
        }
    }

    /**
     * Returns true when every required chunk was accepted by the TTS service, false
     * the moment any speak() returns ERROR. Stops on first failure rather than
     * thrashing — the caller will recover and retry.
     */
    private fun ensureQueueCovers(playbackIndex: Int): Boolean {
        if (utterances.isEmpty()) return true
        val target = (playbackIndex + Constants.TTS_LOOKAHEAD_CHUNKS).coerceAtMost(utterances.size - 1)
        var next = enqueuedThroughIndex + 1
        while (next <= target) {
            if (!enqueueChunkAt(next)) return false
            next++
        }
        return true
    }

    /**
     * Tear the dead [TextToSpeech] instance down and rebind a fresh one. After a
     * successful reconnect, also clears [enqueuedThroughIndex] / [chunksThatSawRange]
     * and bumps [sessionGuid] so any stale callbacks the dead binder emits during
     * teardown can't slip past [matchesSession] and mutate the new session's cursors.
     *
     * Concurrency: guarded by [recoveryMutex] so the play() path and the onDone refill
     * path can't both rebind in parallel. Re-entrant safe — if a second caller wins the
     * lock after the first recovered, the early [isInitialized] check returns true
     * without recreating the instance.
     *
     * Returns true on successful rebind, false if init throws (no engine on device,
     * permission revoked, etc).
     */
    private suspend fun recoverFromDisconnect(): Boolean = recoveryMutex.withLock {
        if (isInitialized) return@withLock true
        isInitialized = false
        // Session bump must happen BEFORE we discard the [tts] reference — any in-flight
        // onDone/onRangeStart from the dying binder would otherwise pass matchesSession
        // against the old GUID and corrupt new-session cursors before the new instance
        // takes over.
        sessionGuid = UUID.randomUUID().toString()
        try { tts?.shutdown() } catch (e: Exception) {
            Logger.w(TAG, "Shutdown of dead TTS instance failed: ${e.message}")
        }
        tts = null
        try {
            // [recoveryMutex] is already held; call the locked variant so we don't try
            // to re-enter the same mutex (Mutex is non-reentrant).
            initIfNeededLocked()
            // Lookahead bookkeeping is keyed to a specific [tts] instance — the new
            // service has nothing queued yet, so reset the high-watermark to force
            // [ensureQueueCovers] to re-enqueue every chunk.
            enqueuedThroughIndex = -1
            chunksThatSawRange.clear()
            true
        } catch (e: Exception) {
            Logger.e(TAG, "TTS reconnect failed", e)
            false
        }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            if (!matchesSession(utteranceId)) return
            val started = chunkIndexOf(utteranceId) ?: return
            // Advance the playing-chunk cursor to whichever chunk the engine is now
            // speaking. With lookahead queued, this is how subsequent onRangeStart
            // callbacks find the right TtsUtterance for char-offset mapping.
            if (started > playingChunkIndex) playingChunkIndex = started
            engineScope.launch {
                if (!matchesSession(utteranceId)) return@launch
                _state.value = TtsState.Playing
            }
        }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            if (!matchesSession(utteranceId)) return
            val owningChunk = chunkIndexOf(utteranceId) ?: return
            // Drop late callbacks from chunks the engine has already finished. With
            // lookahead, onDone(N) and onStart(N+1) cluster on the callback thread and a
            // trailing onRangeStart for chunk N can arrive after playingChunkIndex
            // advanced to N+1. Resolving start against N's wordRanges yields a valid
            // word in N — but it's earlier than what N+1's onRangeStart has already
            // emitted, producing the visible "cycle back to a previous word for a split
            // second" at every chunk boundary (most pronounced at higher speech rates,
            // where chunk transitions crowd together).
            if (owningChunk < playingChunkIndex) return
            chunksThatSawRange.add(owningChunk)
            val utterance = utterances.getOrNull(owningChunk) ?: return
            val pos = utterance.positionForCharOffset(start) ?: return
            engineScope.launch {
                if (!matchesSession(utteranceId)) return@launch
                currentPosition = pos
                words.getOrNull(pos)?.let { _currentWord.value = it }
                if (words.isNotEmpty()) {
                    _progress.value = pos.toFloat() / words.size
                }
            }
        }

        override fun onDone(utteranceId: String) {
            if (!matchesSession(utteranceId)) return
            val finishedChunk = chunkIndexOf(utteranceId) ?: return

            val voiceId = settings.voiceId ?: "default"
            val sawRange = chunksThatSawRange.remove(finishedChunk)
            if (!sawRange) {
                if (voiceRangeSupportMemo[voiceId] != true) {
                    voiceRangeSupportMemo[voiceId] = false
                }
                advancePositionProportionally(finishedChunk, utteranceId)
            } else {
                voiceRangeSupportMemo[voiceId] = true
            }

            // Re-check isPausing and session INSIDE the launch — between the outer check
            // and the launch body running on Main, pause/seek may have fired. Without the
            // inner check we'd auto-advance one extra utterance past the pause.
            engineScope.launch {
                if (isPausing.get() || !matchesSession(utteranceId)) return@launch
                if (finishedChunk >= utterances.size - 1) {
                    currentPosition = words.size
                    _progress.value = 1f
                    _state.value = TtsState.Finished
                    audioFocusHelper.release()
                } else {
                    // Refill the queue. ensureQueueCovers anchors on the chunk that will
                    // play NEXT (finishedChunk + 1), not on the one that just drained, so
                    // the lookahead window stays one full chunk past playback.
                    ensureQueueCoversWithRecovery(finishedChunk + 1)
                }
            }
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            // Guarded by isPausing — a pause/seek already set the caller-desired state.
        }

        // Override kept because pre-API-21 paths still call the legacy single-arg onError.
        // @Deprecated marks our own surface; @Suppress silences Kotlin 2.x OVERRIDE_DEPRECATION
        // (the diagnostic fires regardless of @Deprecated being present).
        @Deprecated("Use onError(utteranceId, errorCode)", ReplaceWith("onError(utteranceId, TextToSpeech.ERROR)"))
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String) {
            onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            if (!matchesSession(utteranceId)) return
            if (isPausing.get()) return
            engineScope.launch {
                if (!matchesSession(utteranceId)) return@launch
                _state.value = TtsState.Error("TTS error $errorCode")
                audioFocusHelper.release()
            }
        }
    }

    private fun matchesSession(utteranceId: String): Boolean =
        utteranceId.startsWith("${sessionGuid}_")

    private fun chunkIndexOf(utteranceId: String): Int? =
        utteranceId.removePrefix("${sessionGuid}_").toIntOrNull()

    /**
     * Fallback when onRangeStart never fired for the just-completed utterance. Jumps the
     * position cursor forward by the utterance's full word count — coarse but keeps
     * progress moving. The chunk index is passed in (rather than read from a global cursor)
     * because lookahead means the playing-chunk cursor may already have moved on by the
     * time onDone fires for an earlier chunk.
     */
    private fun advancePositionProportionally(finishedChunkIndex: Int, utteranceId: String) {
        val utterance = utterances.getOrNull(finishedChunkIndex) ?: return
        val lastIndex = utterance.globalWordIndices.lastOrNull() ?: return
        engineScope.launch {
            if (!matchesSession(utteranceId)) return@launch
            currentPosition = (lastIndex + 1).coerceAtMost(words.size)
            words.getOrNull(lastIndex)?.let { _currentWord.value = it }
            if (words.isNotEmpty()) {
                _progress.value = currentPosition.toFloat() / words.size
            }
        }
    }

    private fun onAudioFocusLoss(loss: AudioFocusHelper.FocusLoss) {
        // Any loss pauses — v1 has no auto-resume so Transient and Permanent behave the same
        // from the engine's perspective. The helper handles re-request on the next play().
        engineScope.launch {
            if (_state.value is TtsState.Playing) {
                pause()
            }
        }
    }

    companion object {
        private const val TAG = "NativeTtsEngine"
    }
}

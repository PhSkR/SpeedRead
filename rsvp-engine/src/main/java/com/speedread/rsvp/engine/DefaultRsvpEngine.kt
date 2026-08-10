package com.speedread.rsvp.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultRsvpEngine(
    private val textProcessor: TextProcessor = DefaultTextProcessor(),
    private val timingCalculator: TimingCalculator = DefaultTimingCalculator(),
    // CPU-bound text processing (tokenization, raw-token counting, chunk-size reprocess)
    // hops onto this dispatcher so the Main-thread engineScope doesn't block on multi-
    // hundred-KB documents. Default is Dispatchers.Default for production; tests inject
    // a TestDispatcher so all engine work runs on a single TestScheduler and
    // advanceUntilIdle() drains it deterministically (no real-thread races).
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default
) : RsvpEngine {

    // Engine-owned scope. Dispatchers.Main.immediate keeps same-thread emissions
    // synchronous (avoids re-entrancy races) and SupervisorJob prevents one failing
    // child from taking down siblings. Lives for the engine singleton's lifetime.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Serializes all state-mutating operations (loadText, play/pause/stop, seek,
    // updateSettings reprocess). Without this, concurrent callers could interleave
    // `words` reassignment with an active playback loop or double-launch playback.
    private val mutex = Mutex()

    private val _currentWord = MutableStateFlow<RsvpWord?>(null)
    private val _progress = MutableStateFlow<Float>(0f)
    private val _state = MutableStateFlow<RsvpState>(RsvpState.Idle)

    private var words: List<RsvpWord> = emptyList()
    private var cachedSingleWordTokens: List<RsvpWord> = emptyList()
    private var currentPosition = 0
    private var settings = RsvpSettings()
    private var playbackJob: Job? = null
    private var currentText: String = ""
    private var lastLoadTruncated: Boolean = false

    override suspend fun loadText(text: String) = mutex.withLock {
        cancelPlaybackAndJoin()
        currentText = text
        val (singleWords, finalWords, truncated) = withContext(processingDispatcher) {
            val single = textProcessor.processText(text)
            val final = if (settings.chunkSize > 1) {
                textProcessor.rechunk(single, settings.chunkSize)
            } else {
                single
            }
            Triple(single, final, rawTokenCount(text) > EngineConstants.MAX_TOKENS)
        }
        cachedSingleWordTokens = singleWords
        words = finalWords
        lastLoadTruncated = truncated
        currentPosition = 0
        _currentWord.value = null
        _progress.value = 0f
        _state.value = RsvpState.Idle
    }

    override suspend fun loadTextWithProgress(text: String, onProgress: (Float) -> Unit) = mutex.withLock {
        cancelPlaybackAndJoin()
        currentText = text

        onProgress(EngineConstants.PROGRESS_STAGE_START)
        val (singleWords, finalWords, truncated) = withContext(processingDispatcher) {
            val single = textProcessor.processText(text)
            val final = if (settings.chunkSize > 1) {
                textProcessor.rechunk(single, settings.chunkSize)
            } else {
                single
            }
            Triple(single, final, rawTokenCount(text) > EngineConstants.MAX_TOKENS)
        }
        cachedSingleWordTokens = singleWords
        words = finalWords
        onProgress(EngineConstants.PROGRESS_STAGE_TOKENIZED)
        lastLoadTruncated = truncated
        currentPosition = 0
        _currentWord.value = null
        _progress.value = 0f
        _state.value = RsvpState.Idle
        onProgress(EngineConstants.PROGRESS_STAGE_DONE)
    }

    override suspend fun loadPreTokenized(singleWordTokens: List<RsvpWord>) = mutex.withLock {
        cancelPlaybackAndJoin()
        currentText = ""
        cachedSingleWordTokens = singleWordTokens
        words = if (settings.chunkSize > 1) {
            withContext(processingDispatcher) {
                textProcessor.rechunk(singleWordTokens, settings.chunkSize)
            }
        } else {
            singleWordTokens
        }
        lastLoadTruncated = singleWordTokens.size >= EngineConstants.MAX_TOKENS
        currentPosition = 0
        _currentWord.value = null
        _progress.value = 0f
        _state.value = RsvpState.Idle
    }

    override suspend fun play(): Unit = mutex.withLock {
        if (words.isEmpty()) return@withLock

        cancelPlaybackAndJoin()
        // Pressing Play after reaching the end restarts from word 0. Without this,
        // the playback loop below would exit immediately because currentPosition
        // is still past the list, giving the user a silent no-op.
        if (currentPosition >= words.size) {
            currentPosition = 0
            _progress.value = 0f
        }
        _state.value = RsvpState.Playing
        playbackJob = engineScope.launch { runPlaybackLoop() }
    }

    override suspend fun pause() = mutex.withLock {
        // Paused is only legal as a suspension of active playback. Pausing from Idle /
        // Paused / Finished must no-op — otherwise the engine ends up in Paused with no
        // playback job to resume, confusing UI state observers that map state → buttons.
        if (_state.value != RsvpState.Playing) return@withLock
        _state.value = RsvpState.Paused
        cancelPlaybackAndJoin()
    }

    override suspend fun stop() = mutex.withLock {
        _state.value = RsvpState.Idle
        cancelPlaybackAndJoin()
        currentPosition = 0
        _progress.value = 0f
    }

    override suspend fun seekToPosition(position: Int) = mutex.withLock {
        if (words.isEmpty()) return@withLock
        // Clamp rather than no-op on out-of-range positions. TTS reports words.size as its
        // position after a natural finish; silently ignoring that seek left the engine parked
        // at whatever position was last synced (often the mode-toggle point), so a toggle back
        // to RSVP after listening to the end resumed from a stale position instead of the end.
        val clampedPosition = position.coerceIn(0, words.size - 1)
        currentPosition = clampedPosition
        val progress = (clampedPosition.toFloat() / words.size).coerceIn(0f, 1f)
        _progress.value = progress

        // Emit the seeked word unless we're actively playing (the playback loop owns
        // _currentWord emissions then). Previously this only fired on Paused, which
        // meant an Idle state — such as right after loadText on app startup — never
        // pushed a word to the display, leaving the reader blank until Play was pressed.
        if (_state.value != RsvpState.Playing) {
            _currentWord.value = words[clampedPosition]
        }
    }

    override fun observeCurrentWord(): Flow<RsvpWord?> = _currentWord.asStateFlow()
    override fun observeProgress(): Flow<Float> = _progress.asStateFlow()
    override fun observeState(): Flow<RsvpState> = _state.asStateFlow()

    override fun updateSettings(settings: RsvpSettings) {
        val previousChunkSize = this.settings.chunkSize
        this.settings = settings

        // If chunk size changed and we have text loaded, reprocess atomically.
        // Everything (stop, reprocess, rescale position, optional restart) runs under
        // the same mutex that guards play/pause/seek, so callers can't double-start
        // playback or read words mid-reassignment.
        if (previousChunkSize != settings.chunkSize && cachedSingleWordTokens.isNotEmpty()) {
            engineScope.launch {
                mutex.withLock {
                    val wasPlaying = _state.value == RsvpState.Playing
                    cancelPlaybackAndJoin()
                    val oldSize = words.size
                    words = withContext(processingDispatcher) {
                        textProcessor.rechunk(cachedSingleWordTokens, settings.chunkSize)
                    }
                    currentPosition = if (words.isEmpty() || oldSize == 0) {
                        0
                    } else {
                        ((currentPosition.toDouble() / oldSize) * words.size)
                            .toInt()
                            .coerceIn(0, words.size - 1)
                    }
                    if (wasPlaying) {
                        _state.value = RsvpState.Playing
                        playbackJob = engineScope.launch { runPlaybackLoop() }
                    }
                }
            }
        }
    }

    override fun getWords(): List<RsvpWord> = words

    override fun getSingleWordTokens(): List<RsvpWord> = cachedSingleWordTokens

    override fun getTotalWords(): Int = words.size

    override fun getCurrentPosition(): Int = currentPosition

    override fun wasLastLoadTruncated(): Boolean = lastLoadTruncated

    private suspend fun cancelPlaybackAndJoin() {
        playbackJob?.cancelAndJoin()
        playbackJob = null
    }

    private suspend fun runPlaybackLoop() {
        try {
            while (currentPosition < words.size && _state.value == RsvpState.Playing) {
                val word = words[currentPosition]
                _currentWord.value = word

                val progress = if (words.isNotEmpty()) {
                    (currentPosition.toFloat() / words.size).coerceIn(0f, 1f)
                } else 0f
                _progress.value = progress

                val delayMs = timingCalculator.calculateDelay(word, settings)
                val positionBeforeDelay = currentPosition
                delay(delayMs)

                // A seek that landed while this word was on screen already repositioned
                // currentPosition; incrementing past it would skip the sought word entirely
                // (the seek path suppresses its own emission while Playing and relies on
                // this loop to display the target).
                if (currentPosition == positionBeforeDelay) {
                    currentPosition++
                }
            }

            if (currentPosition >= words.size) {
                _progress.value = 1f
                _state.value = RsvpState.Finished
            }
        } catch (e: CancellationException) {
            // Expected when cancelled
        } catch (e: Exception) {
            _state.value = RsvpState.Paused
        }
    }

    private fun rawTokenCount(text: String): Int {
        var count = 0
        var inWord = false
        for (ch in text) {
            if (count > EngineConstants.MAX_TOKENS) return count
            // Mirror TextProcessor.isWordSeparator so the raw token count stays aligned with the
            // tokenizer's actual output. Without this, sources with NEL / ZWSP / BOM noise would
            // produce a smaller token count here than the tokenizer eventually emits, which throws
            // off the engine's progress-percent / chunk-size clamping math.
            if (ch.isWordSeparator()) {
                if (inWord) {
                    count++
                    inWord = false
                }
            } else {
                inWord = true
            }
        }
        if (inWord) count++
        return count
    }
}

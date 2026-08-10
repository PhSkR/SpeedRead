// Piper / VITS neural TTS backend, driven by Sherpa-ONNX. Linked at build time via the
// fileTree("*.aar") glob in app/build.gradle.kts (the AAR lives in app/libs/). The
// `com.k2fsa.sherpa.onnx.*` imports below resolve only when that AAR is present.
//
// Voice discovery is decoupled from the runtime: TtsModelRegistry scans the drop-in folder
// at /Android/data/com.speedread.rsvp/files/tts_models/<voice>/ regardless of whether this
// engine is wired up, so the same registry works for both the stub (pre-1.14.41) and the
// real backend. isAvailable() returns true once at least one valid voice folder is present;
// TtsEngineProvider's resolve() silently downgrades NEURAL → SYSTEM when this returns false.
//
// See app/libs/README-NEURAL-TTS.md for the recovery procedure if a clean checkout is
// missing the AAR (gradle sync will fail with "Unresolved reference: com.k2fsa.sherpa.onnx").

package com.speedread.rsvp.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.speedread.rsvp.Constants
import com.speedread.rsvp.engine.RsvpWord
import com.speedread.rsvp.engine.TtsChunker
import com.speedread.rsvp.engine.TtsUtterance
import com.speedread.rsvp.engine.WordTimingPlanner
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NeuralTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: TtsModelRegistry,
    private val audioFocusHelper: AudioFocusHelper
) : TtsEngine {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    private val _currentWord = MutableStateFlow<RsvpWord?>(null)
    private val _progress = MutableStateFlow(0f)

    private val isPausing = AtomicBoolean(false)
    @Volatile private var sessionGuid: String = UUID.randomUUID().toString()

    private var offlineTts: OfflineTts? = null
    private var loadedVoiceId: String? = null
    private var audioTrack: AudioTrack? = null

    private var words: List<RsvpWord> = emptyList()
    private var currentPosition: Int = 0
    private var utterances: List<TtsUtterance> = emptyList()
    private var chunkIndex: Int = 0
    private var settings: TtsSettings = TtsSettings()
    private var synthesisJob: Job? = null

    override fun backendId(): TtsBackend = TtsBackend.NEURAL

    override fun isAvailable(): Boolean = registry.voices.value.isNotEmpty()

    override fun observeCurrentWord(): Flow<RsvpWord?> = _currentWord.asStateFlow()
    override fun observeProgress(): Flow<Float> = _progress.asStateFlow()
    override fun observeState(): Flow<TtsState> = _state.asStateFlow()

    override fun updateSettings(settings: TtsSettings) {
        val voiceChanged = this.settings.voiceId != settings.voiceId
        this.settings = settings
        if (!voiceChanged) return
        // Serialize the model swap behind the engine mutex. Releasing the ONNX model
        // synchronously here (the old behavior) freed the native object while an in-flight
        // generate() on Dispatchers.IO could still be using it (native crash), and left the
        // synthesis loop spinning through the remaining chunks against a null model —
        // instantly emitting Finished. Interrupt like pause(), swap, then resume in place.
        engineScope.launch {
            mutex.withLock {
                val wasPlaying = _state.value is TtsState.Playing
                isPausing.set(true)
                sessionGuid = UUID.randomUUID().toString()
                // Joins the synthesis job; any in-flight generate() completes on the OLD
                // model instance BEFORE we release it below.
                cancelSynthesis()
                audioTrack?.pause()
                releaseOfflineTts()

                if (!wasPlaying) return@withLock

                isPausing.set(false)
                val voice = resolveVoice()
                if (voice == null) {
                    _state.value = TtsState.BackendUnavailable
                    audioFocusHelper.release()
                    return@withLock
                }
                try {
                    ensureTtsLoaded(voice)
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to load Neural model after voice change", e)
                    _state.value = TtsState.Error("Failed to load model: ${e.message}")
                    audioFocusHelper.release()
                    return@withLock
                }
                rebuildUtteranceQueue()
                startSynthesisLoop(voice)
            }
        }
    }

    override fun getAvailableVoices(): List<TtsVoice> = registry.voices.value.map { it.toTtsVoice() }

    override fun getCurrentPosition(): Int = currentPosition

    override suspend fun loadWords(words: List<RsvpWord>) = mutex.withLock {
        cancelSynthesis()
        this.words = words
        this.currentPosition = 0
        this.utterances = emptyList()
        this.chunkIndex = 0
        _currentWord.value = words.firstOrNull()
        _progress.value = 0f
        _state.value = TtsState.Idle
    }

    override suspend fun play(): Unit = mutex.withLock {
        if (words.isEmpty()) return@withLock
        // Idempotence guard: a second play while already Playing (BT media key, stale
        // notification tap) must not rebuild the utterance queue under the running
        // synthesis loop — that overwrites synthesisJob without cancelling it, leaving two
        // loops sharing chunkIndex/audioTrack (overlapping audio, skipped chunks).
        if (_state.value is TtsState.Playing) return@withLock

        val voice = resolveVoice() ?: run {
            _state.value = TtsState.BackendUnavailable
            return@withLock
        }

        if (!audioFocusHelper.request(::onAudioFocusLoss)) {
            _state.value = TtsState.Error("Could not acquire audio focus")
            return@withLock
        }

        isPausing.set(false)

        if (currentPosition >= words.size) currentPosition = 0

        try {
            ensureTtsLoaded(voice)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load Neural model", e)
            _state.value = TtsState.Error("Failed to load model: ${e.message}")
            audioFocusHelper.release()
            return@withLock
        }

        rebuildUtteranceQueue()
        _state.value = TtsState.Playing
        startSynthesisLoop(voice)
    }

    override suspend fun pause() = mutex.withLock {
        if (_state.value !is TtsState.Playing) return@withLock
        isPausing.set(true)
        sessionGuid = UUID.randomUUID().toString()
        cancelSynthesis()
        audioTrack?.pause()
        audioFocusHelper.release()
        _state.value = TtsState.Paused
    }

    override suspend fun stop() = mutex.withLock {
        isPausing.set(true)
        sessionGuid = UUID.randomUUID().toString()
        cancelSynthesis()
        stopAndReleaseAudioTrack()
        audioFocusHelper.release()
        currentPosition = 0
        chunkIndex = 0
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
        sessionGuid = UUID.randomUUID().toString()
        cancelSynthesis()
        stopAndReleaseAudioTrack()
        currentPosition = clamped
        chunkIndex = 0
        _currentWord.value = words[clamped]
        _progress.value = clamped.toFloat() / words.size

        if (wasPlaying) {
            isPausing.set(false)
            val voice = resolveVoice()
            if (voice != null) {
                rebuildUtteranceQueue()
                startSynthesisLoop(voice)
            } else {
                _state.value = TtsState.BackendUnavailable
            }
        } else {
            utterances = emptyList()
        }
    }

    override suspend fun shutdown() = mutex.withLock {
        isPausing.set(true)
        sessionGuid = UUID.randomUUID().toString()
        cancelSynthesis()
        stopAndReleaseAudioTrack()
        releaseOfflineTts()
        audioFocusHelper.release()
        _state.value = TtsState.Idle
    }

    private fun rebuildUtteranceQueue() {
        utterances = TtsChunker.chunk(
            words = words,
            startPosition = currentPosition,
            maxWordsPerChunk = Constants.TTS_CHUNK_WORDS,
            maxCharsPerChunk = Constants.TTS_MAX_UTTERANCE_CHARS
        )
        chunkIndex = 0
    }

    private fun resolveVoice(): NeuralVoice? {
        val available = registry.voices.value
        if (available.isEmpty()) return null
        val preferred = settings.voiceId?.let { id -> available.firstOrNull { it.id == id } }
        return preferred ?: available.first()
    }

    private suspend fun ensureTtsLoaded(voice: NeuralVoice) {
        if (offlineTts != null && loadedVoiceId == voice.id) return
        releaseOfflineTts()
        withContext(Dispatchers.IO) {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = voice.modelPath,
                        tokens = voice.tokensPath,
                        dataDir = voice.dataDir ?: ""
                    )
                )
            )
            offlineTts = OfflineTts(config = config)
            loadedVoiceId = voice.id
        }
    }

    private fun releaseOfflineTts() {
        val tts = offlineTts ?: return
        offlineTts = null
        loadedVoiceId = null
        try { tts.release() } catch (e: Exception) { Logger.w(TAG, "offlineTts.release failed: ${e.message}") }
    }

    private fun startSynthesisLoop(voice: NeuralVoice) {
        val mySession = sessionGuid
        synthesisJob = engineScope.launch {
            try {
                while (isActive && !isPausing.get() && mySession == sessionGuid) {
                    if (chunkIndex >= utterances.size) break
                    val utterance = utterances[chunkIndex]
                    playUtterance(voice, utterance, mySession)
                    if (mySession != sessionGuid || isPausing.get()) break
                    chunkIndex++
                }
                if (mySession == sessionGuid && !isPausing.get() && chunkIndex >= utterances.size) {
                    _state.value = TtsState.Finished
                    _progress.value = 1f
                    audioFocusHelper.release()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Synthesis loop failed", e)
                if (mySession == sessionGuid) {
                    _state.value = TtsState.Error("Synthesis failed: ${e.message}")
                    audioFocusHelper.release()
                }
            }
        }
    }

    private suspend fun playUtterance(voice: NeuralVoice, utterance: TtsUtterance, mySession: String) {
        val tts = offlineTts ?: return
        val audio = withContext(Dispatchers.IO) {
            tts.generate(utterance.text, sid = 0, speed = settings.speechRate)
        }
        if (mySession != sessionGuid || isPausing.get()) return

        val sampleRate = audio.sampleRate
        val samples = audio.samples
        if (samples.isEmpty()) return

        ensureAudioTrack(sampleRate)
        val track = audioTrack ?: return

        // Sherpa-ONNX returns one PCM blob per utterance with no per-word timestamps, so we
        // partition the audio's frame axis between words (weighted by syllable count) and
        // drive the on-screen highlight from AudioTrack.playbackHeadPosition. This pins the
        // displayed word to actual playback rather than a coroutine clock that would drift
        // on any IO stall in track.write().
        val plan = WordTimingPlanner.plan(
            utterance = utterance,
            totalFrames = samples.size,
            minSyllablesPerWord = Constants.TTS_NEURAL_MIN_WORD_WEIGHT,
            maxSyllablesPerWord = Constants.TTS_NEURAL_MAX_WORD_WEIGHT,
            nonLatinCharWeightScale = Constants.TTS_NEURAL_NON_LATIN_CHAR_WEIGHT_SCALE
        )

        // ensureAudioTrack reuses the same AudioTrack across consecutive chunks of the same
        // voice/sample-rate, so playbackHeadPosition is cumulative across utterances. Capture
        // a per-utterance baseline before play() so the per-utterance endFrames (which start
        // at 0) compare directly against (head - baseline).
        val playStartFrame = track.playbackHeadPosition

        // Wrap the poller in coroutineScope so it is a structured child of this coroutine
        // (and therefore of synthesisJob). cancelSynthesis() will wait for the poller to
        // finish before stopAndReleaseAudioTrack() runs, so the poller cannot read a
        // released AudioTrack from pause()/stop()/seek()/shutdown().
        coroutineScope {
            val pollJob = launch {
                // WordTimingPlanner contract: endFrames[i] is the frame at which word i
                // YIELDS the highlight to word i+1. The display must therefore lead each
                // threshold (show word i while head < endFrames[i]), not trail it — the
                // previous loop assigned word i when word i FINISHED, lagging the voice by
                // one full word from the second word of every utterance onward.
                fun showWord(index: Int) {
                    val globalIndex = utterance.globalWordIndices.getOrNull(index) ?: return
                    currentPosition = globalIndex
                    words.getOrNull(globalIndex)?.let { _currentWord.value = it }
                    if (words.isNotEmpty()) _progress.value = globalIndex.toFloat() / words.size
                }

                var i = 0
                val endFrames = plan.endFrames
                val totalFrames = plan.totalFrames
                if (endFrames.isNotEmpty()) showWord(0)
                while (isActive && mySession == sessionGuid && !isPausing.get()) {
                    val head = track.playbackHeadPosition - playStartFrame
                    while (i < endFrames.size - 1 && head >= endFrames[i]) {
                        i++
                        showWord(i)
                    }
                    if (head >= totalFrames) break
                    delay(Constants.TTS_NEURAL_PLAYBACK_POLL_INTERVAL_MS)
                }
            }

            try {
                track.play()
                withContext(Dispatchers.IO) {
                    // Feed the track in bounded slices instead of one full-utterance blocking
                    // write. WRITE_BLOCKING on MODE_STREAM returns only as the data plays out,
                    // and the JNI call is immune to coroutine cancellation — a whole-chunk
                    // write made pause/seek/stop block for the remainder of the utterance
                    // (speech kept going seconds after the tap, including over incoming
                    // calls via the audio-focus-loss path). Slices cap that latency at
                    // roughly TTS_NEURAL_WRITE_SLICE_FRAMES / sampleRate seconds.
                    var offset = 0
                    while (offset < samples.size && isActive &&
                        mySession == sessionGuid && !isPausing.get()
                    ) {
                        val sliceLength =
                            minOf(Constants.TTS_NEURAL_WRITE_SLICE_FRAMES, samples.size - offset)
                        val written =
                            track.write(samples, offset, sliceLength, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) break
                        offset += written
                    }
                }
                // write() returns once samples are queued; the AudioTrack still has up to one
                // buffer of audio (~46ms at 22050Hz / 4096B) playing out. Wait for the head to
                // reach totalFrames so the last word's threshold actually fires, with a
                // defensive timeout against vendor-buggy AudioTracks that never advance.
                withTimeoutOrNull(Constants.TTS_NEURAL_PLAYBACK_DRAIN_TIMEOUT_MS) { pollJob.join() }
            } finally {
                pollJob.cancel()
            }
        }
    }

    private fun ensureAudioTrack(sampleRate: Int) {
        val existing = audioTrack
        if (existing != null && existing.sampleRate == sampleRate) return
        stopAndReleaseAudioTrack()

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(4096)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun stopAndReleaseAudioTrack() {
        val track = audioTrack ?: return
        audioTrack = null
        try {
            track.pause()
            track.flush()
            track.stop()
            track.release()
        } catch (e: Exception) {
            Logger.w(TAG, "AudioTrack teardown error: ${e.message}")
        }
    }

    private suspend fun cancelSynthesis() {
        synthesisJob?.cancelAndJoin()
        synthesisJob = null
    }

    private fun onAudioFocusLoss(loss: AudioFocusHelper.FocusLoss) {
        engineScope.launch {
            if (_state.value is TtsState.Playing) pause()
        }
    }

    private fun NeuralVoice.toTtsVoice(): TtsVoice = TtsVoice(
        id = id,
        displayName = displayName,
        localeTag = "",
        isNetworkRequired = false,
        qualityTier = 400
    )

    companion object {
        private const val TAG = "NeuralTtsEngine"
    }
}

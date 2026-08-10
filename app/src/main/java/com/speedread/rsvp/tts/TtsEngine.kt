package com.speedread.rsvp.tts

import com.speedread.rsvp.engine.RsvpWord
import kotlinx.coroutines.flow.Flow

/**
 * Backend used to synthesize speech. Mirrored in SharedPreferences as the setting string.
 *
 * SYSTEM: android.speech.tts.TextToSpeech. Zero install cost, offline, word-sync via
 *         UtteranceProgressListener.onRangeStart on most modern engines.
 * NEURAL: Sherpa-ONNX running a Piper/VITS model dropped into the app-external model dir.
 *         Best quality; requires user to place voice files first.
 */
enum class TtsBackend {
    SYSTEM,
    NEURAL;

    companion object {
        fun fromId(id: String?): TtsBackend = values().firstOrNull { it.name == id } ?: SYSTEM
    }
}

/**
 * Which engine is actively driving the word-advance flow. The coordinator flips between
 * these under a mutex; consumers of currentWord/progress/state never see a gap.
 */
enum class PlaybackMode { RSVP, TTS }

/**
 * Unified engine state surfaced to the UI. Distinct from RsvpState because TTS has
 * additional states (Initializing = TTS service connecting, BackendUnavailable = user
 * picked Neural but no model present, Error = OEM engine or synthesis failure).
 */
sealed class TtsState {
    object Idle : TtsState()
    object Initializing : TtsState()
    object Playing : TtsState()
    object Paused : TtsState()
    object Finished : TtsState()
    object BackendUnavailable : TtsState()
    data class Error(val message: String) : TtsState()
}

/**
 * A voice exposed by the System (Android TTS) backend. Derived from
 * android.speech.tts.Voice, but kept as a pure data class so the Options UI doesn't
 * drag android.speech.tts types into its layer.
 */
data class TtsVoice(
    val id: String,               // engine-provided name, unique within a backend
    val displayName: String,
    val localeTag: String,        // e.g. "en-US"
    val isNetworkRequired: Boolean,
    val qualityTier: Int          // android.speech.tts.Voice.QUALITY_VERY_LOW..VERY_HIGH
)

/**
 * A Neural voice discovered in the drop-in model folder. Populated by TtsModelRegistry
 * from a subfolder's files — displayName is the folder name (users can rename freely).
 */
data class NeuralVoice(
    val id: String,               // folder name
    val displayName: String,      // pretty-printed folder name
    val modelPath: String,        // absolute path to model.onnx
    val tokensPath: String,       // absolute path to tokens.txt
    val dataDir: String?,         // absolute path to espeak-ng-data/ if present
    val sampleRate: Int,          // parsed from config JSON or defaulted
    val speakerCount: Int         // 1 for single-speaker voices
)

/**
 * Common interface for both TTS backends. Mirrors the shape of RsvpEngine so the
 * PlaybackCoordinator can swap backends without consumers changing their observers.
 *
 * Takes a List<RsvpWord> instead of a raw String so TTS reuses the existing tokenization
 * — bookmark positions, Page View highlight indices, and seek logic stay identical
 * across RSVP and TTS modes.
 */
interface TtsEngine {
    /** Which backend this instance implements — used for logging and UI backend badges. */
    fun backendId(): TtsBackend

    /** True when this engine can actually synthesize. Neural returns false if no model present. */
    fun isAvailable(): Boolean

    suspend fun loadWords(words: List<RsvpWord>)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seekToPosition(position: Int)

    fun observeCurrentWord(): Flow<RsvpWord?>
    fun observeProgress(): Flow<Float>
    fun observeState(): Flow<TtsState>

    fun updateSettings(settings: TtsSettings)

    /**
     * Voices available for the current backend. For SYSTEM, sourced from the OS TTS engine
     * (populated after async init). For NEURAL, sourced from TtsModelRegistry subfolders.
     */
    fun getAvailableVoices(): List<TtsVoice>

    /**
     * Position (word index) this engine last spoke. PlaybackCoordinator reads this on pause
     * to sync the RSVP engine so subsequent play() calls resume from where TTS left off.
     * Without this sync, every hold-release cycle would jump back to the position that TTS
     * originally seeded from.
     */
    fun getCurrentPosition(): Int

    /** Release native resources. Safe to call from Application shutdown; idempotent. */
    suspend fun shutdown()
}

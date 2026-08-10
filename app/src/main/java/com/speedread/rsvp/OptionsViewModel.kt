package com.speedread.rsvp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedread.rsvp.data.settings.SettingsRepository
import com.speedread.rsvp.data.settings.TtsSettingsRepository
import com.speedread.rsvp.engine.LandscapePlayButtonCorner
import com.speedread.rsvp.engine.RsvpSettings
import com.speedread.rsvp.tts.NativeTtsEngine
import com.speedread.rsvp.tts.NeuralVoice
import com.speedread.rsvp.tts.PlaybackCoordinator
import com.speedread.rsvp.tts.PlaybackMode
import com.speedread.rsvp.tts.TtsBackend
import com.speedread.rsvp.tts.TtsModelRegistry
import com.speedread.rsvp.tts.TtsSettings
import com.speedread.rsvp.tts.TtsVoice
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin ViewModel over SettingsRepository + TtsSettingsRepository. All persistence, in-memory
 * state, and engine updates happen inside the repositories — this class exists only to
 * expose StateFlows to the Options Fragment and to present a familiar VM-level API.
 */
@HiltViewModel
class OptionsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val ttsSettingsRepository: TtsSettingsRepository,
    private val ttsModelRegistry: TtsModelRegistry,
    private val nativeTtsEngine: NativeTtsEngine,
    private val playbackCoordinator: PlaybackCoordinator
) : ViewModel() {

    // --- RSVP settings (existing) ---

    val settings: StateFlow<RsvpSettings> = settingsRepository.settings

    val wpm: StateFlow<Int> = settingsRepository.settings
        .map { it.wpm }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.settings.value.wpm)

    fun updateWpm(newWpm: Int) = settingsRepository.updateWpm(newWpm)

    fun updateSettings(settings: RsvpSettings) = settingsRepository.saveRsvpSettings(settings)

    fun updateChunkSize(chunkSize: Int) = settingsRepository.updateChunkSize(chunkSize)

    fun updateOrpSettings(enableOrp: Boolean, centerOrp: Boolean) =
        settingsRepository.updateOrpSettings(enableOrp, centerOrp)

    fun updateOrpColor(color: Int) = settingsRepository.updateOrpColor(color)

    fun updateTextColors(textColor: Int, backgroundColor: Int) =
        settingsRepository.updateTextColors(textColor, backgroundColor)

    fun updatePunctuationPausing(enabled: Boolean) =
        settingsRepository.updatePunctuationPausing(enabled)

    fun updateScreenDimming(enabled: Boolean) =
        settingsRepository.updateScreenDimming(enabled)

    fun updateParagraphSpacing(enabled: Boolean) =
        settingsRepository.updateParagraphSpacing(enabled)

    fun updateLandscapePlayButtonCorners(corners: Set<LandscapePlayButtonCorner>) =
        settingsRepository.updateLandscapePlayButtonCorners(corners)

    fun updateScrubPixelsPerWord(pixels: Float) =
        settingsRepository.updateScrubPixelsPerWord(pixels)

    fun updateWordLengthTimingEnabled(enabled: Boolean) =
        settingsRepository.updateWordLengthTimingEnabled(enabled)

    fun updateWordLengthBaseline(baseline: Int) =
        settingsRepository.updateWordLengthBaseline(baseline)

    fun updateWordLengthScalingPercent(percent: Int) =
        settingsRepository.updateWordLengthScalingPercent(percent)

    fun updatePunctuationTiming(settingIndex: Int, value: Int) =
        settingsRepository.updatePunctuationTiming(settingIndex, value)

    fun resetSettingsToDefaults() = settingsRepository.resetToDefaults()

    // --- TTS settings ---

    val ttsSettings: StateFlow<TtsSettings> = ttsSettingsRepository.settings

    /**
     * System-backend voices (android.speech.tts). Sourced from the engine's own StateFlow
     * so the empty→populated transition that happens when TTS init completes is observable
     * to the UI. Init itself is triggered lazily by [prewarmTts] when the Options screen
     * comes into the foreground, so the dropdown fills without requiring a prior play().
     */
    val systemTtsVoices: StateFlow<List<TtsVoice>> = nativeTtsEngine.voices

    /** Neural-backend voices discovered in the drop-in model folder. */
    val neuralVoices: StateFlow<List<NeuralVoice>> = ttsModelRegistry.voices

    /** Playback mode (RSVP vs TTS). Options disables chunk-size while in TTS mode. */
    val activeMode: StateFlow<PlaybackMode> = playbackCoordinator.activeMode

    /** Absolute path of the drop-in Neural voice folder (shown in the Options UI). */
    val neuralModelFolderPath: String?
        get() = ttsModelRegistry.modelRootDir?.absolutePath

    fun updateTtsBackend(backend: TtsBackend) {
        // Capture the departing backend BEFORE persisting the change — the coordinator needs
        // it to pause the old engine and read the true playback position from it.
        val previousBackend = ttsSettingsRepository.settings.value.backend
        ttsSettingsRepository.updateBackend(backend)
        viewModelScope.launch {
            // If a document is currently loaded and we're in TTS mode, re-sync the new backend
            // to the current word list/position so playback resumes transparently.
            playbackCoordinator.onTtsBackendChanged(previousBackend)
        }
    }

    fun updateTtsSpeechRate(rate: Float) = ttsSettingsRepository.updateSpeechRate(rate)

    fun updateTtsPitch(pitch: Float) = ttsSettingsRepository.updatePitch(pitch)

    fun updateTtsVoiceId(voiceId: String?) = ttsSettingsRepository.updateVoiceId(voiceId)

    /**
     * Set the System-backend language filter. If the currently-selected voice is *confirmed*
     * to be a System voice with a different locale than the new filter, also clear the voice
     * id so the dropdown falls back to "Default" — otherwise the user would see a non-matching
     * voice highlighted with no way to play it from a list that excludes it.
     *
     * Three cases where we DO NOT clear the voice id:
     *  1. localeTag == null ("All languages") — no filter, never disturb the voice.
     *  2. systemTtsVoices is empty — the System TTS engine hasn't finished its async init yet
     *     (common in the moment the Options screen opens). We literally cannot check whether
     *     the saved voiceId matches; clearing it would lose a perfectly-valid persisted choice
     *     just because we're early.
     *  3. voiceId is set but doesn't appear in systemTtsVoices at all — it's a Neural voice id
     *     ([TtsSettings.voiceId] is a shared field across backends), and clearing it would
     *     nuke the user's Neural voice selection while they're just changing System UI state.
     */
    fun updateSystemLanguageFilter(localeTag: String?) {
        if (localeTag == null) {
            ttsSettingsRepository.updateSystemLanguageFilter(null)
            return
        }
        val currentVoiceId = ttsSettings.value.voiceId
        val voices = systemTtsVoices.value
        // Case 2: voice list not yet populated → save filter only, leave voiceId alone.
        if (voices.isEmpty()) {
            ttsSettingsRepository.updateSystemLanguageFilter(localeTag)
            return
        }
        // Case 3: voiceId not present in the System catalog → it's a Neural id (or stale).
        val matchingVoice = currentVoiceId?.let { id -> voices.firstOrNull { it.id == id } }
        if (matchingVoice == null) {
            ttsSettingsRepository.updateSystemLanguageFilter(localeTag)
            return
        }
        // We have a confirmed System voice. Clear only if its locale doesn't match the filter.
        if (matchingVoice.localeTag == localeTag) {
            ttsSettingsRepository.updateSystemLanguageFilter(localeTag)
        } else {
            ttsSettingsRepository.updateSystemLanguageFilterAndVoice(localeTag, voiceId = null)
        }
    }

    fun resetTtsSettingsToDefaults() = ttsSettingsRepository.resetToDefaults()

    /** Called from OptionsFragment.onResume so user-dropped model folders appear without restart. */
    fun rescanNeuralVoices() = ttsModelRegistry.rescan()

    /**
     * Force the System TTS engine to bind so its voice list populates. Called from
     * OptionsFragment.onResume so the voice dropdown fills immediately on screen entry —
     * without this, voices only appear after the user's first play() because that was
     * historically the only call site of [NativeTtsEngine.initIfNeeded]. Idempotent.
     */
    fun prewarmTts() {
        viewModelScope.launch {
            try {
                nativeTtsEngine.ensureInitialized()
            } catch (t: Throwable) {
                // TTS init failure is recoverable from the Options screen — the voice picker
                // simply stays empty and the user can still use RSVP. Swallow rather than let
                // the unhandled coroutine exception escalate to a process crash.
                Logger.w("OptionsViewModel", "prewarmTts: TTS init failed: ${t.message}")
            }
        }
    }
}

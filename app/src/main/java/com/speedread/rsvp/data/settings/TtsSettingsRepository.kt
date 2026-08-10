package com.speedread.rsvp.data.settings

import com.speedread.rsvp.tts.TtsBackend
import com.speedread.rsvp.tts.TtsEngineProvider
import com.speedread.rsvp.tts.TtsSettings
import com.speedread.rsvp.tts.TtsSettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for [TtsSettings]. Direct structural mirror of [SettingsRepository]:
 * one StateFlow, one writer, every write synchronously updates SharedPreferences AND pushes
 * the new settings to both TTS engines so toggling backends preserves rate/pitch/voice state.
 *
 * Keeping this independent of SettingsRepository avoids cross-contamination — resetting one
 * settings domain doesn't touch the other, and the pure-JVM rsvp-engine module stays free of
 * Android TTS concepts.
 */
@Singleton
class TtsSettingsRepository @Inject constructor(
    private val manager: TtsSettingsManager,
    private val engineProvider: TtsEngineProvider
) {
    private val _settings = MutableStateFlow(manager.getCurrentTtsSettings())
    val settings: StateFlow<TtsSettings> = _settings.asStateFlow()

    init {
        // Seed both engines with the persisted settings at construction time so the first
        // toggle from RSVP to TTS uses the correct rate / pitch / voice without waiting for
        // the first user-driven change.
        pushToEngines(_settings.value)
    }

    fun updateBackend(backend: TtsBackend) {
        manager.updateBackend(backend)
        publish(_settings.value.copy(backend = backend))
    }

    fun updateSpeechRate(rate: Float) {
        manager.updateSpeechRate(rate)
        publish(_settings.value.copy(speechRate = rate))
    }

    fun updatePitch(pitch: Float) {
        manager.updatePitch(pitch)
        publish(_settings.value.copy(pitch = pitch))
    }

    fun updateVoiceId(voiceId: String?) {
        manager.updateVoiceId(voiceId)
        publish(_settings.value.copy(voiceId = voiceId))
    }

    /**
     * Update the System-backend language filter. UI-only — does not need to push to engines
     * since [TtsSettings.systemLanguageFilter] doesn't affect synthesis. We still publish
     * via [publish] / [pushToEngines] to keep the parallel structure with other writers and
     * avoid surprising future engines that might want to observe it.
     */
    fun updateSystemLanguageFilter(localeTag: String?) {
        manager.updateSystemLanguageFilter(localeTag)
        publish(_settings.value.copy(systemLanguageFilter = localeTag))
    }

    /**
     * Atomically update both the language filter and the voice id. Used when changing the
     * filter requires invalidating the current voice (its locale doesn't match the new filter)
     * — splitting into two writes would briefly publish a state where the voice and filter
     * disagree, which the UI could re-render mid-update.
     */
    fun updateSystemLanguageFilterAndVoice(localeTag: String?, voiceId: String?) {
        manager.updateSystemLanguageFilter(localeTag)
        manager.updateVoiceId(voiceId)
        publish(_settings.value.copy(systemLanguageFilter = localeTag, voiceId = voiceId))
    }

    fun resetToDefaults() {
        manager.resetToDefaults()
        publish(manager.getCurrentTtsSettings())
    }

    private fun publish(updated: TtsSettings) {
        _settings.value = updated
        pushToEngines(updated)
    }

    private fun pushToEngines(settings: TtsSettings) {
        // Push to BOTH engines: only the active one synthesizes, but keeping both in sync
        // means toggling backends never loses voice/rate/pitch state mid-session.
        engineProvider.native().updateSettings(settings)
        engineProvider.neural().updateSettings(settings)
    }
}

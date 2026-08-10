package com.speedread.rsvp.data.settings

import com.speedread.rsvp.RsvpSettingsManager
import com.speedread.rsvp.engine.LandscapePlayButtonCorner
import com.speedread.rsvp.engine.RsvpEngine
import com.speedread.rsvp.engine.RsvpSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for RsvpSettings.
 *
 * Owns the StateFlow that both ReadingViewModel and OptionsViewModel observe, and is
 * the only component that calls `rsvpEngine.updateSettings`. This eliminates the
 * dual-writer hazard where each VM used to mutate its own private _settings copy and
 * independently push to the engine — which produced stale WPM in auto-bookmark
 * metadata when Options wrote a new value that Reading's private copy hadn't pulled.
 *
 * Persistence (SharedPreferences) still flows through RsvpSettingsManager; this class
 * synchronizes the in-memory StateFlow, the persistent store, and the engine state.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val rsvpSettingsManager: RsvpSettingsManager,
    private val rsvpEngine: RsvpEngine
) {

    private val _settings = MutableStateFlow(rsvpSettingsManager.getCurrentRsvpSettings())
    val settings: StateFlow<RsvpSettings> = _settings.asStateFlow()

    init {
        // Seed the engine with the persisted settings at construction time, so the
        // first document load uses the correct WPM / chunk size / ORP settings even
        // before any VM or user action fires.
        rsvpEngine.updateSettings(_settings.value)
    }

    fun updateWpm(wpm: Int) {
        rsvpSettingsManager.updateWpm(wpm)
        publish(_settings.value.copy(wpm = wpm))
    }

    fun saveRsvpSettings(settings: RsvpSettings) {
        rsvpSettingsManager.saveRsvpSettings(settings)
        publish(settings)
    }

    fun updateChunkSize(chunkSize: Int) {
        rsvpSettingsManager.updateChunkSize(chunkSize)
        publish(_settings.value.copy(chunkSize = chunkSize))
    }

    fun updateOrpSettings(enableOrp: Boolean, centerOrp: Boolean) {
        rsvpSettingsManager.updateOrpSettings(enableOrp, centerOrp)
        publish(_settings.value.copy(enableOrp = enableOrp, centerOrp = centerOrp))
    }

    fun updateOrpColor(color: Int) {
        rsvpSettingsManager.updateOrpColor(color)
        publish(_settings.value.copy(orpColor = color))
    }

    fun updateTextColors(textColor: Int, backgroundColor: Int) {
        rsvpSettingsManager.updateTextColors(textColor, backgroundColor)
        publish(_settings.value.copy(textColor = textColor, backgroundColor = backgroundColor))
    }

    fun updatePunctuationPausing(enabled: Boolean) {
        rsvpSettingsManager.updatePunctuationPausing(enabled)
        publish(_settings.value.copy(enablePunctuationPausing = enabled))
    }

    fun updateScreenDimming(enabled: Boolean) {
        rsvpSettingsManager.updateScreenDimming(enabled)
        publish(_settings.value.copy(enableScreenDimming = enabled))
    }

    fun updateParagraphSpacing(enabled: Boolean) {
        rsvpSettingsManager.updateParagraphSpacing(enabled)
        publish(_settings.value.copy(enableParagraphSpacing = enabled))
    }

    fun updateLandscapePlayButtonCorners(corners: Set<LandscapePlayButtonCorner>) {
        if (corners.isEmpty()) return
        rsvpSettingsManager.updateLandscapePlayButtonCorners(corners)
        publish(_settings.value.copy(landscapePlayButtonCorners = corners))
    }

    fun updateScrubPixelsPerWord(pixels: Float) {
        rsvpSettingsManager.updateScrubPixelsPerWord(pixels)
        publish(_settings.value.copy(scrubPixelsPerWord = pixels))
    }

    fun updateWordLengthTimingEnabled(enabled: Boolean) {
        rsvpSettingsManager.updateWordLengthTimingEnabled(enabled)
        publish(_settings.value.copy(enableWordLengthTiming = enabled))
    }

    fun updateWordLengthBaseline(baseline: Int) {
        rsvpSettingsManager.updateWordLengthBaseline(baseline)
        publish(_settings.value.copy(
            wordLengthTiming = _settings.value.wordLengthTiming.copy(baseline = baseline)
        ))
    }

    fun updateWordLengthScalingPercent(percent: Int) {
        rsvpSettingsManager.updateWordLengthScalingPercent(percent)
        publish(_settings.value.copy(
            wordLengthTiming = _settings.value.wordLengthTiming.copy(scalingPercent = percent)
        ))
    }

    fun updatePunctuationTiming(settingIndex: Int, value: Int) {
        rsvpSettingsManager.updatePunctuationTiming(settingIndex, value)
        publish(_settings.value.copy(punctuationTiming = rsvpSettingsManager.getCurrentPunctuationTiming()))
    }

    fun resetToDefaults() {
        rsvpSettingsManager.resetToDefaults()
        publish(rsvpSettingsManager.getCurrentRsvpSettings())
    }

    /**
     * Atomically update the StateFlow and notify the engine. Kept private so all
     * writes go through the typed repo API — no caller can mutate the StateFlow
     * directly.
     */
    private fun publish(updated: RsvpSettings) {
        _settings.value = updated
        rsvpEngine.updateSettings(updated)
    }
}

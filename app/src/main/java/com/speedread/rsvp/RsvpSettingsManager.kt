package com.speedread.rsvp

import android.content.Context
import android.content.SharedPreferences
import com.speedread.rsvp.engine.EngineConstants
import com.speedread.rsvp.engine.LandscapePlayButtonCorner
import com.speedread.rsvp.engine.PunctuationTiming
import com.speedread.rsvp.engine.RsvpSettings
import com.speedread.rsvp.engine.WordLengthTiming
import com.speedread.rsvp.tts.PlaybackMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RsvpSettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val RSVP_PREFS = "rsvp_preferences"
        
        // RsvpSettings keys
        private const val KEY_WPM = "wpm"
        private const val KEY_CHUNK_SIZE = "chunk_size"
        private const val KEY_ENABLE_ORP = "enable_orp"
        private const val KEY_CENTER_ORP = "center_orp"
        private const val KEY_ORP_COLOR = "orp_color"
        private const val KEY_ENABLE_PUNCTUATION_PAUSING = "enable_punctuation_pausing"
        private const val KEY_ENABLE_PARAGRAPH_SPACING = "enable_paragraph_spacing"
        
        // PunctuationTiming keys
        private const val KEY_COMMA_TIMING = "comma_timing"
        private const val KEY_PERIOD_TIMING = "period_timing"
        private const val KEY_SEMICOLON_TIMING = "semicolon_timing"
        private const val KEY_COLON_TIMING = "colon_timing"
        private const val KEY_QUESTION_TIMING = "question_timing"
        private const val KEY_EXCLAMATION_TIMING = "exclamation_timing"
        private const val KEY_LINE_BREAK_TIMING = "line_break_timing"
        private const val KEY_PARAGRAPH_TIMING = "paragraph_timing"
        
        // Word length timing keys
        private const val KEY_ENABLE_WORD_LENGTH_TIMING = "enable_word_length_timing"
        private const val KEY_WORD_LENGTH_BASELINE = "word_length_baseline"
        private const val KEY_WORD_LENGTH_SCALING_PERCENT = "word_length_scaling_percent"

        // Text appearance keys
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_BACKGROUND_COLOR = "background_color"

        // Reader UI keys
        private const val KEY_ENABLE_SCREEN_DIMMING = "enable_screen_dimming"
        // Legacy single-corner key. Read-only — migrated into KEY_LANDSCAPE_PLAY_BUTTON_CORNERS
        // on first load so users who upgrade past the additive-selector change keep their
        // existing corner preference.
        private const val KEY_LANDSCAPE_PLAY_BUTTON_CORNER_LEGACY = "landscape_play_button_corner"
        private const val KEY_LANDSCAPE_PLAY_BUTTON_CORNERS = "landscape_play_button_corners"
        private const val KEY_SCRUB_PIXELS_PER_WORD = "scrub_pixels_per_word"

        // Last active playback mode (RSVP / TTS) — persisted so the app resumes in whichever
        // mode the user was last using rather than defaulting to RSVP every cold start.
        // Stored as the enum name string; unparseable values fall back to the default.
        private const val KEY_LAST_PLAYBACK_MODE = "last_playback_mode"

        // Default values
        private const val DEFAULT_WPM = EngineConstants.DEFAULT_WPM
        private const val DEFAULT_CHUNK_SIZE = EngineConstants.DEFAULT_CHUNK_SIZE
        private const val DEFAULT_ENABLE_ORP = true
        private const val DEFAULT_CENTER_ORP = true
        private const val DEFAULT_ENABLE_PUNCTUATION_PAUSING = true
        private const val DEFAULT_ENABLE_PARAGRAPH_SPACING = false
        private const val DEFAULT_ENABLE_WORD_LENGTH_TIMING = false
        private val DEFAULT_LAST_PLAYBACK_MODE = PlaybackMode.RSVP
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(RSVP_PREFS, Context.MODE_PRIVATE)

    /**
     * Get current RSVP settings from preferences
     */
    fun getCurrentRsvpSettings(): RsvpSettings {
        val punctuationTiming = getCurrentPunctuationTiming()
        val wordLengthTiming = getCurrentWordLengthTiming()
        val defaultSettings = RsvpSettings()

        return RsvpSettings(
            wpm = prefs.getInt(KEY_WPM, DEFAULT_WPM),
            chunkSize = prefs.getInt(KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE),
            enableOrp = prefs.getBoolean(KEY_ENABLE_ORP, DEFAULT_ENABLE_ORP),
            centerOrp = prefs.getBoolean(KEY_CENTER_ORP, DEFAULT_CENTER_ORP),
            orpColor = prefs.getInt(KEY_ORP_COLOR, EngineConstants.DEFAULT_ORP_COLOR),
            enablePunctuationPausing = prefs.getBoolean(KEY_ENABLE_PUNCTUATION_PAUSING, DEFAULT_ENABLE_PUNCTUATION_PAUSING),
            punctuationTiming = punctuationTiming,
            textColor = prefs.getInt(KEY_TEXT_COLOR, defaultSettings.textColor),
            backgroundColor = prefs.getInt(KEY_BACKGROUND_COLOR, defaultSettings.backgroundColor),
            enableScreenDimming = prefs.getBoolean(KEY_ENABLE_SCREEN_DIMMING, EngineConstants.DEFAULT_ENABLE_SCREEN_DIMMING),
            landscapePlayButtonCorners = readLandscapePlayButtonCorners(),
            scrubPixelsPerWord = prefs.getFloat(KEY_SCRUB_PIXELS_PER_WORD, EngineConstants.DEFAULT_SCRUB_PIXELS_PER_WORD)
                .coerceIn(EngineConstants.MIN_SCRUB_PIXELS_PER_WORD, EngineConstants.MAX_SCRUB_PIXELS_PER_WORD),
            enableParagraphSpacing = prefs.getBoolean(KEY_ENABLE_PARAGRAPH_SPACING, DEFAULT_ENABLE_PARAGRAPH_SPACING),
            enableWordLengthTiming = prefs.getBoolean(KEY_ENABLE_WORD_LENGTH_TIMING, DEFAULT_ENABLE_WORD_LENGTH_TIMING),
            wordLengthTiming = wordLengthTiming
        )
    }

    // Persisted as a set of enum name strings; unknown/legacy values are filtered out and the
    // result is never empty (UI guarantees at-least-one, but a corrupt pref must not leave the
    // reader without any hold-to-play FAB). Legacy single-corner key is migrated on first read.
    private fun readLandscapePlayButtonCorners(): Set<LandscapePlayButtonCorner> {
        val rawSet = prefs.getStringSet(KEY_LANDSCAPE_PLAY_BUTTON_CORNERS, null)
        if (rawSet != null) {
            val parsed = rawSet.mapNotNullTo(mutableSetOf()) { name ->
                runCatching { LandscapePlayButtonCorner.valueOf(name) }.getOrNull()
            }
            if (parsed.isNotEmpty()) return parsed
        }
        // Legacy migration: a pre-additive install stored a single corner. Seed the new
        // multi-corner set from it and persist so future reads skip this branch.
        val legacyRaw = prefs.getString(KEY_LANDSCAPE_PLAY_BUTTON_CORNER_LEGACY, null)
        if (legacyRaw != null) {
            val migrated = runCatching { setOf(LandscapePlayButtonCorner.valueOf(legacyRaw)) }
                .getOrDefault(EngineConstants.DEFAULT_LANDSCAPE_PLAY_BUTTON_CORNERS)
            prefs.edit()
                .putStringSet(KEY_LANDSCAPE_PLAY_BUTTON_CORNERS, migrated.map { it.name }.toSet())
                .remove(KEY_LANDSCAPE_PLAY_BUTTON_CORNER_LEGACY)
                .apply()
            return migrated
        }
        return EngineConstants.DEFAULT_LANDSCAPE_PLAY_BUTTON_CORNERS
    }

    /**
     * Save RSVP settings to preferences
     */
    fun saveRsvpSettings(settings: RsvpSettings) {
        prefs.edit()
            .putInt(KEY_WPM, settings.wpm)
            .putInt(KEY_CHUNK_SIZE, settings.chunkSize)
            .putBoolean(KEY_ENABLE_ORP, settings.enableOrp)
            .putBoolean(KEY_CENTER_ORP, settings.centerOrp)
            .putInt(KEY_ORP_COLOR, settings.orpColor)
            .putBoolean(KEY_ENABLE_PUNCTUATION_PAUSING, settings.enablePunctuationPausing)
            .putInt(KEY_TEXT_COLOR, settings.textColor)
            .putInt(KEY_BACKGROUND_COLOR, settings.backgroundColor)
            .putBoolean(KEY_ENABLE_SCREEN_DIMMING, settings.enableScreenDimming)
            .putStringSet(
                KEY_LANDSCAPE_PLAY_BUTTON_CORNERS,
                settings.landscapePlayButtonCorners.map { it.name }.toSet()
            )
            .putFloat(KEY_SCRUB_PIXELS_PER_WORD, settings.scrubPixelsPerWord)
            .putBoolean(KEY_ENABLE_PARAGRAPH_SPACING, settings.enableParagraphSpacing)
            .putBoolean(KEY_ENABLE_WORD_LENGTH_TIMING, settings.enableWordLengthTiming)
            .apply()

        // Save punctuation timing separately
        savePunctuationTiming(settings.punctuationTiming)
        saveWordLengthTiming(settings.wordLengthTiming)
    }

    /**
     * Get current punctuation timing from preferences
     */
    fun getCurrentPunctuationTiming(): PunctuationTiming {
        val defaultTiming = PunctuationTiming.default()
        
        return PunctuationTiming(
            comma = prefs.getInt(KEY_COMMA_TIMING, defaultTiming.comma),
            period = prefs.getInt(KEY_PERIOD_TIMING, defaultTiming.period),
            semicolon = prefs.getInt(KEY_SEMICOLON_TIMING, defaultTiming.semicolon),
            colon = prefs.getInt(KEY_COLON_TIMING, defaultTiming.colon),
            questionMark = prefs.getInt(KEY_QUESTION_TIMING, defaultTiming.questionMark),
            exclamation = prefs.getInt(KEY_EXCLAMATION_TIMING, defaultTiming.exclamation),
            lineBreak = prefs.getInt(KEY_LINE_BREAK_TIMING, defaultTiming.lineBreak),
            paragraphBreak = prefs.getInt(KEY_PARAGRAPH_TIMING, defaultTiming.paragraphBreak)
        )
    }

    /**
     * Last active playback mode the user toggled to. Restored on app start so a user who was
     * listening (TTS) at last close doesn't have to re-toggle out of RSVP every cold launch.
     * Defaults to RSVP for first-run installs and for any prefs corruption.
     */
    fun getLastPlaybackMode(): PlaybackMode {
        val raw = prefs.getString(KEY_LAST_PLAYBACK_MODE, DEFAULT_LAST_PLAYBACK_MODE.name)
            ?: DEFAULT_LAST_PLAYBACK_MODE.name
        return runCatching { PlaybackMode.valueOf(raw) }.getOrDefault(DEFAULT_LAST_PLAYBACK_MODE)
    }

    fun saveLastPlaybackMode(mode: PlaybackMode) {
        prefs.edit().putString(KEY_LAST_PLAYBACK_MODE, mode.name).apply()
    }

    /**
     * Save punctuation timing to preferences
     */
    fun savePunctuationTiming(timing: PunctuationTiming) {
        prefs.edit()
            .putInt(KEY_COMMA_TIMING, timing.comma)
            .putInt(KEY_PERIOD_TIMING, timing.period)
            .putInt(KEY_SEMICOLON_TIMING, timing.semicolon)
            .putInt(KEY_COLON_TIMING, timing.colon)
            .putInt(KEY_QUESTION_TIMING, timing.questionMark)
            .putInt(KEY_EXCLAMATION_TIMING, timing.exclamation)
            .putInt(KEY_LINE_BREAK_TIMING, timing.lineBreak)
            .putInt(KEY_PARAGRAPH_TIMING, timing.paragraphBreak)
            .apply()
    }

    /**
     * Update WPM and save immediately
     */
    fun updateWpm(wpm: Int) {
        prefs.edit()
            .putInt(KEY_WPM, wpm)
            .apply()
    }

    /**
     * Update chunk size and save immediately
     */
    fun updateChunkSize(chunkSize: Int) {
        prefs.edit()
            .putInt(KEY_CHUNK_SIZE, chunkSize)
            .apply()
    }

    /**
     * Update individual punctuation timing and save immediately
     */
    fun updatePunctuationTiming(settingIndex: Int, value: Int) {
        val currentTiming = getCurrentPunctuationTiming()
        val updatedTiming = when (settingIndex) {
            0 -> currentTiming.copy(comma = value)
            1 -> currentTiming.copy(period = value)
            2 -> currentTiming.copy(semicolon = value)
            3 -> currentTiming.copy(colon = value)
            4 -> currentTiming.copy(questionMark = value)
            5 -> currentTiming.copy(exclamation = value)
            6 -> currentTiming.copy(lineBreak = value)
            7 -> currentTiming.copy(paragraphBreak = value)
            else -> currentTiming
        }
        savePunctuationTiming(updatedTiming)
    }

    /**
     * Update ORP settings and save immediately
     */
    fun updateOrpSettings(enableOrp: Boolean, centerOrp: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ENABLE_ORP, enableOrp)
            .putBoolean(KEY_CENTER_ORP, centerOrp)
            .apply()
    }

    fun updateOrpColor(color: Int) {
        prefs.edit()
            .putInt(KEY_ORP_COLOR, color)
            .apply()
    }

    /**
     * Update punctuation pausing setting and save immediately
     */
    fun updatePunctuationPausing(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ENABLE_PUNCTUATION_PAUSING, enabled)
            .apply()
    }

    /**
     * Update screen dimming setting and save immediately
     */
    fun updateScreenDimming(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ENABLE_SCREEN_DIMMING, enabled)
            .apply()
    }

    /**
     * Update landscape play-button corners and save immediately.
     * Empty input is rejected — callers must supply at least one corner so the landscape
     * reader is never left without a hold-to-play FAB.
     */
    fun updateLandscapePlayButtonCorners(corners: Set<LandscapePlayButtonCorner>) {
        if (corners.isEmpty()) return
        prefs.edit()
            .putStringSet(KEY_LANDSCAPE_PLAY_BUTTON_CORNERS, corners.map { it.name }.toSet())
            .remove(KEY_LANDSCAPE_PLAY_BUTTON_CORNER_LEGACY)
            .apply()
    }

    /**
     * Update landscape scrub sensitivity and save immediately
     */
    fun updateScrubPixelsPerWord(pixels: Float) {
        prefs.edit()
            .putFloat(KEY_SCRUB_PIXELS_PER_WORD, pixels)
            .apply()
    }

    /**
     * Update paragraph spacing setting and save immediately
     */
    fun updateParagraphSpacing(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ENABLE_PARAGRAPH_SPACING, enabled)
            .apply()
    }

    fun getCurrentWordLengthTiming(): WordLengthTiming {
        val defaults = WordLengthTiming.default()
        return WordLengthTiming(
            baseline = prefs.getInt(KEY_WORD_LENGTH_BASELINE, defaults.baseline)
                .coerceIn(EngineConstants.MIN_WORD_LENGTH_BASELINE, EngineConstants.MAX_WORD_LENGTH_BASELINE),
            scalingPercent = prefs.getInt(KEY_WORD_LENGTH_SCALING_PERCENT, defaults.scalingPercent)
                .coerceIn(EngineConstants.MIN_WORD_LENGTH_SCALING_PERCENT, EngineConstants.MAX_WORD_LENGTH_SCALING_PERCENT)
        )
    }

    fun saveWordLengthTiming(timing: WordLengthTiming) {
        prefs.edit()
            .putInt(KEY_WORD_LENGTH_BASELINE, timing.baseline)
            .putInt(KEY_WORD_LENGTH_SCALING_PERCENT, timing.scalingPercent)
            .apply()
    }

    fun updateWordLengthTimingEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ENABLE_WORD_LENGTH_TIMING, enabled)
            .apply()
    }

    fun updateWordLengthBaseline(baseline: Int) {
        prefs.edit()
            .putInt(KEY_WORD_LENGTH_BASELINE, baseline)
            .apply()
    }

    fun updateWordLengthScalingPercent(percent: Int) {
        prefs.edit()
            .putInt(KEY_WORD_LENGTH_SCALING_PERCENT, percent)
            .apply()
    }

    /**
     * Update text color settings and save immediately
     */
    fun updateTextColors(textColor: Int, backgroundColor: Int) {
        prefs.edit()
            .putInt(KEY_TEXT_COLOR, textColor)
            .putInt(KEY_BACKGROUND_COLOR, backgroundColor)
            .apply()
    }

    /**
     * Reset all settings to defaults
     */
    fun resetToDefaults() {
        val defaultSettings = RsvpSettings()
        saveRsvpSettings(defaultSettings)
    }

    /**
     * Initialize settings on app startup - loads saved settings
     */
    fun initializeSettings(): RsvpSettings {
        return getCurrentRsvpSettings()
    }
}
package com.speedread.rsvp.tts

import android.content.Context
import android.content.SharedPreferences
import com.speedread.rsvp.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SharedPreferences-backed persistence for [TtsSettings]. Mirrors RsvpSettingsManager's
 * shape — same constructor injection pattern, same key-in-companion convention, same
 * update-one-field-at-a-time helpers — so the two managers stay visually parallel.
 *
 * Uses a dedicated pref file (TTS_PREFS_NAME) separate from the RSVP one so:
 *   - resetting RSVP doesn't clobber TTS settings and vice versa
 *   - the TTS subsystem can be cleared independently from Options without touching RSVP
 */
@Singleton
class TtsSettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEY_BACKEND = "tts_backend"
        private const val KEY_SPEECH_RATE = "tts_speech_rate"
        private const val KEY_PITCH = "tts_pitch"
        private const val KEY_VOICE_ID = "tts_voice_id"
        private const val KEY_SYSTEM_LANGUAGE_FILTER = "tts_system_language_filter"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.TTS_PREFS_NAME, Context.MODE_PRIVATE)

    fun getCurrentTtsSettings(): TtsSettings {
        return TtsSettings(
            backend = TtsBackend.fromId(prefs.getString(KEY_BACKEND, TtsBackend.SYSTEM.name)),
            speechRate = prefs.getFloat(KEY_SPEECH_RATE, Constants.TTS_DEFAULT_RATE)
                .coerceIn(Constants.TTS_MIN_RATE, Constants.TTS_MAX_RATE),
            pitch = prefs.getFloat(KEY_PITCH, Constants.TTS_DEFAULT_PITCH)
                .coerceIn(Constants.TTS_MIN_PITCH, Constants.TTS_MAX_PITCH),
            voiceId = prefs.getString(KEY_VOICE_ID, null),
            systemLanguageFilter = prefs.getString(KEY_SYSTEM_LANGUAGE_FILTER, null)
        )
    }

    fun saveTtsSettings(settings: TtsSettings) {
        prefs.edit()
            .putString(KEY_BACKEND, settings.backend.name)
            .putFloat(KEY_SPEECH_RATE, settings.speechRate)
            .putFloat(KEY_PITCH, settings.pitch)
            .putString(KEY_VOICE_ID, settings.voiceId)
            .putString(KEY_SYSTEM_LANGUAGE_FILTER, settings.systemLanguageFilter)
            .apply()
    }

    fun updateBackend(backend: TtsBackend) {
        prefs.edit().putString(KEY_BACKEND, backend.name).apply()
    }

    fun updateSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(Constants.TTS_MIN_RATE, Constants.TTS_MAX_RATE)
        prefs.edit().putFloat(KEY_SPEECH_RATE, clamped).apply()
    }

    fun updatePitch(pitch: Float) {
        val clamped = pitch.coerceIn(Constants.TTS_MIN_PITCH, Constants.TTS_MAX_PITCH)
        prefs.edit().putFloat(KEY_PITCH, clamped).apply()
    }

    fun updateVoiceId(voiceId: String?) {
        prefs.edit().apply {
            if (voiceId == null) remove(KEY_VOICE_ID) else putString(KEY_VOICE_ID, voiceId)
        }.apply()
    }

    fun updateSystemLanguageFilter(localeTag: String?) {
        prefs.edit().apply {
            if (localeTag == null) remove(KEY_SYSTEM_LANGUAGE_FILTER)
            else putString(KEY_SYSTEM_LANGUAGE_FILTER, localeTag)
        }.apply()
    }

    fun resetToDefaults() {
        saveTtsSettings(TtsSettings())
    }
}

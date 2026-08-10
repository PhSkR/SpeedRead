package com.speedread.rsvp.tts

import com.speedread.rsvp.Constants

/**
 * TTS/Listen-mode settings. Deliberately kept separate from RsvpSettings so:
 *  - SharedPreferences files can be reset independently,
 *  - changes don't trigger the RSVP engine's updateSettings chunkSize-compare reprocess,
 *  - the pure-JVM rsvp-engine module stays free of Android TTS concepts.
 *
 * @param backend which synthesizer to use
 * @param speechRate multiplier applied to the engine's base rate (1.0 = default, 2.0 = double)
 * @param pitch multiplier applied to base pitch
 * @param voiceId voice identifier within the chosen backend (SYSTEM: android Voice.getName(),
 *        NEURAL: TtsModelRegistry folder name). null = engine default.
 * @param systemLanguageFilter IETF BCP-47 locale tag (e.g. "en-US") used by the Options UI to
 *        narrow the System voice dropdown to one language. null = no filter, show all voices.
 *        Pure UI affordance — does NOT affect synthesis behavior, NativeTtsEngine ignores it.
 *        Only meaningful when [backend] = SYSTEM (Android TTS engines ship 100+ voices spanning
 *        every regional locale; the Neural catalog is small enough that filtering is moot).
 */
data class TtsSettings(
    val backend: TtsBackend = TtsBackend.SYSTEM,
    val speechRate: Float = Constants.TTS_DEFAULT_RATE,
    val pitch: Float = Constants.TTS_DEFAULT_PITCH,
    val voiceId: String? = null,
    val systemLanguageFilter: String? = null
)

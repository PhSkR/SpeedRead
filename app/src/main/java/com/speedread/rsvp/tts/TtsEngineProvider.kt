package com.speedread.rsvp.tts

import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Thin factory over the two TTS backend implementations. Uses Dagger's [Provider] so
 * instances are lazily constructed — the Neural engine's ONNX runtime initializer doesn't
 * pay its cost until the user actually picks Neural.
 *
 * Resolution policy:
 *   - SYSTEM → [NativeTtsEngine] always.
 *   - NEURAL → [NeuralTtsEngine] if it reports [TtsEngine.isAvailable] (i.e. at least one
 *     model dropped into the registry folder); otherwise falls back to the System engine.
 *     Callers that need to distinguish "user wants neural but we downgraded" call
 *     [neuralWouldFallBack] before [resolve] and surface a Snackbar.
 *
 * TtsSettingsRepository writes settings to both [native] and [neural] regardless of which
 * one is currently active, so toggling backends mid-session preserves rate/pitch/voice.
 */
@Singleton
class TtsEngineProvider @Inject constructor(
    private val nativeProvider: Provider<NativeTtsEngine>,
    private val neuralProvider: Provider<NeuralTtsEngine>
) {
    fun native(): TtsEngine = nativeProvider.get()
    fun neural(): TtsEngine = neuralProvider.get()

    /**
     * Returns the engine that should service a play() call for the given backend, applying
     * the NEURAL → SYSTEM safety downgrade.
     */
    fun resolve(backend: TtsBackend): TtsEngine = when (backend) {
        TtsBackend.SYSTEM -> nativeProvider.get()
        TtsBackend.NEURAL -> {
            val n = neuralProvider.get()
            if (n.isAvailable()) n else nativeProvider.get()
        }
    }

    /** True if [resolve] would downgrade NEURAL to SYSTEM because no model is installed. */
    fun neuralWouldFallBack(): Boolean = !neuralProvider.get().isAvailable()
}

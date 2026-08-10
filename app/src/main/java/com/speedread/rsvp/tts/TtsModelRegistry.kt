package com.speedread.rsvp.tts

import android.content.Context
import com.speedread.rsvp.Constants
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers Piper/VITS voice folders that the user has dropped into the app's external files
 * directory. One scan on process start (via SpeedReadApplication.onCreate) and an on-demand
 * rescan when the user opens the Options screen.
 *
 * Folder layout (documented in auto-generated README):
 * ```
 * /Android/data/com.speedread.rsvp/files/tts_models/
 *   README.txt
 *   en_US-lessac-medium/
 *     model.onnx         REQUIRED
 *     tokens.txt         REQUIRED
 *     espeak-ng-data/    REQUIRED for Piper voices using espeak phonemization
 *   en_GB-alan-low/
 *     ...
 * ```
 *
 * Uses getExternalFilesDir so no runtime permission is needed and the directory is cleaned
 * up on uninstall. The folder path resolves identically to any file manager / ADB / MTP view.
 */
@Singleton
class TtsModelRegistry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _voices = MutableStateFlow<List<NeuralVoice>>(emptyList())
    val voices: StateFlow<List<NeuralVoice>> = _voices.asStateFlow()

    /**
     * Absolute path of the model directory — shown in Options so the user can copy-paste
     * it into a file manager. Nullable because getExternalFilesDir can return null if the
     * external storage is unavailable (rare; emulator / missing SD card on older hardware).
     */
    val modelRootDir: File? get() = context.getExternalFilesDir(Constants.TTS_MODEL_DIR_NAME)

    /**
     * Create the model directory + a README.txt with the expected layout if they don't
     * exist yet. Called from SpeedReadApplication.onCreate on a background dispatcher so
     * cold-start isn't blocked. Safe to call repeatedly.
     */
    fun ensureInitialized() {
        ioScope.launch {
            try {
                val root = modelRootDir ?: return@launch
                if (!root.exists()) root.mkdirs()
                val readme = File(root, Constants.TTS_MODEL_README_FILENAME)
                if (!readme.exists()) {
                    readme.writeText(buildReadme())
                }
            } catch (e: Exception) {
                Logger.e(TAG, "ensureInitialized failed", e)
            }
        }
    }

    /**
     * Scan the model directory and update [voices]. Called from the Application bootstrap
     * and from OptionsFragment.onResume so user-dropped models appear without a restart.
     *
     * Skips invalid folders silently (logs at warn) — a partial / broken voice shouldn't
     * prevent the rest of the list from appearing.
     */
    fun rescan() {
        ioScope.launch {
            _voices.value = scanNow()
        }
    }

    private fun scanNow(): List<NeuralVoice> {
        val root = modelRootDir ?: return emptyList()
        if (!root.exists()) return emptyList()
        val children = root.listFiles() ?: return emptyList()

        val discovered = mutableListOf<NeuralVoice>()
        for (child in children) {
            if (!child.isDirectory) continue
            val voice = parseVoiceFolder(child)
            if (voice != null) discovered += voice
        }
        return discovered.sortedBy { it.displayName.lowercase() }
    }

    private fun parseVoiceFolder(folder: File): NeuralVoice? {
        // Two valid Piper voice naming conventions:
        //   1. Manually-dropped folders the user prepared themselves — file is `model.onnx`.
        //   2. Sherpa-ONNX repackaged tarballs (used by the in-app catalog as of 1.14.43) —
        //      file is `<voice-id>.onnx` (e.g. `en_US-amy-low.onnx`). Sherpa renames sample
        //      rate / config files but keeps the model named after the voice id.
        // Accept either: prefer the literal `model.onnx` if present, otherwise the first
        // file that ends in `.onnx` but NOT `.onnx.json` (the Piper config sibling). This
        // makes the manual drop-in path AND the catalog install path both work without
        // forcing the catalog to rename files post-extract.
        val modelFile = locateModelFile(folder) ?: run {
            Logger.w(TAG, "Skipping ${folder.name}: no .onnx model file present")
            return null
        }
        val tokensFile = File(folder, Constants.TTS_NEURAL_REQUIRED_TOKENS_FILE)
        if (!tokensFile.isFile) {
            Logger.w(TAG, "Skipping ${folder.name}: missing tokens.txt")
            return null
        }

        val dataDir = File(folder, Constants.TTS_NEURAL_ESPEAK_DATA_DIR)
            .takeIf { it.isDirectory }?.absolutePath

        // Piper ships a sibling {modelName}.onnx.json config describing sample rate / speaker
        // count. We try to read it but fall back to sane defaults when absent.
        val config = readVoiceConfig(folder)

        return NeuralVoice(
            id = folder.name,
            displayName = prettyName(folder.name),
            modelPath = modelFile.absolutePath,
            tokensPath = tokensFile.absolutePath,
            dataDir = dataDir,
            sampleRate = config?.sampleRate ?: DEFAULT_SAMPLE_RATE,
            speakerCount = config?.speakerCount ?: 1
        )
    }

    /**
     * Find the .onnx model file in a voice folder. Returns the literal `model.onnx` if it
     * exists, otherwise the first sibling that ends in `.onnx` but not `.onnx.json` (which
     * is Piper's audio config, not the model itself). Returns null if nothing matches.
     */
    private fun locateModelFile(folder: File): File? {
        val literal = File(folder, Constants.TTS_NEURAL_REQUIRED_MODEL_FILE)
        if (literal.isFile) return literal
        return folder.listFiles { f ->
            f.isFile && f.name.endsWith(".onnx") && !f.name.endsWith(".onnx.json")
        }?.firstOrNull()
    }

    private data class VoiceConfig(val sampleRate: Int, val speakerCount: Int)

    private fun readVoiceConfig(folder: File): VoiceConfig? {
        // Look for either "{name}.onnx.json" or "config.json" — Piper uses the former.
        val candidates = folder.listFiles { f ->
            f.isFile && (f.name.endsWith(".onnx.json") || f.name == "config.json")
        } ?: return null
        val configFile = candidates.firstOrNull() ?: return null

        return try {
            val json = JSONObject(configFile.readText())
            val audio = json.optJSONObject("audio")
            val sampleRate = audio?.optInt("sample_rate", DEFAULT_SAMPLE_RATE) ?: DEFAULT_SAMPLE_RATE
            val speakerCount = json.optInt("num_speakers", 1).coerceAtLeast(1)
            VoiceConfig(sampleRate, speakerCount)
        } catch (e: Exception) {
            Logger.w(TAG, "Could not parse ${configFile.name}: ${e.message}")
            null
        }
    }

    // "en_US-lessac-medium" -> "En US Lessac Medium"
    private fun prettyName(folderName: String): String =
        folderName.replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .joinToString(" ") { part ->
                if (part.isNotEmpty()) part.replaceFirstChar { it.uppercase() } else part
            }

    private fun buildReadme(): String = """
        SpeedRead Neural TTS voices

        Drop a Piper (or Piper-compatible VITS) voice folder here. Each voice must have:

          {voice-name}/
            *.onnx                REQUIRED   - the neural model. May be named "model.onnx"
                                               OR "<voice-name>.onnx" (Sherpa-ONNX repackaged
                                               Piper tarballs use the latter).
            tokens.txt            REQUIRED   - token vocabulary
            espeak-ng-data/       REQUIRED   - phonemization tables (for Piper voices)
            {name}.onnx.json      OPTIONAL   - audio config (sample rate, speaker count)

        Voices auto-discover on app start. To rescan without restarting, open the Options
        screen — or use the in-app catalog (Options → Listening → Browse voice catalog),
        which downloads + extracts a chosen voice into the layout above for you.

        Piper voices can be downloaded from:
          - In-app: Options → Listening → Browse voice catalog (1.14.43+).
          - Manual: https://huggingface.co/rhasspy/piper-voices or
                    https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models

        Folder path resolved on this device:
          ${modelRootDir?.absolutePath ?: "(unavailable)"}
    """.trimIndent()

    companion object {
        private const val TAG = "TtsModelRegistry"
        private const val DEFAULT_SAMPLE_RATE = 22050
    }
}

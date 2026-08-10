package com.speedread.rsvp.tts.catalog

import com.speedread.rsvp.Constants
import kotlinx.serialization.Serializable

/**
 * One Piper voice as listed in the Sherpa-ONNX `tts-models` GitHub release. Built from a
 * GitHub Releases API asset entry — see [VoiceCatalogClient.parseAssets].
 *
 * @param assetName e.g. `vits-piper-en_US-lessac-medium.tar.bz2` — also drives the install
 *   folder name (asset minus prefix `vits-piper-` and suffix `.tar.bz2`), which is what
 *   [com.speedread.rsvp.tts.TtsModelRegistry] uses as the [com.speedread.rsvp.tts.NeuralVoice.id].
 * @param downloadUrl direct browser_download_url from the GitHub asset.
 * @param sizeBytes asset size from the API; used to decide "update available" against
 *   the recorded install metadata, and to render a "X MB" label in the UI.
 * @param remoteUpdatedAt asset.updated_at ISO-8601 string — second update-detection signal
 *   in case Sherpa republishes a voice with the same byte count.
 * @param languageCode parsed from the asset name (e.g. `en_US`). Empty string when parsing
 *   failed, which keeps the entry visible under the "Other" group rather than dropping it.
 * @param voiceName parsed from the asset name (e.g. `lessac`). Same fallback behavior.
 * @param quality parsed from the asset name tail (`low`, `medium`, `high`, `x_low`).
 */
@Serializable
data class VoiceCatalogEntry(
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val remoteUpdatedAt: String,
    val languageCode: String,
    val voiceName: String,
    val quality: String
) {
    /**
     * Folder name used under `tts_models/`. Stable across re-fetches because it is derived
     * purely from the asset name. Matches [com.speedread.rsvp.tts.NeuralVoice.id].
     */
    val voiceId: String
        get() = assetName
            .removePrefix(Constants.VOICE_CATALOG_ASSET_PREFIX)
            .removeSuffix(Constants.VOICE_CATALOG_ASSET_SUFFIX)

    /** Pretty label for the row, e.g. `Lessac (medium)`. Falls back to the raw asset id. */
    val displayName: String
        get() {
            if (voiceName.isEmpty()) return voiceId
            val base = voiceName.replaceFirstChar { it.uppercase() }
            return if (quality.isNotEmpty()) "$base ($quality)" else base
        }
}

/**
 * Persisted next to each installed voice as `.voice_metadata.json`. Read on next launch to
 * compute [VoiceInstallStatus.UpdateAvailable]. Written immediately after a successful
 * install / update so a process kill mid-install leaves no metadata (the partial folder
 * itself is removed before the metadata write).
 */
@Serializable
data class VoiceInstallMetadata(
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val remoteUpdatedAt: String,
    /** Epoch ms when the install completed. Informational only; not used for diffing. */
    val installedAt: Long
)

/** Top-level state of the catalog list itself (the result of one HTTP fetch). */
sealed interface CatalogState {
    data object Loading : CatalogState
    data class Loaded(val entries: List<VoiceCatalogEntry>) : CatalogState
    data class Error(val message: String) : CatalogState
}

/**
 * Per-voice install status, computed by joining the catalog with the on-disk registry plus
 * any in-flight downloads. Drives the rendered button for each row.
 */
sealed interface VoiceInstallStatus {
    data object NotInstalled : VoiceInstallStatus
    data class Downloading(val progressPercent: Int) : VoiceInstallStatus
    data object Extracting : VoiceInstallStatus
    data object Installed : VoiceInstallStatus
    data object UpdateAvailable : VoiceInstallStatus
    data class Failed(val message: String) : VoiceInstallStatus
}

/** Render-ready bundle for the RecyclerView adapter. */
data class VoiceCatalogRow(
    val entry: VoiceCatalogEntry,
    val status: VoiceInstallStatus
)

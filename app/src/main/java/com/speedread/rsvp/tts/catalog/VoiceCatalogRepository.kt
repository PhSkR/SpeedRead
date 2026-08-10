package com.speedread.rsvp.tts.catalog

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.speedread.rsvp.Constants
import com.speedread.rsvp.tts.NeuralVoice
import com.speedread.rsvp.tts.TtsModelRegistry
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the in-app voice catalog lifecycle: list → install (DownloadManager + tar.bz2
 * extract) → uninstall (folder delete) → update (compare cached metadata to catalog entry).
 *
 * Concurrency model:
 * - One catalog StateFlow shared by all observers; refresh is a suspend call.
 * - Per-voice transient status (downloading / extracting / failed) lives in a Map<voiceId,
 *   VoiceInstallStatus> StateFlow. The terminal Installed / UpdateAvailable / NotInstalled
 *   states are computed on the fly by joining catalog + registry.voices + on-disk metadata,
 *   so they survive process restart without any state to persist beyond the metadata file.
 * - Active downloads are tracked in [activeDownloads] (voiceId → DownloadManager id) under a
 *   mutex so cancel-by-voiceId can find and remove the right one.
 *
 * Background work uses [Dispatchers.IO] coroutines exclusively — same pattern as
 * [TtsModelRegistry]. No WorkManager. If the user backgrounds the app mid-download, the
 * DownloadManager keeps the network transfer running (system service); only the post-download
 * extraction would die with the process. In that case the next foreground enqueue retries
 * the install from scratch — DownloadManager's per-request id is local, not durable across
 * process kills, so we don't try to resume.
 */
@Singleton
class VoiceCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: VoiceCatalogClient,
    private val extractor: TarBz2Extractor,
    private val registry: TtsModelRegistry,
    private val downloadManager: DownloadManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _catalogState = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val catalogState: StateFlow<CatalogState> = _catalogState.asStateFlow()

    private val _transientStatus = MutableStateFlow<Map<String, VoiceInstallStatus>>(emptyMap())
    /** Per-voice in-flight or just-failed status; absent voices fall back to the on-disk view. */
    val transientStatus: StateFlow<Map<String, VoiceInstallStatus>> = _transientStatus.asStateFlow()

    private val mutex = Mutex()
    private val activeDownloads = mutableMapOf<String, Long>()
    private val activeJobs = mutableMapOf<String, Job>()

    /**
     * Triggers an HTTP fetch (or returns cached). Updates [catalogState] before returning.
     *
     * If [forceRefresh] is false AND a Loaded snapshot is already in memory, this is a no-op.
     * That guard matters because [VoiceCatalogViewModel] re-fires `refreshCatalog(false)` from
     * its `init {}` on every fragment recreate; without the guard we'd flash through Loading
     * → Loaded on every back-stack pop even though the data hasn't changed.
     */
    suspend fun refreshCatalog(forceRefresh: Boolean = false) {
        if (!forceRefresh && _catalogState.value is CatalogState.Loaded) return
        _catalogState.value = CatalogState.Loading
        try {
            val entries = client.fetchCatalog(forceRefresh)
            _catalogState.value = CatalogState.Loaded(entries)
        } catch (e: Exception) {
            Logger.w(TAG, "refreshCatalog failed: ${e.message}")
            _catalogState.value = CatalogState.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Compute the user-facing status for one catalog entry. Order of precedence:
     * 1. Transient (Downloading / Extracting / Failed) overrides everything — that's the
     *    in-flight truth.
     * 2. On-disk: voice exists AND metadata says it matches the catalog → Installed.
     * 3. On-disk: voice exists but metadata is missing OR differs from catalog → UpdateAvailable.
     * 4. Otherwise → NotInstalled.
     */
    fun computeStatus(
        entry: VoiceCatalogEntry,
        installedVoices: List<NeuralVoice>,
        transient: Map<String, VoiceInstallStatus>
    ): VoiceInstallStatus {
        transient[entry.voiceId]?.let { return it }

        val installed = installedVoices.any { it.id == entry.voiceId }
        if (!installed) return VoiceInstallStatus.NotInstalled

        val metadata = readInstallMetadata(entry.voiceId)
            ?: return VoiceInstallStatus.UpdateAvailable
        val sizeMatches = metadata.sizeBytes == entry.sizeBytes
        val timestampMatches = metadata.remoteUpdatedAt == entry.remoteUpdatedAt
        return if (sizeMatches && timestampMatches) {
            VoiceInstallStatus.Installed
        } else {
            VoiceInstallStatus.UpdateAvailable
        }
    }

    /**
     * Enqueue a DownloadManager request for [entry]. Returns immediately; progress is reported
     * via [transientStatus]. Safe to call repeatedly: if a download is already active for the
     * same voiceId, this is a no-op.
     */
    suspend fun install(entry: VoiceCatalogEntry) = mutex.withLock {
        val voiceId = entry.voiceId
        if (activeDownloads.containsKey(voiceId)) {
            Logger.w(TAG, "install($voiceId) ignored; download already active")
            return@withLock
        }
        clearTransientFor(voiceId)
        val stagingFile = stagingFileFor(voiceId)
        // Stale partial from a prior aborted run — DownloadManager refuses to overwrite, so
        // we evict before requesting.
        if (stagingFile.exists()) stagingFile.delete()
        stagingFile.parentFile?.mkdirs()

        val request = DownloadManager.Request(Uri.parse(entry.downloadUrl))
            .setTitle(entry.displayName)
            .setDescription(context.getString(
                com.speedread.rsvp.R.string.voice_catalog_status_downloading, 0
            ))
            .setDestinationInExternalFilesDir(
                context,
                Constants.VOICE_CATALOG_DOWNLOAD_STAGING_DIR,
                "$voiceId${Constants.VOICE_CATALOG_ASSET_SUFFIX}"
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadId = downloadManager.enqueue(request)
        activeDownloads[voiceId] = downloadId
        setTransient(voiceId, VoiceInstallStatus.Downloading(0))

        activeJobs[voiceId] = scope.launch {
            runInstallPipeline(entry, downloadId, stagingFile)
        }
    }

    /**
     * Cancel an in-flight install. Removes the DownloadManager request, cancels the polling
     * coroutine, awaits its finally block, and clears UI state.
     *
     * Critical: we release the mutex BEFORE joining the cancelled job. The cancelled
     * coroutine's finally block reacquires the mutex (`mutex.withLock { activeJobs.remove }`),
     * so holding it across the join would deadlock. Joining ensures the old pipeline has
     * fully unwound before this returns — without that, a rapid cancel→reinstall could
     * cause the OLD finally to fire AFTER the NEW install has populated activeJobs /
     * staging, removing the new entry / deleting the new staging file.
     */
    suspend fun cancelInstall(voiceId: String) {
        val pair = mutex.withLock {
            val downloadId = activeDownloads.remove(voiceId)
            val job = activeJobs.remove(voiceId)
            // Set transient null INSIDE the lock so the old pipeline can't still set it
            // post-cancel from a racing setTransient call between cancel() and join().
            clearTransientFor(voiceId)
            Pair(job, downloadId)
        }
        val (job, downloadId) = pair

        job?.cancel()
        try { job?.join() } catch (_: CancellationException) {}

        if (downloadId != null) {
            try { downloadManager.remove(downloadId) } catch (e: Exception) {
                Logger.w(TAG, "downloadManager.remove failed: ${e.message}")
            }
        }
        try { stagingFileFor(voiceId).delete() } catch (_: Exception) {}
        // clearTransient again in case the cancelled coroutine's finally re-emitted between
        // the in-mutex clear above and the join completing (would only happen on a partial
        // race — second clear is idempotent).
        clearTransientFor(voiceId)
    }

    /** Delete the voice folder and refresh the registry so the dropdown updates. */
    suspend fun uninstall(voiceId: String) = withContext(Dispatchers.IO) {
        val voiceDir = voiceDirFor(voiceId) ?: return@withContext
        if (voiceDir.exists()) {
            voiceDir.deleteRecursively()
        }
        registry.rescan()
    }

    private suspend fun runInstallPipeline(
        entry: VoiceCatalogEntry,
        downloadId: Long,
        stagingFile: File
    ) {
        val voiceId = entry.voiceId
        // Identity-tag this coroutine so the cleanup block won't clobber a NEWER install's
        // activeJobs entry on a rapid cancel→reinstall cycle. Without this guard the old
        // finally's `activeJobs.remove(voiceId)` would remove the new install's job, leaving
        // cancelInstall with nothing to find next time.
        val myJob = currentCoroutineContext()[Job]
        try {
            val outcome = pollDownload(downloadId, voiceId)
            mutex.withLock { activeDownloads.remove(voiceId) }
            when (outcome) {
                is DownloadOutcome.Success -> {
                    setTransient(voiceId, VoiceInstallStatus.Extracting)
                    extractAndRegister(entry, stagingFile)
                    // After extract: clear transient so computeStatus picks up Installed
                    // from the on-disk metadata + registry.
                    clearTransientFor(voiceId)
                }
                is DownloadOutcome.Failed -> {
                    setTransient(voiceId, VoiceInstallStatus.Failed(outcome.reason))
                }
                DownloadOutcome.Cancelled -> {
                    clearTransientFor(voiceId)
                }
            }
        } catch (e: CancellationException) {
            // User-driven cancel via cancelInstall(). cancelInstall already cleared the
            // transient and removed the entries from activeDownloads / activeJobs, so the
            // only thing left is to honour the cancel and exit. Rethrowing keeps coroutine
            // semantics consistent for any upstream observers.
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Install pipeline crashed for $voiceId", e)
            setTransient(voiceId, VoiceInstallStatus.Failed(e.message ?: "install failed"))
        } finally {
            // NonCancellable so the cleanup runs even when this coroutine was just cancelled
            // — otherwise activeJobs would carry a stale entry. Note: stagingFile.delete()
            // is INTENTIONALLY not here. It runs in cancelInstall's cleanup (cancel path) or
            // would fight with a rapid-reinstall's enqueue (success/failure path). The
            // file's life is bounded by either branch above or by cancelInstall.
            withContext(NonCancellable) {
                mutex.withLock {
                    // Identity check: only remove if we're still the owning entry. A
                    // rapid cancel→reinstall cycle could have replaced activeJobs[voiceId]
                    // with a newer Job between our cancellation and this finally running.
                    if (activeJobs[voiceId] === myJob) {
                        activeJobs.remove(voiceId)
                    }
                }
                // Best-effort staging cleanup on the SUCCESS / non-cancelled-failure paths
                // only. On cancellation, cancelInstall handles staging deletion. We detect
                // "non-cancelled" via `coroutineContext.isActive`-equivalent — if we got
                // here without a CancellationException pending, we own the staging file.
                if (myJob?.isCancelled != true) {
                    try { stagingFile.delete() } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun pollDownload(downloadId: Long, voiceId: String): DownloadOutcome {
        while (true) {
            // querySnapshot is a synchronous Binder/cursor read with no suspension point, so
            // a cancellation request that lands between two delays could otherwise be missed
            // for one full cursor read. Explicit ensureActive at loop top closes that window.
            currentCoroutineContext().ensureActive()
            val snapshot = querySnapshot(downloadId) ?: return DownloadOutcome.Failed("download missing")
            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> return DownloadOutcome.Success
                DownloadManager.STATUS_FAILED -> {
                    return if (snapshot.reason == DownloadManager.ERROR_CANNOT_RESUME ||
                        snapshot.reason == DownloadManager.ERROR_DEVICE_NOT_FOUND ||
                        snapshot.reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS ||
                        snapshot.reason == DownloadManager.ERROR_FILE_ERROR ||
                        snapshot.reason == DownloadManager.ERROR_HTTP_DATA_ERROR ||
                        snapshot.reason == DownloadManager.ERROR_INSUFFICIENT_SPACE ||
                        snapshot.reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS ||
                        snapshot.reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE ||
                        snapshot.reason == DownloadManager.ERROR_UNKNOWN
                    ) {
                        DownloadOutcome.Failed(failureReason(snapshot.reason))
                    } else {
                        // ERROR_CANNOT_RESUME etc. is a real failure; anything else (e.g. user
                        // cancellation via downloadManager.remove) shows up here too with
                        // reason 0, which we treat as cancelled.
                        DownloadOutcome.Cancelled
                    }
                }
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> {
                    val progressPercent = if (snapshot.totalBytes > 0L) {
                        ((snapshot.bytesSoFar * 100L) / snapshot.totalBytes).toInt().coerceIn(0, 100)
                    } else 0
                    setTransient(voiceId, VoiceInstallStatus.Downloading(progressPercent))
                    delay(POLL_INTERVAL_MS)
                }
                else -> {
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun querySnapshot(downloadId: Long): DownloadSnapshot? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        var cursor: Cursor? = null
        return try {
            cursor = downloadManager.query(query)
            if (cursor == null || !cursor.moveToFirst()) return null
            val statusCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val reasonCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val soFarCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            DownloadSnapshot(
                status = cursor.getInt(statusCol),
                reason = cursor.getInt(reasonCol),
                bytesSoFar = cursor.getLong(soFarCol),
                totalBytes = cursor.getLong(totalCol)
            )
        } catch (e: Exception) {
            Logger.w(TAG, "querySnapshot failed: ${e.message}")
            null
        } finally {
            cursor?.close()
        }
    }

    private suspend fun extractAndRegister(entry: VoiceCatalogEntry, stagingFile: File) {
        val voiceDir = voiceDirFor(entry.voiceId)
            ?: throw IllegalStateException("External files dir unavailable")
        // Replace any prior install of this voice with a fresh extract — handles the update
        // path where the new tarball may have a different file set than the old one.
        if (voiceDir.exists()) voiceDir.deleteRecursively()
        voiceDir.mkdirs()

        try {
            extractor.extract(stagingFile, voiceDir)
            writeInstallMetadata(entry)
            registry.rescan()

            // Wait for registry.voices to reflect the new install before we return. Without
            // this the caller would clearTransientFor(voiceId) before the registry's
            // StateFlow has re-emitted, causing the row to flicker through NotInstalled
            // (transient cleared, registry not yet updated) before settling on Installed.
            // The timeout is a safety net for the (rare) case where the new folder fails
            // registry validation — clearing the transient anyway is correct, the row just
            // lands on NotInstalled which is accurate.
            withTimeoutOrNull(REGISTRY_OBSERVE_TIMEOUT_MS) {
                registry.voices.first { voices -> voices.any { it.id == entry.voiceId } }
            }
        } catch (e: Throwable) {
            // Wipe the partially-extracted folder so a retry starts from a clean state and
            // the registry doesn't pick up a half-voice. Runs on success-path failures
            // (extract crash, IO error) AND on cancellation — withContext(NonCancellable)
            // ensures the cleanup completes even if the surrounding job has been cancelled.
            withContext(NonCancellable) {
                try { voiceDir.deleteRecursively() } catch (_: Exception) {}
                registry.rescan()
            }
            throw e
        }
    }

    private fun writeInstallMetadata(entry: VoiceCatalogEntry) {
        val voiceDir = voiceDirFor(entry.voiceId) ?: return
        val metadata = VoiceInstallMetadata(
            assetName = entry.assetName,
            downloadUrl = entry.downloadUrl,
            sizeBytes = entry.sizeBytes,
            remoteUpdatedAt = entry.remoteUpdatedAt,
            installedAt = System.currentTimeMillis()
        )
        try {
            File(voiceDir, Constants.VOICE_INSTALL_METADATA_FILENAME)
                .writeText(json.encodeToString(VoiceInstallMetadata.serializer(), metadata))
        } catch (e: Exception) {
            Logger.w(TAG, "writeInstallMetadata failed: ${e.message}")
        }
    }

    private fun readInstallMetadata(voiceId: String): VoiceInstallMetadata? {
        val voiceDir = voiceDirFor(voiceId) ?: return null
        val file = File(voiceDir, Constants.VOICE_INSTALL_METADATA_FILENAME)
        if (!file.isFile) return null
        return try {
            json.decodeFromString(VoiceInstallMetadata.serializer(), file.readText())
        } catch (e: Exception) {
            Logger.w(TAG, "readInstallMetadata failed: ${e.message}")
            null
        }
    }

    private fun voiceDirFor(voiceId: String): File? {
        val root = registry.modelRootDir ?: return null
        return File(root, voiceId)
    }

    private fun stagingFileFor(voiceId: String): File {
        val staging = context.getExternalFilesDir(Constants.VOICE_CATALOG_DOWNLOAD_STAGING_DIR)
            ?: File(context.cacheDir, Constants.VOICE_CATALOG_DOWNLOAD_STAGING_DIR)
        return File(staging, "$voiceId${Constants.VOICE_CATALOG_ASSET_SUFFIX}")
    }

    private fun setTransient(voiceId: String, status: VoiceInstallStatus) {
        _transientStatus.update { it + (voiceId to status) }
    }

    private fun clearTransientFor(voiceId: String) {
        _transientStatus.update { it - voiceId }
    }

    private fun failureReason(code: Int): String = when (code) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "not enough space"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "network error"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
        DownloadManager.ERROR_CANNOT_RESUME -> "cannot resume"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "external storage unavailable"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "stale download file"
        DownloadManager.ERROR_FILE_ERROR -> "file write error"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "server error"
        else -> "download failed"
    }

    private data class DownloadSnapshot(
        val status: Int,
        val reason: Int,
        val bytesSoFar: Long,
        val totalBytes: Long
    )

    private sealed interface DownloadOutcome {
        data object Success : DownloadOutcome
        data object Cancelled : DownloadOutcome
        data class Failed(val reason: String) : DownloadOutcome
    }

    companion object {
        private const val TAG = "VoiceCatalogRepo"
        private const val POLL_INTERVAL_MS = 500L
        private const val REGISTRY_OBSERVE_TIMEOUT_MS = 5_000L
    }
}

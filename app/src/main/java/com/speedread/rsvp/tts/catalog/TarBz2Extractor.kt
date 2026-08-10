package com.speedread.rsvp.tts.catalog

import com.speedread.rsvp.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Streams a `.tar.bz2` Sherpa-ONNX Piper voice tarball into a target directory. The tarballs
 * always contain a single top-level folder (e.g. `vits-piper-en_US-amy-medium/`) wrapping
 * the model + tokens + espeak data; this extractor strips that prefix so files land directly
 * under [targetDir]. The end shape (`model.onnx`, `tokens.txt`, `espeak-ng-data/`,
 * `<id>.onnx.json`) is exactly what [com.speedread.rsvp.tts.TtsModelRegistry.scanNow] expects.
 *
 * Tar entries pointing outside [targetDir] (e.g. `../../etc/passwd`) are rejected silently —
 * "tar-slip" protection. The check is a canonical-path prefix comparison so symlinks inside
 * the archive cannot dodge it either.
 */
@Singleton
class TarBz2Extractor @Inject constructor() {

    /**
     * Extracts [source] into [targetDir]. Caller is responsible for clearing [targetDir]
     * first if a fresh extraction is desired. Cancellation-aware via the surrounding
     * coroutine context — interrupting mid-extract leaves a partial directory tree which
     * the orchestrator (VoiceCatalogRepository) is expected to delete.
     */
    suspend fun extract(source: File, targetDir: File): Unit = withContext(Dispatchers.IO) {
        require(source.isFile) { "Source archive missing: ${source.absolutePath}" }
        if (targetDir.exists() && !targetDir.isDirectory) {
            throw IOException("Target path is not a directory: ${targetDir.absolutePath}")
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Could not create target directory: ${targetDir.absolutePath}")
        }

        val baseCanonical = targetDir.canonicalFile

        BZip2CompressorInputStream(BufferedInputStream(FileInputStream(source))).use { bzipIn ->
            TarArchiveInputStream(bzipIn).use { tarIn ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = tarIn.nextEntry ?: break
                    val stripped = stripTopLevelComponent(entry.name)
                    if (stripped.isEmpty()) continue

                    val resolved = File(baseCanonical, stripped).canonicalFile
                    if (!isUnderBase(resolved, baseCanonical)) {
                        Logger.w(TAG, "Rejecting tar entry outside target dir: ${entry.name}")
                        continue
                    }

                    if (entry.isDirectory) {
                        if (!resolved.exists() && !resolved.mkdirs()) {
                            Logger.w(TAG, "mkdirs failed for ${resolved.absolutePath}")
                        }
                        continue
                    }

                    resolved.parentFile?.mkdirs()
                    FileOutputStream(resolved).use { out ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = tarIn.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
        }
    }

    /**
     * Drops the first path component of a tar entry name (e.g. `vits-piper-en_US-amy-low/
     * model.onnx` → `model.onnx`). Sherpa's Piper voice tarballs are always wrapped in a
     * single top-level folder; entries with no `/` are floor-level files we don't expect and
     * skip rather than writing to the target root (where they could theoretically clash with
     * legitimate files like `model.onnx`).
     *
     * Path traversal entries (`..`, leading `/`) aren't sanitized here — we leave that to
     * the canonical-path containment check in [isUnderBase]. Stripping them here would only
     * give the illusion of safety; the real defense is "after resolution, must be under
     * targetDir". Returning the path verbatim post-strip keeps that contract straightforward.
     */
    private fun stripTopLevelComponent(path: String): String {
        val firstSlash = path.indexOf('/')
        return if (firstSlash < 0) "" else path.substring(firstSlash + 1)
    }

    private fun isUnderBase(candidate: File, base: File): Boolean {
        val candidatePath = candidate.path
        val basePath = base.path
        if (candidatePath == basePath) return true
        return candidatePath.startsWith(basePath + File.separator)
    }

    companion object {
        private const val TAG = "TarBz2Extractor"
        private const val BUFFER_SIZE = 32 * 1024
    }
}

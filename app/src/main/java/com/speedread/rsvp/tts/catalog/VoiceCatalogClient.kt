package com.speedread.rsvp.tts.catalog

import android.content.Context
import com.speedread.rsvp.Constants
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches and parses the Piper voice catalog from Sherpa-ONNX's `tts-models` GitHub release.
 * Caches the parsed list to [Context.cacheDir] so a transient offline state on relaunch
 * still surfaces the previously-known catalog instead of a blank screen.
 *
 * Network is intentionally minimal: no OkHttp / Ktor — a single GET on app open is well
 * within `HttpURLConnection`'s comfort zone, and the existing app does not yet have a
 * shared HTTP client to reuse (URL importer uses JSoup, which is overkill for a JSON GET).
 */
@Singleton
class VoiceCatalogClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Fetch the catalog. If [forceRefresh] is false and a cached parse newer than
     * [Constants.VOICE_CATALOG_CACHE_TTL_MS] exists, returns it without hitting the network.
     * On any failure (network, parse, JSON shape change), returns the most recent cached
     * snapshot if one exists, otherwise rethrows.
     */
    suspend fun fetchCatalog(forceRefresh: Boolean = false): List<VoiceCatalogEntry> = withContext(Dispatchers.IO) {
        val cacheFile = catalogCacheFile()
        if (!forceRefresh && isCacheFresh(cacheFile)) {
            readCache(cacheFile)?.let { return@withContext it }
        }

        try {
            val freshEntries = fetchFromNetwork()
            writeCache(cacheFile, freshEntries)
            freshEntries
        } catch (e: Exception) {
            Logger.w(TAG, "Network fetch failed (${e.message}); falling back to cache if present")
            readCache(cacheFile)
                ?: throw e
        }
    }

    private fun fetchFromNetwork(): List<VoiceCatalogEntry> {
        val url = URL(Constants.VOICE_CATALOG_RELEASES_API_URL)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = Constants.VOICE_CATALOG_HTTP_CONNECT_TIMEOUT_MS
            readTimeout = Constants.VOICE_CATALOG_HTTP_READ_TIMEOUT_MS
            setRequestProperty("User-Agent", Constants.VOICE_CATALOG_USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("GitHub API returned HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val release = json.decodeFromString(GitHubRelease.serializer(), body)
            return parseAssets(release.assets)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAssets(assets: List<GitHubAsset>): List<VoiceCatalogEntry> {
        return assets.mapNotNull { asset -> toEntry(asset) }
            .sortedWith(compareBy({ it.languageCode }, { it.voiceName }, { it.quality }))
    }

    private fun toEntry(asset: GitHubAsset): VoiceCatalogEntry? {
        val name = asset.name
        if (!name.startsWith(Constants.VOICE_CATALOG_ASSET_PREFIX)) return null
        if (!name.endsWith(Constants.VOICE_CATALOG_ASSET_SUFFIX)) return null

        val core = name
            .removePrefix(Constants.VOICE_CATALOG_ASSET_PREFIX)
            .removeSuffix(Constants.VOICE_CATALOG_ASSET_SUFFIX)
        val parts = core.split('-')
        if (parts.size < 3) {
            // Pattern is `{lang}-{voice}-{quality}` for every Sherpa Piper asset to date —
            // anything shorter is a non-conforming entry we'd rather skip than mis-classify.
            return null
        }

        val languageCode = parts.first()
        val quality = parts.last()
        val voiceName = parts.subList(1, parts.size - 1).joinToString("-")

        return VoiceCatalogEntry(
            assetName = name,
            downloadUrl = asset.browserDownloadUrl,
            sizeBytes = asset.size,
            remoteUpdatedAt = asset.updatedAt,
            languageCode = languageCode,
            voiceName = voiceName,
            quality = quality
        )
    }

    private fun catalogCacheFile(): File =
        File(context.cacheDir, Constants.VOICE_CATALOG_CACHE_FILENAME)

    private fun isCacheFresh(file: File): Boolean {
        if (!file.isFile) return false
        val ageMs = System.currentTimeMillis() - file.lastModified()
        return ageMs in 0..Constants.VOICE_CATALOG_CACHE_TTL_MS
    }

    private fun readCache(file: File): List<VoiceCatalogEntry>? {
        if (!file.isFile) return null
        return try {
            json.decodeFromString(CatalogCache.serializer(), file.readText()).entries
        } catch (e: Exception) {
            Logger.w(TAG, "Catalog cache unreadable, ignoring: ${e.message}")
            null
        }
    }

    private fun writeCache(file: File, entries: List<VoiceCatalogEntry>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(CatalogCache.serializer(), CatalogCache(entries)))
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to write catalog cache: ${e.message}")
        }
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String? = null,
        val assets: List<GitHubAsset> = emptyList()
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        val size: Long,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        @SerialName("updated_at") val updatedAt: String = ""
    )

    @Serializable
    private data class CatalogCache(val entries: List<VoiceCatalogEntry>)

    companion object {
        private const val TAG = "VoiceCatalogClient"
    }
}

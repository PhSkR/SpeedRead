package com.speedread.rsvp

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PageViewTokenCache @Inject constructor(
    val store: PageViewTokenCacheStore
) {

    data class CacheKey(
        val contentIdentity: Int,
        val contentHash: String?,
        val enableParagraphSpacing: Boolean
    )

    class CachedTokenData(
        val tokenizedWords: List<String>,
        val continuousJoined: String,
        val wordCharStarts: IntArray
    )

    class CachedPageData(
        val pages: List<DocumentPage>,
        val hasBoundaries: Boolean
    )

    private var key: CacheKey? = null
    private var tokenData: CachedTokenData? = null
    private var pageData: CachedPageData? = null

    fun getTokenData(cacheKey: CacheKey): CachedTokenData? =
        if (key == cacheKey) tokenData else null

    fun putTokenData(cacheKey: CacheKey, data: CachedTokenData) {
        key = cacheKey
        tokenData = data
        pageData = null
    }

    fun getPageData(cacheKey: CacheKey, hasBoundaries: Boolean): CachedPageData? {
        val cached = pageData ?: return null
        return if (key == cacheKey && cached.hasBoundaries == hasBoundaries) cached else null
    }

    fun putPageData(data: CachedPageData) {
        pageData = data
    }

    fun invalidateInMemory() {
        key = null
        tokenData = null
        pageData = null
    }
}

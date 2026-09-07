package com.speedread.rsvp.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlImportConstantsTest {

    @Test
    fun `default user agent contains current version`() {
        assertEquals("1.14.73", UrlImportConstants.DEFAULT_APP_VERSION)
        assertEquals(
            "Mozilla/5.0 (Android; SpeedRead/1.14.73) AppleWebKit/537.36",
            UrlImportConstants.USER_AGENT
        )
    }

    @Test
    fun `buildUserAgent formats custom version correctly`() {
        assertEquals(
            "Mozilla/5.0 (Android; SpeedRead/2.0.0) AppleWebKit/537.36",
            UrlImportConstants.buildUserAgent("2.0.0")
        )
    }

    @Test
    fun `allowed schemes include http and https`() {
        assertTrue(UrlImportConstants.ALLOWED_SCHEMES.contains("http"))
        assertTrue(UrlImportConstants.ALLOWED_SCHEMES.contains("https"))
    }
}

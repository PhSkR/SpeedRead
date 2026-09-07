package com.speedread.rsvp.data.parser

object UrlImportConstants {
    const val READ_TIMEOUT_MS = 30_000

    // Peak memory during extraction ~= MAX_RESPONSE_BYTES * 3 (raw bytes +
    // UTF-16 decoded String + Readability4J DOM). 5 MB keeps the peak well
    // inside a low-end device's budget while covering every realistic
    // article body (typical HTML < 500 KB).
    const val MAX_RESPONSE_BYTES = 5_242_880

    const val DEFAULT_APP_VERSION = "1.14.73"

    fun buildUserAgent(appVersion: String = DEFAULT_APP_VERSION): String =
        "Mozilla/5.0 (Android; SpeedRead/$appVersion) AppleWebKit/537.36"

    val USER_AGENT: String = buildUserAgent()

    val ALLOWED_SCHEMES = setOf("http", "https")
}

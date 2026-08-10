package com.speedread.rsvp.data.parser

object UrlImportConstants {
    const val READ_TIMEOUT_MS = 30_000

    // Peak memory during extraction ~= MAX_RESPONSE_BYTES * 3 (raw bytes +
    // UTF-16 decoded String + Readability4J DOM). 5 MB keeps the peak well
    // inside a low-end device's budget while covering every realistic
    // article body (typical HTML < 500 KB).
    const val MAX_RESPONSE_BYTES = 5_242_880

    const val USER_AGENT =
        "Mozilla/5.0 (Android; SpeedRead/1.13.25) AppleWebKit/537.36"

    val ALLOWED_SCHEMES = setOf("http", "https")
}

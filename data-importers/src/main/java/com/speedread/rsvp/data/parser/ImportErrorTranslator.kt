package com.speedread.rsvp.data.parser

/**
 * Translates low-level import exceptions (often coming from cloud storage providers
 * like Google Drive, OneDrive, Dropbox) into user-actionable messages.
 *
 * Cloud providers expose files via content:// URIs whose bytes are fetched on demand.
 * When the fetch fails the provider surfaces an internal, user-hostile error string
 * (e.g. Google Drive's "Cello error 2" = ENOENT from its native storage library).
 * This translator centralizes detection of those patterns so every parser / provider
 * surfaces the same friendly message.
 */
object ImportErrorTranslator {

    // Known native error signatures from cloud providers.
    private const val SIG_GOOGLE_DRIVE_CELLO = "Cello error"
    private const val SIG_DRIVE_DOCS_PROVIDER = "com.google.android.apps.docs"
    private const val SIG_ONEDRIVE_PROVIDER = "com.microsoft.skydrive"
    private const val SIG_DROPBOX_PROVIDER = "com.dropbox.android"

    // URL-import network / HTTP error signatures (matched against the full exception
    // chain's message + class-name concatenation built by translate()).
    private const val SIG_UNKNOWN_HOST = "UnknownHostException"
    private const val SIG_SOCKET_TIMEOUT = "SocketTimeoutException"
    private const val SIG_SSL = "SSLException"
    private const val SIG_SSL_HANDSHAKE = "SSLHandshakeException"
    private const val SIG_JSOUP_HTTP_STATUS = "HttpStatusException"
    private const val SIG_MALFORMED_URL = "MalformedURLException"
    private const val SIG_INVALID_SCHEME = "invalid scheme"

    // User-facing messages.
    const val MSG_DRIVE_UNREACHABLE =
        "Can't open this Google Drive file. Make it available offline in Drive, or download it first, then try again."
    const val MSG_ONEDRIVE_UNREACHABLE =
        "Can't open this OneDrive file. Make sure it's downloaded to the device, then try again."
    const val MSG_DROPBOX_UNREACHABLE =
        "Can't open this Dropbox file. Make sure it's available offline, then try again."
    const val MSG_CLOUD_GENERIC_UNREACHABLE =
        "Can't open this cloud file. Make sure it's downloaded to the device, then try again."

    // URL-import user-facing messages.
    const val MSG_URL_UNKNOWN_HOST =
        "Couldn't reach that site. Check the URL and your connection."
    const val MSG_URL_TIMEOUT =
        "The site took too long to respond. Try again later."
    const val MSG_URL_SSL =
        "Secure connection to the site failed."
    const val MSG_URL_INVALID_SCHEME =
        "That doesn't look like a valid web URL. Use http:// or https://."
    const val MSG_URL_PAYWALL =
        "This article requires login or is behind a paywall."
    const val MSG_URL_NOT_FOUND =
        "Page not found."
    const val MSG_URL_HTTP_GENERIC =
        "The site returned an error."

    /**
     * Translate a parser-level failure into a user-friendly message.
     *
     * @param throwable the underlying exception thrown by the parser or content resolver
     * @param sourceUriString optional URI string — lets us detect the provider even if
     *                        the exception message has been stripped of context
     * @param fallback the original message to return when no cloud signature matches;
     *                 preserves per-parser phrasing (e.g. "Failed to open PDF file: ...")
     */
    fun translate(
        throwable: Throwable?,
        sourceUriString: String? = null,
        fallback: String
    ): String {
        val exceptionText = buildString {
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                current.message?.let { append(it).append(' ') }
                current.javaClass.name.let { append(it).append(' ') }
                current = current.cause
                depth++
            }
        }
        val uriText = sourceUriString.orEmpty()

        val isDriveUri = uriText.contains(SIG_DRIVE_DOCS_PROVIDER, ignoreCase = true)
        val isOneDriveUri = uriText.contains(SIG_ONEDRIVE_PROVIDER, ignoreCase = true)
        val isDropboxUri = uriText.contains(SIG_DROPBOX_PROVIDER, ignoreCase = true)
        val hasCelloSignature = exceptionText.contains(SIG_GOOGLE_DRIVE_CELLO, ignoreCase = true)
        val looksUnreachable = looksLikeUnreachableCloud(exceptionText)

        // URL-import signatures are cheaper to match than walking the JSoup HttpStatusException
        // status code out of the exception object, so fall back on text matching. Order matters:
        // the invalid-scheme sentinel is deliberately checked before generic "unreachable" since
        // a rejected file:// URI could otherwise be misread as a network failure.
        val hasInvalidScheme = exceptionText.contains(SIG_INVALID_SCHEME, ignoreCase = true)
        val hasUnknownHost = exceptionText.contains(SIG_UNKNOWN_HOST, ignoreCase = false)
        val hasTimeout = exceptionText.contains(SIG_SOCKET_TIMEOUT, ignoreCase = false)
        val hasSsl = exceptionText.contains(SIG_SSL_HANDSHAKE, ignoreCase = false) ||
            exceptionText.contains(SIG_SSL, ignoreCase = false)
        val hasHttpStatus = exceptionText.contains(SIG_JSOUP_HTTP_STATUS, ignoreCase = false)
        val hasMalformedUrl = exceptionText.contains(SIG_MALFORMED_URL, ignoreCase = false)
        val isHttpUri = uriText.startsWith("http://", ignoreCase = true) ||
            uriText.startsWith("https://", ignoreCase = true)
        val statusCode = extractHttpStatusCode(throwable)

        return when {
            // URL-import branches first — match when the source URI is a web URL OR the
            // exception carries an unambiguous network signature. Keeps file-based flows
            // from accidentally routing through these.
            hasInvalidScheme -> MSG_URL_INVALID_SCHEME
            hasMalformedUrl -> MSG_URL_INVALID_SCHEME
            hasHttpStatus && statusCode in listOf(401, 403) -> MSG_URL_PAYWALL
            hasHttpStatus && statusCode == 404 -> MSG_URL_NOT_FOUND
            hasHttpStatus -> MSG_URL_HTTP_GENERIC
            isHttpUri && hasUnknownHost -> MSG_URL_UNKNOWN_HOST
            isHttpUri && hasTimeout -> MSG_URL_TIMEOUT
            isHttpUri && hasSsl -> MSG_URL_SSL

            // Google Drive's Cello native lib surfaces errors like "...: Cello error 2"
            // (error 2 = ENOENT). Match on the signature so truncated prefixes still work.
            hasCelloSignature -> MSG_DRIVE_UNREACHABLE
            isDriveUri && looksUnreachable -> MSG_DRIVE_UNREACHABLE
            isOneDriveUri && looksUnreachable -> MSG_ONEDRIVE_UNREACHABLE
            isDropboxUri && looksUnreachable -> MSG_DROPBOX_UNREACHABLE
            looksUnreachable && uriText.startsWith("content://") -> MSG_CLOUD_GENERIC_UNREACHABLE
            else -> fallback
        }
    }

    /**
     * JSoup's HttpStatusException exposes the HTTP status via a `statusCode` property.
     * Using reflection here (rather than a direct import) keeps this object free of a
     * compile-time dependency on jsoup — other parsers in this module must continue to
     * compile even if the jsoup dep is removed later.
     */
    private fun extractHttpStatusCode(throwable: Throwable?): Int? {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            val node = current
            if (node.javaClass.simpleName == "HttpStatusException") {
                return runCatching {
                    val method = node.javaClass.getMethod("getStatusCode")
                    (method.invoke(node) as? Int)
                }.getOrNull()
            }
            current = node.cause
            depth++
        }
        return null
    }

    private fun looksLikeUnreachableCloud(exceptionText: String): Boolean {
        if (exceptionText.isBlank()) return false
        return UNREACHABLE_SIGNATURES.any { exceptionText.contains(it, ignoreCase = true) }
    }

    private const val MAX_CAUSE_DEPTH = 5

    private val UNREACHABLE_SIGNATURES = listOf(
        "FileNotFoundException",
        "No such file",
        "ENOENT",
        "Could not open",
        "Cello error",
        "NetworkOnMainThreadException",
        "UnknownHostException",
        "SSLException",
        "Broken pipe",
        "Connection reset"
    )
}

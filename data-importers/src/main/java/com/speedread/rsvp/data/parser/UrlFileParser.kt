package com.speedread.rsvp.data.parser

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches an http(s) URL, classifies the response by Content-Type, and returns readable
 * article text via the same FileParseResult contract as the file-based parsers.
 *
 * - text/html + text/xhtml -> JSoup parse + Readability4J article extraction
 * - text/plain              -> raw body decoded with the response charset
 * - application/pdf         -> bytes buffered to cacheDir, delegated to PdfFileParser
 *
 * Non-http(s) schemes are rejected before any network call is made.
 */
@Singleton
class UrlFileParser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pdfFileParser: PdfFileParser
) : FileParser {

    // UrlFileParser is reached only via direct injection from FileTextProvider for URL
    // imports — never via FileParserRegistry. canParse / getSupportedMimeTypes /
    // getSupportedExtensions all return "no match" so if this parser ever were added
    // to the FileParser multibinding set by mistake, the registry still couldn't route
    // a local file to it (which would incorrectly surface an "invalid scheme" error
    // because parseFile(Uri) rejects everything that isn't http/https).
    override fun canParse(mimeType: String?, fileExtension: String?): Boolean = false

    override fun getSupportedMimeTypes(): List<String> = emptyList()

    override fun getSupportedExtensions(): List<String> = emptyList()

    /**
     * Parse HTML bytes that were already fetched (used when delegated from another parser).
     * Treated as text/html with no base URL — relative links won't resolve, but since we
     * only emit text content that's fine.
     */
    override suspend fun parseFile(inputStream: InputStream): FileParseResult {
        return withContext(Dispatchers.IO) {
            try {
                val doc = Jsoup.parse(inputStream, null, "")
                val article = Readability4J("", doc).parse()
                val htmlContent = article.content
                val text = if (!htmlContent.isNullOrBlank()) {
                    stripHtmlTags(htmlContent)
                } else {
                    article.textContent.orEmpty()
                }.trim()
                if (text.isBlank()) {
                    return@withContext FileParseResult.Error(
                        IllegalStateException("no readable content"),
                        "Couldn't extract any readable text from that page."
                    )
                }
                FileParseResult.Success(
                    text,
                    FileMetadata(
                        title = article.title ?: doc.title().ifBlank { null },
                        author = article.byline,
                        wordCount = countWords(text),
                        fileSize = text.length.toLong(),
                        mimeType = "text/html",
                        language = doc.select("html").attr("lang").ifBlank { null }
                    )
                )
            } catch (e: Exception) {
                FileParseResult.Error(
                    e,
                    ImportErrorTranslator.translate(
                        throwable = e,
                        fallback = "Error parsing HTML: ${e.message}"
                    )
                )
            }
        }
    }

    override suspend fun parseFile(uri: Uri): FileParseResult {
        val scheme = uri.scheme?.lowercase()
        if (scheme !in UrlImportConstants.ALLOWED_SCHEMES) {
            val e = IllegalArgumentException("invalid scheme: $scheme")
            return FileParseResult.Error(
                e,
                ImportErrorTranslator.translate(
                    throwable = e,
                    sourceUriString = uri.toString(),
                    fallback = "Only http:// and https:// URLs are supported."
                )
            )
        }
        return parseUrl(uri.toString())
    }

    private suspend fun parseUrl(url: String): FileParseResult {
        return withContext(Dispatchers.IO) {
            try {
                val conn = Jsoup.connect(url)
                    .userAgent(UrlImportConstants.USER_AGENT)
                    .timeout(UrlImportConstants.READ_TIMEOUT_MS)
                    .maxBodySize(UrlImportConstants.MAX_RESPONSE_BYTES)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(false)
                    .execute()

                val contentType = conn.contentType().orEmpty().lowercase()

                when {
                    contentType.startsWith("application/pdf") -> delegateToPdf(url, conn.bodyAsBytes())
                    contentType.startsWith("text/plain") -> buildPlainTextResult(url, conn.bodyAsBytes(), conn.charset())
                    contentType.startsWith("text/") ||
                    contentType.startsWith("application/xhtml") ||
                    contentType.isEmpty() -> buildHtmlResult(url, conn.parse())
                    else -> FileParseResult.Error(
                        IllegalStateException("unsupported content type"),
                        "That URL points to a ${contentType.substringBefore(';').trim()} file, which SpeedRead can't read as text."
                    )
                }
            } catch (e: Exception) {
                FileParseResult.Error(
                    e,
                    ImportErrorTranslator.translate(
                        throwable = e,
                        sourceUriString = url,
                        fallback = "Failed to fetch URL: ${e.message}"
                    )
                )
            }
        }
    }

    private suspend fun delegateToPdf(url: String, bytes: ByteArray): FileParseResult {
        val cacheFile = File(context.cacheDir, "url_import_${sha1Hex(url)}.pdf")
        try {
            FileOutputStream(cacheFile).use { it.write(bytes) }
            cacheFile.inputStream().use { stream ->
                return pdfFileParser.parseFile(stream)
            }
        } finally {
            // Cached PDF isn't needed after parsing — the extracted text is what gets stored.
            // Best-effort delete; ignore failure (worst case cacheDir grows, which Android
            // eventually reclaims under pressure).
            runCatching { cacheFile.delete() }
        }
    }

    private fun buildPlainTextResult(url: String, bytes: ByteArray, charsetName: String?): FileParseResult {
        val charset = runCatching { Charset.forName(charsetName ?: "UTF-8") }
            .getOrElse { Charset.forName("UTF-8") }
        val text = String(bytes, charset).trim()
        if (text.isBlank()) {
            return FileParseResult.Error(
                IllegalStateException("empty body"),
                "That URL returned no text content."
            )
        }
        return FileParseResult.Success(
            text,
            FileMetadata(
                title = url,
                wordCount = countWords(text),
                fileSize = text.length.toLong(),
                mimeType = "text/plain"
            )
        )
    }

    private fun buildHtmlResult(url: String, doc: org.jsoup.nodes.Document): FileParseResult {
        val article = Readability4J(url, doc).parse()
        val htmlContent = article.content
        val text = if (!htmlContent.isNullOrBlank()) {
            stripHtmlTags(htmlContent)
        } else {
            article.textContent.orEmpty()
        }.trim()
        if (text.isBlank()) {
            return FileParseResult.Error(
                IllegalStateException("no readable content"),
                "Couldn't extract any readable text from that page. It may be behind a login or rendered entirely by JavaScript."
            )
        }
        return FileParseResult.Success(
            text,
            FileMetadata(
                title = article.title ?: doc.title().ifBlank { null },
                author = article.byline,
                wordCount = countWords(text),
                fileSize = text.length.toLong(),
                mimeType = "text/html",
                language = doc.select("html").attr("lang").ifBlank { null }
            )
        )
    }

    private fun countWords(text: String): Int {
        return text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun stripHtmlTags(html: String): String {
        return try {
            val doc = Jsoup.parse(html)
            doc.select("style, script").remove()
            val sb = java.lang.StringBuilder()
            
            val visitor = object : org.jsoup.select.NodeVisitor {
                override fun head(node: org.jsoup.nodes.Node, depth: Int) {
                    if (node is org.jsoup.nodes.TextNode) {
                        sb.append(node.text())
                    } else if (node is org.jsoup.nodes.Element) {
                        val name = node.normalName()
                        if (name == "br") {
                            sb.append("\n")
                        }
                    }
                }

                override fun tail(node: org.jsoup.nodes.Node, depth: Int) {
                    if (node is org.jsoup.nodes.Element) {
                        val name = node.normalName()
                        if (name == "p" || name == "h1" || name == "h2" || name == "h3" || name == "h4" || name == "h5" || name == "h6") {
                            if (sb.isNotEmpty() && !sb.endsWith("\n\n")) {
                                if (sb.endsWith("\n")) sb.append("\n") else sb.append("\n\n")
                            }
                        } else if (name == "div" || name == "li" || name == "tr") {
                            if (sb.isNotEmpty() && !sb.endsWith("\n")) {
                                sb.append("\n")
                            }
                        }
                    }
                }
            }
            
            (doc.body() ?: doc).traverse(visitor)
            sb.toString().trim()
        } catch (e: Exception) {
            html
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("&[a-zA-Z0-9#]+;"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}

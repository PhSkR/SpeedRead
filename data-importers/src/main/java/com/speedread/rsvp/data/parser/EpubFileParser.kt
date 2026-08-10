package com.speedread.rsvp.data.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.Xml
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.jsoup.Jsoup
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpubFileParser @Inject constructor(
    @ApplicationContext private val context: Context
) : FileParser {
    
    override fun canParse(mimeType: String?, fileExtension: String?): Boolean {
        return mimeType == "application/epub+zip" || fileExtension == "epub"
    }
    
    override suspend fun parseFile(inputStream: InputStream): FileParseResult {
        return withContext(Dispatchers.IO) {
            try {
                extractTextFromEpub(inputStream)
            } catch (e: Exception) {
                FileParseResult.Error(
                    e,
                    ImportErrorTranslator.translate(
                        throwable = e,
                        fallback = "Failed to parse EPUB: ${e.message}"
                    )
                )
            }
        }
    }

    override suspend fun parseFile(uri: Uri): FileParseResult {
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    parseFile(inputStream)
                } ?: FileParseResult.Error(
                    IllegalArgumentException("Cannot open file"),
                    "Unable to open file from URI"
                )
            } catch (e: Exception) {
                FileParseResult.Error(
                    e,
                    ImportErrorTranslator.translate(
                        throwable = e,
                        sourceUriString = uri.toString(),
                        fallback = "Failed to open EPUB file: ${e.message}"
                    )
                )
            }
        }
    }
    
    override fun getSupportedMimeTypes(): List<String> {
        return listOf("application/epub+zip")
    }
    
    override fun getSupportedExtensions(): List<String> {
        return listOf("epub")
    }
    
    private suspend fun extractTextFromEpub(inputStream: InputStream): FileParseResult {
        return withContext(Dispatchers.Default) {
            try {
                val textBuilder = StringBuilder()
                var title: String? = null
                var author: String? = null
                var pageCount = 0
                
                // First pass: collect all files and find OPF
                val zipEntries = mutableMapOf<String, ByteArray>()
                var opfPath: String? = null
                var containerXmlContent: String? = null
                
                ZipInputStream(inputStream).use { zipStream ->
                    var entry = zipStream.nextEntry
                    
                    while (entry != null) {
                        val entryName = entry.name
                        val content = zipStream.readBytes()
                        zipEntries[entryName] = content
                        
                        when {
                            entryName == "META-INF/container.xml" -> {
                                containerXmlContent = content.toString(Charsets.UTF_8)
                            }
                            entryName.endsWith(".opf") && opfPath == null -> {
                                opfPath = entryName
                            }
                        }
                        
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
                
                // Find OPF path from container.xml if available
                containerXmlContent?.let { container ->
                    val opfMatch = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(container)
                    opfMatch?.groupValues?.get(1)?.let { path ->
                        opfPath = path
                    }
                }
                
                opfPath?.let { opfFile ->
                    zipEntries[opfFile]?.let { opfContent ->
                        val opfData = parseOpfFile(opfContent.toString(Charsets.UTF_8))
                        title = opfData.title
                        author = opfData.author
                        
                        // Process files in spine order
                        val opfDirectory = if (opfFile.contains("/")) {
                            opfFile.substringBeforeLast("/") + "/"
                        } else ""
                        
                        opfData.spineOrder.forEach { spineItem ->
                            val manifestItem = opfData.manifest[spineItem.idref]
                            if (manifestItem != null && spineItem.linear) {
                                val filePath = if (opfDirectory.isNotEmpty() && !manifestItem.href.startsWith("/")) {
                                    opfDirectory + manifestItem.href
                                } else {
                                    manifestItem.href
                                }
                                
                                zipEntries[filePath]?.let { fileContent ->
                                    if (isReadableContentType(manifestItem.mediaType)) {
                                        val content = fileContent.toString(Charsets.UTF_8)
                                        val plainText = stripHtmlTags(content)
                                        if (plainText.isNotBlank()) {
                                            textBuilder.append(plainText).append("\n\n")
                                            pageCount++
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Fallback to old method if OPF parsing fails
                if (textBuilder.isEmpty()) {
                    zipEntries.entries
                        .filter { (name, _) -> 
                            name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm") 
                        }
                        .sortedBy { it.key }
                        .forEach { (_, content) ->
                            val plainText = stripHtmlTags(content.toString(Charsets.UTF_8))
                            if (plainText.isNotBlank()) {
                                textBuilder.append(plainText).append("\n\n")
                                pageCount++
                            }
                        }
                }
                
                val fullText = textBuilder.toString().trim()
                
                val metadata = FileMetadata(
                    title = title?.takeIf { it.isNotBlank() },
                    author = author?.takeIf { it.isNotBlank() },
                    wordCount = countWords(fullText),
                    pageCount = pageCount,
                    mimeType = "application/epub+zip"
                )
                
                if (fullText.isBlank()) {
                    FileParseResult.Error(
                        IllegalStateException("EPUB contains no readable text"),
                        "The EPUB file appears to contain no text content"
                    )
                } else {
                    FileParseResult.Success(fullText, metadata)
                }
            } catch (e: Exception) {
                FileParseResult.Error(e, "Failed to extract text from EPUB: ${e.message}")
            }
        }
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
            
            doc.body().traverse(visitor)
            sb.toString().trim()
        } catch (_: Exception) {
            // Robust fallback if Jsoup parsing fails
            html
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("&[a-zA-Z0-9#]+;"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
    
    private fun countWords(text: String): Int {
        return text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
    }
    
    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String
    )
    
    private data class SpineItem(
        val idref: String,
        val linear: Boolean
    )
    
    private data class OpfData(
        val title: String?,
        val author: String?,
        val manifest: Map<String, ManifestItem>,
        val spineOrder: List<SpineItem>
    )
    
    private fun parseOpfFile(opfContent: String): OpfData {
        return try {
            val parser = Xml.newPullParser()
            parser.setInput(opfContent.byteInputStream(), "UTF-8")
            
            var title: String? = null
            var author: String? = null
            val manifest = mutableMapOf<String, ManifestItem>()
            val spineOrder = mutableListOf<SpineItem>()
            
            var eventType = parser.eventType
            var inManifest = false
            var inSpine = false
            
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "manifest" -> inManifest = true
                            "spine" -> inSpine = true
                            "dc:title" -> {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    title = parser.text
                                }
                            }
                            "dc:creator" -> {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    author = parser.text
                                }
                            }
                            "item" -> if (inManifest) {
                                val id = parser.getAttributeValue(null, "id")
                                val href = parser.getAttributeValue(null, "href")
                                val mediaType = parser.getAttributeValue(null, "media-type")
                                
                                if (id != null && href != null && mediaType != null) {
                                    manifest[id] = ManifestItem(id, href, mediaType)
                                }
                            }
                            "itemref" -> if (inSpine) {
                                val idref = parser.getAttributeValue(null, "idref")
                                val linear = parser.getAttributeValue(null, "linear") != "no"
                                
                                if (idref != null) {
                                    spineOrder.add(SpineItem(idref, linear))
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "manifest" -> inManifest = false
                            "spine" -> inSpine = false
                        }
                    }
                }
                eventType = parser.next()
            }
            
            OpfData(title, author, manifest, spineOrder)
        } catch (e: Exception) {
            Log.w("EpubFileParser", "OPF parse failed; returning empty manifest/spine", e)
            OpfData(null, null, emptyMap(), emptyList())
        }
    }
    
    private fun isReadableContentType(mediaType: String): Boolean {
        return when (mediaType) {
            "application/xhtml+xml",
            "text/html",
            "image/svg+xml",
            "application/x-dtbook+xml" -> true
            else -> false
        }
    }
}
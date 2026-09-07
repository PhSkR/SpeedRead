package com.speedread.rsvp

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedread.rsvp.data.bookmark.BookmarkRepository
import com.speedread.rsvp.data.bookmark.BookmarkSource
import com.speedread.rsvp.data.document.DocumentJson
import com.speedread.rsvp.data.document.FigureRegion as DocFigureRegion
import com.speedread.rsvp.data.document.SavedDocument
import com.speedread.rsvp.data.document.SavedDocumentRepository
import com.speedread.rsvp.data.document.toImporter
import com.speedread.rsvp.data.parser.PageBoundary as ImporterPageBoundary
import com.speedread.rsvp.pdf.FigureImageStore
import com.speedread.rsvp.engine.RsvpSettings
import com.speedread.rsvp.engine.TextProcessor
import com.speedread.rsvp.engine.RsvpWord
import com.speedread.rsvp.engine.TrailingBreak
import com.speedread.rsvp.pdf.PdfPageRenderer
import com.speedread.rsvp.ui.PdfPageItem
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * One page of paged-mode Page View content.
 *
 * Not a `data class`: `wordStarts` is an `IntArray` whose generated `equals`/`hashCode` would use
 * reference identity, breaking `DiffUtil.areContentsTheSame` whenever a page list is rebuilt with
 * the same logical content but a freshly allocated array. Equality is hand-written over the
 * logical fields (`pageNumber`, `content`, `wordCount`, `startWordIndex`, `endWordIndex`); the
 * cached `wordStarts` is purely derived from `content` + `startWordIndex`, so excluding it from
 * equality is correct.
 *
 * `wordStarts[i]` is the char offset inside [content] of the i-th word on this page (0-based,
 * relative to this page — not the document). The array length equals `wordCount`. Used by
 * [com.speedread.rsvp.ui.PageAdapter] for O(1) absolute-word-index → char-range lookups during
 * highlight updates, replacing a per-bind O(N) char scan that compounded on long documents
 * during TTS narration. See changelog v1.14.21 for the parallel continuous-mode fix that
 * established this pattern.
 */
/**
 * An inline figure anchored inside one page's display text. [figureIndex] indexes the
 * document's full FigureRegion list (cache key + viewer lookup); the offsets locate the
 * single U+FFFC placeholder char in each display variant's content, where the adapter
 * mounts the rendered figure as an ImageSpan.
 */
data class PageFigure(
    val figureIndex: Int,
    val aspectRatio: Float,
    val pageNumber: Int,
    val contentOffset: Int,
    val continuousOffset: Int
)

class DocumentPage(
    val pageNumber: Int,
    val content: String,
    val wordCount: Int,
    val startWordIndex: Int,
    val endWordIndex: Int,
    val wordStarts: IntArray,
    // Continuous-mode display variant: identical word-index space, but page-furniture words
    // (printed page numbers, running headers/footers) are blanked to zero length and
    // contribute no separator. Paged mode keeps the raw content — furniture at the top or
    // bottom of a discrete page reads naturally; jammed inline mid-sentence in the continuous
    // flow it does not. Defaults alias the raw fields so pages (and whole documents) without
    // detected furniture carry no extra allocations. Excluded from equality like wordStarts:
    // both are deterministically derived from content + boundaries.
    val continuousContent: String = content,
    val continuousWordStarts: IntArray = wordStarts,
    // Figures anchored on this page (both variants carry the same placeholders). Derived
    // like wordStarts, so excluded from equality.
    val figures: List<PageFigure> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DocumentPage) return false
        return pageNumber == other.pageNumber &&
            wordCount == other.wordCount &&
            startWordIndex == other.startWordIndex &&
            endWordIndex == other.endWordIndex &&
            content == other.content
    }

    override fun hashCode(): Int {
        var result = pageNumber
        result = 31 * result + content.hashCode()
        result = 31 * result + wordCount
        result = 31 * result + startWordIndex
        result = 31 * result + endWordIndex
        return result
    }
}

data class DisplayPageInfo(val current: Int, val total: Int)

class ContinuousTextWindow(
    val startWordIndex: Int,
    val endWordIndex: Int,
    val content: String,
    val wordStarts: IntArray,
    val wordLengths: IntArray
) {
    fun localCharRangeForAbsoluteWord(wordIndex: Int): IntRange? {
        if (wordIndex < startWordIndex || wordIndex > endWordIndex) return null
        val localIndex = wordIndex - startWordIndex
        if (localIndex < 0 || localIndex >= wordStarts.size) return null
        val start = wordStarts[localIndex]
        val length = wordLengths[localIndex]
        return start until (start + length)
    }
}

@HiltViewModel
class PageViewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookmarkRepository: BookmarkRepository,
    private val savedDocumentRepository: SavedDocumentRepository,
    private val pdfRenderer: PdfPageRenderer,
    private val rsvpSettingsManager: RsvpSettingsManager,
    private val textProcessor: TextProcessor,
    private val tokenCache: PageViewTokenCache,
    private val figureImageStore: FigureImageStore
) : ViewModel() {

    // Single-word (chunkSize=1) indexing. Page View word-position indices must stay aligned
    // with bookmarks and the RSVP highlight, which are also 1-word-per-index. Changing this
    // would silently drift cross-mode position sync.
    private val tokenizeSettings = RsvpSettings(chunkSize = 1)

    private val _pages = MutableStateFlow<List<DocumentPage>>(emptyList())
    val pages: StateFlow<List<DocumentPage>> = _pages.asStateFlow()

    // Whitespace-normalized full-document text used by continuous (endless-scroll) mode.
    // Built from the same TextProcessor tokenization that drives RSVP word indexing, so an
    // absolute word index (1 token per index) maps deterministically to a character range
    // inside this string — see computeHighlightCharRange in the activity.
    private val _continuousText = MutableStateFlow("")
    val continuousText: StateFlow<String> = _continuousText.asStateFlow()

    // Word index the user is currently parked on in continuous mode. Tracked independently
    // from _highlightedWordIndex (which marks where RSVP left off and stays put as the user
    // scrolls) so leaving Page View can return the precise scroll-based word back to RSVP
    // without overwriting the highlight visual.
    private val _continuousWordPosition = MutableStateFlow(0)
    val continuousWordPosition: StateFlow<Int> = _continuousWordPosition.asStateFlow()

    private val _pdfPages = MutableStateFlow<List<PdfPageItem>>(emptyList())
    val pdfPages: StateFlow<List<PdfPageItem>> = _pdfPages.asStateFlow()
    
    private val _isPdfMode = MutableStateFlow(false)
    val isPdfMode: StateFlow<Boolean> = _isPdfMode.asStateFlow()

    private val _documentTitle = MutableStateFlow<String?>(null)
    val documentTitle: StateFlow<String?> = _documentTitle.asStateFlow()

    private val _currentPageIndex = MutableStateFlow(-1)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private val _currentDocument = MutableStateFlow<SavedDocument?>(null)
    val currentDocument: StateFlow<SavedDocument?> = _currentDocument.asStateFlow()

    // Absolute word index to highlight in text-mode pages — used to mark where RSVP left off
    // when the user switches from RSVP to Page View. Null = no highlight.
    private val _highlightedWordIndex = MutableStateFlow<Int?>(null)
    val highlightedWordIndex: StateFlow<Int?> = _highlightedWordIndex.asStateFlow()

    private var currentText: String = ""
    private var currentContentHash: String? = null
    private var pageBoundaries: List<ImporterPageBoundary>? = null
    private var currentTextSource: BookmarkSource = BookmarkSource.MANUAL_TEXT

    // Figure regions parsed from the loaded document's figureRegionsJson, in stored order.
    // Index into this list == PageFigure.figureIndex == the FigureImageStore cache key.
    private var figureRegionList: List<DocFigureRegion> = emptyList()

    // Tokenization cache. Built once per document load on Dispatchers.Default so a long
    // novel does not block the UI thread when entering Page View. Previously the activity
    // re-tokenized inside `getTotalWordCount()`, `processTextIntoPages()`, `createArtificialPages()`,
    // and the activity's per-TTS-word `findCharRangeForWord` walk — three tokenization
    // passes plus an O(N) char walk on every spoken word. With the cache:
    //   - `cachedTokenizedWords`  — the single-token RsvpWord list (token strings only)
    //   - `cachedContinuousJoined` — the whitespace-normalized full-document text used by the
    //     continuous-scroll TextView, kept in sync with `_continuousText.value`
    //   - `cachedWordCharStarts`  — per-word char start offsets in the joined string;
    //     length = words.size + 1 so the end of word i is `cachedWordCharStarts[i + 1] - 1`
    //     (subtract the single-space separator). Lookup via `getWordCharRange(i)` is O(1).
    private var cachedTokenizedWords: List<String> = emptyList()
    private var cachedContinuousJoined: String = ""
    private var cachedWordCharStarts: IntArray = IntArray(0)

    /**
     * [preferTextMode] short-circuits the PDF-first path for FILE_PDF documents: the caller
     * (activity) passes the persisted content-mode preference so a reader who switched to
     * reflowed text once is not bounced back into native PDF page mode on every open.
     */
    fun loadDocumentById(documentId: Long, currentWordPosition: Int = 0, preferTextMode: Boolean = false) {
        viewModelScope.launch {
            try {
                val document = savedDocumentRepository.getDocumentById(documentId)
                if (document == null) {
                    _errorMessage.value = "Document not found"
                    return@launch
                }

                // Clear the previous document's text immediately. The disk-cache fast path
                // below defers the content read to the background, and consumers of
                // currentText (bookmark creation, TTS raw text) must never see the PREVIOUS
                // document's text during that window.
                currentText = ""
                _currentDocument.value = document
                _documentTitle.value = document.title
                _highlightedWordIndex.value = currentWordPosition.takeIf { it >= 0 }
                currentContentHash = document.contentHash
                currentTextSource = document.source
                figureRegionList = DocumentJson.decodeFigureRegions(document.figureRegionsJson).orEmpty()
                _continuousWordPosition.value = currentWordPosition.coerceAtLeast(0)

                if (document.source == BookmarkSource.FILE_PDF && !preferTextMode) {
                    currentText = loadDocumentContent(document)
                    tryLoadPdfForRendering(document, currentWordPosition)
                    return@launch
                }

                pageBoundaries = parsePageBoundaries(document.pageBoundariesJson)

                // Check whether a disk cache (display or token) exists BEFORE
                // loading raw content.  A cheap file-existence check avoids
                // the full JSON parse that tokenCache.store.load() would do.
                // On hit: pages display immediately; the raw-content file read
                // runs in the background (only needed for bookmarks / TTS).
                val spacing = rsvpSettingsManager.getCurrentRsvpSettings().enableParagraphSpacing
                val hasDiskCache = tokenCache.store.hasCache(document.contentHash, spacing)
                if (hasDiskCache) {
                    processTextIntoPages()
                    launch {
                        currentText = loadDocumentContent(document)
                        // The fast path can come up empty when the cache files exist but fail
                        // to load (version mismatch, partial corruption) — rebuildTokenCache
                        // then has no text to tokenize. Retry once the real content is here.
                        if (_pages.value.isEmpty() && currentText.isNotEmpty()) {
                            processTextIntoPages()
                            _currentPageIndex.value = findPageForWordPosition(currentWordPosition)
                        }
                    }
                } else {
                    currentText = loadDocumentContent(document)
                    processTextIntoPages()
                }

                val targetPageIndex = findPageForWordPosition(currentWordPosition)
                Logger.d("PageViewViewModel", "Document loaded: wordPosition=$currentWordPosition, targetPage=$targetPageIndex, totalPages=${_pages.value.size}")
                if (_pages.value.isNotEmpty() && targetPageIndex < _pages.value.size) {
                    val targetPage = _pages.value[targetPageIndex]
                    Logger.d("PageViewViewModel", "Target page range: ${targetPage.startWordIndex}-${targetPage.endWordIndex}")
                }
                _currentPageIndex.value = targetPageIndex

            } catch (e: Exception) {
                Logger.e("PageViewViewModel", "loadDocument failed", e)
                _errorMessage.value = "Failed to load document: ${e.message}"
            }
        }
    }

    private suspend fun loadDocumentContent(document: SavedDocument): String {
        return try {
            if (document.isContentExternal) {
                savedDocumentRepository.loadDocumentContent(document)
            } else {
                document.content
            }
        } catch (e: Exception) {
            Logger.e("PageViewViewModel", "Failed to load document content for ${document.title}", e)
            ""
        }
    }

    private fun parsePageBoundaries(json: String?): List<ImporterPageBoundary>? =
        DocumentJson.decodePageBoundaries(json)?.map { it.toImporter() }

    fun loadTextDocument(text: String, title: String?, currentWordPosition: Int = 0) {
        viewModelScope.launch {
            try {
                currentText = text
                currentContentHash = null
                _currentDocument.value = null
                _documentTitle.value = title ?: "Document"
                currentTextSource = BookmarkSource.MANUAL_TEXT
                pageBoundaries = null
                figureRegionList = emptyList()
                _highlightedWordIndex.value = currentWordPosition.takeIf { it >= 0 }
                _continuousWordPosition.value = currentWordPosition.coerceAtLeast(0)

                processTextIntoPages()

                // Find the page containing the current word position
                val targetPageIndex = findPageForWordPosition(currentWordPosition)
                
                // Debug logging
                Logger.d("PageViewViewModel", "Text document loaded: wordPosition=$currentWordPosition, targetPage=$targetPageIndex, totalPages=${_pages.value.size}")
                if (_pages.value.isNotEmpty() && targetPageIndex < _pages.value.size) {
                    val targetPage = _pages.value[targetPageIndex]
                    Logger.d("PageViewViewModel", "Target page range: ${targetPage.startWordIndex}-${targetPage.endWordIndex}")
                }
                
                _currentPageIndex.value = targetPageIndex

            } catch (e: Exception) {
                Logger.e("PageViewViewModel", "loadTextDocument failed", e)
                _errorMessage.value = "Failed to load text: ${e.message}"
            }
        }
    }

    /**
     * Tokenize the current document and rebuild the cache off the main thread. Walks the
     * full text once on `Dispatchers.Default` to produce the token list, the joined
     * continuous-scroll text, and the per-word char-start array. Previously these were
     * recomputed up to three times on Main per Page View entry, which blocked the UI for
     * hundreds of ms on long documents.
     *
     * The joined text uses a single ASCII space as the separator between every token so
     * `cachedWordCharStarts[i + 1] - 1` yields the (exclusive) end of word i and any
     * char offset within `cachedContinuousJoined` is decodable via a binary search /
     * direct index into the same array.
     */
    private fun buildCacheKey(): PageViewTokenCache.CacheKey {
        val enableSpacing = rsvpSettingsManager.getCurrentRsvpSettings().enableParagraphSpacing
        return PageViewTokenCache.CacheKey(
            contentIdentity = currentContentHash?.hashCode() ?: currentText.hashCode(),
            contentHash = currentContentHash,
            enableParagraphSpacing = enableSpacing
        )
    }

    private suspend fun rebuildTokenCache(diskCacheOverride: List<CachedToken>? = null) {
        val cacheKey = buildCacheKey()

        // L1: in-memory singleton
        val memHit = tokenCache.getTokenData(cacheKey)
        if (memHit != null) {
            cachedTokenizedWords = memHit.tokenizedWords
            cachedContinuousJoined = memHit.continuousJoined
            cachedWordCharStarts = memHit.wordCharStarts
            _continuousText.value = memHit.continuousJoined
            return
        }

        val diskHash = cacheKey.contentHash

        // L2: pre-built display cache (binary, no JSON parsing or string rebuilding)
        if (diskHash != null) {
            val displayHit = tokenCache.store.loadDisplay(diskHash, cacheKey.enableParagraphSpacing)
            if (displayHit != null) {
                applyAndCacheTokenData(cacheKey, displayHit.words, displayHit.joinedText, displayHit.charStarts)
                return
            }
        }

        // L3: token cache — need to rebuild joined text from individual tokens
        val diskHit = diskCacheOverride ?: if (diskHash != null) tokenCache.store.load(diskHash) else null
        if (diskHit != null) {
            val (joined, starts) = withContext(Dispatchers.Default) {
                buildJoinedText(diskHit, cacheKey.enableParagraphSpacing)
            }
            val words = diskHit.map { it.t }
            applyAndCacheTokenData(cacheKey, words, joined, starts)
            if (diskHash != null) {
                tokenCache.store.saveDisplay(
                    diskHash, cacheKey.enableParagraphSpacing,
                    DisplayCacheEntry(words, joined, starts)
                )
            }
            return
        }

        // L4: full tokenization
        val text = currentText
        if (text.isEmpty()) {
            // Nothing to tokenize (disk-cache fast path loads content in the background).
            // Bail rather than tokenize an empty document and persist an empty token +
            // display cache under the real contentHash, which would render the document
            // blank on every subsequent open.
            return
        }
        val (words, tokens, joined, starts) = withContext(Dispatchers.Default) {
            val rsvpWords = textProcessor.processText(text, tokenizeSettings)
            val w = rsvpWords.map { it.text }
            val t = rsvpWords.map { CachedToken(it.text, it.trailingBreak?.name) }
            val (j, s) = buildJoinedText(t, cacheKey.enableParagraphSpacing)
            TokenizeResult(w, t, j, s)
        }
        applyAndCacheTokenData(cacheKey, words, joined, starts)

        if (diskHash != null) {
            tokenCache.store.save(diskHash, tokens)
            tokenCache.store.saveDisplay(
                diskHash, cacheKey.enableParagraphSpacing,
                DisplayCacheEntry(words, joined, starts)
            )
        }
    }

    private data class TokenizeResult(
        val words: List<String>,
        val tokens: List<CachedToken>,
        val joined: String,
        val starts: IntArray
    )

    /**
     * Absolute word indices of page furniture (printed page numbers, repeated running
     * headers/footers) for the current document. Continuous mode hides these words; paged
     * mode keeps them — furniture at the top/bottom of a discrete page reads naturally,
     * jammed inline mid-sentence in the continuous flow it does not. Detection is possible
     * only for boundary-mapped documents (PDF imports); the token stream itself is never
     * modified, so absolute word indices shared with RSVP, TTS, and bookmarks stay aligned.
     */
    private fun detectPageArtifacts(
        words: List<String>,
        boundaries: List<ImporterPageBoundary>
    ): Set<Int> {
        if (!Constants.PAGE_ARTIFACT_FILTERING_ENABLED) return emptySet()
        val artifacts = PageArtifactDetector.detectArtifactWordIndices(words, boundaries)
        Logger.d(
            "PageViewViewModel",
            "Page artifact detection: ${artifacts.size} furniture tokens across ${boundaries.size} boundary pages"
        )
        return artifacts
    }

    private fun buildJoinedText(
        tokens: List<CachedToken>,
        enableSpacing: Boolean
    ): Pair<String, IntArray> {
        val sb = StringBuilder()
        val s = IntArray(tokens.size + 1)
        // Separator owed before the next non-empty token. Tokens blanked by the artifact
        // filter contribute neither text nor separator (no double spaces where furniture was
        // removed); their char start collapses onto the current write position, yielding a
        // zero-length word that highlight consumers already treat as "not highlightable".
        var pendingSeparator: String? = null
        for (i in tokens.indices) {
            val tokenText = tokens[i].t
            if (tokenText.isEmpty()) {
                s[i] = sb.length
                continue
            }
            if (pendingSeparator != null) sb.append(pendingSeparator)
            s[i] = sb.length
            sb.append(tokenText)
            pendingSeparator = if (enableSpacing) {
                when (tokens[i].b) {
                    TrailingBreak.PARAGRAPH.name -> "\n\n"
                    TrailingBreak.LINE.name -> "\n"
                    else -> " "
                }
            } else {
                " "
            }
        }
        s[tokens.size] = sb.length
        return sb.toString() to s
    }

    private fun applyAndCacheTokenData(
        cacheKey: PageViewTokenCache.CacheKey,
        words: List<String>,
        joined: String,
        starts: IntArray
    ) {
        cachedTokenizedWords = words
        cachedContinuousJoined = joined
        cachedWordCharStarts = starts
        _continuousText.value = joined
        tokenCache.putTokenData(
            cacheKey,
            PageViewTokenCache.CachedTokenData(words, joined, starts)
        )
    }

    /**
     * Track the word the user is currently parked on while reading in continuous (endless-
     * scroll) mode. Called by the activity as the NestedScrollView scrolls so leaving Page
     * View can return the precise word position back to RSVP. Clamped to a non-negative
     * value; out-of-range indices are left to the consumer to validate.
     */
    fun setContinuousWordPosition(wordIndex: Int) {
        _continuousWordPosition.value = wordIndex.coerceAtLeast(0)
    }

    /**
     * User explicitly chose this word as the RSVP-continuation point (e.g. long-press in
     * Page View). Updates both the visible highlight (so the user sees which word will be
     * resumed from) and the continuous-mode exit position (so leaving Page View returns
     * exactly that word). Distinct from [setContinuousWordPosition], which only tracks
     * passive scroll position.
     */
    fun setContinuationWord(wordIndex: Int) {
        val safe = wordIndex.coerceAtLeast(0)
        _highlightedWordIndex.value = safe
        _continuousWordPosition.value = safe
    }

    private suspend fun processTextIntoPages(diskCacheOverride: List<CachedToken>? = null) {
        rebuildTokenCache(diskCacheOverride)
        val cacheKey = buildCacheKey()
        val boundaries = pageBoundaries
        val hasBoundaries = boundaries != null

        val cachedPages = tokenCache.getPageData(cacheKey, hasBoundaries)
        if (cachedPages != null) {
            _pages.value = cachedPages.pages
            return
        }

        if (boundaries == null && currentTextSource == BookmarkSource.FILE_PDF) {
            Logger.w(
                "PageViewViewModel",
                "No stored page boundaries for this PDF; page furniture detection skipped (re-import the PDF to enable)"
            )
        }

        val joined = cachedContinuousJoined
        val charStarts = cachedWordCharStarts
        val words = cachedTokenizedWords
        val figures = figureRegionList
        val pagesList = withContext(Dispatchers.Default) {
            if (boundaries != null) {
                buildBoundaryPages(
                    boundaries = boundaries,
                    words = words,
                    continuousOnlyArtifacts = detectPageArtifacts(words, boundaries),
                    figureLabelIndices = collectFigureLabelIndices(figures, words.size),
                    figures = figures
                )
            } else {
                buildArtificialPages(words, joined, charStarts)
            }
        }
        _pages.value = pagesList

        tokenCache.putPageData(
            PageViewTokenCache.CachedPageData(pagesList, hasBoundaries)
        )
    }

    /**
     * Absolute word indices of figure label tokens — the stray diagram/caption words the
     * figure region contributed to the extracted text. Blanked in BOTH display variants:
     * unlike page furniture (meaningful at a discrete page's edge), label fragments are
     * extraction noise everywhere, and the figure itself is shown inline instead.
     */
    private fun collectFigureLabelIndices(figures: List<DocFigureRegion>, wordCount: Int): Set<Int> {
        if (figures.isEmpty()) return emptySet()
        val indices = HashSet<Int>()
        for (figure in figures) {
            val start = figure.labelStartWordIndex
            val end = figure.labelEndWordIndex
            if (start < 0 || end < start) continue
            for (i in start.coerceAtLeast(0)..end.coerceAtMost(wordCount - 1)) indices.add(i)
        }
        return indices
    }

    private fun buildBoundaryPages(
        boundaries: List<ImporterPageBoundary>,
        words: List<String>,
        continuousOnlyArtifacts: Set<Int>,
        figureLabelIndices: Set<Int>,
        figures: List<DocFigureRegion>
    ): List<DocumentPage> {
        val pagesList = mutableListOf<DocumentPage>()
        for (boundary in boundaries) {
            try {
                val startIndex = boundary.startWordIndex.coerceAtLeast(0)
                val endIndex = (boundary.endWordIndex + 1).coerceAtMost(words.size)
                if (startIndex < words.size && endIndex > startIndex) {
                    val pageWords = words.subList(startIndex, endIndex)

                    // Figures anchored on this source page, with their placeholder insertion
                    // point expressed as "before word rel" (0..size). Anchors outside the
                    // page's word range clamp to the page edges.
                    val pageFigureIndices = figures.indices.filter {
                        figures[it].pageNumber == boundary.pageNumber
                    }
                    val insertBeforeWord = HashMap<Int, MutableList<Int>>()
                    pageFigureIndices.forEachIndexed { localOrdinal, figureIndex ->
                        val rel = (figures[figureIndex].anchorWordIndex - startIndex + 1)
                            .coerceIn(0, pageWords.size)
                        insertBeforeWord.getOrPut(rel) { mutableListOf() }.add(localOrdinal)
                    }

                    // Paged variant keeps page furniture but drops figure label noise.
                    val pagedWords = if (figureLabelIndices.isEmpty()) pageWords else {
                        pageWords.mapIndexed { rel, word ->
                            if ((startIndex + rel) in figureLabelIndices) "" else word
                        }
                    }
                    val (pageContent, wordStarts, pagedFigureOffsets) =
                        joinWithStartsAndFigures(pagedWords, insertBeforeWord, pageFigureIndices.size)
                    if (pageContent.isNotBlank() || pageFigureIndices.isNotEmpty()) {
                        // Continuous variant additionally blanks page furniture. Reuses the
                        // paged join when the blank sets add nothing on this page.
                        val hasContinuousExtra = (startIndex until endIndex).any { it in continuousOnlyArtifacts }
                        val (continuousContent, continuousStarts, continuousFigureOffsets) =
                            if (hasContinuousExtra) {
                                joinWithStartsAndFigures(
                                    pageWords.mapIndexed { rel, word ->
                                        val abs = startIndex + rel
                                        if (abs in continuousOnlyArtifacts || abs in figureLabelIndices) "" else word
                                    },
                                    insertBeforeWord,
                                    pageFigureIndices.size
                                )
                            } else {
                                Triple(pageContent, wordStarts, pagedFigureOffsets)
                            }
                        val pageFigures = pageFigureIndices.mapIndexed { localOrdinal, figureIndex ->
                            PageFigure(
                                figureIndex = figureIndex,
                                aspectRatio = figures[figureIndex].aspectRatio,
                                pageNumber = figures[figureIndex].pageNumber,
                                contentOffset = pagedFigureOffsets[localOrdinal],
                                continuousOffset = continuousFigureOffsets[localOrdinal]
                            )
                        }
                        pagesList.add(
                            DocumentPage(
                                pageNumber = boundary.pageNumber,
                                content = pageContent,
                                wordCount = pageWords.size,
                                startWordIndex = boundary.startWordIndex,
                                endWordIndex = boundary.endWordIndex,
                                wordStarts = wordStarts,
                                continuousContent = continuousContent,
                                continuousWordStarts = continuousStarts,
                                figures = pageFigures
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.w("PageViewViewModel", "Skipping page ${boundary.pageNumber} due to processing error", e)
                continue
            }
        }
        return pagesList.sortedBy { it.pageNumber }
    }

    private fun buildArtificialPages(
        words: List<String>,
        joined: String,
        charStarts: IntArray
    ): List<DocumentPage> {
        if (words.isEmpty()) return emptyList()
        val pagesList = mutableListOf<DocumentPage>()
        var currentWordIndex = 0
        var pageNumber = 1
        while (currentWordIndex < words.size) {
            var endIndex = (currentWordIndex + Constants.WORDS_PER_PAGE_ESTIMATION).coerceAtMost(words.size)

            if (endIndex < words.size) {
                val scanFloor = (endIndex - Constants.PAGE_BREAK_SCAN_LIMIT).coerceAtLeast(currentWordIndex + 1)
                var bestBreak = -1
                for (i in endIndex - 1 downTo scanFloor) {
                    val wordEnd = charStarts[i] + words[i].length
                    val nextWordStart = charStarts[i + 1]
                    val sep = joined.substring(wordEnd, nextWordStart)
                    if ('\n' in sep) {
                        bestBreak = i + 1
                        break
                    }
                }
                if (bestBreak > 0) endIndex = bestBreak
            }

            val contentStart = charStarts[currentWordIndex]
            val contentEnd = if (endIndex < words.size) {
                val lastWord = endIndex - 1
                charStarts[lastWord] + words[lastWord].length
            } else {
                charStarts[words.size]
            }
            val pageContent = joined.substring(contentStart, contentEnd)
            val pageWordStarts = IntArray(endIndex - currentWordIndex) { i ->
                charStarts[currentWordIndex + i] - contentStart
            }

            pagesList.add(
                DocumentPage(
                    pageNumber = pageNumber,
                    content = pageContent,
                    wordCount = endIndex - currentWordIndex,
                    startWordIndex = currentWordIndex,
                    endWordIndex = endIndex - 1,
                    wordStarts = pageWordStarts
                )
            )
            currentWordIndex = endIndex
            pageNumber++
        }
        return pagesList
    }

    /**
     * Join [pageWords] with single-space separators and produce the per-word char-start array in
     * one pass. Mirrors the joiner used to build [cachedContinuousJoined] for the continuous-mode
     * cache, but at page granularity. `wordStarts[i]` is the offset of word `i`'s first char
     * inside the returned content. Words blanked by the artifact filter contribute neither text
     * nor a separator; their start collapses onto the current write position, so the starts
     * array stays monotonic and highlight lookups treat them as zero-length (not highlightable).
     */
    private fun joinWithStarts(pageWords: List<String>): Pair<String, IntArray> {
        val (content, starts, _) = joinWithStartsAndFigures(pageWords, emptyMap(), 0)
        return content to starts
    }

    /**
     * [joinWithStarts] plus inline figure placeholders. [insertBeforeWord] maps a word
     * position (0..size — size means "after the last word") to the local figure ordinals to
     * mount there; each figure contributes a `\n￼\n` run whose U+FFFC offset is
     * reported in the returned offsets array (aligned to local ordinals, -1 if unplaced).
     * Placeholders are separator-level content: word count and word starts stay aligned to
     * the shared absolute index space.
     */
    private fun joinWithStartsAndFigures(
        pageWords: List<String>,
        insertBeforeWord: Map<Int, List<Int>>,
        figureCount: Int
    ): Triple<String, IntArray, IntArray> {
        val starts = IntArray(pageWords.size)
        val figureOffsets = IntArray(figureCount) { -1 }
        val sb = StringBuilder()
        var needSeparator = false

        fun insertFiguresAt(position: Int) {
            val ordinals = insertBeforeWord[position] ?: return
            for (ordinal in ordinals) {
                if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                if (ordinal in figureOffsets.indices) figureOffsets[ordinal] = sb.length
                sb.append(Constants.FIGURE_PLACEHOLDER_CHAR)
                sb.append('\n')
                needSeparator = false
            }
        }

        for (i in pageWords.indices) {
            insertFiguresAt(i)
            val word = pageWords[i]
            if (word.isEmpty()) {
                starts[i] = sb.length
                continue
            }
            if (needSeparator) sb.append(' ')
            starts[i] = sb.length
            sb.append(word)
            needSeparator = true
        }
        insertFiguresAt(pageWords.size)
        return Triple(sb.toString(), starts, figureOffsets)
    }

    /**
     * Rendered inline bitmap for a figure by its document-wide index. Suspends through the
     * memory/disk/render cascade in [FigureImageStore]; null when the figure cannot be
     * produced (the adapter keeps its placeholder).
     */
    suspend fun getFigureBitmap(figureIndex: Int): Bitmap? {
        val document = _currentDocument.value ?: return null
        val region = figureRegionList.getOrNull(figureIndex) ?: return null
        return figureImageStore.getFigureBitmap(document, region, figureIndex)
    }

    /** Full source-page render behind an inline figure, for the tap-to-zoom viewer. */
    suspend fun renderFigureSourcePage(figureIndex: Int): Bitmap? {
        val document = _currentDocument.value ?: return null
        val region = figureRegionList.getOrNull(figureIndex) ?: return null
        return figureImageStore.renderFullPage(document, region.pageNumber)
    }

    fun getFigureSourcePageNumber(figureIndex: Int): Int? =
        figureRegionList.getOrNull(figureIndex)?.pageNumber

    /**
     * Public wrapper around the internal page-lookup so the activity can resync the paged
     * RecyclerView after the user has been reading in continuous mode (the RV's current
     * position is whatever page they last paged-mode-viewed; we want it on the word they
     * just left in continuous mode).
     */
    fun findPageIndexForWord(wordPosition: Int): Int = findPageForWordPosition(wordPosition)

    /**
     * Raw document text used for tokenization. Exposed so the activity can hand the same
     * text to the shared PlaybackCoordinator for TTS playback — TextProcessor in the engine
     * will tokenize it identically, so word indices stay aligned across RSVP, Page View
     * highlight, and TTS speech.
     */
    fun getRawText(): String {
        if (currentText.isNotEmpty()) return currentText
        val doc = _currentDocument.value
        if (doc != null && !doc.isContentExternal && doc.content.isNotEmpty()) {
            currentText = doc.content
        }
        return currentText
    }

    /**
     * Total word count for this document (single-token chunks). Activity uses this to detect
     * whether the shared PlaybackCoordinator already has the same document loaded so it can
     * skip a redundant tokenization pass before TTS playback. Reads the post-load cache —
     * never re-tokenizes — so callers on Main don't pay an O(N) walk.
     */
    fun getTotalWordCount(): Int = cachedTokenizedWords.size

    /**
     * O(1) char range of the given absolute word index inside `continuousText.value`. Returns
     * null for out-of-range indices or before the cache is populated. Replaces the previous
     * activity-side `findCharRangeForWord` linear walk that was called per spoken TTS word —
     * on a 100k-character document that walk was ~ms per call, so highlight updates during
     * narration could starve the main thread.
     */
    fun getWordCharRange(wordIndex: Int): IntRange? {
        val starts = cachedWordCharStarts
        if (wordIndex < 0 || wordIndex >= cachedTokenizedWords.size) return null
        if (starts.size < wordIndex + 2) return null
        val start = starts[wordIndex]
        val wordLen = cachedTokenizedWords[wordIndex].length
        return start until (start + wordLen)
    }

    /**
     * Small text window around a given absolute word index. Used by continuous-mode TTS so the
     * live spoken-word highlight can update inside a bounded snippet rather than a single giant
     * full-document TextView.
     */
    fun getContinuousTextWindowAroundWord(
        wordIndex: Int,
        wordsBefore: Int,
        wordsAfter: Int
    ): ContinuousTextWindow? {
        val words = cachedTokenizedWords
        if (words.isEmpty()) return null
        val safeWord = wordIndex.coerceIn(0, words.size - 1)
        val safeBefore = wordsBefore.coerceAtLeast(0)
        val safeAfter = wordsAfter.coerceAtLeast(0)
        val start = (safeWord - safeBefore).coerceAtLeast(0)
        val endExclusive = (safeWord + safeAfter + 1).coerceAtMost(words.size)
        return getContinuousTextWindowForWordRange(start, endExclusive - 1)
    }

    /**
     * Snippet builder that takes an explicit absolute word range, used by the continuous-mode
     * TTS preview to align the snippet to body line boundaries. Caller is responsible for
     * choosing [startWordIndex] and [endWordIndex] such that they coincide with the first and
     * last words on a contiguous run of body lines — that's what makes the preview's TextView
     * wrap identically to the body and the snippet's pixel height match body's coverage.
     */
    fun getContinuousTextWindowForWordRange(
        startWordIndex: Int,
        endWordIndex: Int
    ): ContinuousTextWindow? {
        val words = cachedTokenizedWords
        if (words.isEmpty()) return null
        val safeStart = startWordIndex.coerceIn(0, words.size - 1)
        val safeEnd = endWordIndex.coerceIn(safeStart, words.size - 1)
        
        val startChar = cachedWordCharStarts[safeStart]
        val endChar = cachedWordCharStarts[safeEnd] + words[safeEnd].length
        val content = cachedContinuousJoined.substring(startChar, endChar)
        
        val size = safeEnd - safeStart + 1
        val wordStarts = IntArray(size)
        val wordLengths = IntArray(size)
        
        for (i in 0 until size) {
            val absIndex = safeStart + i
            wordStarts[i] = cachedWordCharStarts[absIndex] - startChar
            wordLengths[i] = words[absIndex].length
        }
        
        return ContinuousTextWindow(
            startWordIndex = safeStart,
            endWordIndex = safeEnd,
            content = content,
            wordStarts = wordStarts,
            wordLengths = wordLengths
        )
    }

    /**
     * Inverse of [getWordCharRange]: maps a char offset inside `continuousText.value` to the
     * containing absolute word index via binary search over `cachedWordCharStarts`. Replaces
     * an activity-side O(charOffset) whitespace-counting walk that was firing from the
     * NestedScrollView's onScrollChanged listener — at the bottom of a long document each
     * tick re-walked every char from 0, producing visible scroll lag. Lookup is O(log N) in
     * the word count.
     *
     * Whitespace positions resolve to the preceding word, matching the prior linear walker
     * (the topmost-visible "word" at a separator is the word that just ended).
     */
    fun getWordIndexForCharOffset(charOffset: Int, preferNext: Boolean = false): Int {
        val starts = cachedWordCharStarts
        val wordCount = cachedTokenizedWords.size
        if (wordCount == 0 || charOffset <= 0) return 0
        // Search domain is the word-start positions starts[0..wordCount-1]; starts[wordCount]
        // is the end-of-text sentinel and intentionally excluded so a charOffset past the
        // last word's start clamps to the last word rather than reporting an invalid index.
        val r = starts.binarySearch(charOffset, 0, wordCount)
        val idx = if (r >= 0) {
            r
        } else {
            val insertPoint = -(r + 1)
            if (preferNext) {
                insertPoint
            } else {
                insertPoint - 1
            }
        }
        return idx.coerceIn(0, wordCount - 1)
    }

    fun findPageForWordPosition(wordPosition: Int): Int {
        val pagesList = _pages.value
        if (pagesList.isEmpty() || wordPosition < 0) return 0
        
        // Find the page containing this word position
        for (i in pagesList.indices) {
            val page = pagesList[i]
            if (wordPosition >= page.startWordIndex && wordPosition <= page.endWordIndex) {
                return i
            }
        }
        
        // If not found, find the closest page
        // If position is before the first page, return 0
        if (wordPosition < pagesList.first().startWordIndex) {
            return 0
        }
        
        // If position is beyond the last page, return the last page
        if (wordPosition > pagesList.last().endWordIndex) {
            return pagesList.size - 1
        }
        
        // If we still haven't found it, find the closest page
        var closestPageIndex = 0
        var minDistance = Int.MAX_VALUE
        
        for (i in pagesList.indices) {
            val page = pagesList[i]
            val distance = when {
                wordPosition < page.startWordIndex -> page.startWordIndex - wordPosition
                wordPosition > page.endWordIndex -> wordPosition - page.endWordIndex
                else -> 0 // Should have been caught above, but just in case
            }
            
            if (distance < minDistance) {
                minDistance = distance
                closestPageIndex = i
            }
        }
        
        return closestPageIndex
    }

    fun getWordPositionForPage(pageIndex: Int): Int {
        val pagesList = _pages.value
        if (pageIndex < 0 || pageIndex >= pagesList.size) return 0
        val page = pagesList[pageIndex]
        // If the RSVP-left-off word is on this page, prefer it over the page's first word so
        // switching back to RSVP resumes exactly where the user left off rather than jumping
        // to the start of the page they were browsing.
        val highlight = _highlightedWordIndex.value
        return if (highlight != null && highlight in page.startWordIndex..page.endWordIndex) {
            highlight
        } else {
            page.startWordIndex
        }
    }

    /**
     * Returns the page number to DISPLAY to the user at the given RecyclerView index. For
     * documents with real page boundaries (PDFs, page-mapped EPUBs) this is the source page
     * number — so the indicator lines up with the printed page number rather than a sequential
     * chunking index. For documents without boundaries it falls back to index+1.
     */
    fun getDisplayPageInfo(pageIndex: Int): DisplayPageInfo {
        return if (_isPdfMode.value) {
            val total = _pdfPages.value.size
            val current = (pageIndex + 1).coerceIn(1, maxOf(1, total))
            DisplayPageInfo(current, total)
        } else {
            val pages = _pages.value
            if (pages.isNotEmpty() && pageIndex in pages.indices) {
                val current = pages[pageIndex].pageNumber
                // Max pageNumber, not list size — real total survives empty-content filtering
                // so boundary-based docs still report the document's true page count.
                val total = pages.maxOf { it.pageNumber }
                DisplayPageInfo(current, total)
            } else {
                DisplayPageInfo(0, 0)
            }
        }
    }

    /** Find RecyclerView index for a user-typed page number (handles real boundary numbers). */
    fun findIndexForDisplayPageNumber(displayPageNumber: Int): Int {
        return if (_isPdfMode.value) {
            (displayPageNumber - 1).coerceIn(0, maxOf(0, _pdfPages.value.size - 1))
        } else {
            val pages = _pages.value
            if (pages.isEmpty()) return 0
            val exact = pages.indexOfFirst { it.pageNumber == displayPageNumber }
            if (exact >= 0) return exact
            // Pick the closest real page number when exact match is missing (filtered boundary).
            pages.withIndex().minBy { kotlin.math.abs(it.value.pageNumber - displayPageNumber) }.index
        }
    }

    private suspend fun ensureCurrentTextLoaded(): String {
        if (currentText.isNotBlank()) return currentText
        var document = _currentDocument.value
        if (document == null && !currentContentHash.isNullOrBlank()) {
            document = savedDocumentRepository.getDocumentByContentHash(currentContentHash!!)
            if (document != null) {
                _currentDocument.value = document
            }
        }
        if (document != null) {
            val content = loadDocumentContent(document)
            if (content.isNotBlank()) {
                currentText = content
            }
        }
        return currentText
    }

    suspend fun getBookmarksForCurrentDocument(): List<com.speedread.rsvp.data.bookmark.BookmarkWithProgress> {
        val text = ensureCurrentTextLoaded()
        if (text.isNotBlank()) {
            bookmarkRepository.migrateLegacyHashIfPresent(text)
            return bookmarkRepository.getBookmarksForText(text).first()
        }
        val hash = currentContentHash ?: _currentDocument.value?.contentHash
        if (!hash.isNullOrBlank()) {
            return bookmarkRepository.getBookmarksForHash(hash).first()
        }
        return emptyList()
    }

    fun createBookmark(title: String, currentPageIndex: Int) {
        viewModelScope.launch {
            try {
                val text = ensureCurrentTextLoaded()
                if (text.isBlank()) {
                    Logger.w("PageViewViewModel", "Cannot create bookmark: document text is empty")
                    return@launch
                }
                val pagesList = _pages.value
                if (currentPageIndex >= 0 && currentPageIndex < pagesList.size) {
                    val page = pagesList[currentPageIndex]

                    bookmarkRepository.createBookmark(
                        title = title,
                        fullText = text,
                        wordPosition = page.startWordIndex,
                        pageNumber = page.pageNumber,
                        wpm = rsvpSettingsManager.getCurrentRsvpSettings().wpm,
                        source = currentTextSource,
                        sourceUri = null,
                        isAutoBookmark = false
                    )

                    // Could show a success message here
                }
            } catch (e: Exception) {
                Logger.e("PageViewViewModel", "createBookmarkForCurrentPage failed", e)
                _errorMessage.value = "Failed to create bookmark: ${e.message}"
            }
        }
    }

    /**
     * Word-precise bookmark for continuous (endless-scroll) mode where there is no discrete
     * "current page". The page number stored alongside the word position is the page that
     * contains the word, so RSVP / paged-view can still display it on a sensible page.
     */
    fun createBookmarkAtWord(title: String, wordPosition: Int) {
        viewModelScope.launch {
            try {
                val text = ensureCurrentTextLoaded()
                if (text.isBlank()) {
                    Logger.w("PageViewViewModel", "Cannot create bookmark: document text is empty")
                    return@launch
                }
                val safeWord = wordPosition.coerceAtLeast(0)
                val pagesList = _pages.value
                val pageNumber = if (pagesList.isNotEmpty()) {
                    val pageIdx = findPageForWordPosition(safeWord).coerceIn(0, pagesList.size - 1)
                    pagesList[pageIdx].pageNumber
                } else {
                    1
                }
                bookmarkRepository.createBookmark(
                    title = title,
                    fullText = text,
                    wordPosition = safeWord,
                    pageNumber = pageNumber,
                    wpm = rsvpSettingsManager.getCurrentRsvpSettings().wpm,
                    source = currentTextSource,
                    sourceUri = null,
                    isAutoBookmark = false
                )
            } catch (e: Exception) {
                Logger.e("PageViewViewModel", "createBookmarkAtWord failed", e)
                _errorMessage.value = "Failed to create bookmark: ${e.message}"
            }
        }
    }

    private suspend fun tryLoadPdfForRendering(document: SavedDocument, currentWordPosition: Int) {
        try {
            // Try to load original PDF file for rendering
            val originalPdfPath = document.originalUri
            if (originalPdfPath != null) {
                val uri = android.net.Uri.parse(originalPdfPath)
                val inputStream = context.contentResolver.openInputStream(uri)
                
                if (inputStream != null) {
                    val result = inputStream.use { stream -> pdfRenderer.initializePdf(stream) }
                    val pageCount = result.getOrNull()
                    if (pageCount != null) {
                        _isPdfMode.value = true
                        setupPdfPages(pageCount, currentWordPosition)
                    } else {
                        Logger.w(
                            "PageViewViewModel",
                            "Failed to render PDF natively, falling back to text mode: ${result.exceptionOrNull()?.message}"
                        )
                        fallbackToTextMode(currentWordPosition)
                    }
                } else {
                    Logger.w("PageViewViewModel", "Failed to open input stream for PDF")
                    fallbackToTextMode(currentWordPosition)
                }
            } else {
                Logger.w("PageViewViewModel", "No original PDF path available for document: ${document.title}")
                Logger.d("PageViewViewModel", "Document details: isContentExternal=${document.isContentExternal}, source=${document.source}")
                fallbackToTextMode(currentWordPosition)
            }
        } catch (e: Exception) {
            Logger.w("PageViewViewModel", "Exception loading PDF for rendering", e)
            fallbackToTextMode(currentWordPosition)
        }
    }
    
    private fun setupPdfPages(pageCount: Int, currentWordPosition: Int) {
        val pdfPageItems = (0 until pageCount).map { pageIndex ->
            PdfPageItem(
                pageIndex = pageIndex,
                renderedPage = null,
                isLoading = false,
                error = null
            )
        }
        
        _pdfPages.value = pdfPageItems
        
        // Find target page based on word position and page boundaries
        val targetPageIndex = findPdfPageForWordPosition(currentWordPosition)
        _currentPageIndex.value = targetPageIndex
        
        Logger.d("PageViewViewModel", "PDF mode: pageCount=$pageCount, targetPage=$targetPageIndex, wordPosition=$currentWordPosition")
    }
    
    private suspend fun fallbackToTextMode(currentWordPosition: Int) {
        _isPdfMode.value = false

        // Process as regular text document
        val documentPageBoundaries = parsePageBoundaries(_currentDocument.value?.pageBoundariesJson)
        pageBoundaries = documentPageBoundaries
        processTextIntoPages()

        // Find the page containing the current word position
        val targetPageIndex = findPageForWordPosition(currentWordPosition)
        _currentPageIndex.value = targetPageIndex
    }

    private fun findPdfPageForWordPosition(wordPosition: Int): Int {
        // Use page boundaries if available to map word position to PDF page
        val boundaries = pageBoundaries
        if (boundaries != null && boundaries.isNotEmpty()) {
            for (boundary in boundaries) {
                if (wordPosition >= boundary.startWordIndex && wordPosition <= boundary.endWordIndex) {
                    // PDF pages are 1-based, convert to 0-based index
                    return (boundary.pageNumber - 1).coerceIn(0, _pdfPages.value.size - 1)
                }
            }
        }

        // Fallback: estimate based on total words and pages. Reads the post-load cache
        // so this stays cheap on Main even for novel-sized documents.
        val totalPdfPages = _pdfPages.value.size
        if (totalPdfPages > 0 && currentText.isNotBlank()) {
            val totalWords = cachedTokenizedWords.size
            if (totalWords > 0) {
                val estimatedPageIndex = ((wordPosition.toFloat() / totalWords) * totalPdfPages).toInt()
                return estimatedPageIndex.coerceIn(0, totalPdfPages - 1)
            }
        }

        return 0
    }
    
    fun renderPdfPage(pageIndex: Int) {
        if (!_isPdfMode.value) return

        // Idempotence guard. Three independent paths can target the same page on a single
        // navigation event: the activity's `onPageVisible` callback when a holder binds an
        // unrendered slot, the activity's currentPageIndex collector when the user lands on
        // a page, and `preloadPdfPages` when that same page falls inside a neighbour's
        // preload window. Without this short-circuit a page that was already rendered (or is
        // mid-render) would re-enter the suspending render path; the loading-state emit
        // BEFORE the bitmap completes would briefly flip `isLoading=true` on a slot whose
        // bitmap is already on screen, flickering the cell to the spinner during page turns.
        // `preloadPdfPages` already gates this same condition before calling here; this
        // guard centralises the rule so the activity-side callsites don't need to duplicate
        // it. Errored slots (renderedPage=null, isLoading=false, error!=null) are not
        // guarded out — calling renderPdfPage on them is a deliberate retry path.
        val existing = _pdfPages.value.getOrNull(pageIndex) ?: return
        if (existing.renderedPage != null || existing.isLoading) return

        viewModelScope.launch {
            // Atomically update the page to show loading
            _pdfPages.update { pages ->
                if (pageIndex !in pages.indices) pages
                else pages.toMutableList().apply {
                    this[pageIndex] = this[pageIndex].copy(isLoading = true, error = null)
                }
            }
            
            // Render the page
            val result = pdfRenderer.renderPage(pageIndex)
            result.fold(
                onSuccess = { renderedPage ->
                    _pdfPages.update { pages ->
                        if (pageIndex !in pages.indices) pages
                        else pages.toMutableList().apply {
                            this[pageIndex] = this[pageIndex].copy(
                                renderedPage = renderedPage,
                                isLoading = false,
                                error = null
                            )
                        }
                    }
                },
                onFailure = { exception ->
                    _pdfPages.update { pages ->
                        if (pageIndex !in pages.indices) pages
                        else pages.toMutableList().apply {
                            this[pageIndex] = this[pageIndex].copy(
                                isLoading = false,
                                error = "Failed to render: ${exception.message}"
                            )
                        }
                    }
                }
            )
        }
    }
    
    fun preloadPdfPages(centerIndex: Int, range: Int = Constants.PDF_PRELOAD_RANGE) {
        if (!_isPdfMode.value) return

        val startIndex = (centerIndex - range).coerceAtLeast(0)
        val endIndex = (centerIndex + range).coerceAtMost(_pdfPages.value.size - 1)

        for (i in startIndex..endIndex) {
            val pageItem = _pdfPages.value.getOrNull(i)
            if (pageItem != null && pageItem.renderedPage == null && !pageItem.isLoading) {
                renderPdfPage(i)
            }
        }

        evictDistantPdfPages(centerIndex)
    }

    /**
     * Drop bitmap references for any rendered page outside `centerIndex ± keepRange` so
     * swiping through a long PDF cannot pin every visited page in memory. Bitmaps are
     * cleared by reference only — `Bitmap.recycle()` is intentionally NOT called here
     * because a freshly off-screen page may still be held by an off-screen-but-cached
     * RecyclerView holder, and recycling would crash the next draw of that holder.
     * GC reclaims native pixel memory once the holder rebinds to its new state and the
     * previous list snapshot is unreachable; `onCleared` does the eager recycle on
     * activity finish where no holders survive.
     *
     * `keepRange` defaults to `Constants.PDF_BITMAP_KEEP_RANGE`, which is intentionally
     * larger than `PDF_PRELOAD_RANGE` so a page just preloaded by the same navigation
     * event is not immediately evicted on the same call.
     *
     * The `centerIndex` argument represents the "user's current page" only loosely — this
     * function is called from `preloadPdfPages`, which the activity invokes both from real
     * navigation events (scroll-end, ViewModel `currentPageIndex` updates) AND from the
     * adapter's per-holder `onPageVisible` callback. In the latter case `centerIndex` is
     * the binding holder's page, which RecyclerView guarantees to be within prefetch
     * distance of the user's actual visible page. If RecyclerView's prefetch distance ever
     * grows beyond `keepRange - 1`, eviction could begin clipping pages adjacent to the
     * visible viewport — adjust `PDF_BITMAP_KEEP_RANGE` upward in that case.
     */
    private fun evictDistantPdfPages(centerIndex: Int, keepRange: Int = Constants.PDF_BITMAP_KEEP_RANGE) {
        val keepStart = centerIndex - keepRange
        val keepEnd = centerIndex + keepRange
        _pdfPages.update { current ->
            if (current.isEmpty()) return@update current
            var changed = false
            // Only `renderedPage` carries memory weight; `isLoading` and `error` are tiny flags
            // and the gate `renderedPage != null` already implies (per `renderPdfPage`'s success
            // path) that `isLoading=false` and `error=null`, so clearing only `renderedPage`
            // produces an identical result with less noise in the diff.
            val updated = current.map { item ->
                if (item.renderedPage != null && (item.pageIndex < keepStart || item.pageIndex > keepEnd)) {
                    changed = true
                    item.copy(renderedPage = null)
                } else {
                    item
                }
            }
            if (changed) updated else current
        }
    }

    fun switchToPdfMode() {
        val document = _currentDocument.value
        if (document?.source == BookmarkSource.FILE_PDF && document.originalUri != null) {
            Logger.d("PageViewViewModel", "Switching to PDF mode")
            val currentWordPosition = getCurrentWordPosition()
            viewModelScope.launch {
                tryLoadPdfForRendering(document, currentWordPosition)
            }
        } else {
            _errorMessage.value = "PDF mode not available for this document"
        }
    }
    
    fun switchToTextMode() {
        Logger.d("PageViewViewModel", "Switching to text mode")
        val currentWordPosition = getCurrentWordPosition()
        viewModelScope.launch {
            fallbackToTextMode(currentWordPosition)
        }
    }
    
    private fun getCurrentWordPosition(): Int {
        // Get current word position based on current page and mode
        val currentPageIdx = _currentPageIndex.value
        if (currentPageIdx < 0) return 0
        
        return if (_isPdfMode.value) {
            // In PDF mode, estimate word position from page boundaries
            val boundaries = pageBoundaries
            if (boundaries != null && currentPageIdx < boundaries.size) {
                boundaries[currentPageIdx].startWordIndex
            } else {
                // Estimate based on page ratio. Cache hit — no re-tokenization on Main.
                val totalWords = cachedTokenizedWords.size
                val totalPages = _pdfPages.value.size
                if (totalPages > 0) {
                    ((currentPageIdx.toFloat() / totalPages) * totalWords).toInt()
                } else {
                    0
                }
            }
        } else {
            // Delegate to getWordPositionForPage so the highlight-aware logic applies here too
            // (text → PDF mode switches should preserve the exact word, not jump to page start).
            getWordPositionForPage(currentPageIdx)
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        runBlocking {
            pdfRenderer.closePdf()
        }
        // Eagerly recycle any pinned page bitmaps so native pixel memory is reclaimed
        // immediately on Page View exit instead of waiting for finalizer-driven GC.
        // Safe here because `onCleared` runs after the activity's `onDestroy`, so no
        // RecyclerView holder remains alive to draw a recycled bitmap.
        _pdfPages.value.forEach { item ->
            val bitmap = item.renderedPage?.bitmap
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }
}

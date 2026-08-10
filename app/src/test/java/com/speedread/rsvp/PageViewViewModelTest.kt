package com.speedread.rsvp

import android.content.Context
import com.speedread.rsvp.data.bookmark.BookmarkRepository
import com.speedread.rsvp.data.bookmark.BookmarkSource
import com.speedread.rsvp.data.document.SavedDocument
import com.speedread.rsvp.data.document.SavedDocumentRepository
import com.speedread.rsvp.engine.DefaultTextProcessor
import com.speedread.rsvp.pdf.PdfPageRenderer
import io.mockk.coEvery
import io.mockk.mockk
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-logic tests for PageViewViewModel. Covers pagination math (no-boundaries path),
 * word-to-page mapping, highlight-aware position sync (v1.13.13 regression guard), and
 * display-page-number translation — all via the public loadTextDocument() entry point so
 * no Room, Hilt, or Android framework is involved.
 *
 * PDF-specific paths, page-boundary JSON parsing, and bookmark creation are intentionally
 * out of scope for this first test pass (they require heavier mocking / real Android APIs).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageViewViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: PageViewViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = PageViewViewModel(
            context = mockk<Context>(relaxed = true),
            bookmarkRepository = mockk<BookmarkRepository>(relaxed = true),
            savedDocumentRepository = mockk<SavedDocumentRepository>(relaxed = true),
            pdfRenderer = mockk<PdfPageRenderer>(relaxed = true),
            rsvpSettingsManager = mockk<RsvpSettingsManager>(relaxed = true),
            textProcessor = DefaultTextProcessor(),
            tokenCache = PageViewTokenCache(mockk<PageViewTokenCacheStore>(relaxed = true)),
            figureImageStore = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildWords(count: Int): String =
        (1..count).joinToString(" ") { "w$it" }

    private suspend fun TestScope.loadAndSettle(text: String, title: String?, pos: Int = 0) {
        viewModel.loadTextDocument(text, title, pos)
        // viewModelScope.launch dispatches tokenization to Dispatchers.Default (real thread).
        // Thread.sleep lets that work complete; advanceUntilIdle then processes the resumed
        // continuation on the test dispatcher so StateFlows are populated before assertions.
        Thread.sleep(SETTLE_MS)
        advanceUntilIdle()
    }

    companion object {
        private const val SETTLE_MS = 100L
    }

    @Test
    fun loadTextDocument_splits600WordsIntoThreePagesOf250() = runTest {
        loadAndSettle(buildWords(600), "doc")

        val pages = viewModel.pages.value
        assertEquals(3, pages.size)
        assertEquals(0, pages[0].startWordIndex)
        assertEquals(249, pages[0].endWordIndex)
        assertEquals(250, pages[0].wordCount)
        assertEquals(250, pages[1].startWordIndex)
        assertEquals(499, pages[1].endWordIndex)
        assertEquals(500, pages[2].startWordIndex)
        assertEquals(599, pages[2].endWordIndex)
        assertEquals(100, pages[2].wordCount)
    }

    @Test
    fun loadTextDocument_exactMultipleOfChunk_createsEvenPages() = runTest {
        loadAndSettle(buildWords(500), "doc")

        val pages = viewModel.pages.value
        assertEquals(2, pages.size)
        assertEquals(0, pages[0].startWordIndex)
        assertEquals(249, pages[0].endWordIndex)
        assertEquals(250, pages[1].startWordIndex)
        assertEquals(499, pages[1].endWordIndex)
    }

    @Test
    fun loadTextDocument_fewerWordsThanChunk_createsOnePage() = runTest {
        loadAndSettle(buildWords(10), "doc")

        val pages = viewModel.pages.value
        assertEquals(1, pages.size)
        assertEquals(0, pages[0].startWordIndex)
        assertEquals(9, pages[0].endWordIndex)
        assertEquals(10, pages[0].wordCount)
    }

    @Test
    fun loadTextDocument_blankText_createsNoPages() = runTest {
        loadAndSettle("", "doc")

        assertTrue(viewModel.pages.value.isEmpty())
    }

    @Test
    fun loadTextDocument_wordInMiddlePage_seeksToThatPage() = runTest {
        loadAndSettle(buildWords(600), "doc", 400)

        // word 400 lives on page index 1 (250..499)
        assertEquals(1, viewModel.currentPageIndex.value)
    }

    @Test
    fun loadTextDocument_wordBeyondEnd_lastPage() = runTest {
        loadAndSettle(buildWords(600), "doc", 9_999)

        assertEquals(2, viewModel.currentPageIndex.value)
    }

    @Test
    fun loadTextDocument_negativeWordPosition_firstPage() = runTest {
        loadAndSettle(buildWords(600), "doc", -5)

        assertEquals(0, viewModel.currentPageIndex.value)
    }

    @Test
    fun getWordPositionForPage_highlightWithinPage_returnsHighlightedIndex() = runTest {
        loadAndSettle(buildWords(600), "doc", 400)

        // currentPageIndex is 1, highlight is 400 which is within [250, 499]
        assertEquals(400, viewModel.getWordPositionForPage(1))
    }

    @Test
    fun getWordPositionForPage_highlightOnDifferentPage_returnsPageStart() = runTest {
        loadAndSettle(buildWords(600), "doc", 400)

        // Highlight is 400 (page 1), but user has navigated to page 2 — should return 500
        // (page 2 start), not 400.
        assertEquals(500, viewModel.getWordPositionForPage(2))
    }

    @Test
    fun getWordPositionForPage_invalidIndex_returnsZero() = runTest {
        loadAndSettle(buildWords(600), "doc")

        assertEquals(0, viewModel.getWordPositionForPage(-1))
        assertEquals(0, viewModel.getWordPositionForPage(999))
    }

    @Test
    fun getDisplayPageInfo_noBoundaries_usesPageNumberField() = runTest {
        loadAndSettle(buildWords(600), "doc")

        // Artificial pagination assigns pageNumber = 1..N. getDisplayPageInfo uses
        // pages[index].pageNumber for current and max(pageNumber) for total.
        val info = viewModel.getDisplayPageInfo(pageIndex = 0)
        assertEquals(1, info.current)
        assertEquals(3, info.total)

        val mid = viewModel.getDisplayPageInfo(pageIndex = 1)
        assertEquals(2, mid.current)
        assertEquals(3, mid.total)
    }

    @Test
    fun findIndexForDisplayPageNumber_matchingPage_returnsIndex() = runTest {
        loadAndSettle(buildWords(600), "doc")

        // Page numbers are 1,2,3 → indices 0,1,2
        assertEquals(0, viewModel.findIndexForDisplayPageNumber(1))
        assertEquals(1, viewModel.findIndexForDisplayPageNumber(2))
        assertEquals(2, viewModel.findIndexForDisplayPageNumber(3))
    }

    @Test
    fun loadTextDocument_setsHighlightedWordIndex() = runTest {
        loadAndSettle(buildWords(600), "doc", 123)

        assertEquals(123, viewModel.highlightedWordIndex.value)
    }

    @Test
    fun loadTextDocument_handlesMultipleWhitespaceKinds() = runTest {
        loadAndSettle("one\r\ntwo\tthree   four\nfive", "doc")

        val pages = viewModel.pages.value
        assertEquals(1, pages.size)
        assertEquals(5, pages[0].wordCount)
    }

    @Test
    fun loadTextDocument_titleIsStored() = runTest {
        loadAndSettle(buildWords(10), "My Title")

        assertEquals("My Title", viewModel.documentTitle.value)
    }

    @Test
    fun loadTextDocument_nullTitle_fallsBackToDefault() = runTest {
        loadAndSettle(buildWords(10), null)

        assertNotNull(viewModel.documentTitle.value)
        assertEquals("Document", viewModel.documentTitle.value)
    }

    @Test
    fun boundaryPages_mountFigurePlaceholderAndBlankLabelsInBothVariants() = runTest {
        val store = mockk<PageViewTokenCacheStore>(relaxed = true)
        coEvery { store.hasCache(any(), any()) } returns false
        coEvery { store.load(any()) } returns null
        coEvery { store.loadDisplay(any(), any()) } returns null

        // Four plain pages of 25 words; page 2 carries a figure anchored after its 10th
        // word, whose stray labels are the 11th-13th words (typical diagram-label garbage).
        val allWords = mutableListOf<String>()
        val boundaryJson = StringBuilder("[")
        repeat(4) { i ->
            val pageWords = (0 until 25).map { "p${i}w$it" }
            val start = allWords.size
            allWords.addAll(pageWords)
            if (i > 0) boundaryJson.append(",")
            boundaryJson.append(
                """{"pageNumber":${i + 1},"startWordIndex":$start,"endWordIndex":${allWords.size - 1},"wordCount":${pageWords.size}}"""
            )
        }
        boundaryJson.append("]")
        val page2Start = 25
        val anchor = page2Start + 9
        val labelStart = page2Start + 10
        val labelEnd = page2Start + 12
        val figuresJson =
            """[{"pageNumber":2,"left":0.1,"top":0.3,"width":0.8,"height":0.2,"aspectRatio":0.25,""" +
                """"anchorWordIndex":$anchor,"labelStartWordIndex":$labelStart,"labelEndWordIndex":$labelEnd}]"""

        val document = SavedDocument(
            id = 8L,
            title = "Figured",
            content = allWords.joinToString(" "),
            contentHash = "hash-figure-test",
            source = BookmarkSource.MANUAL_TEXT,
            createdAt = Date(0),
            lastAccessedAt = Date(0),
            pageBoundariesJson = boundaryJson.toString(),
            figureRegionsJson = figuresJson
        )
        val repository = mockk<SavedDocumentRepository>(relaxed = true)
        coEvery { repository.getDocumentById(8L) } returns document

        val vm = PageViewViewModel(
            context = mockk<Context>(relaxed = true),
            bookmarkRepository = mockk<BookmarkRepository>(relaxed = true),
            savedDocumentRepository = repository,
            pdfRenderer = mockk<PdfPageRenderer>(relaxed = true),
            rsvpSettingsManager = mockk<RsvpSettingsManager>(relaxed = true),
            textProcessor = DefaultTextProcessor(),
            tokenCache = PageViewTokenCache(store),
            figureImageStore = mockk(relaxed = true)
        )
        vm.loadDocumentById(8L, 0)
        Thread.sleep(SETTLE_MS)
        advanceUntilIdle()

        val pages = vm.pages.value
        assertEquals(4, pages.size)

        val figuredPage = pages[1]
        assertEquals(1, figuredPage.figures.size)
        val figure = figuredPage.figures[0]
        assertEquals(0, figure.figureIndex)
        // The placeholder char is mounted at the reported offset in BOTH variants.
        assertEquals(Constants.FIGURE_PLACEHOLDER_CHAR, figuredPage.content[figure.contentOffset])
        assertEquals(Constants.FIGURE_PLACEHOLDER_CHAR, figuredPage.continuousContent[figure.continuousOffset])
        // The placeholder sits right after the anchor word and before what follows the labels.
        val contentTokens = figuredPage.content.split(" ", "\n").filter { it.isNotEmpty() }
        val anchorPos = contentTokens.indexOf("p1w9")
        assertTrue(anchorPos >= 0)
        assertEquals(Constants.FIGURE_PLACEHOLDER_CHAR.toString(), contentTokens[anchorPos + 1])
        // Label words are blanked from BOTH variants; neighbors survive.
        for (variant in listOf(figuredPage.content, figuredPage.continuousContent)) {
            val tokens = variant.split(" ", "\n").filter { it.isNotEmpty() }
            assertFalse(tokens.contains("p1w10"))
            assertFalse(tokens.contains("p1w11"))
            assertFalse(tokens.contains("p1w12"))
            assertTrue(tokens.contains("p1w9"))
            assertTrue(tokens.contains("p1w13"))
        }
        // Word-index space intact on every page.
        pages.forEach { page ->
            assertEquals(page.wordCount, page.wordStarts.size)
            assertEquals(page.wordCount, page.continuousWordStarts.size)
        }
        // Pages without figures are untouched.
        assertTrue(pages[0].figures.isEmpty())
        assertFalse(pages[0].content.contains(Constants.FIGURE_PLACEHOLDER_CHAR))
    }

    @Test
    fun boundaryPages_blankPageFurnitureInContinuousVariantOnly() = runTest {
        // Store stubs must return real nulls/false — the relaxed default returns non-null
        // mock objects for loadDisplay/load, which would short-circuit tokenization.
        val store = mockk<PageViewTokenCacheStore>(relaxed = true)
        coEvery { store.hasCache(any(), any()) } returns false
        coEvery { store.load(any()) } returns null
        coEvery { store.loadDisplay(any(), any()) } returns null

        // Eight pages shaped like the PDF extraction of a printed book: running header
        // "THE EGO" first, 20 body words, printed page number (offset +10 from the PDF
        // page number, as with front matter) last.
        val allWords = mutableListOf<String>()
        val boundaryJson = StringBuilder("[")
        repeat(8) { i ->
            val pageWords = listOf("THE", "EGO") + (0 until 20).map { "body${i}w$it" } + "${i + 11}"
            val start = allWords.size
            allWords.addAll(pageWords)
            if (i > 0) boundaryJson.append(",")
            boundaryJson.append(
                """{"pageNumber":${i + 1},"startWordIndex":$start,"endWordIndex":${allWords.size - 1},"wordCount":${pageWords.size}}"""
            )
        }
        boundaryJson.append("]")

        val document = SavedDocument(
            id = 7L,
            title = "Aion",
            content = allWords.joinToString(" "),
            contentHash = "hash-furniture-test",
            source = BookmarkSource.MANUAL_TEXT,
            createdAt = Date(0),
            lastAccessedAt = Date(0),
            pageBoundariesJson = boundaryJson.toString()
        )
        val repository = mockk<SavedDocumentRepository>(relaxed = true)
        coEvery { repository.getDocumentById(7L) } returns document

        val vm = PageViewViewModel(
            context = mockk<Context>(relaxed = true),
            bookmarkRepository = mockk<BookmarkRepository>(relaxed = true),
            savedDocumentRepository = repository,
            pdfRenderer = mockk<PdfPageRenderer>(relaxed = true),
            rsvpSettingsManager = mockk<RsvpSettingsManager>(relaxed = true),
            textProcessor = DefaultTextProcessor(),
            tokenCache = PageViewTokenCache(store),
            figureImageStore = mockk(relaxed = true)
        )
        vm.loadDocumentById(7L, 0)
        Thread.sleep(SETTLE_MS)
        advanceUntilIdle()

        val pages = vm.pages.value
        assertEquals(8, pages.size)
        pages.forEachIndexed { i, page ->
            val rawTokens = page.content.split(" ").filter { it.isNotEmpty() }
            val continuousTokens = page.continuousContent.split(" ").filter { it.isNotEmpty() }
            // Paged mode keeps the header and the printed page number.
            assertEquals(listOf("THE", "EGO"), rawTokens.take(2))
            assertEquals("${i + 11}", rawTokens.last())
            // Continuous mode hides them but keeps every body word.
            assertEquals("body${i}w0", continuousTokens.first())
            assertFalse(continuousTokens.contains("${i + 11}"))
            assertEquals(rawTokens.size - 3, continuousTokens.size)
            // Same word-index space: the starts arrays still cover every word, blanked or not.
            assertEquals(page.wordStarts.size, page.continuousWordStarts.size)
        }
    }
}

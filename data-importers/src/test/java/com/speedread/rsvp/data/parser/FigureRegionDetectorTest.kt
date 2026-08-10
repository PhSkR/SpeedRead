package com.speedread.rsvp.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-geometry tests for FigureRegionDetector using synthetic page layouts. Page: 500 x 700
 * points. Body lines span the full column (x 50..450); sparse lines are short label
 * fragments. Word offsets are page-relative counts in reading order.
 */
class FigureRegionDetectorTest {

    private val pageWidth = 500f
    private val pageHeight = 700f

    private fun bodyLine(top: Float, words: Int = 10) =
        FigureRegionDetector.TextLine(left = 50f, top = top, right = 450f, bottom = top + 12f, wordCount = words)

    private fun sparseLine(top: Float, left: Float = 200f, right: Float = 280f, words: Int = 2) =
        FigureRegionDetector.TextLine(left = left, top = top, right = right, bottom = top + 10f, wordCount = words)

    /** A column of body lines from [fromY] with a regular 18pt rhythm. */
    private fun bodyBlock(fromY: Float, count: Int): List<FigureRegionDetector.TextLine> =
        (0 until count).map { bodyLine(fromY + it * 18f) }

    @Test
    fun `plain body page yields no figures`() {
        val lines = bodyBlock(60f, 30)
        val figures = FigureRegionDetector.detect(pageWidth, pageHeight, lines, emptyList())
        assertTrue(figures.isEmpty())
    }

    @Test
    fun `diagram band with label cluster is detected with contiguous label range`() {
        // 10 body lines, then a 200pt band holding 4 scattered labels, then 10 more body lines.
        val topBlock = bodyBlock(60f, 10)              // words 0..99
        val labels = listOf(
            sparseLine(280f, left = 120f, right = 180f),   // words 100..101
            sparseLine(320f, left = 300f, right = 380f),   // words 102..103
            sparseLine(360f, left = 100f, right = 150f),   // words 104..105
            sparseLine(400f, left = 250f, right = 350f)    // words 106..107
        )
        val bottomBlock = bodyBlock(460f, 10)          // words 108..
        val lines = topBlock + labels + bottomBlock

        val figures = FigureRegionDetector.detect(pageWidth, pageHeight, lines, emptyList())
        assertEquals(1, figures.size)
        val figure = figures[0]
        assertEquals(100, figure.anchorWordOffset)
        assertEquals(100, figure.labelWordStart)
        assertEquals(107, figure.labelWordEnd)
        // Box hugs the label cluster (plus padding), normalized within the page.
        assertTrue(figure.top > 0.3f && figure.top < 0.45f)
        assertTrue(figure.width > 0f && figure.width <= 1f)
        assertTrue(figure.aspectRatio > 0f)
    }

    @Test
    fun `single sparse margin line is page furniture not a figure`() {
        // Running header at the top, body below — the leading band has only ONE sparse line.
        val header = sparseLine(30f, left = 220f, right = 280f)
        val lines = listOf(header) + bodyBlock(120f, 25)
        val figures = FigureRegionDetector.detect(pageWidth, pageHeight, lines, emptyList())
        assertTrue(figures.isEmpty())
    }

    @Test
    fun `pure whitespace gap without content is not a figure`() {
        // Section break: large gap between body blocks but nothing inside it.
        val lines = bodyBlock(60f, 8) + bodyBlock(450f, 8)
        val figures = FigureRegionDetector.detect(pageWidth, pageHeight, lines, emptyList())
        assertTrue(figures.isEmpty())
    }

    @Test
    fun `embedded image in a gap is detected even without labels`() {
        val lines = bodyBlock(60f, 8) + bodyBlock(500f, 8)     // gap 204..500
        val image = FigureRegionDetector.ImageBox(left = 100f, top = 240f, right = 400f, bottom = 460f)
        val figures = FigureRegionDetector.detect(pageWidth, pageHeight, lines, listOf(image))
        assertEquals(1, figures.size)
        val figure = figures[0]
        assertEquals(-1, figure.labelWordStart)
        assertEquals(-1, figure.labelWordEnd)
        // Anchored after the 8 top body lines (80 words).
        assertEquals(80, figure.anchorWordOffset)
    }

    @Test
    fun `full page plate with caption has whole-page band and caption labels`() {
        // No body lines at all: a big image plus a two-line caption under it.
        val caption = listOf(
            sparseLine(620f, left = 180f, right = 320f, words = 3),  // words 0..2
            sparseLine(640f, left = 150f, right = 350f, words = 6)   // words 3..8
        )
        val image = FigureRegionDetector.ImageBox(left = 60f, top = 50f, right = 440f, bottom = 600f)
        val figures = FigureRegionDetector.detect(pageWidth, pageHeight, caption, listOf(image))
        assertEquals(1, figures.size)
        val figure = figures[0]
        assertEquals(0, figure.anchorWordOffset)
        assertEquals(0, figure.labelWordStart)
        assertEquals(8, figure.labelWordEnd)
        // Box spans the union of image and caption.
        assertTrue(figure.height > 0.7f)
    }

    @Test
    fun `empty page yields no figures`() {
        assertTrue(FigureRegionDetector.detect(pageWidth, pageHeight, emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `dense text block in a gap - verse or footnote - is not a figure`() {
        // Six tightly-stacked narrow lines (a centered verse quote) between body blocks:
        // sparse by width, but vertically dense and word-heavy — must NOT become a figure.
        val verse = (0 until 6).map { i ->
            sparseLine(280f + i * 14f, left = 150f, right = 340f, words = 7)
        }
        val lines = bodyBlock(60f, 10) + verse + bodyBlock(430f, 10)
        assertTrue(FigureRegionDetector.detect(pageWidth, pageHeight, lines, emptyList()).isEmpty())
    }

    @Test
    fun `gibberish classifier separates OCR garbage from prose`() {
        assertTrue(FigureRegionDetector.isGibberish("bar<<3 pbv-w& *< rui>>K<<rt*>>^v<x xs&u re\$ser# <^ ntaau^ fj *>\$aftw"))
        assertTrue(!FigureRegionDetector.isGibberish("the parallel of the lapis and Mercurius with Christ and, because of"))
        assertTrue(!FigureRegionDetector.isGibberish("57 rrjv apxeyopwv (pvaiv tuv 6iuv kv Apx^ovu ffiripfiari."))
    }

    @Test
    fun `bare number classifier catches page numbers only`() {
        assertTrue(FigureRegionDetector.isBareNumber("22"))
        assertTrue(FigureRegionDetector.isBareNumber("347."))
        assertTrue(!FigureRegionDetector.isBareNumber("22a"))
        assertTrue(!FigureRegionDetector.isBareNumber("12345"))
        assertTrue(!FigureRegionDetector.isBareNumber("Cf. infra, par. 347."))
    }

    @Test
    fun `footnote tail and page number in the bottom margin are not a figure`() {
        // A paragraph's short last line right under the body block (normal line rhythm) and
        // the page number floating in the bottom margin: prose plus furniture, not a figure.
        val body = bodyBlock(60f, 20)
        val tail = sparseLine(420f, left = 50f, right = 210f, words = 3)
        val pageNumber = FigureRegionDetector.TextLine(
            left = 400f, top = 660f, right = 425f, bottom = 670f, wordCount = 1, numericOnly = true
        )
        val lines = body + listOf(tail, pageNumber)
        assertTrue(FigureRegionDetector.detect(pageWidth, pageHeight, lines, emptyList()).isEmpty())
    }

    @Test
    fun `chapter opener page with clean headings and page scan is not a figure`() {
        // No body text, two CLEAN heading lines, plus the full-page scan raster every page
        // of a scanned book carries. Chapter titles must stay in the text flow.
        val headings = listOf(
            sparseLine(200f, left = 230f, right = 270f, words = 1),
            sparseLine(400f, left = 150f, right = 350f, words = 3)
        )
        val pageScan = FigureRegionDetector.ImageBox(left = 0f, top = 0f, right = pageWidth, bottom = pageHeight)
        assertTrue(FigureRegionDetector.detect(pageWidth, pageHeight, headings, listOf(pageScan)).isEmpty())
    }

    @Test
    fun `full page diagram with many scattered labels is detected without image evidence`() {
        // A facing-page diagram in a scanned book: no body text, many scattered clean labels,
        // page-scan raster excluded — the label-cluster path must still detect it.
        val labels = (0 until 8).map { i ->
            sparseLine(
                80f + i * 70f,
                left = if (i % 2 == 0) 80f else 320f,
                right = if (i % 2 == 0) 180f else 420f,
                words = 2
            )
        }
        val pageScan = FigureRegionDetector.ImageBox(left = 0f, top = 0f, right = pageWidth, bottom = pageHeight)
        val figures = FigureRegionDetector.detect(pageWidth, pageHeight, labels, listOf(pageScan))
        assertEquals(1, figures.size)
        assertEquals(0, figures[0].labelWordStart)
        assertEquals(15, figures[0].labelWordEnd)
        assertEquals(0, figures[0].anchorWordOffset)
    }

    @Test
    fun `scanned plate with wide gibberish OCR lines and page scan image is one whole-page figure`() {
        // OCR of a manuscript plate: page-wide garbage lines that would pass the body width
        // test, plus the full-page scan raster every page of a scanned book carries.
        val garbage = (0 until 12).map { i ->
            FigureRegionDetector.TextLine(
                left = 60f, top = 80f + i * 40f, right = 440f, bottom = 92f + i * 40f,
                wordCount = 9, gibberish = true
            )
        }
        val pageScan = FigureRegionDetector.ImageBox(left = 0f, top = 0f, right = pageWidth, bottom = pageHeight)
        val figures = FigureRegionDetector.detect(pageWidth, pageHeight, garbage, listOf(pageScan))
        assertEquals(1, figures.size)
        val figure = figures[0]
        // Whole page, all garbage words blanked as labels.
        assertTrue(figure.width > 0.9f && figure.height > 0.9f)
        assertEquals(0, figure.anchorWordOffset)
        assertEquals(0, figure.labelWordStart)
        assertEquals(12 * 9 - 1, figure.labelWordEnd)
    }

    @Test
    fun `full page scan image on a body page does not blob the figure to the whole page`() {
        // Scanned book: ordinary text page = body lines + label band + the page-scan image.
        val topBlock = bodyBlock(60f, 10)
        val labels = listOf(
            sparseLine(300f, left = 120f, right = 180f),
            sparseLine(360f, left = 300f, right = 380f)
        )
        val bottomBlock = bodyBlock(460f, 10)
        val pageScan = FigureRegionDetector.ImageBox(left = 0f, top = 0f, right = pageWidth, bottom = pageHeight)
        val figures = FigureRegionDetector.detect(
            pageWidth, pageHeight, topBlock + labels + bottomBlock, listOf(pageScan)
        )
        assertEquals(1, figures.size)
        val figure = figures[0]
        // Box hugs the label cluster; the page-scan raster is excluded as figure evidence.
        assertTrue(figure.height < 0.4f)
        assertTrue(figure.top > 0.3f)
    }

    @Test
    fun `region count per page is capped`() {
        // Alternating body blocks and label-cluster bands, more bands than the cap.
        val lines = mutableListOf<FigureRegionDetector.TextLine>()
        var y = 30f
        repeat(6) {
            lines.addAll(bodyBlock(y, 2))
            y += 2 * 18f
            lines.add(sparseLine(y + 30f))
            lines.add(sparseLine(y + 85f))
            y += 120f
        }
        lines.addAll(bodyBlock(y, 2))
        val figures = FigureRegionDetector.detect(pageWidth, pageHeight, lines, emptyList())
        assertTrue(figures.size <= FigureDetectionConstants.MAX_REGIONS_PER_PAGE)
    }
}

package com.speedread.rsvp

import com.speedread.rsvp.data.parser.PageBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for PageArtifactDetector. Documents are synthesized as flat word lists
 * with PageBoundary ranges, mirroring how PdfFileParser counts words per page: any running
 * header becomes the first tokens of a page's range and a printed page number the last.
 */
class PageArtifactDetectorTest {

    private class DocumentBuilder {
        val words = mutableListOf<String>()
        val boundaries = mutableListOf<PageBoundary>()
        private var nextPageNumber = 1

        fun page(vararg tokens: String): DocumentBuilder {
            val start = words.size
            words.addAll(tokens)
            boundaries.add(
                PageBoundary(
                    pageNumber = nextPageNumber,
                    startWordIndex = start,
                    endWordIndex = words.size - 1,
                    wordCount = tokens.size
                )
            )
            nextPageNumber++
            return this
        }

        fun detect(): Set<Int> =
            PageArtifactDetector.detectArtifactWordIndices(words, boundaries)
    }

    private fun bodyWords(count: Int, seed: Int): Array<String> =
        Array(count) { "body${seed}w$it" }

    @Test
    fun `trailing page numbers with constant offset are flagged`() {
        val doc = DocumentBuilder()
        // Printed numbers run ahead of PDF page numbers by 3 (front matter offset).
        repeat(8) { i ->
            doc.page(*bodyWords(20, i), "${i + 4}")
        }
        val artifacts = doc.detect()

        val expected = doc.boundaries.map { it.endWordIndex }.toSet()
        assertEquals(expected, artifacts)
    }

    @Test
    fun `repeated all-caps two-token header at page starts is flagged`() {
        val doc = DocumentBuilder()
        repeat(6) { i ->
            doc.page("THE", "EGO", *bodyWords(25, i))
        }
        val artifacts = doc.detect()

        val expectedHeaderIndices = doc.boundaries
            .flatMap { listOf(it.startWordIndex, it.startWordIndex + 1) }
            .toSet()
        assertTrue(artifacts.containsAll(expectedHeaderIndices))
        // Nothing beyond the headers may be flagged.
        assertEquals(expectedHeaderIndices, artifacts)
    }

    @Test
    fun `page number adjacent to header at the same edge is skipped over`() {
        val doc = DocumentBuilder()
        // Top-of-page furniture in the form "6 THE EGO ...body...".
        repeat(8) { i ->
            doc.page("${i + 1}", "THE", "EGO", *bodyWords(25, i))
        }
        val artifacts = doc.detect()

        val expected = doc.boundaries
            .flatMap { listOf(it.startWordIndex, it.startWordIndex + 1, it.startWordIndex + 2) }
            .toSet()
        assertEquals(expected, artifacts)
    }

    @Test
    fun `plain body text yields no artifacts`() {
        val doc = DocumentBuilder()
        repeat(8) { i ->
            doc.page(*bodyWords(30, i))
        }
        assertTrue(doc.detect().isEmpty())
    }

    @Test
    fun `in-body numbers without a consistent offset are not flagged`() {
        val doc = DocumentBuilder()
        val years = intArrayOf(1914, 1066, 300, 42, 7, 1999, 1848, 2)
        repeat(8) { i ->
            doc.page(*bodyWords(20, i), "${years[i]}")
        }
        assertTrue(doc.detect().isEmpty())
    }

    @Test
    fun `repeated lowercase prose at page starts is not flagged`() {
        val doc = DocumentBuilder()
        repeat(8) { i ->
            doc.page("the", "king", *bodyWords(25, i))
        }
        assertTrue(doc.detect().isEmpty())
    }

    @Test
    fun `too few pages produces no detection`() {
        val doc = DocumentBuilder()
        repeat(Constants.ARTIFACT_MIN_PAGES - 1) { i ->
            doc.page("THE", "EGO", *bodyWords(20, i), "${i + 1}")
        }
        assertTrue(doc.detect().isEmpty())
    }

    @Test
    fun `header repeated on fewer than the minimum pages is not flagged`() {
        val doc = DocumentBuilder()
        repeat(Constants.ARTIFACT_HEADER_MIN_REPEAT - 1) { i ->
            doc.page("THE", "EGO", *bodyWords(20, i))
        }
        // Pad with distinct-start pages so the page-count gate passes.
        repeat(6) { i ->
            doc.page(*bodyWords(20, 100 + i))
        }
        assertTrue(doc.detect().isEmpty())
    }

    @Test
    fun `alternating recto-verso headers are both flagged`() {
        val doc = DocumentBuilder()
        repeat(12) { i ->
            if (i % 2 == 0) {
                doc.page("AION", *bodyWords(25, i))
            } else {
                doc.page("THE", "EGO", *bodyWords(25, i))
            }
        }
        val artifacts = doc.detect()

        doc.boundaries.forEachIndexed { i, boundary ->
            assertTrue("page $i header missing", boundary.startWordIndex in artifacts)
            if (i % 2 == 1) {
                assertTrue("page $i second header token missing", boundary.startWordIndex + 1 in artifacts)
            }
        }
    }

    @Test
    fun `punctuated page numbers and mixed body are handled`() {
        val doc = DocumentBuilder()
        repeat(8) { i ->
            doc.page(*bodyWords(20, i), "[${i + 1}]")
        }
        val artifacts = doc.detect()
        val expected = doc.boundaries.map { it.endWordIndex }.toSet()
        assertEquals(expected, artifacts)
    }

    @Test
    fun `body words are never flagged when furniture exists`() {
        val doc = DocumentBuilder()
        repeat(10) { i ->
            doc.page("THE", "EGO", *bodyWords(30, i), "${i + 6}")
        }
        val artifacts = doc.detect()
        for (boundary in doc.boundaries) {
            val bodyRange = (boundary.startWordIndex + 2) until boundary.endWordIndex
            assertFalse(bodyRange.any { it in artifacts })
        }
    }
}

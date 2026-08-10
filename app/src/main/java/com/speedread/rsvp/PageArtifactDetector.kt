package com.speedread.rsvp

import com.speedread.rsvp.data.parser.PageBoundary

/**
 * Detects page furniture — printed page numbers and repeated running headers/footers — that
 * PDF text extraction leaves embedded in the word stream. PdfFileParser takes PDFBox output
 * verbatim with sortByPosition enabled, so a page's visual header becomes the first tokens of
 * its word range and its printed page number typically the last tokens.
 *
 * Detection is anchored on the imported [PageBoundary] word ranges and is deliberately
 * conservative: a token is only flagged when it matches document-wide repetition evidence —
 * an arithmetic page-number sequence with a constant offset from the PDF page numbers, or a
 * token run repeated verbatim at the same page edge across several pages. Boundary indices
 * come from the import-time whitespace tokenizer, which can drift slightly from the engine
 * tokenizer on exotic whitespace; the edge scan windows absorb small drift, and larger drift
 * degrades to missed detections, never to false positives elsewhere in the body.
 *
 * Callers blank the returned indices in DISPLAY text only. The shared token stream (RSVP,
 * TTS, bookmarks) is never modified, so absolute word indices stay aligned across modes —
 * see PageViewViewModel.filterPageArtifacts.
 */
object PageArtifactDetector {

    // Lowercase connective words permitted inside a title-case running header ("The History
    // of the Ego"). Linguistic data rather than a tuning parameter, so it lives here instead
    // of Constants.
    private val HEADER_PARTICLES = setOf(
        "of", "the", "and", "a", "an", "in", "on", "to", "for", "at", "by", "or"
    )

    private class PageSpan(val pageNumber: Int, val firstWord: Int, val lastWord: Int) {
        val wordCount: Int get() = lastWord - firstWord + 1
    }

    /**
     * Returns the absolute word indices (into [words]) identified as page furniture. Empty
     * when the document has too few boundary-mapped pages for the repetition heuristics to
     * produce trustworthy evidence.
     */
    fun detectArtifactWordIndices(words: List<String>, boundaries: List<PageBoundary>): Set<Int> {
        if (words.isEmpty()) return emptySet()
        val pages = boundaries.mapNotNull { boundary ->
            val first = boundary.startWordIndex.coerceAtLeast(0)
            val last = boundary.endWordIndex.coerceAtMost(words.size - 1)
            if (first > last) null else PageSpan(boundary.pageNumber, first, last)
        }
        if (pages.size < Constants.ARTIFACT_MIN_PAGES) return emptySet()

        val artifacts = HashSet<Int>()
        // Page numbers first: a number adjacent to a header at the same edge must already be
        // flagged so the header-run scan can skip over it and see the header tokens.
        detectPageNumbers(words, pages, fromStart = true, artifacts = artifacts)
        detectPageNumbers(words, pages, fromStart = false, artifacts = artifacts)
        detectRepeatedEdgeRuns(words, pages, fromStart = true, artifacts = artifacts)
        detectRepeatedEdgeRuns(words, pages, fromStart = false, artifacts = artifacts)
        return artifacts
    }

    /**
     * Flags printed page numbers at one page edge. The candidate on each page is the numeric
     * token nearest that edge within the scan window. Candidates only become artifacts when
     * (printed value - PDF page number) is the same on enough pages — front matter shifts
     * printed numbering by a constant, so the offset is learned rather than assumed zero,
     * while in-body numbers (years, quantities) do not form a sequence aligned with the page
     * numbering and never reach the thresholds.
     */
    private fun detectPageNumbers(
        words: List<String>,
        pages: List<PageSpan>,
        fromStart: Boolean,
        artifacts: MutableSet<Int>
    ) {
        class Candidate(val wordIndex: Int, val delta: Int)

        val candidates = ArrayList<Candidate>(pages.size)
        for (page in pages) {
            for (wordIndex in edgeIndices(page, fromStart, Constants.ARTIFACT_PAGE_NUMBER_SCAN_TOKENS)) {
                val value = asPageNumber(words[wordIndex]) ?: continue
                candidates.add(Candidate(wordIndex, value - page.pageNumber))
                break
            }
        }
        if (candidates.isEmpty()) return

        val modalDelta = candidates.groupingBy { it.delta }.eachCount()
            .maxByOrNull { it.value }?.key ?: return
        val matched = candidates.filter { it.delta == modalDelta }
        if (matched.size < Constants.ARTIFACT_PAGE_NUMBER_MIN_MATCHES) return
        if (matched.size < Constants.ARTIFACT_PAGE_NUMBER_MIN_FRACTION * pages.size) return
        matched.forEach { artifacts.add(it.wordIndex) }
    }

    /**
     * Flags running headers/footers: a normalized token run that recurs verbatim at the same
     * page edge on several pages. Longer runs are matched first so a page already claimed by
     * a longer header is not re-counted for its own prefixes. Single-token runs carry the
     * highest false-positive risk (short common words), so they additionally require an
     * all-caps token and more repetitions.
     */
    private fun detectRepeatedEdgeRuns(
        words: List<String>,
        pages: List<PageSpan>,
        fromStart: Boolean,
        artifacts: MutableSet<Int>
    ) {
        // Per page: token indices inward from the edge, skipping already-flagged tokens (a
        // page number sitting between the edge and the header text).
        val runs = pages.map { page ->
            edgeIndices(page, fromStart, page.wordCount)
                .asSequence()
                .filter { it !in artifacts }
                .take(Constants.ARTIFACT_HEADER_MAX_TOKENS)
                .toList()
        }

        val consumed = BooleanArray(pages.size)
        for (length in Constants.ARTIFACT_HEADER_MAX_TOKENS downTo 1) {
            val pagesByKey = HashMap<String, MutableList<Int>>()
            for (pageOrdinal in runs.indices) {
                if (consumed[pageOrdinal]) continue
                val run = runs[pageOrdinal]
                if (run.size < length) continue
                val slice = run.subList(0, length)
                if (!isPlausibleHeaderRun(words, slice, length)) continue
                val key = slice.joinToString(" ") { normalize(words[it]) }
                if (key.isBlank()) continue
                pagesByKey.getOrPut(key) { mutableListOf() }.add(pageOrdinal)
            }

            val minRepeat = if (length == 1) {
                Constants.ARTIFACT_HEADER_SINGLE_TOKEN_MIN_REPEAT
            } else {
                Constants.ARTIFACT_HEADER_MIN_REPEAT
            }
            for (matchedPages in pagesByKey.values) {
                if (matchedPages.size < minRepeat) continue
                for (pageOrdinal in matchedPages) {
                    consumed[pageOrdinal] = true
                    runs[pageOrdinal].subList(0, length).forEach { artifacts.add(it) }
                }
            }
        }
    }

    /** Word indices walking inward from one page edge, at most [count] and never past the span. */
    private fun edgeIndices(page: PageSpan, fromStart: Boolean, count: Int): List<Int> {
        val n = count.coerceAtMost(page.wordCount)
        return if (fromStart) {
            (page.firstWord until page.firstWord + n).toList()
        } else {
            (page.lastWord downTo page.lastWord - n + 1).toList()
        }
    }

    /**
     * Case gate for header runs: real running headers are set in caps or title case, so every
     * token must lead with an uppercase letter or digit, except connective particles in
     * title-case headers. Repeated ordinary prose ("the king went...") fails this gate even
     * if several pages happen to start with the same words.
     */
    private fun isPlausibleHeaderRun(words: List<String>, indices: List<Int>, length: Int): Boolean {
        if (length == 1) {
            val letters = words[indices[0]].filter { it.isLetter() }
            return letters.length >= Constants.ARTIFACT_HEADER_SINGLE_TOKEN_MIN_LENGTH &&
                letters.all { it.isUpperCase() }
        }
        return indices.all { index ->
            val token = words[index]
            val firstAlphanumeric = token.firstOrNull { it.isLetterOrDigit() } ?: return@all false
            firstAlphanumeric.isDigit() || firstAlphanumeric.isUpperCase() ||
                stripPunctuation(token).lowercase() in HEADER_PARTICLES
        }
    }

    /** Printed page number: an edge token that is purely digits once punctuation is stripped. */
    private fun asPageNumber(token: String): Int? {
        val core = stripPunctuation(token)
        if (core.isEmpty() || core.length > Constants.ARTIFACT_PAGE_NUMBER_MAX_DIGITS) return null
        if (!core.all { it.isDigit() }) return null
        return core.toIntOrNull()
    }

    private fun stripPunctuation(token: String): String = token.trim { !it.isLetterOrDigit() }

    private fun normalize(token: String): String = stripPunctuation(token).uppercase()
}

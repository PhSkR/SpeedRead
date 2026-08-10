package com.speedread.rsvp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextProcessorTest {

    private val processor = DefaultTextProcessor()

    @Test
    fun blankInputProducesEmptyList() {
        assertTrue(processor.processText("").isEmpty())
        assertTrue(processor.processText("   \n\t  ").isEmpty())
    }

    @Test
    fun singleWordYieldsOneRsvpWord() {
        val result = processor.processText("Hello")
        assertEquals(1, result.size)
        assertEquals("Hello", result[0].text)
        assertEquals(0, result[0].position)
    }

    // NOTE: break-classification inputs below end the pre-break word with sentence-final
    // punctuation so the typographic-wrap suppression (see tokenizeWithBreaks KDoc) does
    // not apply — these tests target newline CLASSIFICATION only.

    @Test
    fun crlfIsSingleLineBreakNotParagraph() {
        // Windows CRLF (\r\n) must collapse to a single \n so the tokenizer classifies
        // it as LINE, not PARAGRAPH. Previously "\r\n" expanded to "\n\n" and every
        // Windows line ending fired a paragraph-length pause.
        val result = processor.processText("foo.\r\nbar")
        assertEquals(2, result.size)
        assertEquals(TrailingBreak.LINE, result[0].trailingBreak)
    }

    @Test
    fun crlfPairParagraphBreakStillParagraph() {
        // Genuine paragraph breaks in CRLF files ("\r\n\r\n") must still classify as
        // PARAGRAPH after the collapse.
        val result = processor.processText("foo.\r\n\r\nbar")
        assertEquals(2, result.size)
        assertEquals(TrailingBreak.PARAGRAPH, result[0].trailingBreak)
    }

    @Test
    fun loneCarriageReturnIsLineBreak() {
        // Classic-Mac line endings (lone \r) should degrade to a LINE break.
        val result = processor.processText("foo.\rbar")
        assertEquals(2, result.size)
        assertEquals(TrailingBreak.LINE, result[0].trailingBreak)
    }

    @Test
    fun singleNewlineIsLineBreak() {
        val result = processor.processText("foo.\nbar")
        assertEquals(TrailingBreak.LINE, result[0].trailingBreak)
    }

    @Test
    fun doubleNewlineIsParagraphBreak() {
        val result = processor.processText("foo.\n\nbar")
        assertEquals(TrailingBreak.PARAGRAPH, result[0].trailingBreak)
    }

    @Test
    fun paragraphNeverDowngradesToLine() {
        val result = processor.processText("foo.\n\n\nbar")
        assertEquals(TrailingBreak.PARAGRAPH, result[0].trailingBreak)
    }

    // --- Typographic-wrap suppression: breaks that are hard-wrap artifacts (previous word
    // does not end a sentence AND next word continues lowercase) must not be recorded —
    // they inject paragraph pauses into RSVP and force mid-sentence TTS utterance splits.

    @Test
    fun midSentenceParagraphArtifactIsSuppressed() {
        // Page-break artifact from PDF extraction: "...adjudging the\n\nresponsibility..."
        val result = processor.processText("adjudging the\n\nresponsibility of it")
        assertNull(result[1].trailingBreak)
    }

    @Test
    fun midSentenceLineWrapIsSuppressed() {
        val result = processor.processText("the quick\nbrown fox")
        assertNull(result[1].trailingBreak)
    }

    @Test
    fun wrapAfterCommaIsSuppressed() {
        // Paragraphs do not end with commas; comma + lowercase continuation is a wrap.
        val result = processor.processText("the whole,\n\nand nothing")
        assertNull(result[1].trailingBreak)
    }

    @Test
    fun headingWithoutPunctuationKeepsParagraphBreak() {
        // Headings rarely end with punctuation but the next paragraph starts uppercase.
        val result = processor.processText("Chapter One\n\nIt was the best")
        assertEquals(TrailingBreak.PARAGRAPH, result[1].trailingBreak)
    }

    @Test
    fun sentenceEndKeepsBreakBeforeLowercaseWord() {
        val result = processor.processText("It ended.\n\nthen again")
        assertEquals(TrailingBreak.PARAGRAPH, result[1].trailingBreak)
    }

    @Test
    fun sentenceEndInsideClosingQuoteKeepsBreak() {
        val result = processor.processText("he said.\"\n\nthen silence")
        assertEquals(TrailingBreak.PARAGRAPH, result[1].trailingBreak)
    }

    @Test
    fun longWordPreservedInFull() {
        // Regression guard: TextProcessor previously truncated tokens to 100 chars
        // which silently corrupted long URLs / compound words.
        val longWord = "a".repeat(150)
        val result = processor.processText(longWord)
        assertEquals(1, result.size)
        assertEquals(150, result[0].text.length)
    }

    @Test
    fun inputExceedingMaxTokensIsCappedAtMax() {
        // Regression guard: MAX_TOKENS is the hard ceiling.
        val tokenCount = EngineConstants.MAX_TOKENS + 1000
        val input = buildString {
            repeat(tokenCount) {
                append("w")
                append(' ')
            }
        }
        val result = processor.processText(input)
        assertEquals(EngineConstants.MAX_TOKENS, result.size)
    }

    @Test
    fun chunkedProcessingGroupsCorrectly() {
        val input = (1..10).joinToString(" ") { "w$it" }
        val settings = RsvpSettings(chunkSize = 3)
        val result = processor.processText(input, settings)
        // 10 tokens chunked by 3 → 4 chunks: [w1 w2 w3][w4 w5 w6][w7 w8 w9][w10]
        assertEquals(4, result.size)
        assertEquals("w1 w2 w3", result[0].text)
        assertEquals("w10", result[3].text)
    }

    @Test
    fun chunkedOrpIndexPointsInsideChunk() {
        val input = "aaa bbb ccc"
        val settings = RsvpSettings(chunkSize = 3)
        val result = processor.processText(input, settings)
        assertEquals(1, result.size)
        // Middle word "bbb" starts at index 4 ("aaa "=4), orp of 3-char word is 1.
        // So chunk orp should fall inside [4,6].
        val orp = result[0].orpIndex
        assertTrue("orp $orp should be inside 'bbb'", orp in 4..6)
    }

    @Test
    fun noTrailingBreakWhenNoNewlines() {
        val result = processor.processText("foo bar baz")
        result.forEach { assertNull(it.trailingBreak) }
    }

    @Test
    fun positionsAreZeroIndexedAndContiguous() {
        val result = processor.processText("a b c d")
        result.forEachIndexed { index, word -> assertEquals(index, word.position) }
    }

    @Test
    fun chunkSizeLargerThanTokenCountClampsToMax() {
        // Regression guard: chunkSize=1000 on a 50-word doc previously produced one
        // chunk of 50 concatenated words, with the ORP landing on an arbitrary char.
        // Clamp to EngineConstants.MAX_CHUNK_SIZE (=10) so 50 tokens yield 5 chunks.
        val tokens = (1..50).joinToString(" ") { "w$it" }
        val result = processor.processText(tokens, RsvpSettings(chunkSize = 1000))
        assertEquals(5, result.size)
    }

    @Test
    fun chunkSizeAtMaxConstantHonored() {
        // chunkSize exactly at the defensive ceiling must pass through unchanged.
        val tokenCount = EngineConstants.MAX_CHUNK_SIZE * 3
        val tokens = (1..tokenCount).joinToString(" ") { "w$it" }
        val result = processor.processText(tokens, RsvpSettings(chunkSize = EngineConstants.MAX_CHUNK_SIZE))
        assertEquals(3, result.size)
    }

    // ---- isWordSeparator coverage: chars Character.isWhitespace misses but that ----
    // ---- the tokenizer must still split on (otherwise they leak into tokens and  ----
    // ---- TextView wraps a line per glyph; see Changelog 1.14.51).                ----

    @Test
    fun nelBetweenWordsSplitsCleanly() {
        // U+0085 NEL is a UAX #14 mandatory line break that Character.isWhitespace
        // does NOT recognise. Pre-fix it would have been appended into the current
        // word; post-fix it splits the boundary like a space.
        val nel = Char(0x0085)
        val result = processor.processText("foo${nel}bar")
        assertEquals(2, result.size)
        assertEquals("foo", result[0].text)
        assertEquals("bar", result[1].text)
    }

    @Test
    fun zwspBetweenWordsSplitsCleanly() {
        // U+200B Zero Width Space — UAX #14 break opportunity, also missed by
        // Character.isWhitespace. Same separator semantics as a regular space.
        val zwsp = Char(0x200B)
        val result = processor.processText("foo${zwsp}bar")
        assertEquals(2, result.size)
        assertEquals("foo", result[0].text)
        assertEquals("bar", result[1].text)
    }

    @Test
    fun bomBetweenWordsSplitsCleanly() {
        // U+FEFF — BOM / ZERO WIDTH NO-BREAK SPACE. Treated as separator so the BOM
        // can never live inside a word token and confuse downstream rendering.
        val bom = Char(0xFEFF)
        val result = processor.processText("foo${bom}bar")
        assertEquals(2, result.size)
        assertEquals("foo", result[0].text)
        assertEquals("bar", result[1].text)
    }

    @Test
    fun nelDoesNotEmitLineOrParagraphBreak() {
        // NEL is intentionally treated as PLAIN whitespace, not as a LINE / PARAGRAPH
        // break. Rationale: a PDF that sprays NEL between every glyph would otherwise
        // inject a LINE_BREAK_PAUSE per character into RSVP narration. The pause shape
        // stays opt-in to real `\n`.
        val nel = Char(0x0085)
        val result = processor.processText("foo${nel}bar")
        assertNull(result[0].trailingBreak)
        assertNull(result[1].trailingBreak)
    }

    @Test
    fun zwnjAndZwjStayInsideWord() {
        // U+200C ZWNJ and U+200D ZWJ are intentionally NOT separators — they're
        // legitimate within-word joiner controls used by Arabic / Devanagari / etc.
        // for proper glyph rendering. A token containing them must remain ONE token.
        val zwnj = Char(0x200C)
        val zwj = Char(0x200D)
        val result = processor.processText("foo${zwnj}${zwj}bar")
        assertEquals(1, result.size)
        assertEquals("foo${zwnj}${zwj}bar", result[0].text)
    }

    @Test
    fun softHyphenStaysInsideWord() {
        // U+00AD SOFT HYPHEN is a typographer's hyphenation hint commonly embedded
        // in justified PDF text. Treating it as a separator would shred any such
        // pre-encoded word; left intentionally inside tokens.
        val shy = Char(0x00AD)
        val result = processor.processText("hyp${shy}hen${shy}ation")
        assertEquals(1, result.size)
        assertEquals("hyp${shy}hen${shy}ation", result[0].text)
    }

    @Test
    fun nelInterleavedBetweenGlyphsRegressionFromV1_14_51() {
        // Repro of the user-reported Page View bug (Changelog 1.14.51): a passage
        // extracted from a PDF arrived as `... go on" + NEL + "i" + NEL + "n" + NEL
        // + (many NEL) + "t" + NEL + "these ...` — every glyph (or short run) was
        // glued by NEL chars. Pre-fix the tokenizer produced a single mega-token
        // `"in...tthese`; the joined continuous text passed to TextView still held
        // every embedded NEL, and TextView wrapped a line per NEL — producing the
        // observable `" / i / n / [gap] / t` per-glyph wrapping.
        //
        // Post-fix: every NEL splits a token boundary, so the resulting tokens are
        // the individual glyphs / short runs. Joining them back with single spaces
        // produces clean text whose only "weirdness" is the original PDF's
        // chars-with-spaces-between (a content artefact that is no longer a layout
        // bug).
        val nel = Char(0x0085)
        val passage = "go on${nel}\"${nel}i${nel}n${nel}${nel}${nel}${nel}t${nel}these"
        val result = processor.processText(passage)
        // "go" and "on" come from the leading space; the rest are NEL-separated:
        // ["go", "on", "\"", "i", "n", "t", "these"]
        val texts = result.map { it.text }
        assertEquals(listOf("go", "on", "\"", "i", "n", "t", "these"), texts)
        // No token retains an embedded NEL — the joined output is therefore safe to
        // hand to TextView without producing per-glyph line breaks.
        result.forEach { assertTrue("token must not contain NEL: ${it.text}", nel !in it.text) }
    }

    @Test
    fun newlineFollowedByNelStillEmitsLineBreak() {
        // Defensive trace: the NEL/ZWSP/BOM separator branch sets `newlineRun = 0`,
        // but the previous \n already pushed `pendingBreak = LINE` on the empty
        // word's flush. That break must still attach to the PREVIOUS token when the
        // next non-separator word arrives (i.e. NEL after \n must not silently
        // swallow the LINE break). Same reasoning for paragraph breaks below.
        val nel = Char(0x0085)
        val result = processor.processText("foo.\n${nel}bar")
        assertEquals(2, result.size)
        assertEquals(TrailingBreak.LINE, result[0].trailingBreak)
    }

    @Test
    fun doubleNewlineFollowedByNelStillEmitsParagraphBreak() {
        val nel = Char(0x0085)
        val result = processor.processText("foo.\n\n${nel}bar")
        assertEquals(2, result.size)
        assertEquals(TrailingBreak.PARAGRAPH, result[0].trailingBreak)
    }

    @Test
    fun delC0ControlSplitsCleanly() {
        // U+007F DEL — outside Character.isWhitespace (which only covers \t, \n, \v,
        // \f, \r, FS, GS, RS, US in C0). Now caught by isISOControl(). PDFBox can
        // emit DEL between text-run boundaries on some malformed PDFs.
        val del = Char(0x007F)
        val result = processor.processText("foo${del}bar")
        assertEquals(2, result.size)
        assertEquals("foo", result[0].text)
        assertEquals("bar", result[1].text)
    }

    @Test
    fun nullCharSplitsCleanly() {
        // U+0000 NUL — would otherwise pass straight into a token and hand TextView
        // a glyph with no visible width, which screws up line measurement. Caught by
        // isISOControl().
        val nul = Char(0x0000)
        val result = processor.processText("foo${nul}bar")
        assertEquals(2, result.size)
        assertEquals("foo", result[0].text)
        assertEquals("bar", result[1].text)
    }

    @Test
    fun c1ControlSplitsCleanly() {
        // U+0090 DCS (Device Control String) — arbitrary C1 control, no whitespace
        // semantics in JDK but isISOControl=true. Generic regression: any C1 control
        // (0x80–0x9F) must be treated as a separator.
        val dcs = Char(0x0090)
        val result = processor.processText("foo${dcs}bar")
        assertEquals(2, result.size)
        assertEquals("foo", result[0].text)
        assertEquals("bar", result[1].text)
    }

    @Test
    fun bomAtStartOfDocumentDoesNotProduceEmptyToken() {
        // EPUBs and TXTs sometimes ship with a leading BOM. The tokenizer must
        // treat it as a no-op separator at start-of-text rather than emitting a
        // zero-length token (which the upstream Page View joiner would render as
        // a doubled space `  `).
        val bom = Char(0xFEFF)
        val result = processor.processText("${bom}hello world")
        assertEquals(2, result.size)
        assertEquals("hello", result[0].text)
        assertEquals("world", result[1].text)
    }

    @Test
    fun nbspAndNnbspBetweenWordsSplitsCleanly() {
        val nbsp = Char(0x00A0)
        val nnbsp = Char(0x202F)
        val result = processor.processText("foo${nbsp}bar${nnbsp}baz")
        assertEquals(3, result.size)
        assertEquals("foo", result[0].text)
        assertEquals("bar", result[1].text)
        assertEquals("baz", result[2].text)
    }
}

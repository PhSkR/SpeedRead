package com.speedread.rsvp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsChunkerTest {

    private fun word(text: String, position: Int, br: TrailingBreak? = null): RsvpWord =
        RsvpWord(text = text, position = position, trailingBreak = br)

    @Test
    fun emptyListReturnsEmpty() {
        val out = TtsChunker.chunk(emptyList(), 0, 10, 100)
        assertTrue(out.isEmpty())
    }

    @Test
    fun startPositionBeyondListReturnsEmpty() {
        val words = listOf(word("hello", 0), word("world", 1))
        val out = TtsChunker.chunk(words, 99, 10, 100)
        assertTrue(out.isEmpty())
    }

    @Test
    fun singleWordProducesSingleUtterance() {
        val out = TtsChunker.chunk(listOf(word("Hello", 0)), 0, 10, 100)
        assertEquals(1, out.size)
        assertEquals("Hello", out[0].text)
        assertEquals(listOf(0 until 5), out[0].wordRanges)
        assertEquals(listOf(0), out[0].globalWordIndices)
    }

    @Test
    fun wordsJoinedBySpaceWithCorrectRanges() {
        val out = TtsChunker.chunk(
            listOf(word("Hello", 0), word("world", 1), word("foo", 2)),
            0, 10, 100
        )
        assertEquals(1, out.size)
        val u = out[0]
        assertEquals("Hello world foo", u.text)
        // "Hello" = 0..5, " " = 5, "world" = 6..11, " " = 11, "foo" = 12..15
        assertEquals(listOf(0 until 5, 6 until 11, 12 until 15), u.wordRanges)
        assertEquals(listOf(0, 1, 2), u.globalWordIndices)
    }

    @Test
    fun paragraphBreakFlushesImmediately() {
        val words = listOf(
            word("First", 0, TrailingBreak.PARAGRAPH),
            word("Second", 1),
            word("Third", 2)
        )
        val out = TtsChunker.chunk(words, 0, 10, 100)
        assertEquals(2, out.size)
        assertEquals("First", out[0].text)
        assertTrue(out[0].hasParagraphBreakAtEnd)
        assertEquals("Second Third", out[1].text)
        assertFalse(out[1].hasParagraphBreakAtEnd)
    }

    @Test
    fun wordCountCapFlushes() {
        val words = (0 until 10).map { word("w$it", it) }
        val out = TtsChunker.chunk(words, 0, 3, 10_000)
        // 10 words / 3 per chunk = 4 utterances (3, 3, 3, 1)
        assertEquals(4, out.size)
        assertEquals(3, out[0].wordCount)
        assertEquals(3, out[1].wordCount)
        assertEquals(3, out[2].wordCount)
        assertEquals(1, out[3].wordCount)
    }

    @Test
    fun charCapForcesFlushOnNonEmptyBuffer() {
        // Three 6-char words joined = "aaaaaa bbbbbb cccccc" (20 chars). With a 12-char cap,
        // after "aaaaaa" (6) the next word would bring us to 13 — flush; then "bbbbbb" on
        // its own fills 6 again, then "cccccc" next.
        val words = listOf(word("aaaaaa", 0), word("bbbbbb", 1), word("cccccc", 2))
        val out = TtsChunker.chunk(words, 0, 100, 12)
        assertEquals(3, out.size)
        assertEquals("aaaaaa", out[0].text)
        assertEquals("bbbbbb", out[1].text)
        assertEquals("cccccc", out[2].text)
    }

    @Test
    fun oversizedSingleWordAcceptedAsOwnUtterance() {
        val words = listOf(word("supercalifragilisticexpialidocious", 0))
        val out = TtsChunker.chunk(words, 0, 10, 5)  // cap below word length
        assertEquals(1, out.size)
        assertEquals("supercalifragilisticexpialidocious", out[0].text)
    }

    @Test
    fun lineBreakHalfFullFlushesButSmallBufferDoesNot() {
        // maxWordsPerChunk=6, halfFull threshold = 3 words.
        // 5 words, all with LINE break: first two stay (buffer 1, 2 — below threshold),
        // third at threshold flushes, fourth + fifth form second utterance.
        val words = listOf(
            word("a", 0, TrailingBreak.LINE),
            word("b", 1, TrailingBreak.LINE),
            word("c", 2, TrailingBreak.LINE),
            word("d", 3, TrailingBreak.LINE),
            word("e", 4, TrailingBreak.LINE)
        )
        val out = TtsChunker.chunk(words, 0, 6, 10_000)
        assertEquals(2, out.size)
        assertEquals("a b c", out[0].text)
        assertEquals("d e", out[1].text)
    }

    @Test
    fun positionForCharOffsetMapsInRange() {
        val out = TtsChunker.chunk(
            listOf(word("Hello", 0), word("world", 1)),
            0, 10, 100
        )
        val u = out[0]
        // Ranges: "Hello" = 0..4 (exclusive 5), "world" = 6..10 (exclusive 11)
        assertEquals(0, u.positionForCharOffset(0))
        assertEquals(0, u.positionForCharOffset(4))
        assertEquals(1, u.positionForCharOffset(6))
        assertEquals(1, u.positionForCharOffset(10))
    }

    @Test
    fun positionForCharOffsetReturnsNullForGap() {
        val out = TtsChunker.chunk(
            listOf(word("Hello", 0), word("world", 1)),
            0, 10, 100
        )
        val u = out[0]
        // Char 5 is the space between "Hello" and "world" — no word owns it.
        assertNull(u.positionForCharOffset(5))
        // Char past end
        assertNull(u.positionForCharOffset(99))
    }

    @Test
    fun startPositionHonored() {
        val words = (0 until 5).map { word("w$it", it) }
        val out = TtsChunker.chunk(words, startPosition = 2, maxWordsPerChunk = 10, maxCharsPerChunk = 100)
        assertEquals(1, out.size)
        assertEquals("w2 w3 w4", out[0].text)
        assertEquals(listOf(2, 3, 4), out[0].globalWordIndices)
    }

    @Test
    fun globalWordIndicesPreservedAcrossChunkBoundaries() {
        val words = (0 until 6).map { word("w$it", it, if (it == 2) TrailingBreak.PARAGRAPH else null) }
        val out = TtsChunker.chunk(words, 0, 10, 100)
        assertEquals(2, out.size)
        assertEquals(listOf(0, 1, 2), out[0].globalWordIndices)
        assertEquals(listOf(3, 4, 5), out[1].globalWordIndices)
    }

    @Test
    fun sentenceEndAlwaysFlushes() {
        // Every '.', '!', '?' flushes — regardless of buffer fullness. Aligns each
        // utterance with a sentence boundary so the Android TTS service-level inter-
        // utterance gap lands at a natural sentence-end pause point.
        val words = listOf(
            word("a", 0), word("b.", 1),
            word("c", 2), word("d.", 3),
            word("e", 4), word("f.", 5)
        )
        val out = TtsChunker.chunk(words, 0, 100, 10_000)
        assertEquals(3, out.size)
        assertEquals("a b.", out[0].text)
        assertEquals("c d.", out[1].text)
        assertEquals("e f.", out[2].text)
        out.forEach { assertFalse(it.hasParagraphBreakAtEnd) }
    }

    @Test
    fun shortSentencesProduceSmallUtterances() {
        // Single-word sentences each flush. "Yes." → 1-word utterance; "Hi!" → 1-word
        // utterance. Voice/service handle the natural sentence-end pauses.
        val words = listOf(word("Yes.", 0), word("Hi!", 1), word("Done?", 2))
        val out = TtsChunker.chunk(words, 0, 100, 10_000)
        assertEquals(3, out.size)
        assertEquals("Yes.", out[0].text)
        assertEquals("Hi!", out[1].text)
        assertEquals("Done?", out[2].text)
    }

    @Test
    fun abbreviationsDoNotFlush() {
        // "Mr." / "Dr." / "etc." would naively look like sentence ends but are abbreviations
        // — flushing there would re-introduce mid-sentence pauses. Each abbreviation in
        // NON_TERMINAL_ABBREVIATIONS must be skipped so the chunk runs through to the real
        // sentence terminator at "hi.".
        val words = listOf(
            word("Hello", 0), word("Mr.", 1), word("Smith", 2), word("said", 3),
            word("hi.", 4),
            word("Then", 5), word("he", 6), word("left.", 7)
        )
        val out = TtsChunker.chunk(words, 0, 100, 10_000)
        assertEquals(2, out.size)
        assertEquals("Hello Mr. Smith said hi.", out[0].text)
        assertEquals("Then he left.", out[1].text)
    }

    @Test
    fun singleLetterInitialsDoNotFlush() {
        // "J. R. R. Tolkien wrote books." should be one utterance — single-letter initials
        // are caught by the same heuristic as the abbreviation blacklist.
        val words = listOf(
            word("J.", 0), word("R.", 1), word("R.", 2),
            word("Tolkien", 3), word("wrote", 4), word("books.", 5)
        )
        val out = TtsChunker.chunk(words, 0, 100, 10_000)
        assertEquals(1, out.size)
        assertEquals("J. R. R. Tolkien wrote books.", out[0].text)
    }

    @Test
    fun paragraphTakesPrecedenceOverSentenceEnd() {
        // Word ends with '.' AND has a PARAGRAPH break — flush triggered, paragraphEnd
        // flag is true (paragraph is evaluated first in shouldFlush so the resulting
        // utterance is correctly marked).
        val words = listOf(
            word("First.", 0, TrailingBreak.PARAGRAPH),
            word("Second", 1), word("Third.", 2)
        )
        val out = TtsChunker.chunk(words, 0, 100, 100)
        assertEquals(2, out.size)
        assertEquals("First.", out[0].text)
        assertTrue(out[0].hasParagraphBreakAtEnd)
        assertEquals("Second Third.", out[1].text)
    }

    @Test
    fun sentenceEndDetectsTrailingClosingPunctuation() {
        // 'said."' / 'really?' / 'Wow!' should each flush. Closing punctuation
        // (' " ) ] } ’ ”) is stripped before checking the terminator.
        val words = listOf(
            word("She", 0), word("said.\"", 1),
            word("Then", 2), word("really?", 3),
            word("Wow!", 4)
        )
        val out = TtsChunker.chunk(words, 0, 100, 10_000)
        assertEquals(3, out.size)
        assertEquals("She said.\"", out[0].text)
        assertEquals("Then really?", out[1].text)
        assertEquals("Wow!", out[2].text)
    }

    @Test
    fun runOnTextWithoutSentenceEndHitsWordCap() {
        // No sentence terminators across the input → only the word-cap can flush. Keeps
        // a backstop against unbounded buffer growth on text with no '.!?'.
        val words = (0 until 6).map { word("w$it", it) }
        val out = TtsChunker.chunk(words, 0, maxWordsPerChunk = 3, maxCharsPerChunk = 10_000)
        assertEquals(2, out.size)
        assertEquals("w0 w1 w2", out[0].text)
        assertEquals("w3 w4 w5", out[1].text)
    }
}

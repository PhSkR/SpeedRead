package com.speedread.rsvp.engine

/**
 * A single speech-synthesis utterance: one text string plus the character ranges of each
 * word inside it, so `UtteranceProgressListener.onRangeStart(start, end)` can be mapped
 * back to the originating RsvpWord position.
 *
 * [globalWordIndices] parallel [wordRanges]: for the i-th word in this utterance,
 * wordRanges[i] is its (charStart inclusive, charEnd exclusive) inside [text], and
 * globalWordIndices[i] is its RsvpWord.position in the source document.
 */
data class TtsUtterance(
    val text: String,
    val wordRanges: List<IntRange>,
    val globalWordIndices: List<Int>,
    val hasParagraphBreakAtEnd: Boolean
) {
    /**
     * Map a character offset (as reported by UtteranceProgressListener.onRangeStart) to
     * the RsvpWord position it falls within. Returns null if the offset is outside all
     * known word ranges (whitespace or punctuation gap — some OEMs report gaps).
     */
    fun positionForCharOffset(charOffset: Int): Int? {
        // Linear scan: wordRanges are small (<= TTS_CHUNK_WORDS) and sequential, so this
        // is cheaper than a binary search after accounting for callback frequency.
        for (i in wordRanges.indices) {
            val range = wordRanges[i]
            if (charOffset in range) return globalWordIndices[i]
        }
        return null
    }

    val wordCount: Int get() = globalWordIndices.size
}

/**
 * Chunks an RsvpWord list into TTS utterances. Used by NativeTtsEngine and NeuralTtsEngine.
 *
 * Pure function — lives in rsvp-engine so it can be unit-tested without Android. Callers
 * pass the chunk bounds (app.Constants.TTS_CHUNK_WORDS / TTS_MAX_UTTERANCE_CHARS) since the
 * rsvp-engine module cannot depend on app.
 *
 * Chunking strategy (precedence top → bottom):
 *   1. PARAGRAPH break — always flush (preserves natural speech pacing).
 *   2. Word-count hard cap — flush at [maxWordsPerChunk] (run-on backstop).
 *   3. Sentence-end (`.`, `!`, `?`, optionally followed by closing quote/paren) — every
 *      one flushes. Aligns each utterance boundary with a sentence boundary so the
 *      Android TTS service-level inter-utterance gap (~150-300 ms, intrinsic to each
 *      `speak()` call and not suppressible via API) lands at the spot where listeners
 *      expect a pause anyway. The voice's natural sentence-end prosody (pitch drop,
 *      micro-pause) overlaps with the service gap and the combined silence reads as a
 *      single natural sentence-end pause rather than an abrupt mid-clause cut.
 *      Abbreviations like "Mr.", "Dr.", "i.e." and single-letter initials are excluded
 *      via [endsSentence] so they don't trigger false flushes mid-sentence.
 *   4. LINE break when the buffer is at least [PREFERRED_LINE_FLUSH_RATIO] full, so short
 *      wrapped lines don't cause choppy utterances.
 *   5. Char-cap (oversized buffer) flushes pre-emptively before appending the next word.
 */
object TtsChunker {

    private const val WORD_SEPARATOR = " "
    private const val PREFERRED_LINE_FLUSH_RATIO = 0.5f

    private val SENTENCE_TERMINATORS = setOf('.', '!', '?')
    // Closers that may legally follow a sentence terminator: ASCII straight/curly quotes,
    // brackets, parens. Stripped right-to-left so `."`, `!")`, `?”` all detect as a
    // sentence end.
    private val CLOSING_AFTER_TERMINATOR = setOf(
        '"', '\'', ')', ']', '}',
        '’', // right single quote (’)
        '”'  // right double quote (”)
    )

    // Tokens that LOOK like a sentence terminator (end with `.`) but aren't — flushing
    // mid-sentence at any of these would re-introduce the original mid-clause-pause bug.
    // Best-effort list covering the common cases; rare or domain-specific abbreviations
    // ("approx.", "Sgt.", "Pty.") will still false-positive but the cost is one
    // mid-sentence pause per occurrence, which is no worse than the chunker's existing
    // run-on word-cap fallback.
    private val NON_TERMINAL_ABBREVIATIONS = setOf(
        "Mr.", "Mrs.", "Ms.", "Dr.", "Prof.", "St.", "Jr.", "Sr.",
        "vs.", "etc.", "i.e.", "e.g.", "cf.",
        "a.m.", "p.m.", "A.M.", "P.M.",
        "Inc.", "Ltd.", "Co.", "Corp.", "Mt.", "Ft.",
        "No.", "Vol.", "Fig.", "Ch.", "pp.", "p."
    )

    fun chunk(
        words: List<RsvpWord>,
        startPosition: Int = 0,
        maxWordsPerChunk: Int,
        maxCharsPerChunk: Int
    ): List<TtsUtterance> {
        if (words.isEmpty() || startPosition >= words.size) return emptyList()
        require(maxWordsPerChunk > 0) { "maxWordsPerChunk must be positive" }
        require(maxCharsPerChunk > 0) { "maxCharsPerChunk must be positive" }

        val result = mutableListOf<TtsUtterance>()

        val builder = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        val indices = mutableListOf<Int>()

        var i = startPosition.coerceAtLeast(0)
        while (i < words.size) {
            val word = words[i]
            val addSeparator = builder.isNotEmpty()
            val separatorLen = if (addSeparator) WORD_SEPARATOR.length else 0
            val projectedLen = builder.length + separatorLen + word.text.length

            // Hard cap: if this word would overflow the char limit AND the buffer is
            // non-empty, flush what we have and let the word start the next utterance.
            // If the buffer is empty, we accept the overflow (single pathological word
            // larger than the limit — rare; still produces a valid utterance).
            if (projectedLen > maxCharsPerChunk && builder.isNotEmpty()) {
                result += buildUtterance(builder, ranges, indices, paragraphEnd = false)
                builder.clear()
                ranges.clear()
                indices.clear()
                continue  // retry this word on a fresh buffer
            }

            if (addSeparator) builder.append(WORD_SEPARATOR)
            val charStart = builder.length
            builder.append(word.text)
            val charEnd = builder.length
            ranges += charStart until charEnd
            indices += word.position

            val wordCount = indices.size
            val hitWordCap = wordCount >= maxWordsPerChunk
            val isParagraph = word.trailingBreak == TrailingBreak.PARAGRAPH
            val isLineBreak = word.trailingBreak == TrailingBreak.LINE
            val lineHalfFull = wordCount >= (maxWordsPerChunk * PREFERRED_LINE_FLUSH_RATIO).toInt()
            val sentenceEnd = endsSentence(word.text)

            val shouldFlush = isParagraph ||
                hitWordCap ||
                sentenceEnd ||
                (isLineBreak && lineHalfFull)
            if (shouldFlush) {
                result += buildUtterance(builder, ranges, indices, paragraphEnd = isParagraph)
                builder.clear()
                ranges.clear()
                indices.clear()
            }

            i++
        }

        if (builder.isNotEmpty()) {
            result += buildUtterance(builder, ranges, indices, paragraphEnd = false)
        }

        return result
    }

    /**
     * Whether [text] ends a complete sentence — i.e. its last non-closing-punctuation
     * character is `.`, `!`, or `?` AND the token isn't a known non-terminal abbreviation
     * or single-letter initial. False positives ("approx.") still slip through; the cost
     * is one mid-sentence pause per occurrence, no worse than the existing word-cap
     * run-on fallback.
     */
    private fun endsSentence(text: String): Boolean {
        if (text.isEmpty()) return false
        var i = text.length - 1
        while (i >= 0 && text[i] in CLOSING_AFTER_TERMINATOR) i--
        if (i < 0) return false
        if (text[i] !in SENTENCE_TERMINATORS) return false

        // Strip the same closers from the abbreviation candidate so `Mr."` matches `Mr.`.
        val core = text.substring(0, i + 1)
        if (core in NON_TERMINAL_ABBREVIATIONS) return false
        // Single-letter initial like `J.` or `K.` — treat as non-terminal so "J. R. R.
        // Tolkien" doesn't fragment into three utterances.
        if (core.length == 2 && core[0].isLetter() && core[0].isUpperCase() && core[1] == '.') {
            return false
        }
        return true
    }

    private fun buildUtterance(
        builder: StringBuilder,
        ranges: List<IntRange>,
        indices: List<Int>,
        paragraphEnd: Boolean
    ): TtsUtterance = TtsUtterance(
        text = builder.toString(),
        wordRanges = ranges.toList(),
        globalWordIndices = indices.toList(),
        hasParagraphBreakAtEnd = paragraphEnd
    )
}

package com.speedread.rsvp.engine

interface TextProcessor {
    fun processText(text: String, settings: RsvpSettings = RsvpSettings()): List<RsvpWord>
    fun rechunk(singleWordTokens: List<RsvpWord>, chunkSize: Int): List<RsvpWord>
}

/**
 * Token separators recognised by the tokenizer. Extends Kotlin's `Char.isWhitespace()`
 * (which delegates to Character.isWhitespace) with characters that the JDK does NOT
 * classify as whitespace but that either render as line breaks in TextView (UAX #14
 * mandatory or strong break opportunities) or are zero-width / format characters that
 * should never live inside a word token. Without this extension, sources that emit
 * these chars — notably PDF extractors that interleave control characters between
 * glyphs — produce mega-tokens with embedded line-break chars; the joined
 * `continuousText` then breaks line per glyph in the Page View body TextView, showing
 * up as `" / i / n / [gap] / t` per-character wrapping with the snippet overlay
 * appearing to "fix" it (the overlay's opaque background just masks the symptom).
 *
 * Char classes added beyond `isWhitespace()`:
 *   - `isISOControl()` — every C0 control (U+0000 – U+001F + U+007F DEL) AND every C1
 *     control (U+0080 – U+009F, which includes NEL at U+0085 — a UAX #14 mandatory
 *     line break). C0 chars 0x09 (\t), 0x0A (\n), 0x0B (\v), 0x0C (\f), 0x0D (\r),
 *     and 0x1C..0x1F (FS/GS/RS/US) are already in `isWhitespace`; the new chars caught
 *     here are 0x00..0x07 (NUL..BEL), 0x0E..0x1B (SO..ESC), 0x7F (DEL), and the entire
 *     C1 block — which is exactly the noise PDF/EPUB extractors emit between text-run
 *     boundaries. Cheap range check, no per-char Unicode lookup.
 *   - U+200B ZWSP — Zero Width Space, UAX #14 ZW class (break opportunity).
 *   - U+FEFF BOM / ZERO WIDTH NO-BREAK SPACE — invisible format char.
 *
 * Deliberately NOT included:
 *   - U+200C ZWNJ / U+200D ZWJ — invisible joiner controls used WITHIN words by Arabic,
 *     Devanagari, and other complex scripts; treating them as separators would split
 *     legitimate multi-glyph words.
 *   - U+200E LRM / U+200F RLM — BiDi direction marks; UAX #14 CM class, no break.
 *   - U+00AD SOFT HYPHEN — common typographer's hint inside words for break-point
 *     suggestions; treating as separator would shred any justified PDF text that
 *     pre-encodes soft-hyphens for hyphenation.
 *
 * Control chars are intentionally NOT promoted to LINE/PARAGRAPH breaks (they fall
 * into the "plain whitespace" branch of the tokenizer rather than `isNewline`). A PDF
 * that sprays a control char between every glyph would otherwise inject a
 * `LINE_BREAK_PAUSE` per character into RSVP narration. Cleaning the token boundary
 * is enough to fix the Page View rendering bug; the pause-shape semantics stay opt-in
 * to real `\n` / `\r`.
 *
 * Specific extra codepoints are written as `Char(0xXXXX)` (not `'\uXXXX'` or raw
 * glyphs) so they stay visible / greppable in source — the underlying chars are
 * zero-width or control characters that any text editor would render as blank.
 */
private const val CHAR_NO_BREAK_SPACE = 0x00A0
private const val CHAR_NARROW_NO_BREAK_SPACE = 0x202F
private const val CHAR_ZERO_WIDTH_SPACE = 0x200B
private const val CHAR_BOM = 0xFEFF

internal fun Char.isWordSeparator(): Boolean = isWhitespace() ||
    isISOControl() ||
    this.code == CHAR_NO_BREAK_SPACE ||
    this.code == CHAR_NARROW_NO_BREAK_SPACE ||
    this.code == CHAR_ZERO_WIDTH_SPACE ||
    this.code == CHAR_BOM

class DefaultTextProcessor : TextProcessor {
    override fun processText(text: String, settings: RsvpSettings): List<RsvpWord> {
        if (text.isBlank()) return emptyList()
        return processTextInMemory(text, settings)
    }

    private fun processTextInMemory(text: String, settings: RsvpSettings): List<RsvpWord> {
        // Tokenize while preserving LINE / PARAGRAPH break metadata on the word that precedes the break.
        val tokens = tokenizeWithBreaks(text).take(EngineConstants.MAX_TOKENS)

        return when {
            settings.chunkSize <= 1 -> createSingleWordChunks(tokens)
            else -> createMultiWordChunks(tokens, settings.chunkSize)
        }
    }

    /**
     * Splits [text] into words and annotates each with the break (if any) that follows it in the source.
     * A run of two or more newlines counts as PARAGRAPH; a single newline counts as LINE. Other whitespace
     * (and the extra separators recognised by [isWordSeparator]) is treated as a plain separator.
     *
     * Typographic-wrap suppression: a break is only recorded when it looks SEMANTIC. Text
     * extracted from PDFs/EPUBs is hard-wrapped — single newlines at every rendered line and
     * double newlines at page/column boundaries, both landing mid-sentence ("adjudging the
     * \n\n responsibility"). Recording those verbatim injects paragraph-length pauses into
     * RSVP narration and, worse, forces TtsChunker to end the speech utterance mid-clause
     * (each utterance boundary carries the TTS service's intrinsic inter-utterance gap plus
     * sentence-final prosody — an audible mid-sentence stall that no timing setting can
     * remove). A break survives only if the preceding word ends with sentence-final
     * punctuation (optionally wrapped in closing quotes/brackets) OR the following word does
     * NOT start with a lowercase letter — real paragraphs virtually always satisfy one of
     * these, while wrap artifacts virtually never do. Headings keep their breaks via the
     * uppercase rule; enjambed poetry lines starting lowercase are the accepted casualty.
     */
    private fun tokenizeWithBreaks(text: String): List<TokenWithBreak> {
        if (text.isBlank()) return emptyList()
        val tokens = mutableListOf<TokenWithBreak>()
        val word = StringBuilder()
        var newlineRun = 0
        var pendingBreak: TrailingBreak? = null

        fun flushWord() {
            if (word.isEmpty()) return
            val trimmed = word.toString()
            // Apply the break that accumulated BEFORE this word to the previous word —
            // unless it is a typographic wrap artifact (see the function KDoc).
            val pending = pendingBreak
            if (pending != null && tokens.isNotEmpty()) {
                val prevIndex = tokens.lastIndex
                val prev = tokens[prevIndex]
                if (!isTypographicWrapBreak(prev.text, trimmed)) {
                    // Never downgrade PARAGRAPH to LINE.
                    val merged = when {
                        prev.trailingBreak == TrailingBreak.PARAGRAPH -> TrailingBreak.PARAGRAPH
                        else -> pending
                    }
                    tokens[prevIndex] = prev.copy(trailingBreak = merged)
                }
            }
            tokens += TokenWithBreak(trimmed, null)
            word.setLength(0)
            pendingBreak = null
        }

        var i = 0
        while (i < text.length) {
            if (tokens.size >= EngineConstants.MAX_TOKENS) break
            val ch = text[i]

            // Collapse CRLF pairs on the fly so Windows line endings count as one
            // line break (not two, which would misclassify as PARAGRAPH).
            val isNewline = when {
                ch == '\n' -> true
                ch == '\r' -> {
                    if (i + 1 < text.length && text[i + 1] == '\n') i++ // Skip paired LF
                    true
                }
                else -> false
            }

            when {
                isNewline -> {
                    flushWord()
                    newlineRun++
                    pendingBreak = if (newlineRun >= 2) TrailingBreak.PARAGRAPH else TrailingBreak.LINE
                }
                ch.isWordSeparator() -> {
                    flushWord()
                    newlineRun = 0
                }
                else -> {
                    word.append(ch)
                    newlineRun = 0
                }
            }
            i++
        }
        flushWord()

        // Apply any trailing break accumulated after the last word.
        if (pendingBreak != null && tokens.isNotEmpty()) {
            val prevIndex = tokens.lastIndex
            val prev = tokens[prevIndex]
            val merged = when {
                prev.trailingBreak == TrailingBreak.PARAGRAPH -> TrailingBreak.PARAGRAPH
                else -> pendingBreak
            }
            tokens[prevIndex] = prev.copy(trailingBreak = merged)
        }
        return tokens
    }

    private data class TokenWithBreak(val text: String, val trailingBreak: TrailingBreak?)

    /**
     * True when a line/paragraph break between [previousText] and [followingText] is a
     * hard-wrap artifact rather than a semantic break: the previous word does not end a
     * sentence AND the next word continues in lowercase. Sentence-final punctuation may be
     * wrapped in closing quotes/brackets (`."`, `?")`, `.']`). Non-letter starts (digits,
     * quotes, bullets) count as "not lowercase" so lists and dialogue keep their breaks.
     */
    private fun isTypographicWrapBreak(previousText: String, followingText: String): Boolean {
        if (endsSentenceLike(previousText)) return false
        val firstChar = followingText.firstOrNull() ?: return false
        return firstChar.isLowerCase()
    }

    private fun endsSentenceLike(text: String): Boolean {
        var i = text.length - 1
        while (i >= 0 && text[i] in CLOSING_PUNCTUATION) i--
        return i >= 0 && text[i] in SENTENCE_FINAL_CHARS
    }

    private companion object {
        // Punctuation that can legitimately end a paragraph's final word. Comma and em-dash
        // are deliberately excluded — paragraphs do not end with them, so a break after one
        // followed by a lowercase word is always a wrap artifact.
        private val SENTENCE_FINAL_CHARS = setOf('.', '!', '?', ':', ';', Char(0x2026))

        // Closers that may wrap sentence-final punctuation: straight/curly quotes, brackets.
        private val CLOSING_PUNCTUATION = setOf(
            '"', '\'', ')', ']', '}', Char(0x2019), Char(0x201D)
        )
    }

    private fun createSingleWordChunks(tokens: List<TokenWithBreak>): List<RsvpWord> {
        return tokens.mapIndexed { index, token ->
            RsvpWord(
                text = token.text,
                position = index,
                trailingBreak = token.trailingBreak
            )
        }
    }

    private fun createMultiWordChunks(tokens: List<TokenWithBreak>, chunkSize: Int): List<RsvpWord> {
        // Defensive clamp: never let one chunk swallow more tokens than exist, and cap at
        // MAX_CHUNK_SIZE so a pathological chunkSize (e.g. 1000 on a 50-word doc) can't
        // produce a single giant concatenated "word" with a meaningless ORP index.
        val effectiveChunkSize = chunkSize
            .coerceAtMost(EngineConstants.MAX_CHUNK_SIZE)
            .coerceAtMost(tokens.size.coerceAtLeast(1))
            .coerceAtLeast(2)
        
        var absoluteWordIndex = 0
        return tokens.chunked(effectiveChunkSize).mapIndexed { chunkIndex, chunk ->
            val chunkText = chunk.joinToString(" ") { it.text }
            // Promote the strongest break within the chunk to the chunk word (paragraph > line > null).
            val chunkBreak = chunk.fold<TokenWithBreak, TrailingBreak?>(null) { acc, t ->
                when {
                    t.trailingBreak == TrailingBreak.PARAGRAPH -> TrailingBreak.PARAGRAPH
                    t.trailingBreak == TrailingBreak.LINE && acc != TrailingBreak.PARAGRAPH -> TrailingBreak.LINE
                    else -> acc
                }
            }
            val startIdx = absoluteWordIndex
            val endIdx = absoluteWordIndex + chunk.size - 1
            absoluteWordIndex += chunk.size
            RsvpWord(
                text = chunkText,
                position = chunkIndex,
                orpIndex = calculateChunkOrpIndex(chunk.map { it.text }),
                trailingBreak = chunkBreak,
                absoluteStartIndex = startIdx,
                absoluteEndIndex = endIdx
            )
        }
    }

    override fun rechunk(singleWordTokens: List<RsvpWord>, chunkSize: Int): List<RsvpWord> {
        if (chunkSize <= 1) return singleWordTokens
        val tokens = singleWordTokens.map { TokenWithBreak(it.text, it.trailingBreak) }
        return createMultiWordChunks(tokens, chunkSize)
    }

    private fun calculateChunkOrpIndex(chunk: List<String>): Int {
        if (chunk.isEmpty()) return 0
        val middleWordIndex = chunk.size / 2
        val beforeWords = chunk.take(middleWordIndex)
        val middleWord = chunk[middleWordIndex]
        val beforeLength = beforeWords.sumOf { it.length } + beforeWords.size
        val middleWordOrp = RsvpWord.calculateOrp(middleWord)
        return beforeLength + middleWordOrp
    }
}

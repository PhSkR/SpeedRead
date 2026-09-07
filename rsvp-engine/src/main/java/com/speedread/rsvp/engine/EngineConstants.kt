package com.speedread.rsvp.engine

/**
 * Constants used throughout the RSVP engine
 */
object EngineConstants {
    
    // Default settings
    const val DEFAULT_WPM = 250
    const val DEFAULT_CHUNK_SIZE = 1

    // Timing settings
    const val DEFAULT_COMMA_PAUSE = 150      // milliseconds
    const val DEFAULT_PERIOD_PAUSE = 300     // milliseconds
    const val DEFAULT_SEMICOLON_PAUSE = 200  // milliseconds
    const val DEFAULT_COLON_PAUSE = 200      // milliseconds
    const val DEFAULT_QUESTION_PAUSE = 350   // milliseconds
    const val DEFAULT_EXCLAMATION_PAUSE = 350 // milliseconds
    const val DEFAULT_LINE_BREAK_PAUSE = 100 // milliseconds
    const val DEFAULT_PARAGRAPH_PAUSE = 500  // milliseconds

    // Closing punctuation that may follow a sentence- or clause-ending punctuation mark
    // (e.g. in dialogue, quotations, or parenthetical clauses).
    val CLOSING_PUNCTUATION: Set<Char> = setOf(
        '"', '\'', ')', ']', '}',
        '’', // U+2019 right single quote
        '”', // U+201D right double quote
        '»'  // U+00BB right-pointing double angle quotation mark
    )

    // Word length timing — scales display duration by character count
    const val DEFAULT_WORD_LENGTH_BASELINE = 5
    const val DEFAULT_WORD_LENGTH_SCALING_PERCENT = 8
    const val MIN_WORD_LENGTH_BASELINE = 2
    const val MAX_WORD_LENGTH_BASELINE = 10
    const val MIN_WORD_LENGTH_SCALING_PERCENT = 1
    const val MAX_WORD_LENGTH_SCALING_PERCENT = 25
    const val MAX_WORD_LENGTH_MULTIPLIER = 3.0

    // Colors
    const val DEFAULT_TEXT_COLOR = 0xFF000000.toInt() // Black
    const val DEFAULT_BACKGROUND_COLOR = 0xFFFFFFFF.toInt() // White
    const val DEFAULT_ORP_COLOR = 0xFFFF6B35.toInt() // Orange

    // Reader UI — opt-in focus aid that dims the area above/below the word display while Playing.
    const val DEFAULT_ENABLE_SCREEN_DIMMING = false

    // Default corner(s) for the landscape compact hold-to-play FABs. The selector is additive:
    // every corner in this set renders its own FAB simultaneously (up to all four). Bottom-start
    // is the single-corner default — near the thumb for a typical right-handed landscape grip
    // and clear of the gesture-nav back zone.
    val DEFAULT_LANDSCAPE_PLAY_BUTTON_CORNERS: Set<LandscapePlayButtonCorner> =
        setOf(LandscapePlayButtonCorner.BOTTOM_START)

    // Pixels of horizontal drag that advance or rewind the reader by one word in landscape
    // scrub. Higher → slower / more precise. 100px default feels like ~20 words per full
    // card-width drag on a typical 2400px landscape phone — roughly 5s of reading at 250 WPM.
    const val DEFAULT_SCRUB_PIXELS_PER_WORD = 100f
    const val MIN_SCRUB_PIXELS_PER_WORD = 10f
    const val MAX_SCRUB_PIXELS_PER_WORD = 300f

    // Hard ceiling on tokens per document. Raised from 100k to 500k; truncation
    // past this point is surfaced to the UI via RsvpEngine.wasLastLoadTruncated.
    const val MAX_TOKENS = 500_000

    // Defensive upper bound on multi-word chunk size. UI slider currently maxes at 5;
    // this ceiling absorbs any pathological persisted value so createMultiWordChunks
    // cannot produce a single chunk that swallows an entire document.
    const val MAX_CHUNK_SIZE = 10

    // Progress stages emitted by loadTextWithProgress. Coarse phases rather than a
    // linear ramp because the underlying tokenize is a single O(n) pass.
    const val PROGRESS_STAGE_START = 0.1f
    const val PROGRESS_STAGE_TOKENIZED = 0.9f
    const val PROGRESS_STAGE_DONE = 1.0f
}
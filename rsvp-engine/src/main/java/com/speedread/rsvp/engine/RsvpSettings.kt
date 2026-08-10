package com.speedread.rsvp.engine

data class PunctuationTiming(
    val comma: Int = EngineConstants.DEFAULT_COMMA_PAUSE,        // milliseconds
    val period: Int = EngineConstants.DEFAULT_PERIOD_PAUSE,       // milliseconds
    val semicolon: Int = EngineConstants.DEFAULT_SEMICOLON_PAUSE,    // milliseconds
    val colon: Int = EngineConstants.DEFAULT_COLON_PAUSE,        // milliseconds
    val questionMark: Int = EngineConstants.DEFAULT_QUESTION_PAUSE, // milliseconds
    val exclamation: Int = EngineConstants.DEFAULT_EXCLAMATION_PAUSE,  // milliseconds
    val lineBreak: Int = EngineConstants.DEFAULT_LINE_BREAK_PAUSE,    // milliseconds
    val paragraphBreak: Int = EngineConstants.DEFAULT_PARAGRAPH_PAUSE // milliseconds
) {
    companion object {
        fun default() = PunctuationTiming()
        
        fun getPauseForCharacter(char: Char, timing: PunctuationTiming): Int {
            return when (char) {
                ',' -> timing.comma
                '.' -> timing.period
                ';' -> timing.semicolon
                ':' -> timing.colon
                '?' -> timing.questionMark
                '!' -> timing.exclamation
                '\n' -> timing.lineBreak
                else -> 0
            }
        }
        
        fun getPauseForParagraphBreak(timing: PunctuationTiming): Int = timing.paragraphBreak
    }
}

data class WordLengthTiming(
    val baseline: Int = EngineConstants.DEFAULT_WORD_LENGTH_BASELINE,
    val scalingPercent: Int = EngineConstants.DEFAULT_WORD_LENGTH_SCALING_PERCENT
) {
    companion object {
        fun default() = WordLengthTiming()
    }
}

/**
 * Which corner the compact hold-to-play FAB anchors to in landscape "reader mode".
 * Values are orientation-neutral (START/END, not LEFT/RIGHT) so RTL locales place
 * the button symmetrically.
 */
enum class LandscapePlayButtonCorner {
    TOP_START,
    TOP_END,
    BOTTOM_START,
    BOTTOM_END
}

data class RsvpSettings(
    val wpm: Int = EngineConstants.DEFAULT_WPM,
    val chunkSize: Int = EngineConstants.DEFAULT_CHUNK_SIZE,
    val enableOrp: Boolean = true,
    val centerOrp: Boolean = true,
    val orpColor: Int = EngineConstants.DEFAULT_ORP_COLOR,
    val enablePunctuationPausing: Boolean = true,
    val punctuationTiming: PunctuationTiming = PunctuationTiming.default(),
    val textColor: Int = EngineConstants.DEFAULT_TEXT_COLOR, // Default black
    val backgroundColor: Int = EngineConstants.DEFAULT_BACKGROUND_COLOR, // Default white
    val enableScreenDimming: Boolean = EngineConstants.DEFAULT_ENABLE_SCREEN_DIMMING,
    // Additive — every corner in this set renders its own compact FAB. At least one corner is
    // always selected (persistence + UI enforce non-empty) so the reader is never without a
    // hold-to-play affordance in landscape.
    val landscapePlayButtonCorners: Set<LandscapePlayButtonCorner> =
        EngineConstants.DEFAULT_LANDSCAPE_PLAY_BUTTON_CORNERS,
    val scrubPixelsPerWord: Float = EngineConstants.DEFAULT_SCRUB_PIXELS_PER_WORD,
    val enableParagraphSpacing: Boolean = false,
    val enableWordLengthTiming: Boolean = false,
    val wordLengthTiming: WordLengthTiming = WordLengthTiming()
)
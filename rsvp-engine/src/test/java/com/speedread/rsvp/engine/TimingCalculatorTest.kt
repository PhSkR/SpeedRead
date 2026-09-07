package com.speedread.rsvp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingCalculatorTest {

    private val calc = DefaultTimingCalculator()

    @Test
    fun baseDelayAt250Wpm() {
        val word = RsvpWord(text = "hello", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = false)
        assertEquals(240L, calc.calculateDelay(word, settings))
    }

    @Test
    fun periodAddsPeriodPause() {
        val word = RsvpWord(text = "end.", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        val expected = 240L + EngineConstants.DEFAULT_PERIOD_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    @Test
    fun commaAddsCommaPause() {
        val word = RsvpWord(text = "one,", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        val expected = 240L + EngineConstants.DEFAULT_COMMA_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    @Test
    fun paragraphBreakAppliesParagraphPause() {
        val word = RsvpWord(text = "end", position = 0, trailingBreak = TrailingBreak.PARAGRAPH)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        val expected = 240L + EngineConstants.DEFAULT_PARAGRAPH_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    @Test
    fun lineBreakAppliesLineBreakPause() {
        val word = RsvpWord(text = "end", position = 0, trailingBreak = TrailingBreak.LINE)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        val expected = 240L + EngineConstants.DEFAULT_LINE_BREAK_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    @Test
    fun punctuationPausingDisabledIgnoresPeriod() {
        val word = RsvpWord(text = "end.", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = false)
        assertEquals(240L, calc.calculateDelay(word, settings))
    }

    @Test
    fun delayClampedToFloor() {
        val word = RsvpWord(text = "x", position = 0)
        // WPM=2000 → 30ms base; with no punctuation, still 30ms (above 1ms floor).
        val settings = RsvpSettings(wpm = 2000, enablePunctuationPausing = false)
        val delay = calc.calculateDelay(word, settings)
        assertTrue(delay >= 1L)
    }

    @Test
    fun delayClampedToCeiling() {
        val word = RsvpWord(text = "end.", position = 0, trailingBreak = TrailingBreak.PARAGRAPH)
        // WPM=1 → 60000ms base; plus punctuation; must clamp to 30000.
        val settings = RsvpSettings(wpm = 1, enablePunctuationPausing = true)
        assertEquals(30_000L, calc.calculateDelay(word, settings))
    }

    @Test
    fun wpmZeroCoercedToOne() {
        val word = RsvpWord(text = "x", position = 0)
        val settings = RsvpSettings(wpm = 0, enablePunctuationPausing = false)
        // Safe coerce → wpm 1 → 60000ms base → clamped to 30000ms ceiling.
        assertEquals(30_000L, calc.calculateDelay(word, settings))
     }

    @Test
    fun multiWordChunkScalesBaseDelay() {
        // A chunk of 5 words at WPM 250:
        // Base delay per word = 60000 / 250 = 240ms.
        // Base delay for 5 words = 240ms * 5 = 1200ms.
        val word = RsvpWord(text = "one two three four five", position = 0, absoluteStartIndex = 0, absoluteEndIndex = 4)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = false)
        assertEquals(1200L, calc.calculateDelay(word, settings))
    }

    // --- Word Length Timing ---

    @Test
    fun wordLengthTimingDisabledHasNoEffect() {
        val short = RsvpWord(text = "hi", position = 0)
        val long = RsvpWord(text = "abcdefghij", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = false, enableWordLengthTiming = false)
        assertEquals(240L, calc.calculateDelay(short, settings))
        assertEquals(240L, calc.calculateDelay(long, settings))
    }

    @Test
    fun longWordIncreasesDelay() {
        // 10 chars, baseline 5, scaling 8%: delta=5, multiplier=1.4, 240*1.4=336
        val word = RsvpWord(text = "abcdefghij", position = 0)
        val settings = RsvpSettings(
            wpm = 250, enablePunctuationPausing = false,
            enableWordLengthTiming = true,
            wordLengthTiming = WordLengthTiming(baseline = 5, scalingPercent = 8)
        )
        assertEquals(336L, calc.calculateDelay(word, settings))
    }

    @Test
    fun shortWordDecreasesDelay() {
        // 2 chars, baseline 5, scaling 8%: delta=-3, multiplier=0.76, 240*0.76=182
        val word = RsvpWord(text = "hi", position = 0)
        val settings = RsvpSettings(
            wpm = 250, enablePunctuationPausing = false,
            enableWordLengthTiming = true,
            wordLengthTiming = WordLengthTiming(baseline = 5, scalingPercent = 8)
        )
        assertEquals(182L, calc.calculateDelay(word, settings))
    }

    @Test
    fun baselineLengthWordUnchanged() {
        val word = RsvpWord(text = "hello", position = 0)
        val settings = RsvpSettings(
            wpm = 250, enablePunctuationPausing = false,
            enableWordLengthTiming = true,
            wordLengthTiming = WordLengthTiming(baseline = 5, scalingPercent = 8)
        )
        assertEquals(240L, calc.calculateDelay(word, settings))
    }

    @Test
    fun extremeScalingClampsMultiplier() {
        // 1 char, baseline 10, scaling 25%: delta=-9, raw multiplier=-1.25, clamped to 0.1
        // 240 * 0.1 = 24
        val word = RsvpWord(text = "I", position = 0)
        val settings = RsvpSettings(
            wpm = 250, enablePunctuationPausing = false,
            enableWordLengthTiming = true,
            wordLengthTiming = WordLengthTiming(baseline = 10, scalingPercent = 25)
        )
        assertEquals(24L, calc.calculateDelay(word, settings))
    }

    @Test
    fun longWordClampsMultiplierAtCeiling() {
        // 30 chars, baseline 2, scaling 25%: delta=28, raw multiplier=8.0, clamped to 3.0
        // 240 * 3.0 = 720
        val word = RsvpWord(text = "abcdefghijklmnopqrstuvwxyz1234", position = 0)
        val settings = RsvpSettings(
            wpm = 250, enablePunctuationPausing = false,
            enableWordLengthTiming = true,
            wordLengthTiming = WordLengthTiming(baseline = 2, scalingPercent = 25)
        )
        assertEquals(720L, calc.calculateDelay(word, settings))
    }

    @Test
    fun wordLengthTimingStacksWithPunctuation() {
        // 10-char word ending with period: word-length scales base, then period pause added on top
        // base=240, scaled=240*1.4=336, + period(300) = 636
        val word = RsvpWord(text = "sentence.", position = 0)
        val settings = RsvpSettings(
            wpm = 250, enablePunctuationPausing = true,
            enableWordLengthTiming = true,
            wordLengthTiming = WordLengthTiming(baseline = 5, scalingPercent = 8)
        )
        // "sentence." is 9 chars, delta=4, multiplier=1.32, 240*1.32=316 (truncated toLong)
        val expected = 316L + EngineConstants.DEFAULT_PERIOD_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    // --- Quoted Dialogue & Closing Punctuation ---

    @Test
    fun quotedExclamationAddsExclamationPause() {
        val word = RsvpWord(text = "\"Hello!\"", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        val expected = 240L + EngineConstants.DEFAULT_EXCLAMATION_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    @Test
    fun curlyQuotedPeriodAddsPeriodPause() {
        val word = RsvpWord(text = "“Yes.”", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        val expected = 240L + EngineConstants.DEFAULT_PERIOD_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    @Test
    fun quotedQuestionAddsQuestionPause() {
        val word = RsvpWord(text = "\"Really?\"", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        val expected = 240L + EngineConstants.DEFAULT_QUESTION_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    @Test
    fun quotedCommaAddsCommaPause() {
        val word = RsvpWord(text = "\"Wait,\"", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        val expected = 240L + EngineConstants.DEFAULT_COMMA_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    @Test
    fun singleQuotedExclamationAddsExclamationPause() {
        val word = RsvpWord(text = "'Stop!'", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        val expected = 240L + EngineConstants.DEFAULT_EXCLAMATION_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    @Test
    fun parentheticalPeriodAddsPeriodPause() {
        val word = RsvpWord(text = "(clause.)", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        val expected = 240L + EngineConstants.DEFAULT_PERIOD_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    @Test
    fun bracketQuestionAddsQuestionPause() {
        val word = RsvpWord(text = "[who?]", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        val expected = 240L + EngineConstants.DEFAULT_QUESTION_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    @Test
    fun guillemetExclamationAddsExclamationPause() {
        val word = RsvpWord(text = "«Merci!»", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        val expected = 240L + EngineConstants.DEFAULT_EXCLAMATION_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    @Test
    fun multipleClosingQuotesHandled() {
        val word = RsvpWord(text = "“Stop!””", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        val expected = 240L + EngineConstants.DEFAULT_EXCLAMATION_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    @Test
    fun stackedQuotesAndParenHandled() {
        val word = RsvpWord(text = "(\"Wait!\")", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        val expected = 240L + EngineConstants.DEFAULT_EXCLAMATION_PAUSE
        assertEquals(expected, calc.calculateDelay(word, settings))
    }

    @Test
    fun onlyClosingPunctuationReturnsNoPunctuationPause() {
        val word = RsvpWord(text = "\"\"", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        assertEquals(240L, calc.calculateDelay(word, settings))
    }

    @Test
    fun closingPunctuationWithoutPrecedingPunctuationReturnsNoPause() {
        val word = RsvpWord(text = "word)", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = true)
        assertEquals(240L, calc.calculateDelay(word, settings))
    }

    @Test
    fun punctuationPausingDisabledIgnoresQuotedPunctuation() {
        val word = RsvpWord(text = "\"Hello!\"", position = 0)
        val settings = RsvpSettings(wpm = 250, enablePunctuationPausing = false)
        assertEquals(240L, calc.calculateDelay(word, settings))
    }
}

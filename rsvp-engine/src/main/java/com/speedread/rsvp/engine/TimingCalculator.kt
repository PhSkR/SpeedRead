package com.speedread.rsvp.engine

interface TimingCalculator {
    fun calculateDelay(word: RsvpWord, settings: RsvpSettings): Long
}

class DefaultTimingCalculator : TimingCalculator {
    override fun calculateDelay(word: RsvpWord, settings: RsvpSettings): Long {
        // Ensure WPM is positive and reasonable
        val safeWpm = settings.wpm.coerceIn(1, 2000)
        // Scale base delay linearly with the number of words inside this chunk.
        // Falls back safely to 1 if indices are uninitialised or corrupted.
        val wordCount = (word.absoluteEndIndex - word.absoluteStartIndex + 1).coerceAtLeast(1)
        val baseDelay = (60_000L / safeWpm) * wordCount // milliseconds per chunk

        val wordLengthAdjustedDelay = if (settings.enableWordLengthTiming) {
            val avgCharsPerWord = word.text.length.toDouble() / wordCount
            val charDelta = avgCharsPerWord - settings.wordLengthTiming.baseline
            val multiplier = (1.0 + charDelta * settings.wordLengthTiming.scalingPercent / 100.0)
                .coerceIn(0.1, EngineConstants.MAX_WORD_LENGTH_MULTIPLIER)
            (baseDelay * multiplier).toLong()
        } else baseDelay

        val punctuationDelay = if (settings.enablePunctuationPausing) {
            calculatePunctuationDelay(word.text, settings.punctuationTiming)
        } else 0L

        val breakDelay = if (settings.enablePunctuationPausing) {
            when (word.trailingBreak) {
                TrailingBreak.LINE -> settings.punctuationTiming.lineBreak.toLong()
                TrailingBreak.PARAGRAPH -> settings.punctuationTiming.paragraphBreak.toLong()
                null -> 0L
            }
        } else 0L

        // Ensure delay is reasonable (at least 1ms, max 30 seconds)
        return (wordLengthAdjustedDelay + punctuationDelay + breakDelay).coerceIn(1L, 30_000L)
    }

    private fun calculatePunctuationDelay(text: String, timing: PunctuationTiming): Long {
        if (text.isEmpty()) return 0L
        var i = text.length - 1
        while (i >= 0 && text[i] in EngineConstants.CLOSING_PUNCTUATION) {
            i--
        }
        if (i < 0) return 0L
        val lastChar = text[i]
        return PunctuationTiming.getPauseForCharacter(lastChar, timing).toLong()
    }
}
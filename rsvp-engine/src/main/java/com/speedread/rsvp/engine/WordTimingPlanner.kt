package com.speedread.rsvp.engine

import kotlin.math.ceil

/**
 * A pre-computed map from word index -> playback frame at which that word ends.
 *
 * `endFrames[i]` is the cumulative frame count (samples since the utterance's first
 * frame, for mono PCM) at which word `i` should yield the on-screen highlight to word
 * `i + 1`. The poller in NeuralTtsEngine compares `AudioTrack.playbackHeadPosition -
 * playStartFrame` against these thresholds and advances the displayed word accordingly.
 *
 * Per-word weight (longer words = more screen time) is computed by [WordTimingPlanner].
 */
data class WordTimingPlan(
    val endFrames: IntArray,
    val totalFrames: Int
) {
    val wordCount: Int get() = endFrames.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WordTimingPlan) return false
        return endFrames.contentEquals(other.endFrames) && totalFrames == other.totalFrames
    }

    override fun hashCode(): Int = 31 * endFrames.contentHashCode() + totalFrames
}

/**
 * Builds [WordTimingPlan] for an utterance given its synthesized total frames.
 *
 * The neural TTS path (Sherpa-ONNX Piper) produces one PCM blob per utterance with no
 * per-word timestamps. To keep the on-screen RSVP word in sync with the spoken voice we
 * partition the audio's frame axis between words, weighted by syllable count for Latin
 * scripts (so "the" gets less screen time than "synchronization") and by character
 * length for non-Latin scripts where the syllable heuristic does not apply.
 */
object WordTimingPlanner {

    private const val NON_LATIN_SENTINEL = -1

    // Latin vowels including common Latin-1 / Latin-Extended-A accented forms so that
    // languages like French, Spanish, German, Portuguese, etc. count vowel groups
    // correctly through the same heuristic ("café" -> 2, not 1).
    private const val VOWELS =
        "aeiouy" +
        "àáâãäåæ" +   // a-grave .. ae
        "èéêë" +                       // e-grave .. e-diaeresis
        "ìíîï" +                       // i-grave .. i-diaeresis
        "òóôõö" +                 // o-grave .. o-diaeresis
        "ùúûü" +                       // u-grave .. u-diaeresis
        "ýÿ" +                                   // y-acute, y-diaeresis
        "œ"                                           // oe ligature

    fun plan(
        utterance: TtsUtterance,
        totalFrames: Int,
        minSyllablesPerWord: Int,
        maxSyllablesPerWord: Int,
        nonLatinCharWeightScale: Double
    ): WordTimingPlan {
        val n = utterance.wordCount
        val total = totalFrames.coerceAtLeast(0)
        if (n == 0 || total == 0) {
            return WordTimingPlan(IntArray(0), total)
        }

        val text = utterance.text
        val ranges = utterance.wordRanges
        val weights = IntArray(n) { i ->
            val r = ranges[i]
            val word = text.substring(r.first, r.last + 1)
            weightForWord(word, minSyllablesPerWord, maxSyllablesPerWord, nonLatinCharWeightScale)
        }
        var sum = 0L
        for (w in weights) sum += w
        if (sum <= 0L) {
            // Floor of 1 keeps sum > 0; this branch is defensive against future edits to
            // the weight bounds.
            return WordTimingPlan(IntArray(n) { total }, total)
        }

        val endFrames = IntArray(n)
        var cum = 0L
        for (i in 0 until n) {
            cum += weights[i]
            endFrames[i] = ((cum * total) / sum).toInt().coerceAtMost(total)
        }
        // Integer rounding can leave the last threshold one frame short; pin the last
        // word to the exact end of the audio so the highlight reaches the period when
        // the voice does.
        endFrames[n - 1] = total
        return WordTimingPlan(endFrames, total)
    }

    fun weightForWord(
        word: String,
        minSyllablesPerWord: Int,
        maxSyllablesPerWord: Int,
        nonLatinCharWeightScale: Double
    ): Int {
        val syllables = countSyllables(word)
        return if (syllables == NON_LATIN_SENTINEL) {
            val charLength = word.count { it.isLetterOrDigit() }.coerceAtLeast(1)
            ceil(charLength * nonLatinCharWeightScale).toInt()
                .coerceIn(minSyllablesPerWord, maxSyllablesPerWord)
        } else {
            syllables.coerceIn(minSyllablesPerWord, maxSyllablesPerWord)
        }
    }

    /**
     * Returns the syllable count for a Latin-script word, or -1 if the word contains any
     * non-Latin letter (caller should fall back to character-length scaling).
     */
    fun countSyllables(word: String): Int {
        val stripped = word.trim { !it.isLetter() }.lowercase()
        if (stripped.isEmpty()) return 1

        for (ch in stripped) {
            if (!ch.isLatinLetter()) return NON_LATIN_SENTINEL
        }

        var count = 0
        var prevVowel = false
        for (ch in stripped) {
            val isVowel = ch in VOWELS
            if (isVowel && !prevVowel) count++
            prevVowel = isVowel
        }

        if (stripped.length >= 3 && stripped.last() == 'e' && count > 1) {
            val before = stripped[stripped.length - 2]
            val beforeIsConsonant = before !in VOWELS
            // The classic "-le after consonant" exception (table, puzzle, simple): the
            // 'e' is part of the syllabic 'l', not silent, so don't subtract.
            val isLeAfterConsonant =
                before == 'l' && stripped[stripped.length - 3] !in VOWELS
            if (beforeIsConsonant && !isLeAfterConsonant) count--
        }

        return count.coerceAtLeast(1)
    }

    private fun Char.isLatinLetter(): Boolean {
        val code = this.code
        return code in 0x0041..0x005A ||           // A-Z
               code in 0x0061..0x007A ||           // a-z
               code in 0x00C0..0x024F              // Latin-1 Supplement, Extended-A, Extended-B
    }
}

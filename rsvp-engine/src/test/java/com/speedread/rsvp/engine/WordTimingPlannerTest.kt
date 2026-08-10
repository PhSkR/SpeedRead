package com.speedread.rsvp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimingPlannerTest {

    private val minWeight = 1
    private val maxWeight = 6
    private val nonLatinScale = 0.5

    // ---- countSyllables ------------------------------------------------------------

    @Test
    fun countSyllables_basicWords() {
        assertEquals(1, WordTimingPlanner.countSyllables("the"))
        assertEquals(1, WordTimingPlanner.countSyllables("a"))
        assertEquals(5, WordTimingPlanner.countSyllables("synchronization"))
        assertEquals(5, WordTimingPlanner.countSyllables("constitutional"))
    }

    @Test
    fun countSyllables_silentE() {
        assertEquals(1, WordTimingPlanner.countSyllables("make"))
        assertEquals(1, WordTimingPlanner.countSyllables("code"))
        assertEquals(1, WordTimingPlanner.countSyllables("trade"))
        assertEquals(1, WordTimingPlanner.countSyllables("rule"))
        assertEquals(1, WordTimingPlanner.countSyllables("smile"))
    }

    @Test
    fun countSyllables_leSuffixKeepsCount() {
        assertEquals(2, WordTimingPlanner.countSyllables("table"))
        assertEquals(2, WordTimingPlanner.countSyllables("puzzle"))
        assertEquals(2, WordTimingPlanner.countSyllables("simple"))
    }

    @Test
    fun countSyllables_emptyOrPunctuationFloor() {
        assertEquals(1, WordTimingPlanner.countSyllables("."))
        assertEquals(1, WordTimingPlanner.countSyllables("!"))
        assertEquals(1, WordTimingPlanner.countSyllables(""))
        assertEquals(1, WordTimingPlanner.countSyllables("123"))
    }

    @Test
    fun countSyllables_consonantOnlyWordHitsFloor() {
        // "rhythms" — heuristic catches y-as-vowel, returns 1 (one group).
        assertEquals(1, WordTimingPlanner.countSyllables("rhythms"))
    }

    @Test
    fun countSyllables_accentedLatinTreatedAsLatin() {
        // "café" should be 2 (a, é). é is in the extended vowel set.
        assertEquals(2, WordTimingPlanner.countSyllables("café"))
    }

    @Test
    fun countSyllables_nonLatinReturnsSentinel() {
        assertEquals(-1, WordTimingPlanner.countSyllables("東京"))
        assertEquals(-1, WordTimingPlanner.countSyllables("こんにちは"))
        assertEquals(-1, WordTimingPlanner.countSyllables("مرحبا"))
    }

    // ---- weightForWord -------------------------------------------------------------

    @Test
    fun weightForWord_capsAtMax() {
        // "antidisestablishmentarianism" = 12 syllables in the heuristic; capped at 6.
        val w = WordTimingPlanner.weightForWord(
            "antidisestablishmentarianism", minWeight, maxWeight, nonLatinScale
        )
        assertEquals(maxWeight, w)
    }

    @Test
    fun weightForWord_singleLetterAtFloor() {
        val w = WordTimingPlanner.weightForWord("a", minWeight, maxWeight, nonLatinScale)
        assertEquals(minWeight, w)
    }

    @Test
    fun weightForWord_nonLatinUsesCharScale() {
        // 4 chars at scale 0.5 -> ceil(2.0) = 2.
        val w = WordTimingPlanner.weightForWord(
            "東京都市", minWeight, maxWeight, nonLatinScale
        )
        assertEquals(2, w)
    }

    @Test
    fun weightForWord_nonLatinCappedAtMax() {
        // 20 CJK chars at scale 0.5 -> 10, but capped at maxWeight (6).
        val w = WordTimingPlanner.weightForWord(
            "東京都市東京都市東京都市東京都市東京都市", minWeight, maxWeight, nonLatinScale
        )
        assertEquals(maxWeight, w)
    }

    // ---- plan ----------------------------------------------------------------------

    @Test
    fun plan_emptyUtterance() {
        val plan = WordTimingPlanner.plan(
            utterance = utterance("", emptyList(), emptyList()),
            totalFrames = 1000,
            minSyllablesPerWord = minWeight,
            maxSyllablesPerWord = maxWeight,
            nonLatinCharWeightScale = nonLatinScale
        )
        assertEquals(0, plan.wordCount)
    }

    @Test
    fun plan_zeroFrames() {
        val u = utterance("hello world", listOf(0..4, 6..10), listOf(0, 1))
        val plan = WordTimingPlanner.plan(u, 0, minWeight, maxWeight, nonLatinScale)
        assertEquals(0, plan.wordCount)
    }

    @Test
    fun plan_endFramesMonotonicNonDecreasing() {
        // "the constitutional convention demanded synchronization"
        val text = "the constitutional convention demanded synchronization"
        val ranges = listOf(0..2, 4..17, 19..28, 30..37, 39..53)
        val global = listOf(0, 1, 2, 3, 4)
        val plan = WordTimingPlanner.plan(
            utterance(text, ranges, global), 10_000, minWeight, maxWeight, nonLatinScale
        )
        for (i in 1 until plan.wordCount) {
            assertTrue(
                "endFrames[${i - 1}]=${plan.endFrames[i - 1]} > endFrames[$i]=${plan.endFrames[i]}",
                plan.endFrames[i] >= plan.endFrames[i - 1]
            )
        }
    }

    @Test
    fun plan_lastEndFrameEqualsTotalFrames() {
        // Pick weights and totalFrames where integer rounding would otherwise leave a gap.
        val u = utterance(
            "the synchronization",
            listOf(0..2, 4..18),
            listOf(0, 1)
        )
        val plan = WordTimingPlanner.plan(u, 1001, minWeight, maxWeight, nonLatinScale)
        assertEquals(1001, plan.endFrames.last())
        assertEquals(1001, plan.totalFrames)
    }

    @Test
    fun plan_shortWordGetsLessTimeThanLongWord() {
        val u = utterance(
            "the synchronization",
            listOf(0..2, 4..18),
            listOf(0, 1)
        )
        val plan = WordTimingPlanner.plan(u, 1000, minWeight, maxWeight, nonLatinScale)
        val shortDwell = plan.endFrames[0]
        val longDwell = plan.endFrames[1] - plan.endFrames[0]
        assertTrue(
            "short=$shortDwell long=$longDwell",
            longDwell > shortDwell
        )
    }

    @Test
    fun plan_uniformWeightsProduceUniformSpacing() {
        val u = utterance(
            "the the the",
            listOf(0..2, 4..6, 8..10),
            listOf(0, 1, 2)
        )
        val plan = WordTimingPlanner.plan(u, 300, minWeight, maxWeight, nonLatinScale)
        assertEquals(intArrayOf(100, 200, 300).toList(), plan.endFrames.toList())
    }

    @Test
    fun plan_singleWordCoversFullDuration() {
        val u = utterance("hello", listOf(0..4), listOf(0))
        val plan = WordTimingPlanner.plan(u, 5000, minWeight, maxWeight, nonLatinScale)
        assertEquals(intArrayOf(5000).toList(), plan.endFrames.toList())
    }

    // ---- helpers -------------------------------------------------------------------

    private fun utterance(
        text: String,
        wordRanges: List<IntRange>,
        globalWordIndices: List<Int>
    ): TtsUtterance = TtsUtterance(
        text = text,
        wordRanges = wordRanges,
        globalWordIndices = globalWordIndices,
        hasParagraphBreakAtEnd = false
    )
}

package com.speedread.rsvp.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRsvpEngineTest {

    // Single TestDispatcher drives every coroutine the engine touches:
    //   - Dispatchers.setMain(testDispatcher) routes the engine's
    //     Dispatchers.Main.immediate scope (engineScope, playback loop) onto it.
    //   - DefaultRsvpEngine(processingDispatcher = testDispatcher) routes the engine's
    //     CPU-bound text processing (loadText / loadTextWithProgress / updateSettings)
    //     onto it as well, instead of the production Dispatchers.Default.
    //   - runTest(testDispatcher) makes the test scope use the same TestScheduler.
    // Result: advanceUntilIdle() drains the entire engine pipeline on a single virtual
    // clock — no real-thread races, no polling-with-delay workarounds for fire-and-forget
    // launches like updateSettings's chunk-size reprocess.
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadTextPopulatesWordsAndResetsState() = runTest(testDispatcher) {
        val engine = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engine.loadText("one two three four")
        assertEquals(4, engine.getTotalWords())
        assertEquals(0, engine.getCurrentPosition())
        assertEquals(RsvpState.Idle, engine.observeState().first())
    }

    @Test
    fun updateSettingsPreservesRelativePositionAcrossChunkSizeChange() = runTest(testDispatcher) {
        // Regression guard for the identity-math bug: previously
        //   ((currentPosition / newSize) * newSize) == currentPosition,
        // which ignored the old size entirely and jumped to arbitrary places.
        val engine = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engine.loadText((1..100).joinToString(" ") { "w$it" })
        engine.seekToPosition(50) // halfway in single-word chunks

        engine.updateSettings(RsvpSettings(chunkSize = 5))
        advanceUntilIdle()

        // 100 tokens → 20 chunks at chunkSize=5. Relative 50/100 → 10/20.
        assertEquals(20, engine.getTotalWords())
        assertEquals(10, engine.getCurrentPosition())
    }

    @Test
    fun pauseReturnsAfterPlaybackJoined() = runTest(testDispatcher) {
        val engine = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engine.loadText("one two three four five")
        val playJob = launch { engine.play() }
        // runCurrent lets play() enter its playback loop and hit the first delay()
        // without draining the entire document — we want to test pausing mid-flight
        // (state=Playing), not after the loop naturally completes (state=Finished,
        // where pause() now correctly no-ops per the new Playing-only guard).
        runCurrent()
        assertEquals(RsvpState.Playing, engine.observeState().first())

        engine.pause()
        // After pause returns, state must be Paused and no further word emissions
        // should happen — i.e. the playback loop has been cancelled and joined.
        val stateAfterPause = engine.observeState().first()
        assertEquals(RsvpState.Paused, stateAfterPause)
        playJob.cancel()
    }

    @Test
    fun wasLastLoadTruncatedFalseForNormalInput() = runTest(testDispatcher) {
        val engine = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engine.loadText("tiny input")
        assertFalse(engine.wasLastLoadTruncated())
    }

    @Test
    fun wasLastLoadTruncatedTrueWhenInputExceedsMaxTokens() = runTest(testDispatcher) {
        val engine = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        val tokenCount = EngineConstants.MAX_TOKENS + 500
        val input = buildString {
            repeat(tokenCount) {
                append("w")
                append(' ')
            }
        }
        engine.loadText(input)
        assertTrue(engine.wasLastLoadTruncated())
        assertEquals(EngineConstants.MAX_TOKENS, engine.getTotalWords())
    }

    @Test
    fun seekEmitsWordWhenNotPlaying() = runTest(testDispatcher) {
        val engine = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engine.loadText("alpha bravo charlie delta")
        engine.seekToPosition(2)
        val emitted = engine.observeCurrentWord().first()
        assertEquals("charlie", emitted?.text)
    }

    @Test
    fun stopResetsPosition() = runTest(testDispatcher) {
        val engine = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engine.loadText("a b c d e")
        engine.seekToPosition(3)
        engine.stop()
        assertEquals(0, engine.getCurrentPosition())
        assertEquals(RsvpState.Idle, engine.observeState().first())
    }

    @Test
    fun pauseWhileIdleIsNoop() = runTest(testDispatcher) {
        // After loadText the state is Idle. Calling pause() must not transition to
        // Paused (which would imply a playback job exists to cancel).
        val engine = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engine.loadText("one two three")
        assertEquals(RsvpState.Idle, engine.observeState().first())
        engine.pause()
        assertEquals(RsvpState.Idle, engine.observeState().first())
    }

    @Test
    fun pauseWhileFinishedIsNoop() = runTest(testDispatcher) {
        val engine = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engine.loadText("alpha bravo charlie")
        engine.seekToPosition(2)
        val playJob = launch { engine.play() }
        advanceUntilIdle()
        assertEquals(RsvpState.Finished, engine.observeState().first())
        engine.pause()
        assertEquals(RsvpState.Finished, engine.observeState().first())
        playJob.cancel()
    }

    @Test
    fun loadTextWithProgressProducesSameWordsAsLoadText() = runTest(testDispatcher) {
        // Regression guard: the prior implementation sliced text into 5000-char
        // chunks and tokenized each independently, cutting words at boundaries.
        // Construct input so `longWord` straddles character index 5000 — any
        // return to char-based chunking at that size would split it and fail the
        // equality below. loadTextWithProgress must match loadText byte for byte.
        val longWord = "supercalifragilisticexpialidocious" // 34 chars
        // 4998 chars of filler so longWord begins at index 4999 — index 5000
        // lands inside the second character of longWord.
        val prePadding = "a".repeat(4998) + " "
        val postPadding = " " + "b".repeat(2000)
        val input = prePadding + longWord + postPadding

        val engineA = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engineA.loadText(input)
        val wordsFromLoadText = engineA.getWords().map { it.text }

        val engineB = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engineB.loadTextWithProgress(input) { /* discard */ }
        val wordsFromProgress = engineB.getWords().map { it.text }

        assertEquals(wordsFromLoadText, wordsFromProgress)
        assertTrue(
            "long word must survive intact across the pre-regression chunk boundary",
            wordsFromProgress.contains(longWord)
        )
    }

    @Test
    fun loadTextWithProgressTruncationMatchesLoadText() = runTest(testDispatcher) {
        val tokenCount = EngineConstants.MAX_TOKENS + 500
        val input = buildString {
            repeat(tokenCount) {
                append("w")
                append(' ')
            }
        }
        val engineA = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engineA.loadText(input)

        val engineB = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engineB.loadTextWithProgress(input) { /* discard */ }

        assertEquals(engineA.wasLastLoadTruncated(), engineB.wasLastLoadTruncated())
        assertEquals(engineA.getTotalWords(), engineB.getTotalWords())
    }

    @Test
    fun loadTextWithProgressEmitsProgressStages() = runTest(testDispatcher) {
        val engine = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        val emissions = mutableListOf<Float>()
        engine.loadTextWithProgress("alpha bravo charlie") { emissions.add(it) }
        assertTrue("must emit at least a done stage", emissions.contains(EngineConstants.PROGRESS_STAGE_DONE))
        assertEquals(
            "final emitted progress must be 1.0",
            EngineConstants.PROGRESS_STAGE_DONE,
            emissions.last()
        )
    }

    @Test
    fun playAfterFinishRestartsFromZero() = runTest(testDispatcher) {
        // Regression guard for Bug 1: tapping Play after the reader finished the
        // document used to be a silent no-op. play() must now reset currentPosition
        // so the loop has something to emit again.
        val engine = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engine.loadText("alpha bravo charlie")
        // Drive to Finished without waiting the full natural duration: seek to end
        // then call play(); the loop condition fails immediately and emits Finished.
        engine.seekToPosition(2)
        val playJob1 = launch { engine.play() }
        advanceUntilIdle()
        // currentPosition should have advanced past words.size and state=Finished.
        assertEquals(RsvpState.Finished, engine.observeState().first())
        assertEquals(3, engine.getCurrentPosition())

        val playJob2 = launch { engine.play() }
        // runCurrent lets play()'s body + the playback loop's pre-delay code run
        // without advancing past the first iteration's delay, so we observe the
        // reset state (pos=0, Playing) instead of racing to Finished again.
        runCurrent()
        assertEquals(RsvpState.Playing, engine.observeState().first())
        assertEquals(0, engine.getCurrentPosition())

        playJob1.cancel()
        playJob2.cancel()
    }

    @Test
    fun playbackAdvancesExactlyOneWordPerDelayPeriod() = runTest(testDispatcher) {
        // Cadence regression guard: every word must display for exactly one
        // calculateDelay period. A hang (word displayed for two or more periods)
        // means the loop's post-delay increment was skipped without a real seek.
        val engine = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engine.updateSettings(RsvpSettings(wpm = 250, enablePunctuationPausing = false))
        engine.loadText((1..20).joinToString(" ") { "w$it" })
        val playJob = launch { engine.play() }
        runCurrent()
        assertEquals(0, engine.getCurrentPosition())

        val delayMs = 60_000L / 250
        for (expected in 1..10) {
            advanceTimeBy(delayMs)
            runCurrent()
            assertEquals(
                "position after ${expected} delay periods",
                expected,
                engine.getCurrentPosition()
            )
        }
        playJob.cancel()
    }

    @Test
    fun redundantSeekToCurrentPositionDoesNotStallPlayback() = runTest(testDispatcher) {
        // A seek targeting the position already on screen must not suppress the
        // post-delay increment — otherwise any redundant sync-seek arriving during
        // the delay window re-displays the same word for a full extra period.
        val engine = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engine.updateSettings(RsvpSettings(wpm = 250, enablePunctuationPausing = false))
        engine.loadText((1..20).joinToString(" ") { "w$it" })
        val playJob = launch { engine.play() }
        runCurrent()

        val delayMs = 60_000L / 250
        advanceTimeBy(delayMs / 2)
        engine.seekToPosition(engine.getCurrentPosition())
        advanceTimeBy(delayMs - delayMs / 2)
        runCurrent()
        assertEquals(1, engine.getCurrentPosition())

        playJob.cancel()
    }

    @Test
    fun seekWhilePlayingDisplaysTargetWordForOneFullPeriod() = runTest(testDispatcher) {
        // The sought word must be displayed (not skipped past) and then playback
        // continues from target+1 after one period.
        val engine = DefaultRsvpEngine(processingDispatcher = testDispatcher)
        engine.updateSettings(RsvpSettings(wpm = 250, enablePunctuationPausing = false))
        engine.loadText((1..20).joinToString(" ") { "w$it" })
        val playJob = launch { engine.play() }
        runCurrent()

        val delayMs = 60_000L / 250
        advanceTimeBy(delayMs / 2)
        engine.seekToPosition(10)
        advanceTimeBy(delayMs - delayMs / 2)
        runCurrent()
        // Loop woke from word 0's delay; seek moved the cursor, so the loop shows
        // word 10 now instead of incrementing past it.
        assertEquals(10, engine.getCurrentPosition())
        assertEquals("w11", engine.observeCurrentWord().first()?.text)

        advanceTimeBy(delayMs)
        runCurrent()
        assertEquals(11, engine.getCurrentPosition())

        playJob.cancel()
    }
}

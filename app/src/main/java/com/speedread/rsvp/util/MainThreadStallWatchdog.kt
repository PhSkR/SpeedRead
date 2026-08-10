package com.speedread.rsvp.util

import android.os.Handler
import android.os.Looper
import com.speedread.rsvp.Constants
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug diagnostic: detects main-thread stalls and logs the main thread's stack trace at the
 * moment of detection, so bursty jank (words rendered late, frozen frames) can be attributed
 * to the exact blocking code instead of guessed at.
 *
 * Mechanism: a daemon thread posts a heartbeat Runnable to the main Looper, sleeps for
 * [Constants.STALL_WATCHDOG_THRESHOLD_MS], and if the heartbeat has not executed by then the
 * main thread is considered blocked — its stack is captured immediately (mid-stall, so the
 * culprit frame is on it), then the watchdog keeps polling until the heartbeat lands to
 * report the total blocked duration. Logged under the RsvpDiag tag alongside the word-hold
 * overshoot diagnostic in ReadingFragment.
 *
 * Start/stop from a screen's onStart/onStop so samples are scoped to the flow under
 * investigation. Idempotent; the sampling thread is a daemon and never outlives stop().
 */
class MainThreadStallWatchdog {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var watchdogThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        watchdogThread = Thread {
            val mainThread = Looper.getMainLooper().thread
            while (running) {
                val beat = AtomicBoolean(false)
                mainHandler.post { beat.set(true) }
                if (!sleepQuietly(Constants.STALL_WATCHDOG_THRESHOLD_MS)) return@Thread
                if (!beat.get() && running) {
                    // Main thread is blocked right now — capture the stack mid-stall.
                    val stack = mainThread.stackTrace
                    var blockedMs = Constants.STALL_WATCHDOG_THRESHOLD_MS
                    while (!beat.get() && running && blockedMs < Constants.STALL_WATCHDOG_MAX_WAIT_MS) {
                        if (!sleepQuietly(Constants.STALL_WATCHDOG_POLL_MS)) return@Thread
                        blockedMs += Constants.STALL_WATCHDOG_POLL_MS
                    }
                    val frames = stack
                        .take(Constants.STALL_WATCHDOG_STACK_FRAMES)
                        .joinToString(separator = "\n    ") { it.toString() }
                    Logger.w(
                        "RsvpDiag",
                        "main thread blocked ~${blockedMs}ms; stack at detection:\n    $frames"
                    )
                }
                if (!sleepQuietly(Constants.STALL_WATCHDOG_SAMPLE_INTERVAL_MS)) return@Thread
            }
        }.apply {
            name = "RsvpStallWatchdog"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    private fun sleepQuietly(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            true
        } catch (_: InterruptedException) {
            false
        }
    }
}

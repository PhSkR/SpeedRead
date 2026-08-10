package com.speedread.rsvp.tts

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [ContextCompat.startForegroundService] / [Context.stopService] for
 * [TtsPlaybackService]. Split out so [PlaybackCoordinator] does not need to see Android
 * framework types directly — keeps coordinator logic easy to unit-test on the JVM.
 *
 * Both operations are safe to call repeatedly:
 *   - startForegroundService on an already-started service is a no-op beyond redelivering
 *     the intent, which the service ignores (no-arg onStartCommand).
 *   - stopService on a non-running service returns false without side effects.
 */
@Singleton
class TtsServiceController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun startIfNeeded() {
        val intent = Intent(context, TtsPlaybackService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop() {
        context.stopService(Intent(context, TtsPlaybackService::class.java))
    }
}

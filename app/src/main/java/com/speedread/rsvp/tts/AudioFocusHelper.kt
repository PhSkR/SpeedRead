package com.speedread.rsvp.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps [AudioManager] focus handling for the TTS engines. Without requesting focus the
 * app would talk over Spotify, nav prompts, phone calls, etc. — instant one-star reviews.
 *
 * v1 behavior:
 *   - Request transient focus on play (AUDIOFOCUS_LOSS_TRANSIENT pauses the caller, but
 *     we also want to yield permanently on full LOSS).
 *   - Report all loss events to [onFocusLoss] (the caller decides whether to pause or stop).
 *   - v1 does NOT auto-resume on GAIN — the user must press play again. Matches the "v1 is
 *     foreground-only" scope; auto-resume ships with the v2 media-session integration.
 *
 * minSdk 26 simplifies this: [AudioFocusRequest] is always available.
 */
@Singleton
class AudioFocusHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var currentRequest: AudioFocusRequest? = null
    private var currentListener: ((FocusLoss) -> Unit)? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        val listener = currentListener ?: return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener(FocusLoss.Transient)
            AudioManager.AUDIOFOCUS_LOSS -> listener(FocusLoss.Permanent)
            // GAIN intentionally unhandled in v1 — caller does not auto-resume.
            else -> Unit
        }
    }

    /**
     * Request audio focus for speech playback. Returns true if the request was granted.
     * [onLoss] is invoked on the main thread when focus is later lost.
     */
    fun request(onLoss: (FocusLoss) -> Unit): Boolean {
        // Release any prior grant so we don't leak listeners across sessions.
        release()

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .build()

        currentListener = onLoss
        currentRequest = req

        val result = audioManager.requestAudioFocus(req)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            Logger.w(TAG, "Audio focus request denied (result=$result)")
            currentRequest = null
            currentListener = null
        }
        return granted
    }

    /** Release the current focus grant. Safe to call when no grant is active. */
    fun release() {
        val req = currentRequest ?: return
        audioManager.abandonAudioFocusRequest(req)
        currentRequest = null
        currentListener = null
    }

    enum class FocusLoss { Transient, Permanent }

    companion object {
        private const val TAG = "AudioFocusHelper"
    }
}

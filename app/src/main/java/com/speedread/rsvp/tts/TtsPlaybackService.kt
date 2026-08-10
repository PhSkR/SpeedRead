package com.speedread.rsvp.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.media.session.MediaButtonReceiver
import com.speedread.rsvp.Constants
import com.speedread.rsvp.MainActivity
import com.speedread.rsvp.R
import com.speedread.rsvp.engine.RsvpState
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps TTS playback alive while the app is backgrounded or the screen
 * is off. Does not drive playback itself — the existing [PlaybackCoordinator] + [NativeTtsEngine]
 * remain the single source of truth. This service only:
 *   1. Holds process-lifetime long enough for TTS to survive backgrounding (foreground service).
 *   2. Holds a PARTIAL_WAKE_LOCK while Playing so doze can't suspend the native TTS service.
 *   3. Projects coordinator state into a [MediaSessionCompat] + [NotificationCompat] so
 *      lock-screen / BT headset / notification-shade controls stay in sync.
 *   4. Routes transport callbacks (play / pause / stop / skip) back into the coordinator.
 *
 * Lifecycle:
 *   - Started by [TtsServiceController.startIfNeeded] when [PlaybackCoordinator.play] fires in
 *     TTS mode. Must call [startForeground] within 5s of creation, so [onStartCommand] does so
 *     unconditionally with a placeholder notification that the state observer immediately
 *     updates to the real one.
 *   - Stops itself when coordinator state transitions to Finished, Idle-via-stop, or Error, or
 *     when [TtsServiceController.stop] is called on mode switch. Paused state keeps the service
 *     alive so the notification offers a Play action without requiring app foreground.
 */
@AndroidEntryPoint
class TtsPlaybackService : android.app.Service() {

    @Inject lateinit var coordinator: PlaybackCoordinator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var observerJob: Job? = null
    // Tracks whether we've already called startForeground() this lifecycle, so state updates
    // use notify() rather than re-entering foreground (cheaper; also avoids flicker).
    private var isInForeground: Boolean = false
    // Live word index captured from the coordinator's flow — authoritative during TTS playback
    // (coordinator.getCurrentPosition() proxies to RsvpEngine, which is stale while TTS speaks,
    // so skip-to-next / skip-to-previous must consult this instead). Updated per word from a
    // dedicated unsampled collector so skip math always uses the freshest position.
    @Volatile private var liveWordPosition: Int = 0

    // Latest state / title observed by the dedicated state collector. Cached here so the
    // throttled position collector can render its notification with the current state without
    // having to re-combine with the state flow per tick.
    @Volatile private var lastObservedState: RsvpState = RsvpState.Idle
    @Volatile private var lastObservedTitle: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession() // Initialize session immediately first so buildNotification() can read sessionToken
        
        // Start foreground immediately in onCreate() to prevent ForegroundServiceDidNotStartInTimeException.
        // On modern Android versions, calling startForeground inside onCreate() is the only way to guarantee
        // it runs before any main thread message queue delays or blocking initializers (such as MediaSessionCompat)
        // trigger the system's 5-second watchdog timeout.
        val notification = buildNotification(
            stateLabel = STATE_LABEL_STARTING,
            isPlaying = true,
            title = null,
            subtitle = null
        )
        startForegroundCompat(notification)
        
        initWakeLock()
        observeCoordinator()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Hardware key events (BT / wired headphone play-pause button) are delivered here as
        // ACTION_MEDIA_BUTTON intents routed through the manifest-declared MediaButtonReceiver.
        // Hand them to MediaSessionCompat which dispatches to the callback below.
        MediaButtonReceiver.handleIntent(session, intent)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isInForeground = false
        observerJob?.cancel()
        observerJob = null
        releaseWakeLock()
        session.isActive = false
        session.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // --- init helpers ---

    private fun createNotificationChannel() {
        val manager = getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(Constants.TTS_NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            Constants.TTS_NOTIFICATION_CHANNEL_ID,
            Constants.TTS_NOTIFICATION_CHANNEL_NAME,
            // Media notifications should not buzz / beep. LOW keeps them visible + interactive
            // on the lock screen without any sound or heads-up behaviour.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = Constants.TTS_NOTIFICATION_CHANNEL_DESC
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun initMediaSession() {
        session = MediaSessionCompat(this, Constants.TTS_MEDIA_SESSION_TAG).apply {
            setCallback(sessionCallback)
            isActive = true
            // Seed with an empty state so controllers that bind before the first flow emission
            // see a coherent (if minimal) session.
            setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_CONNECTING, 0))
        }
    }

    private fun initWakeLock() {
        val pm = getSystemService<PowerManager>() ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Constants.TTS_WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }
    }

    // --- observers ---

    @OptIn(FlowPreview::class) // Flow.sample() — see collector C below for why throttling is required.
    private fun observeCoordinator() {
        // Three coordinated collectors instead of one combined flow, because per-word
        // notification spam was tripping NotificationManager's ~5 updates/sec rate limit
        // (logcat: "Shedding notify (update) ... rate limit (5.0) exceeded"). Over-rate
        // updates are silently dropped, so the visible notification subtitle would stall
        // mid-playback at higher TTS rates while the underlying state continued advancing.
        //
        //   A. liveWordPosition — every word, no notify. Skip-next / skip-previous on the
        //      lock screen / BT headset use this for the +-10 word jump basis, so it must
        //      stay tight to the actual playback position regardless of notification cadence.
        //
        //   B. state + title — rare transitions (Playing/Paused/Finished, plus title swaps
        //      on document load). Always emit the notification immediately so transport state
        //      reflects the engine without delay.
        //
        //   C. position (sampled) — per-word inside the engine but throttled to
        //      TTS_NOTIFICATION_THROTTLE_MS so we stay well under the 5/sec ceiling. The
        //      MediaSession's PlaybackStateCompat carries STATE_PLAYING + speed * time so
        //      lock-screen scrub bars interpolate between updates; a 4Hz cadence is plenty
        //      for visible smoothness of the "{n} / {total}" word counter.
        observerJob = serviceScope.launch {
            launch {
                coordinator.currentWord.collect { liveWordPosition = it?.absoluteStartIndex ?: 0 }
            }
            launch {
                combine(coordinator.state, coordinator.currentTitle) { s, t -> s to t }
                    .distinctUntilChanged()
                    .collect { (state, title) ->
                        lastObservedState = state
                        lastObservedTitle = title
                        onStateTick(state, title, liveWordPosition)
                    }
            }
            launch {
                coordinator.currentWord
                    .map { it?.absoluteStartIndex ?: 0 }
                    .sample(Constants.TTS_NOTIFICATION_THROTTLE_MS)
                    .distinctUntilChanged { oldPos, newPos ->
                        val (oldPage, _) = coordinator.getPageInfoForPosition(oldPos)
                        val (newPage, _) = coordinator.getPageInfoForPosition(newPos)
                        oldPage == newPage
                    }
                    .collect { position ->
                        onStateTick(lastObservedState, lastObservedTitle, position)
                    }
            }
        }
    }

    private fun onStateTick(state: RsvpState, title: String?, position: Int) {
        val subtitle = if (coordinator.getAbsoluteWordCount() > 0) {
            val (currentPage, totalPages) = coordinator.getPageInfoForPosition(position)
            getString(R.string.tts_notification_page_progress, currentPage, totalPages)
        } else null

        when (state) {
            RsvpState.Playing -> {
                acquireWakeLock()
                session.setPlaybackState(
                    buildPlaybackState(PlaybackStateCompat.STATE_PLAYING, position)
                )
                session.setMetadata(buildMetadata(title, subtitle))
                updateNotification(
                    stateLabel = STATE_LABEL_PLAYING,
                    isPlaying = true,
                    title = title,
                    subtitle = subtitle
                )
            }
            RsvpState.Paused -> {
                releaseWakeLock()
                session.setPlaybackState(
                    buildPlaybackState(PlaybackStateCompat.STATE_PAUSED, position)
                )
                session.setMetadata(buildMetadata(title, subtitle))
                updateNotification(
                    stateLabel = STATE_LABEL_PAUSED,
                    isPlaying = false,
                    title = title,
                    subtitle = subtitle
                )
            }
            RsvpState.Finished -> {
                releaseWakeLock()
                session.setPlaybackState(
                    buildPlaybackState(PlaybackStateCompat.STATE_STOPPED, position)
                )
                stopSelfClean()
            }
            RsvpState.Idle -> {
                // Idle can be reached two ways: (a) transient state during init before play()
                // has flipped us to Playing, or (b) an explicit stop()/error. We can't tell
                // them apart from the state alone, so keep the service alive with the paused
                // notification. coordinator.stop() is followed by TtsServiceController.stop()
                // in the coordinator, which will destroy us through the Android lifecycle.
                releaseWakeLock()
                session.setPlaybackState(
                    buildPlaybackState(PlaybackStateCompat.STATE_NONE, position)
                )
                updateNotification(
                    stateLabel = STATE_LABEL_PAUSED,
                    isPlaying = false,
                    title = title,
                    subtitle = subtitle
                )
            }
        }
    }

    // --- media session callback ---

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            serviceScope.launch {
                try { coordinator.play() } catch (e: Exception) {
                    Logger.e(TAG, "onPlay failed", e)
                }
            }
        }

        override fun onPause() {
            serviceScope.launch {
                try { coordinator.pause() } catch (e: Exception) {
                    Logger.e(TAG, "onPause failed", e)
                }
            }
        }

        override fun onStop() {
            serviceScope.launch {
                try {
                    coordinator.stop()
                } catch (e: Exception) {
                    Logger.e(TAG, "onStop failed", e)
                } finally {
                    stopSelfClean()
                }
            }
        }

        override fun onSkipToNext() {
            val target = (liveWordPosition + Constants.TTS_SKIP_STEP_WORDS)
                .coerceAtMost((coordinator.getAbsoluteWordCount() - 1).coerceAtLeast(0))
            serviceScope.launch {
                try { coordinator.seekToAbsoluteWordPosition(target) } catch (e: Exception) {
                    Logger.e(TAG, "onSkipToNext failed", e)
                }
            }
        }

        override fun onSkipToPrevious() {
            val target = (liveWordPosition - Constants.TTS_SKIP_STEP_WORDS)
                .coerceAtLeast(0)
            serviceScope.launch {
                try { coordinator.seekToAbsoluteWordPosition(target) } catch (e: Exception) {
                    Logger.e(TAG, "onSkipToPrevious failed", e)
                }
            }
        }
    }

    // --- building blocks ---

    private fun buildPlaybackState(state: Int, positionWords: Int): PlaybackStateCompat {
        // Position is reported in "ms" so lock-screen scrub bars can render something — we
        // repurpose it as (word index * 1000) so UI ordering is monotonic. Total duration is
        // unknown (TTS rate varies), so we leave the bar uncapped.
        val actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        return PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, (positionWords * 1000L), 1.0f)
            .build()
    }

    private fun buildMetadata(title: String?, subtitle: String?): MediaMetadataCompat {
        return MediaMetadataCompat.Builder()
            .putString(
                MediaMetadataCompat.METADATA_KEY_TITLE,
                title ?: getString(R.string.tts_notification_default_title)
            )
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle ?: "")
            .build()
    }

    private fun buildNotification(
        stateLabel: Int,
        isPlaying: Boolean,
        title: String?,
        subtitle: String?
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_pause,
                getString(R.string.tts_action_pause),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play_arrow,
                getString(R.string.tts_action_play),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
            )
        }
        val stopAction = NotificationCompat.Action(
            R.drawable.ic_stop,
            getString(R.string.tts_action_stop),
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this, PlaybackStateCompat.ACTION_STOP
            )
        )

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0, 1)

        val displayTitle = title ?: getString(R.string.tts_notification_default_title)
        val displaySub = subtitle ?: getString(stateLabel)

        return NotificationCompat.Builder(this, Constants.TTS_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(displayTitle)
            .setContentText(displaySub)
            .setContentIntent(contentIntent)
            .setDeleteIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_STOP
                )
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(mediaStyle)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Constants.TTS_FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(Constants.TTS_FOREGROUND_NOTIFICATION_ID, notification)
        }
        isInForeground = true
    }

    private fun updateNotification(
        stateLabel: Int,
        isPlaying: Boolean,
        title: String?,
        subtitle: String?
    ) {
        val notification = buildNotification(stateLabel, isPlaying, title, subtitle)
        if (!isInForeground) {
            startForegroundCompat(notification)
            return
        }
        getSystemService<NotificationManager>()
            ?.notify(Constants.TTS_FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) {
            // No timeout — we release it deterministically on Paused/Finished/Destroy. A
            // timed acquire would silently expire mid-sentence on long documents.
            lock.acquire()
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            try { lock.release() } catch (e: Exception) {
                Logger.w(TAG, "Wake lock release failed: ${e.message}")
            }
        }
    }

    private fun stopSelfClean() {
        isInForeground = false
        observerJob?.cancel()
        observerJob = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    companion object {
        private const val TAG = "TtsPlaybackService"
        private val STATE_LABEL_STARTING = R.string.tts_notification_state_starting
        private val STATE_LABEL_PLAYING = R.string.tts_notification_state_playing
        private val STATE_LABEL_PAUSED = R.string.tts_notification_state_paused
    }
}

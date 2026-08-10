package com.speedread.rsvp

import android.app.Application
import com.speedread.rsvp.tts.TtsModelRegistry
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SpeedReadApplication : Application() {

    // Hilt field injection works on @HiltAndroidApp subclasses — fields are available
    // after super.onCreate() has run.
    @Inject lateinit var ttsModelRegistry: TtsModelRegistry

    override fun onCreate() {
        super.onCreate()
        // Theme will be initialized in MainActivity since it needs Hilt injection

        // Bootstrap the drop-in Neural voice directory off the main thread. Creates
        // /Android/data/com.speedread.rsvp/files/tts_models/ + README.txt on first install
        // and scans for any voices already dropped in before the user first opens Options.
        ttsModelRegistry.ensureInitialized()
        ttsModelRegistry.rescan()
    }
}
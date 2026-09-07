package com.speedread.rsvp.tts

import com.speedread.rsvp.RsvpSettingsManager
import com.speedread.rsvp.data.settings.TtsSettingsRepository
import com.speedread.rsvp.engine.RsvpEngine
import com.speedread.rsvp.engine.RsvpWord
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var rsvpEngine: RsvpEngine
    private lateinit var engineProvider: TtsEngineProvider
    private lateinit var ttsSettings: TtsSettingsRepository
    private lateinit var serviceController: TtsServiceController
    private lateinit var rsvpSettingsManager: RsvpSettingsManager
    private lateinit var coordinator: PlaybackCoordinator

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        rsvpEngine = mockk<RsvpEngine>(relaxed = true)
        every { rsvpEngine.observeState() } returns MutableStateFlow(com.speedread.rsvp.engine.RsvpState.Idle)
        every { rsvpEngine.observeCurrentWord() } returns MutableStateFlow(null)
        every { rsvpEngine.observeProgress() } returns MutableStateFlow(0f)

        val ttsEngine = mockk<TtsEngine>(relaxed = true)
        every { ttsEngine.observeState() } returns MutableStateFlow(TtsState.Idle)
        every { ttsEngine.observeCurrentWord() } returns MutableStateFlow(null)
        every { ttsEngine.observeProgress() } returns MutableStateFlow(0f)

        engineProvider = mockk<TtsEngineProvider>(relaxed = true)
        every { engineProvider.resolve(any()) } returns ttsEngine

        ttsSettings = mockk<TtsSettingsRepository>(relaxed = true)
        every { ttsSettings.settings } returns MutableStateFlow(TtsSettings())

        serviceController = mockk<TtsServiceController>(relaxed = true)
        rsvpSettingsManager = mockk<RsvpSettingsManager>(relaxed = true)
        every { rsvpSettingsManager.getLastPlaybackMode() } returns PlaybackMode.RSVP

        coordinator = PlaybackCoordinator(
            rsvpEngine = rsvpEngine,
            engineProvider = engineProvider,
            ttsSettings = ttsSettings,
            serviceController = serviceController,
            rsvpSettingsManager = rsvpSettingsManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadText_storesDocumentIdAndContentHash() = runTest {
        coordinator.loadText("sample text", 42L, "hash123")
        assertEquals(42L, coordinator.getCurrentDocumentId())
        assertEquals("hash123", coordinator.getCurrentContentHash())
    }

    @Test
    fun loadText_defaultArgs_clearsDocumentIdAndContentHash() = runTest {
        coordinator.loadText("sample text", 42L, "hash123")
        coordinator.loadText("new text")
        assertNull(coordinator.getCurrentDocumentId())
        assertNull(coordinator.getCurrentContentHash())
    }

    @Test
    fun loadPreTokenized_storesDocumentIdAndContentHash() = runTest {
        val tokens = listOf(RsvpWord("word", 0))
        coordinator.loadPreTokenized(tokens, 99L, "hash456")
        assertEquals(99L, coordinator.getCurrentDocumentId())
        assertEquals("hash456", coordinator.getCurrentContentHash())
    }

    @Test
    fun loadTextWithProgress_storesDocumentIdAndContentHash() = runTest {
        coordinator.loadTextWithProgress("test text", 101L, "hash789") {}
        assertEquals(101L, coordinator.getCurrentDocumentId())
        assertEquals("hash789", coordinator.getCurrentContentHash())
    }
}

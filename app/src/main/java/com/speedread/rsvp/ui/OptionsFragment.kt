package com.speedread.rsvp.ui

import android.content.Context
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.speedread.rsvp.OptionsViewModel
import com.speedread.rsvp.R
import com.speedread.rsvp.RsvpSettingsManager
import com.speedread.rsvp.ThemeManager
import com.speedread.rsvp.TimingSettingsManager
import com.speedread.rsvp.databinding.FragmentOptionsBinding
import com.speedread.rsvp.engine.LandscapePlayButtonCorner
import com.speedread.rsvp.engine.PageViewScrollMode
import com.speedread.rsvp.engine.PunctuationTiming
import com.speedread.rsvp.engine.ThemeMode
import com.speedread.rsvp.engine.ThemeSettings
import com.speedread.rsvp.engine.ThemeStyle
import com.speedread.rsvp.tts.NeuralVoice
import com.speedread.rsvp.tts.TtsBackend
import com.speedread.rsvp.tts.TtsVoice
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class ColorPreset(
    val name: String,
    val textColor: Int,
    val backgroundColor: Int
)

@AndroidEntryPoint
class OptionsFragment : Fragment() {

    private var _binding: FragmentOptionsBinding? = null
    private val binding get() = _binding!!

    private val optionsViewModel: OptionsViewModel by activityViewModels()
    private var isUpdatingSettingsSlidersProgram = false
    // Re-entrancy guard for the corner-toggle group: programmatic check/uncheck calls made
    // inside updateCornerToggleSelection fire the same listener that writes to the VM, so we
    // suppress the write-back while syncing the UI to the observed set.
    private var isUpdatingCornerToggleProgram = false
    // Re-entrancy guard for the TTS controls (backend/voice dropdowns + rate/pitch sliders).
    // Same pattern as the RSVP sliders — suppress VM writes while syncing observed state.
    private var isUpdatingTtsSettingsProgram = false
    // Caches so the voice dropdown's onItemClick can look up the picked model/voice by index
    // without an O(n) string compare.
    private var currentSystemVoices: List<TtsVoice> = emptyList()
    private var currentNeuralVoices: List<NeuralVoice> = emptyList()
    // Parallel cache for the language dropdown — index 0 is always the "All" sentinel
    // (localeTag = null), positions 1..N hold the localeTags shown in the dropdown.
    private var currentLanguageLocaleTags: List<String?> = listOf(null)

    @Inject
    lateinit var themeManager: ThemeManager

    private lateinit var timingSettingsManager: TimingSettingsManager
    private var currentPunctuationTiming = PunctuationTiming.default()
    private var currentThemeSettings = ThemeSettings.default()
    private var isInitializingTheme = false
    private var currentFontSize = 32f

    private val colorPresets by lazy { PageViewColorPresets.build(requireContext()) }
    private val orpColorPresets by lazy { OrpColorPresets.build(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        timingSettingsManager = TimingSettingsManager()
        initializeSettings()
        setupUserManualButton()
        setupOptionsTab()
        setupPageViewAppearanceControls()
        setupTtsControls()
        observeViewModel()
        observeTtsViewModel()
    }

    /**
     * Wires the top-of-screen User Manual button. The manual is rendered from a single
     * HTML string resource (`R.string.user_manual_content_html`) into a scrollable
     * TextView so that future feature additions only need to touch strings.xml — no
     * per-section view inflation, no Markdown library dependency. The dialog is created
     * fresh each time the button is tapped (cheap; the layout is a single TextView in a
     * NestedScrollView) so we don't have to manage its lifecycle across Settings-tab
     * navigations.
     */
    private fun setupUserManualButton() {
        binding.userManualButton.setOnClickListener { showUserManualDialog() }
    }

    private fun showUserManualDialog() {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_user_manual, null, false)
        val body = view.findViewById<TextView>(R.id.userManualBody)
        // FROM_HTML_MODE_LEGACY preserves visible blank lines between <p> blocks; COMPACT
        // collapses them and the manual reads as a wall of text. Movement method enables
        // any future <a href> links inside the manual to be tappable without extra wiring.
        body.text = Html.fromHtml(
            getString(R.string.user_manual_content_html),
            Html.FROM_HTML_MODE_LEGACY
        )
        body.movementMethod = LinkMovementMethod.getInstance()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.user_manual_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.user_manual_close_button, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Pick up any Neural voice folders the user dropped in while Options was backgrounded.
        optionsViewModel.rescanNeuralVoices()
        // Force System TTS to bind so its installed-voices list populates the dropdown
        // immediately on screen entry. Without this the System voices only appear after the
        // user's first play(), so the picker reads as empty for first-time visitors. Safe to
        // call repeatedly — the engine no-ops once initialised.
        optionsViewModel.prewarmTts()
    }

    private fun initializeSettings() {
        // Initialize theme early to ensure proper theming
        themeManager.initializeTheme()
        currentThemeSettings = themeManager.getCurrentThemeSettings()

        // Initialize font size
        currentFontSize = themeManager.getCurrentFontSize()

        // Initialize punctuation timing - this will be loaded by ViewModel from RsvpSettingsManager
        // but we keep a local copy for UI updates
        collectOnView(optionsViewModel.settings) { settings ->
            currentPunctuationTiming = settings.punctuationTiming
            updateTimingSliders(settings.punctuationTiming)
        }
    }

    private fun cornerFromButtonId(buttonId: Int): LandscapePlayButtonCorner? = when (buttonId) {
        R.id.cornerTopStartButton -> LandscapePlayButtonCorner.TOP_START
        R.id.cornerTopEndButton -> LandscapePlayButtonCorner.TOP_END
        R.id.cornerBottomStartButton -> LandscapePlayButtonCorner.BOTTOM_START
        R.id.cornerBottomEndButton -> LandscapePlayButtonCorner.BOTTOM_END
        else -> null
    }

    private fun buttonIdForCorner(corner: LandscapePlayButtonCorner): Int = when (corner) {
        LandscapePlayButtonCorner.TOP_START -> R.id.cornerTopStartButton
        LandscapePlayButtonCorner.TOP_END -> R.id.cornerTopEndButton
        LandscapePlayButtonCorner.BOTTOM_START -> R.id.cornerBottomStartButton
        LandscapePlayButtonCorner.BOTTOM_END -> R.id.cornerBottomEndButton
    }

    // Sync the multi-select toggle group to match the observed set. We compute a diff against
    // the group's current checkedButtonIds and only issue check/uncheck for changed buttons —
    // each such call fires the listener, so the isUpdatingCornerToggleProgram flag suppresses
    // the write-back path while we reconcile.
    private fun updateCornerToggleSelection(corners: Set<LandscapePlayButtonCorner>) {
        val group = binding.landscapePlayButtonCornerToggleGroup
        val targetIds = corners.mapTo(mutableSetOf()) { buttonIdForCorner(it) }
        val currentIds = group.checkedButtonIds.toSet()
        if (currentIds == targetIds) return
        isUpdatingCornerToggleProgram = true
        for (id in targetIds - currentIds) group.check(id)
        for (id in currentIds - targetIds) group.uncheck(id)
        isUpdatingCornerToggleProgram = false
    }

    private fun observeViewModel() {
        collectOnView(optionsViewModel.wpm) { wpm ->
            if (!isUpdatingSettingsSlidersProgram) {
                binding.baseWpmSlider.setValueClamped(wpm.toFloat())
                binding.baseWpmValue.text = "$wpm WPM"
            }
        }

        collectOnView(optionsViewModel.settings) { settings ->
            if (!isUpdatingSettingsSlidersProgram) {
                binding.chunkSizeSlider.setValueClamped(settings.chunkSize.toFloat())
                binding.chunkSizeValue.text = "${settings.chunkSize} word${if (settings.chunkSize > 1) "s" else ""}"
                binding.orpToggleSwitch.isChecked = settings.enableOrp
                binding.centerOrpToggleSwitch.isChecked = settings.centerOrp
                binding.screenDimmingToggleSwitch.isChecked = settings.enableScreenDimming
                binding.punctuationPausingToggleSwitch.isChecked = settings.enablePunctuationPausing
                binding.paragraphSpacingToggleSwitch.isChecked = settings.enableParagraphSpacing
                updateCornerToggleSelection(settings.landscapePlayButtonCorners)
                binding.scrubSensitivitySlider.setValueClamped(settings.scrubPixelsPerWord)
                binding.scrubSensitivityValue.text =
                    getString(R.string.scrub_sensitivity_value, settings.scrubPixelsPerWord.toInt())
                updateColorPresetSelection(settings.textColor, settings.backgroundColor, colorPresets)

                binding.wordLengthTimingToggleSwitch.isChecked = settings.enableWordLengthTiming
                binding.wordLengthTimingControls.visibility =
                    if (settings.enableWordLengthTiming) View.VISIBLE else View.GONE
                binding.wordLengthBaselineSlider.setValueClamped(settings.wordLengthTiming.baseline.toFloat())
                binding.wordLengthBaselineValue.text =
                    getString(R.string.word_length_baseline_value, settings.wordLengthTiming.baseline)
                binding.wordLengthScalingSlider.setValueClamped(settings.wordLengthTiming.scalingPercent.toFloat())
                binding.wordLengthScalingValue.text =
                    getString(R.string.word_length_scaling_value, settings.wordLengthTiming.scalingPercent)
            }
        }
    }

    private fun setupTtsControls() {
        // Backend dropdown: System / Neural. Options are seeded here so the dropdown is
        // clickable even before the first observer emission fires.
        val backendLabels = arrayOf(
            getString(R.string.tts_backend_system),
            getString(R.string.tts_backend_neural)
        )
        binding.ttsBackendDropdown.setAdapter(NoFilterArrayAdapter(requireContext(), backendLabels))
        binding.ttsBackendDropdown.setOnItemClickListener { _, _, position, _ ->
            if (isUpdatingTtsSettingsProgram) return@setOnItemClickListener
            val backend = if (position == 0) TtsBackend.SYSTEM else TtsBackend.NEURAL
            optionsViewModel.updateTtsBackend(backend)
        }

        binding.ttsVoiceDropdown.setOnItemClickListener { _, _, position, _ ->
            if (isUpdatingTtsSettingsProgram) return@setOnItemClickListener
            val currentBackend = optionsViewModel.ttsSettings.value.backend
            val voiceId: String? = when (currentBackend) {
                TtsBackend.SYSTEM -> visibleSystemVoices().getOrNull(position - 1)?.id
                TtsBackend.NEURAL -> currentNeuralVoices.getOrNull(position - 1)?.id
            }
            // position 0 is the "Default" sentinel; voice list occupies positions 1..N.
            optionsViewModel.updateTtsVoiceId(voiceId)
        }

        binding.ttsLanguageDropdown.setOnItemClickListener { _, _, position, _ ->
            if (isUpdatingTtsSettingsProgram) return@setOnItemClickListener
            // position 0 is "All languages" (null filter); positions 1..N map to
            // currentLanguageLocaleTags entries.
            val localeTag = currentLanguageLocaleTags.getOrNull(position)
            optionsViewModel.updateSystemLanguageFilter(localeTag)
        }

        binding.ttsRateSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) optionsViewModel.updateTtsSpeechRate(value)
        }

        binding.ttsPitchSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) optionsViewModel.updateTtsPitch(value)
        }

        binding.ttsBrowseCatalogButton.setOnClickListener {
            findNavController().navigate(R.id.action_options_to_voiceCatalog)
        }
    }

    private fun observeTtsViewModel() {
        // Drive the TTS card UI off the (settings, systemVoices, neuralVoices) triple so every
        // observed change re-renders the visible voice list against the currently-selected
        // backend. combine fires on the latest of each flow and dedupes trivially.
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                optionsViewModel.ttsSettings,
                optionsViewModel.systemTtsVoices,
                optionsViewModel.neuralVoices
            ) { settings, systemVoices, neuralVoices ->
                Triple(settings, systemVoices, neuralVoices)
            }.collect { (settings, systemVoices, neuralVoices) ->
                currentSystemVoices = systemVoices
                currentNeuralVoices = neuralVoices
                renderTtsCard(settings, systemVoices, neuralVoices)
            }
        }

        // Disable the chunk-size slider while TTS is driving playback: chunk size is a
        // visual-reader concept and changing it mid-TTS would invalidate enqueued utterances
        // (DefaultRsvpEngine.updateSettings reprocesses the word list on chunkSize change).
        collectOnView(optionsViewModel.activeMode) { mode ->
            val isRsvp = mode == com.speedread.rsvp.tts.PlaybackMode.RSVP
            binding.chunkSizeSlider.isEnabled = isRsvp
            binding.chunkSizeSlider.alpha = if (isRsvp) 1.0f else 0.5f
        }
    }

    private fun renderTtsCard(
        settings: com.speedread.rsvp.tts.TtsSettings,
        systemVoices: List<TtsVoice>,
        neuralVoices: List<NeuralVoice>
    ) {
        isUpdatingTtsSettingsProgram = true
        try {
            // Backend label
            val backendLabel = when (settings.backend) {
                TtsBackend.SYSTEM -> getString(R.string.tts_backend_system)
                TtsBackend.NEURAL -> getString(R.string.tts_backend_neural)
            }
            binding.ttsBackendDropdown.setText(backendLabel, false)

            // Language dropdown (System backend only). Show every distinct localeTag the
            // device exposes plus an "All" sentinel at position 0; sort by display name in
            // the device's locale. effectiveLanguageFilter coerces a stale saved filter (one
            // that no longer matches any installed voice) back to "All" — better UX than
            // surfacing an empty voice list.
            val isSystem = settings.backend == TtsBackend.SYSTEM
            val isNeural = settings.backend == TtsBackend.NEURAL
            val savedFilter = settings.systemLanguageFilter
            val effectiveLanguageFilter = if (savedFilter != null &&
                systemVoices.any { it.localeTag == savedFilter }) savedFilter else null
            val sortedLocaleTags = systemVoices
                .mapNotNull { it.localeTag.takeIf { tag -> tag.isNotBlank() } }
                .distinct()
                .sortedBy { tag -> displayLanguageName(tag).lowercase() }
            val allLabel = getString(R.string.tts_language_all)
            currentLanguageLocaleTags = listOf<String?>(null) + sortedLocaleTags
            val languageLabels = arrayOf(allLabel) +
                sortedLocaleTags.map { displayLanguageName(it) }.toTypedArray()
            val currentLanguageLabel = if (effectiveLanguageFilter == null) allLabel
                else displayLanguageName(effectiveLanguageFilter)
            binding.ttsLanguageDropdown.setAdapter(
                NoFilterArrayAdapter(requireContext(), languageLabels)
            )
            binding.ttsLanguageDropdown.setText(currentLanguageLabel, false)
            binding.ttsLanguageDropdownLayout.visibility = if (isSystem) View.VISIBLE else View.GONE

            // Voice list (backend-dependent). Position 0 is always "Default"; voices fill 1..N.
            // System voices are filtered by the language picker so the user doesn't scroll
            // through the device's full TTS catalog (often 100+ regional voices).
            val defaultLabel = getString(R.string.tts_voice_default)
            val visibleSystem = if (effectiveLanguageFilter == null) systemVoices
                else systemVoices.filter { it.localeTag == effectiveLanguageFilter }
            val voiceLabels: Array<String>
            val currentVoiceLabel: String
            when (settings.backend) {
                TtsBackend.SYSTEM -> {
                    voiceLabels = arrayOf(defaultLabel) + visibleSystem.map { it.displayName }
                    currentVoiceLabel = visibleSystem.firstOrNull { it.id == settings.voiceId }
                        ?.displayName ?: defaultLabel
                }
                TtsBackend.NEURAL -> {
                    voiceLabels = arrayOf(defaultLabel) + neuralVoices.map { it.displayName }
                    currentVoiceLabel = neuralVoices.firstOrNull { it.id == settings.voiceId }
                        ?.displayName ?: defaultLabel
                }
            }
            // Use an explicit non-filtering ArrayAdapter rather than [setSimpleItems]. The
            // Material library's MaterialArrayAdapter still routes through ArrayAdapter's
            // filter, and combined with [setText(label, false)] the popup occasionally opens
            // showing only entries that match the current text — the user reported the picker
            // collapsing to just "Default" when that was the saved selection. A no-op filter
            // guarantees the popup always shows the full list regardless of the EditText
            // contents at open time.
            binding.ttsVoiceDropdown.setAdapter(NoFilterArrayAdapter(requireContext(), voiceLabels))
            binding.ttsVoiceDropdown.setText(currentVoiceLabel, false)

            // Neural folder helper visibility
            binding.ttsNeuralFolderSection.visibility = if (isNeural) View.VISIBLE else View.GONE
            binding.ttsNeuralFolderPath.text = optionsViewModel.neuralModelFolderPath
                ?: "(external storage unavailable)"
            binding.ttsNeuralEmptyState.visibility =
                if (isNeural && neuralVoices.isEmpty()) View.VISIBLE else View.GONE

            // Sliders. Clamp to each slider's OWN bounds (rate 0.5-3.0, pitch 0.5-2.0 per the
            // layout) — a hardcoded 2.0 rate cap here previously snapped the displayed rate
            // back while playback kept the real 2.0-3.0 value, and the next slider touch then
            // silently rewrote the persisted rate downward.
            binding.ttsRateSlider.setValueClamped(settings.speechRate)
            binding.ttsPitchSlider.setValueClamped(settings.pitch)
        } finally {
            isUpdatingTtsSettingsProgram = false
        }
    }

    /**
     * Returns the System voice list as currently filtered by the saved language filter — the
     * same list shown in the dropdown, so an onItemClick at position N maps to entry N-1
     * here. Mirrors the filtering done in [renderTtsCard]; falling out of sync would let the
     * click handler pick a different voice than the one the user tapped.
     */
    private fun visibleSystemVoices(): List<TtsVoice> {
        val savedFilter = optionsViewModel.ttsSettings.value.systemLanguageFilter
        val effectiveFilter = if (savedFilter != null &&
            currentSystemVoices.any { it.localeTag == savedFilter }) savedFilter else null
        return if (effectiveFilter == null) currentSystemVoices
            else currentSystemVoices.filter { it.localeTag == effectiveFilter }
    }

    /**
     * Render an IETF BCP-47 locale tag (e.g. "en-US") as a human-readable language name in the
     * device's display locale. Falls back to the raw tag if Locale parsing fails so a
     * non-conforming tag from a third-party TTS engine still shows something rather than
     * an empty row.
     */
    private fun displayLanguageName(localeTag: String): String {
        return try {
            val locale = Locale.forLanguageTag(localeTag)
            val display = locale.displayName
            if (display.isNullOrBlank()) localeTag else display
        } catch (_: Exception) {
            localeTag
        }
    }

    private fun setupOptionsTab() {
        // Setup font size slider (this comes from ThemeManager, not RsvpSettings)
        with(binding) {
            fontSizeSlider.value = currentFontSize
            fontSizeValue.text = "${currentFontSize.toInt()}sp"
            fontSizeSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
                if (fromUser) {
                    currentFontSize = value
                    fontSizeValue.text = "${value.toInt()}sp"
                    // updateFontSize(value) // This is for RSVP display, handled by ReadingFragment
                    themeManager.saveFontSize(value)
                }
            }

            // Reset defaults button
            btnResetDefaults.setOnClickListener {
                showResetDefaultsConfirmationDialog()
            }

            // Page View Only Mode toggle
            pageViewOnlyToggleSwitch.isChecked = themeManager.isPageViewOnlyMode()
            pageViewOnlyToggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                themeManager.savePageViewOnlyMode(isChecked)
            }

            // WPM Slider
            baseWpmSlider.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val wpm = value.toInt()
                    baseWpmValue.text = "$wpm WPM"
                    isUpdatingSettingsSlidersProgram = true
                    optionsViewModel.updateWpm(wpm)
                    isUpdatingSettingsSlidersProgram = false
                }
            }

            // Chunk Size Slider
            chunkSizeSlider.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val chunkSize = value.toInt()
                    chunkSizeValue.text = "$chunkSize word${if (chunkSize > 1) "s" else ""}"
                    isUpdatingSettingsSlidersProgram = true
                    optionsViewModel.updateChunkSize(chunkSize)
                    isUpdatingSettingsSlidersProgram = false
                }
            }

            // ORP Toggles
            orpToggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                optionsViewModel.updateOrpSettings(isChecked, centerOrpToggleSwitch.isChecked)
            }
            centerOrpToggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                optionsViewModel.updateOrpSettings(orpToggleSwitch.isChecked, isChecked)
            }

            // ORP Color Dropdown
            orpColorDropdown.setAdapter(
                NoFilterArrayAdapter(requireContext(), orpColorPresets.map { it.name }.toTypedArray())
            )
            orpColorDropdown.setOnItemClickListener { _, _, position, _ ->
                optionsViewModel.updateOrpColor(orpColorPresets[position].color)
            }
            launchOnView {
                val currentOrpColor = optionsViewModel.settings.first().orpColor
                val matching = orpColorPresets.find { it.color == currentOrpColor }
                orpColorDropdown.setText((matching ?: orpColorPresets[0]).name, false)
            }

            // Screen Dimming Toggle
            screenDimmingToggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                optionsViewModel.updateScreenDimming(isChecked)
            }

            // Master punctuation-pausing toggle. Gates every punctuation pause AND the
            // line/paragraph break pauses in the timing calculator; the per-punctuation
            // sliders below tune individual pauses once this is on. The setting existed
            // (and was consumed by the engine) with no UI to change it before this switch.
            punctuationPausingToggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                optionsViewModel.updatePunctuationPausing(isChecked)
            }

            // Additive corner picker (up to all four). The listener fires on every check AND
            // uncheck within the multi-select group; we read the group's full checkedButtonIds
            // snapshot on each event so partial-state bugs can't creep in. Programmatic sync
            // from updateCornerToggleSelection sets the re-entrancy flag so its check/uncheck
            // calls don't round-trip back to the VM. The layout's app:selectionRequired="true"
            // prevents user-initiated unchecking of the last selected button, guaranteeing the
            // resulting set is never empty — but the VM still short-circuits an empty write
            // defensively (see SettingsRepository).
            landscapePlayButtonCornerToggleGroup.addOnButtonCheckedListener { group, _, _ ->
                if (isUpdatingCornerToggleProgram) return@addOnButtonCheckedListener
                val corners = group.checkedButtonIds
                    .mapNotNullTo(mutableSetOf()) { cornerFromButtonId(it) }
                if (corners.isEmpty()) return@addOnButtonCheckedListener
                optionsViewModel.updateLandscapePlayButtonCorners(corners)
            }

            // Landscape scrub sensitivity slider. `fromUser` guard prevents the
            // programmatic-update path (observer → slider.value =) from looping back through
            // the VM; the isUpdatingSettingsSlidersProgram flag in observeViewModel already
            // handles the value-text label refresh.
            scrubSensitivitySlider.addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                scrubSensitivityValue.text =
                    getString(R.string.scrub_sensitivity_value, value.toInt())
                isUpdatingSettingsSlidersProgram = true
                optionsViewModel.updateScrubPixelsPerWord(value)
                isUpdatingSettingsSlidersProgram = false
            }
        }

        // Setup theme controls
        setupThemeControls()

        // Setup text color controls
        setupTextColorControls()

        // Setup timing settings
        setupTimingSettings()

        // Setup word length timing controls
        setupWordLengthTimingControls()
    }

    private fun setupTimingSettings() {
        with(binding) {
            // Comma Pause
            TimingSettingViewHelper(binding.commaPauseSetting.root).apply {
                setTitle(getString(R.string.comma_timing))
                setDescription(getString(R.string.comma_description))
                setSliderRange(0, 500, 10)
                setInitialValue(roundToStep(currentPunctuationTiming.comma.toFloat(), 10f))
                setOnSliderChangeListener { value ->
                    optionsViewModel.updatePunctuationTiming(0, value.toInt())
                }
            }
            // Period Pause
            TimingSettingViewHelper(binding.periodPauseSetting.root).apply {
                setTitle(getString(R.string.period_timing))
                setDescription(getString(R.string.period_description))
                setSliderRange(0, 1000, 20)
                setInitialValue(roundToStep(currentPunctuationTiming.period.toFloat(), 20f))
                setOnSliderChangeListener { value ->
                    optionsViewModel.updatePunctuationTiming(1, value.toInt())
                }
            }
            // Semicolon Pause
            TimingSettingViewHelper(binding.semicolonPauseSetting.root).apply {
                setTitle(getString(R.string.semicolon_timing))
                setDescription(getString(R.string.semicolon_description))
                setSliderRange(0, 750, 10)
                setInitialValue(roundToStep(currentPunctuationTiming.semicolon.toFloat(), 10f))
                setOnSliderChangeListener { value ->
                    optionsViewModel.updatePunctuationTiming(2, value.toInt())
                }
            }
            // Colon Pause
            TimingSettingViewHelper(binding.colonPauseSetting.root).apply {
                setTitle(getString(R.string.colon_timing))
                setDescription(getString(R.string.colon_description))
                setSliderRange(0, 750, 10)
                setInitialValue(roundToStep(currentPunctuationTiming.colon.toFloat(), 10f))
                setOnSliderChangeListener { value ->
                    optionsViewModel.updatePunctuationTiming(3, value.toInt())
                }
            }
            // Question Mark Pause
            TimingSettingViewHelper(binding.questionPauseSetting.root).apply {
                setTitle(getString(R.string.question_timing))
                setDescription(getString(R.string.question_description))
                setSliderRange(0, 1000, 10)
                setInitialValue(currentPunctuationTiming.questionMark.toFloat())
                setOnSliderChangeListener { value ->
                    optionsViewModel.updatePunctuationTiming(4, value.toInt())
                }
            }
            // Exclamation Pause
            TimingSettingViewHelper(binding.exclamationPauseSetting.root).apply {
                setTitle(getString(R.string.exclamation_timing))
                setDescription(getString(R.string.exclamation_description))
                setSliderRange(0, 1000, 10)
                setInitialValue(currentPunctuationTiming.exclamation.toFloat())
                setOnSliderChangeListener { value ->
                    optionsViewModel.updatePunctuationTiming(5, value.toInt())
                }
            }
            // Line Break Pause
            TimingSettingViewHelper(binding.lineBreakPauseSetting.root).apply {
                setTitle(getString(R.string.line_break_timing))
                setDescription(getString(R.string.line_break_description))
                setSliderRange(0, 1000, 20)
                setInitialValue(roundToStep(currentPunctuationTiming.lineBreak.toFloat(), 20f))
                setOnSliderChangeListener { value ->
                    optionsViewModel.updatePunctuationTiming(6, value.toInt())
                }
            }
            // Paragraph Break Pause
            TimingSettingViewHelper(binding.paragraphPauseSetting.root).apply {
                setTitle(getString(R.string.paragraph_timing))
                setDescription(getString(R.string.paragraph_description))
                setSliderRange(0, 1500, 50)
                setInitialValue(roundToStep(currentPunctuationTiming.paragraphBreak.toFloat(), 50f))
                setOnSliderChangeListener { value ->
                    optionsViewModel.updatePunctuationTiming(7, value.toInt())
                }
            }
        }
    }

    private fun setupWordLengthTimingControls() {
        with(binding) {
            wordLengthTimingToggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (!isUpdatingSettingsSlidersProgram) {
                    optionsViewModel.updateWordLengthTimingEnabled(isChecked)
                }
            }
            wordLengthBaselineSlider.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    wordLengthBaselineValue.text =
                        getString(R.string.word_length_baseline_value, value.toInt())
                    isUpdatingSettingsSlidersProgram = true
                    optionsViewModel.updateWordLengthBaseline(value.toInt())
                    isUpdatingSettingsSlidersProgram = false
                }
            }
            wordLengthScalingSlider.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    wordLengthScalingValue.text =
                        getString(R.string.word_length_scaling_value, value.toInt())
                    isUpdatingSettingsSlidersProgram = true
                    optionsViewModel.updateWordLengthScalingPercent(value.toInt())
                    isUpdatingSettingsSlidersProgram = false
                }
            }
        }
    }

    private fun roundToStep(value: Float, step: Float): Float {
        return (value / step).toInt() * step
    }

    // Clamp a persisted value into the slider's own [valueFrom, valueTo] range and snap it
    // to the step grid before assignment. Material Slider throws IllegalStateException for
    // values outside the range or off the step grid, so any out-of-band persisted value
    // (backup restore across versions, ranges widened elsewhere — the engine deliberately
    // tolerates chunk sizes and WPM beyond what the sliders expose) must be sanitized at
    // the UI boundary or the Options screen crashes on open with no way to recover.
    private fun Slider.setValueClamped(raw: Float) {
        val snapped = if (stepSize > 0f) {
            valueFrom + Math.round((raw - valueFrom) / stepSize) * stepSize
        } else {
            raw
        }
        value = snapped.coerceIn(valueFrom, valueTo)
    }

    private fun updateTimingSliders(timing: PunctuationTiming) {
        isUpdatingSettingsSlidersProgram = true
        with(binding) {
            TimingSettingViewHelper(binding.commaPauseSetting.root).setInitialValue(timing.comma.toFloat())
            TimingSettingViewHelper(binding.periodPauseSetting.root).setInitialValue(timing.period.toFloat())
            TimingSettingViewHelper(binding.semicolonPauseSetting.root).setInitialValue(timing.semicolon.toFloat())
            TimingSettingViewHelper(binding.colonPauseSetting.root).setInitialValue(timing.colon.toFloat())
            TimingSettingViewHelper(binding.questionPauseSetting.root).setInitialValue(timing.questionMark.toFloat())
            TimingSettingViewHelper(binding.exclamationPauseSetting.root).setInitialValue(timing.exclamation.toFloat())
            TimingSettingViewHelper(binding.lineBreakPauseSetting.root).setInitialValue(timing.lineBreak.toFloat())
            TimingSettingViewHelper(binding.paragraphPauseSetting.root).setInitialValue(timing.paragraphBreak.toFloat())
        }
        isUpdatingSettingsSlidersProgram = false
    }

    private fun resetToDefaults() {
        // Reset RSVP settings via ViewModel (includes persistence)
        // The UI will be automatically updated via the settings Flow observer
        optionsViewModel.resetSettingsToDefaults()

        // Reset TTS settings too — the confirmation dialog promises to reset ALL settings,
        // and backend/voice/rate/pitch previously survived the reset.
        optionsViewModel.resetTtsSettingsToDefaults()

        // Reset theme settings to defaults. Capture whether the STYLE actually changes:
        // style switches (e.g. Dynamic -> Default) only take visual effect on activity
        // recreation, which the normal style-change path performs but this reset path
        // previously skipped — leaving wallpaper-derived colors on screen while the toggle
        // showed Material Default.
        val styleChanged = themeManager.getCurrentThemeSettings().style != ThemeSettings.default().style
        currentThemeSettings = ThemeSettings.default()
        themeManager.saveThemeSettings(currentThemeSettings)
        isInitializingTheme = true
        updateThemeSelections()
        isInitializingTheme = false
        themeManager.applyThemeSettings(currentThemeSettings)

        // Reset font size to default
        val defaultFontSize = com.speedread.rsvp.Constants.DEFAULT_FONT_SIZE
        currentFontSize = defaultFontSize
        themeManager.saveFontSize(defaultFontSize)
        binding.fontSizeSlider.value = defaultFontSize
        binding.fontSizeValue.text = "${defaultFontSize.toInt()}sp"
        // updateFontSize(defaultFontSize) // This is for RSVP display, handled by ReadingFragment

        // Reset Page View Only Mode
        themeManager.resetPageViewOnlyMode()
        binding.pageViewOnlyToggleSwitch.isChecked = themeManager.isPageViewOnlyMode()

        // Reset Page View appearance to defaults
        themeManager.resetPageViewFontSize()
        themeManager.resetPageViewColors()
        themeManager.resetPageViewScrollMode()
        val defaultPageViewFontSize = com.speedread.rsvp.Constants.DEFAULT_PAGE_VIEW_FONT_SIZE
        binding.pageViewFontSizeSlider.value = defaultPageViewFontSize
        binding.pageViewFontSizeValue.text = "${defaultPageViewFontSize.toInt()}sp"
        val resetColors = themeManager.getCurrentPageViewColors()
        val resetMatching = colorPresets.find {
            it.textColor == resetColors.textColor && it.backgroundColor == resetColors.backgroundColor
        }
        binding.pageViewColorPresetDropdown.setText(
            (resetMatching ?: colorPresets[0]).name,
            false
        )
        val resetMode = themeManager.getCurrentPageViewScrollMode()
        val resetModeButtonId = if (resetMode == PageViewScrollMode.CONTINUOUS) {
            R.id.pageViewScrollModeContinuous
        } else {
            R.id.pageViewScrollModePaged
        }
        binding.pageViewScrollModeGroup.check(resetModeButtonId)

        binding.orpColorDropdown.setText(orpColorPresets[0].name, false)

        Toast.makeText(requireContext(), "Settings reset to defaults", Toast.LENGTH_SHORT).show()

        if (styleChanged) {
            requireActivity().recreate()
        }
    }

    private fun showResetDefaultsConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Reset Settings")
            .setMessage("Are you sure you want to reset all settings to their default values? This cannot be undone.")
            .setPositiveButton("Reset") { _, _ ->
                resetToDefaults()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupThemeControls() {
        with(binding) {
            // Set initial selections based on current theme BEFORE setting up listeners
            isInitializingTheme = true
            updateThemeSelections()
            isInitializingTheme = false

            // Setup theme mode toggle group
            themeModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked && !isInitializingTheme) {
                    val newMode = when (checkedId) {
                        R.id.btnLightTheme -> ThemeMode.LIGHT
                        R.id.btnDarkTheme -> ThemeMode.DARK
                        R.id.btnSystemTheme -> ThemeMode.SYSTEM_DEFAULT
                        else -> ThemeMode.SYSTEM_DEFAULT
                    }
                    if (newMode != currentThemeSettings.mode) {
                        currentThemeSettings = currentThemeSettings.copy(mode = newMode)
                        applyThemeSettings()
                    }
                }
            }

            // Setup theme style toggle group
            themeStyleToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked && !isInitializingTheme) {
                    val newStyle = when (checkedId) {
                        R.id.btnMaterialDefault -> ThemeStyle.MATERIAL_DEFAULT
                        R.id.btnMaterialDynamic -> ThemeStyle.MATERIAL_DYNAMIC
                        else -> ThemeStyle.MATERIAL_DEFAULT
                    }
                    if (newStyle != currentThemeSettings.style) {
                        currentThemeSettings = currentThemeSettings.copy(style = newStyle)
                        applyThemeSettings()
                    }
                }
            }

            // Disable dynamic theming if not available
            if (!themeManager.isDynamicThemingAvailable()) {
                btnMaterialDynamic.isEnabled = false
                btnMaterialDynamic.text = "${getString(R.string.material_dynamic)} (Android 12+)"
            }
        }
    }

    private fun setupTextColorControls() {
        with(binding) {
            colorPresetDropdown.setAdapter(
                NoFilterArrayAdapter(requireContext(), colorPresets.map { it.name }.toTypedArray())
            )

            // Handle selection
            colorPresetDropdown.setOnItemClickListener { _, _, position, _ ->
                val selectedPreset = colorPresets[position]
                optionsViewModel.updateTextColors(selectedPreset.textColor, selectedPreset.backgroundColor)
            }

            // Set initial selection based on current settings
            launchOnView {
                val currentSettings = optionsViewModel.settings.first()
                updateColorPresetSelection(currentSettings.textColor, currentSettings.backgroundColor, colorPresets)
            }
        }
    }

    // Page View has its own font size + color theme so users can read full pages at a
    // comfortable body-text size while keeping RSVP at a larger single-word size.
    private fun setupPageViewAppearanceControls() {
        with(binding) {
            // Font size slider
            val initialPageViewFontSize = themeManager.getCurrentPageViewFontSize()
            pageViewFontSizeSlider.value = initialPageViewFontSize
            pageViewFontSizeValue.text = "${initialPageViewFontSize.toInt()}sp"
            pageViewFontSizeSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
                if (fromUser) {
                    pageViewFontSizeValue.text = "${value.toInt()}sp"
                    themeManager.savePageViewFontSize(value)
                }
            }

            // Color preset dropdown
            pageViewColorPresetDropdown.setAdapter(
                NoFilterArrayAdapter(requireContext(), colorPresets.map { it.name }.toTypedArray())
            )
            pageViewColorPresetDropdown.setOnItemClickListener { _, _, position, _ ->
                val preset = colorPresets[position]
                themeManager.savePageViewColors(preset.textColor, preset.backgroundColor)
            }

            // Initial dropdown selection matches whichever preset is currently stored (or falls
            // back to the first preset if the user previously picked a non-preset combo).
            val currentColors = themeManager.getCurrentPageViewColors()
            val matching = colorPresets.find {
                it.textColor == currentColors.textColor && it.backgroundColor == currentColors.backgroundColor
            }
            pageViewColorPresetDropdown.setText(
                (matching ?: colorPresets[0]).name,
                false
            )

            // Scroll mode toggle (mirrors the in-place sheet so the setting is reachable from
            // the main Options screen too). Pre-check before adding the listener so first-paint
            // doesn't immediately re-save the same value.
            val initialMode = themeManager.getCurrentPageViewScrollMode()
            val initialModeButtonId = if (initialMode == PageViewScrollMode.CONTINUOUS) {
                R.id.pageViewScrollModeContinuous
            } else {
                R.id.pageViewScrollModePaged
            }
            pageViewScrollModeGroup.check(initialModeButtonId)
            pageViewScrollModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val mode = when (checkedId) {
                    R.id.pageViewScrollModeContinuous -> PageViewScrollMode.CONTINUOUS
                    R.id.pageViewScrollModePaged -> PageViewScrollMode.PAGED
                    else -> return@addOnButtonCheckedListener
                }
                if (themeManager.getCurrentPageViewScrollMode() != mode) {
                    themeManager.savePageViewScrollMode(mode)
                }
            }

            // Paragraph Spacing Toggle
            paragraphSpacingToggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                optionsViewModel.updateParagraphSpacing(isChecked)
            }
        }
    }

    private fun updateColorPresetSelection(textColor: Int, backgroundColor: Int, colorPresets: List<ColorPreset>) {
        // Find matching preset
        val matchingPreset = colorPresets.find { preset ->
            preset.textColor == textColor && preset.backgroundColor == backgroundColor
        }

        if (matchingPreset != null) {
            binding.colorPresetDropdown.setText(matchingPreset.name, false)
        } else {
            // Default to first option if no match
            binding.colorPresetDropdown.setText(colorPresets[0].name, false)
        }

        // Apply the colors to the display
        // binding.rsvpWordDisplay.setTextColor(textColor) // Handled by ReadingFragment
        // binding.rsvpWordDisplay.setBackgroundColor(backgroundColor) // Handled by ReadingFragment
    }

    private fun updateThemeSelections() {
        with(binding) {
            // Clear all selections first
            themeModeToggleGroup.clearChecked()
            themeStyleToggleGroup.clearChecked()

            // Set theme mode selection
            val modeButtonId = when (currentThemeSettings.mode) {
                ThemeMode.LIGHT -> R.id.btnLightTheme
                ThemeMode.DARK -> R.id.btnDarkTheme
                ThemeMode.SYSTEM_DEFAULT -> R.id.btnSystemTheme
            }
            themeModeToggleGroup.check(modeButtonId)

            // Set theme style selection
            val styleButtonId = when (currentThemeSettings.style) {
                ThemeStyle.MATERIAL_DEFAULT -> R.id.btnMaterialDefault
                ThemeStyle.MATERIAL_DYNAMIC -> R.id.btnMaterialDynamic
            }
            themeStyleToggleGroup.check(styleButtonId)
        }
    }

    private fun applyThemeSettings() {
        // Save theme settings but don't apply immediately during initialization
        themeManager.saveThemeSettings(currentThemeSettings)

        // Only apply theme mode change immediately (no restart needed for this)
        themeManager.applyThemeMode(currentThemeSettings.mode)

        // Force status bar to stay black regardless of theme
        // themeController.forceStatusBarBlack() // Handled by ThemeController

        // For style changes that require a restart, show a simple toast
        Toast.makeText(requireContext(), "Theme updated", Toast.LENGTH_SHORT).show()

        // Recreate activity to apply any theme style changes
        requireActivity().recreate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * [ArrayAdapter] that disables filtering — every dropdown open shows the full list regardless
 * of the bound [AutoCompleteTextView]'s current text. Backs the TTS backend / voice pickers
 * so setting the current selection via `setText(label, false)` doesn't bleed into the popup
 * window's auto-filter behavior and prune the visible options to ones containing that label.
 */
internal class NoFilterArrayAdapter(
    context: Context,
    private val items: Array<String>
) : ArrayAdapter<String>(context, com.google.android.material.R.layout.mtrl_auto_complete_simple_item, items) {

    private val noFilter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults =
            FilterResults().apply {
                values = items.toList()
                count = items.size
            }
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            notifyDataSetChanged()
        }
    }

    override fun getFilter(): Filter = noFilter
}
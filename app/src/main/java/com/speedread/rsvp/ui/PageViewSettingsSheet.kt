package com.speedread.rsvp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.speedread.rsvp.Constants
import com.speedread.rsvp.R
import com.speedread.rsvp.ThemeManager
import com.speedread.rsvp.databinding.SheetPageViewSettingsBinding
import com.speedread.rsvp.engine.PageViewScrollMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * In-place Page View appearance editor. Shown from the PageView toolbar's Settings action so
 * font size and color changes apply live while the user stays on the page they were reading.
 * Writes through to ThemeManager — PageViewActivity already observes those StateFlows and
 * rebinds the adapter on emission, so no direct wiring to the activity is needed.
 */
@AndroidEntryPoint
class PageViewSettingsSheet : BottomSheetDialogFragment() {

    @Inject lateinit var themeManager: ThemeManager

    private var _binding: SheetPageViewSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetPageViewSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFontSizeSlider()
        setupColorPresetDropdown()
        setupScrollModeToggle()
        binding.pageViewSheetDoneButton.setOnClickListener { dismiss() }
    }

    private fun setupFontSizeSlider() {
        val current = themeManager.getCurrentPageViewFontSize()
        with(binding) {
            pageViewSheetFontSizeSlider.valueFrom = Constants.MIN_PAGE_VIEW_FONT_SIZE
            pageViewSheetFontSizeSlider.valueTo = Constants.MAX_PAGE_VIEW_FONT_SIZE
            pageViewSheetFontSizeSlider.value =
                current.coerceIn(Constants.MIN_PAGE_VIEW_FONT_SIZE, Constants.MAX_PAGE_VIEW_FONT_SIZE)
            pageViewSheetFontSizeValue.text = "${current.toInt()}sp"
            pageViewSheetFontSizeSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
                if (fromUser) {
                    pageViewSheetFontSizeValue.text = "${value.toInt()}sp"
                    themeManager.savePageViewFontSize(value)
                }
            }
        }
    }

    private fun setupColorPresetDropdown() {
        val presets = PageViewColorPresets.build(requireContext())
        with(binding) {
            pageViewSheetColorPresetDropdown.setAdapter(
                NoFilterArrayAdapter(requireContext(), presets.map { it.name }.toTypedArray())
            )
            pageViewSheetColorPresetDropdown.setOnItemClickListener { _, _, position, _ ->
                val preset = presets[position]
                themeManager.savePageViewColors(preset.textColor, preset.backgroundColor)
            }
            // Pre-select whichever preset is stored; fall back to the first if the user
            // previously picked a non-preset combo (e.g. through a future custom picker).
            val currentColors = themeManager.getCurrentPageViewColors()
            val matching = presets.find {
                it.textColor == currentColors.textColor && it.backgroundColor == currentColors.backgroundColor
            }
            pageViewSheetColorPresetDropdown.setText((matching ?: presets[0]).name, false)
        }
    }

    private fun setupScrollModeToggle() {
        val current = themeManager.getCurrentPageViewScrollMode()
        with(binding) {
            // Pre-check the active mode without firing the listener (which would otherwise
            // re-save the same value on first open and re-trigger any cross-mode side
            // effects in the activity).
            val initialId = if (current == PageViewScrollMode.CONTINUOUS) {
                R.id.pageViewSheetScrollModeContinuous
            } else {
                R.id.pageViewSheetScrollModePaged
            }
            pageViewSheetScrollModeGroup.check(initialId)
            pageViewSheetScrollModeGroup.addOnButtonCheckedListener(
                MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
                    if (!isChecked) return@OnButtonCheckedListener
                    val mode = when (checkedId) {
                        R.id.pageViewSheetScrollModeContinuous -> PageViewScrollMode.CONTINUOUS
                        R.id.pageViewSheetScrollModePaged -> PageViewScrollMode.PAGED
                        else -> return@OnButtonCheckedListener
                    }
                    if (themeManager.getCurrentPageViewScrollMode() != mode) {
                        themeManager.savePageViewScrollMode(mode)
                    }
                }
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "page_view_settings"
    }
}

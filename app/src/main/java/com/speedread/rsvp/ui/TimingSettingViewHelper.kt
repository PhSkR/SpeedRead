package com.speedread.rsvp.ui

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.slider.Slider
import com.speedread.rsvp.R

/**
 * Helper class to manage timing setting items that are included as layouts
 * since custom views from library modules can't be directly used in app XML layouts
 */
class TimingSettingViewHelper(private val rootView: View) {
    
    private val timingLabel: TextView = rootView.findViewById(R.id.timingLabel)
    private val timingValue: TextView = rootView.findViewById(R.id.timingValue)
    private val timingSlider: Slider = rootView.findViewById(R.id.timingSlider)
    private val timingDescription: TextView = rootView.findViewById(R.id.timingDescription)
    
    fun setTitle(title: String) {
        timingLabel.text = title
    }
    
    fun setDescription(description: String) {
        timingDescription.text = description
        timingDescription.isVisible = description.isNotEmpty()
    }
    
    fun setSliderRange(min: Int, max: Int, step: Int) {
        timingSlider.valueFrom = min.toFloat()
        timingSlider.valueTo = max.toFloat()
        timingSlider.stepSize = step.toFloat()
    }
    
    fun setInitialValue(value: Float) {
        // Material Slider throws IllegalStateException if value is not on a step boundary.
        // Defensively snap to the nearest valid step and clamp to the declared range so a
        // mismatched persisted value or drifted default cannot crash the next layout pass.
        val snapped = snapToSlider(value)
        timingSlider.value = snapped
        updateValueDisplay(snapped.toInt())
    }

    private fun snapToSlider(value: Float): Float {
        val min = timingSlider.valueFrom
        val max = timingSlider.valueTo
        val step = timingSlider.stepSize
        val clamped = value.coerceIn(min, max)
        if (step <= 0f) return clamped
        val steps = Math.round((clamped - min) / step)
        return (min + steps * step).coerceIn(min, max)
    }
    
    fun setOnSliderChangeListener(listener: (Float) -> Unit) {
        timingSlider.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                updateValueDisplay(value.toInt())
                listener(value)
            }
        }
    }
    
    private fun updateValueDisplay(value: Int) {
        timingValue.text = "${value}ms"
    }
    
    fun getValue(): Float = timingSlider.value
}
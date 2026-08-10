package com.speedread.rsvp

import android.view.View
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.speedread.rsvp.engine.PunctuationTiming

data class TimingSetting(
    val labelRes: Int,
    val descriptionRes: Int,
    val getValue: (PunctuationTiming) -> Int,
    val setValue: (PunctuationTiming, Int) -> PunctuationTiming
)

class TimingSettingsManager {
    
    companion object {
        val TIMING_SETTINGS = listOf(
            TimingSetting(
                labelRes = R.string.comma_timing,
                descriptionRes = R.string.comma_description,
                getValue = { it.comma },
                setValue = { timing, value -> timing.copy(comma = value) }
            ),
            TimingSetting(
                labelRes = R.string.period_timing,
                descriptionRes = R.string.period_description,
                getValue = { it.period },
                setValue = { timing, value -> timing.copy(period = value) }
            ),
            TimingSetting(
                labelRes = R.string.semicolon_timing,
                descriptionRes = R.string.semicolon_description,
                getValue = { it.semicolon },
                setValue = { timing, value -> timing.copy(semicolon = value) }
            ),
            TimingSetting(
                labelRes = R.string.colon_timing,
                descriptionRes = R.string.colon_description,
                getValue = { it.colon },
                setValue = { timing, value -> timing.copy(colon = value) }
            ),
            TimingSetting(
                labelRes = R.string.question_timing,
                descriptionRes = R.string.question_description,
                getValue = { it.questionMark },
                setValue = { timing, value -> timing.copy(questionMark = value) }
            ),
            TimingSetting(
                labelRes = R.string.exclamation_timing,
                descriptionRes = R.string.exclamation_description,
                getValue = { it.exclamation },
                setValue = { timing, value -> timing.copy(exclamation = value) }
            ),
            TimingSetting(
                labelRes = R.string.line_break_timing,
                descriptionRes = R.string.line_break_description,
                getValue = { it.lineBreak },
                setValue = { timing, value -> timing.copy(lineBreak = value) }
            ),
            TimingSetting(
                labelRes = R.string.paragraph_timing,
                descriptionRes = R.string.paragraph_description,
                getValue = { it.paragraphBreak },
                setValue = { timing, value -> timing.copy(paragraphBreak = value) }
            )
        )
    }
    
    fun setupTimingSettingView(
        view: View,
        setting: TimingSetting,
        currentTiming: PunctuationTiming,
        onValueChanged: (Int) -> Unit
    ) {
        val labelView = view.findViewById<TextView>(R.id.timingLabel)
        val valueView = view.findViewById<TextView>(R.id.timingValue)
        val descriptionView = view.findViewById<TextView>(R.id.timingDescription)
        val slider = view.findViewById<Slider>(R.id.timingSlider)
        
        // Set texts
        labelView.setText(setting.labelRes)
        descriptionView.setText(setting.descriptionRes)
        
        // Set current value
        val currentValue = setting.getValue(currentTiming)
        valueView.text = view.context.getString(R.string.timing_format, currentValue)
        slider.value = currentValue.toFloat()
        
        // Set up slider listener
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val intValue = value.toInt()
                valueView.text = view.context.getString(R.string.timing_format, intValue)
                onValueChanged(intValue)
            }
        }
    }
}
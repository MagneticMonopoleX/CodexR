package com.example.codexmobile

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.example.codexmobile.api.AgentQuestion

/** Explicit selection and submission, shared by every refresh of one overlay question. */
internal class OverlayQuestionView(
    context: Context,
    question: AgentQuestion,
    textColor: Int,
    onCustomInput: (EditText?) -> Unit,
    onAnswer: (String) -> Boolean,
    onSkip: () -> Boolean,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        addView(TextView(context).apply {
            text = question.question
            setTextColor(textColor)
            textSize = 14f
        })
        val choices = RadioGroup(context)
        val options = question.options.associate { option ->
            val radio = RadioButton(context).apply {
                id = View.generateViewId()
                text = option
                setTextColor(textColor)
            }
            choices.addView(radio)
            radio.id to option
        }
        val custom = RadioButton(context).apply {
            id = View.generateViewId()
            text = "Specify your own"
            setTextColor(textColor)
        }
        choices.addView(custom)
        addView(choices)
        val input = EditText(context).apply {
            hint = "Your answer"
            contentDescription = "Custom answer"
            setTextColor(textColor)
            setHintTextColor(textColor)
            maxLines = 3
            filters = arrayOf(android.text.InputFilter.LengthFilter(8_000))
            visibility = View.GONE
        }
        addView(input, LayoutParams(-1, -2))
        val submit = Button(context).apply { text = "Answer"; isEnabled = false }
        val skip = Button(context).apply { text = "Skip" }
        var submitted = false
        fun answer(): String = if (choices.checkedRadioButtonId == custom.id) input.text.toString().trim()
            else options[choices.checkedRadioButtonId].orEmpty()
        choices.setOnCheckedChangeListener { _, id ->
            val customSelected = id == custom.id
            input.visibility = if (customSelected) View.VISIBLE else View.GONE
            onCustomInput(if (customSelected) input else null)
            submit.isEnabled = !submitted && answer().isNotBlank()
        }
        input.doAfterTextChanged { submit.isEnabled = !submitted && answer().isNotBlank() }
        fun markSubmitted() {
            submitted = true
            submit.isEnabled = false
            skip.isEnabled = false
            choices.isEnabled = false
            for (index in 0 until choices.childCount) choices.getChildAt(index).isEnabled = false
            input.isEnabled = false
            onCustomInput(null)
        }
        submit.setOnClickListener {
            val value = answer()
            if (!submitted && value.isNotBlank() && onAnswer(value)) markSubmitted()
        }
        skip.setOnClickListener { if (!submitted && onSkip()) markSubmitted() }
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(skip, LayoutParams(0, -2, 1f))
            addView(submit, LayoutParams(0, -2, 1f))
        })
    }
}

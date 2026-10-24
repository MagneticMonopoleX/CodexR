package com.example.codexmobile

import android.app.Application
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import com.example.codexmobile.api.AgentQuestion
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class OverlayQuestionViewTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun `choices and custom input require explicit submission and send once`() {
        val answers = mutableListOf<String>()
        val panel = OverlayQuestionView(RuntimeEnvironment.getApplication(),
            AgentQuestion("q", "Which device?", listOf("Tablet", "Phone")), Color.BLACK, {},
            { answers += it; true }, { false })
        val views = descendants(panel)
        val answer = views.filterIsInstance<Button>().single { it.text == "Answer" }
        assertFalse(answer.isEnabled)
        views.filterIsInstance<RadioButton>().single { it.text == "Tablet" }.performClick()
        assertTrue(answer.isEnabled)
        assertTrue(answers.isEmpty())
        views.filterIsInstance<RadioButton>().single { it.text == "Specify your own" }.performClick()
        assertFalse(answer.isEnabled)
        val editor = views.filterIsInstance<EditText>().single()
        assertEquals(View.VISIBLE, editor.visibility)
        editor.setText("My device")
        answer.performClick()
        answer.performClick()
        assertEquals(listOf("My device"), answers)
        assertFalse(answer.isEnabled)
    }

    @Test fun `skip does not submit the selected answer`() {
        var skips = 0
        val panel = OverlayQuestionView(RuntimeEnvironment.getApplication(),
            AgentQuestion("q", "Which device?", listOf("Tablet")), Color.BLACK, {},
            { fail("Skip must not answer"); false }, { skips++; true })
        val views = descendants(panel)
        views.filterIsInstance<RadioButton>().first().performClick()
        val skip = views.filterIsInstance<Button>().single { it.text == "Skip" }
        skip.performClick()
        skip.performClick()
        assertEquals(1, skips)
        assertFalse(skip.isEnabled)
    }
}

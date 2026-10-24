package com.example.codexmobile

import android.graphics.Color
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import org.hamcrest.Matcher
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.codexmobile.api.AgentQuestion
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayQuestionDeviceTest {
    private val accessibleClick = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isDisplayed()
        override fun getDescription() = "Activate the native view through its accessibility click action"
        override fun perform(controller: UiController, view: View) {
            check(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            controller.loopMainThreadUntilIdle()
        }
    }
    @Test fun nativeQuestionSupportsCustomAnswerOnDevice() {
        var received: String? = null
        // The real overlay is a native View hierarchy. Test it directly, without a
        // Compose AndroidView bridge. Accessibility activation also avoids injected
        // pointer-coordinate failures in remote/rotated Android test environments.
        ActivityScenario.launch(ComponentActivity::class.java).use { activity ->
            activity.onActivity {
                it.setContentView(OverlayQuestionView(it, AgentQuestion("q", "Which device?", listOf("Tablet", "Phone")),
                    Color.BLACK, onCustomInput = { editor -> editor?.requestFocus() },
                    onAnswer = { answer -> received = answer; true }, onSkip = { true }))
            }
            onView(withText("Specify your own")).perform(accessibleClick).check(matches(isChecked()))
            onView(withContentDescription("Custom answer")).perform(replaceText("My tablet"), closeSoftKeyboard())
            assertNull(received)
            onView(withText("Answer")).perform(accessibleClick)
            assertEquals("My tablet", received)
        }
    }
}

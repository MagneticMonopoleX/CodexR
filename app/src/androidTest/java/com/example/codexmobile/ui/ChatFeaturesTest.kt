package com.example.codexmobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.codexmobile.api.AgentQuestion
import com.example.codexmobile.api.ChatMessage
import com.example.codexmobile.data.ChatSession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatFeaturesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun questionSupportsChoicesCustomTextAndExplicitSubmission() {
        var received: String? = null
        compose.setContent { MaterialTheme {
            AgentQuestionDialog(AgentQuestion("q", "Which device?", listOf("Tablet", "Phone")),
                onAnswer = { received = it }, onSkip = {})
        } }
        compose.onNodeWithText("Answer").assertIsNotEnabled()
        compose.onNodeWithText("Tablet").performClick()
        assertNull(received)
        compose.onNodeWithText("Answer").assertIsEnabled()
        compose.onNodeWithTag("question-answer").performTextInput("Custom device")
        compose.onNodeWithText("Answer").performClick()
        assertEquals("Custom device", received)
    }

    @Test fun selectedOptionIsReturnedOnlyAfterConfirming() {
        var received: String? = null
        compose.setContent { MaterialTheme {
            AgentQuestionDialog(AgentQuestion("q", "Which device?", listOf("Tablet", "Phone")),
                onAnswer = { received = it }, onSkip = {})
        } }
        compose.onNodeWithText("Phone").performClick()
        assertNull(received)
        compose.onNodeWithText("Answer").performClick()
        assertEquals("Phone", received)
    }

    @Test fun searchSelectsTheExactMessage() {
        val message = ChatMessage("assistant", "The device is online")
        val session = ChatSession.create().copy(title = "Device setup", messages = listOf(message))
        var hit: ChatSearchHit? = null
        compose.setContent { MaterialTheme { ChatSearchDialog(listOf(session), true, { hit = it }, {}) } }
        compose.onNodeWithTag("chat-search-query").performTextInput("online")
        compose.onNodeWithTag("chat-search-hit-0").performClick()
        val selected = requireNotNull(hit)
        assertEquals(message.id, selected.messageId)
        assertEquals(session.id, selected.sessionId)
    }

    @Test fun skipDoesNotSubmitASelectedAnswer() {
        var skipped = false
        compose.setContent { MaterialTheme {
            AgentQuestionDialog(AgentQuestion("q", "Which device?", listOf("Tablet", "Phone")),
                onAnswer = { fail("Skip must not answer") }, onSkip = { skipped = true })
        } }
        compose.onNodeWithText("Tablet").performClick()
        compose.onNodeWithText("Stop").assertDoesNotExist()
        compose.onNodeWithText("Skip").performClick()
        assertTrue(skipped)
    }

    @Test fun overlayQuestionSupportsCustomAnswerOnDevice() {
        var received: String? = null
        compose.setContent {
            androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
                com.example.codexmobile.OverlayQuestionView(context,
                    AgentQuestion("q", "Which device?", listOf("Tablet", "Phone")), android.graphics.Color.BLACK,
                    onCustomInput = { it?.requestFocus() }, onAnswer = { received = it; true }, onSkip = { true })
            })
        }
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Specify your own"))
            .perform(androidx.test.espresso.action.ViewActions.click())
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Custom answer"))
            .perform(androidx.test.espresso.action.ViewActions.replaceText("My tablet"), androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
        assertNull(received)
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Answer"))
            .perform(androidx.test.espresso.action.ViewActions.click())
        assertEquals("My tablet", received)
    }
}

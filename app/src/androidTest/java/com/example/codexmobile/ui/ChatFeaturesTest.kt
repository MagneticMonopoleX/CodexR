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

    @Test fun storageControlsExposeActionsAndDisableThemDuringWork() {
        var compactions = 0
        var exports = 0
        var imports = 0
        val enabled = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { MaterialTheme {
            ChatStorageControls("Full history saved locally · auto compact on", enabled.value, true,
                { compactions++ }, { exports++ }, { imports++ })
        } }
        compose.onNodeWithText("Compact now").performClick()
        compose.onNodeWithText("Export chat").performClick()
        compose.onNodeWithText("Import chat").performClick()
        assertEquals(1, compactions)
        assertEquals(1, exports)
        assertEquals(1, imports)
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("Compact now").assertIsNotEnabled()
        compose.onNodeWithText("Export chat").assertIsNotEnabled()
        compose.onNodeWithText("Import chat").assertIsNotEnabled()
    }

    @Test fun exportTargetsChosenChatWithoutSelectingOrDeletingIt() {
        val active = ChatSession.create().copy(title = "Active chat")
        val other = ChatSession.create().copy(title = "Other chat")
        var selected = active.id
        var exported: String? = null
        var deleted: String? = null
        val enabled = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Column {
            listOf(active, other).forEach { session ->
                SessionListItem(session, session.id == selected, "Model", enabled.value,
                    { selected = it }, { exported = it }, { deleted = it })
            }
        } } }
        compose.onNodeWithTag("chat-actions-${other.id}").performClick()
        compose.onNodeWithTag("chat-export-${other.id}").performClick()
        assertEquals(other.id, exported)
        assertEquals(active.id, selected)
        assertNull(deleted)
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("chat-actions-${other.id}").assertIsNotEnabled()
    }
}

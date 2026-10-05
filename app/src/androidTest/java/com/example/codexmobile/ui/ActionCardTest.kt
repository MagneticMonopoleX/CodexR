package com.example.codexmobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.codexmobile.api.ChatMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionCardTest {
    @get:Rule val compose = createComposeRule()
    private val call = ChatMessage("assistant", "Requested root command:\n```bash\nid\n```",
        id = "action", kind = "tool_call", toolCallId = "call", toolName = "exec_command",
        toolArguments = "{\"cmd\":\"id\"}")

    @Test fun collapsedByDefaultWithCopyAndTapToExpand() {
        compose.setContent { MaterialTheme { ActionCard(chatDisplayRows(listOf(call)).single()) } }
        compose.onNodeWithTag("action-details-action").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy action").assertExists()
        compose.onNodeWithTag("action-header-action").performClick()
        compose.onNodeWithTag("action-details-action").assertExists()
        compose.onNodeWithTag("action-header-action").performClick()
        compose.onNodeWithTag("action-details-action").assertDoesNotExist()
    }

    @Test fun expansionIsPreservedWhenResultArrives() {
        val messages = mutableStateOf(listOf(call))
        compose.setContent { MaterialTheme { ActionCard(chatDisplayRows(messages.value).single()) } }
        compose.onNodeWithTag("action-header-action").performClick()
        compose.runOnIdle {
            messages.value = listOf(call, ChatMessage("user", "exit_code: 0", kind = "tool_result", toolCallId = "call"))
        }
        compose.onNodeWithTag("action-details-action").assertExists()
    }
}

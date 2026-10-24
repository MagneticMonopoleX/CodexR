package com.example.codexmobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.codexmobile.data.ChatSession
import com.example.codexmobile.api.AgentQuestion

@Composable
internal fun AgentQuestionDialog(question: AgentQuestion, onAnswer: (String) -> Unit, onSkip: () -> Unit) {
    var customAnswer by rememberSaveable(question.callId) { mutableStateOf("") }
    var selectedOption by rememberSaveable(question.callId) { mutableStateOf<Int?>(null) }
    val answer = selectedOption?.let { question.options.getOrNull(it) } ?: customAnswer
    AlertDialog(
        onDismissRequest = {},
        title = { Text("CodexR has a question") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text(question.question)
                question.options.forEachIndexed { index, option ->
                    Row(Modifier.fillMaxWidth().clickable { selectedOption = index },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = selectedOption == index, onClick = { selectedOption = index })
                        Text(option, Modifier.weight(1f).padding(vertical = 8.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { selectedOption = -1 },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = selectedOption == -1, onClick = { selectedOption = -1 })
                    Text("Specify your own")
                }
                OutlinedTextField(value = customAnswer, onValueChange = { customAnswer = it.take(8_000); selectedOption = -1 },
                    label = { Text("Your answer") }, supportingText = { Text("Choose an option or specify your own. Nothing is sent until you tap Answer.") },
                    modifier = Modifier.fillMaxWidth().testTag("question-answer"))
            }
        },
        confirmButton = { Button(onClick = { onAnswer(answer) }, enabled = selectedOption != null && answer.isNotBlank()) { Text("Answer") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
    )
}

@Composable
internal fun ChatSearchDialog(sessions: List<ChatSession>, canOpen: Boolean,
    onOpen: (ChatSearchHit) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(sessions, query) { searchChats(sessions, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search chats") },
        text = {
            Column {
                OutlinedTextField(query, { query = it.take(300) }, label = { Text("Search titles and messages") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("chat-search-query"))
                Text(if (query.isBlank()) "Search stays on this device."
                    else if (hits.isEmpty()) "No matches" else "${hits.size} matches${if (hits.size == 100) " (first 100)" else ""}",
                    style = MaterialTheme.typography.bodySmall)
                if (!canOpen) Text("Stop the current response before opening another result.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(hits) { index, hit ->
                        Column(Modifier.fillMaxWidth().testTag("chat-search-hit-$index")
                            .clickable(enabled = canOpen) { onOpen(hit) }.padding(vertical = 10.dp)) {
                            Text(hit.title, style = MaterialTheme.typography.titleSmall)
                            Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

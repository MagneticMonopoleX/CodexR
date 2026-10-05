package com.example.codexmobile.ui

import com.example.codexmobile.api.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ChatDisplayRow(val messages: List<ChatMessage>, val isAction: Boolean) {
    val id: String get() = messages.first().id
    val title: String get() {
        val call = messages.first()
        if (call.toolName == "capture_screen" || call.kind.startsWith("capture_")) return "Screen capture"
        val command = shellCommand(call)
        return command?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(90)
            ?: call.toolName ?: "Command / action"
    }
    val status: String get() {
        val result = messages.lastOrNull { it.kind in resultKinds }
        if (result == null) return "Requested"
        val firstLine = result.content.lineSequence().firstOrNull().orEmpty()
        return when {
            firstLine.contains("stopped", true) || firstLine.contains("interrupted", true) -> "Stopped"
            firstLine.startsWith("User denied", true) -> "Denied"
            firstLine.startsWith("Tool failed", true) || firstLine.contains("could not be confirmed", true) -> "Failed"
            else -> Regex("(?m)^exit_code: (-?\\d+)\\s*$").find(result.content)?.groupValues?.get(1)?.let {
                if (it == "0") "Completed" else "Failed · exit $it"
            } ?: "Result"
        }
    }
    val needsAttention: Boolean get() = status.startsWith("Failed") || status in setOf("Stopped", "Denied")
}

private val callKinds = setOf("tool_call", "capture_call")
private val resultKinds = setOf("tool_result", "capture_result", "tool")

private fun shellCommand(message: ChatMessage): String? = runCatching {
    Json.parseToJsonElement(message.toolArguments.orEmpty()).jsonObject["cmd"]?.jsonPrimitive?.contentOrNull
}.getOrNull() ?: Regex("```(?:bash|sh)\\s*\\r?\\n(.*?)```",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    .find(message.content)?.groupValues?.get(1)?.trim()

/** Group only adjacent matching calls/results; never move a result across a human prompt. */
fun chatDisplayRows(messages: List<ChatMessage>): List<ChatDisplayRow> {
    val visible = messages.filterNot { it.kind == "reasoning" }
    val rows = mutableListOf<ChatDisplayRow>()
    var index = 0
    while (index < visible.size) {
        val current = visible[index]
        val next = visible.getOrNull(index + 1)
        val paired = (
            current.kind in callKinds && current.toolCallId != null && next != null &&
                next.kind in resultKinds && next.toolCallId == current.toolCallId
        )
        // Historical shell calls were assistant Markdown followed by an unstructured tool result.
        val legacyPair = (
            current.role == "assistant" && current.kind == "message" && next?.kind == "tool" &&
                shellCommand(current) != null
        )
        val convertedLegacyCall = (
            current.role == "assistant" && current.kind == "message" &&
                next?.kind == "tool_call" && next.toolName == "exec_command" && shellCommand(current) != null &&
                shellCommand(current) == shellCommand(next)
        )
        if (convertedLegacyCall) {
            val result = visible.getOrNull(index + 2)?.takeIf {
                it.kind in resultKinds && next.toolCallId != null && it.toolCallId == next.toolCallId
            }
            rows += ChatDisplayRow(listOfNotNull(current, next, result), true)
            index += if (result != null) 3 else 2
        } else if (paired || legacyPair) {
            rows += ChatDisplayRow(listOf(current, next), true)
            index += 2
        } else {
            rows += ChatDisplayRow(listOf(current), current.kind in callKinds || current.kind in resultKinds)
            index++
        }
    }
    return rows
}

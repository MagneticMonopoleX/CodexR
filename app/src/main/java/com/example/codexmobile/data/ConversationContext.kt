package com.example.codexmobile.data

import com.example.codexmobile.api.ChatMessage
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Model context is a disposable checkpoint. The transcript is always the source of truth. */
@Serializable
data class ContextCheckpoint(
    val input: JsonArray,
    val coveredMessages: Int,
    val prefixHash: String,
    val modelId: String,
    val authScope: String,
    val method: String = "native",
    val createdAt: Long = System.currentTimeMillis(),
)

data class CompactedContext(val input: JsonArray, val method: String = "native")

object ConversationContext {
    // Conservative fallback when the provider does not advertise a context window.
    const val DEFAULT_CONTEXT_WINDOW = 128_000L
    private const val TOKEN_RESERVE = 16_000L
    private val json = Json { encodeDefaults = true }

    fun fingerprint(messages: List<ChatMessage>): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(messages).toByteArray()).joinToString("") { "%02x".format(it) }

    fun validCheckpoint(session: ChatSession, scope: String): ContextCheckpoint? = session.contextCheckpoint?.takeIf {
        it.modelId == session.modelId && it.authScope == scope && it.coveredMessages in 0..session.messages.size &&
            it.prefixHash == fingerprint(session.messages.take(it.coveredMessages))
    }

    fun requestMessages(session: ChatSession, scope: String): List<ChatMessage> {
        val checkpoint = validCheckpoint(session, scope)
        val tail = session.messages.drop(checkpoint?.coveredMessages ?: 0)
            .filter { it.kind != "partial" && it.includeInContext }
        return if (checkpoint == null) tail else listOf(ChatMessage("assistant", checkpoint.input.toString(),
            id = "context-checkpoint", kind = "context_window")) + tail
    }

    /** A UTF-8 byte estimate is deliberately conservative for non-English text and code. */
    fun estimateTokens(messages: List<ChatMessage>): Long = messages.sumOf { message ->
        val text = message.content.toByteArray().size.toLong() + (message.toolArguments?.toByteArray()?.size ?: 0)
        // Opaque reasoning/compaction data is overestimated; image data is not counted as base64 text.
        val textTokens = if (message.kind == "context_window") {
            val input = json.parseToJsonElement(message.content) as JsonArray
            input.sumOf { item ->
                val content = (item as? JsonObject)?.get("content")
                if (content is JsonArray) content.sumOf { part ->
                    if ((part as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "input_image") 4_096L
                    else part.toString().toByteArray().size.toLong() / 3 + 16
                } else item.toString().toByteArray().size.toLong() / 3 + 16
            }
        } else text / 3
        textTokens + 16 + message.attachments.sumOf {
            if (it.mimeType.startsWith("image/")) 4_096L else (File(it.localPath).length() + 2) / 3
        }
    }

    fun threshold(window: Long?): Long {
        val size = window?.takeIf { it >= 8_192 } ?: DEFAULT_CONTEXT_WINDOW
        return minOf(size * 70 / 100, size - minOf(TOKEN_RESERVE, size / 4))
    }

    fun shouldCompact(session: ChatSession, scope: String, window: Long?): Boolean {
        val checkpoint = validCheckpoint(session, scope)
        // A checkpoint covering all current messages must not compact itself repeatedly.
        if (checkpoint?.coveredMessages == session.messages.size) return false
        val estimate = estimateTokens(requestMessages(session, scope))
        val added = session.messages.drop(session.usageMessageCount.coerceIn(0, session.messages.size))
        val observed = session.lastInputTokens?.takeIf {
            session.usageModelId == session.modelId && session.usageAuthScope == scope &&
                session.usageCheckpointAt == checkpoint?.createdAt
        }?.plus(estimateTokens(added)) ?: 0
        if (checkpoint != null && observed < threshold(window) &&
            estimateTokens(session.messages.drop(checkpoint.coveredMessages)) < maxOf(2_048, threshold(window) / 10)) return false
        return maxOf(estimate, observed) >= threshold(window)
    }

    fun checkpoint(session: ChatSession, scope: String, result: CompactedContext) = ContextCheckpoint(
        input = result.input, coveredMessages = session.messages.size, prefixHash = fingerprint(session.messages),
        modelId = session.modelId, authScope = scope, method = result.method,
    )
}

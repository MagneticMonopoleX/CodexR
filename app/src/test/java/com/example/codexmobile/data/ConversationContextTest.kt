package com.example.codexmobile.data

import com.example.codexmobile.api.ChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ConversationContextTest {
    private val compacted = Json.parseToJsonElement("""[{"type":"message","role":"user","content":"retained"},{"type":"compaction","encrypted_content":"opaque-state"}]""").let {
        CompactedContext(it as kotlinx.serialization.json.JsonArray)
    }

    @Test fun `compaction keeps canonical window and sends only new messages without altering history`() {
        val old = listOf(ChatMessage("user", "Inspect"), ChatMessage("assistant", "Done"))
        val original = ChatSession.create().copy(messages = old)
        val checkpoint = ConversationContext.checkpoint(original, "account", compacted)
        val next = ChatMessage("user", "Continue")
        val session = original.copy(contextCheckpoint = checkpoint, messages = old + next)
        val context = ConversationContext.requestMessages(session, "account")
        assertEquals(listOf("context_window", "message"), context.map { it.kind })
        assertEquals(compacted.input.toString(), context.first().content)
        assertEquals(next, context.last())
        assertEquals(old + next, session.messages)
        val restored = Json.decodeFromString<ChatSession>(Json.encodeToString(session))
        assertEquals(context.map { it.content }, ConversationContext.requestMessages(restored, "account").map { it.content })
    }

    @Test fun `edits model changes and account changes invalidate checkpoint`() {
        val initial = ChatSession.create().copy(messages = listOf(ChatMessage("user", "Original")))
        val session = initial.copy(contextCheckpoint = ConversationContext.checkpoint(initial, "account", compacted))
        assertNotNull(ConversationContext.validCheckpoint(session, "account"))
        assertNull(ConversationContext.validCheckpoint(session, "other"))
        assertNull(ConversationContext.validCheckpoint(session.copy(modelId = "another-model"), "account"))
        assertNull(ConversationContext.validCheckpoint(session.copy(messages = listOf(session.messages.single().copy(content = "Edited"))), "account"))
        assertNull(ConversationContext.validCheckpoint(session.copy(messages = emptyList()), "account"))
    }

    @Test fun `automatic trigger uses model budget and observed usage without compacting the same checkpoint twice`() {
        val initial = ChatSession.create().copy(messages = listOf(ChatMessage("user", "x".repeat(21_000))))
        assertTrue(ConversationContext.shouldCompact(initial, "account", 8_192))
        val session = initial.copy(contextCheckpoint = ConversationContext.checkpoint(initial, "account", compacted))
        assertFalse(ConversationContext.shouldCompact(session, "account", 8_192))
        val next = session.copy(messages = session.messages + ChatMessage("user", "Next"))
        assertFalse(ConversationContext.shouldCompact(next, "account", 8_192))
        assertTrue(ConversationContext.shouldCompact(next.copy(lastInputTokens = 6_000, usageMessageCount = 1,
            usageModelId = next.modelId, usageAuthScope = "account", usageCheckpointAt = next.contextCheckpoint!!.createdAt), "account", 8_192))
    }

    @Test fun `imported opaque reasoning and interrupted partial text are not model input`() {
        val user = ChatMessage("user", "Hello")
        val session = ChatSession.create().copy(messages = listOf(user,
            ChatMessage("assistant", "opaque", kind = "reasoning", includeInContext = false),
            ChatMessage("assistant", "incomplete", kind = "partial")))
        assertEquals(listOf(user), ConversationContext.requestMessages(session, "account"))
        assertEquals(3, session.messages.size)
    }
}

package com.example.codexmobile.ui

import com.example.codexmobile.api.ChatMessage
import com.example.codexmobile.data.ChatSession
import org.junit.Assert.*
import org.junit.Test

class ChatSearchTest {
    @Test fun `search matches titles messages and tool output but not hidden reasoning`() {
        val session = ChatSession.create().copy(title = "Network settings", messages = listOf(
            ChatMessage("assistant", "Network is online", id = "reply"),
            ChatMessage("user", "NETWORK device", id = "output", kind = "tool_result"),
            ChatMessage("assistant", "network secret", kind = "reasoning")))
        val hits = searchChats(listOf(session), " network ")
        assertEquals(listOf(null, "reply", "output"), hits.map { it.messageId })
        assertEquals(session.id, hits.last().sessionId)
        assertTrue(searchChats(listOf(session), "").isEmpty())
        assertTrue(searchChats(listOf(session), "net.*").isEmpty())
    }

    @Test fun `search is bounded and shows matching context in recent chats first`() {
        val older = ChatSession.create(now = 1).copy(title = "Old", messages = listOf(ChatMessage("user", "needle")))
        val newer = ChatSession.create(now = 2).copy(title = "New", messages = List(120) {
            ChatMessage("assistant", "x".repeat(600) + "needle" + "y".repeat(600)) })
        val hits = searchChats(listOf(older, newer), "needle")
        assertEquals(100, hits.size)
        assertEquals(newer.id, hits.first().sessionId)
        assertTrue(hits.first().snippet.contains("needle"))
        assertTrue(hits.first().snippet.length < 200)
    }

    @Test fun `title matches also count toward the result limit`() {
        val sessions = List(60) { ChatSession.create(now = it.toLong()).copy(title = "match",
            messages = listOf(ChatMessage("user", "match"))) }
        assertEquals(100, searchChats(sessions, "match").size)
    }
}

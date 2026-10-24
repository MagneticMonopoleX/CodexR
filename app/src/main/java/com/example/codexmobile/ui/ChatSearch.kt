package com.example.codexmobile.ui

import com.example.codexmobile.data.ChatSession

data class ChatSearchHit(val sessionId: String, val messageId: String?, val title: String, val snippet: String)

/** Literal, local-only search. Never includes hidden reasoning or sends history to a server. */
fun searchChats(sessions: List<ChatSession>, query: String): List<ChatSearchHit> {
    val term = query.trim().take(300)
    if (term.isEmpty()) return emptyList()
    return buildList {
        for (session in sessions.sortedByDescending { it.updatedAt }) {
            if (session.title.contains(term, ignoreCase = true)) {
                add(ChatSearchHit(session.id, null, session.title, "Chat title"))
                if (size >= 100) return@buildList
            }
            for (message in session.messages) {
                if (message.kind == "reasoning") continue
                val at = message.content.indexOf(term, ignoreCase = true)
                if (at < 0) continue
                val start = (at - 50).coerceAtLeast(0)
                val snippet = (if (start > 0) "…" else "") +
                    message.content.substring(start, (at + term.length + 100).coerceAtMost(message.content.length))
                        .replace(Regex("\\s+"), " ")
                add(ChatSearchHit(session.id, message.id, session.title, snippet))
                if (size >= 100) return@buildList
            }
            if (size >= 100) return@buildList
        }
    }
}

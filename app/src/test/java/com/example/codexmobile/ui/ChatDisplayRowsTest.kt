package com.example.codexmobile.ui

import com.example.codexmobile.api.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class ChatDisplayRowsTest {
    private fun call(id: String = "call1") = ChatMessage("assistant", "Command", kind = "tool_call",
        toolCallId = id, toolName = "exec_command", toolArguments = "{\"cmd\":\"ls -la\\npwd\"}")
    private fun result(id: String = "call1", content: String = "Command execution result:\nexit_code: 0\nstdout:\nhello") =
        ChatMessage("user", content, kind = "tool_result", toolCallId = id)

    @Test fun `adjacent call and output share one stable action row`() {
        val call = call()
        val pending = chatDisplayRows(listOf(call)).single()
        val complete = chatDisplayRows(listOf(call, result())).single()
        assertTrue(complete.isAction)
        assertEquals(pending.id, complete.id)
        assertEquals(2, complete.messages.size)
        assertEquals("ls -la", complete.title)
        assertEquals("Requested", pending.status)
        assertEquals("Completed", complete.status)
    }

    @Test fun `does not merge unrelated ids or across a user message`() {
        assertEquals(2, chatDisplayRows(listOf(call(), result("other"))).size)
        val rows = chatDisplayRows(listOf(call(), ChatMessage("user", "Wait"), result()))
        assertEquals(3, rows.size)
        assertFalse(rows[1].isAction)
    }

    @Test fun `normal replies remain visible and hidden reasoning does not break pairing`() {
        val rows = chatDisplayRows(listOf(ChatMessage("user", "Inspect"), call(),
            ChatMessage("assistant", "{}", kind = "reasoning"), result(), ChatMessage("assistant", "Done")))
        assertEquals(3, rows.size)
        assertEquals(listOf(false, true, false), rows.map { it.isAction })
    }

    @Test fun `legacy shell output and screenshots also fold`() {
        val legacy = chatDisplayRows(listOf(ChatMessage("assistant", "```bash\nid\n```"),
            ChatMessage("user", "exit_code: 0", kind = "tool"))).single()
        assertTrue(legacy.isAction)
        assertEquals("id", legacy.title)
        val capture = chatDisplayRows(listOf(ChatMessage("assistant", "Capture", kind = "capture_call", toolCallId = "screen"),
            ChatMessage("user", "Captured", kind = "capture_result", toolCallId = "screen"))).single()
        assertTrue(capture.isAction)
        assertEquals("Screen capture", capture.title)
        assertTrue(chatDisplayRows(listOf(result())).single().isAction)
    }

    @Test fun `failures denial and interruption remain visible while folded`() {
        listOf("exit_code: 4" to "Failed · exit 4", "Tool failed: network" to "Failed",
            "User denied this action." to "Denied", "User stopped this tool immediately." to "Stopped",
            "This tool call was interrupted by the user or app" to "Stopped").forEach { (content, expected) ->
            val row = chatDisplayRows(listOf(call(), result(content = content))).single()
            assertEquals(expected, row.status)
            assertTrue(row.needsAttention)
        }
    }

    @Test fun `stdout mentioning failure does not change successful status`() {
        assertEquals("Completed", chatDisplayRows(listOf(call(), result(content =
            "Command execution result:\nexit_code: 0\nstdout:\nUser denied this action. stopped failed"))).single().status)
    }

    @Test fun `converted legacy command is not duplicated outside the folded card`() {
        val call = call()
        val markdown = ChatMessage("assistant", "```bash\nls -la\npwd\n```")
        val row = chatDisplayRows(listOf(markdown, call, result())).single()
        assertTrue(row.isAction)
        assertEquals(3, row.messages.size)
        assertEquals("Completed", row.status)
    }
}

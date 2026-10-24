package com.example.codexmobile.api

import java.io.StringReader
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SearchAndQuestionTest {
    @Test fun `web search is opt in and clarification tool is always available`() {
        val off = AIClient.buildRequestBody(emptyList())["tools"]!!.jsonArray
        assertFalse(off.any { it.jsonObject["type"]?.jsonPrimitive?.content == "web_search" })
        assertTrue(off.any { it.jsonObject["name"]?.jsonPrimitive?.content == "ask_user" })
        val on = AIClient.buildRequestBody(listOf(ChatMessage("assistant", "search status", kind = "web_search")),
            webSearchEnabled = true)
        assertTrue(on["tools"]!!.jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "web_search" })
        assertTrue(on["input"]!!.jsonArray.isEmpty())
    }

    @Test fun `native search output and citations are deduplicated and clickable`() {
        val item = """{"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search","queries":["Android docs"]}}"""
        val message = """{"type":"message","content":[{"type":"output_text","text":"Android docs","annotations":[{"type":"url_citation","url":"https://example.com/docs","title":"Docs","start_index":0,"end_index":12},{"type":"url_citation","url":"javascript:bad","title":"Bad"}]}]}"""
        val result = AIClient.parseEventStream(StringReader(
            "data: {\"type\":\"response.output_item.done\",\"item\":$item}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"output\":[$item,$message]}}\n\n").buffered())
        assertEquals(listOf("Web search completed: Android docs"), result.searches)
        assertTrue(result.text.contains("Android docs [1](<https://example.com/docs>)"))
        assertTrue(result.text.contains("[Docs](<https://example.com/docs>)"))
        assertFalse(result.text.contains("javascript:"))
    }

    @Test fun `clarification options may be empty but invalid questions are rejected`() {
        assertEquals(emptyList<String>(), AgentQuestion.parse("q", """{"question":"Which device?","options":[]}""").options)
        listOf("""{"question":"","options":[]}""", """{"question":"Choose","options":["A","A"]}""",
            """{"question":"Choose","options":[1]}""").forEach { args ->
            assertThrows(CodexProtocolException::class.java) { AgentQuestion.parse("q", args) }
        }
    }

    @Test fun `question stream becomes an ordinary paired function call`() {
        val args = """{"question":"Which device?","options":["Tablet","Phone"]}"""
        val item = buildJsonObject {
            put("type", "function_call"); put("name", "ask_user"); put("call_id", "question_1"); put("arguments", args)
        }
        val result = AIClient.parseEventStream(StringReader(
            "data: {\"type\":\"response.completed\",\"response\":{\"output\":[$item]}}\n\n").buffered())
        assertEquals("ask_user", result.toolCalls.single().name)
        assertEquals("question_1", result.toolCalls.single().callId)
    }

    @Test fun `citation markers and unsafe markdown in source titles are handled`() {
        val result = renderSearchCitations("Claim \uE200cite\uE202turn0search0\uE201", mapOf("https://example.com/(x)" to "[title]\nnew"))
        assertFalse(result.contains("\uE200"))
        assertTrue(result.contains("[\\[title\\] new](<https://example.com/(x)>)"))
    }
}

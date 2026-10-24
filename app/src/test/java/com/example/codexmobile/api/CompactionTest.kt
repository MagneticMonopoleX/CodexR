package com.example.codexmobile.api

import java.io.StringReader
import kotlinx.serialization.json.*
import com.example.codexmobile.data.ReasoningLevel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CompactionTest {
    @Test fun `native compact output is carried forward intact before new input`() {
        val text = """{"output":[{"type":"message","role":"user","content":[{"type":"input_text","text":"Retained user prompt"}]},{"type":"compaction","id":"cmp_1","encrypted_content":"opaque"}]}"""
        val window = AIClient.parseCompactionOutput(text)
        val input = AIClient.buildRequestBody(listOf(ChatMessage("system", "Current instructions"),
            ChatMessage("assistant", window.toString(), kind = "context_window"), ChatMessage("user", "Next")))["input"]!!.jsonArray
        assertEquals(window, JsonArray(input.take(2)))
        assertEquals("Next", input.last().jsonObject["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
        assertThrows(CodexProtocolException::class.java) { AIClient.parseCompactionOutput("""{"output":[]}""") }
    }

    @Test fun `stream reports actual input usage for automatic compaction`() {
        val stream = "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":12345},\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Done\"}]}]}}\n\n"
        assertEquals(12345L, AIClient.parseEventStream(StringReader(stream).buffered()).inputTokens)
    }

    @Test fun `native endpoint is stateless and preserves its complete output`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"output":[{"type":"message","role":"user","content":"Keep this"},{"type":"compaction","encrypted_content":"opaque"}]}"""))
            server.start()
            val result = AIClient.sendCompaction(AuthCredential.ApiKey("test-only"), listOf(ChatMessage("user", "Old history")),
                "test-model", ReasoningLevel.LOW, server.url("/responses").toString())
            assertEquals("native", result.method)
            assertEquals(2, result.input.size)
            val request = server.takeRequest()
            assertEquals("/responses/compact", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(setOf("model", "instructions", "input"), body.keys)
        }
    }

    @Test fun `unsupported endpoint falls back to a tool-free summary while rate errors remain failures`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"Unsupported endpoint"}}"""))
            server.enqueue(MockResponse().setBody("data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Current goal and completed work\"}]}]}}\n\n"))
            server.start()
            val result = AIClient.sendCompaction(AuthCredential.ApiKey("test-only"), listOf(ChatMessage("user", "Old history")),
                "test-model", ReasoningLevel.LOW, server.url("/responses").toString())
            assertEquals("summary", result.method)
            assertTrue(result.input.toString().contains("Current goal"))
            assertEquals("/responses/compact", server.takeRequest().path)
            val fallback = server.takeRequest()
            assertEquals("/responses", fallback.path)
            val body = Json.parseToJsonElement(fallback.body.readUtf8()).jsonObject
            assertTrue(body["tools"]!!.jsonArray.isEmpty())
            assertEquals("none", body["tool_choice"]!!.jsonPrimitive.content)
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Rate limited"}}"""))
            try {
                AIClient.sendCompaction(AuthCredential.ApiKey("test-only"), emptyList(), "test-model", ReasoningLevel.LOW,
                    server.url("/responses").toString())
                fail("Expected rate-limit error")
            } catch (error: CodexHttpException) { assertEquals(429, error.statusCode) }
            assertEquals(3, server.requestCount)
        }
    }
}

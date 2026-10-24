package com.example.codexmobile.ui

import android.app.Application
import com.example.codexmobile.ShellCommandResult
import com.example.codexmobile.api.ChatMessage
import com.example.codexmobile.api.CodexResponse
import com.example.codexmobile.api.CodexToolCall
import com.example.codexmobile.api.MessageAttachment
import com.example.codexmobile.data.FALLBACK_CODEX_MODEL_OPTIONS
import com.example.codexmobile.data.ReasoningLevel
import com.example.codexmobile.data.CompactedContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationBehaviorTest {
    private lateinit var runtime: FakeRuntime
    private lateinit var controller: ChatViewModel

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        runtime = FakeRuntime()
        controller = ChatViewModel(RuntimeEnvironment.getApplication(), runtime, Dispatchers.Unconfined)
        withTimeout(5_000) { controller.isReady.first { it } }
        Unit
    }

    @After fun teardown() = runBlocking {
        if (::controller.isInitialized) {
            controller.close()?.join()
        }
        Dispatchers.resetMain()
    }

    @Test fun `queued followup goes after tool output and before next request`() = runBlocking {
        val rootGate = CompletableDeferred<ShellCommandResult>()
        runtime.replies += CodexResponse("", listOf(CodexToolCall("call_1", "exec_command", "{\"cmd\":\"printf hello\"}")))
        runtime.replies += CodexResponse("Done")
        runtime.root = { rootGate.await() }
        controller.sendMessage("Run it")
        assertNotNull(controller.pendingCommand.value)
        controller.approveCommand()
        assertTrue(controller.isTyping.value)
        controller.sendMessage("Now explain the output")
        assertEquals(1, controller.queuedMessages.value.size)
        rootGate.complete(ShellCommandResult(0, "hello", ""))
        withTimeout(5_000) { controller.isTyping.first { !it } }
        val next = runtime.requests.last()
        assertTrue(next[next.lastIndex - 1].content.contains("stdout:"))
        assertEquals("Now explain the output", next.last().content)
        assertTrue(controller.queuedMessages.value.isEmpty())
        assertEquals(1, runtime.executions)
    }

    @Test fun `stop cancels root work and retry does not directly replay it`() = runBlocking {
        runtime.replies += CodexResponse("", listOf(CodexToolCall("call_1", "exec_command", "{\"cmd\":\"sleep 100\"}")))
        runtime.replies += CodexResponse("Stopped safely")
        var cancelled = false
        runtime.root = { try { awaitCancellation() } finally { cancelled = true } }
        controller.sendMessage("Run a long command")
        controller.approveCommand()
        controller.stopExecution()
        withTimeout(5_000) { controller.isTyping.first { !it } }
        assertTrue(cancelled)
        assertTrue(controller.canRetry.value)
        val interrupted = controller.messages.value.single { it.kind == "tool_result" }
        assertEquals("call_1", interrupted.toolCallId)
        assertTrue(interrupted.content.contains("stopped", ignoreCase = true))
        controller.retryResponse()
        assertEquals(1, runtime.executions)
        assertTrue(runtime.requests.last().any { it.kind == "tool_result" && it.toolCallId == "call_1" })
        assertEquals("Stopped safely", controller.messages.value.last().content)
    }

    @Test fun `stop at approval never executes and prompt is editable`() {
        runtime.replies += CodexResponse("", listOf(CodexToolCall("call_1", "exec_command", "{\"cmd\":\"echo original\"}")))
        runtime.replies += CodexResponse("Revised response")
        controller.sendMessage("Original")
        val id = controller.messages.value.first().id
        controller.stopExecution()
        assertEquals(0, runtime.executions)
        assertNull(controller.pendingCommand.value)
        controller.editMessage(id, "Revised", emptyList())
        assertEquals(listOf("Revised", "Revised response"), controller.messages.value.map { it.content })
    }

    @Test fun `session permission is isolated and revocable`() {
        runtime.replies += listOf(
            CodexResponse("", listOf(CodexToolCall("call_1", "exec_command", "{\"cmd\":\"echo one\"}"))),
            CodexResponse("", listOf(CodexToolCall("call_2", "exec_command", "{\"cmd\":\"echo two\"}"))),
            CodexResponse("Done"),
        )
        controller.sendMessage("Inspect")
        val firstSession = controller.activeSessionId.value!!
        controller.approveCommand(forSession = true)
        assertEquals(2, runtime.executions)
        assertTrue(controller.sessionRootAllowed.value)
        controller.createSession()
        assertFalse(controller.sessionRootAllowed.value)
        runtime.replies += CodexResponse("", listOf(CodexToolCall("call_3", "exec_command", "{\"cmd\":\"echo third\"}")))
        controller.sendMessage("Inspect again")
        assertNotNull(controller.pendingCommand.value)
        assertEquals(2, runtime.executions)
        controller.stopExecution()
        controller.selectSession(firstSession)
        assertTrue(controller.sessionRootAllowed.value)
        controller.revokeSessionRoot()
        assertFalse(controller.sessionRootAllowed.value)
    }

    @Test fun `failed response can retry without duplicating the user prompt`() {
        runtime.failure = IllegalStateException("Connection dropped")
        controller.sendMessage("Hello")
        assertTrue(controller.canRetry.value)
        assertEquals("Connection dropped", controller.errorMessage.value)
        runtime.failure = null
        runtime.replies += CodexResponse("Hello back")
        controller.retryResponse()
        assertEquals(1, controller.messages.value.count { it.role == "user" })
        assertEquals("Hello back", controller.messages.value.last().content)
        assertFalse(controller.canRetry.value)
    }

    @Test fun `AI screen capture returns an attachment and uses root approval`() = runBlocking {
        runtime.replies += CodexResponse("", listOf(CodexToolCall("call_capture", "capture_screen", "{}")))
        runtime.replies += CodexResponse("I can see the screen")
        controller.sendMessage("What is on the screen?")
        assertTrue(controller.pendingCommand.value!!.contains("capture_screen"))
        assertEquals(0, runtime.captures)
        controller.approveCommand()
        withTimeout(5_000) { controller.isTyping.first { !it } }
        assertEquals(1, runtime.captures)
        val output = runtime.requests.last().single { it.kind == "tool_result" }
        assertEquals("call_capture", output.toolCallId)
        assertEquals(1, output.attachments.size)
    }

    @Test fun `followup interrupts inference without waiting for completion`() = runBlocking {
        val gate = CompletableDeferred<CodexResponse>()
        runtime.responseGate = gate
        controller.sendMessage("First")
        runtime.responseGate = null
        runtime.replies += CodexResponse("Second answer")
        controller.sendMessage("Second")
        withTimeout(5_000) { controller.isTyping.first { !it } }
        assertEquals(listOf("First", "Second", "Second answer"), controller.messages.value.map { it.content })
        assertNull(controller.sessions.value.single { it.id == controller.activeSessionId.value }.lastError)
        gate.complete(CodexResponse("First answer"))
        assertFalse(controller.messages.value.any { it.content == "First answer" })
    }

    @Test fun `steering retains partial locally but excludes it from continuation`() = runBlocking {
        runtime.partial = "Unfinished answer"
        runtime.responseGate = CompletableDeferred()
        controller.sendMessage("First")
        runtime.partial = null
        runtime.responseGate = null
        runtime.replies += CodexResponse("Revised answer")
        controller.sendMessage("Change direction")
        withTimeout(5_000) { controller.isTyping.first { !it } }
        assertTrue(controller.messages.value.any { it.kind == "partial" && it.content == "Unfinished answer" })
        assertFalse(runtime.requests.last().any { it.content == "Unfinished answer" })
        assertEquals("Change direction", runtime.requests.last().last().content)
        assertEquals(0, runtime.executions)
    }

    @Test fun `stop after steering cancels continuation without another request`() = runBlocking {
        runtime.responseGate = CompletableDeferred()
        controller.sendMessage("First")
        runtime.responseGate = CompletableDeferred()
        controller.sendMessage("Second")
        assertEquals(2, runtime.requests.size)
        controller.stopExecution()
        withTimeout(5_000) { controller.isTyping.first { !it } }
        assertEquals(2, runtime.requests.size)
        assertTrue(controller.canRetry.value)
    }

    @Test fun `repeated steering keeps each user message exactly once and ignores stale callbacks`() = runBlocking {
        runtime.responseGate = CompletableDeferred()
        controller.sendMessage("First")
        val staleCallback = runtime.partialCallbacks.single()
        runtime.responseGate = CompletableDeferred()
        controller.sendMessage("Second")
        staleCallback("Late obsolete text")
        assertEquals("", controller.streamingText.value)
        runtime.responseGate = null
        runtime.replies += CodexResponse("Final")
        controller.sendMessage("Third")
        withTimeout(5_000) { controller.isTyping.first { !it } }
        assertEquals(listOf("First", "Second", "Third"), runtime.requests.last()
            .filter { it.role == "user" }.map { it.content })
        assertEquals(listOf("First", "Second", "Third", "Final"), controller.messages.value.map { it.content })
    }

    @Test fun `steering after a completed command never replays that command`() = runBlocking {
        runtime.replies += CodexResponse("", listOf(CodexToolCall("once", "exec_command", "{\"cmd\":\"printf done\"}")))
        controller.sendMessage("Run command")
        runtime.responseGate = CompletableDeferred()
        controller.approveCommand()
        assertEquals(1, runtime.executions)
        runtime.responseGate = null
        runtime.replies += CodexResponse("Explanation")
        controller.sendMessage("Explain instead")
        withTimeout(5_000) { controller.isTyping.first { !it } }
        assertEquals(1, runtime.executions)
        assertEquals(1, runtime.requests.last().count { it.kind == "tool_result" && it.toolCallId == "once" })
        assertEquals("Explain instead", runtime.requests.last().last().content)
    }

    @Test fun `question waits for explicit answer even in full access and resumes with paired result`() {
        controller.setFullAccessEnabled(true)
        runtime.replies += CodexResponse("", listOf(CodexToolCall("q1", "ask_user",
            """{"question":"Which device?","options":["Tablet","Phone"]}""")))
        runtime.replies += CodexResponse("Using your custom device")
        controller.sendMessage("Set up a device")
        assertTrue(controller.isTyping.value)
        assertEquals(listOf("Tablet", "Phone"), controller.pendingQuestion.value!!.options)
        assertNull(controller.pendingCommand.value)
        assertEquals(1, runtime.requests.size)
        assertEquals(0, runtime.executions)
        assertTrue(controller.answerQuestion("My own device"))
        assertNull(controller.pendingQuestion.value)
        assertFalse(controller.isTyping.value)
        val answer = runtime.requests.last().single { it.kind == "tool_result" }
        assertEquals("q1", answer.toolCallId)
        assertEquals("User answer: My own device", answer.content)
        assertFalse(controller.answerQuestion("Duplicate"))
    }

    @Test fun `stopping a question cancels its gate and never invents an answer`() {
        runtime.replies += CodexResponse("", listOf(CodexToolCall("q1", "ask_user",
            """{"question":"Continue?","options":["Yes","No"]}""")))
        controller.sendMessage("Help")
        controller.stopExecution()
        assertNull(controller.pendingQuestion.value)
        assertFalse(controller.answerQuestion("Yes"))
        assertEquals(1, runtime.requests.size)
        assertTrue(controller.canRetry.value)
        assertTrue(controller.messages.value.last().content.contains("without answering"))
        assertEquals(0, runtime.executions)
    }

    @Test fun `skip returns an explicit non-answer and continues without root consent`() {
        runtime.replies += CodexResponse("", listOf(CodexToolCall("q1", "ask_user",
            """{"question":"Which device?","options":["Tablet","Phone"]}""")))
        runtime.replies += CodexResponse("Continuing without that detail")
        controller.sendMessage("Help")
        assertTrue(controller.skipQuestion())
        assertNull(controller.pendingQuestion.value)
        assertFalse(controller.isTyping.value)
        assertFalse(controller.canRetry.value)
        val result = runtime.requests.last().single { it.kind == "tool_result" }
        assertEquals("q1", result.toolCallId)
        assertTrue(result.content.contains("User skipped"))
        assertTrue(result.content.contains("No answer or permission"))
        assertFalse(controller.skipQuestion())
        assertEquals(0, runtime.executions)
    }

    @Test fun `continue adds explicit resume instruction and keeps interrupted tool context`() {
        assertFalse(controller.continueResponse())
        runtime.replies += CodexResponse("", listOf(CodexToolCall("call_1", "exec_command", "{\"cmd\":\"echo hello\"}")))
        controller.sendMessage("Help")
        assertFalse(controller.continueResponse())
        controller.stopExecution()
        runtime.replies += CodexResponse("Resumed")
        assertTrue(controller.continueResponse())
        val request = runtime.requests.last()
        assertTrue(request.last().content.startsWith("Continue where you left off."))
        assertTrue(request.any { it.kind == "tool_result" && it.toolCallId == "call_1" })
        assertEquals(0, runtime.executions)
        assertFalse(controller.continueResponse())
    }

    @Test fun `web search preference is per chat and passed to runtime`() {
        controller.setWebSearchEnabled(true)
        val firstChat = controller.activeSessionId.value!!
        runtime.replies += CodexResponse("Searched", searches = listOf("Web search completed: docs"))
        controller.sendMessage("Find docs")
        assertTrue(runtime.webSearchFlags.last())
        assertEquals("web_search", controller.messages.value[1].kind)
        controller.createSession()
        assertFalse(controller.webSearchEnabled.value)
        controller.selectSession(firstChat)
        assertTrue(controller.webSearchEnabled.value)
    }

    @Test fun `shell examples in normal answers never execute as root`() {
        controller.setFullAccessEnabled(true)
        runtime.replies += CodexResponse("Example only:\n```bash\nreboot\n```")
        controller.sendMessage("Show an example")
        assertEquals(0, runtime.executions)
        assertFalse(controller.isTyping.value)
    }

    @Test fun `automatic compaction preserves all local messages and sends the checkpoint`() {
        val prompt = "x".repeat(21_000)
        runtime.replies += CodexResponse("Done")
        controller.sendMessage(prompt)
        assertEquals(1, runtime.compactions.size)
        assertTrue(runtime.requests.single().any { it.kind == "context_window" })
        assertFalse(runtime.requests.single().any { it.content == prompt })
        assertEquals(listOf(prompt, "Done"), controller.messages.value.map { it.content })
        assertNotNull(controller.sessions.value.single().contextCheckpoint)
        assertEquals(0, runtime.executions)
    }

    @Test fun `stopping compaction keeps the transcript and previous context intact`() = runBlocking {
        runtime.compactGate = CompletableDeferred()
        val prompt = "x".repeat(21_000)
        controller.sendMessage(prompt)
        assertTrue(controller.phase.value.contains("Compacting"))
        controller.stopExecution()
        withTimeout(5_000) { controller.isTyping.first { !it } }
        assertEquals(listOf(prompt), controller.messages.value.map { it.content })
        assertNull(controller.sessions.value.single().contextCheckpoint)
        assertTrue(runtime.requests.isEmpty())
        assertEquals(0, runtime.executions)
    }

    @Test fun `followup received during compaction reaches the next request boundary`() = runBlocking {
        val gate = CompletableDeferred<CompactedContext>()
        runtime.compactGate = gate
        controller.sendMessage("x".repeat(21_000))
        controller.sendMessage("Also check permissions")
        runtime.compactGate = null
        runtime.replies += CodexResponse("First reply")
        runtime.replies += CodexResponse("Followup reply")
        gate.complete(runtime.compacted)
        withTimeout(5_000) { controller.isTyping.first { !it } }
        assertEquals("Also check permissions", runtime.requests.last().last().content)
        assertTrue(controller.queuedMessages.value.isEmpty())
        assertEquals(0, runtime.executions)
    }

    @Test fun `compaction failure does not replace transcript and is retryable`() {
        runtime.compactFailure = IllegalStateException("Network unavailable")
        val prompt = "x".repeat(21_000)
        controller.sendMessage(prompt)
        assertEquals(listOf(prompt), controller.messages.value.map { it.content })
        assertNull(controller.sessions.value.single().contextCheckpoint)
        assertTrue(controller.canRetry.value)
        assertEquals(0, runtime.executions)
    }

    @Test fun `manual compaction preserves continue availability and editing discards stale checkpoint`() {
        runtime.replies += CodexResponse("", listOf(CodexToolCall("call_1", "exec_command", "{\"cmd\":\"id\"}")))
        controller.sendMessage("Inspect")
        controller.stopExecution()
        val before = controller.messages.value
        controller.compactNow()
        assertEquals(before, controller.messages.value)
        assertTrue(controller.canRetry.value)
        assertNotNull(controller.sessions.value.single().contextCheckpoint)
        runtime.replies += CodexResponse("Updated")
        controller.editMessage(before.first().id, "New task", emptyList())
        assertNull(controller.sessions.value.single().contextCheckpoint)
        assertFalse(runtime.requests.last().any { it.kind == "context_window" })
        assertEquals(0, runtime.executions)
    }

    private class FakeRuntime : ChatRuntime {
        val compactions = mutableListOf<List<ChatMessage>>()
        var compactGate: CompletableDeferred<CompactedContext>? = null
        var compactFailure: Exception? = null
        val compacted = CompactedContext(Json.parseToJsonElement("""[{"type":"compaction","encrypted_content":"state"}]""") as JsonArray)
        override suspend fun compact(messages: List<ChatMessage>, modelId: String, reasoningLevel: ReasoningLevel): CompactedContext {
            compactions += messages
            compactFailure?.let { throw it }
            return compactGate?.await() ?: compacted
        }
        val replies = ArrayDeque<CodexResponse>()
        val requests = mutableListOf<List<ChatMessage>>()
        val partialCallbacks = mutableListOf<(String) -> Unit>()
        val webSearchFlags = mutableListOf<Boolean>()
        var executions = 0
        var captures = 0
        var failure: Exception? = null
        var responseGate: CompletableDeferred<CodexResponse>? = null
        var partial: String? = null
        var root: suspend () -> ShellCommandResult = { ShellCommandResult(0, "ok", "") }
        override suspend fun respond(messages: List<ChatMessage>, modelId: String, reasoningLevel: ReasoningLevel,
            webSearchEnabled: Boolean, onPartial: (String) -> Unit): CodexResponse {
            requests += messages
            partialCallbacks += onPartial
            webSearchFlags += webSearchEnabled
            partial?.let(onPartial)
            failure?.let { throw it }
            return responseGate?.await() ?: replies.removeFirst()
        }
        override suspend fun execute(command: String): ShellCommandResult { executions++; return root() }
        override suspend fun capture(): List<MessageAttachment> { captures++; return listOf(MessageAttachment(name = "Screen", mimeType = "image/jpeg", localPath = "fake", sizeBytes = 1)) }
        override suspend fun models() = FALLBACK_CODEX_MODEL_OPTIONS.map { it.copy(contextWindow = 8_192) }
        override fun startBackground() {}
        override fun stopBackground() {}
    }
}

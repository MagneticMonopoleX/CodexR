package com.example.codexmobile.api

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.codexmobile.data.*
import com.example.codexmobile.ui.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live inference, production controller, separate disposable transcript; no shell tools. */
@RunWith(AndroidJUnit4::class)
class ClientSteeringDeviceTest {
    @Test fun steerLiveHttpResponse() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("clientSteeringProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.example.codexmobile")
        val saved = ChatSessionStore(context).load()
        check(saved?.sessions?.none { it.turnInProgress } != false)
        val model = saved?.sessions?.firstOrNull { it.id == saved.activeSessionId }?.modelId ?: "gpt-6.1-sol"
        val catalog = listOf(CodexModelOption(model, model, defaultReasoningLevel = ReasoningLevel.LOW))
        val directory = File(context.cacheDir, "steering-test-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : Application() {
            init { attachBaseContext(context) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = directory
            override fun getCacheDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("steering_diagnostic_$name", mode)
        }
        AIClient.initialize(context)
        ModelCatalogStore(isolated).save(ModelCatalogState(catalog, System.currentTimeMillis()))
        var requests = 0
        var cancellations = 0
        val firstPartial = CompletableDeferred<Unit>()
        val runtime = object : ChatRuntime {
            override suspend fun respond(messages: List<ChatMessage>, modelId: String, reasoningLevel: ReasoningLevel,
                webSearchEnabled: Boolean, onPartial: (String) -> Unit): CodexResponse {
                val index = ++requests
                try {
                    return AIClient.generateResponse(messages, modelId, reasoningLevel, {
                        onPartial(it)
                        if (index == 1 && it.isNotBlank()) firstPartial.complete(Unit)
                    }, false)
                } catch (e: CancellationException) { cancellations++; throw e }
            }
            override suspend fun execute(command: String): com.example.codexmobile.ShellCommandResult = error("Diagnostic must not execute commands")
            override suspend fun capture(): List<MessageAttachment> = error("Diagnostic must not capture screen")
            override suspend fun models() = catalog
            override fun startBackground() {}
            override fun stopBackground() {}
        }
        val controller = withContext(Dispatchers.Main) { ChatViewModel(isolated, runtime) }
        try {
            withTimeout(90_000) {
                controller.isReady.first { it }
                withContext(Dispatchers.Main) {
                    controller.sendMessage("Do not use tools. Write a detailed 3000-word tutorial on triangle geometry. Start with the words Triangle geometry and keep writing.")
                }
                coroutineScope {
                    val failureWatch = launch {
                        controller.isTyping.first { !it }
                        check(firstPartial.isCompleted) { "Initial response ended before streaming: ${controller.sessions.value.first().lastError}" }
                    }
                    try { firstPartial.await() } finally { failureWatch.cancel() }
                }
                withContext(Dispatchers.Main) {
                    assertTrue("Initial response must still be running", controller.isTyping.value)
                    controller.sendMessage("Change direction immediately. Do not use tools. Reply with exactly CODEXR_CLIENT_STEERING_OK and nothing else.")
                }
                controller.isTyping.first { !it }
            }
            assertEquals(2, requests)
            assertEquals(1, cancellations)
            assertTrue(controller.messages.value.any { it.kind == "partial" })
            assertEquals("CODEXR_CLIENT_STEERING_OK", controller.messages.value.last().content.trim())
            assertTrue(controller.queuedMessages.value.isEmpty())
            instrumentation.sendStatus(0, Bundle().apply {
                putString("codexr_client_steering", "PASS: live HTTP response cancelled; follow-up completed; 2 requests; 1 cancellation; no tools executed; isolated transcript")
            })
        } finally {
            withContext(Dispatchers.Main) { controller.close() }?.join()
            directory.deleteRecursively()
        }
    }
}

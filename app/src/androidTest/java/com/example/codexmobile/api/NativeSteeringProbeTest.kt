package com.example.codexmobile.api

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.codexmobile.data.ChatSessionStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in diagnostic, never included in ordinary connected-test runs. */
@RunWith(AndroidJUnit4::class)
class NativeSteeringProbeTest {
    @Test fun checkNativeSteeringWithSavedSignIn() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeSteeringProbe") == "true")
        val context = instrumentation.targetContext
        check(context.packageName == "com.example.codexmobile") {
            "This opt-in probe must use a separately signed instrumentation APK; never retarget Gradle connected tests."
        }
        val state = ChatSessionStore(context).load()
        check(state?.sessions?.none { it.turnInProgress } != false) { "Finish active work before running the probe." }
        val model = state?.sessions?.firstOrNull { it.id == state.activeSessionId }?.modelId
            ?.takeIf { it.startsWith("gpt-6") } ?: "gpt-6.1-sol"
        val credential = AuthManager.credential(context)
        val chatGpt = credential is AuthCredential.ChatGpt
        val token = when (credential) {
            is AuthCredential.ChatGpt -> credential.session.accessToken
            is AuthCredential.ApiKey -> credential.key
        }
        val url = if (chatGpt) "wss://chatgpt.com/backend-api/codex/responses" else "wss://api.openai.com/v1/responses"
        val result = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val output = mutableMapOf<String, StringBuilder>()
        var currentId = ""
        var initialId = ""
        var handshaken = false
        var created = 0
        var sent = false
        var accepted = false
        var successorCompleted = false
        var finalMarker = false
        var httpCode: Int? = null
        var errorCode: String? = null
        var errorMessage: String? = null
        var closeCode: Int? = null
        val marker = "CODEXR_STEERING_OK"
        val create = buildJsonObject {
            put("type", "response.create")
            put("model", model)
            put("store", false)
            put("instructions", "You are a diagnostic assistant. Follow the latest user instruction. No tools are available.")
            put("input", buildJsonArray { add(buildJsonObject {
                put("role", "user"); put("content", "Write a 200-word explanation of basic triangle geometry.")
            }) })
            put("tools", buildJsonArray {})
            put("parallel_tool_calls", false)
            put("reasoning", buildJsonObject { put("effort", "low") })
            put("text", buildJsonObject { put("verbosity", "low") })
        }
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("User-Agent", "CodexR-native-steering-probe/1.0")
        if (credential is AuthCredential.ChatGpt) {
            builder.header("chatgpt-account-id", credential.session.accountId).header("originator", "codexr")
        }
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        val socket = client.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handshaken = true
                httpCode = response.code
                webSocket.send(create.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = Json.parseToJsonElement(text).jsonObject
                    val type = event["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (type != "response.output_text.delta" && events.size < 100) events += type
                    when (type) {
                        "response.created" -> {
                            currentId = event["response"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
                            output[currentId] = StringBuilder()
                            created++
                            if (initialId.isEmpty()) {
                                initialId = currentId
                                sent = webSocket.send(buildJsonObject {
                                    put("type", "response.steer")
                                    put("previous_response_id", initialId)
                                    put("input", "Change the task now: reply with exactly $marker and nothing else.")
                                }.toString())
                            }
                        }
                        "response.steer.accepted" -> accepted = true
                        "response.output_text.delta" -> output[currentId]?.append(event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        "response.steer.failed", "error", "response.failed" -> {
                            val error = event["error"] as? JsonObject
                                ?: (event["response"] as? JsonObject)?.get("error") as? JsonObject
                            errorCode = error?.get("code")?.jsonPrimitive?.contentOrNull ?: type
                            errorMessage = error?.get("message")?.jsonPrimitive?.contentOrNull?.replace(token, "[redacted]")?.take(500)
                            result.complete(Unit)
                        }
                        "response.completed", "response.done" -> {
                            val response = event["response"] as? JsonObject
                            val id = response?.get("id")?.jsonPrimitive?.contentOrNull ?: currentId
                            if (id != initialId && created >= 2) {
                                successorCompleted = true
                                val completedText = (response?.get("output") as? JsonArray).orEmpty().flatMap {
                                    ((it as? JsonObject)?.get("content") as? JsonArray).orEmpty()
                                }.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("")
                                finalMarker = completedText.trim() == marker || output[id]?.toString()?.trim() == marker
                                result.complete(Unit)
                            }
                        }
                        "response.incomplete" -> {
                            val reason = event["response"]?.jsonObject?.get("incomplete_details")?.jsonObject
                                ?.get("reason")?.jsonPrimitive?.contentOrNull
                            if (reason != "steered") { errorCode = "incomplete_${reason.orEmpty()}"; result.complete(Unit) }
                        }
                        "response.steer.pending" -> { errorCode = "unexpected_pending_without_tools"; result.complete(Unit) }
                    }
                } catch (failure: Exception) {
                    errorCode = "event_parse_${failure.javaClass.simpleName}"
                    result.complete(Unit)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                httpCode = response?.code ?: httpCode
                errorCode = "websocket_${t.javaClass.simpleName}"
                result.complete(Unit)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closeCode = code
                webSocket.close(code, null)
                result.complete(Unit)
            }
        })
        val finished = withTimeoutOrNull(60_000) { result.await(); true } ?: false
        socket.cancel()
        client.dispatcher.executorService.shutdown()
        val report = buildJsonObject {
            put("authMethod", if (chatGpt) "chatgpt" else "api_key")
            put("model", model); put("webSocketOpened", handshaken)
            httpCode?.let { put("httpStatus", it) }
            put("responseCreatedCount", created); put("steerSent", sent); put("steerAccepted", accepted)
            put("successorCompleted", successorCompleted); put("followedUpdatedInstruction", finalMarker)
            put("nativeSteeringWorks", handshaken && accepted && successorCompleted && finalMarker)
            put("timedOut", !finished)
            errorCode?.let { put("errorCode", it) }; errorMessage?.let { put("errorMessage", it) }
            closeCode?.let { put("closeCode", it) }
            put("events", buildJsonArray { events.forEach { add(JsonPrimitive(it)) } })
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("codexr_native_steering", report.toString()) })
    }
}

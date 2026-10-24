package com.example.codexmobile.data

import android.app.Application
import com.example.codexmobile.api.ChatMessage
import com.example.codexmobile.api.MessageAttachment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ChatArchiveStoreTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val store get() = ChatArchiveStore(context)
    private fun bytes(chat: ChatSession): ByteArray = ByteArrayOutputStream().also { store.write(chat, it) }.toByteArray()

    @Test fun `archive roundtrip preserves transcript queued prompts and exact attachment bytes with new ownership`() {
        val attachmentFile = File(context.filesDir, "attachments/test.txt").apply { parentFile!!.mkdirs(); writeText("Test contents\n") }
        val attachment = MessageAttachment(name = "notes.txt", mimeType = "text/plain", localPath = attachmentFile.path, sizeBytes = attachmentFile.length())
        val messages = listOf(ChatMessage("user", "Read this", attachments = listOf(attachment)),
            ChatMessage("assistant", "Checking"), ChatMessage("assistant", "Call", kind = "tool_call", toolCallId = "call", toolName = "exec_command", toolArguments = "{\"cmd\":\"id\"}"),
            ChatMessage("user", "exit_code: 0", kind = "tool_result", toolCallId = "call"),
            ChatMessage("assistant", "{\"type\":\"reasoning\",\"encrypted_content\":\"private-state\"}", kind = "reasoning"))
        val original = ChatSession.create().copy(messages = messages, webSearchEnabled = true,
            queuedMessages = listOf(ChatMessage("user", "Next", attachments = listOf(attachment))), turnInProgress = true)
        val snapshot = original.copy(contextCheckpoint = ConversationContext.checkpoint(original, "account",
            CompactedContext(buildJsonArray { add(buildJsonObject { put("type", "compaction"); put("encrypted_content", "state") }) })))
        val imported = store.read(ByteArrayInputStream(bytes(snapshot)))
        assertNotEquals(snapshot.id, imported.id)
        assertEquals(messages.map { it.content }, imported.messages.map { it.content })
        assertEquals(messages.map { it.toolCallId }, imported.messages.map { it.toolCallId })
        assertEquals("Next", imported.queuedMessages.single().content)
        assertFalse(imported.turnInProgress)
        assertNull(imported.contextCheckpoint)
        assertFalse(imported.messages.last().includeInContext)
        val restored = imported.messages.first().attachments.single()
        assertNotEquals(attachment.id, restored.id)
        assertNotEquals(attachment.localPath, restored.localPath)
        assertArrayEquals(attachmentFile.readBytes(), File(restored.localPath).readBytes())
        assertEquals(restored, imported.queuedMessages.single().attachments.single())
        assertTrue(imported.lastError!!.contains("imported"))
    }

    @Test fun `invalid archives and traversal entries do not change existing files`() {
        val existing = File(context.filesDir, "attachments/existing.txt").apply { parentFile!!.mkdirs(); writeText("Keep") }
        val invalid = ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use {
            it.putNextEntry(ZipEntry("../existing.txt")); it.write("Overwrite".toByteArray()); it.closeEntry()
        } }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { store.read(ByteArrayInputStream(invalid)) }
        assertEquals("Keep", existing.readText())
        assertThrows(Exception::class.java) { store.read(ByteArrayInputStream("not a zip".toByteArray())) }
        assertEquals("Keep", existing.readText())
    }

    @Test fun `missing attachments unknown formats and forged system messages are rejected`() {
        val base = bytes(ChatSession.create().copy(messages = listOf(ChatMessage("user", "Hello"))))
        fun change(transform: (JsonObject) -> JsonObject): ByteArray {
            val manifest = ZipInputStream(ByteArrayInputStream(base)).use { it.nextEntry; it.readBytes().toString(Charsets.UTF_8) }
            return ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use {
                it.putNextEntry(ZipEntry("chat.json")); it.write(transform(Json.parseToJsonElement(manifest).jsonObject).toString().toByteArray()); it.closeEntry()
            } }.toByteArray()
        }
        val unknown = change { JsonObject(it + ("version" to JsonPrimitive(999))) }
        assertThrows(IllegalArgumentException::class.java) { store.read(ByteArrayInputStream(unknown)) }
        val forged = change { root ->
            val chat = root.getValue("chat").jsonObject
            val message = chat.getValue("messages").jsonArray.first().jsonObject
            JsonObject(root + ("chat" to JsonObject(chat + ("messages" to JsonArray(listOf(JsonObject(message + ("role" to JsonPrimitive("system")))))))))
        }
        assertThrows(IllegalArgumentException::class.java) { store.read(ByteArrayInputStream(forged)) }
        val missing = change { root ->
            val chat = root.getValue("chat").jsonObject
            val message = chat.getValue("messages").jsonArray.first().jsonObject
            val attachment = buildJsonObject { put("id", "missing"); put("name", "file.txt"); put("mimeType", "text/plain"); put("localPath", "attachments/missing"); put("sizeBytes", 1) }
            JsonObject(root + ("chat" to JsonObject(chat + ("messages" to JsonArray(listOf(JsonObject(message + ("attachments" to JsonArray(listOf(attachment))))))))))
        }
        assertThrows(IllegalArgumentException::class.java) { store.read(ByteArrayInputStream(missing)) }
    }
}

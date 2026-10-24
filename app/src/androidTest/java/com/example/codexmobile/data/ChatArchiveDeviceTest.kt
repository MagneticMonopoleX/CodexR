package com.example.codexmobile.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.codexmobile.api.ChatMessage
import com.example.codexmobile.api.MessageAttachment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatArchiveDeviceTest {
    @Test fun archiveRoundTripKeepsFullTranscriptAfterCompaction() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        check(context.packageName.endsWith(".uitest"))
        val file = File(context.filesDir, "attachments/archive-test.txt").apply { parentFile!!.mkdirs(); writeText("Exact bytes\n") }
        val store = ChatArchiveStore(context)
        var imported: ChatSession? = null
        try {
            val original = ChatSession.create().copy(messages = listOf(
                ChatMessage("user", "Remember this", attachments = listOf(MessageAttachment(name = "note.txt", mimeType = "text/plain", localPath = file.path, sizeBytes = file.length()))),
                ChatMessage("assistant", "Done")))
            val window = CompactedContext(kotlinx.serialization.json.JsonArray(emptyList()))
            val compacted = original.copy(contextCheckpoint = ConversationContext.checkpoint(original, "account", window))
            val output = ByteArrayOutputStream()
            store.write(compacted, output)
            val restored = store.read(ByteArrayInputStream(output.toByteArray()))
            imported = restored
            assertEquals(original.messages.map { it.content }, restored.messages.map { it.content })
            assertNotEquals(original.id, restored.id)
            assertNull(restored.contextCheckpoint)
            assertFalse(restored.turnInProgress)
            assertArrayEquals(file.readBytes(), File(restored.messages.first().attachments.single().localPath).readBytes())
        } finally {
            imported?.let(store::discardImported)
            file.delete()
        }
    }
}

package com.example.codexmobile.data

import android.content.Context
import android.net.Uri
import com.example.codexmobile.api.ChatMessage
import com.example.codexmobile.api.MessageAttachment
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class ChatArchive(val format: String = "codexr.chat", val version: Int = 1, val chat: ChatSession)

/** Portable full transcript + attachments. No account preferences, keys, or root grants. */
class ChatArchiveStore(private val context: Context) {
    private val attachments = File(context.filesDir, "attachments").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(uri: Uri, session: ChatSession) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { write(session, it) }
            ?: error("Cannot open the selected export location.")
    }

    suspend fun import(uri: Uri): ChatSession = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { read(it) }
            ?: error("Cannot open the selected chat archive.")
    }

    internal fun write(session: ChatSession, output: OutputStream) {
        val all = session.messages + session.queuedMessages
        require(all.size <= 50_000) { "This chat has too many messages to export." }
        val files = all.flatMap { it.attachments }.distinctBy { it.id }
        require(files.size <= MAX_FILES) { "This chat has too many attachments to export." }
        val names = files.associate { it.id to "attachments/${UUID.randomUUID()}" }
        val sources = files.associate { attachment ->
            val file = File(attachment.localPath).canonicalFile
            require(file.parentFile == attachments.canonicalFile && file.isFile) {
                "An attachment is missing: ${attachment.name}"
            }
            require(file.length() <= MAX_FILE_BYTES) { "An attachment is too large to export." }
            attachment.id to file
        }
        fun portable(message: ChatMessage) = message.copy(attachments = message.attachments.map {
            it.copy(localPath = names.getValue(it.id), sizeBytes = sources.getValue(it.id).length())
        })
        val sanitized = resetContext(session).copy(messages = session.messages.map(::portable),
            queuedMessages = session.queuedMessages.map(::portable), turnInProgress = false)
        val manifest = json.encodeToString(ChatArchive(chat = sanitized)).toByteArray()
        require(manifest.size <= MAX_MANIFEST_BYTES && manifest.size + sources.values.sumOf { it.length() } <= MAX_ARCHIVE_BYTES) {
            "This chat is too large to export (128 MB maximum)."
        }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("chat.json")); zip.write(manifest); zip.closeEntry()
            files.forEach {
                zip.putNextEntry(ZipEntry(names.getValue(it.id)))
                sources.getValue(it.id).inputStream().use { source -> source.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    internal fun read(input: InputStream): ChatSession {
        val temporary = File.createTempFile("chat-import-", ".zip", context.cacheDir)
        val created = mutableListOf<File>()
        try {
            temporary.outputStream().use { boundedCopy(input, it, MAX_ARCHIVE_BYTES) }
            ZipFile(temporary).use { zip ->
                val entries = zip.entries().asSequence().take(MAX_FILES + 2).toList()
                require(entries.size in 1..MAX_FILES + 1 && entries.map { it.name }.distinct().size == entries.size) {
                    "Invalid chat archive entries."
                }
                require(entries.all { !it.isDirectory && (it.name == "chat.json" ||
                    it.name.matches(Regex("attachments/[A-Za-z0-9-]+"))) }) { "Invalid archive path." }
                val manifest = zip.getEntry("chat.json") ?: error("This is not a CodexR chat archive.")
                val manifestBytes = zip.getInputStream(manifest).use { source ->
                    val bytes = java.io.ByteArrayOutputStream()
                    boundedCopy(source, bytes, MAX_MANIFEST_BYTES.toLong())
                    bytes.toByteArray()
                }
                val archive = json.decodeFromString<ChatArchive>(manifestBytes.toString(Charsets.UTF_8))
                require(archive.format == "codexr.chat" && archive.version == 1) { "Unsupported chat archive version." }
                val chat = archive.chat
                val all = chat.messages + chat.queuedMessages
                require(all.size <= 50_000 && chat.title.length <= 1_000 && chat.modelId.length <= 200) { "Chat archive is too large." }
                require(all.map { it.id }.distinct().size == all.size) { "Chat archive contains duplicate message IDs." }
                all.forEach { message ->
                    require(message.role in setOf("user", "assistant") && message.kind in setOf(
                        "message", "partial", "reasoning", "tool", "tool_call", "tool_result", "capture_call", "capture_result", "web_search")) {
                        "Chat archive contains an unsupported message."
                    }
                    require(message.id.length <= 200 && message.content.length <= MAX_MANIFEST_BYTES &&
                        (message.toolArguments?.length ?: 0) <= 256 * 1024) { "Chat archive contains an oversized message." }
                    require(message.attachments.size <= AttachmentStore.MAX_ATTACHMENTS) { "Too many attachments in one message." }
                }
                val refs = all.flatMap { it.attachments }.groupBy { it.id }
                require(refs.size <= MAX_FILES) { "Too many chat attachments." }
                require(refs.values.all { list -> list.all { it == list.first() } }) { "Conflicting attachment references." }
                require(refs.values.map { it.first().localPath }.distinct().size == refs.size) { "Duplicate attachment paths." }
                require(entries.map { it.name }.toSet() == setOf("chat.json") + refs.values.map { it.first().localPath }) {
                    "Chat archive has missing or unexpected attachments."
                }
                var total = manifestBytes.size.toLong()
                val imported = refs.mapValues { (_, occurrences) ->
                    val attachment = occurrences.first()
                    require(attachment.name.length <= 1_000 && attachment.mimeType.length <= 100 &&
                        (attachment.mimeType.startsWith("image/") || attachment.mimeType == "text/plain")) { "Unsupported attachment type." }
                    val entry = zip.getEntry(attachment.localPath) ?: error("Missing attachment: ${attachment.name}")
                    val file = File(attachments, UUID.randomUUID().toString())
                    created += file
                    val size = zip.getInputStream(entry).use { source -> file.outputStream().use {
                        boundedCopy(source, it, minOf(MAX_FILE_BYTES, MAX_ARCHIVE_BYTES - total))
                    } }
                    total += size
                    if (attachment.mimeType == "text/plain") {
                        require(size <= AttachmentStore.MAX_TEXT_BYTES) { "Text attachment is too large." }
                        val bytes = file.readBytes()
                        require(bytes.none { it == 0.toByte() }) { "Invalid text attachment." }
                        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                    } else {
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                        require(bounds.outWidth in 1..4_096 && bounds.outHeight in 1..4_096) { "Invalid or oversized image attachment." }
                    }
                    attachment.copy(id = UUID.randomUUID().toString(), localPath = file.path, sizeBytes = size)
                }
                fun restored(message: ChatMessage) = message.copy(
                    attachments = message.attachments.map { imported.getValue(it.id) },
                    includeInContext = message.kind != "reasoning",
                )
                return resetContext(chat).copy(id = UUID.randomUUID().toString(),
                    title = chat.title.ifBlank { "Imported chat" }, updatedAt = System.currentTimeMillis(),
                    messages = chat.messages.map(::restored), queuedMessages = chat.queuedMessages.map(::restored),
                    turnInProgress = false,
                    lastError = if (all.isEmpty()) null else "Chat imported. Review it, then send a message or tap Continue to resume.")
            }
        } catch (error: Exception) {
            created.forEach { it.delete() }
            throw error
        } finally { temporary.delete() }
    }

    internal fun discardImported(session: ChatSession) {
        (session.messages + session.queuedMessages).flatMap { it.attachments }.forEach {
            val file = File(it.localPath)
            if (file.canonicalFile.parentFile == attachments.canonicalFile) file.delete()
        }
    }

    private fun resetContext(chat: ChatSession) = chat.copy(contextCheckpoint = null, lastInputTokens = null,
        usageMessageCount = 0, usageModelId = null, usageAuthScope = null, usageCheckpointAt = null)

    private fun boundedCopy(source: InputStream, target: OutputStream, limit: Long): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = source.read(buffer)
            if (count < 0) return total
            total += count
            require(total <= limit) { "Chat archive exceeds the supported size limit." }
            target.write(buffer, 0, count)
        }
    }

    companion object {
        private const val MAX_FILES = 1_000
        private const val MAX_FILE_BYTES = 10L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 32 * 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
    }
}

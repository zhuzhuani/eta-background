package io.github.mangi.eta.agent.tasks

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Durable references only; finalized results remain immutable in TaskRunResultStore.
 * Full/corrupt outboxes fail closed rather than silently dropping undelivered completions.
 */
internal class TaskConversationDeliveryStore(private val directory: File) {
    data class Delivery(val runId: String, val conversationId: String)

    companion object {
        const val MAX_PENDING = 1024
        private const val MAX_BYTES = 2 * 1024 * 1024
        private val monitor = Any()
        private val mutableChanges = MutableSharedFlow<Unit>(replay = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
        val changes = mutableChanges.asSharedFlow()
        fun forContext(context: Context) = TaskConversationDeliveryStore(File(context.filesDir, "task-conversation-delivery"))

        private fun validateId(value: String) {
            require(value.isNotBlank() && value.length <= 200 && value.none { it.isISOControl() } &&
                value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value) { "Invalid delivery identity" }
        }
    }

    /** Only call with a saved result whose conversationId was supplied by the trusted run owner. */
    fun enqueue(result: TaskRunResult) {
        val owner = result.conversationId ?: return
        val delivery = Delivery(result.runId, owner)
        validateId(delivery.runId)
        validateId(delivery.conversationId)
        transaction { pending ->
            val existing = pending.firstOrNull { it.runId == delivery.runId }
            check(existing == null || existing == delivery) { "Conflicting completion owner" }
            if (existing != null) pending else {
                check(pending.size < MAX_PENDING) { "Task conversation outbox is full" }
                pending + delivery
            }
        }
        mutableChanges.tryEmit(Unit)
    }

    fun pending(): List<Delivery> = transaction { it }

    /** Caller must first commit the message (or confirmed conversation deletion) to persistence. */
    fun acknowledge(delivery: Delivery) {
        transaction { pending -> pending.filterNot { it == delivery } }
    }

    private fun transaction(change: (List<Delivery>) -> List<Delivery>): List<Delivery> = synchronized(monitor) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create task delivery directory")
        val root = directory.canonicalFile
        val target = File(root, "pending.json")
        val lockFile = File(root, ".lock")
        require(target.canonicalFile == target.absoluteFile && lockFile.canonicalFile == lockFile.absoluteFile)
        RandomAccessFile(lockFile, "rw").use { lock ->
            lock.channel.lock().use {
                val before = read(target)
                val after = change(before)
                if (after != before) write(target, after)
                after
            }
        }
    }

    private fun read(file: File): List<Delivery> {
        if (!file.exists()) return emptyList()
        val bytes = file.inputStream().use { it.readNBytes(MAX_BYTES + 1) }
        require(bytes.size <= MAX_BYTES)
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        val tokener = JSONTokener(text)
        val json = tokener.nextValue() as? JSONObject ?: error("Invalid delivery outbox")
        require(tokener.nextClean() == '\u0000' && json.getInt("version") == 1)
        val rows = json.getJSONArray("pending")
        require(rows.length() <= MAX_PENDING)
        val result = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            Delivery(row.getString("runId"), row.getString("conversationId")).also {
                validateId(it.runId)
                validateId(it.conversationId)
            }
        }
        require(result.map { it.runId }.distinct().size == result.size)
        return result
    }

    private fun write(target: File, pending: List<Delivery>) {
        val json = JSONObject().put("version", 1).put("pending", JSONArray().also { rows ->
            pending.forEach { rows.put(JSONObject().put("runId", it.runId).put("conversationId", it.conversationId)) }
        })
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val temporary = File.createTempFile(".pending-", ".tmp", target.parentFile)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}

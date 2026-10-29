package io.github.mangi.eta.agent.tasks

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Immutable, bounded per-execution records. Errors writing are deliberately propagated to callers.
 * A corrupt existing record is not an invitation to overwrite a previously finalized execution.
 */
internal class TaskRunResultStore(private val directory: File) {
    companion object {
        const val MAX_REPORT_CHARS = 128 * 1024
        const val MAX_JSON_BYTES = 1024 * 1024
        const val MAX_ATTACHMENT_BYTES = 16L * 1024 * 1024
        private val monitor = Any()

        fun forContext(context: Context) = TaskRunResultStore(File(context.filesDir, "task-run-results"))

        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        private fun validateId(id: String) {
            require(id.isNotBlank() && id.length <= 200 && id.none { it.isISOControl() } &&
                id.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == id) { "Invalid execution ID" }
        }
    }

    fun save(result: TaskRunResult): TaskRunResult = synchronized(monitor) {
        validate(result)
        result.attachments.filter { it.privateFile != null }.forEach {
            require(resolveAttachment(it) != null) { "Attachment is not a valid private copy" }
        }
        ensureDirectory()
        RandomAccessFile(child(".lock"), "rw").use { lock ->
            lock.channel.lock().use {
                val target = recordFile(result.runId)
                if (target.exists()) {
                    val existing = read(result.runId)
                        ?: throw IOException("Existing finalized result is unreadable")
                    check(existing == result) { "Execution ${result.runId} already has a different finalized result" }
                    return@synchronized existing
                }
                val bytes = encode(result).toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_JSON_BYTES) { "Result JSON exceeds limit" }
                atomicWrite(target, bytes)
                result
            }
        }
    }

    fun read(runId: String): TaskRunResult? = synchronized(monitor) {
        try {
            validateId(runId)
            val file = recordFile(runId)
            if (!file.isFile) return@synchronized null
            val bytes = file.inputStream().use { it.readNBytes(MAX_JSON_BYTES + 1) }
            if (bytes.size > MAX_JSON_BYTES) return@synchronized null
            val text = bytes.toString(Charsets.UTF_8)
            require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
            val tokener = JSONTokener(text)
            val json = tokener.nextValue() as? JSONObject ?: return@synchronized null
            require(tokener.nextClean() == '\u0000') { "Trailing result data" }
            val result = decode(json)
            validate(result)
            if (result.runId != runId) null else result
        } catch (_: Exception) {
            null
        }
    }

    /** Only a caller-approved directory can supply files. No URI resolution or permission grants.
     * Content-addressed copies survive deletion of the producer's original file.
     */
    fun importAttachment(
        file: File,
        approvedDirectory: File,
        name: String = file.name,
        mimeType: String = "application/octet-stream",
    ): TaskResultAttachment = synchronized(monitor) {
        val approved = approvedDirectory.canonicalFile
        val source = file.canonicalFile
        require(approved.isDirectory && source.toPath().startsWith(approved.toPath()) && source != approved)
        require(source.isFile && source.length() <= MAX_ATTACHMENT_BYTES)
        val bytes = source.inputStream().use { it.readNBytes(MAX_ATTACHMENT_BYTES.toInt() + 1) }
        require(bytes.size <= MAX_ATTACHMENT_BYTES) { "Attachment exceeds limit" }
        val attachment = TaskResultAttachment(name, mimeType, "${hash(bytes)}.bin", sizeBytes = bytes.size.toLong())
        attachment.validate()
        ensureDirectory()
        val target = attachmentFile(attachment.privateFile!!)
        RandomAccessFile(child(".lock"), "rw").use { lock ->
            lock.channel.lock().use {
                if (!target.exists()) atomicWrite(target, bytes)
                else check(target.isFile && target.length() == bytes.size.toLong() &&
                    target.inputStream().use { hash(it.readNBytes(MAX_ATTACHMENT_BYTES.toInt() + 1)) } == hash(bytes)) {
                    "Existing attachment is corrupt"
                }
            }
        }
        attachment
    }

    /** Bounded inline report bytes are copied into private durable storage, never opened as a path. */
    fun importAttachmentBytes(bytes: ByteArray, name: String, mimeType: String): TaskResultAttachment = synchronized(monitor) {
        require(bytes.size <= MAX_ATTACHMENT_BYTES)
        val attachment = TaskResultAttachment(name, mimeType, "${hash(bytes)}.bin", sizeBytes = bytes.size.toLong())
        attachment.validate()
        ensureDirectory()
        val target = attachmentFile(attachment.privateFile!!)
        RandomAccessFile(child(".lock"), "rw").use { lock ->
            lock.channel.lock().use {
                if (!target.exists()) atomicWrite(target, bytes)
                else require(target.isFile && target.length() == bytes.size.toLong() &&
                    target.inputStream().use { hash(it.readNBytes(MAX_ATTACHMENT_BYTES.toInt() + 1)) } == hash(bytes))
            }
        }
        attachment
    }

    fun resolveAttachment(attachment: TaskResultAttachment): File? = synchronized(monitor) {
        try {
            attachment.validate()
            val token = attachment.privateFile ?: return@synchronized null
            val file = attachmentFile(token)
            if (!file.isFile || file.length() != attachment.sizeBytes) return@synchronized null
            val bytes = file.inputStream().use { it.readNBytes(MAX_ATTACHMENT_BYTES.toInt() + 1) }
            if (bytes.size > MAX_ATTACHMENT_BYTES || "${hash(bytes)}.bin" != token) null else file
        } catch (_: Exception) { null }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create result directory")
    }

    private fun child(name: String): File {
        val root = directory.canonicalFile
        val file = File(root, name)
        require(file.canonicalFile == file.absoluteFile) { "Store path must not be a symlink" }
        return file
    }

    private fun recordFile(runId: String): File = child("${hash(runId.toByteArray(Charsets.UTF_8))}.json")
    private fun attachmentFile(token: String): File = child(token)

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temporary = File.createTempFile(".pending-", ".tmp", directory.canonicalFile)
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            // Same-filesystem atomic rename: no truncate/write window, no non-atomic fallback.
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }

    private fun validate(result: TaskRunResult) {
        validateId(result.runId)
        listOfNotNull(result.taskId, result.conversationId, result.diagnosticJobId).forEach { validateId(it) }
        require(result.title.isNotBlank() && result.title.length <= 512)
        require(result.summary.length <= 4096 && result.report.length <= MAX_REPORT_CHARS)
        require(result.source.length <= 256)
        require(result.startedAt >= 0 && result.completedAt >= result.startedAt)
        require(result.attachments.size <= 16)
        result.attachments.forEach {
            it.validate()
            // Missing/tampered attachment must not hide the report; resolveAttachment returns null for its UI row.
        }
    }

    private fun encode(result: TaskRunResult): JSONObject = JSONObject()
        .put("version", 1).put("runId", result.runId)
        .put("taskId", result.taskId ?: JSONObject.NULL)
        .put("conversationId", result.conversationId ?: JSONObject.NULL)
        .put("title", result.title).put("status", result.status.name)
        .put("summary", result.summary).put("report", result.report)
        .put("startedAt", result.startedAt).put("completedAt", result.completedAt)
        .put("diagnosticJobId", result.diagnosticJobId ?: JSONObject.NULL).put("source", result.source)
        .put("attachments", JSONArray().also { array -> result.attachments.forEach { attachment ->
            array.put(JSONObject().put("name", attachment.name).put("mimeType", attachment.mimeType)
                .put("privateFile", attachment.privateFile ?: JSONObject.NULL)
                .put("httpsUrl", attachment.httpsUrl ?: JSONObject.NULL)
                .put("sizeBytes", attachment.sizeBytes ?: JSONObject.NULL))
        } })

    private fun decode(json: JSONObject): TaskRunResult {
        require(json.getInt("version") == 1)
        val attachments = json.getJSONArray("attachments")
        require(attachments.length() <= 16)
        return TaskRunResult(
            runId = json.getString("runId"), taskId = json.nullableString("taskId"),
            conversationId = json.nullableString("conversationId"), title = json.getString("title"),
            status = TaskRunStatus.valueOf(json.getString("status")), summary = json.getString("summary"),
            report = json.getString("report"), startedAt = json.getLong("startedAt"),
            completedAt = json.getLong("completedAt"), diagnosticJobId = json.nullableString("diagnosticJobId"),
            source = json.getString("source"), attachments = List(attachments.length()) { index ->
                val a = attachments.getJSONObject(index)
                TaskResultAttachment(a.getString("name"), a.getString("mimeType"), a.nullableString("privateFile"),
                    a.nullableString("httpsUrl"), if (a.isNull("sizeBytes")) null else a.getLong("sizeBytes"))
            },
        )
    }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
}

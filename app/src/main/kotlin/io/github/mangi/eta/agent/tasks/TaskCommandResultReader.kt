package io.github.mangi.eta.agent.tasks

import io.github.mangi.eta.agent.terminal.DetachedTask
import org.json.JSONObject
import org.json.JSONTokener

/** Reads only an explicitly supplied structured terminal result, never a log or stdout tail.
 * Producer schema: {"status":"Succeeded|Partial|Failed|Cancelled|Unconfirmed",
 * "summary":"...", "report":"..."}. Optional runId must match the detached execution.
 * Optional attachments support HTTPS references or bounded inline base64 copied into the store,
 * never producer-controlled filesystem paths.
 */
internal object TaskCommandResultReader {
    fun read(
        task: DetachedTask,
        structuredJson: String? = null,
        taskId: String? = null,
        conversationId: String? = null,
        store: TaskRunResultStore? = null,
    ): TaskRunResult {
        require(task.completedAt != null) { "A running command has no terminal result" }
        val explicit = parse(structuredJson, task.id, store)
        val cancelled = task.endReason?.lowercase() in setOf("cancelled", "canceled", "stopped", "user_cancelled")
        val failed = task.exitCode?.let { it != 0 } == true ||
            task.endReason?.lowercase() in setOf("timeout", "timed_out", "failed", "interrupted", "lost")
        val status = when {
            cancelled -> TaskRunStatus.Cancelled
            failed -> if (explicit?.status == TaskRunStatus.Partial) TaskRunStatus.Partial else TaskRunStatus.Failed
            else -> explicit?.status ?: TaskRunStatus.Unconfirmed
        }
        return TaskRunResult(
            runId = task.id,
            taskId = taskId,
            conversationId = conversationId,
            title = task.description.ifBlank { "Terminal task" }.take(512),
            status = status,
            summary = when {
                cancelled -> "任务已取消。" + explicit?.summary.orEmpty().take(4000)
                failed && explicit?.status != TaskRunStatus.Partial -> "命令失败、超时或中断；请查看报告及诊断日志。"
                explicit != null -> explicit.summary
                else -> "命令已结束，但没有提供有效的结构化报告；目标完成情况未确认。"
            },
            report = explicit?.report.orEmpty(),
            startedAt = task.startedAt,
            completedAt = task.completedAt.coerceAtLeast(task.startedAt),
            diagnosticJobId = task.id,
            source = "terminal",
            attachments = explicit?.attachments.orEmpty(),
        )
    }

    private data class Explicit(val status: TaskRunStatus, val summary: String, val report: String,
        val attachments: List<TaskResultAttachment>)

    private fun parse(raw: String?, runId: String, store: TaskRunResultStore?): Explicit? = try {
        if (raw == null || raw.length > TaskRunResultStore.MAX_JSON_BYTES ||
            raw.toByteArray(Charsets.UTF_8).size > TaskRunResultStore.MAX_JSON_BYTES) null
        else {
            val tokener = JSONTokener(raw)
            val json = tokener.nextValue() as? JSONObject ?: throw IllegalArgumentException("Expected result object")
            require(tokener.nextClean().code == 0) { "Trailing structured result data" }
            require(!json.has("runId") || json.getString("runId") == runId)
            val status = TaskRunStatus.valueOf(json.getString("status"))
            val summary = json.getString("summary")
            val report = json.getString("report")
            require(summary.isNotBlank() && summary.length <= 4096)
            require(report.length <= TaskRunResultStore.MAX_REPORT_CHARS)
            val array = json.optJSONArray("attachments")
            require(array == null || array.length() <= 16)
            val attachments = if (array == null) emptyList() else List(array.length()) { index ->
                val attachment = array.getJSONObject(index)
                // Only user-clicked HTTPS references; never import arbitrary filesystem paths from a command.
                val name = attachment.getString("name")
                val mime = attachment.optString("mimeType", "application/octet-stream")
                if (attachment.has("base64")) {
                    require(!attachment.has("url"))
                    val bytes = java.util.Base64.getDecoder().decode(attachment.getString("base64"))
                    require(bytes.size <= 40 * 1024) { "Inline attachment exceeds command report budget" }
                    requireNotNull(store) { "Private attachment import unavailable" }.importAttachmentBytes(bytes, name, mime)
                } else TaskResultAttachment.https(attachment.getString("url"), name, mime)
            }
            Explicit(status, summary, report, attachments)
        }
    } catch (_: Exception) { null }
}

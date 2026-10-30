package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.tasks.TaskRunResult
import io.github.mangi.eta.agent.tasks.TaskRunResultStore
import io.github.mangi.eta.agent.tasks.TaskRunStatus

/** Separate durable history from the ACK-deleted conversation delivery outbox. */
internal object AgentTaskResultDelivery {
    fun persist(context: Context, request: AgentRuntimeWire.RunRequest, result: AgentRuntimeWire.RunResult, startedAt: Long, cancelled: Boolean = false): TaskRunResult {
        val store = TaskRunResultStore.forContext(context)
        store.read(result.runId)?.let { return it }
        val fullReport = result.content.ifBlank { result.error.orEmpty() }
        val report = if (fullReport.length > TaskRunResultStore.MAX_REPORT_CHARS)
            fullReport.take(TaskRunResultStore.MAX_REPORT_CHARS - 60) + "\n\n[报告过长，此副本已截断；完整内容请查看原会话。]"
        else fullReport
        // RunResult.ok means the agent turn completed, not independent verification of its goal.
        val status = if (cancelled) TaskRunStatus.Cancelled else if (result.ok) TaskRunStatus.Unconfirmed else TaskRunStatus.Failed
        val summary = if (cancelled) "任务已取消；已生成的内容可在详情查看。" else if (result.ok) {
            report.lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString(" ").take(240)
                .ifBlank { "本轮已结束，但没有提供可读的任务报告；目标是否完成尚未确认。" }
        } else "执行失败：" + result.error.orEmpty().take(200).ifBlank { "未提供原因，请查看详情。" }
        val automation = request.handoff?.takeIf { it.source == "automation" }
            ?.let { AgentExternalArchivePayload.from(it.payload) }
        val taskId = automation?.adapterPayload?.optString("taskId")?.takeIf { it.isNotBlank() }
        return store.save(TaskRunResult(
            runId = result.runId,
            taskId = taskId,
            conversationId = AgentConversationIdentity.fromHandoff(request.handoff, request.runId),
            title = automation?.title?.take(80)?.takeIf { it.isNotBlank() } ?: request.prompt.take(80).ifBlank { "Agent 任务" },
            status = status,
            summary = summary,
            report = report.ifBlank { "未提供详细结果。" },
            startedAt = startedAt,
            completedAt = System.currentTimeMillis().coerceAtLeast(startedAt),
            source = if (automation != null) "automation" else "agent",
        ))
    }
}

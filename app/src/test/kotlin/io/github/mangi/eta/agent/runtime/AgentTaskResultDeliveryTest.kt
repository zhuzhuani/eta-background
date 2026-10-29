package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tasks.TaskRunResult
import io.github.mangi.eta.agent.tasks.TaskRunResultStore
import io.github.mangi.eta.agent.tasks.TaskRunStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentTaskResultDeliveryTest {
    @Test fun successfulTurnIsUnconfirmedNotVerifiedTaskSuccess() {
        val context = RuntimeEnvironment.getApplication()
        val request = request("agent-unconfirmed")
        val result = AgentTaskResultDelivery.persist(context, request,
            AgentRuntimeWire.RunResult(request.runId, true, "已查看订单页面。\n尚需核对收货状态。"), 1)

        assertEquals(TaskRunStatus.Unconfirmed, result.status)
        assertEquals("run:${request.runId}", result.conversationId)
        assertEquals(request.prompt, result.title)
        assertEquals("已查看订单页面。 尚需核对收货状态。", result.summary)
        assertEquals("已查看订单页面。\n尚需核对收货状态。", result.report)
        assertEquals("agent", result.source)
        assertEquals(1L, result.startedAt)
        assertTrue(result.completedAt >= result.startedAt)
        assertEquals(result, TaskRunResultStore.forContext(context).read(request.runId))
    }

    @Test fun rawToolStdoutAndReasoningAreNotUsedAsTheTaskReport() {
        val context = RuntimeEnvironment.getApplication()
        val request = request("agent-no-stdout")
        val rawOutput = "RAW_TOOL_STDOUT_PRIVATE_VALUE"
        val result = AgentTaskResultDelivery.persist(context, request,
            AgentRuntimeWire.RunResult(request.runId, true, "",
                reasoningContent = rawOutput,
                transcript = listOf(AgentModelClient.ConversationMessage("tool", rawOutput))), 1)

        assertEquals(TaskRunStatus.Unconfirmed, result.status)
        assertFalse(result.report.contains(rawOutput))
        assertFalse(result.summary.contains(rawOutput))
        assertTrue(result.summary.contains("尚未确认"))
        assertEquals("未提供详细结果。", result.report)
        assertTrue(result.attachments.isEmpty())
    }

    @Test fun failedTurnHasAnExplicitFailureReport() {
        val context = RuntimeEnvironment.getApplication()
        val request = request("agent-failed-report")
        val result = AgentTaskResultDelivery.persist(context, request,
            AgentRuntimeWire.RunResult(request.runId, false, "", error = "目标应用无法打开"), 1)

        assertEquals(TaskRunStatus.Failed, result.status)
        assertEquals("执行失败：目标应用无法打开", result.summary)
        assertEquals("目标应用无法打开", result.report)
    }

    @Test fun oversizedReportIsBoundedAndItsTruncationIsVisible() {
        val context = RuntimeEnvironment.getApplication()
        val request = request("agent-bounded-report")
        val fullReport = "R".repeat(TaskRunResultStore.MAX_REPORT_CHARS + 512)
        val result = AgentTaskResultDelivery.persist(context, request,
            AgentRuntimeWire.RunResult(request.runId, true, fullReport), 1)

        assertTrue(result.report.length <= TaskRunResultStore.MAX_REPORT_CHARS)
        assertTrue(result.report.startsWith("RRRR"))
        assertTrue(result.report.contains("已截断"))
        assertTrue(result.summary.length <= 240)
        assertEquals(result, TaskRunResultStore.forContext(context).read(request.runId))
    }

    @Test fun explicitCancellationIsNotAnExecutionFailure() {
        val context = RuntimeEnvironment.getApplication()
        val request = request("agent-cancelled")
        val result = AgentTaskResultDelivery.persist(context, request,
            AgentRuntimeWire.RunResult(request.runId, false, "已完成部分读取", error = "用户停止"),
            startedAt = 1, cancelled = true)
        assertEquals(TaskRunStatus.Cancelled, result.status)
        assertTrue(result.summary.contains("取消"))
        assertEquals("已完成部分读取", result.report)
    }

    @Test fun retryPreservesTheExistingFinalizedRecord() {
        val context = RuntimeEnvironment.getApplication()
        val request = request("agent-existing-result")
        val original = TaskRunResult(request.runId, conversationId = "original-conversation",
            title = "原始任务", status = TaskRunStatus.Partial, summary = "完成一部分，等待确认",
            report = "已查询记录，但提交步骤未获确认。", startedAt = 1, completedAt = 2, source = "agent")
        val store = TaskRunResultStore.forContext(context)
        store.save(original)

        val retried = AgentTaskResultDelivery.persist(context, request,
            AgentRuntimeWire.RunResult(request.runId, true, "重试时不同的答案"), 99)

        assertEquals(original, retried)
        assertEquals(original, store.read(request.runId))
    }

    private fun request(id: String) = AgentRuntimeWire.RunRequest(
        runId = id,
        prompt = "查看订单状态并说明证据",
        config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1",
            apiKey = "test-only", model = "test", systemPrompt = ""),
        images = emptyList(),
    )
}

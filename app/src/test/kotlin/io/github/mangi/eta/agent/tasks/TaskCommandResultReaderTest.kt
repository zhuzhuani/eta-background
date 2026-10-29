package io.github.mangi.eta.agent.tasks

import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import org.junit.Assert.*
import org.junit.Test

class TaskCommandResultReaderTest {
    private fun task() = DetachedTask(
        id = "stable-execution", pid = 123, token = "token", command = "echo success", cwd = "/",
        identity = "user", environment = TerminalEnvironment.ANDROID, logPath = "/not/read/stdout",
        startedAt = 1, completedAt = 2, exitCode = 0, commandTask = true,
    )

    @Test fun exitZeroAndLogsNeverBecomeSuccessOrReport() {
        val result = TaskCommandResultReader.read(task())
        assertEquals(TaskRunStatus.Unconfirmed, result.status)
        assertEquals("", result.report)
        assertEquals(task().id, result.runId)
        assertEquals(task().id, result.diagnosticJobId)
    }

    @Test fun explicitReportHasBoundedStructuredContract() {
        val json = """{"runId":"stable-execution","status":"Succeeded","summary":"Done","report":"Verified result"}"""
        val result = TaskCommandResultReader.read(task(), json, taskId = "schedule", conversationId = "chat")
        assertEquals(TaskRunStatus.Succeeded, result.status)
        assertEquals("Verified result", result.report)
        assertEquals("schedule", result.taskId)
        assertEquals("chat", result.conversationId)
    }

    @Test fun invalidOrMismatchedStructuredResultIsUnconfirmed() {
        listOf("stdout says success", "{", """{"status":"Succeeded"}""",
            """{"runId":"other","status":"Succeeded","summary":"Done","report":"Report"}""",
            """{"status":"success","summary":"Done","report":"Report"}""").forEach {
            assertEquals(TaskRunStatus.Unconfirmed, TaskCommandResultReader.read(task(), it).status)
        }
    }

    @Test fun processFailureAndCancellationCannotBePromotedByProducerSuccess() {
        val json = """{"status":"Succeeded","summary":"Claimed success","report":"Claim"}"""
        assertEquals(TaskRunStatus.Failed, TaskCommandResultReader.read(task().copy(exitCode = 4), json).status)
        assertEquals(TaskRunStatus.Cancelled, TaskCommandResultReader.read(task().copy(endReason = "cancelled"), json).status)
        assertEquals(TaskRunStatus.Failed, TaskCommandResultReader.read(task().copy(endReason = "timeout"), json).status)
    }

    @Test fun onlySafeHttpsAttachmentReferencesAreAccepted() {
        val json = """{"status":"Partial","summary":"Report","report":"Body","attachments":[{"url":"https://example.org/report.pdf","name":"report","mimeType":"application/pdf"}]}"""
        val result = TaskCommandResultReader.read(task(), json)
        assertEquals("https://example.org/report.pdf", result.attachments.single().httpsUrl)
        assertEquals(TaskRunStatus.Unconfirmed, TaskCommandResultReader.read(task(), json.replace("https://example.org/report.pdf", "file:///private/secret")).status)
    }

    @Test fun inlineAttachmentsAreDurableCopiesNotPaths() {
        val directory = java.nio.file.Files.createTempDirectory("inline-results").toFile()
        val store = TaskRunResultStore(directory)
        val json = """{"status":"Succeeded","summary":"Report","report":"Body","attachments":[{"base64":"aGVsbG8=","name":"result.txt","mimeType":"text/plain"}]}"""
        val result = TaskCommandResultReader.read(task(), json, store = store)
        store.save(result)
        assertEquals("hello", TaskRunResultStore(directory).resolveAttachment(result.attachments.single())!!.readText())
        assertEquals(TaskRunStatus.Unconfirmed, TaskCommandResultReader.read(task(), json).status)
    }

    @Test fun runningTaskCannotBeFinalized() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskCommandResultReader.read(task().copy(completedAt = null))
        }
    }
}

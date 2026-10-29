package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tasks.TaskRunResult
import io.github.mangi.eta.agent.tasks.TaskRunStatus
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class TaskConversationDeliveryTest {
    @Before fun resetDatabase() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }

    @Test fun persistenceBeforeAckRetryKeepsExactlyOneReceipt() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val original = AgentChatUiState(messages = listOf(UserMessageUi("later", "Newer turn")),
            input = "", isStreaming = false, thinkingEnabled = true)
        val initial = AgentConversationStore.Snapshot("original", mapOf("original" to original),
            mapOf("original" to "Original"), mapOf("original" to 1L))
        val persistence = AgentConversationPersistence(initial)
        AgentConversationPersistence().save(context, initial)
        val delivered = appendTaskConversationResult(original, result())
        persistence.save(context, initial.copy(conversationsById = mapOf("original" to delivered)))
        // Simulate process death after SQLite commit but before outbox ACK.
        val restored = requireNotNull(AgentConversationStore.loadConversation(context, "original"))
        val retried = appendTaskConversationResult(restored, result())
        assertSame(restored, retried)
        assertEquals(1, retried.messages.count { it.id == "task-command:execution-7" })
    }

    private fun result() = TaskRunResult(
        runId = "execution-7", conversationId = "original", title = "Background check",
        status = TaskRunStatus.Partial, summary = "Two checks complete", report = "<untrusted> **report**",
        startedAt = 1, completedAt = 2,
    )

    @Test fun appendsPlainTextReceiptWithoutReplacingAdvancedContextOrDraft() {
        val history = listOf(AgentModelClient.ConversationMessage("user", "Newer turn"))
        val journal = listOf(AgentModelClient.ConversationMessage("user", "Original transcript"))
        val state = AgentChatUiState(messages = listOf(UserMessageUi("later", "Newer turn")),
            history = history, journal = journal, input = "draft", isStreaming = false, thinkingEnabled = true)
        val updated = appendTaskConversationResult(state, result())
        assertSame(history, updated.history)
        assertSame(journal, updated.journal)
        assertEquals("draft", updated.input)
        assertEquals(state.messages.first(), updated.messages.first())
        val receipt = updated.messages.last() as AgentMessageUi
        assertEquals("task-command:execution-7", receipt.id)
        assertFalse(receipt.renderMarkdown)
        assertTrue(receipt.content.contains("部分完成"))
        assertTrue(receipt.content.contains("execution-7"))
        assertTrue(receipt.content.contains("<untrusted> **report**"))
        assertSame(updated, appendTaskConversationResult(updated, result()))
    }

    @Test fun persistedMarkerDeduplicatesWhenMessageIsOutsideLoadedWindow() {
        val state = AgentChatUiState(messages = emptyList(), input = "", isStreaming = false,
            thinkingEnabled = true, appliedRuntimeRunIds = listOf("task-command:execution-7"))
        assertSame(state, appendTaskConversationResult(state, result()))
    }

    @Test fun boundedReportDoesNotImportAttachmentUris() {
        val state = AgentChatUiState(messages = emptyList(), input = "", isStreaming = false, thinkingEnabled = true)
        val updated = appendTaskConversationResult(state, result().copy(report = "x".repeat(10000)))
        val receipt = updated.messages.single() as AgentMessageUi
        assertTrue(receipt.content.length < 9000)
        assertTrue(receipt.content.contains("报告已截断"))
    }
}

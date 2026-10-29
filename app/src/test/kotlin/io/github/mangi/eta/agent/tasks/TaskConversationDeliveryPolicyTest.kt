package io.github.mangi.eta.agent.tasks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TaskConversationDeliveryPolicyTest {
    private val delivery = TaskConversationDeliveryStore.Delivery("run", "chat")
    private val result = TaskRunResult("run", conversationId = "chat", title = "Task",
        status = TaskRunStatus.Partial, summary = "Partial", report = "Report", startedAt = 1, completedAt = 2)

    @Test fun matchingExistingConversationCanAppend() {
        assertEquals(TaskConversationDeliveryPolicy.Decision.Append,
            TaskConversationDeliveryPolicy.decide(delivery, result, true, false))
    }

    @Test fun editingOrRunningDefersThenCanDeliverWhenNoLongerBusy() {
        assertEquals(TaskConversationDeliveryPolicy.Decision.Defer,
            TaskConversationDeliveryPolicy.decide(delivery, result, true, true))
        assertEquals(TaskConversationDeliveryPolicy.Decision.Append,
            TaskConversationDeliveryPolicy.decide(delivery, result, true, false))
    }

    @Test fun wrongOwnerWrongRunOrMissingResultCannotAppend() {
        listOf(null, result.copy(conversationId = "other"), result.copy(runId = "other")).forEach {
            assertEquals(TaskConversationDeliveryPolicy.Decision.Defer,
                TaskConversationDeliveryPolicy.decide(delivery, it, true, false))
        }
    }

    @Test fun deletedConversationIsAcknowledgedWithoutCreatingIt() {
        assertEquals(TaskConversationDeliveryPolicy.Decision.AcknowledgeDeleted,
            TaskConversationDeliveryPolicy.decide(delivery, result, false, false))
    }

    @Test fun failedSaveRetainsReferenceAndSuccessfulRetryAcksAfterSave() = runBlocking {
        val root = Files.createTempDirectory("task-delivery-policy").toFile()
        val outbox = TaskConversationDeliveryStore(root)
        outbox.enqueue(result)
        TaskConversationDeliveryPolicy.commit(outbox.pending(), persist = { false }, acknowledge = outbox::acknowledge)
        assertEquals(listOf(delivery), TaskConversationDeliveryStore(root).pending())
        val order = mutableListOf<String>()
        TaskConversationDeliveryPolicy.commit(outbox.pending(), persist = {
            order += "persist"
            assertEquals(listOf(delivery), outbox.pending())
            true
        }, acknowledge = {
            order += "ack"
            outbox.acknowledge(it)
        })
        assertEquals(listOf("persist", "ack"), order)
        assertTrue(TaskConversationDeliveryStore(root).pending().isEmpty())
    }

    @Test fun throwingSaveDoesNotAcknowledgeOrDeleteDurableReport() = runBlocking {
        val root = Files.createTempDirectory("task-delivery-failure").toFile()
        val outbox = TaskConversationDeliveryStore(java.io.File(root, "outbox"))
        val history = TaskRunResultStore(java.io.File(root, "results"))
        history.save(result)
        outbox.enqueue(result)
        val failure = runCatching {
            TaskConversationDeliveryPolicy.commit(outbox.pending(), persist = { error("disk failure") }, acknowledge = outbox::acknowledge)
        }
        assertTrue(failure.isFailure)
        assertEquals(listOf(delivery), outbox.pending())
        assertEquals(result, history.read(result.runId))
    }
}

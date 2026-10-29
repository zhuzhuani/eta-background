package io.github.mangi.eta.agent.tasks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
class TaskConversationDeliveryStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun result(id: String = "run-1", owner: String? = "conversation-1") = TaskRunResult(
        runId = id, conversationId = owner, title = "Command", status = TaskRunStatus.Succeeded,
        summary = "Done", report = "Report", startedAt = 1, completedAt = 2,
    )

    @Test fun survivesReopenAndDuplicateEnqueue() {
        val root = temporary.newFolder()
        TaskConversationDeliveryStore(root).enqueue(result())
        TaskConversationDeliveryStore(root).enqueue(result())
        val reopened = TaskConversationDeliveryStore(root)
        assertEquals(listOf(TaskConversationDeliveryStore.Delivery("run-1", "conversation-1")), reopened.pending())
        reopened.acknowledge(reopened.pending().single())
        assertTrue(TaskConversationDeliveryStore(root).pending().isEmpty())
    }

    @Test fun missingOwnerIsNotDeliveredAndConflictingOwnerIsRejected() {
        val store = TaskConversationDeliveryStore(temporary.newFolder())
        store.enqueue(result(owner = null))
        assertTrue(store.pending().isEmpty())
        store.enqueue(result())
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { store.enqueue(result(owner = "other")) }
        assertEquals("conversation-1", store.pending().single().conversationId)
    }

    @Test fun staleAcknowledgementCannotRemoveAnotherReference() {
        val store = TaskConversationDeliveryStore(temporary.newFolder())
        store.enqueue(result())
        store.acknowledge(TaskConversationDeliveryStore.Delivery("run-1", "other"))
        assertEquals(1, store.pending().size)
    }

    @Test fun corruptionIsNotSilentlyReplaced() {
        val root = temporary.newFolder()
        File(root, "pending.json").writeText("broken")
        val store = TaskConversationDeliveryStore(root)
        org.junit.Assert.assertThrows(Exception::class.java) { store.enqueue(result()) }
        assertEquals("broken", File(root, "pending.json").readText())
    }
}

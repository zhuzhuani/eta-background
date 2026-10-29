package io.github.mangi.eta.agent.tasks

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskRunResultStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory get() = File(temporary.root, "results")
    private fun result(id: String = "run/../one") = TaskRunResult(
        runId = id, taskId = "task", conversationId = "conversation", title = "Title",
        status = TaskRunStatus.Partial, summary = "Summary", report = "A durable report\n中文",
        startedAt = 1, completedAt = 2, diagnosticJobId = "job", source = "test",
    )

    @Test fun survivesRecreationWithExactExecutionKey() {
        val expected = result()
        assertEquals(expected, TaskRunResultStore(directory).save(expected))
        assertEquals(expected, TaskRunResultStore(directory).read(expected.runId))
        assertTrue(directory.listFiles()!!.filter { it.extension == "json" }.all {
            it.name.matches(Regex("[0-9a-f]{64}\\.json"))
        })
    }

    @Test fun idsAreIsolatedEvenWhenPathLike() {
        val store = TaskRunResultStore(directory)
        listOf("../one", "one", "a/one", "a\\one", "ONE").forEach { store.save(result(it)) }
        listOf("../one", "one", "a/one", "a\\one", "ONE").forEach { assertEquals(result(it), store.read(it)) }
    }

    @Test fun invalidAndOversizedRecordsAreRejected() {
        val store = TaskRunResultStore(directory)
        listOf("", " ", "a\u0000b", "x".repeat(1025)).forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { store.save(result(id)) }
            assertNull(store.read(id))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save(result().copy(report = "a".repeat(TaskRunResultStore.MAX_REPORT_CHARS + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) { store.save(result().copy(completedAt = 0)) }
    }

    @Test fun corruptAndTruncatedDataCannotBeSilentlyOverwritten() {
        val store = TaskRunResultStore(directory)
        store.save(result())
        val record = directory.listFiles()!!.single { it.extension == "json" }
        record.writeText("{\"runId\":")
        assertNull(store.read(result().runId))
        assertThrows(IOException::class.java) { store.save(result()) }
        record.writeBytes(ByteArray(TaskRunResultStore.MAX_JSON_BYTES + 1))
        assertNull(store.read(result().runId))
    }

    @Test fun wrongIdInsideRecordCannotBeReadAsAnotherExecution() {
        val store = TaskRunResultStore(directory)
        store.save(result("one"))
        val record = directory.listFiles()!!.single { it.extension == "json" }
        record.writeText(record.readText().replace("\"runId\":\"one\"", "\"runId\":\"two\""))
        assertNull(store.read("one"))
    }

    @Test fun concurrentRetriesAcrossStoresAreIdempotentAndConflictsAreVisible() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val futures = (1..12).map { pool.submit<TaskRunResult> {
                start.await()
                TaskRunResultStore(directory).save(result())
            } }
            start.countDown()
            futures.forEach { assertEquals(result(), it.get()) }
            assertThrows(IllegalStateException::class.java) {
                TaskRunResultStore(directory).save(result().copy(report = "different retry"))
            }
            assertEquals(result(), TaskRunResultStore(directory).read(result().runId))
        } finally { pool.shutdownNow() }
    }

    @Test fun copiesOnlyApprovedFilesAndKeepsPrivateContentAfterSourceDeletion() {
        val store = TaskRunResultStore(directory)
        val approved = temporary.newFolder("approved")
        val source = File(approved, "report.txt").apply { writeText("private report") }
        val attachment = store.importAttachment(source, approved, mimeType = "text/plain")
        val record = result().copy(attachments = listOf(attachment, TaskResultAttachment.https("https://example.org/report", "Link")))
        store.save(record)
        source.delete()
        val recreated = TaskRunResultStore(directory)
        assertEquals(record, recreated.read(record.runId))
        assertEquals("private report", recreated.resolveAttachment(attachment)!!.readText())
        val outside = temporary.newFile("outside")
        assertThrows(IllegalArgumentException::class.java) { store.importAttachment(outside, approved) }
        assertThrows(IllegalArgumentException::class.java) { store.importAttachment(File(approved, "../outside"), approved) }
        assertNull(store.resolveAttachment(attachment.copy(privateFile = "../outside")))
        assertThrows(IllegalArgumentException::class.java) { store.save(result("bad").copy(attachments = listOf(attachment.copy(privateFile = "../outside")))) }
    }

    @Test fun tamperedPrivateCopyAndUnsafeLinksAreRejected() {
        val store = TaskRunResultStore(directory)
        val approved = temporary.newFolder("approved")
        val source = File(approved, "data").apply { writeText("123") }
        val attachment = store.importAttachment(source, approved)
        val record = result("tamper").copy(attachments = listOf(attachment))
        store.save(record)
        store.resolveAttachment(attachment)!!.writeText("456")
        assertNull(store.resolveAttachment(attachment))
        assertEquals(record, TaskRunResultStore(directory).read(record.runId))
        listOf("http://example.org", "file:///tmp/data", "content://provider/data", "https://user:secret@example.org/", "https:///nohost").forEach {
            assertThrows(Exception::class.java) { TaskResultAttachment.https(it, "Report") }
        }
    }

    @Test fun attachmentSizeAndMetadataAreBounded() {
        val store = TaskRunResultStore(directory)
        val approved = temporary.newFolder("approved")
        val source = File(approved, "huge")
        java.io.RandomAccessFile(source, "rw").use { it.setLength(TaskRunResultStore.MAX_ATTACHMENT_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { store.importAttachment(source, approved) }
        assertThrows(IllegalArgumentException::class.java) { TaskResultAttachment.https("https://example.org", "Report", "invalid") }
    }

    @Test fun writeFailurePropagatesAndNeverReturnsPhantomSuccess() {
        val blocked = temporary.newFile("not-a-directory")
        val store = TaskRunResultStore(blocked)
        assertThrows(IOException::class.java) { store.save(result()) }
        assertNull(store.read(result().runId))
        assertEquals(0L, blocked.length())
    }
}

package io.github.mangi.eta.agent.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ScreenLeaseRegistryTest {
    @Test fun fourthTaskCancelsOldestButWaitsForCleanupAcknowledgement() {
        val registry = ScreenLeaseRegistry()
        val cancelled = CountDownLatch(1)
        registry.acquire("a", "app.a", {}, { cancelled.countDown() })
        registry.acquire("b", "app.b", {}, { fail("wrong victim") })
        registry.acquire("c", "app.c", {}, { fail("wrong victim") })
        val admitted = CountDownLatch(1)
        val worker = thread {
            registry.acquire("d", "app.d", {}, {})
            admitted.countDown()
        }
        try {
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertFalse(admitted.await(150, TimeUnit.MILLISECONDS))
            assertEquals(listOf("a", "b", "c"), registry.owners())
            registry.release("a")
            assertTrue(admitted.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("b", "c", "d"), registry.owners())
        } finally { worker.interrupt(); worker.join(2000) }
    }

    @Test fun sameAppWaitsWhileUnrelatedAppsCanAdvance() {
        val registry = ScreenLeaseRegistry()
        registry.acquire("a", "shared.app", {}, { fail("same-app wait must not evict") })
        val admitted = CountDownLatch(1)
        val worker = thread { registry.acquire("b", "shared.app", {}, {}); admitted.countDown() }
        try {
            registry.acquire("c", "another.app", {}, {})
            assertFalse(admitted.await(150, TimeUnit.MILLISECONDS))
            assertEquals(listOf("a", "c"), registry.owners())
            registry.release("a")
            assertTrue(admitted.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("c", "b"), registry.owners())
        } finally { worker.interrupt(); worker.join(2000) }
    }

    @Test fun cancelledWaiterNeverTakesOwnership() {
        val registry = ScreenLeaseRegistry()
        val cancelled = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        registry.acquire("a", "shared.app", {}, {})
        val worker = thread {
            try {
                registry.acquire("b", "shared.app", { if (cancelled.get()) throw AgentRunCancelledException() }, {})
            } catch (_: AgentRunCancelledException) { finished.countDown() }
        }
        try {
            cancelled.set(true)
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("a"), registry.owners())
        } finally { worker.interrupt(); worker.join(2000) }
    }
}

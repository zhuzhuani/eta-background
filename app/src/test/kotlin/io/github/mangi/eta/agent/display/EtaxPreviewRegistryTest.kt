package io.github.mangi.eta.agent.display

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EtaxPreviewRegistryTest {
    private fun session(id: String = "binding-1") = EtaxPreviewSession(id, "run-1", "app.one", "One")

    @Test fun registryContainsOnlyExplicitlyRegisteredActiveSessions() = runBlocking {
        val registry = EtaxPreviewSessionStore()
        val captures = AtomicInteger()
        assertTrue(registry.sessions.value.isEmpty())
        assertNull(registry.capture("missing"))
        val token = registry.register(session()) { captures.incrementAndGet(); "preview-only" }
        assertEquals(listOf(session()), registry.sessions.value)
        assertEquals("preview-only", registry.capture("binding-1"))
        registry.unregister(token)
        assertTrue(registry.sessions.value.isEmpty())
        assertNull(registry.capture("binding-1"))
        assertEquals(1, captures.get())
    }

    @Test fun cleanupDuringCaptureDiscardsLateFrameWithoutBlockingDetach() = runBlocking {
        val registry = EtaxPreviewSessionStore()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = registry.register(session()) {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            "stale-frame"
        }
        val frame = async(kotlinx.coroutines.Dispatchers.Default) { registry.capture("binding-1") }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            registry.unregister(token)
            assertTrue(registry.sessions.value.isEmpty())
        } finally { release.countDown() }
        assertNull(frame.await())
    }

    @Test fun oldRegistrationCannotDetachAnotherBindingAndFailuresAreReadOnly() = runBlocking {
        val registry = EtaxPreviewSessionStore()
        val old = registry.register(session()) { error("engine unavailable") }
        assertNull(registry.capture("binding-1"))
        registry.unregister(old)
        val replacement = registry.register(session("binding-2")) { "new-frame" }
        registry.unregister(old)
        assertEquals(listOf(session("binding-2")), registry.sessions.value)
        assertNull(registry.capture("binding-1"))
        assertEquals("new-frame", registry.capture("binding-2"))
        registry.unregister(replacement)
    }

    @Test fun duplicateLiveBindingCannotReplaceItsCaptureCapability() {
        val registry = EtaxPreviewSessionStore()
        registry.register(session()) { "first" }
        assertThrows(IllegalStateException::class.java) { registry.register(session()) { "second" } }
        assertEquals(listOf(session()), registry.sessions.value)
    }
}

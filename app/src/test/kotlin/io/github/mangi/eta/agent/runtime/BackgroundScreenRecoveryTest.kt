package io.github.mangi.eta.agent.runtime

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class BackgroundScreenRecoveryTest {
    private class Connection : ScreenToolConnection {
        val attempts = AtomicInteger()
        @Volatile var failures = 0
        @Volatile var releaseAllowed = true
        var pendingLaunches = 0
        val calls = CopyOnWriteArrayList<String>()
        override fun execute(name: String, arguments: String): String {
            calls += name
            if (name == "screen_open" && pendingLaunches-- > 0) return "{\"ok\":false,\"code\":\"CLEANUP_PENDING\"}"
            return "{\"ok\":true}"
        }
        override fun release(keepFinished: Boolean) = attempts.incrementAndGet() > failures && releaseAllowed
        override fun keepAlive() = Unit
    }

    @Test fun retriesOnlyEngineConfirmedPendingCleanupBeforeLaunch() {
        val connection = Connection().apply { pendingLaunches = 2 }
        val opened = mutableListOf<String>()
        val tools = BackgroundScreenTools("parked-reclaim", AgentRunController(), onAppOpened = { opened += it }, registry = ScreenLeaseRegistry(),
            connectionFactory = { connection }, retryDelayMs = 20)
        try {
            assertEquals("{\"ok\":true}", tools.execute("screen_open", "{\"package\":\"app.one\"}"))
            assertEquals(3, connection.calls.size)
            assertEquals(listOf("app.one"), opened)
        } finally { tools.close() }
    }

    @Test fun cleanupRecoversAfterMoreThanThreeFailuresAndNextAppCanOpen() {
        val registry = ScreenLeaseRegistry()
        val connections = CopyOnWriteArrayList<Connection>()
        val tools = BackgroundScreenTools("recovery", AgentRunController(), registry = registry,
            connectionFactory = { Connection().also { connections += it } }, retryDelayMs = 20)
        try {
            tools.execute("screen_open", "{\"package\":\"app.one\"}")
            connections.single().failures = 4
            tools.execute("screen_open", "{\"package\":\"app.two\"}")
            assertTrue(connections.first().attempts.get() >= 5)
            assertEquals(2, connections.size)
            assertEquals(1, registry.owners().size)
            assertEquals(listOf("screen_open"), connections.last().calls.toList())
        } finally { tools.close() }
    }

    @Test fun finishReleasesOwnershipBeforeModelReplyAndDoesNotStopAnotherTask() {
        val registry = ScreenLeaseRegistry()
        val a = Connection().apply { releaseAllowed = false }
        val b = Connection()
        val first = BackgroundScreenTools("a", AgentRunController(), registry = registry, connectionFactory = { a }, retryDelayMs = 20)
        val second = BackgroundScreenTools("b", AgentRunController(), registry = registry, connectionFactory = { b }, retryDelayMs = 20)
        val opened = CountDownLatch(1)
        var waiter: Thread? = null
        try {
            first.execute("screen_open", "{\"package\":\"app.shared\"}")
            first.execute("screen_finish", "{}")
            waiter = thread {
                second.execute("screen_open", "{\"package\":\"app.shared\"}")
                opened.countDown()
            }
            assertFalse(opened.await(100, TimeUnit.MILLISECONDS))
            a.releaseAllowed = true
            assertTrue(opened.await(3, TimeUnit.SECONDS))
            first.close() // A late close must never release B's screen.
            assertEquals("{\"ok\":true}", second.execute("screen_tap", "{}"))
            assertEquals(0, b.attempts.get())
        } finally {
            a.releaseAllowed = true
            first.close(); second.close()
            waiter?.join(3000)
        }
    }
}

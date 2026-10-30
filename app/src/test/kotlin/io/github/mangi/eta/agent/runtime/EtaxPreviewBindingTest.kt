package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.display.EtaxPreviewSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class EtaxPreviewBindingTest {
    private class Connection : ScreenToolConnection {
        val calls = CopyOnWriteArrayList<String>()
        var openOk = true
        var releaseAllowed = true
        override fun execute(name: String, arguments: String): String {
            calls += name
            return if (name == "screen_open" && !openOk) """{"ok":false,"code":"LAUNCH_FAILED"}""" else """{"ok":true}"""
        }
        override fun previewFrame(): String { calls += "previewFrame"; return """{"ok":true,"path":"preview-only.png"}""" }
        override fun release(keepFinished: Boolean): Boolean { calls += "release"; return releaseAllowed }
        override fun keepAlive() { calls += "keepAlive" }
    }

    @Test fun successfulOpenPublishesBindingAndPreviewNeverUsesModelScreenshotTool() = runBlocking {
        val registry = EtaxPreviewSessionStore()
        val connection = Connection()
        val tools = BackgroundScreenTools("run", AgentRunController(), registry = ScreenLeaseRegistry(),
            connectionFactory = { connection }, previewRegistry = registry)
        try {
            assertTrue(registry.sessions.value.isEmpty())
            tools.execute("screen_open", """{"package":"app.one","app_name":"One"}""")
            val session = registry.sessions.value.single()
            assertEquals("run", session.runId)
            assertEquals("app.one", session.packageName)
            assertEquals("One", session.title)
            assertTrue(session.sessionId.startsWith("run:"))
            assertTrue(registry.capture(session.sessionId)!!.contains("preview-only.png"))
            assertEquals(listOf("screen_open", "previewFrame"), connection.calls.toList())
            tools.execute("screen_finish", "{}")
            assertTrue(registry.sessions.value.isEmpty())
            assertNull(registry.capture(session.sessionId))
        } finally { tools.close() }
    }

    @Test fun failedLaunchNeverRegistersAndCloseDetachesBeforeEngineCleanupAcknowledges() {
        val registry = EtaxPreviewSessionStore()
        val connection = Connection().apply { openOk = false; releaseAllowed = false }
        val tools = BackgroundScreenTools("run", AgentRunController(), registry = ScreenLeaseRegistry(),
            connectionFactory = { connection }, previewRegistry = registry, retryDelayMs = 10)
        try {
            tools.execute("screen_open", """{"package":"app.one"}""")
            assertTrue(registry.sessions.value.isEmpty())
            connection.openOk = true
            tools.execute("screen_open", """{"package":"app.one"}""")
            assertEquals(1, registry.sessions.value.size)
            tools.close()
            assertTrue(registry.sessions.value.isEmpty())
        } finally { connection.releaseAllowed = true; tools.close() }
    }

    @Test fun switchingApplicationsDetachesOldBindingRatherThanReusingPreviewIdentity() = runBlocking {
        val registry = EtaxPreviewSessionStore()
        val tools = BackgroundScreenTools("run", AgentRunController(), registry = ScreenLeaseRegistry(),
            connectionFactory = { Connection() }, previewRegistry = registry, retryDelayMs = 10)
        try {
            tools.execute("screen_open", """{"package":"app.one"}""")
            val old = registry.sessions.value.single().sessionId
            tools.execute("screen_open", """{"package":"app.two"}""")
            val next = registry.sessions.value.single()
            assertNotEquals(old, next.sessionId)
            assertEquals("app.two", next.packageName)
            assertNull(registry.capture(old))
        } finally { tools.close() }
    }
}

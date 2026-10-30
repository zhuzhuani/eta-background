package io.github.mangi.eta.agent.display

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Public metadata contains no engine connection or command capability. Only actively owned screens appear. */
internal data class EtaxPreviewSession(
    val sessionId: String,
    val runId: String,
    val packageName: String,
    val title: String,
)

/** Read-only bridge to existing bindings. It never acquires leases, launches apps or sends keep-alives. */
internal class EtaxPreviewSessionStore {
    internal class Registration internal constructor(internal val entry: Entry)
    internal class Entry(val session: EtaxPreviewSession, capture: () -> String?) {
        val active = AtomicBoolean(true)
        @Volatile var capture: (() -> String?)? = capture
        val captureLock = Any()
    }
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private val mutableSessions = MutableStateFlow<List<EtaxPreviewSession>>(emptyList())
    val sessions: StateFlow<List<EtaxPreviewSession>> = mutableSessions.asStateFlow()

    internal fun register(session: EtaxPreviewSession, capture: () -> String?): Registration = synchronized(lock) {
        require(session.sessionId.isNotBlank() && session.runId.isNotBlank() && session.packageName.isNotBlank())
        // Session IDs are binding identities, never reusable handles to a new screen.
        check(session.sessionId !in entries) { "Preview binding already registered" }
        val entry = Entry(session, capture)
        entries[session.sessionId] = entry
        publish()
        Registration(entry)
    }

    internal fun unregister(registration: Registration) = synchronized(lock) {
        val entry = registration.entry
        entry.active.set(false)
        entry.capture = null
        if (entries[entry.session.sessionId] === entry) {
            entries.remove(entry.session.sessionId)
            publish()
        }
    }

    suspend fun capture(sessionId: String): String? = withContext(Dispatchers.IO) {
        val entry = synchronized(lock) { entries[sessionId] } ?: return@withContext null
        synchronized(entry.captureLock) {
            if (!entry.active.get()) return@synchronized null
            val read = entry.capture ?: return@synchronized null
            val frame = runCatching(read).getOrNull()
            // Cleanup may happen during an engine request. Never publish a detached frame to the UI.
            if (entry.active.get() && synchronized(lock) { entries[sessionId] === entry }) frame else null
        }
    }

    private fun publish() { mutableSessions.value = entries.values.map { it.session } }
}

internal object EtaxPreviewRegistry {
    internal val store = EtaxPreviewSessionStore()
    val sessions: StateFlow<List<EtaxPreviewSession>> = store.sessions
    suspend fun capture(sessionId: String): String? = store.capture(sessionId)
}

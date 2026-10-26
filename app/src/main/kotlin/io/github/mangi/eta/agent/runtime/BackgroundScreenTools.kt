package io.github.mangi.eta.agent.runtime

import com.eta.shadow.ShadowTools
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Capacity is reserved before launch; a cancelled owner keeps its slot until engine cleanup ACK. */
internal class ScreenLeaseRegistry(private val capacity: Int = 3) {
    private data class Lease(val owner: String, val app: String, val stop: () -> Unit, var evicting: Boolean = false)
    private val monitor = Object()
    private val leases = linkedMapOf<String, Lease>()

    fun acquire(owner: String, app: String, checkCancelled: () -> Unit, stop: () -> Unit) {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
        while (true) {
            checkCancelled()
            var victim: (() -> Unit)? = null
            synchronized(monitor) {
                if (leases[owner]?.app == app) return
                // Two tasks using one Android app must never race its existing Activity task.
                if (leases.values.none { it.app == app }) {
                    if (leases.size < capacity) {
                        leases[owner] = Lease(owner, app, stop)
                        return
                    }
                    if (leases.values.none { it.evicting }) {
                        leases.values.first().also { it.evicting = true; victim = it.stop }
                    }
                }
                if (victim == null) monitor.wait(100)
            }
            victim?.invoke() // cancellation may initiate cleanup; never call it under the registry lock
            check(System.nanoTime() < deadline) { "等待后台应用或屏幕释放超时，请检查正在运行的任务" }
        }
    }

    fun release(owner: String) = synchronized(monitor) {
        leases.remove(owner)
        monitor.notifyAll()
    }

    internal fun owners(): List<String> = synchronized(monitor) { leases.keys.toList() }
}

internal class BackgroundScreenTools(
    private val runId: String,
    private val controller: AgentRunController,
) : AutoCloseable {
    private class Binding(val id: String, val app: String) {
        val tools = ShadowTools(id)
        val closing = java.util.concurrent.atomic.AtomicBoolean()
        val released = CountDownLatch(1)
        var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null
    }
    private val lock = Any()
    private var binding: Binding? = null
    @Volatile private var closed = false

    fun execute(name: String, arguments: String): String? {
        if (!name.startsWith("screen_") && name != "terminal") return null
        controller.throwIfCancelled()
        if (name == "screen_open") {
            val args = JSONObject(arguments)
            val app = sequenceOf("component", "package", "app_name")
                .map { args.optString(it).trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().substringBefore('/')
            if (!app.contains('.')) return "需要有效的应用包名"
            val previous = synchronized(lock) { binding }
            if (previous != null && previous.app != app) {
                cleanup(previous)
                if (!previous.released.await(40, TimeUnit.SECONDS)) error("旧屏幕尚未释放，未打开新应用")
            }
            var next = synchronized(lock) { binding?.takeIf { it.app == app && !it.closing.get() } }
            if (next == null) {
                next = Binding("$runId:${UUID.randomUUID()}", app)
                val candidate = next
                leases.acquire(candidate.id, app, controller::throwIfCancelled) {
                    controller.cancel("已停止：三个虚拟屏已占满，本任务按创建顺序被新任务替换")
                }
                synchronized(lock) {
                    if (closed || controller.isCancelled) {
                        leases.release(candidate.id)
                        controller.throwIfCancelled()
                        error("任务已结束")
                    }
                    binding = candidate
                    candidate.heartbeat = heartbeat.scheduleWithFixedDelay(candidate.tools::keepAlive, 30, 30, TimeUnit.SECONDS)
                }
            }
            return next.tools.intercept(name, arguments)
        }
        val current = synchronized(lock) { binding }
        if (current == null) {
            // A terminal guard and health query need no GUI session, but node reads never target display 0 implicitly.
            return if (name == "terminal" || name == "screen_state") ShadowTools(runId).intercept(name, arguments)
            else "后台屏幕尚未打开，请先调用 screen_open"
        }
        return current.tools.intercept(name, arguments)
    }

    private fun cleanup(value: Binding) {
        if (!value.closing.compareAndSet(false, true)) return
        value.heartbeat?.cancel(false)
        thread(name = "eta-screen-release", isDaemon = true) {
            // A transient socket timeout is not a cleanup acknowledgement. Retry the idempotent close.
            var released = false
            repeat(3) { if (!released) released = value.tools.closeSession(!controller.isCancelled) }
            if (released) {
                leases.release(value.id)
                synchronized(lock) { if (binding === value) binding = null }
                value.released.countDown()
            }
        }
    }

    override fun close() {
        closed = true
        synchronized(lock) { binding }?.let(::cleanup)
    }

    companion object {
        private val leases = ScreenLeaseRegistry()
        private val heartbeat = java.util.concurrent.Executors.newScheduledThreadPool(3) { runnable ->
            Thread(runnable, "eta-screen-heartbeat").apply { isDaemon = true }
        }
    }
}

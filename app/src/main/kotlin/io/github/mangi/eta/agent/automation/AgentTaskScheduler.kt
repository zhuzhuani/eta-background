package io.github.mangi.eta.agent.automation

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.AgentTaskRunEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Experimental scheduler: durable queue, inexact alarms and best-effort live system events. */
internal object AgentTaskScheduler {
    private const val JOB_ID = 1110
    private val eventWorker = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(100),
        { r -> Thread(r, "eta-trigger-events").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())
    private val cancellationWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "eta-task-cancel").apply { isDaemon = true } }
    private val activeRuns = ConcurrentHashMap<String, String>()
    private val activeStops = ConcurrentHashMap<String, () -> Unit>()
    private val running = AtomicBoolean()
    private val jobLock = Any()
    private val startupRecovered = AtomicBoolean()

    fun jobStarted(): Boolean = synchronized(jobLock) { running.compareAndSet(false, true) }
    fun jobFinished() = synchronized(jobLock) { running.set(false) }
    fun isRegistered(runId: String) = activeRuns.containsKey(runId)
    fun register(runId: String, taskId: String, onCancel: () -> Unit = {}) { activeRuns[runId] = taskId; activeStops[runId] = onCancel }
    fun unregister(runId: String) { activeRuns.remove(runId); activeStops.remove(runId) }

    /** Called once after application initialization, never resumes an interrupted action. */
    fun initialize(context: Context) {
        if (!startupRecovered.compareAndSet(false, true)) return
        Thread({
            runCatching {
                synchronized(jobLock) {
                    if (!running.get()) runBlocking {
                        EtaDatabase.get(context).agentTaskDao().recoverInterrupted(System.currentTimeMillis())
                    }
                }
                enqueueDue(context)
                refresh(context)
            }.onFailure { startupRecovered.set(false); AndroidAgentLogger.warn("Automation startup recovery failed") }
        }, "eta-automation-startup").apply { isDaemon = true }.start()
    }

    fun cancelRunning(context: Context, taskId: String) {
        activeRuns.filterValues { it == taskId }.keys.forEach { id ->
            activeStops[id]?.invoke() // Interrupt request handoff too; a cancel arriving before Binder START must not be lost.
            cancellationWorker.execute { AgentRuntimeClient(context, AndroidAgentLogger).cancelRun(id) }
        }
    }

    fun refresh(context: Context) = runBlocking {
        val dao = EtaDatabase.get(context).agentTaskDao()
        val tasks = dao.tasks().filter { it.enabled && it.runCount < it.maxRuns }
        val now = System.currentTimeMillis()
        val current = state(context)
        val earliest = tasks.mapNotNull { task -> task.nextRunAt?.let { due ->
            if (due <= now && !AgentTaskRules.conditionsMatch(JSONObject(task.triggerJson), current)) now + 900_000 else due
        } }.minOrNull()
        val alarm = PendingIntent.getBroadcast(context, JOB_ID,
            Intent(context, AgentTaskReceiver::class.java).setAction(AgentTaskReceiver.ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(alarm)
        if (earliest != null) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(earliest, now + 60_000), alarm)
        val queued = dao.nextQueued() != null
        synchronized(jobLock) {
            if (!running.get()) {
                if (queued) requestJob(context)
                else if (earliest != null) requestJob(context, (earliest - now).coerceAtLeast(0))
                else context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
            }
        }
        if (tasks.any { JSONObject(it.triggerJson).optString("type") in monitoredEventTypes }) {
            runCatching { context.startForegroundService(Intent(context, AgentTriggerMonitorService::class.java)) }
                .onFailure { AndroidAgentLogger.warn("Trigger monitor requires a foreground user entry") }
        } else context.stopService(Intent(context, AgentTriggerMonitorService::class.java))
    }

    fun requestJob(context: Context, delayMs: Long = 0): Unit = synchronized(jobLock) {
        if (running.get()) return
        val delay = delayMs.coerceIn(0, 365L * 86400_000)
        // Never replace a running Job: its uncertain in-flight GUI action must not be replayed.
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AgentTaskJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(delay)
            .setPersisted(true).setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        if (context.getSystemService(JobScheduler::class.java).schedule(job) != JobScheduler.RESULT_SUCCESS)
            AndroidAgentLogger.warn("Automation job scheduling rejected")
    }

    fun state(context: Context): Map<String, Boolean> {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return mapOf("charging" to ((battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0),
            "unlocked" to !context.getSystemService(KeyguardManager::class.java).isDeviceLocked,
            "networkConnected" to (context.getSystemService(ConnectivityManager::class.java).activeNetwork != null))
    }

    fun enqueueDue(context: Context, now: Long = System.currentTimeMillis()) = runBlocking {
        val dao = EtaDatabase.get(context).agentTaskDao()
        val current = state(context)
        dao.tasks().filter { it.enabled && it.nextRunAt?.let { due -> due <= now } == true }.forEach { task ->
            val rule = JSONObject(task.triggerJson)
            if (AgentTaskRules.conditionsMatch(rule, current)) dao.enqueue(task,
                AgentTaskRunEntity(UUID.randomUUID().toString(), task.id, "time:${task.nextRunAt}", queuedAt = now),
                AgentTaskRules.nextTime(rule, now, false))
        }
    }

    fun enqueueManual(context: Context, task: AgentTaskEntity): Boolean = runBlocking {
        EtaDatabase.get(context).agentTaskDao().enqueue(task,
            AgentTaskRunEntity(UUID.randomUUID().toString(), task.id, "manual:${UUID.randomUUID()}", queuedAt = System.currentTimeMillis()),
            task.nextRunAt, manual = true).also { if (it) requestJob(context) }
    }

    fun publish(context: Context, type: String, key: String, data: JSONObject = JSONObject()) {
        if (type !in AgentTaskRules.eventTypes) return
        val app = context.applicationContext
        val copy = JSONObject(data.toString())
        eventWorker.execute {
            runCatching { runBlocking {
                val dao = EtaDatabase.get(app).agentTaskDao()
                val now = System.currentTimeMillis()
                val current = state(app)
                var queued = false
                dao.tasks().filter { it.enabled }.forEach { task ->
                    val rule = JSONObject(task.triggerJson)
                    if (AgentTaskRules.matches(rule, type, copy) && AgentTaskRules.conditionsMatch(rule, current)) {
                        val event = JSONObject().put("type", type)
                        if (copy.has("transport")) event.put("transport", copy.optString("transport").take(100))
                        queued = dao.enqueue(task, AgentTaskRunEntity(UUID.randomUUID().toString(), task.id,
                            "$type:${key.take(200)}", eventJson = event.toString(), queuedAt = now), null) || queued
                    }
                }
                if (queued) requestJob(app)
            } }.onFailure { AndroidAgentLogger.warn("Trigger event could not be queued") }
        }
    }

    fun capabilities(context: Context): JSONObject {
        val supported = AgentTaskRules.timeTypes + AgentTaskRules.eventTypes
        val triggers = JSONArray()
        supported.forEach { type -> triggers.put(JSONObject().put("type", type).put("available", available(context, type))
            .put("scope", if (type in AgentTaskRules.timeTypes) "Inexact AlarmManager + JobScheduler; may be delayed" else "Only while monitor is alive; boot restores schedules, not interrupted actions")) }
        setOf("notification_posted", "notification_removed", "app_foreground", "memory_updated", "task_completed", "task_failed",
            "location_enter", "location_exit", "calendar_near", "file_changed", "shared_content", "vivo_memory_updated").forEach {
            triggers.put(JSONObject().put("type", it).put("available", false).put("scope", "Not wired in this experimental port"))
        }
        return JSONObject().put("triggers", triggers).put("monitorRunning", AgentTriggerMonitorService.isRunning)
            .put("conditions", JSONArray(listOf("charging", "unlocked", "networkConnected")))
    }
    fun requireTriggerAvailable(context: Context, rule: JSONObject) {
        require(available(context, rule.getString("type"))) { "触发器未实现或未授权；可 enabled=false 保存" }
    }
    private fun available(context: Context, type: String): Boolean = when (type) {
        "bluetooth_connected", "bluetooth_disconnected" -> context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else -> type in AgentTaskRules.timeTypes || type in AgentTaskRules.eventTypes
    }
    private val monitoredEventTypes = AgentTaskRules.eventTypes - "device_boot"
}

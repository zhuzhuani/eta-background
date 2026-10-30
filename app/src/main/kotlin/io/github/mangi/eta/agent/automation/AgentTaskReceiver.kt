package io.github.mangi.eta.agent.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

internal class AgentTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(ACTION_TICK, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        worker.execute {
            try {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    runBlocking {
                        io.github.mangi.eta.data.db.EtaDatabase.get(context).agentTaskDao()
                            .recoverInterrupted(System.currentTimeMillis())
                    }
                    AgentTaskScheduler.publish(
                        context,
                        "device_boot",
                        System.currentTimeMillis().toString()
                    )
                }
                AgentTaskScheduler.enqueueDue(context)
                AgentTaskScheduler.refresh(context)
            } catch (_: Exception) {
                io.github.mangi.eta.core.AndroidAgentLogger.warn("Automation receiver could not restore schedules")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TICK = "io.github.mangi.eta.action.AUTOMATION_TICK"
        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(
                runnable,
                "eta-task-alarm"
            ).apply { isDaemon = true }
        }
    }
}

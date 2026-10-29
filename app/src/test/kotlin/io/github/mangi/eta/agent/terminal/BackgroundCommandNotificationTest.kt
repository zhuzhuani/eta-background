package io.github.mangi.eta.agent.terminal

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import io.github.mangi.eta.agent.tasks.TaskCommandResultReader
import io.github.mangi.eta.agent.tasks.TaskRunResultStore
import io.github.mangi.eta.agent.tasks.TaskRunStatus
import io.github.mangi.eta.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackgroundCommandNotificationTest {
    private fun task(id: String) = DetachedTask(id, 42, "token", "echo secret-command", "/private", "user",
        TerminalEnvironment.ANDROID, "/private/log", 1, commandTask = true, description = "private task title",
        completedAt = 2, exitCode = 0)

    @Test fun notificationUsesPersistedSummaryAndPrivateDetailsNavigation() {
        val context = RuntimeEnvironment.getApplication()
        val result = TaskCommandResultReader.read(task("notification-job"),
            """{"status":"Succeeded","summary":"实际生成了两份报告","report":"private full report"}""")
        val saved = TaskRunResultStore.forContext(context).save(result)
        BackgroundCommandNotification.show(context, saved)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals("${saved.title} · 任务已完成", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(saved.summary, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(saved.summary, notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertFalse(notification.extras.toString().contains("secret-command"))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertFalse(notification.publicVersion.extras.toString().contains(saved.summary))
        assertFalse(notification.publicVersion.extras.toString().contains(saved.title))
        assertFalse(notification.publicVersion.extras.toString().contains(saved.report))
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity.ACTION_OPEN_TASK_RUN, intent.action)
        assertEquals(saved.runId, intent.getStringExtra(MainActivity.EXTRA_TASK_RUN_ID))
        assertEquals("eta-task://run/notification-job", intent.dataString)
    }

    @Test fun uniqueExecutionIntentsAndStableTagPreventOverwritingOrDuplicateReplay() {
        val context = RuntimeEnvironment.getApplication()
        val first = TaskCommandResultReader.read(task("first-job"))
        val second = TaskCommandResultReader.read(task("second-job"))
        assertEquals(TaskRunStatus.Unconfirmed, first.status)
        BackgroundCommandNotification.show(context, first)
        BackgroundCommandNotification.show(context, second)
        BackgroundCommandNotification.show(context, first)
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertEquals(2, notifications.size)
        val intents = notifications.map { shadowOf(it.notification.contentIntent).savedIntent }
        assertEquals(setOf("eta-task://run/first-job", "eta-task://run/second-job"), intents.map { it.dataString }.toSet())
        assertEquals(setOf(first.runId, second.runId), intents.map { it.getStringExtra(MainActivity.EXTRA_TASK_RUN_ID) }.toSet())
    }
}

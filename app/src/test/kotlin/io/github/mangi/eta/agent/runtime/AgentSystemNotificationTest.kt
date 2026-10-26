package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AgentSystemNotificationTest {
    @Test fun nativeCardKeepsOneNotificationAndStableChatAndTaskDestinations() {
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        val service = controller.get()
        val manager = service.getSystemService(NotificationManager::class.java)
        try {
            assertTrue(AgentExecutionService.usesSystemTaskSurface())
            AgentExecutionService.acquire(service, "test-native", onStop = {})
            AgentExecutionService.updateRunStatus(service, AgentOverlayStatus.GeneratingAnswer, 3,
                "正在查询天气", "chat-a", "run-a", "查询天气", false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            val first = manager.activeNotifications.single().notification
            val firstDestination = first.contentIntent
            assertTrue("flags=${first.flags}, extras=${first.extras}, actions=${first.actions?.size}",
                first.hasPromotableCharacteristics())
            assertEquals(3, first.actions.size)
            assertTrue(first.actions.all { shadowOf(it.actionIntent).savedIntent.getStringExtra("run_id") == "run-a" })
            AgentExecutionService.updateRunStatus(service, AgentOverlayStatus.Paused, 3,
                "查询到设备数量", "chat-b", "run-b", "查询设备", true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            val second = manager.activeNotifications.single().notification
            assertEquals(second.extras.getCharSequence(Notification.EXTRA_TEXT),
                shadowOf(service).lastForegroundNotification.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals("chat-a", shadowOf(firstDestination).savedIntent.getStringExtra(MainActivity.EXTRA_CONVERSATION_ID))
            assertEquals("chat-b", shadowOf(second.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_CONVERSATION_ID))
            assertEquals(AgentExecutionService.ACTION_RESUME_RUN, shadowOf(second.actions[1].actionIntent).savedIntent.action)
        } finally {
            AgentExecutionService.release("test-native")
            AgentExecutionService.updateRunStatus(service, null, 0)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            controller.destroy()
            manager.cancelAll()
        }
    }

    @Test fun completionReplacesOngoingCardAfterLeaseReleaseWithoutPostingASecondCard() {
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        val service = controller.get()
        val manager = service.getSystemService(NotificationManager::class.java)
        try {
            AgentExecutionService.acquire(service, "test-completion", onStop = {})
            AgentExecutionService.updateRunStatus(service, AgentOverlayStatus.GeneratingAnswer, 1,
                "处理中", "chat-complete", "run-complete", "测试", false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            val originalId = manager.activeNotifications.single().id
            AgentExecutionService.publishRunCompletion(service, "run-complete", "chat-complete", "已完成", "查询完成")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, manager.activeNotifications.size)
            assertTrue(manager.activeNotifications.single().notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            AgentExecutionService.release("test-completion")
            AgentExecutionService.updateRunStatus(service, null, 0)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            val completed = manager.activeNotifications.single()
            assertEquals(originalId, completed.id)
            assertFalse(completed.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals("查询完成", completed.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
            assertEquals("chat-complete", shadowOf(completed.notification.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_CONVERSATION_ID))
            // Repeated release callbacks must not remove the result that replaced the foreground card.
            AgentExecutionService.release("test-completion")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            assertEquals(originalId, manager.activeNotifications.single().id)
        } finally {
            AgentExecutionService.release("test-completion")
            AgentExecutionService.updateRunStatus(service, null, 0)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            controller.destroy()
            manager.cancelAll()
        }
    }
}

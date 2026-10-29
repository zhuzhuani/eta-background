package io.github.mangi.eta.ui

import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.ui.app.ArchivedConversationRequest
import io.github.mangi.eta.ui.voice.ACTION_SPEECH_SETTINGS
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MainActivityLaunchRequestsTest {
    @Test fun `继续指令保留会话与输入焦点请求且不会在消费后重放`() {
        val intent = Intent(MainActivity.ACTION_OPEN_CONVERSATION_ID)
            .putExtra(MainActivity.EXTRA_CONVERSATION_ID, "game-chat")
            .putExtra(MainActivity.EXTRA_COMPOSE_REPLY, true)
        val pending = MainActivityLaunchRequests().accept(intent)
        assertEquals("game-chat", pending.conversationId)
        assertEquals(true, pending.composeReply)
        assertEquals(pending, MainActivityLaunchRequests.restore(savedState(pending), intent))
        val consumed = pending.copy(conversationId = null, composeReply = false)
        assertEquals(consumed, MainActivityLaunchRequests.restore(savedState(consumed), intent))
        val history = pending.accept(Intent(MainActivity.ACTION_OPEN_CONVERSATION_ID)
            .putExtra(MainActivity.EXTRA_CONVERSATION_ID, "another-chat"))
        assertEquals(false, history.composeReply)
    }

    @Test
    fun `重建不重新执行已消费的启动会话`() {
        val originalIntent = Intent(EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION)
            .putExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY, "old-conversation")
        val pending = MainActivityLaunchRequests.restore(null, originalIntent)
        assertEquals("old-conversation", pending.assistantConversationKey)
        val consumed = pending.copy(assistantConversationKey = null)

        val restored = MainActivityLaunchRequests.restore(savedState(consumed), originalIntent)

        assertEquals(MainActivityLaunchRequests(), restored)
    }

    @Test
    fun `重建保留尚未完成的跳转且不被原始 Intent 覆盖`() {
        val pending = MainActivityLaunchRequests(
            assistantConversationKey = "assistant",
            conversationId = "conversation",
            archivedConversation = ArchivedConversationRequest("source", "archive"),
            speechSettings = true,
            terminal = true,
            taskRunId = "pending-task",
        )

        val restored = MainActivityLaunchRequests.restore(
            savedState(pending),
            Intent(MainActivity.ACTION_OPEN_CONVERSATION_ID)
                .putExtra(MainActivity.EXTRA_CONVERSATION_ID, "original"),
        )

        assertEquals(pending, restored)
    }

    @Test
    fun `桌面再次进入不产生跳转也不清除尚未完成的请求`() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pending = MainActivityLaunchRequests(conversationId = "conversation")

        assertEquals(MainActivityLaunchRequests(), MainActivityLaunchRequests().accept(launcher))
        assertEquals(pending, pending.accept(launcher))
    }

    @Test
    fun `真正的新 Intent 在恢复后仍能打开目标入口`() {
        val restored = MainActivityLaunchRequests.restore(savedState(MainActivityLaunchRequests()), null)

        assertEquals(
            MainActivityLaunchRequests(terminal = true),
            restored.accept(Intent(MainActivity.ACTION_OPEN_TERMINAL)),
        )
        assertEquals(
            MainActivityLaunchRequests(speechSettings = true),
            restored.accept(Intent(ACTION_SPEECH_SETTINGS)),
        )
        assertEquals(
            MainActivityLaunchRequests(archivedConversation = ArchivedConversationRequest("source", "archive")),
            restored.accept(
                Intent(MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION)
                    .putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, "source")
                    .putExtra(MainActivity.EXTRA_CONVERSATION_KEY, "archive"),
            ),
        )
    }

    @Test
    fun `无效会话参数不会产生跳转`() {
        assertEquals(
            MainActivityLaunchRequests(),
            MainActivityLaunchRequests().accept(
                Intent(MainActivity.ACTION_OPEN_CONVERSATION_ID)
                    .putExtra(MainActivity.EXTRA_CONVERSATION_ID, " "),
            ),
        )
        assertEquals(
            MainActivityLaunchRequests(),
            MainActivityLaunchRequests().accept(
                Intent(MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION)
                    .putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, "source"),
            ),
        )
    }

    @Test
    fun `任务详情冷启动保存精确 ID 且不从 URI 推断`() {
        val intent = taskIntent("run:A/中文?x=1")
            .setData(android.net.Uri.parse("eta://task/not-the-run-id"))
        val pending = MainActivityLaunchRequests.restore(null, intent)

        assertEquals("run:A/中文?x=1", pending.taskRunId)
        assertEquals(pending, MainActivityLaunchRequests.restore(savedState(pending), taskIntent("stale")))
        assertEquals(null, MainActivityLaunchRequests.restore(null,
            Intent(MainActivity.ACTION_OPEN_TASK_RUN).setData(android.net.Uri.parse("eta://task/run-uri")),
        ).taskRunId)
    }

    @Test
    fun `多个连续任务通知以最新 ID 替换并在消费保存后不重放`() {
        val firstIntent = taskIntent("first-run")
        val first = MainActivityLaunchRequests.restore(null, firstIntent)
        val second = first.accept(taskIntent("second-run"))
        assertEquals("second-run", second.taskRunId)
        val restoredPending = MainActivityLaunchRequests.restore(savedState(second), firstIntent)
        assertEquals("second-run", restoredPending.taskRunId)

        val consumed = restoredPending.copy(taskRunId = null)
        val restoredEmpty = MainActivityLaunchRequests.restore(savedState(consumed), taskIntent("second-run"))
        assertEquals(MainActivityLaunchRequests(), restoredEmpty)
        assertEquals("third-run", restoredEmpty.accept(taskIntent("third-run")).taskRunId)
        assertEquals("second-run", restoredEmpty.accept(taskIntent("second-run")).taskRunId)
    }

    @Test
    fun `任务请求与其他待办共存且桌面再次进入不丢失任务`() {
        val pending = MainActivityLaunchRequests(conversationId = "conversation", terminal = true)
            .accept(taskIntent("run-id"))
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        assertEquals(pending, pending.accept(launcher))
        assertEquals("conversation", pending.conversationId)
        assertEquals(true, pending.terminal)
        assertEquals(pending, MainActivityLaunchRequests.restore(savedState(pending), null))
    }

    @Test
    fun `任务 ID 缺失空白或过长不会产生跳转且边界保持原值`() {
        for (invalid in listOf(null, "", "  ", "x".repeat(201))) {
            assertEquals(null, MainActivityLaunchRequests().accept(taskIntent(invalid)).taskRunId)
        }
        val boundary = "x".repeat(200)
        assertEquals(boundary, MainActivityLaunchRequests().accept(taskIntent(boundary)).taskRunId)
        assertEquals(null, MainActivityLaunchRequests().accept(
            Intent(MainActivity.ACTION_OPEN_CONVERSATION_ID).putExtra(MainActivity.EXTRA_TASK_RUN_ID, "wrong-action"),
        ).taskRunId)
    }

    private fun taskIntent(runId: String?): Intent = Intent(MainActivity.ACTION_OPEN_TASK_RUN)
        .putExtra(MainActivity.EXTRA_TASK_RUN_ID, runId)

    private fun savedState(requests: MainActivityLaunchRequests): Bundle {
        val state = Bundle().also(requests::saveTo)
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(state)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
    }
}

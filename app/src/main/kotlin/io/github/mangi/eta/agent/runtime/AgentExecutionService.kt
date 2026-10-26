package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.net.Uri
import android.graphics.drawable.Icon
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.localizedText
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity
import java.util.concurrent.atomic.AtomicLong

/** 只在用户任务存活期间持有前台执行生命周期；进程被系统停止后不重放任务。 */
internal class AgentExecutionService : Service() {
    private val stopQueue = ExecutionStopQueue { failure ->
        AndroidAgentLogger.warn("Execution task stop failed: type=${failure.safeLogType()}")
    }
    private val owner = ownerSequence.incrementAndGet()
    private var foregroundActive = false
    @Volatile private var startRejected = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        leases.attachOwner(owner)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.execution_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            },
        )
        ensureForeground()
    }

    private fun ensureForeground() {
        if (foregroundActive || startRejected) return
        leases.attachOwner(owner)
        try {
            postForegroundNotification()
            foregroundActive = true
            publishedCompletionId = null // This foreground card replaced any previous completion.
        } catch (failure: RuntimeException) {
            startRejected = true
            AndroidAgentLogger.warn("Execution service foreground failed: type=${failure.safeLogType()}")
            stopTasks(startFailed = true)
        }
    }

    private fun postForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action in RUN_ACTIONS) {
            intent?.getStringExtra(EXTRA_RUN_ID)?.let { runId ->
                AgentRuntimeService.dispatchNotificationAction(intent.action.orEmpty(), runId)
            }
            refreshNotification() // A stale PendingIntent must not keep an empty service alive.
        } else if (intent?.action == ACTION_STOP) {
            stopTasks()
        } else {
            ensureForeground()
            refreshNotification()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        // 销毁时同样收回本服务拥有的任务。回收在独立有界工作线程上完成，不阻塞 Main。
        stopQueue.close(leases.drainOwner(owner))
        super.onDestroy()
    }

    private fun stopTasks(startFailed: Boolean = false) {
        val callbacks = leases.drain(startFailed)
        stopQueue.submit(callbacks) {
            mainHandler.post { if (instance === this) refreshNotification() }
        }
    }

    private fun refreshNotification() {
        if (leases.closeOwnerIfIdle(owner)) {
            if (foregroundActive) {
                foregroundActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            publishCompletionIfIdle(this)
            stopSelf()
        } else {
            // Keep the service's notification record and SystemUI's promoted card on the same snapshot.
            // Updating only NotificationManager leaves the service holding the initial generic summary.
            postForegroundNotification()
        }
    }

    private fun notification(): Notification {
        val status = runStatus
        val open = conversationIntent(this, selectedConversationId)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val taskCount = if (activeRunCount > 0) activeRunCount else leases.count()
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.execution_title))
            // etax: 运行中显示最新一句流式输出（包住 3.3.4 新提的 executionText）
            .setContentText(executionText(this, status, taskCount) + streamPreview.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
            .setContentIntent(open)
            .setSubText(selectedTaskTitle.take(60).takeIf(String::isNotBlank))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val runId = selectedRunId
        if (status != null && runId != null) {
            if (activeRunCount > 1) builder.addAction(runAction(
                runId, ACTION_NEXT_TASK, R.string.execution_next_task, android.R.drawable.ic_media_next,
            ))
            builder.addAction(runAction(runId,
                if (selectedRunPaused) ACTION_RESUME_RUN else ACTION_PAUSE_RUN,
                if (selectedRunPaused) R.string.overlay_resume else R.string.overlay_pause,
                if (selectedRunPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            ))
            builder.addAction(runAction(runId, ACTION_STOP_RUN, R.string.overlay_stop_task, R.drawable.ic_assistant_stop))
        } else {
            builder.addAction(Notification.Action.Builder(null, getString(R.string.execution_stop), stop).build())
        }
        builder.setVisibility(Notification.VISIBILITY_PRIVATE)
        // 只有 Agent run 在跑时才申请提升为 Live Update；终端会话、Linux 启动等长驻任务不属于"正在进行、需要持续关注"的活动。
        if (status != null && usesSystemTaskSurface()) {
            builder
                .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                .setShortCriticalText(if (activeRunCount > 1) getString(R.string.execution_chip_tasks, activeRunCount) else status.shortText())
                .setRequestPromotedOngoing(true)
        }
        return builder.build()
    }

    private fun runAction(runId: String, action: String, label: Int, icon: Int): Notification.Action {
        val intent = Intent(this, AgentExecutionService::class.java).setAction(action)
            .setData(Uri.Builder().scheme("eta-task").authority("control").appendPath(runId).appendPath(action).build())
            .putExtra(EXTRA_RUN_ID, runId)
        val pending = PendingIntent.getService(this, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), pending).build()
    }

    /** 状态栏胶囊约 7 个字符可完整显示；更长时只取动作，工具名留在展开态。 */
    private fun AgentOverlayStatus.shortText(): String = when (this) {
        is AgentOverlayStatus.RunningTool, is AgentOverlayStatus.HostedToolRunning -> getString(R.string.execution_chip_running)
        AgentOverlayStatus.Paused -> getString(R.string.overlay_paused)
        AgentOverlayStatus.Stopping -> getString(R.string.overlay_stopping)
        AgentOverlayStatus.GeneratingAnswer -> getString(R.string.execution_chip_answering)
        else -> getString(R.string.overlay_reasoning)
    }

    companion object {
        private const val CHANNEL = "eta_execution_tasks_v2"
        private const val NOTIFICATION_ID = 1107
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_USER_EXECUTION"
        internal const val ACTION_NEXT_TASK = "io.github.mangi.etax.action.NEXT_TASK"
        internal const val ACTION_PAUSE_RUN = "io.github.mangi.etax.action.PAUSE_RUN"
        internal const val ACTION_RESUME_RUN = "io.github.mangi.etax.action.RESUME_RUN"
        internal const val ACTION_STOP_RUN = "io.github.mangi.etax.action.STOP_RUN"
        private const val EXTRA_RUN_ID = "run_id"
        private val RUN_ACTIONS = setOf(ACTION_NEXT_TASK, ACTION_PAUSE_RUN, ACTION_RESUME_RUN, ACTION_STOP_RUN)
        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null
        private var streamPreview = ""
        private var selectedConversationId: String? = null
        private var selectedRunId: String? = null
        private var selectedTaskTitle = ""
        private var selectedRunPaused = false
        private data class Completion(val runId: String, val conversationId: String, val status: String, val text: String)
        @Volatile private var completion: Completion? = null
        private var publishedCompletionId: String? = null
        private var refreshPending = false
        private var runStatus: AgentOverlayStatus? = null
        private var activeRunCount = 0
        private var publishedStatusText: String? = null

        /**
         * Agent run 的当前状态，供通知与状态栏胶囊展示；null 表示没有 Agent run 在跑。
         * 只在显示文字变化时刷新通知，避免流式事件把通知更新频率推到系统限流。
         */
        fun updateRunStatus(context: Context, status: AgentOverlayStatus?, activeRunCount: Int, preview: String = "", conversationId: String? = null,
            runId: String? = null, taskTitle: String = "", paused: Boolean = false) {
            mainHandler.post {
                runStatus = status
                streamPreview = preview
                selectedConversationId = conversationId
                selectedRunId = runId
                selectedTaskTitle = taskTitle
                selectedRunPaused = paused
                this.activeRunCount = activeRunCount
                if (activeRunCount == 0 && instance?.foregroundActive != true) publishCompletionIfIdle(context)
                val count = if (activeRunCount > 0) activeRunCount else leases.count()
                val text = listOf(executionText(context, status, count), preview, conversationId, runId, taskTitle, paused).joinToString("\u0000")
                if (text == publishedStatusText) return@post
                publishedStatusText = text
                // A later service start reads this state directly; there is no notification to refresh yet.
                if (instance?.foregroundActive != true) return@post
                if (!refreshPending) {
                    refreshPending = true
                    mainHandler.postDelayed({
                        refreshPending = false
                        instance?.takeIf { it.foregroundActive }?.refreshNotification()
                    }, 500)
                }
            }
        }

        internal fun usesSystemTaskSurface(): Boolean = Build.VERSION.SDK_INT >= 36 &&
            Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1

        private fun conversationIntent(context: Context, conversationId: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            conversationId?.takeIf(String::isNotBlank)?.let {
                intent.action = MainActivity.ACTION_OPEN_CONVERSATION_ID
                intent.data = Uri.Builder().scheme("eta-task").authority("conversation").appendPath(it).build()
                intent.putExtra(MainActivity.EXTRA_CONVERSATION_ID, it)
            }
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /** Completion replaces the same notification after foreground ownership has ended. */
        @JvmStatic fun publishRunCompletion(context: Context, runId: String, conversationId: String, status: String, text: String) {
            mainHandler.post {
                completion = Completion(runId, conversationId, status, text)
                if (instance?.foregroundActive == true) instance?.refreshNotification()
                else publishCompletionIfIdle(context.applicationContext)
            }
        }

        private fun publishCompletionIfIdle(context: Context) {
            val result = completion ?: return
            if (leases.count() != 0 || activeRunCount > 0 || result.runId == publishedCompletionId) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null || !manager.areNotificationsEnabled()) return
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Etax · ${result.status}")
                .setContentText(result.text.takeLast(240))
                .setStyle(Notification.BigTextStyle().bigText(result.text))
                .setContentIntent(conversationIntent(context, result.conversationId))
                .setOnlyAlertOnce(true).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
            runCatching { manager.notify(NOTIFICATION_ID, notification) }
                .onSuccess { publishedCompletionId = result.runId }
                .onFailure { AndroidAgentLogger.warn("Completion notification failed: type=${it.safeLogType()}") }
        }

        private fun executionText(context: Context, status: AgentOverlayStatus?, taskCount: Int): String {
            val summary = context.resources.getQuantityString(R.plurals.execution_summary, taskCount, taskCount)
            return when {
                status == null -> summary
                taskCount <= 1 -> status.localizedText(context.resources)
                else -> "$summary · ${status.localizedText(context.resources)}"
            }
        }

        /** 必须从有效的用户入口取得引用，再创建会话或子进程；失败时调用方不启动任务。 */
        fun acquire(
            context: Context,
            id: String,
            allowBoundFallback: Boolean = false,
            onStop: () -> Unit,
        ): Boolean {
            if (instance?.startRejected == true) return false
            if (!leases.acquire(id, allowBoundFallback, onStop)) return true
            completion = null
            return try {
                context.applicationContext.startForegroundService(Intent(context, AgentExecutionService::class.java))
                true
            } catch (failure: RuntimeException) {
                leases.release(id)
                AndroidAgentLogger.warn("Execution service start rejected: type=${failure.safeLogType()}")
                false
            }
        }

        fun release(id: String) {
            leases.release(id)
            mainHandler.post { instance?.refreshNotification() }
        }

        /**
         * etax: 供 ShadowNotice 在后台刷新前台通知（流式正文）。走服务自身的
         * 同一服务的前台通知更新，不用 startService——后台启动服务会被系统拦，
         * 回桌面后流式就不再更新了。
         */
        @JvmStatic
        fun pokeStreaming() {
            mainHandler.post { instance?.takeIf { it.foregroundActive }?.refreshNotification() }
        }
    }
}

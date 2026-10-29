package io.github.mangi.eta.agent.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tasks.TaskRunResult
import io.github.mangi.eta.agent.tasks.TaskRunStatus
import io.github.mangi.eta.ui.MainActivity

/** The caller must durably save the finalized result before posting this notification. */
internal object BackgroundCommandNotification {
    private const val CHANNEL = "eta_background_commands"
    private const val NOTIFICATION_ID = 1124

    internal fun openIntent(context: Context, runId: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_TASK_RUN)
            .setData(Uri.Builder().scheme("eta-task").authority("run").appendPath(runId).build())
            .putExtra(MainActivity.EXTRA_TASK_RUN_ID, runId)

    internal fun build(context: Context, result: TaskRunResult): Notification {
        val open = PendingIntent.getActivity(context, 0, openIntent(context, result.runId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val status = when (result.status) {
            TaskRunStatus.Succeeded -> "任务已完成"
            TaskRunStatus.Partial -> "任务部分完成"
            TaskRunStatus.Failed -> "任务执行失败"
            TaskRunStatus.Cancelled -> "任务已取消"
            TaskRunStatus.Unconfirmed -> "任务结果待确认"
        }
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("后台任务已结束")
            .setContentText("解锁后查看任务结果")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${result.title.take(60)} · $status")
            .setContentText(result.summary)
            .setStyle(Notification.BigTextStyle().bigText(result.summary))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun show(context: Context, result: TaskRunResult) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "后台任务结果", NotificationManager.IMPORTANCE_DEFAULT))
        // Same execution retries replace the same notification; separate executions never overwrite each other.
        if (manager.areNotificationsEnabled()) manager.notify("task-run:${result.runId}", NOTIFICATION_ID, build(context, result))
    }
}

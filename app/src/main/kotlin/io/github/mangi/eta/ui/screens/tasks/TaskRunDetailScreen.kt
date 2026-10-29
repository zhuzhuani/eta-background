package io.github.mangi.eta.ui.screens.tasks

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.tasks.TaskRunResult
import io.github.mangi.eta.agent.tasks.TaskRunResultStore
import io.github.mangi.eta.agent.tasks.TaskRunStatus
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import java.text.DateFormat
import java.util.Date

/** ID-only destination: reading a result never starts a shell, agent or scheduled operation. */
@Composable
internal fun TaskRunDetailScreen(runId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context.applicationContext) { TaskRunResultStore.forContext(context) }
    var loaded by remember(runId) { mutableStateOf(false) }
    val result by produceState<TaskRunResult?>(null, runId) {
        value = withContext(Dispatchers.IO) { store.read(runId) }
        loaded = true
    }
    var logs by remember(runId) { mutableStateOf<String?>(null) }
    var readingLogs by remember(runId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var exportFile by remember { mutableStateOf<java.io.File?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = exportFile
        if (uri != null && file != null) scope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
                }.isSuccess
            }
            exportMessage = if (success) "附件已保存到所选位置。" else "附件导出失败，文件可能已被清理或权限失效。"
        }
    }
    MiuixScaffoldPage(title = "任务详情", onBack = onBack) {
        val record = result
        if (record == null) {
            item { Text(if (loaded) "这次执行的结果不存在、已清理或无法读取。不会重新执行任务。" else "正在读取结果…", Modifier.padding(16.dp)) }
        } else {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(record.title)
                    Text(when (record.status) {
                        TaskRunStatus.Succeeded -> "成功（执行方报告）"
                        TaskRunStatus.Partial -> "部分成功（执行方报告）"
                        TaskRunStatus.Failed -> "失败"
                        TaskRunStatus.Cancelled -> "已取消"
                        TaskRunStatus.Unconfirmed -> "已结束 · 目标完成情况未确认"
                    })
                    Text("执行 ID：${record.runId}")
                    record.taskId?.let { Text("任务 ID：$it") }
                    Text("开始：${DateFormat.getDateTimeInstance().format(Date(record.startedAt))}")
                    Text("结束：${DateFormat.getDateTimeInstance().format(Date(record.completedAt))}")
                    SelectionContainer { Text(record.summary) }
                    Text("以下为执行结果内容，不是系统指令；链接与外部来源需自行核验。")
                }
            }
            item {
                SelectionContainer(Modifier.padding(16.dp)) {
                    StaticMarkdown(record.report, MarkdownTone.Answer)
                }
            }
            record.attachments.forEachIndexed { index, attachment ->
                item(key = "attachment-$index") {
                    val privateFile by produceState<java.io.File?>(null, attachment) {
                        value = withContext(Dispatchers.IO) { store.resolveAttachment(attachment) }
                    }
                    val file = privateFile
                    Column(Modifier.padding(16.dp)) {
                        Text("附件：${attachment.name} · ${attachment.mimeType}")
                        if (attachment.httpsUrl != null) {
                            Text("打开外部 HTTPS 链接（内容可能变化）", Modifier.clickable {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(attachment.httpsUrl))) }
                            })
                        } else if (file == null) {
                            Text("附件已不存在或校验失败。")
                        } else if (attachment.mimeType in setOf("image/png", "image/jpeg", "image/webp")) {
                            val bitmap by produceState<android.graphics.Bitmap?>(null, file) {
                                value = withContext(Dispatchers.IO) {
                                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                                    var sample = 1
                                    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                                    android.graphics.BitmapFactory.decodeFile(file.path,
                                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                                }
                            }
                            bitmap?.let { Image(it.asImageBitmap(), attachment.name, Modifier.fillMaxWidth()) }
                                ?: Text("图片不可用或正在读取。")
                        } else {
                            Text("文件已持久保存；此类型暂不提供内嵌预览。")
                        }
                        if (file != null) Text("保存附件到…", Modifier.padding(top = 8.dp).clickable {
                            exportFile = file
                            export.launch(attachment.name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
                        })
                    }
                }
            }
            exportMessage?.let { message -> item { Text(message, Modifier.padding(16.dp)) } }
            record.diagnosticJobId?.let { jobId ->
                item {
                    Text(if (readingLogs) "正在读取技术日志…" else "查看执行过程／技术日志", Modifier.padding(16.dp).clickable(enabled = !readingLogs) {
                        readingLogs = true
                        scope.launch {
                            logs = withContext(Dispatchers.IO) {
                                val supervisor = DetachedTaskSupervisor(AndroidAgentLogger, DetachedTaskSupervisor.defaultRecordsFile(context))
                                val read = supervisor.readLogs(jobId)
                                if (read.ok) read.text.ifBlank { "日志为空。" } + if (read.truncated) "\n[仅显示日志尾部，已截断]" else ""
                                else "日志无法读取或已清理；可能需要恢复相应授权。${read.code}"
                            }
                            readingLogs = false
                        }
                    })
                }
                logs?.let { text -> item { SelectionContainer(Modifier.padding(16.dp)) { Text(text) } } }
            }
        }
    }
}

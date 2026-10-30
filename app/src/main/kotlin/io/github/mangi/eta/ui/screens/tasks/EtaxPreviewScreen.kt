package io.github.mangi.eta.ui.screens.tasks

import android.app.KeyguardManager
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.mangi.eta.agent.display.EtaxPreviewRegistry
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text

/** Read-only viewer: no input injection, app launch, screen ownership or background capture lease. */
@Composable
internal fun EtaxPreviewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sessions by EtaxPreviewRegistry.sessions.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    val active = sessions.firstOrNull { it.sessionId == selected } ?: sessions.firstOrNull()
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var message by remember { mutableStateOf("等待后台虚拟屏任务。") }
    LaunchedEffect(active?.sessionId, lifecycle) {
        bitmap = null
        val id = active?.sessionId ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                    bitmap = null
                    message = "锁屏时暂停预览。"
                } else {
                    val response = EtaxPreviewRegistry.capture(id)
                    val frame = withContext(Dispatchers.IO) {
                        runCatching {
                            val json = JSONObject(response ?: "{}")
                            if (!json.optBoolean("ok")) return@runCatching null
                            val encoded = json.optString("imageBase64")
                            require(encoded.length <= 2 * 1024 * 1024)
                            val bytes = Base64.decode(encoded, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }.getOrNull()
                    }
                    bitmap = if (!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                        EtaxPreviewRegistry.sessions.value.any { it.sessionId == id }) frame else null
                    message = if (frame == null) "尚未取得画面；可能未就绪、连接中断或任务已经释放。不会自动重建或重放操作。" else "只读实时预览；返回页面后停止抓帧。"
                }
                delay(500)
            }
        }
    }
    MiuixScaffoldPage(title = "后台虚拟屏预览（实验性）", onBack = onBack) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("预览源自当前任务持有的后台屏幕，不会镜像主屏，也不会代替无障碍服务。")
                Text(message)
                active?.let { Text("${it.packageName}\n执行：${it.runId}") }
            }
        }
        sessions.forEach { session ->
            item(key = session.sessionId) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    io.github.mangi.eta.ui.components.EtaTextButton(text = "查看 ${session.packageName}",
                        onClick = { selected = session.sessionId })
                }
            }
        }
        item {
            val image = bitmap
            if (image != null) Image(image.asImageBitmap(), "后台虚拟屏画面", Modifier.fillMaxWidth().padding(16.dp))
            else Text("没有可显示的画面。请先发起后台 App 任务，再打开本页。", Modifier.padding(16.dp))
        }
    }
}

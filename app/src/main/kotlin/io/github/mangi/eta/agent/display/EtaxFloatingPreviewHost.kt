package io.github.mangi.eta.agent.display

import android.app.KeyguardManager
import android.content.*
import android.graphics.BitmapFactory
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.view.*
import android.widget.*
import io.github.mangi.eta.config.Prefs
import kotlinx.coroutines.*
import org.json.JSONObject

/** PR142-inspired passive preview, adapted to Etax binding ownership rather than a singleton engine. */
internal class EtaxFloatingPreviewHost(private val context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: LinearLayout? = null
    private var image: ImageView? = null
    private var folded = false
    private var dismissed: String? = null
    private var current: String? = null
    private val prefs = Prefs.localAgentPreferences()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> }
    init {
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        scope.launch {
            while (isActive) {
                val session = EtaxPreviewRegistry.sessions.value.firstOrNull()
                if (current != session?.sessionId) { remove(); current = session?.sessionId; dismissed = null; folded = false }
                val visible = session != null && dismissed != session.sessionId &&
                    Prefs.isEnabled(Prefs.Keys.AGENT_FLOATING_PREVIEW) && Settings.canDrawOverlays(context) &&
                    context.getSystemService(PowerManager::class.java).isInteractive &&
                    !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
                if (!visible) remove()
                else {
                    if (view == null) add()
                    if (!folded && view != null) {
                        val response = EtaxPreviewRegistry.capture(session!!.sessionId)
                        val bitmap = withContext(Dispatchers.IO) { runCatching {
                            val json = JSONObject(response ?: "{}")
                            if (!json.optBoolean("ok")) return@runCatching null
                            val raw = json.optString("imageBase64")
                            require(raw.length < 512 * 1024)
                            val bytes = Base64.decode(raw, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }.getOrNull() }
                        if (current == session.sessionId && !folded &&
                            EtaxPreviewRegistry.sessions.value.any { it.sessionId == session.sessionId } &&
                            context.getSystemService(PowerManager::class.java).isInteractive &&
                            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) image?.setImageBitmap(bitmap)
                        else remove()
                    }
                }
                delay(500)
            }
        }
    }
    private fun add() {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff20242c.toInt()) }
        val header = LinearLayout(context)
        header.addView(TextView(context).apply {
            text = "虚拟屏 ▾"; setTextColor(-1); setPadding(12, 8, 12, 8)
            setOnClickListener { folded = !folded; image?.visibility = if (folded) View.GONE else View.VISIBLE; if (folded) image?.setImageDrawable(null) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(context).apply {
            text = " × "; setTextColor(-1); setPadding(8, 8, 8, 8)
            setOnClickListener { dismissed = current; remove() }
        })
        column.addView(header)
        val picture = ImageView(context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        column.addView(picture, LinearLayout.LayoutParams(-1, -2))
        val width = (context.resources.displayMetrics.density * 124).toInt()
        val params = WindowManager.LayoutParams(width, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
            android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = 8; y = 180 }
        var startX = 0f; var startY = 0f; var oldX = 0; var oldY = 0
        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; oldX = params.x; oldY = params.y; false }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (oldX - (event.rawX - startX).toInt()).coerceAtLeast(0)
                    params.y = (oldY + (event.rawY - startY).toInt()).coerceAtLeast(0)
                    runCatching { manager.updateViewLayout(column, params) }; true
                }
                else -> false
            }
        }
        if (runCatching { manager.addView(column, params) }.isSuccess) { view = column; image = picture }
    }
    private fun remove() { image?.setImageDrawable(null); view?.let { runCatching { manager.removeView(it) } }; view = null; image = null }
    override fun close() { scope.cancel(); prefs?.unregisterOnSharedPreferenceChangeListener(listener); remove() }
}

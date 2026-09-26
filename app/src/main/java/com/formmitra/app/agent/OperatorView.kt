package com.formmitra.app.agent

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.formmitra.app.engine.OperatorApi
import com.formmitra.app.engine.OperatorSession
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * Operator console — fullscreen immersive view.
 *
 * - Session start par automation WebView khulti hai (OperatorSession);
 *   har ~3s screenshot server par upload hota hai (operator state).
 * - Yahan live screenshot dikhta hai (GET operator/state har 2-3s poll,
 *   sirf session active ho tab).
 * - Screenshot par tap → normalized 0-1000 coords → tap command;
 *   drag → swipe command (POST /api/agent/operator/command).
 * - Server realtime channel par "operator_command" broadcast karta hai →
 *   app OperatorCommandReceiver se automation WebView par execute karti hai.
 * - Sab labels simple Hinglish. Har entry point Throwable-proof
 *   (CrashCatcher pattern).
 */
class OperatorView : Activity() {

    private val TAG = "OperatorView"

    private lateinit var statusText: TextView
    private lateinit var startStopBtn: Button
    private lateinit var screenView: ImageView
    private lateinit var desktopBtn: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile private var polling = false
    @Volatile private var sessionOn = false
    @Volatile private var desktopOn = false
    @Volatile private var lastShownUrl: String = ""
    private var destroyed = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate super failed", t)
            try { finish() } catch (_: Exception) { }
            return
        }
        try {
            // Fullscreen immersive (no action bar — manifest theme bhi NoActionBar)
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            buildUi()
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate UI failed (non-fatal)", t)
            toast("Operator view khulne me dikkat")
            try { finish() } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        destroyed = true
        polling = false
        try { uiHandler.removeCallbacksAndMessages(null) } catch (_: Exception) { }
        // Session khuli reh sakti hai (background control chalta rahe) —
        // user Stop dabaye to hi band hoti hai.
        try { super.onDestroy() } catch (_: Exception) { }
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
        }

        // Top bar: status + Start/Stop
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }
        statusText = TextView(this).apply {
            text = "🖥️ Operator — session: BAND"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        startStopBtn = Button(this).apply {
            text = "▶ Start"
            setOnClickListener { safe { toggleSession() } }
        }
        topRow.addView(statusText)
        topRow.addView(startStopBtn)
        root.addView(topRow)

        // Live screenshot
        screenView = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f
            )
            setOnTouchListener { v, ev -> safeBool { onScreenTouch(v, ev) } }
        }
        root.addView(screenView)

        // Controls (scrollable)
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 2.2f
            )
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }

        // Nav row
        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navRow.addView(navBtn("⬆ Upar") { sendCmd("scroll", JSONObject().put("direction", "up").put("amount", 80)) })
        navRow.addView(navBtn("⬇ Neeche") { sendCmd("scroll", JSONObject().put("direction", "down").put("amount", 80)) })
        navRow.addView(navBtn("⬅ Back") { sendCmd("back", JSONObject()) })
        navRow.addView(navBtn("➡ Aage") { sendCmd("forward", JSONObject()) })
        navRow.addView(navBtn("🔄 Reload") { sendCmd("reload", JSONObject()) })
        controls.addView(navRow)

        // Type row
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val typeField = EditText(this).apply {
            hint = "Text likho…"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val typeBtn = Button(this).apply {
            text = "⌨ Type karo"
            setOnClickListener { safe {
                val t = typeField.text.toString()
                if (t.isBlank()) { toast("Pehle text likho"); return@safe }
                sendCmd("type", JSONObject().put("text", t))
            } }
        }
        typeRow.addView(typeField); typeRow.addView(typeBtn)
        controls.addView(typeRow)

        // Fill row: selector + value
        val fillRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fillSel = EditText(this).apply {
            hint = "selector (jaise #name)"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val fillVal = EditText(this).apply {
            hint = "value"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val fillBtn = Button(this).apply {
            text = "✏ Bharo"
            setOnClickListener { safe {
                val s = fillSel.text.toString().trim()
                val v = fillVal.text.toString()
                if (s.isEmpty() || v.isEmpty()) { toast("selector + value dono likho"); return@safe }
                sendCmd("fill", JSONObject().put("selector", s).put("text", v))
            } }
        }
        fillRow.addView(fillSel); fillRow.addView(fillVal); fillRow.addView(fillBtn)
        controls.addView(fillRow)

        // Select row: selector + option
        val selRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val selSel = EditText(this).apply {
            hint = "selector (jaise #state)"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val selOpt = EditText(this).apply {
            hint = "option"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val selBtn = Button(this).apply {
            text = "🔽 Chuno"
            setOnClickListener { safe {
                val s = selSel.text.toString().trim()
                val o = selOpt.text.toString().trim()
                if (s.isEmpty() || o.isEmpty()) { toast("selector + option dono likho"); return@safe }
                sendCmd("select", JSONObject().put("selector", s).put("option", o))
            } }
        }
        selRow.addView(selSel); selRow.addView(selOpt); selRow.addView(selBtn)
        controls.addView(selRow)

        // Desktop toggle + CAPTCHA
        val miscRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        desktopBtn = Button(this).apply {
            text = "🖥️ Desktop: BAND"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { safe { toggleDesktop() } }
        }
        val captchaBtn = Button(this).apply {
            text = "🧩 CAPTCHA bhejo"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { safe {
                // standing policy choice=solve: server screenshot lega,
                // run needs_user hoga, user web console se solve karega.
                // Kabhi fake solve nahi.
                sendCmd("captcha_request", JSONObject())
                toast("CAPTCHA screenshot bheja — console se khud solve karo")
            } }
        }
        miscRow.addView(desktopBtn); miscRow.addView(captchaBtn)
        controls.addView(miscRow)

        // Hint
        controls.addView(TextView(this).apply {
            text = "Screenshot par tap = click • ungli ghumao = swipe • commands server se hokar aate hain (1-2 second lag sakta hai)"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, dp(6), 0, 0)
        })

        scroll.addView(controls)
        root.addView(scroll)
        setContentView(root)
    }

    private fun navBtn(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { safe { onClick() } }
        }
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            Log.e(TAG, "UI action failed (non-fatal)", t)
            toast("Kuch gadbad hui — dobara try karo")
        }
    }

    private inline fun safeBool(block: () -> Boolean): Boolean {
        return try { block() } catch (t: Throwable) {
            Log.e(TAG, "touch failed (non-fatal)", t)
            false
        }
    }

    // ---------------- session ----------------

    private fun toggleSession() {
        if (sessionOn) {
            toast("Session band ho rahi…")
            Thread({
                try { OperatorSession.stop(this) } catch (_: Exception) { }
                runOnUiThread { safe {
                    sessionOn = false
                    polling = false
                    statusText.text = "🖥️ Operator — session: BAND"
                    startStopBtn.text = "▶ Start"
                    toast("Session band ✅")
                } }
            }, "OperatorView-stop").apply { isDaemon = true }.start()
        } else {
            toast("Session shuru ho rahi…")
            Thread({
                val ok = try { OperatorSession.start(this, desktopOn) } catch (_: Exception) { false }
                runOnUiThread { safe {
                    if (ok) {
                        sessionOn = true
                        lastShownUrl = ""
                        statusText.text = "🖥️ Operator — session: CHALU ✅"
                        startStopBtn.text = "⏹ Stop"
                        startPolling()
                        toast("Session chalu ✅")
                    } else {
                        toast("Session start nahi hui — shayad form run chal raha hai")
                    }
                } }
            }, "OperatorView-start").apply { isDaemon = true }.start()
        }
    }

    private fun toggleDesktop() {
        desktopOn = !desktopOn
        desktopBtn.text = if (desktopOn) "🖥️ Desktop: CHALU" else "🖥️ Desktop: BAND"
        if (sessionOn) {
            sendCmd("set_desktop", JSONObject().put("enabled", desktopOn))
        } else {
            toast("Session start par desktop mode lagega")
        }
    }

    // ---------------- commands ----------------

    /** Command server ko POST (server realtime par broadcast karega). */
    private fun sendCmd(command: String, params: JSONObject) {
        if (!sessionOn) {
            toast("Pehle Start dabao")
            return
        }
        Thread({
            val ok = try { OperatorApi.postCommand(this, command, params) } catch (_: Exception) { false }
            runOnUiThread { safe {
                toast(if (ok) "Bhej diya ✅ ($command)" else "Bhejne me dikkat 📡")
            } }
        }, "OperatorView-cmd").apply { isDaemon = true }.start()
    }

    // ---------------- screenshot touch ----------------

    private var downX = 0f
    private var downY = 0f

    private fun onScreenTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val w = v.width.toFloat().coerceAtLeast(1f)
                val h = v.height.toFloat().coerceAtLeast(1f)
                val dx = ev.x - downX
                val dy = ev.y - downY
                val distDp = sqrt(dx * dx + dy * dy) / resources.displayMetrics.density
                if (distDp < 14) {
                    // Tap → normalized 0-1000
                    val nx = (downX / w * 1000.0).coerceIn(0.0, 1000.0)
                    val ny = (downY / h * 1000.0).coerceIn(0.0, 1000.0)
                    sendCmd("tap", JSONObject().put("x", nx).put("y", ny))
                } else {
                    // Drag → swipe
                    sendCmd("swipe", JSONObject()
                        .put("x1", (downX / w * 1000.0).coerceIn(0.0, 1000.0))
                        .put("y1", (downY / h * 1000.0).coerceIn(0.0, 1000.0))
                        .put("x2", (ev.x / w * 1000.0).coerceIn(0.0, 1000.0))
                        .put("y2", (ev.y / h * 1000.0).coerceIn(0.0, 1000.0)))
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    // ---------------- live screenshot poll ----------------

    private fun startPolling() {
        if (polling) return
        polling = true
        uiHandler.post(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling || destroyed || !sessionOn) return
            Thread({
                try {
                    val state = OperatorApi.getState(this@OperatorView)
                    val url = state?.optString("screenshot_url", "") ?: ""
                    if (url.isNotEmpty() && url != lastShownUrl && !destroyed) {
                        val bmp = downloadBitmap(url)
                        if (bmp != null && !destroyed) {
                            lastShownUrl = url
                            runOnUiThread { safe {
                                if (!destroyed) screenView.setImageBitmap(bmp)
                            } }
                        }
                    }
                } catch (_: Exception) { }
                if (!destroyed && polling && sessionOn) {
                    uiHandler.postDelayed(this, 2500)
                }
            }, "OperatorView-poll").apply { isDaemon = true }.start()
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                requestMethod = "GET"
            }
            if (conn.responseCode != 200) return null
            val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
            bmp
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }
}

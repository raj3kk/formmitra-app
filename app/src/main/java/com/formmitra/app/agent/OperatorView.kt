package com.formmitra.app.agent

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.formmitra.app.engine.OperatorApi
import com.formmitra.app.engine.OperatorSession
import java.net.HttpURLConnection
import java.net.URL

/**
 * Operator console — fullscreen immersive VIEW-ONLY view (v33 UX rule).
 *
 * - Session ko shuru SIRF agent/operator/AI karta hai (server command
 *   channel se — OperatorCommandReceiver). User ke paas session START
 *   ka button NAHI hai.
 * - Yahan live screenshot dikhta hai (GET operator/state har ~2.5s poll).
 * - STRICTLY VIEW-ONLY: screenshot par tap/drag/pinch se KOI command
 *   nahi jata; koi manual control nahi.
 * - User ke haath me sirf EMERGENCY STOP hai: "BAND KARO" — session
 *   chal rahi ho tab hi dabega. OTP/login/payment/option gates aur
 *   sudhaar chat se hote hain.
 * - Sab labels simple Hinglish. Har entry point Throwable-proof
 *   (CrashCatcher pattern).
 */
class OperatorView : Activity() {

    private val TAG = "OperatorView"

    private lateinit var statusText: TextView
    private lateinit var stopBtn: Button
    private lateinit var screenView: ImageView

    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile private var polling = false
    @Volatile private var sessionOn = false
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
            startPolling()
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
        // user BAND KARO dabaye to hi band hoti hai.
        try { super.onDestroy() } catch (_: Exception) { }
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
        }

        // Top bar: status + BAND KARO (emergency stop only)
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }
        statusText = TextView(this).apply {
            text = "Operator — session band hai, agent shuru karega"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        stopBtn = Button(this).apply {
            text = "BAND KARO"
            isEnabled = false
            setOnClickListener { safe { emergencyStop() } }
        }
        topRow.addView(statusText)
        topRow.addView(stopBtn)
        root.addView(topRow)

        // Live screenshot
        screenView = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f
            )
            // v33 VIEW-ONLY: koi touch listener nahi — tap/drag/pinch se
            // koi command nahi jata. Sirf agent/operator/AI chalata hai.
            isClickable = false
            isFocusable = false
        }
        root.addView(screenView)

        // v33 VIEW-ONLY: manual control section hataya (user order —
        // "sirf agent operate kare, user kuch nahi kare"). Session start
        // sirf agent/server karta hai; user ke paas sirf BAND KARO hai.
        root.addView(TextView(this).apply {
            text = "Sirf dekhne ke liye — screen ko agent chalata hai.\n" +
                "OTP / login / payment / sudhaar chat me hoga.\n" +
                "Rokna ho to BAND KARO dabao (emergency stop)."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        })
        setContentView(root)
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            Log.e(TAG, "UI action failed (non-fatal)", t)
            toast("Kuch gadbad hui — dobara try karo")
        }
    }

    // ---------------- emergency stop ----------------

    private fun emergencyStop() {
        if (!sessionOn) return
        toast("Session band ho rahi…")
        Thread({
            try { OperatorSession.stop(this) } catch (_: Exception) { }
            runOnUiThread { safe {
                sessionOn = false
                statusText.text = "Operator — session band hai, agent shuru karega"
                stopBtn.isEnabled = false
                toast("Session band")
            } }
        }, "OperatorView-stop").apply { isDaemon = true }.start()
    }

    // ---------------- live screenshot poll ----------------

    private fun startPolling() {
        if (polling) return
        polling = true
        uiHandler.post(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling || destroyed) return
            Thread({
                try {
                    val state = OperatorApi.getState(this@OperatorView)
                    val url = state?.optString("screenshot_url", "") ?: ""
                    val active = state != null && state.optBoolean("session_active", url.isNotEmpty())
                    if (!destroyed) {
                        runOnUiThread { safe {
                            if (!destroyed) {
                                sessionOn = active
                                if (active) {
                                    statusText.text = "Operator — session CHALU (agent kaam kar raha hai)"
                                    stopBtn.isEnabled = true
                                } else {
                                    statusText.text = "Operator — session band hai, agent shuru karega"
                                    stopBtn.isEnabled = false
                                }
                            }
                        } }
                    }
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
                if (!destroyed && polling) {
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

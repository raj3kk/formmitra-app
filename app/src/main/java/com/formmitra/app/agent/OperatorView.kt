package com.formmitra.app.agent

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.formmitra.app.engine.FormRunService
import com.formmitra.app.engine.LiveWebViewHost
import com.formmitra.app.engine.OperatorSession

/**
 * v38 — LIVE WEBVIEW VIEW (user order 2026-09-26):
 *
 * "Naya toggle nahi — wahi Live toggle, usi me LIVE WebView dikhe
 *  (screenshot delay nahi, asli live). Agent idle → khaali browser;
 *  agent kaam kare → sab kuch live. Browsing background me hi chalegi;
 *  live view SIRF display (view-only)."
 *
 * - LiveWebViewHost ka EK shared WebView yahan attach hota hai — wahi
 *   object jo automation chala raha hai. Attach/detach par object nahi
 *   badalta → live kholne/band karne se automation kabhi rukti nahi
 *   (approved addition #5).
 * - STRICTLY VIEW-ONLY (v33 rule barkarar): WebView ke UPAR transparent
 *   touch-blocker overlay — user ka tap/drag/pinch WebView tak pahunch
 *   hi nahi sakta. WebView par KOI OnTouchListener NAHI lagate taaki
 *   engine ke synthetic taps (tapAt — reCAPTCHA checkbox,
 *   dispatchTouchEvent seedha WebView par) kaam karte rahen.
 * - Approved addition #2: live khula ho aur kaam khatm/fail/band ho to
 *   completion banner (blank screen nahi).
 * - BAND KARO (emergency stop): operator session + active form run dono
 *   band karta hai. OTP/login/payment/option gates aur sudhaar chat se.
 * - Sab labels simple Hinglish. Har entry point Throwable-proof.
 */
class OperatorView : Activity() {

    private val TAG = "OperatorView"

    private lateinit var statusText: TextView
    private lateinit var stopBtn: Button
    private lateinit var webContainer: FrameLayout
    private lateinit var doneBanner: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    @Volatile private var attachedWv: WebView? = null

    private val liveActivityListener: (LiveActivity.Event) -> Unit = { e ->
        try {
            runOnUiThread { safe { onLiveEvent(e) } }
        } catch (_: Exception) { }
    }

    /** v38 addition #3: renderer crash ke baad naya WebView — dobara attach. */
    private val recreateListener: (WebView) -> Unit = { _ ->
        try {
            runOnUiThread { safe { attachWebView() } }
        } catch (_: Exception) { }
    }

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
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
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
            try {
                LiveActivity.addListener(liveActivityListener)
            } catch (_: Exception) { }
            try {
                LiveWebViewHost.addRecreateListener(recreateListener)
            } catch (_: Exception) { }
            attachWebView()
            refreshFromLastEvent()
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate UI failed (non-fatal)", t)
            toast("Live view khulne me dikkat")
            try { finish() } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        destroyed = true
        try {
            LiveActivity.removeListener(liveActivityListener)
        } catch (_: Exception) { }
        try {
            LiveWebViewHost.removeRecreateListener(recreateListener)
        } catch (_: Exception) { }
        // v38 addition #5: live band → WebView wapas hidden mode me;
        // AUTOMATION JAARI rehti hai (stop NAHI hota).
        try {
            detachWebView()
        } catch (_: Exception) { }
        try {
            LiveWebViewHost.setLiveVisible(false)
        } catch (_: Exception) { }
        try { uiHandler.removeCallbacksAndMessages(null) } catch (_: Exception) { }
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
            text = "Live — taiyaar ho raha hai…"
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

        // WebView container: [WebView | touch-blocker overlay | done banner]
        webContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f
            )
        }
        // v38 addition #2: completion banner (shuru me chhupa).
        doneBanner = TextView(this).apply {
            visibility = View.GONE
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E6B4F"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        webContainer.addView(doneBanner)
        root.addView(webContainer)

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
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "UI action failed (non-fatal)", t)
            toast("Kuch gadbad hui — dobara try karo")
        }
    }

    // ---------------- live WebView attach/detach ----------------

    /**
     * Host ka shared WebView attach karo. Koi automation kabhi chali hi
     * nahi to ensureForViewing() khaali browser banata hai ("agent idle
     * → khaali browser").
     */
    private fun attachWebView() {
        if (destroyed) return
        val wv = try {
            LiveWebViewHost.ensureForViewing(this)
        } catch (t: Throwable) {
            Log.e(TAG, "ensureForViewing failed", t)
            toast("Browser taiyaar nahi hua")
            return
        }
        try {
            // Pehle se kahin attach ho to wahan se hatao (ek view, ek parent).
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
            } catch (_: Exception) { }
            // Purana blocker (recreate par) saaf karo — index 0 WebView ke
            // liye khaali rakho; blocker + banner dobara jodo.
            webContainer.removeAllViews()
            wv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // v38 VIEW-ONLY: WebView par KOI touch listener NAHI (engine ke
            // synthetic taps kaam karte rahen). Uske UPAR transparent
            // blocker — user ka touch WebView tak pahunche hi nahi.
            val blocker = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                isClickable = true
                isFocusable = true
                setOnTouchListener { _, _ -> true }
            }
            webContainer.addView(wv, 0)
            webContainer.addView(blocker)
            webContainer.addView(doneBanner)
            attachedWv = wv
            try {
                LiveWebViewHost.setLiveVisible(true)
            } catch (_: Exception) { }
            updateStatus()
        } catch (t: Throwable) {
            Log.e(TAG, "attachWebView failed (non-fatal)", t)
        }
    }

    private fun detachWebView() {
        val wv = attachedWv
        attachedWv = null
        if (wv == null) return
        try {
            (wv.parent as? ViewGroup)?.removeView(wv)
        } catch (_: Exception) { }
        // Hidden mode ka measure/layout (server screenshot loop ke liye).
        try {
            LiveWebViewHost.layoutForHidden()
        } catch (_: Exception) { }
    }

    // ---------------- status + completion banner ----------------

    private fun updateStatus() {
        if (destroyed || !::statusText.isInitialized) return
        val active = try {
            LiveWebViewHost.isAutomationActive()
        } catch (_: Exception) { false }
        try {
            if (active) {
                statusText.text = "● Live — agent kaam kar raha hai"
                stopBtn.isEnabled = true
            } else {
                statusText.text = "Live — browser (agent khaali hai)"
                stopBtn.isEnabled = false
            }
        } catch (_: Exception) { }
    }

    /** v38 addition #2: kaam khatm/fail/band → banner (blank screen nahi). */
    private fun onLiveEvent(e: LiveActivity.Event) {
        if (destroyed) return
        try {
            if (LiveActivity.isTerminal(e)) {
                val msg = when (e.key.substringAfter(":")) {
                    LiveActivity.STATE_DONE -> "✓ Kaam poora ho gaya"
                    LiveActivity.STATE_FAILED -> "⚠️ Kaam me dikkat aayi — chat me dekho"
                    LiveActivity.STATE_STOPPED -> "⏹ Kaam band kar diya gaya"
                    else -> null
                }
                if (msg != null && ::doneBanner.isInitialized) {
                    doneBanner.text = msg
                    doneBanner.visibility = View.VISIBLE
                    doneBanner.bringToFront()
                }
                updateStatus()
                return
            }
            // Naya kaam / naya step → banner hatao.
            if (::doneBanner.isInitialized && doneBanner.visibility != View.GONE) {
                doneBanner.visibility = View.GONE
            }
            updateStatus()
        } catch (_: Exception) { }
    }

    private fun refreshFromLastEvent() {
        try {
            val last = LiveActivity.lastEvent() ?: run {
                updateStatus()
                return
            }
            onLiveEvent(last)
        } catch (_: Exception) {
            try { updateStatus() } catch (_: Exception) { }
        }
    }

    // ---------------- emergency stop ----------------

    private fun emergencyStop() {
        toast("Band ho raha…")
        Thread({
            try {
                OperatorSession.stop(this)
            } catch (_: Exception) { }
            // v38: active form run bhi band karo (notification wala rasta).
            try {
                FormRunService.requestCancelActive(this)
            } catch (_: Exception) { }
            if (!destroyed) {
                runOnUiThread { safe {
                    stopBtn.isEnabled = false
                    statusText.text = "Band kar diya — chat me dekho"
                    toast("Band ho gaya")
                } }
            }
        }, "OperatorView-stop").apply { isDaemon = true }.start()
    }
}

package com.formmitra.app.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import com.formmitra.app.agent.LiveActivity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v38 — LIVE WEBVIEW HOST (user order 2026-09-26):
 *
 * "Naya toggle nahi — wahi Live toggle, usi me LIVE WebView dikhe
 *  (screenshot delay nahi, asli live). Agent idle → khaali browser;
 *  agent kaam kare → sab kuch live. Browsing background me hi chalegi;
 *  live view SIRF display (view-only)."
 *
 * Design: POORE APP PROCESS me automation ke liye EK HI WebView.
 * - FormRunService ka engine aur OperatorSession ka engine dono isi ko
 *   use karte hain (FormEngine.useSharedWebView) — har run apna WebView
 *   nahi banata.
 * - Live view (OperatorView) isi instance ko apne layout me attach karke
 *   dikhata hai. Attach/detach par OBJECT NAHI badalta, isliye live
 *   kholne/band karne se automation kabhi rukti ya restart nahi hoti
 *   (approved addition #5).
 * - View-only: live view WebView ke UPAR transparent touch-blocker
 *   rakhta hai (WebView par koi OnTouchListener NAHI) — user ka tap
 *   automation ko chhu hi nahi sakta, aur engine ke synthetic taps
 *   (tapAt — reCAPTCHA checkbox) dispatchTouchEvent se seedha WebView
 *   par jate hain, isliye wo kaam karte rehte hain.
 *
 * Battery (approved addition #1):
 * - live visible → onResume()
 * - hidden + idle (koi automation nahi) → onPause() (JS timers samet
 *   pause — page ka kharcha zero; automation waise bhi nahi chal rahi)
 * - hidden + automation ACTIVE → kuch nahi (detached WebView waise bhi
 *   frame composite nahi karta — render cost zero; JS chalna ZAROORI
 *   hai automation ke liye, isliye onPause NAHI)
 *
 * Crash (approved addition #3): renderer process gaya to
 * onRenderProcessGone → true (host handle karta hai — APP CRASH NAHI),
 * ErrorCatcher report, naya WebView, aakhri URL reload, recreate
 * listeners ko suchna (engine apna reference badalta hai, live view
 * dobara attach karta hai), chat status line par "Browser theek ho
 * gaya, kaam jaari…".
 *
 * Threading: WebView ki CREATION aur saare View-method calls SIRF main
 * (UI) thread par (v33 rule). Public methods kisi bhi thread se —
 * andar onMain marshal. Har public entry Throwable-proof.
 */
object LiveWebViewHost {

    private const val TAG = "LiveWebViewHost"

    /** Battery policy ka faisla — PURE function (selftest me pin hai). */
    enum class PowerAction { RESUME, PAUSE, DETACHED_ACTIVE }

    @JvmStatic
    fun decidePowerAction(liveVisible: Boolean, automationActive: Boolean): PowerAction =
        when {
            liveVisible -> PowerAction.RESUME
            !automationActive -> PowerAction.PAUSE
            else -> PowerAction.DETACHED_ACTIVE
        }

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var webView: WebView? = null
    @Volatile private var automationActive = false
    @Volatile private var liveVisible = false
    @Volatile private var lastUrl: String = ""
    @Volatile private var setup: ((WebView) -> Unit)? = null
    @Volatile private var setupApplied = false

    /**
     * v56 — HELP WEBVIEW (dusra persistent browser).
     * Live tab me EXACTLY do browsers:
     *  1. workWebView (webView) — "🌐 User Browser": agent ka kaam wala browser.
     *  2. helpWebView — "🤖 AI Helper": agent ki help (AI Mode) wala browser.
     * Dono host ke paas PERSISTENT rehte hain — kabhi destroy nahi hote
     * (sirf renderer-crash par recreate + URL restore). FormEngine ka
     * getHelpWebView() ab hidden WebView NAHI banata — yehi shared
     * helpWebView deta hai, jo Live tab me VISIBLE hai.
     */
    @Volatile private var helpWebView: WebView? = null
    @Volatile private var helpLastUrl: String = ""
    @Volatile private var helpSetup: ((WebView) -> Unit)? = null
    @Volatile private var helpSetupApplied = false
    private val helpRecreateListeners =
        CopyOnWriteArrayList<(WebView) -> Unit>()

    private val recreateListeners = CopyOnWriteArrayList<(WebView) -> Unit>()

    fun addRecreateListener(l: (WebView) -> Unit) { recreateListeners.add(l) }
    fun removeRecreateListener(l: (WebView) -> Unit) { recreateListeners.remove(l) }

    /** Current hosted WebView (null = abhi bana hi nahi). */
    fun current(): WebView? = webView

    /** v56: current HELP WebView (AI Helper browser). */
    fun currentHelp(): WebView? = helpWebView

    fun addHelpRecreateListener(l: (WebView) -> Unit) {
        helpRecreateListeners.add(l)
    }
    fun removeHelpRecreateListener(l: (WebView) -> Unit) {
        helpRecreateListeners.remove(l)
    }

    /** Koi automation (form run / operator session) chal rahi hai? */
    fun isAutomationActive(): Boolean = automationActive

    fun isLiveVisible(): Boolean = liveVisible

    private fun <T> onMain(timeoutMs: Long = 30_000, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Any? = null
        var err: Throwable? = null
        val latch = CountDownLatch(1)
        try {
            mainHandler.post {
                try {
                    result = block()
                } catch (t: Throwable) {
                    err = t
                } finally {
                    latch.countDown()
                }
            }
        } catch (t: Throwable) {
            throw t
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw java.util.concurrent.TimeoutException(
                "LiveWebViewHost main-thread marshal timeout"
            )
        }
        err?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun createWebViewLocked(appCtx: Context): WebView {
        // Caller MAIN thread par hai.
        val wv = WebView(appCtx)
        try {
            wv.webViewClient = HostWebViewClient()
        } catch (_: Exception) { }
        try {
            setup?.invoke(wv)
            setupApplied = setup != null
        } catch (t: Throwable) {
            Log.e(TAG, "engine setup failed (non-fatal)", t)
        }
        return wv
    }

    /**
     * Automation ke liye WebView lo. Pehli baar banega; dobara wahi milega.
     * Sirf dekhne ke liye bana WebView ho to engine setup ab lagao.
     */
    fun acquire(appCtx: Context, setup: (WebView) -> Unit): WebView {
        val ac = try { appCtx.applicationContext } catch (_: Exception) { appCtx }
        this.setup = setup
        return onMain {
            var wv = webView
            if (wv == null) {
                wv = createWebViewLocked(ac)
                webView = wv
            } else if (!setupApplied) {
                try {
                    setup(wv)
                } catch (t: Throwable) {
                    Log.e(TAG, "late engine setup failed (non-fatal)", t)
                }
                setupApplied = true
            }
            automationActive = true
            try {
                wv.onResume()
            } catch (_: Exception) { }
            applyPowerPolicyLocked()
            wv
        }
    }

    /**
     * Sirf dekhne ke liye WebView pakka karo (idle live view — "khaali
     * browser"). automationActive NAHI badalta, engine setup NAHI lagta
     * (automation shuru hogi to acquire me lagega).
     */
    fun ensureForViewing(appCtx: Context): WebView {
        val ac = try { appCtx.applicationContext } catch (_: Exception) { appCtx }
        return onMain {
            var wv = webView
            if (wv == null) {
                wv = createWebViewLocked(ac)
                try {
                    wv.loadUrl("about:blank")
                } catch (_: Exception) { }
                webView = wv
                applyPowerPolicyLocked()
            }
            wv
        }
    }

    /**
     * v56 — HELP BROWSER acquire (AI Helper).
     * FormEngine.getHelpWebView() / AiHelpSystem yahin se lega.
     * Pehli baar banega (desktop UA + engine setup); dobara wahi milega.
     * Live tab ke "🤖 AI Helper" section me YEHI WebView attach hota hai —
     * agent jo help karta hai wo user ko LIVE dikhta hai.
     */
    fun acquireHelp(appCtx: Context, setup: (WebView) -> Unit): WebView {
        val ac = try { appCtx.applicationContext } catch (_: Exception) { appCtx }
        helpSetup = setup
        return onMain {
            var wv = helpWebView
            if (wv == null) {
                wv = WebView(ac)
                try {
                    wv.webViewClient = HostWebViewClient()
                } catch (_: Exception) { }
                try {
                    // Desktop mode (user order — dono browser desktop).
                    wv.settings.userAgentString =
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                } catch (_: Exception) { }
                try {
                    setup(wv)
                    helpSetupApplied = true
                } catch (t: Throwable) {
                    Log.e(TAG, "help engine setup failed (non-fatal)", t)
                }
                helpWebView = wv
            } else if (!helpSetupApplied) {
                try {
                    setup(wv)
                } catch (t: Throwable) {
                    Log.e(TAG, "late help setup failed (non-fatal)", t)
                }
                helpSetupApplied = true
            }
            try { wv.onResume() } catch (_: Exception) { }
            wv
        }
    }

    /**
     * v56 — Sirf dekhne ke liye HELP WebView pakka karo (idle AI Helper).
     * Engine setup NAHI lagta (help shuru hogi to acquireHelp me lagega).
     */
    fun ensureHelpForViewing(appCtx: Context): WebView {
        val ac = try { appCtx.applicationContext } catch (_: Exception) { appCtx }
        return onMain {
            var wv = helpWebView
            if (wv == null) {
                wv = WebView(ac)
                try {
                    wv.webViewClient = HostWebViewClient()
                } catch (_: Exception) { }
                try {
                    wv.settings.userAgentString =
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                } catch (_: Exception) { }
                try {
                    wv.loadUrl("about:blank")
                } catch (_: Exception) { }
                helpWebView = wv
            }
            wv
        }
    }

    /** v56: help browser ka aakhri URL (crash-recreate par restore). */
    fun trackHelpUrl(url: String) {
        val u = url.trim()
        if (u.isNotEmpty() && !u.startsWith("about:")) helpLastUrl = u
    }

    /** Hidden mode ka measure/layout (screenshot/render ke liye). */
    fun layoutForHidden() {
        try {
            onMain {
                val wv = webView ?: return@onMain
                try {
                    wv.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(
                            1080, android.view.View.MeasureSpec.EXACTLY
                        ),
                        android.view.View.MeasureSpec.makeMeasureSpec(
                            1920, android.view.View.MeasureSpec.EXACTLY
                        )
                    )
                    wv.layout(0, 0, 1080, 1920)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    /**
     * Automation khatm — battery policy (destroy NAHI).
     *
     * v56 ROOT FIX ("browser band ho jata hai"):
     * Pehle yahan webView.loadUrl("about:blank") hota tha — run ke
     * PARK/finish hote hi browser BLANK ho jata tha. OTP gate par run jab
     * park hota tha (AgentLoop finally → engine.stop() → release()), user
     * dekhta tha "browser band ho gaya", aur resume par page dobara load
     * karna padta tha (state/cookies/form-data risk).
     * Ab: page JAISE KA TAISA rehta hai — last URL, DOM, cookies, history
     * sab bache rehte hain. Naya run apna URL khud load karega.
     */
    fun release() {
        automationActive = false
        try {
            onMain {
                layoutForHidden()
                applyPowerPolicyLocked()
            }
        } catch (_: Exception) { }
        Log.i(TAG, "released (idle, page preserved)")
    }

    /** Live view attach/detach ki suchna — battery policy badalti hai. */
    fun setLiveVisible(v: Boolean) {
        liveVisible = v
        try {
            onMain { applyPowerPolicyLocked() }
        } catch (_: Exception) { }
    }

    /**
     * Aakhri dekha hua URL (crash-recreate par wapas kholne ke liye).
     * Public taaki HostWebViewClient (nested class) track kar sake.
     * v56: kaunsa WebView hai us hisaab se work/help me track karo.
     */
    fun trackUrl(view: WebView?, url: String) {
        val u = url.trim()
        if (u.isEmpty() || u.startsWith("about:")) return
        try {
            if (view != null && view === helpWebView) {
                helpLastUrl = u
                // v58: Admin takeover me admin ki navigation capture karo
                // (agent seekhega). Agent ki apni navigation log nahi hoti.
                if (AdminTakeover.isDriving(false)) {
                    try {
                        AdminActionCapture.log(
                            view.context.applicationContext,
                            "help", "admin_navigate", u, null
                        )
                    } catch (_: Exception) { }
                }
                return
            }
        } catch (_: Exception) { }
        lastUrl = u
        // v58: work browser par admin takeover capture
        try {
            if (view != null && AdminTakeover.isDriving(true)) {
                AdminActionCapture.log(
                    view.context.applicationContext,
                    "work", "admin_navigate", u, null
                )
            }
        } catch (_: Exception) { }
    }

    /** Purana signature (compat) — work browser me track. */
    fun trackUrl(url: String) {
        val u = url.trim()
        if (u.isNotEmpty() && !u.startsWith("about:")) lastUrl = u
    }

    private fun applyPowerPolicyLocked() {
        // Caller MAIN thread par hai (hamesha onMain ke andar se).
        val wv = webView ?: return
        try {
            when (decidePowerAction(liveVisible, automationActive)) {
                PowerAction.RESUME -> wv.onResume()
                PowerAction.PAUSE -> wv.onPause()
                PowerAction.DETACHED_ACTIVE -> {
                    // JS chalta rahe (automation); frame waise bhi
                    // composite nahi hota (koi window nahi) — render cost zero.
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Renderer process crash — host ise handle karta hai (caller true
     * return karega), APP CRASH NAHI hota. Naya WebView + aakhri URL +
     * listeners ko suchna. Private-mode engine ka WebView ho to sirf
     * report (usko engine khud handle karega).
     */
    fun handleRenderProcessGone(crashed: WebView?, detail: String) {
        // v56: kaunsa browser crash hua — work ya help?
        val crashedWork = try {
            crashed != null && crashed === webView
        } catch (_: Exception) { false }
        val crashedHelp = try {
            crashed != null && crashed === helpWebView
        } catch (_: Exception) { false }
        val isHosted = crashedWork || crashedHelp
        // Report hamesha (hosted ho ya private-mode).
        try {
            val ctx = try { crashed?.context?.applicationContext } catch (_: Exception) { null }
            if (ctx != null) {
                try {
                    ErrorCatcher.report(
                        ctx, "Browser (live view)",
                        RuntimeException("WebView renderer crash: $detail"),
                        "live-webview", ""
                    )
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        if (!isHosted) {
            Log.i(TAG, "render gone on non-hosted WebView — reported only")
            return
        }
        try {
            val appCtx = try { crashed?.context?.applicationContext } catch (_: Exception) { null }
                ?: return
            onMain {
                if (crashedWork) {
                    try {
                        (webView?.parent as? ViewGroup)?.removeView(webView)
                    } catch (_: Exception) { }
                    try {
                        webView?.destroy()
                    } catch (_: Exception) { }
                    val wv = createWebViewLocked(appCtx)
                    val url = lastUrl
                    if (url.isNotEmpty()) {
                        try {
                            wv.loadUrl(url)
                        } catch (_: Exception) { }
                    }
                    webView = wv
                    applyPowerPolicyLocked()
                    for (l in recreateListeners) {
                        try {
                            l(wv)
                        } catch (t: Throwable) {
                            Log.e(TAG, "recreate listener failed (non-fatal)", t)
                        }
                    }
                } else if (crashedHelp) {
                    // v56: AI Helper browser crash — wapas banao + URL restore.
                    try {
                        (helpWebView?.parent as? ViewGroup)?.removeView(helpWebView)
                    } catch (_: Exception) { }
                    try {
                        helpWebView?.destroy()
                    } catch (_: Exception) { }
                    val wv = WebView(appCtx)
                    try {
                        wv.webViewClient = HostWebViewClient()
                    } catch (_: Exception) { }
                    try {
                        wv.settings.userAgentString =
                            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    } catch (_: Exception) { }
                    try {
                        helpSetup?.invoke(wv)
                        helpSetupApplied = helpSetup != null
                    } catch (t: Throwable) {
                        Log.e(TAG, "help recreate setup failed (non-fatal)", t)
                    }
                    val url = helpLastUrl
                    if (url.isNotEmpty()) {
                        try {
                            wv.loadUrl(url)
                        } catch (_: Exception) { }
                    }
                    helpWebView = wv
                    for (l in helpRecreateListeners) {
                        try {
                            l(wv)
                        } catch (t: Throwable) {
                            Log.e(TAG, "help recreate listener failed (non-fatal)", t)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "handleRenderProcessGone failed (non-fatal)", t)
        }
        // Chat status line: "Browser theek ho gaya, kaam jaari…"
        try {
            LiveActivity.emitRecovered()
        } catch (_: Exception) { }
        Log.i(TAG, "renderer gone handled — WebView recreated")
    }

    /**
     * Host ka WebViewClient — renderer-crash handle + URL track.
     * Engine ke per-navigation clients ISI ko extend karenge taaki crash
     * handling kabhi na khoye (open class).
     */
    open class HostWebViewClient : WebViewClient() {
        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?
        ): Boolean {
            return try {
                val d = try {
                    detail?.toString()?.take(200) ?: "unknown"
                } catch (_: Exception) {
                    "unknown"
                }
                try {
                    LiveWebViewHost.handleRenderProcessGone(view, d)
                } catch (t: Throwable) {
                    Log.e(TAG, "render-gone handling threw (non-fatal)", t)
                }
                true // humne handle kiya — default crash path NAHI
            } catch (_: Exception) {
                true
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            try {
                if (url != null) LiveWebViewHost.trackUrl(view, url)
            } catch (_: Exception) { }
            try {
                super.onPageFinished(view, url)
            } catch (_: Exception) { }
        }

        override fun doUpdateVisitedHistory(
            view: WebView?,
            url: String?,
            isReload: Boolean
        ) {
            try {
                if (url != null) LiveWebViewHost.trackUrl(view, url)
            } catch (_: Exception) { }
            try {
                super.doUpdateVisitedHistory(view, url, isReload)
            } catch (_: Exception) { }
        }
    }
}

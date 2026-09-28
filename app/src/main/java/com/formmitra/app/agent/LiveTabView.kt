package com.formmitra.app.agent

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.formmitra.app.engine.AdminActionCapture
import com.formmitra.app.engine.AdminTakeover
import com.formmitra.app.engine.BrowserPersistence
import com.formmitra.app.engine.FormRunService

/**
 * v58 LIVE TAB — Do FIXED browser, koi tab system nahi.
 *
 * User order (2026-09-28):
 * 1. "+" se unlimited tabs BAND — sirf 2 fixed browser (work + AI helper),
 *    agent ke liye. Koi naya tab nahi khul sakta.
 * 2. USER (non-admin): sirf WORK browser dikhega — aur wo bhi VIEW-ONLY
 *    (dekh sakta hai, chhu nahi sakta). AI Helper user ko dikhega HI NAHI.
 *    User agent/operator/AI ka kaam override nahi kar sakta.
 * 3. ADMIN (owner email se login): DONO browser dekh + chala sakta hai.
 *    Admin jahan tap karega, jo karega — wo capture hoga taaki agent
 *    usse smartly seekhe.
 * 4. CONFLICT SOLUTION: Admin jis browser ko chhuta hai, agent us browser
 *    par TURANT ruk jata hai (AdminTakeover) — dono ek saath nahi ladte.
 *    30s idle par auto-release, ya "Agent ko wapas do" button.
 *
 * Layout (admin):
 * ┌─────────────────────────────────┐
 * │ [🌐 Work Browser] [🤖 AI Helper] │ ← sirf admin
 * ├─────────────────────────────────┤
 * │ URL bar + Go                    │ ← sirf admin
 * ├─────────────────────────────────┤
 * │                                 │
 * │      WebView (work/help)        │
 * │                                 │
 * ├─────────────────────────────────┤
 * │ 👤/🤖 status + [Agent ko wapas] │ ← release sirf admin
 * └─────────────────────────────────┘
 *
 * Layout (user): sirf WebView (work) + status — kuch aur nahi.
 */
class LiveTabView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : LinearLayout(ctx, attrs) {

    private var workWv: WebView? = null
    private var helpWv: WebView? = null
    private var showingAi = false
    private var isAdmin = false

    private var browserSwitchRow: LinearLayout? = null
    private var urlRow: LinearLayout? = null
    private var urlBar: EditText? = null
    private var webContainer: FrameLayout? = null
    private var statusText: TextView? = null
    private var releaseBtn: Button? = null
    private var takeControlBtn: Button? = null
    private var releaseRow: LinearLayout? = null
    // v61: blank browser par helpful placeholder (about:blank = "kuch nahi"
    // lagta tha — user ko lagta tha live kaam nahi kar raha).
    private var placeholderText: TextView? = null

    init {
        orientation = VERTICAL
        isAdmin = try {
            FormRunService.isOwnerDevice(ctx)
        } catch (_: Exception) { false }
        buildUi(ctx)
    }

    private fun buildUi(ctx: Context) {
        // --- Top: browser switch (SIRF ADMIN) ---
        if (isAdmin) {
            val tabRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val workBtn = Button(ctx).apply {
                text = "🌐 Work Browser"
                setOnClickListener { showBrowser(false) }
            }
            val helpBtn = Button(ctx).apply {
                text = "🤖 AI Helper"
                setOnClickListener { showBrowser(true) }
            }
            tabRow.addView(workBtn, LayoutParams(0, -2, 1f))
            tabRow.addView(helpBtn, LayoutParams(0, -2, 1f))
            browserSwitchRow = tabRow
            addView(tabRow)

            // --- URL bar (SIRF ADMIN) ---
            val uRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            urlBar = EditText(ctx).apply {
                hint = "URL ya search..."
                textSize = 14f
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setHorizontallyScrolling(true)
            }
            val goBtn = Button(ctx).apply {
                text = "Go"
                setOnClickListener { navigateCurrent() }
            }
            uRow.addView(urlBar, LayoutParams(0, -2, 1f))
            uRow.addView(goBtn)
            urlRow = uRow
            addView(uRow)
        }

        // --- WebViews: EXACTLY 2, fixed. Koi tab system nahi. ---
        val webContainerView = FrameLayout(ctx).apply {
            layoutParams = LayoutParams(-1, 0, 1f)
        }
        webContainer = webContainerView
        addView(webContainerView)
        attachSharedBrowsers(ctx)

        // v61: blank browser placeholder — WebViews ke UPAR overlay.
        // Jab current browser blank ho (about:blank) aur koi automation
        // active na ho, to ye dikhega: "kuch nahi" ki jagah saaf message.
        placeholderText = TextView(ctx).apply {
            text = "🤖 Agent abhi koi kaam nahi kar raha\n\n" +
                "Kaam shuru karte hi yahan LIVE dikhega — " +
                "browser khud chalega, aap dekh sakte hain."
            textSize = 16f
            setTextColor(0xFF616161.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFFF5F5F5.toInt())
            visibility = View.GONE
        }
        webContainerView.addView(
            placeholderText,
            FrameLayout.LayoutParams(-1, -1)
        )

        // --- Status + release (release button SIRF ADMIN) ---
        statusText = TextView(ctx).apply {
            textSize = 12f
            setPadding(16, 8, 16, 8)
        }
        addView(statusText)

        if (isAdmin) {
            val rRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            // v59: TAKE CONTROL — explicit button (touch se auto nahi).
            // Dikhata hai jab admin control me NAHI hai.
            takeControlBtn = Button(ctx).apply {
                text = "✋ Take Control"
                setOnClickListener {
                    AdminTakeover.takeControl(!showingAi)
                    updateStatus()
                    android.widget.Toast.makeText(
                        context,
                        "Aap control me — agent ruk gaya",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                visibility = View.VISIBLE // shuru me control nahi hai
            }
            releaseBtn = Button(ctx).apply {
                text = "🤖 Agent ko wapas do"
                setOnClickListener {
                    AdminTakeover.release(!showingAi)
                    updateStatus()
                    android.widget.Toast.makeText(
                        context,
                        "Agent wapas kaam par",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                visibility = View.GONE // sirf takeover par dikhega
            }
            rRow.addView(takeControlBtn, LayoutParams(0, -2, 1f))
            rRow.addView(releaseBtn, LayoutParams(0, -2, 1f))
            releaseRow = rRow
            addView(rRow)
        }

        // Takeover change par status refresh (UI thread par)
        AdminTakeover.onChange = {
            try {
                post { updateStatus() }
            } catch (_: Exception) { }
        }
        updateStatus()
        startUrlSync()
    }

    /**
     * Dono shared engine WebViews yahan attach karo.
     * - workWv = agent ka KAAM wala browser (FormEngine)
     * - helpWv = agent ka HELP wala browser (AiHelpSystem / AI Mode)
     * Kabhi destroy nahi, kabhi naye nahi — exactly 2.
     */
    private fun attachSharedBrowsers(ctx: Context) {
        try {
            val wv = com.formmitra.app.engine.LiveWebViewHost
                .ensureForViewing(ctx)
            workWv = wv
            attachToContainer(wv)
            applyTouchPolicy(wv, work = true)
        } catch (t: Throwable) {
            android.util.Log.e("FmLiveTab", "shared work attach failed", t)
        }
        try {
            val hv = com.formmitra.app.engine.LiveWebViewHost
                .ensureHelpForViewing(ctx)
            helpWv = hv
            attachToContainer(hv)
            applyTouchPolicy(hv, work = false)
        } catch (t: Throwable) {
            android.util.Log.e("FmLiveTab", "shared help attach failed", t)
        }
        updateBrowserVisibility()
    }

    /** WebView ko webContainer me lagao (purane parent se hata kar). */
    private fun attachToContainer(wv: WebView) {
        try {
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        } catch (_: Exception) { }
        try {
            wv.layoutParams = FrameLayout.LayoutParams(-1, -1)
            if (wv.parent !== webContainer) {
                webContainer?.addView(wv)
            }
        } catch (_: Exception) { }
    }

    /**
     * TOUCH POLICY (v59) — kaun chhu sakta hai:
     * - USER: kuch nahi chhu sakta — har touch consume (view-only).
     * - ADMIN bina control ke: view-only (user jaisa) — touch se
     *   auto-takeover NAHI (user order).
     * - ADMIN control me (Take Control button se): touch WebView ko
     *   milta hai — browse kar sakta hai.
     */
    private fun applyTouchPolicy(wv: WebView, work: Boolean) {
        if (!isAdmin) {
            // USER: view-only — saare touch kha jao
            wv.setOnTouchListener { _, _ -> true }
        } else {
            // ADMIN: sirf control me hone par touch WebView ko mile.
            // Bina control = view-only (touch consume, takeover nahi).
            wv.setOnTouchListener { _, event ->
                try {
                    val driving = AdminTakeover.isDriving(work)
                    if (!driving) {
                        // View-only — touch kha jao, takeover NAHI
                        true
                    } else {
                        // Control me hai — idle timer refresh, event pass
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            AdminTakeover.onAdminTouch(work)
                        }
                        false
                    }
                } catch (_: Exception) { true }
            }
        }
    }

    private fun currentWebView(): WebView? =
        if (showingAi) helpWv else workWv

    private fun updateBrowserVisibility() {
        // User ko AI Helper KABHI nahi dikhta — hamesha work browser.
        val showHelp = isAdmin && showingAi
        try {
            workWv?.visibility = if (showHelp) View.GONE else View.VISIBLE
            helpWv?.visibility = if (showHelp) View.VISIBLE else View.GONE
        } catch (_: Exception) { }
        updatePlaceholder()
    }

    /**
     * v61: placeholder show/hide logic.
     * - Current browser blank (about:blank/null) AUR koi automation active
     *   nahi → placeholder DIKHAO.
     * - Automation chal rahi ho ya browser me koi page ho → CHHUPAO.
     */
    private fun updatePlaceholder() {
        try {
            val wv = currentWebView()
            val url = try { wv?.url } catch (_: Exception) { null }
            val isBlank = url.isNullOrEmpty() ||
                url.startsWith("about:") ||
                url == "about:blank"
            val automationActive = try {
                com.formmitra.app.engine.FormRunService.activeTaskId != null
            } catch (_: Exception) { false }
            val showPlaceholder = isBlank && !automationActive
            placeholderText?.visibility =
                if (showPlaceholder) View.VISIBLE else View.GONE
            // Placeholder upar ho to WebView neeche — dono overlap nahi.
            // (FrameLayout me placeholder baad me add hua = upar.)
        } catch (_: Exception) {
            try { placeholderText?.visibility = View.GONE } catch (_: Exception) { }
        }
    }

    private fun showBrowser(showAi: Boolean) {
        // User AI Helper par ja hi nahi sakta (button hai hi nahi),
        // phir bhi guard.
        if (showAi && !isAdmin) return
        showingAi = showAi
        try { reattachShared() } catch (_: Exception) { }
        updateBrowserVisibility()
        updateStatus()
        urlBar?.setText(currentWebView()?.url ?: "")
    }

    /**
     * v56 se: Live tab visible ho to shared WebViews wapas yahan lao
     * (OperatorView ne liye hon to).
     * v61: visible hote hi placeholder bhi refresh (automation state badal
     * sakta hai jab tab chhupa tha).
     */
    override fun onVisibilityChanged(
        changedView: View,
        visibility: Int
    ) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            try { reattachShared() } catch (_: Exception) { }
            try { updatePlaceholder() } catch (_: Exception) { }
            startPlaceholderSync()
        } else {
            stopPlaceholderSync()
        }
    }

    // v61: placeholder ka periodic sync — automation start/stop hote hi
    // placeholder update ho (tab khula ho to). Halka: 2s me ek baar.
    private val placeholderSyncHandler = android.os.Handler(
        android.os.Looper.getMainLooper()
    )
    private val placeholderSyncRunnable = object : Runnable {
        override fun run() {
            try {
                if (visibility == View.VISIBLE) {
                    updatePlaceholder()
                    placeholderSyncHandler.postDelayed(this, 2000)
                }
            } catch (_: Exception) { }
        }
    }

    private fun startPlaceholderSync() {
        try {
            placeholderSyncHandler.removeCallbacks(placeholderSyncRunnable)
            placeholderSyncHandler.post(placeholderSyncRunnable)
        } catch (_: Exception) { }
    }

    private fun stopPlaceholderSync() {
        try {
            placeholderSyncHandler.removeCallbacks(placeholderSyncRunnable)
        } catch (_: Exception) { }
    }

    private fun reattachShared() {
        try {
            workWv?.let { attachToContainer(it) }
            helpWv?.let { attachToContainer(it) }
            updateBrowserVisibility()
            urlBar?.setText(currentWebView()?.url ?: "")
            startUrlSync()
        } catch (_: Exception) { }
    }

    // --- URL bar live sync (sirf admin ke liye visible) ---
    private val urlSyncHandler = android.os.Handler(
        android.os.Looper.getMainLooper()
    )
    private var lastSyncedUrl: String? = null
    private val urlSyncRunnable = object : Runnable {
        override fun run() {
            try {
                if (visibility == View.VISIBLE && isAdmin) {
                    val cur = currentWebView()?.url ?: ""
                    if (cur != lastSyncedUrl) {
                        lastSyncedUrl = cur
                        urlBar?.setText(cur)
                    }
                    urlSyncHandler.postDelayed(this, 1500)
                }
            } catch (_: Exception) { }
        }
    }

    private fun startUrlSync() {
        try {
            urlSyncHandler.removeCallbacks(urlSyncRunnable)
            lastSyncedUrl = null
            if (isAdmin) urlSyncHandler.post(urlSyncRunnable)
        } catch (_: Exception) { }
    }

    /**
     * STATUS (v59) — hamesha saaf dikhe kaun control me hai:
     * - User: "🤖 Agent kaam kar raha hai"
     * - Admin, agent kaam kar raha: "🤖 Agent kaam kar raha hai" +
     *   [Take Control] button (touch se control NAHI milta)
     * - Admin, takeover: "👤 Aap control me hain — agent ruka hai" +
     *   [Agent ko wapas do] button
     */
    private fun updateStatus() {
        try {
            val driving = AdminTakeover.isDriving(!showingAi)
            val base = if (showingAi) "🤖 AI Helper" else "🌐 Work Browser"
            statusText?.text = when {
                driving && isAdmin ->
                    "👤 $base — AAP control me hain, agent ruka hai"
                driving ->
                    "🤖 $base — agent ruka hai"
                isAdmin ->
                    "🤖 $base — agent kaam kar raha hai (control ke liye Take Control dabayein)"
                else ->
                    "🤖 Agent kaam kar raha hai — aap dekh sakte hain"
            }
            // v59: Take Control = admin + NOT driving; Release = admin + driving
            takeControlBtn?.visibility =
                if (isAdmin && !driving) View.VISIBLE else View.GONE
            releaseBtn?.visibility =
                if (isAdmin && driving) View.VISIBLE else View.GONE
        } catch (_: Exception) { }
    }

    private fun navigateCurrent() {
        if (!isAdmin) return // user ke paas URL bar hai hi nahi
        // v59: URL bar ek EXPLICIT admin action hai (type + Go) —
        // isko takeover intent maano (casual touch nahi).
        AdminTakeover.takeControl(!showingAi)
        val input = urlBar?.text?.toString()?.trim() ?: return
        if (input.isEmpty()) return
        val url = if (input.startsWith("http")) input
        else "https://www.google.com/search?q=" +
            java.net.URLEncoder.encode(input, "UTF-8")
        try {
            currentWebView()?.loadUrl(url)
            AdminActionCapture.log(
                context, if (showingAi) "help" else "work",
                "admin_navigate", url, "url_bar"
            )
        } catch (_: Exception) { }
        updateStatus()
    }

    /**
     * Admin ki navigation capture — LiveWebViewHost.trackUrl se call hota
     * hai jab AdminTakeover driving ho. (Hook LiveWebViewHost me hai.)
     */
    fun captureAdminNavigation(work: Boolean, url: String) {
        try {
            if (!isAdmin) return
            AdminActionCapture.log(
                context, if (work) "work" else "help",
                "admin_navigate", url, null
            )
        } catch (_: Exception) { }
    }

    /**
     * Kaam wala browser do (engine ke liye).
     * Purana naam barkarar taaki baaki code na toote.
     */
    fun getWorkWebView(): WebView? = workWv
    fun getHelpWebView(): WebView? = helpWv

    fun onDestroy() {
        try {
            urlSyncHandler.removeCallbacks(urlSyncRunnable)
        } catch (_: Exception) { }
        try {
            placeholderSyncHandler.removeCallbacks(placeholderSyncRunnable)
        } catch (_: Exception) { }
        AdminTakeover.onChange = null
        try {
            // SHARED WebViews KABHI destroy nahi — sirf detach.
            // (Engine ka browser band nahi hona chahiye.)
            listOfNotNull(workWv, helpWv).forEach { wv ->
                try {
                    (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                } catch (_: Exception) { }
            }
            workWv = null
            helpWv = null
        } catch (_: Exception) { }
    }
}

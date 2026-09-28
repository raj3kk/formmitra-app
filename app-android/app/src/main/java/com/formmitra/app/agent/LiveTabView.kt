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
    private var releaseRow: LinearLayout? = null

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
            rRow.addView(releaseBtn, LayoutParams(-1, -2))
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
     * TOUCH POLICY — kaun chhu sakta hai:
     * - USER: kuch nahi chhu sakta — har touch consume (view-only).
     * - ADMIN: chhu sakta hai — touch par AdminTakeover mark hota hai.
     */
    private fun applyTouchPolicy(wv: WebView, work: Boolean) {
        if (!isAdmin) {
            // USER: view-only — saare touch kha jao
            wv.setOnTouchListener { _, _ -> true }
        } else {
            // ADMIN: touch par takeover mark karo (consume mat karo —
            // WebView ko event milna chahiye taaki browse kar sake)
            wv.setOnTouchListener { _, event ->
                try {
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        AdminTakeover.onAdminTouch(work)
                    }
                } catch (_: Exception) { }
                false
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
     */
    override fun onVisibilityChanged(
        changedView: View,
        visibility: Int
    ) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            try { reattachShared() } catch (_: Exception) { }
        }
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
     * STATUS — hamesha saaf dikhe kaun control me hai:
     * - User: "🤖 Agent kaam kar raha hai"
     * - Admin, agent kaam kar raha: "🤖 Agent kaam kar raha hai"
     * - Admin, takeover: "👤 Aap control me hain — agent ruka hai"
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
                    "🤖 $base — agent kaam kar raha hai (aap chhu sakte hain)"
                else ->
                    "🤖 Agent kaam kar raha hai — aap dekh sakte hain"
            }
            // Release button sirf admin ko, sirf takeover par
            releaseBtn?.visibility =
                if (isAdmin && driving) View.VISIBLE else View.GONE
        } catch (_: Exception) { }
    }

    private fun navigateCurrent() {
        if (!isAdmin) return // user ke paas URL bar hai hi nahi
        // Admin ki navigation = takeover (agent na lade)
        AdminTakeover.onAdminTouch(!showingAi)
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

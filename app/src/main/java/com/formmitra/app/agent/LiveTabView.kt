package com.formmitra.app.agent

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.formmitra.app.engine.BrowserPersistence

/**
 * v51 LIVE TAB — Do browser.
 *
 * User demand:
 * - "Chat se live remove karo — live tab implement karo jisme do browser ho"
 * - "Ek me user ka kaam, dusre se AI seekhe solution nikale"
 * - "Jab koi problem ho, screenshot le ke dusre browser me search karega"
 * - "Back, forward, scroll, zoom in/out — jo hona chahiye"
 * - "Screenshot kahan rahe, kaise upload hoga AI mode me"
 *
 * Layout:
 * ┌─────────────────────────────┐
 * │ [User Browser] [AI Browser] │ ← tab switch
 * ├─────────────────────────────┤
 * │  URL bar + Go               │
 * ├─────────────────────────────┤
 * │                             │
 * │      WebView                │
 * │                             │
 * ├─────────────────────────────┤
 * │ ◀ ▶ ⟳ 🔍+ 🔍- 📸 🤖        │ ← controls
 * └─────────────────────────────┘
 *
 * User Browser: automation yahan chalta hai (kabhi band nahi hota).
 * AI Browser: problem par AI yahan search/solution nikalta hai.
 */
class LiveTabView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : LinearLayout(ctx, attrs) {

    /**
     * v53: TAB — ek browser me multiple tabs.
     * v56: Tab 0 = SHARED engine WebView (LiveWebViewHost) — agent ka asli
     * browser. User ke extra tabs (1, 2, ...) local hain.
     * User order: "dusra work ya dusra link visit karna ho to dusra tab
     * khol ke search kare, visit kare — jaisa hona chahiye situation ke
     * according."
     */
    private data class BrowserTab(
        val webView: WebView,
        var title: String = "Naya tab",
        val isShared: Boolean = false
    )

    private val userTabs = mutableListOf<BrowserTab>()
    private val aiTabs = mutableListOf<BrowserTab>()
    private var userTabIndex = 0
    private var aiTabIndex = 0
    private var tabStrip: android.widget.HorizontalScrollView? = null
    private var tabStripRow: LinearLayout? = null
    private var webContainer: android.widget.FrameLayout? = null

    // Purane references (compat — ab tabs se map hote hain)
    private val userWebView: WebView?
        get() = userTabs.getOrNull(userTabIndex)?.webView
    private val aiWebView: WebView?
        get() = aiTabs.getOrNull(aiTabIndex)?.webView
    private var showingAi = false
    private var urlBar: EditText? = null
    private var statusText: TextView? = null

    init {
        orientation = VERTICAL
        buildUi(ctx)
    }

    private fun buildUi(ctx: Context) {
        // --- Top: browser switch tabs ---
        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val userTabBtn = Button(ctx).apply {
            text = "🌐 User Browser"
            setOnClickListener { showBrowser(false) }
        }
        val aiTabBtn = Button(ctx).apply {
            text = "🤖 AI Helper"
            setOnClickListener { showBrowser(true) }
        }
        tabRow.addView(userTabBtn, LayoutParams(0, -2, 1f))
        tabRow.addView(aiTabBtn, LayoutParams(0, -2, 1f))
        addView(tabRow)

        // --- URL bar ---
        val urlRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        urlBar = EditText(ctx).apply {
            hint = "URL ya search..."
            textSize = 14f
            // v52: Lamba URL wrap nahi hoga — single line, end me "..."
            // (jaise Chrome). Screenshot me poora URL faila hua tha.
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setHorizontallyScrolling(true)
        }
        val goBtn = Button(ctx).apply {
            text = "Go"
            setOnClickListener { navigateCurrent() }
        }
        urlRow.addView(urlBar, LayoutParams(0, -2, 1f))
        urlRow.addView(goBtn)
        addView(urlRow)

        // --- WebViews: TAB SYSTEM (v53) ---
        // Dono browser ke tabs — "+" se naya tab, tap se switch.
        // Browser kabhi band nahi hote — jahan the wahin rehte hain.
        // v56 ROOT FIX: Tab 0 = SHARED engine WebViews (LiveWebViewHost).
        // "🌐 User Browser" ka tab 0 = agent ka KAAM wala browser (FormEngine
        // isi ko chalata hai). "🤖 AI Helper" ka tab 0 = agent ki HELP wala
        // browser (AiHelpSystem/AI Mode isi me). Dono Live tab me VISIBLE —
        // agent operator jo karega wo yahin LIVE dikhega.
        val tabStripView = android.widget.HorizontalScrollView(ctx).apply {
            layoutParams = LayoutParams(-1, -2)
        }
        tabStripRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabStripRow?.let { tabStripView.addView(it) }
        tabStrip = tabStripView
        addView(tabStripView)

        val webContainerView = FrameLayout(ctx).apply {
            layoutParams = LayoutParams(-1, 0, 1f)
        }
        webContainer = webContainerView
        addView(webContainerView)

        // v56: Pehla tab dono browser me = SHARED (engine wale WebViews).
        attachSharedTabs(ctx)
        showBrowser(false)
        refreshTabStrip()

        // --- Status ---
        statusText = TextView(ctx).apply {
            text = "User browser ready"
            textSize = 12f
            setPadding(16, 8, 16, 8)
        }
        addView(statusText)

        // --- Controls: back, forward, reload, zoom ---
        // v51: Vision AI OPERATOR khud use karta hai (user nahi) —
        // isliye yahan koi manual AI button nahi. Operator atke to
        // khud screenshot le ke AI se puchega.
        val ctrlRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val backBtn = Button(ctx).apply {
            text = "◀"
            setOnClickListener { currentWebView()?.goBack() }
        }
        val fwdBtn = Button(ctx).apply {
            text = "▶"
            setOnClickListener { currentWebView()?.goForward() }
        }
        val reloadBtn = Button(ctx).apply {
            text = "⟳"
            setOnClickListener { currentWebView()?.reload() }
        }
        val zoomInBtn = Button(ctx).apply {
            text = "🔍+"
            setOnClickListener { currentWebView()?.zoomIn() }
        }
        val zoomOutBtn = Button(ctx).apply {
            text = "🔍-"
            setOnClickListener { currentWebView()?.zoomOut() }
        }
        // v52: 🔄 Refresh — automation reset (browser state saaf, wahi se
        // nayi shuruaat). AI memory + Card safe rahenge.
        val refreshBtn = Button(ctx).apply {
            text = "🔄"
            setOnClickListener { refreshAutomation() }
        }
        // v52: 📸 Screenshot — KAAM KARTA HUA button (user report 2026-09-27:
        // "screenshot lene wala button kaam nahi karta").
        // WebView methods SIRF UI thread par (Thread-9 crash fix).
        val shotBtn = Button(ctx).apply {
            text = "📸"
            setOnClickListener { takeScreenshot() }
        }
        ctrlRow.addView(backBtn, LayoutParams(0, -2, 1f))
        ctrlRow.addView(fwdBtn, LayoutParams(0, -2, 1f))
        ctrlRow.addView(reloadBtn, LayoutParams(0, -2, 1f))
        ctrlRow.addView(zoomInBtn, LayoutParams(0, -2, 1f))
        ctrlRow.addView(zoomOutBtn, LayoutParams(0, -2, 1f))
        ctrlRow.addView(shotBtn, LayoutParams(0, -2, 1f))
        ctrlRow.addView(refreshBtn, LayoutParams(0, -2, 1f))
        addView(ctrlRow)

        // Saved session restore karo (wahi se shuru).
        restoreSavedSession()
    }

    private fun createWebView(ctx: Context): WebView {
        return WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // v52: DESKTOP MODE ON dono browser ka (user order 2026-09-27).
            // Desktop sites poori functionality dete hain.
            settings.userAgentString = settings.userAgentString
                ?.replace("; wv", "")
                ?.replace(" Mobile ", " ") + " "
            // Proper desktop UA:
            settings.userAgentString =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // v52: URL bar me poora lamba URL nahi — single line,
                    // end me cut (jaise Chrome). Poora URL long-press par.
                    urlBar?.setText(url ?: "")
                    // Har navigation par state save (persistence).
                    try {
                        BrowserPersistence.saveState(
                            ctx, view, "", 0
                        )
                    } catch (_: Exception) { }
                }
            }
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
    }

    private fun currentWebView(): WebView? =
        if (showingAi) aiWebView else userWebView

    // ============ v56: SHARED ENGINE BROWSERS ============

    /**
     * Dono browser ke tab 0 = LiveWebViewHost ke shared WebViews.
     * Yehi WebViews FormEngine (kaam) aur AiHelpSystem (help) chalate hain —
     * Live tab me attach karne se agent ka kaam LIVE dikhta hai.
     * WebView kahin aur attach ho (OperatorView) to wahan se lekar yahan
     * lagao (ek view, ek parent). Kabhi destroy NAHI.
     */
    private fun attachSharedTabs(ctx: Context) {
        // --- User Browser tab 0: shared WORK WebView ---
        try {
            val workWv = com.formmitra.app.engine.LiveWebViewHost
                .ensureForViewing(ctx)
            attachToContainer(workWv)
            if (userTabs.none { it.isShared }) {
                userTabs.add(
                    0,
                    BrowserTab(workWv, "🤖 Agent ka browser", isShared = true)
                )
                userTabIndex = 0
            }
        } catch (t: Throwable) {
            android.util.Log.e("FmLiveTab", "shared work attach failed", t)
            // Fallback (kabhi hona nahi chahiye): local tab
            if (userTabs.isEmpty()) addTab(ctx, false)
        }
        // --- AI Helper tab 0: shared HELP WebView ---
        try {
            val helpWv = com.formmitra.app.engine.LiveWebViewHost
                .ensureHelpForViewing(ctx)
            attachToContainer(helpWv)
            helpWv.visibility = View.GONE
            if (aiTabs.none { it.isShared }) {
                aiTabs.add(
                    0,
                    BrowserTab(helpWv, "🤖 AI Helper", isShared = true)
                )
                aiTabIndex = 0
            }
        } catch (t: Throwable) {
            android.util.Log.e("FmLiveTab", "shared help attach failed", t)
            if (aiTabs.isEmpty()) addTab(ctx, true)
        }
        updateTabVisibility()
    }

    /** WebView ko webContainer me lagao (purane parent se hata kar). */
    private fun attachToContainer(wv: WebView) {
        try {
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        } catch (_: Exception) { }
        try {
            wv.layoutParams = FrameLayout.LayoutParams(-1, -1)
            // Pehle se container me ho to dobara mat jodo.
            if (wv.parent !== webContainer) {
                webContainer?.addView(wv)
            }
        } catch (_: Exception) { }
    }

    /**
     * v56: Live tab jab bhi visible ho, shared WebViews wapas yahan lao.
     * (OperatorView ne unhe apne paas lagaya ho to.)
     * MainActivity visibility toggle karta hai — yahan pakad lo.
     */
    override fun onVisibilityChanged(
        changedView: android.view.View,
        visibility: Int
    ) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            try {
                reattachShared()
            } catch (_: Exception) { }
        }
    }

    private fun reattachShared() {
        try {
            userTabs.firstOrNull { it.isShared }?.webView?.let {
                attachToContainer(it)
            }
            aiTabs.firstOrNull { it.isShared }?.webView?.let {
                attachToContainer(it)
            }
            updateTabVisibility()
            // URL bar sync (shared WebViews ka client engine ka hai —
            // isliye yahan manual sync).
            urlBar?.setText(currentWebView()?.url ?: "")
            startUrlSync()
        } catch (_: Exception) { }
    }

    // --- v56: URL bar live sync (shared WebViews ke liye) ---
    private val urlSyncHandler = android.os.Handler(
        android.os.Looper.getMainLooper()
    )
    private var lastSyncedUrl: String? = null
    private val urlSyncRunnable = object : Runnable {
        override fun run() {
            try {
                if (visibility == View.VISIBLE) {
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
            urlSyncHandler.post(urlSyncRunnable)
        } catch (_: Exception) { }
    }

    // ============ v53: TAB MANAGEMENT ============

    /**
     * Naya tab kholo (is browser me).
     * @return naye tab ka index
     */
    fun addTab(ctx: Context = context, ai: Boolean = showingAi): Int {
        val wv = createWebView(ctx)
        wv.visibility = View.GONE
        webContainer?.addView(wv)
        val tabs = if (ai) aiTabs else userTabs
        tabs.add(BrowserTab(wv))
        val idx = tabs.size - 1
        if (ai) aiTabIndex = idx else userTabIndex = idx
        updateTabVisibility()
        refreshTabStrip()
        return idx
    }

    /**
     * Tab band karo (pehla tab band nahi hota — browser hamesha zinda).
     * v56: SHARED tab (index 0) KABHI band nahi hota — wo engine ka browser
     * hai. Sirf user ke extra tabs band hote hain (aur sirf wahi destroy).
     */
    fun closeTab(ai: Boolean = showingAi, index: Int = currentTabIndex()): Boolean {
        val tabs = if (ai) aiTabs else userTabs
        if (tabs.size <= 1) return false  // aakhri tab band nahi
        if (index < 0 || index >= tabs.size) return false
        val tab = tabs[index]
        // v56: shared tab band nahi ho sakta (na destroy).
        if (tab.isShared || index == 0) return false
        tabs.removeAt(index)
        webContainer?.removeView(tab.webView)
        try { tab.webView.destroy() } catch (_: Exception) { }
        if (ai) aiTabIndex = aiTabIndex.coerceIn(0, aiTabs.size - 1)
        else userTabIndex = userTabIndex.coerceIn(0, userTabs.size - 1)
        updateTabVisibility()
        refreshTabStrip()
        return true
    }

    private fun currentTabIndex(): Int =
        if (showingAi) aiTabIndex else userTabIndex

    private fun switchTab(ai: Boolean, index: Int) {
        val tabs = if (ai) aiTabs else userTabs
        if (index < 0 || index >= tabs.size) return
        if (ai) aiTabIndex = index else userTabIndex = index
        updateTabVisibility()
        refreshTabStrip()
        // URL bar sync
        urlBar?.setText(currentWebView()?.url ?: "")
    }

    /**
     * Sirf active browser ke active tab ka WebView visible.
     * Baaki sab hidden (lekin ZINDA — state safe).
     */
    private fun updateTabVisibility() {
        userTabs.forEachIndexed { i, tab ->
            tab.webView.visibility =
                if (!showingAi && i == userTabIndex) View.VISIBLE else View.GONE
        }
        aiTabs.forEachIndexed { i, tab ->
            tab.webView.visibility =
                if (showingAi && i == aiTabIndex) View.VISIBLE else View.GONE
        }
    }

    /**
     * Tab strip redraw karo (tab buttons + "+").
     */
    private fun refreshTabStrip() {
        val row = tabStripRow ?: return
        row.removeAllViews()
        val tabs = if (showingAi) aiTabs else userTabs
        val activeIdx = if (showingAi) aiTabIndex else userTabIndex
        tabs.forEachIndexed { i, tab ->
            val btn = Button(context).apply {
                // v56: shared tabs (User Browser / AI Helper) ko naam se
                // dikhao — user ko samajh aaye kaunsa browser hai.
                val label = if (tab.isShared) tab.title else "Tab ${i + 1}"
                text = "${if (i == activeIdx) "● " else ""}$label"
                textSize = 11f
                setOnClickListener { switchTab(showingAi, i) }
                // Long-press = band karo
                setOnLongClickListener {
                    if (closeTab(showingAi, i)) {
                        android.widget.Toast.makeText(
                            context, "Tab band ho gaya", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
            }
            row.addView(btn)
        }
        // "+" = naya tab
        val plusBtn = Button(context).apply {
            text = "+"
            textSize = 14f
            setOnClickListener {
                addTab(ai = showingAi)
                android.widget.Toast.makeText(
                    context, "Naya tab khul gaya", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        row.addView(plusBtn)
        updateTabVisibility()
    }

    private fun showBrowser(showAi: Boolean) {
        showingAi = showAi
        // v56: shared WebViews wapas yahan lao (OperatorView se).
        try { reattachShared() } catch (_: Exception) { }
        updateTabVisibility()
        refreshTabStrip()
        statusText?.text = if (showAi) "AI Helper browser" else "User browser"
        // v52: AI Helper tab pehli baar khule to AI Mode load karo —
        // agent/user yahin AI se baat karega.
        // v56: SIRF jab bilkul khaali ho. Agent help me use kar raha ho
        // to uska page OVERWRITE MAT KARO.
        if (showAi) {
            try {
                val cur = aiWebView?.url ?: ""
                val helpActive = try {
                    com.formmitra.app.engine.AiHelpSystem.isHelping()
                } catch (_: Exception) { false }
                if (cur.isEmpty() && !helpActive) {
                    aiWebView?.loadUrl("https://www.google.com/search?udm=50&q=")
                }
            } catch (_: Exception) { }
        }
        urlBar?.setText(currentWebView()?.url ?: "")
    }

    private fun navigateCurrent() {
        val input = urlBar?.text?.toString()?.trim() ?: return
        if (input.isEmpty()) return
        val url = if (input.startsWith("http")) input
        else "https://www.google.com/search?q=${java.net.URLEncoder.encode(input, "UTF-8")}"
        currentWebView()?.loadUrl(url)
    }

    private fun restoreSavedSession() {
        try {
            // v56: shared work WebView me restore SIRF tab jab khaali ho.
            // Automation chal rahi ho ya page khula ho to CHHUO MAT —
            // nahi to agent ka kaam bigad jayega.
            val wv = userTabs.firstOrNull { it.isShared }?.webView
                ?: userWebView ?: return
            val curUrl = try { wv.url ?: "" } catch (_: Exception) { "" }
            val automationOn = try {
                com.formmitra.app.engine.LiveWebViewHost.isAutomationActive()
            } catch (_: Exception) { false }
            if (automationOn) return
            if (curUrl.isNotEmpty() && !curUrl.startsWith("about:")) return
            val saved = BrowserPersistence.loadState(context) ?: return
            // Pehle memory restore try karo, nahi to URL load.
            val restored = BrowserPersistence.restoreInto(wv)
            if (!restored) {
                wv.loadUrl(saved.url)
            }
            statusText?.text = "Pichla session: ${saved.title}"
        } catch (_: Exception) { }
    }

    /**
     * v52: 📸 Screenshot — UI thread par WebView capture.
     * (Thread-9 crash ka root fix: WebView methods hamesha UI thread.)
     */
    private fun takeScreenshot() {
        val wv = currentWebView() ?: return
        // onClick already UI thread par hai — seedha capture karo.
        try {
            val bmp = android.graphics.Bitmap.createBitmap(
                wv.width.coerceAtLeast(1),
                wv.height.coerceAtLeast(1),
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            wv.draw(canvas)
            // File me save karo
            val file = java.io.File(
                context.cacheDir,
                "live_screenshot_${System.currentTimeMillis()}.png"
            )
            java.io.FileOutputStream(file).use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
            }
            if (!bmp.isRecycled) bmp.recycle()
            android.widget.Toast.makeText(
                context, "📸 Screenshot save ho gaya", android.widget.Toast.LENGTH_SHORT
            ).show()
            statusText?.text = "📸 Screenshot: ${file.name}"
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context, "Screenshot nahi ho paya", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * v52: 🔄 Refresh — automation reset.
     * Browser state saaf, automation nayi shuruaat.
     * 🛡️ AI memory (trained) + Card data KABHI delete nahi hote.
     */
    private fun refreshAutomation() {
        val act = context as? android.app.Activity ?: return
        com.formmitra.app.engine.DeleteGuard.confirmDelete(
            act,
            "Automation refresh karo?",
            "Browser ka current state saaf hoga aur automation naye " +
            "sire se shuru hoga."
        ) {
            try {
                // 1. Chal rahi automation band karo
                com.formmitra.app.engine.AutomationStopper.stopAll(context)
                // 2. Browser state saaf karo
                com.formmitra.app.engine.BrowserPersistence.clearState(context)
                // 3. Gate kholo
                com.formmitra.app.engine.SingleAutomationGate.unlock(context)
                // 4. Browser reload karo
                userWebView?.loadUrl("about:blank")
                statusText?.text = "🔄 Refresh ho gaya — naya kaam shuru kar sakte ho"
            } catch (_: Exception) {
                statusText?.text = "⚠️ Refresh me problem aayi"
            }
        }
    }

    /**
     * User browser ka WebView do (automation ke liye).
     * Kabhi null nahi — hamesha zinda rehta hai.
     * (v53: computed property userWebView se milta hai — current tab ka)
     */

    /**
     * AI browser ka WebView do.
     * (v53: computed property aiWebView se milta hai — current tab ka)
     */

    fun onDestroy() {
        try {
            urlSyncHandler.removeCallbacks(urlSyncRunnable)
        } catch (_: Exception) { }
        try {
            // v56 ROOT FIX: SHARED WebViews KABHI destroy nahi hote —
            // wo LiveWebViewHost ke paas persistent rehte hain (engine ka
            // browser band nahi hona chahiye). Sirf container se detach karo.
            // User ke extra tabs destroy hote hain.
            (userTabs + aiTabs).forEach { tab ->
                try {
                    (tab.webView.parent as? android.view.ViewGroup)
                        ?.removeView(tab.webView)
                } catch (_: Exception) { }
                if (!tab.isShared) {
                    try { tab.webView.destroy() } catch (_: Exception) { }
                }
            }
            userTabs.clear()
            aiTabs.clear()
        } catch (_: Exception) { }
    }
}

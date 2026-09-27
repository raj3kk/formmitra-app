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
     * User order: "dusra work ya dusra link visit karna ho to dusra tab
     * khol ke search kare, visit kare — jaisa hona chahiye situation ke
     * according."
     */
    private data class BrowserTab(
        val webView: WebView,
        var title: String = "Naya tab"
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

        // Pehla tab dono browser me
        addTab(ctx, false)  // user browser
        addTab(ctx, true)   // AI browser
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
     */
    fun closeTab(ai: Boolean = showingAi, index: Int = currentTabIndex()): Boolean {
        val tabs = if (ai) aiTabs else userTabs
        if (tabs.size <= 1) return false  // aakhri tab band nahi
        if (index < 0 || index >= tabs.size) return false
        val tab = tabs.removeAt(index)
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
                text = "${if (i == activeIdx) "● " else ""}Tab ${i + 1}"
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
        updateTabVisibility()
        refreshTabStrip()
        statusText?.text = if (showAi) "AI Helper browser" else "User browser"
        // v52: AI Helper tab pehli baar khule to AI Mode load karo —
        // agent/user yahin AI se baat karega.
        if (showAi && aiWebView?.url.isNullOrEmpty()) {
            aiWebView?.loadUrl("https://www.google.com/search?udm=50&q=")
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
            val saved = BrowserPersistence.loadState(context) ?: return
            // Pehle memory restore try karo, nahi to URL load.
            val restored = userWebView?.let {
                BrowserPersistence.restoreInto(it)
            } ?: false
            if (!restored) {
                userWebView?.loadUrl(saved.url)
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
            // v53: saare tabs destroy karo
            (userTabs + aiTabs).forEach {
                try { it.webView.destroy() } catch (_: Exception) { }
            }
            userTabs.clear()
            aiTabs.clear()
        } catch (_: Exception) { }
    }
}

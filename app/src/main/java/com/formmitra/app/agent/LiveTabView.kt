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

    private var userWebView: WebView? = null
    private var aiWebView: WebView? = null
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

        // --- WebViews (dono, ek visible) ---
        val webContainer = FrameLayout(ctx).apply {
            layoutParams = LayoutParams(-1, 0, 1f)
        }
        userWebView = createWebView(ctx).also { webContainer.addView(it) }
        aiWebView = createWebView(ctx).also {
            it.visibility = View.GONE
            webContainer.addView(it)
        }
        addView(webContainer)

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

    private fun showBrowser(showAi: Boolean) {
        showingAi = showAi
        userWebView?.visibility = if (showAi) View.GONE else View.VISIBLE
        aiWebView?.visibility = if (showAi) View.VISIBLE else View.GONE
        statusText?.text = if (showAi) "AI Helper browser" else "User browser"
        // v52: AI Helper tab pehli baar khule to AI Mode load karo —
        // agent/user yahin AI se baat karega.
        if (showAi && aiWebView?.url.isNullOrEmpty()) {
            aiWebView?.loadUrl("https://www.google.com/search?udm=50&q=")
        }
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
     */
    fun getUserWebView(): WebView? = userWebView

    /**
     * AI browser ka WebView do.
     */
    fun getAiWebView(): WebView? = aiWebView

    fun onDestroy() {
        try {
            userWebView?.destroy()
            aiWebView?.destroy()
        } catch (_: Exception) { }
    }
}

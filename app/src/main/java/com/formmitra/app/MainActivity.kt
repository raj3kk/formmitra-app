package com.formmitra.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.formmitra.app.agent.AgentChatView
import com.formmitra.app.agent.HomeView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * FormMitra v16 — tabs: Home (Mitra chat embedded), History, Wallet, Profile.
 * (+ Admin tab sirf owner email par.)
 *
 * Home = native HomeView (services strip + live-browser button + agent chat).
 * Wallet/Profile/Admin = website WebView me. Purane Agent/Browser/Tracking/
 * Jobs tabs hata diye — services Home strip se khulte hain.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var homeView: HomeView
    private lateinit var agentChatView: AgentChatView
    private var homeVisible = false
    private lateinit var historyView: com.formmitra.app.agent.HistoryView
    private var historyVisible = false

    // Live browser mirror (ab tab nahi — Home ke "🔴 Live" button se)
    private lateinit var browserMirrorView: LinearLayout
    private lateinit var mirrorImg: ImageView
    private lateinit var mirrorEmpty: TextView
    private var mirrorVisible = false
    private val mirrorHandler = Handler(Looper.getMainLooper())
    private val mirrorRunnable = object : Runnable {
        override fun run() {
            if (!mirrorVisible) return
            refreshMirror()
            mirrorHandler.postDelayed(this, 3000)
        }
    }

    private lateinit var navInner: LinearLayout
    private var navButtons: List<Button> = emptyList()
    private var activePath = "/"
    private var isOwner = false

    private val ownerEmail = "priyadarshirajindia@gmail.com"

    private val baseTabs = listOf(
        "Home" to "/",
        "History" to "/history",
        "Wallet" to "/wallet",
        "Profile" to "/profile"
    )

    private fun currentTabs() =
        if (isOwner) baseTabs + ("Admin" to "/admin") else baseTabs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        // v14 audit: third-party cookies ON (form automation ke embedded
        // widgets — payment/SSO iframes — inke bina toot jate hain; ye
        // single-user ka apna automation WebView hai).
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = false

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Owner check: /profile page ke text me owner email dikhe
                // to Admin tab dikhao (fail-closed: na dikhe to tab nahi).
                if (!isOwner && url.trimEnd('/') == baseUrl() + "/profile") {
                    checkOwner(view)
                }
            }
        }
        root.addView(webView)

        // Home: native — services strip + live button + Mitra chat
        agentChatView = AgentChatView(
            this,
            { url -> openLinkInWebView(url) },
            { selectTab("/profile") }
        )
        homeView = HomeView(
            this,
            agentChatView,
            onOpenService = { path -> selectTab(path) },
            onShowMirror = { showMirror() }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            visibility = View.GONE
        }
        root.addView(homeView)

        // History tab — native runs history
        historyView = com.formmitra.app.agent.HistoryView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            visibility = View.GONE
        }
        root.addView(historyView)

        // Live browser mirror — engine ka agent_mirror.png har 3s refresh
        browserMirrorView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.parseColor("#111111"))
            visibility = View.GONE
        }
        mirrorEmpty = TextView(this).apply {
            text = "Koi live browser nahi chal raha"
            textSize = 16f
            setTextColor(Color.GRAY)
        }
        browserMirrorView.addView(mirrorEmpty)
        mirrorImg = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            visibility = View.GONE
        }
        browserMirrorView.addView(mirrorImg)
        root.addView(browserMirrorView)

        // Bottom nav — 4 tabs (+ owner par Admin)
        navInner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val navScroll = android.widget.HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isHorizontalScrollBarEnabled = false
            addView(navInner)
        }
        root.addView(navScroll)
        setContentView(root)
        buildNav()

        // Deep link (notification tap) ya fresh launch
        val deepUrl = intent.getStringExtra("deep_url")
        val openTab = intent.getStringExtra("open_tab")?.let { mapLegacyTab(it) }
        if (!deepUrl.isNullOrEmpty()) {
            loadDeepUrl(deepUrl)
        } else if (!openTab.isNullOrEmpty()) {
            selectTab(openTab)
        } else {
            selectTab("/")
        }

        // FmApp.onCreate me WorkManager pehle hi init ho chuka hai (v15 fix).
        // Belt-and-braces: scheduler kabhi launch crash na banaye.
        try {
            Scheduler.scheduleDigest(this)
            Scheduler.scheduleFormTasks(this)
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "Scheduler failed (non-fatal)", t)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }

        checkForUpdate()
        maybeShowResumeDialog()
    }

    /** Purane tab paths (notification/deep-link) → naye tabs. */
    private fun mapLegacyTab(path: String): String = when (path) {
        "/agent", "/browser" -> "/"
        else -> path
    }

    private fun buildNav() {
        navInner.removeAllViews()
        val tabs = currentTabs()
        navButtons = tabs.map { (label, path) ->
            Button(this).apply {
                text = label
                textSize = 14f
                minWidth = (96 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    val m = (6 * resources.displayMetrics.density).toInt()
                    setMargins(m, 0, m, 0)
                }
                setOnClickListener { selectTab(path) }
            }
        }
        navButtons.forEach { navInner.addView(it) }
        updateNavHighlight(activePath)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val deepUrl = intent.getStringExtra("deep_url")
        if (!deepUrl.isNullOrEmpty()) loadDeepUrl(deepUrl)
        val openTab = intent.getStringExtra("open_tab")?.let { mapLegacyTab(it) }
        if (!openTab.isNullOrEmpty()) selectTab(openTab)
    }

    override fun onResume() {
        super.onResume()
        // App-wide prompt poller: user kisi bhi tab me ho, agent ka sawal
        // (OTP/input/choice/payment) popup me aayega.
        promptHandler.post(promptPollRunnable)
        if (homeVisible) {
            try { homeView.refreshLiveButton() } catch (_: Exception) { }
        }
    }

    override fun onPause() {
        promptHandler.removeCallbacks(promptPollRunnable)
        super.onPause()
    }

    /** Kisi bhi tab se pending agent prompt ko popup me dikhao. */
    private val promptHandler = Handler(Looper.getMainLooper())
    private val promptPollRunnable = object : Runnable {
        override fun run() {
            try {
                val req = com.formmitra.app.engine.UserPrompt.pendingRequest()
                if (req != null &&
                    !com.formmitra.app.agent.PromptDialog.isShowing(req.runId)
                ) {
                    com.formmitra.app.agent.PromptDialog.show(
                        this@MainActivity, req
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "prompt poll failed (non-fatal)", t)
            }
            promptHandler.postDelayed(this, 3000)
        }
    }

    private fun baseUrl(): String = BuildConfig.SITE_URL.trimEnd('/')

    private fun selectTab(path: String) {
        val wasHome = homeVisible
        homeVisible = false
        historyVisible = false
        mirrorVisible = false
        mirrorHandler.removeCallbacks(mirrorRunnable)
        homeView.visibility = View.GONE
        historyView.visibility = View.GONE
        browserMirrorView.visibility = View.GONE
        webView.visibility = View.GONE
        if (wasHome && path != "/") agentChatView.onTabHidden()
        val tabPaths = currentTabs().map { it.second }.toSet()
        when {
            path == "/" -> {
                homeView.visibility = View.VISIBLE
                homeVisible = true
                agentChatView.onTabShown()
                try { homeView.refreshLiveButton() } catch (_: Exception) { }
            }
            path == "/history" -> {
                historyView.visibility = View.VISIBLE
                historyVisible = true
                historyView.onTabShown()
            }
            tabPaths.contains(path) -> {
                // Wallet / Profile / Admin — website WebView me
                webView.visibility = View.VISIBLE
                webView.loadUrl(baseUrl() + path)
            }
            else -> {
                // Service paths (/tracking, /jobs, /scholarships, /resume)
                // WebView me, nav me Home highlight
                webView.visibility = View.VISIBLE
                val full = baseUrl() + path
                if (webView.url != full) webView.loadUrl(full)
            }
        }
        activePath = if (tabPaths.contains(path)) path else "/"
        updateNavHighlight(activePath)
    }

    /** Home ke "🔴 Live" button se — agent ka live browser dikhao. */
    private fun showMirror() {
        homeVisible = false
        historyVisible = false
        mirrorHandler.removeCallbacks(mirrorRunnable)
        homeView.visibility = View.GONE
        historyView.visibility = View.GONE
        webView.visibility = View.GONE
        agentChatView.onTabHidden()
        browserMirrorView.visibility = View.VISIBLE
        mirrorVisible = true
        mirrorHandler.post(mirrorRunnable)
        activePath = "/"
        updateNavHighlight("/")
    }

    /** Plan card ke official link ko main WebView me kholo. */
    private fun openLinkInWebView(url: String) {
        if (homeVisible) agentChatView.onTabHidden()
        homeVisible = false
        historyVisible = false
        mirrorVisible = false
        mirrorHandler.removeCallbacks(mirrorRunnable)
        homeView.visibility = View.GONE
        historyView.visibility = View.GONE
        browserMirrorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(url)
        activePath = "/"
        updateNavHighlight("/")
    }

    private fun loadDeepUrl(fullUrl: String) {
        val path = fullUrl.removePrefix(baseUrl())
        val tabPaths = setOf("/", "/history", "/wallet", "/profile", "/admin")
        if (path in tabPaths) {
            selectTab(path)
        } else {
            if (homeVisible) agentChatView.onTabHidden()
            homeVisible = false
            historyVisible = false
            mirrorVisible = false
            mirrorHandler.removeCallbacks(mirrorRunnable)
            homeView.visibility = View.GONE
            historyView.visibility = View.GONE
            browserMirrorView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl(fullUrl)
            activePath = "/"
            updateNavHighlight("/")
        }
    }

    /** /profile page ke text me owner email → Admin tab (fail-closed). */
    private fun checkOwner(view: WebView) {
        try {
            view.evaluateJavascript(
                "(function(){return document.body?document.body.innerText.slice(0,6000):'';})()"
            ) { res ->
                if (!isOwner && res != null && res.contains(ownerEmail)) {
                    isOwner = true
                    runOnUiThread { buildNav() }
                }
            }
        } catch (_: Exception) { }
    }

    private fun updateNavHighlight(active: String) {
        val tabs = currentTabs()
        tabs.forEachIndexed { idx, (_, path) ->
            if (idx >= navButtons.size) return@forEachIndexed
            val on = path == active
            navButtons[idx].setTextColor(
                if (on) Color.parseColor("#0E7C5B") else Color.DKGRAY
            )
            navButtons[idx].setTypeface(
                null, if (on) Typeface.BOLD else Typeface.NORMAL
            )
        }
    }

    @Deprecated("Use OnBackPressedDispatcher on newer APIs")
    override fun onBackPressed() {
        if (mirrorVisible) {
            selectTab("/")
        } else if (homeVisible || historyVisible) {
            super.onBackPressed()
        } else if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    // ---------- browser mirror ----------

    /** cacheDir/agent_mirror.png (engine likhta hai) → ImageView. */
    private fun refreshMirror() {
        Thread {
            val f = File(cacheDir, "agent_mirror.png")
            val bmp = if (f.exists()) {
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(f.absolutePath, opts)
                } catch (_: Exception) {
                    null
                }
            } else null
            runOnUiThread {
                if (!mirrorVisible) return@runOnUiThread
                val old = (mirrorImg.drawable as? BitmapDrawable)?.bitmap
                if (bmp != null) {
                    mirrorEmpty.visibility = View.GONE
                    mirrorImg.visibility = View.VISIBLE
                    mirrorImg.setImageBitmap(bmp)
                } else {
                    mirrorImg.visibility = View.GONE
                    mirrorEmpty.visibility = View.VISIBLE
                }
                if (old != null && old != bmp && !old.isRecycled) old.recycle()
            }
        }.start()
    }

    // ---------- agent resume ----------

    /** Engine ka AgentResume object — direct call (class hamesha present hai). */
    private fun checkAgentResume(): Triple<String, String, String?>? {
        return try {
            com.formmitra.app.engine.AgentResume.checkPending(this)
        } catch (_: Exception) {
            null
        }
    }

    private fun clearAgentResume() {
        try {
            com.formmitra.app.engine.AgentResume.clear(this)
        } catch (_: Exception) { }
    }

    private fun maybeShowResumeDialog() {
        val pending = checkAgentResume() ?: return
        val (goal, url) = pending
        AlertDialog.Builder(this)
            .setTitle("Adhura kaam")
            .setMessage("Pichhla kaam adhura reh gaya tha:\n$goal\nDobara chalau?")
            .setPositiveButton("Chalao") { _, _ ->
                clearAgentResume()
                Thread {
                    val (_, taskId) =
                        com.formmitra.app.agent.AgentApi.createTask(this, goal, url)
                    if (!taskId.isNullOrEmpty()) {
                        com.formmitra.app.agent.AgentApi.runNow(this, taskId)
                    }
                    runOnUiThread { selectTab("/") }
                }.start()
            }
            .setNegativeButton("Chhodo") { _, _ -> clearAgentResume() }
            .setCancelable(false)
            .show()
    }

    // ---------- activity results → AgentChatView ----------

    @Deprecated("Document picker AgentChatView ke liye")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            com.formmitra.app.agent.PromptDialog.onDocPickResult(requestCode, data)
        } catch (_: Exception) { }
        if (::agentChatView.isInitialized) {
            agentChatView.handleActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AgentChatView.REQ_VOICE_PERM &&
            ::agentChatView.isInitialized
        ) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            agentChatView.onVoicePermissionResult(granted)
        }
    }

    // Fire-and-forget update check — 404/offline: chup-chaap ignore.
    private fun checkForUpdate() {
        Thread {
            try {
                val url = URL(baseUrl() + "/api/app/version")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 20000
                    requestMethod = "GET"
                }
                if (conn.responseCode != 200) return@Thread
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(body)
                val remoteCode = json.optInt("version_code", 0)
                val apkUrl = json.optString("apk_url", "")
                if (remoteCode > BuildConfig.VERSION_CODE && apkUrl.isNotEmpty()) {
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Naya version aaya hai")
                            .setMessage(
                                "FormMitra ka naya version uplabdh hai. " +
                                    "Download karke install karein?"
                            )
                            .setPositiveButton("Download") { _, _ ->
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                                )
                            }
                            .setNegativeButton("Baad mein", null)
                            .show()
                    }
                }
            } catch (_: Exception) {
                // silent — user may be offline
            }
        }.start()
    }
}

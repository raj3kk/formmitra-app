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
import android.widget.Toast
import com.formmitra.app.agent.AgentChatView
import com.formmitra.app.agent.HomeView
import com.formmitra.app.agent.ProfileView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * FormMitra v19 — tabs: Home (Mitra chat embedded), History, Wallet, Profile (native).
 * (+ Admin tab sirf owner email par.)
 *
 * Home = native HomeView (services strip + category cards + live button + agent chat).
 * History = native HistoryView. Profile = native ProfileView (details/vault/admin/logout).
 * Wallet/Admin = website WebView me (unhi par ?app=1 lagta hai).
 * Purane Agent/Browser/Tracking/Jobs tabs hata diye — services Home strip se khulte hain.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var homeView: HomeView
    private lateinit var agentChatView: AgentChatView
    private var homeVisible = false
    private lateinit var historyView: com.formmitra.app.agent.HistoryView
    private var historyVisible = false
    private lateinit var profileView: ProfileView
    private var profileVisible = false

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
            ): Boolean {
                val u = try { request.url?.toString() }
                catch (_: Exception) { null } ?: return false
                // v19 BUG 2: same-origin in-page navigation (website ke Link
                // clicks) par app=1 jod do taaki param khoye nahi. Bahar ke
                // official links ko chhedo mat — WebView khud handle kare.
                if (isSameOrigin(u)) {
                    val withParam = appUrl(u)
                    if (withParam != u) {
                        view.loadUrl(withParam)
                        return true
                    }
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Owner check: /profile page ke text me owner email dikhe
                // to Admin tab dikhao (fail-closed: na dikhe to tab nahi).
                // Query strip karke compare (app=1 lagne ke baad bhi match).
                if (!isOwner && stripQuery(url) == baseUrl() + "/profile") {
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
            onOpenTab = { target ->
                if (target == "vault") openProfileVault()
                else selectTab(target)
            },
            // v20 Task 5: work-category card → pehle Home dikhao, phir chat
            // usi category context me kholo (body me `category` jayega)
            onStartCategory = { category, label, prefill ->
                if (!homeVisible) selectTab("/")
                agentChatView.startCategoryChat(category, label, prefill)
            },
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

        // Profile tab — native (v19, BUG 4): details + edit + vault + admin + logout
        profileView = ProfileView(
            this,
            ownerEmail,
            onOwnerConfirmed = { onOwnerConfirmed() },
            onOpenAdmin = { if (isOwner) selectTab("/admin") },
            onLogout = { doLogout() }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            visibility = View.GONE
        }
        root.addView(profileView)

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
            // v20 Task 2: halki top divider + shadow — nav alag dikhe
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            try { elevation = 6f } catch (_: Exception) { }
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
                    // v20-C: voice help mode — agent atka, user ka action
                    // chahiye → TTS se sunao (mute ho to sirf popup).
                    try {
                        com.formmitra.app.agent.VoiceHelp.announcePrompt(
                            this@MainActivity, req
                        )
                    } catch (_: Exception) { }
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
        profileVisible = false
        mirrorVisible = false
        mirrorHandler.removeCallbacks(mirrorRunnable)
        homeView.visibility = View.GONE
        historyView.visibility = View.GONE
        profileView.visibility = View.GONE
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
            path == "/profile" -> {
                // v19 BUG 4: native ProfileView (WebView nahi)
                profileView.visibility = View.VISIBLE
                profileVisible = true
                profileView.onTabShown()
            }
            tabPaths.contains(path) -> {
                // Wallet / Admin — website WebView me (?app=1 ke saath)
                webView.visibility = View.VISIBLE
                webView.loadUrl(appUrl(baseUrl() + path))
            }
            else -> {
                // Service paths (/tracking, /jobs, /scholarships, /resume)
                // WebView me, nav me Home highlight
                webView.visibility = View.VISIBLE
                val full = appUrl(baseUrl() + path)
                if (stripQuery(webView.url ?: "") != stripQuery(full)) {
                    webView.loadUrl(full)
                }
            }
        }
        activePath = if (tabPaths.contains(path)) path else "/"
        updateNavHighlight(activePath)
    }

    /** Home ke "📁 Document Vault" card se — Profile kholo + vault par scroll. */
    private fun openProfileVault() {
        selectTab("/profile")
        try { profileView.jumpToVault() } catch (_: Exception) { }
    }

    /** ProfileView ka owner check (ya purana WebView check) confirm hua. */
    private fun onOwnerConfirmed() {
        if (!isOwner) {
            isOwner = true
            runOnUiThread { buildNav() }
        }
        if (::profileView.isInitialized) profileView.setOwner(true)
    }

    /** Logout: website session clear, native state reset, Home par wapas. */
    private fun doLogout() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
        } catch (_: Exception) { }
        try {
            if (::profileView.isInitialized) profileView.onLoggedOut()
        } catch (_: Exception) { }
        selectTab("/")
        Toast.makeText(this, "Logout ho gaya", Toast.LENGTH_SHORT).show()
    }

    // ---------- v19 BUG 2: website URLs par ?app=1 ----------

    /** Sirf apna domain (baseUrl ka host) — bahar ke official links kabhi nahi. */
    private fun isSameOrigin(url: String): Boolean {
        return try {
            val u = Uri.parse(url)
            val b = Uri.parse(baseUrl())
            val uh = u.host ?: return false
            val bh = b.host ?: return false
            uh.equals(bh, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Website URL par ?app=1 (ya &app=1) jodo — sirf same-origin par.
     * Pehle se app=1 ho to wapas wahi URL (double param nahi).
     */
    private fun appUrl(url: String): String {
        if (!isSameOrigin(url)) return url
        val frag = if (url.contains("#")) "#" + url.substringAfter("#") else ""
        val noFrag = url.substringBefore("#")
        if (noFrag.contains("app=1")) return url
        val sep = if (noFrag.contains("?")) "&" else "?"
        return "$noFrag${sep}app=1$frag"
    }

    /** Query string hatakar compare — app=1 lagne ke baad bhi match kare. */
    private fun stripQuery(url: String): String = url.substringBefore("?").trimEnd('/')

    /** Home ke "🔴 Live" button se — agent ka live browser dikhao. */
    private fun showMirror() {
        homeVisible = false
        historyVisible = false
        profileVisible = false
        mirrorHandler.removeCallbacks(mirrorRunnable)
        homeView.visibility = View.GONE
        historyView.visibility = View.GONE
        profileView.visibility = View.GONE
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
        profileVisible = false
        mirrorVisible = false
        mirrorHandler.removeCallbacks(mirrorRunnable)
        homeView.visibility = View.GONE
        historyView.visibility = View.GONE
        profileView.visibility = View.GONE
        browserMirrorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        // v19 BUG 2: apne domain par ?app=1 — bahar ke official links jaisi hain waisi
        webView.loadUrl(appUrl(url))
        activePath = "/"
        updateNavHighlight("/")
    }

    private fun loadDeepUrl(fullUrl: String) {
        // v19 BUG 2: query (app=1) hatakar path nikalo — warna tab match toot jayega
        val path = fullUrl.substringBefore("?").removePrefix(baseUrl())
        val tabPaths = setOf("/", "/history", "/wallet", "/profile", "/admin")
        if (path in tabPaths) {
            selectTab(path)
        } else {
            if (homeVisible) agentChatView.onTabHidden()
            homeVisible = false
            historyVisible = false
            profileVisible = false
            mirrorVisible = false
            mirrorHandler.removeCallbacks(mirrorRunnable)
            homeView.visibility = View.GONE
            historyView.visibility = View.GONE
            profileView.visibility = View.GONE
            browserMirrorView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl(appUrl(fullUrl))
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
        } else if (homeVisible || historyVisible || profileVisible) {
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

    // ---------- agent resume (G2) ----------

    private fun maybeShowResumeDialog() {
        val pending = try {
            com.formmitra.app.engine.AgentResume.checkPending(this)
        } catch (_: Exception) {
            null
        } ?: return
        AlertDialog.Builder(this)
            .setTitle("Adhura kaam")
            .setMessage(
                "Pichhla kaam adhura reh gaya tha:\n${pending.goal}\n" +
                    "(step ${pending.stepsTaken} tak hua tha)\nUsi step se continue karu?"
            )
            .setPositiveButton("Chalao") { _, _ ->
                // Naya task NAHI — WakeWorker pending run ko USI STEP se
                // resume karega (WorkingMode ON hona chahiye).
                com.formmitra.app.agent.WorkingMode.setEnabled(this, true)
                com.formmitra.app.WakeWorker.enqueue(this)
                runOnUiThread { selectTab("/") }
            }
            .setNegativeButton("Chhodo") { _, _ ->
                try {
                    com.formmitra.app.engine.AgentResume.clear(this)
                } catch (_: Exception) { }
            }
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
        // v19: Document Vault (ProfileView) ka file picker
        if (::profileView.isInitialized) {
            profileView.handleActivityResult(requestCode, resultCode, data)
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
        // H5: PromptDialog ke mic ka permission result (chat wale flow jaisa).
        if (requestCode == com.formmitra.app.agent.PromptDialog.REQ_PROMPT_VOICE_PERM) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            com.formmitra.app.agent.PromptDialog.onVoicePermissionResult(this, granted)
        }
        // v20-B: category details popup ke mic ka permission result.
        if (requestCode == HomeView.REQ_POPUP_VOICE_PERM &&
            ::homeView.isInitialized
        ) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            homeView.onPopupVoicePermissionResult(granted)
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

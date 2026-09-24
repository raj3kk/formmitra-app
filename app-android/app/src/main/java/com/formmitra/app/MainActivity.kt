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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var agentChatView: AgentChatView
    private var agentVisible = false
    private lateinit var historyView: com.formmitra.app.agent.HistoryView
    private var historyVisible = false

    // Browser mirror tab (live agent screenshot)
    private lateinit var browserMirrorView: LinearLayout
    private lateinit var mirrorImg: ImageView
    private lateinit var mirrorEmpty: TextView
    private var browserVisible = false
    private val mirrorHandler = Handler(Looper.getMainLooper())
    private val mirrorRunnable = object : Runnable {
        override fun run() {
            if (!browserVisible) return
            refreshMirror()
            mirrorHandler.postDelayed(this, 3000)
        }
    }

    private lateinit var navButtons: List<Button>
    private val tabs = listOf(
        "Home" to "/",
        "Tracking" to "/tracking",
        "Jobs" to "/jobs",
        "Agent" to "/agent",
        "Browser" to "/browser",
        "History" to "/history",
        "Admin" to "/admin/agents",
        "Profile" to "/profile"
    )

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
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = false
        }
        root.addView(webView)

        // v3 Phase 1: "Agent" tab = native intake chat (WebView /agent ki jagah)
        agentChatView = AgentChatView(this) { url -> openLinkInWebView(url) }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            visibility = android.view.View.GONE
        }
        root.addView(agentChatView)

        // History tab — native runs history (cafe wala hisaab)
        historyView = com.formmitra.app.agent.HistoryView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            visibility = android.view.View.GONE
        }
        root.addView(historyView)

        // Browser mirror tab — engine ka agent_mirror.png har 3s refresh
        browserMirrorView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.parseColor("#111111"))
            visibility = android.view.View.GONE
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
            visibility = android.view.View.GONE
        }
        browserMirrorView.addView(mirrorImg)
        root.addView(browserMirrorView)

        // Bottom nav — HorizontalScrollView (8 tabs phone par cramped na hon)
        val navInner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        navButtons = tabs.map { (label, path) ->
            Button(this).apply {
                text = label
                textSize = 13f
                minWidth = (88 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    val m = (4 * resources.displayMetrics.density).toInt()
                    setMargins(m, 0, m, 0)
                }
                setOnClickListener { selectTab(path) }
            }
        }
        navButtons.forEach { navInner.addView(it) }
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

        // Deep link (notification tap) ya fresh launch
        val deepUrl = intent.getStringExtra("deep_url")
        val openTab = intent.getStringExtra("open_tab")
        if (!deepUrl.isNullOrEmpty()) {
            loadDeepUrl(deepUrl)
        } else if (!openTab.isNullOrEmpty()) {
            // Prompt notification tap → seedha Agent tab (popup poller dikhayega)
            selectTab(openTab)
        } else {
            selectTab("/")
        }

        Scheduler.scheduleDigest(this)
        Scheduler.scheduleFormTasks(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }

        checkForUpdate()
        maybeShowResumeDialog()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val deepUrl = intent.getStringExtra("deep_url")
        if (!deepUrl.isNullOrEmpty()) loadDeepUrl(deepUrl)
        val openTab = intent.getStringExtra("open_tab")
        if (!openTab.isNullOrEmpty()) selectTab(openTab)
    }

    override fun onResume() {
        super.onResume()
        // App-wide prompt poller: user kisi bhi tab me ho, agent ka sawal
        // (OTP/input/choice/payment) popup me aayega. Agent tab ka apna
        // poller bhi hai — isShowing guard se double-dialog nahi hoga.
        promptHandler.post(promptPollRunnable)
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
            } catch (_: Exception) { }
            promptHandler.postDelayed(this, 3000)
        }
    }

    private fun baseUrl(): String = BuildConfig.SITE_URL.trimEnd('/')

    private fun selectTab(path: String) {
        val wasAgent = agentVisible
        agentVisible = false
        historyVisible = false
        browserVisible = false
        mirrorHandler.removeCallbacks(mirrorRunnable)
        agentChatView.visibility = View.GONE
        historyView.visibility = View.GONE
        browserMirrorView.visibility = View.GONE
        webView.visibility = View.GONE
        if (wasAgent && path != "/agent") agentChatView.onTabHidden()
        when (path) {
            "/agent" -> {
                // Native chat tab — WebView hide, chat view show
                agentChatView.visibility = View.VISIBLE
                agentVisible = true
                agentChatView.onTabShown()
            }
            "/history" -> {
                // Native history tab
                historyView.visibility = View.VISIBLE
                historyVisible = true
                historyView.onTabShown()
            }
            "/browser" -> {
                // Live browser mirror — engine ka screenshot
                browserMirrorView.visibility = View.VISIBLE
                browserVisible = true
                mirrorHandler.post(mirrorRunnable)
            }
            else -> {
                webView.visibility = View.VISIBLE
                webView.loadUrl(baseUrl() + path)
            }
        }
        updateNavHighlight(path)
    }

    /** Plan card ke official link ko main WebView me kholo. */
    private fun openLinkInWebView(url: String) {
        if (agentVisible) agentChatView.onTabHidden()
        agentVisible = false
        browserVisible = false
        mirrorHandler.removeCallbacks(mirrorRunnable)
        agentChatView.visibility = View.GONE
        historyView.visibility = View.GONE
        browserMirrorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(url)
        updateNavHighlight("/agent")
    }

    private fun loadDeepUrl(fullUrl: String) {
        val path = fullUrl.removePrefix(baseUrl())
        if (tabs.any { it.second == path }) {
            selectTab(path)
        } else {
            if (agentVisible) agentChatView.onTabHidden()
            agentVisible = false
            browserVisible = false
            mirrorHandler.removeCallbacks(mirrorRunnable)
            agentChatView.visibility = View.GONE
            historyView.visibility = View.GONE
            browserMirrorView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl(fullUrl)
            updateNavHighlight(path)
        }
    }

    private fun updateNavHighlight(activePath: String) {
        tabs.forEachIndexed { idx, (_, path) ->
            val active = path == activePath
            navButtons[idx].setTextColor(
                if (active) Color.parseColor("#0E7C5B") else Color.DKGRAY
            )
            navButtons[idx].setTypeface(
                null, if (active) Typeface.BOLD else Typeface.NORMAL
            )
        }
    }

    @Deprecated("Use OnBackPressedDispatcher on newer APIs")
    override fun onBackPressed() {
        if (agentVisible || browserVisible || historyVisible) {
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
                if (!browserVisible) return@runOnUiThread
                val old = (mirrorImg.drawable as? BitmapDrawable)?.bitmap
                if (bmp != null) {
                    mirrorEmpty.visibility = View.GONE
                    mirrorImg.visibility = View.VISIBLE
                    mirrorImg.setImageBitmap(bmp)
                } else {
                    mirrorImg.setImageDrawable(null)
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
                    runOnUiThread { selectTab("/agent") }
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
        // Agent prompt ka document picker (vault doc chuno / naya upload)
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

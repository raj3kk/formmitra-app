package com.formmitra.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import com.formmitra.app.agent.AgentChatView
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var agentChatView: AgentChatView
    private var agentVisible = false
    private lateinit var navButtons: List<Button>
    private val tabs = listOf(
        "Home" to "/",
        "Tracking" to "/tracking",
        "Jobs" to "/jobs",
        "Agent" to "/agent",
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

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        navButtons = tabs.map { (label, path) ->
            Button(this).apply {
                text = label
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener { selectTab(path) }
            }
        }
        navButtons.forEach { nav.addView(it) }
        root.addView(nav)
        setContentView(root)

        // Deep link (notification tap) ya fresh launch
        val deepUrl = intent.getStringExtra("deep_url")
        if (!deepUrl.isNullOrEmpty()) {
            loadDeepUrl(deepUrl)
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val deepUrl = intent.getStringExtra("deep_url")
        if (!deepUrl.isNullOrEmpty()) loadDeepUrl(deepUrl)
    }

    private fun baseUrl(): String = BuildConfig.SITE_URL.trimEnd('/')

    private fun selectTab(path: String) {
        if (path == "/agent") {
            // Native chat tab — WebView hide, chat view show
            webView.visibility = android.view.View.GONE
            agentChatView.visibility = android.view.View.VISIBLE
            agentVisible = true
        } else {
            agentVisible = false
            agentChatView.visibility = android.view.View.GONE
            webView.visibility = android.view.View.VISIBLE
            webView.loadUrl(baseUrl() + path)
        }
        updateNavHighlight(path)
    }

    /** Plan card ke official link ko main WebView me kholo. */
    private fun openLinkInWebView(url: String) {
        agentVisible = false
        agentChatView.visibility = android.view.View.GONE
        webView.visibility = android.view.View.VISIBLE
        webView.loadUrl(url)
        updateNavHighlight("/agent")
    }

    private fun loadDeepUrl(fullUrl: String) {
        val path = fullUrl.removePrefix(baseUrl())
        if (tabs.any { it.second == path }) {
            selectTab(path)
        } else {
            agentVisible = false
            agentChatView.visibility = android.view.View.GONE
            webView.visibility = android.view.View.VISIBLE
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
        if (agentVisible) {
            super.onBackPressed()
        } else if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
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

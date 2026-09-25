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
 * Home = native HomeView (services strip + category cards + live button).
 * Agent = native AgentChatView (dedicated full-screen agent chat tab /agent).
 * History = native HistoryView. Profile = native ProfileView (details/vault/admin/logout).
 * Wallet/Admin = website WebView me (unhi par ?app=1 lagta hai).
 * Purane Agent/Browser/Tracking/Jobs tabs hata diye — services Home strip se khulte hain.
 */
class MainActivity : Activity() {

    private var webView: WebView? = null
    // v22: kuch devices par WebView provider toota/missing hota hai — tab
    // `WebView(this)` constructor hi throw karta hai ("khulte hi band").
    // Isliye WebView nullable + guarded: na bana to app Home par chalta rahe.
    private var webViewOk: Boolean = false
    private lateinit var homeView: HomeView
    private lateinit var agentChatView: AgentChatView
    private var homeVisible = false
    private var agentVisible = false
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

    // v24 #2: "Through Agent" card-create ke dauraan yaad rakhi category —
    // card banne ke baad kaam auto-continue ho.
    private var pendingCatKey: String? = null
    private var pendingCatLabel: String? = null
    private var pendingCatKnownIds: Set<String> = emptySet()

    private val ownerEmail = "priyadarshirajindia@gmail.com"

    private val baseTabs = listOf(
        "Home" to "/",
        "💬 Agent" to "/agent",
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

        // v22: WebView constructor kuch devices par throw karta hai (toota/
        // missing WebView provider) — yehi "khulte hi band" ka sabse likely
        // naya cause tha. Guard karo: na bana to app Home (native) par chale.
        try {
            val wv = WebView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            with(wv.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
            }
            // v14 audit: third-party cookies ON (form automation ke embedded
            // widgets — payment/SSO iframes — inke bina toot jate hain; ye
            // single-user ka apna automation WebView hai).
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.webViewClient = object : WebViewClient() {
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
            root.addView(wv)
            webView = wv
            webViewOk = true
        } catch (t: Throwable) {
            android.util.Log.e(
                "MainActivity", "WebView nahi bana (device WebView toota?)", t
            )
            webView = null
            webViewOk = false
        }

        // Home: native — work categories (card-first) + live button
        // v26: AgentChatView ab Home me embedded NAHI — dedicated full-screen
        // "💬 Agent" tab (/agent) me hai.
        agentChatView = AgentChatView(
            this,
            { url -> openLinkInWebView(url) },
            { selectTab("/profile") }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            visibility = View.GONE
        }
        root.addView(agentChatView)
        // v24 #2: chat se "Through Agent" card-create → Agent tab khulta hai.
        agentChatView.onAgentCreateRequest = { prefill ->
            onAgentCreateCard(null, null, prefill)
        }
        homeView = HomeView(
            this,
            // v24 B8/C14: category card → card-first flow complete hone par
            // card bind + category chat start (prefill card se).
            // v28: track card par trackingType bhi thread hota hai.
            onStartCategory = { category, label, prefill, cardId, cardName, cardToken, trackingType ->
                startCategoryWork(
                    category, label, prefill, cardId, cardName, cardToken,
                    trackingType
                )
            },
            onShowMirror = { showMirror() },
            onOpenProfile = { selectTab("/profile") },
            // v24 #2: "Through Agent" chuna — category yaad rakho taaki
            // card banne ke baad kaam auto-continue ho sake.
            onAgentCreateCard = { catKey, catLabel, prefill ->
                onAgentCreateCard(catKey, catLabel, prefill)
            }
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
        // v24 #2: Profile ke "Through Agent" se card-create → Agent tab kholo.
        profileView.onAgentCreateRequest = { prefill ->
            selectTab("/agent")
            agentChatView.startAgentCardCreate(prefill)
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
        // K1: notification tap ka deep link — seedha sahi screen par.
        handleNotifDeepLink(intent)

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
        "/browser" -> "/"
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
        // K1: notification tap ka deep link (app pehle se khuli ho tab bhi).
        handleNotifDeepLink(intent)
    }

    /**
     * K1: notification tap → seedha sahi screen.
     * Extras (NotifCenter se):
     *  - fm_deep_tab: "/history" | "/profile" | "/agent" | "/" (wallet/admin = WebView tab)
     *  - fm_deep_run_id: History me is run ki detail khule
     *  - fm_deep_prompt: is run ka pending prompt (dialog/resume rasta)
     * Purane deep_url/open_tab flow ko nahi chhoota.
     */
    private fun handleNotifDeepLink(intent: Intent) {
        val tab = intent.getStringExtra(com.formmitra.app.agent.NotifCenter.EXTRA_TAB)
        val runId = intent.getStringExtra(com.formmitra.app.agent.NotifCenter.EXTRA_RUN_ID) ?: ""
        val promptRunId =
            intent.getStringExtra(com.formmitra.app.agent.NotifCenter.EXTRA_PROMPT_RUN_ID) ?: ""
        if (tab.isNullOrEmpty() && runId.isEmpty() && promptRunId.isEmpty()) return
        // Ek baar consume — dobara onNewIntent me same intent aaye to repeat na ho.
        try {
            intent.removeExtra(com.formmitra.app.agent.NotifCenter.EXTRA_TAB)
            intent.removeExtra(com.formmitra.app.agent.NotifCenter.EXTRA_RUN_ID)
            intent.removeExtra(com.formmitra.app.agent.NotifCenter.EXTRA_PROMPT_RUN_ID)
        } catch (_: Exception) { }
        val target = mapLegacyTab(tab ?: "/history")
        selectTab(target)
        if (promptRunId.isNotEmpty() && target == "/history") {
            historyView.openPromptEntry(promptRunId)
        } else if (runId.isNotEmpty() && target == "/history") {
            historyView.openRunDetail(runId)
        }
    }

    override fun onResume() {
        super.onResume()
        // App-wide prompt poller: user kisi bhi tab me ho, agent ka sawal
        // (OTP/input/choice/payment) popup me aayega.
        promptHandler.post(promptPollRunnable)
        // v24 N5 (opportunistic): app khulne par bhi stuck-resume check —
        // background poll (15 min) ke alawa turant pata chale.
        try {
            com.formmitra.app.agent.NudgeCenter.checkStuckResume(this)
        } catch (_: Exception) { }
        if (homeVisible) {
            try { homeView.refreshLiveButton() } catch (_: Exception) { }
        }
    }

    override fun onPause() {
        promptHandler.removeCallbacks(promptPollRunnable)
        super.onPause()
    }

    /**
     * v24 N4: app background me gayi + Working Mode OFF + pending kaam →
     * nudge notification ("Kaam ruk gaya hai — app me jao / Working Mode
     * on karo"). NudgeCenter khud dedupe + guards sambhalta hai.
     */
    override fun onStop() {
        try {
            com.formmitra.app.agent.NudgeCenter.onAppBackground(this)
        } catch (_: Exception) { }
        super.onStop()
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

    /**
     * v29 zero-crash gate: toast kabhi crash na kare (destroyed activity
     * context par Toast.makeText throw karta hai — UI thread = crash).
     */
    private fun toast(msg: String, long: Boolean = false) {
        try {
            android.widget.Toast.makeText(
                this, msg,
                if (long) android.widget.Toast.LENGTH_LONG
                else android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) { }
    }

    private fun selectTab(path: String) {
        val wasAgent = agentVisible
        homeVisible = false
        agentVisible = false
        historyVisible = false
        profileVisible = false
        mirrorVisible = false
        mirrorHandler.removeCallbacks(mirrorRunnable)
        homeView.visibility = View.GONE
        agentChatView.visibility = View.GONE
        historyView.visibility = View.GONE
        profileView.visibility = View.GONE
        browserMirrorView.visibility = View.GONE
        webView?.visibility = View.GONE
        if (wasAgent && path != "/agent") agentChatView.onTabHidden()
        val tabPaths = currentTabs().map { it.second }.toSet()
        when {
            path == "/" -> {
                homeView.visibility = View.VISIBLE
                homeVisible = true
                try { homeView.refreshLiveButton() } catch (_: Exception) { }
                // v24 #2: agent se card ban gaya ho to pending category
                // auto-continue (coordination toote nahi).
                try { resumePendingCategory() } catch (_: Exception) { }
            }
            path == "/agent" -> {
                // v26: dedicated full-screen agent chat tab.
                agentChatView.visibility = View.VISIBLE
                agentVisible = true
                agentChatView.onTabShown()
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
                val wv = webView
                if (wv == null) {
                    // v22: WebView nahi bana — Home par raho, user ko batao
                    homeView.visibility = View.VISIBLE
                    homeVisible = true
                    toast(
                        "Is phone par WebView uplabdh nahi — ye tab nahi khul sakta",
                        long = true
                    )
                } else {
                    wv.visibility = View.VISIBLE
                    wv.loadUrl(appUrl(baseUrl() + path))
                }
            }
            else -> {
                // Service paths (/tracking, /jobs, /scholarships, /resume)
                // WebView me, nav me Home highlight
                val wv = webView
                if (wv == null) {
                    // v22: WebView nahi bana — Home par raho
                    homeView.visibility = View.VISIBLE
                    homeVisible = true
                    toast(
                        "Is phone par WebView uplabdh nahi — ye page nahi khul sakta",
                        long = true
                    )
                } else {
                    wv.visibility = View.VISIBLE
                    val full = appUrl(baseUrl() + path)
                    if (stripQuery(wv.url ?: "") != stripQuery(full)) {
                        wv.loadUrl(full)
                    }
                }
            }
        }
        activePath = if (tabPaths.contains(path)) path else "/"
        updateNavHighlight(activePath)
    }

    /**
     * v24 B8/C14: category kaam shuru — card-first flow complete hone ke
     * baad: card chat + automation me bind, category chat start.
     */
    private fun startCategoryWork(
        category: String,
        label: String,
        prefill: Map<String, String>,
        cardId: String,
        cardName: String,
        cardToken: String,
        trackingType: String? = null
    ) {
        // v28 P12: tab-switch/chat-creation par koi crash nahi.
        try {
        if (!agentVisible) selectTab("/agent")
        // v28 P8: Home se har kaam naya session me shuru hota hai —
        // nayiSession = true. Purana kaam Agent tab → 📜 Purane kaam se resume.
        agentChatView.startCategoryChat(
            category, label, prefill, cardId, cardName, cardToken,
            trackingType, true
        )
        } catch (t: Throwable) {
            android.util.Log.e("FmMain", "startCategoryWork failed", t)
            toast("⚠️ Kaam khulne me dikkat aayi — dobara try karo")
        }
    }

    /**
     * v24 #2: "Through Agent" card-create chuna — category yaad rakho
     * (card banne ke baad auto-continue), chat card_create mode me kholo.
     */
    private fun onAgentCreateCard(
        catKey: String?,
        catLabel: String?,
        prefill: Map<String, String>
    ) {
        pendingCatKey = catKey
        pendingCatLabel = catLabel
        pendingCatKnownIds = emptySet()
        Thread({
            try {
                pendingCatKnownIds =
                    com.formmitra.app.agent.CardFlow.knownCardIds(this)
            } catch (_: Exception) { }
        }, "fm-known-cards").start()
        if (!agentVisible) selectTab("/agent")
        agentChatView.startAgentCardCreate(prefill)
    }

    /**
     * v24 #2: Home par wapas aane par — agent se naya card ban gaya ho to
     * PIN unlock karwao → pending category auto-continue. Teeno
     * (agent + operator + AI) ka coordination toote nahi.
     *
     * v24 #4: category ke BINA bhi (chat se "Through Agent" card-create)
     * naya card mile to: PIN unlock → pending details tag ke saath isi
     * card me flush → chat me card bind. Tab koi detail nahi khoyegi.
     */
    private fun resumePendingCategory() {
        if (pendingCatKnownIds.isEmpty()) return
        val key = pendingCatKey
        val label = pendingCatLabel ?: key ?: "kaam"
        com.formmitra.app.agent.CardFlow.checkNewCardAfterAgent(
            this, pendingCatKnownIds
        ) { id, name ->
            pendingCatKnownIds = emptySet()
            Thread({
                val res = try { com.formmitra.app.agent.AgentApi.cards(this) }
                catch (_: Exception) {
                    com.formmitra.app.agent.AgentApi.ApiResult(-1, null)
                }
                var cardJson: org.json.JSONObject? = null
                val arr = res.json?.optJSONArray("cards")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i)
                        if (c != null && c.optString("id") == id) {
                            cardJson = c
                            break
                        }
                    }
                }
                val cj = cardJson
                runOnUiThread {
                    if (cj == null) return@runOnUiThread
                    pendingCatKey = null
                    pendingCatLabel = null
                    toast(
                        "✅ Naya card: $name — PIN dalo, phir aage badhenge",
                        long = true
                    )
                    com.formmitra.app.agent.CardFlow.askPinAndUnlock(
                        this, cj,
                        onUnlocked = { prefill, cid, cname, token ->
                            com.formmitra.app.agent.CardStore.setSelectedCardId(
                                this, cid
                            )
                            // v24 #4: naya card — pending details auto-flush
                            // (tag ke saath isi card me).
                            com.formmitra.app.agent.CardFlow.flushPendingDetails(
                                this, cid, token
                            )
                            if (key != null) {
                                startCategoryWork(
                                    key, label, prefill, cid, cname, token
                                )
                            } else {
                                // Chat se card bana tha — chat me bind karo.
                                agentChatView.setActiveCard(cid, cname, token)
                                toast(
                                    "✅ Card tayyar — details save ho gayi",
                                    long = true
                                )
                            }
                        }
                    )
                }
            }, "fm-resume-cat").start()
        }
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
        toast("Logout ho gaya")
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
        if (agentVisible) agentChatView.onTabHidden()
        homeVisible = false
        agentVisible = false
        historyVisible = false
        profileVisible = false
        mirrorHandler.removeCallbacks(mirrorRunnable)
        homeView.visibility = View.GONE
        agentChatView.visibility = View.GONE
        historyView.visibility = View.GONE
        profileView.visibility = View.GONE
        webView?.visibility = View.GONE
        browserMirrorView.visibility = View.VISIBLE
        mirrorVisible = true
        mirrorHandler.post(mirrorRunnable)
        activePath = "/"
        updateNavHighlight("/")
    }

    /** Plan card ke official link ko main WebView me kholo. */
    private fun openLinkInWebView(url: String) {
        if (agentVisible) agentChatView.onTabHidden()
        homeVisible = false
        agentVisible = false
        historyVisible = false
        profileVisible = false
        mirrorVisible = false
        mirrorHandler.removeCallbacks(mirrorRunnable)
        homeView.visibility = View.GONE
        agentChatView.visibility = View.GONE
        historyView.visibility = View.GONE
        profileView.visibility = View.GONE
        browserMirrorView.visibility = View.GONE
        val wv = webView
        if (wv == null) {
            // v22: WebView nahi bana — Home par raho
            homeView.visibility = View.VISIBLE
            homeVisible = true
            toast("Is phone par WebView uplabdh nahi — ye link nahi khul sakta")
            return
        }
        wv.visibility = View.VISIBLE
        // v19 BUG 2: apne domain par ?app=1 — bahar ke official links jaisi hain waisi
        wv.loadUrl(appUrl(url))
        activePath = "/"
        updateNavHighlight("/")
    }

    private fun loadDeepUrl(fullUrl: String) {
        // v19 BUG 2: query (app=1) hatakar path nikalo — warna tab match toot jayega
        val path = fullUrl.substringBefore("?").removePrefix(baseUrl())
        val tabPaths = setOf("/", "/agent", "/history", "/wallet", "/profile", "/admin")
        if (path in tabPaths) {
            selectTab(path)
        } else {
            if (agentVisible) agentChatView.onTabHidden()
            homeVisible = false
            agentVisible = false
            historyVisible = false
            profileVisible = false
            mirrorVisible = false
            mirrorHandler.removeCallbacks(mirrorRunnable)
            homeView.visibility = View.GONE
            agentChatView.visibility = View.GONE
            historyView.visibility = View.GONE
            profileView.visibility = View.GONE
            browserMirrorView.visibility = View.GONE
            val wv = webView
            if (wv == null) {
                // v22: WebView nahi bana — Home par raho
                homeView.visibility = View.VISIBLE
                homeVisible = true
                toast("Is phone par WebView uplabdh nahi — ye page nahi khul sakta")
                return
            }
            wv.visibility = View.VISIBLE
            wv.loadUrl(appUrl(fullUrl))
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
        } else if (homeVisible || agentVisible || historyVisible || profileVisible) {
            super.onBackPressed()
        } else if (activePath == "/wallet") {
            // v24 A5 ROOT CAUSE: Wallet khula ho to Back = Home tab par wapas
            // (selectTab), WebView history ya website home par NAHI.
            selectTab("/")
        } else if (webView?.canGoBack() == true) webView?.goBack() else super.onBackPressed()
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
        // L1-UPGRADE: SMS User Consent result → OTP auto-fill
        try {
            if (com.formmitra.app.agent.SmsOtpConsent.handleActivityResult(
                    requestCode, resultCode, data
                )
            ) return
        } catch (_: Exception) { }
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

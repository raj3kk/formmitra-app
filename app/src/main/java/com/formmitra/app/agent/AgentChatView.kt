package com.formmitra.app.agent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import com.formmitra.app.engine.Standalone
import com.formmitra.app.engine.UserPrompt
import com.formmitra.app.engine.PrecheckLogic
import com.formmitra.app.engine.AiUsage
import com.formmitra.app.engine.CardUnlockPolicy
import com.formmitra.app.engine.TrackOfferPolicy
import com.formmitra.app.engine.DestructivePolicy
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * FormMitra v16 — native Mitra chat, Home tab me embedded.
 *
 * v16 changes (user demands):
 * - Send path hardened: fail par saaf error + retry button (silent fail nahi);
 *   401 par "Login karo" button (Profile tab kholta hai); 75s watchdog.
 * - PERMANENT FULL APPROVAL (2026-09-25): koi verify/Proceed gate NAHI —
 *   details seedha save hoti hain, task seedha banta hai. Galat detail
 *   user Profile me theek kar sakta hai; run ke beech detail-request
 *   loop (K4) maang lega.
 * - Voice: live partial transcription, sun-ne ka clear indicator, hi-IN →
 *   en-IN fallback, error codes ke saaf messages.
 * - Attach (📎): koi bhi file type (limited selector hata diya).
 * - User-side API key screens hata diye (standalone engine code intact hai).
 */
class AgentChatView(
    context: Context,
    private val onOpenLink: (String) -> Unit,
    private val onOpenProfile: () -> Unit = {}
) : LinearLayout(context) {

    private val history = mutableListOf<Pair<String, String>>()
    private val messageList: LinearLayout
    private val scroll: ScrollView
    private val input: EditText
    private val sendBtn: Button
    private val micBtn: Button
    private var typingView: View? = null
    private var waiting = false

    // v20 Task 5: work-wise category context (chat body me `category` jayega)
    private var activeCategory: String? = null
    /** v28 P3: track category me 8 track-types ka context (chat body me
     *  `tracking_type` jayega; chip me bhi dikhega). */
    private var activeTrackingType: String? = null
    /** Category flow me plan aate hi start khud ho (koi Proceed tap nahi). */
    private var autoPlanArmed = false
    private lateinit var categoryChip: TextView

    // v24: active FormMitra Card — chat + automation dono me bind hota hai.
    // POINT 24 (revised): unlock PERSISTENT hai — memory token ke saath
    // CardStore me unlock flag + encrypted token (koi auto re-lock nahi).
    private var activeCardId: String? = null
    private var activeCardName: String? = null
    private var activeCardToken: String? = null

    /** POINT 24: header me manual lock/unlock button (prominent). */
    private lateinit var lockBtn: Button
    /** POINT 24: is card ke liye entry-PIN is session me dikhaya (nag nahi). */
    private var pinPromptShownFor: String? = null

    /**
     * v24-refine (AI-trained conversational agent): card-create Q&A mode —
     * server ek-ek karke poochhta hai; app-side turant validation
     * (tok-sudhar) isi mode me hoti hai. Card ban jaye → setActiveCard
     * me clear.
     */
    private var cardCreateMode = false

    /** "Through Agent" card-create chuna → MainActivity chat kholta hai. */
    var onAgentCreateRequest: ((prefill: Map<String, String>) -> Unit)? = null

    /** Card select/unlock hua — chat + aage ke automation dono me bind karo. */
    fun setActiveCard(cardId: String?, cardName: String?, cardToken: String?) {
        val switched = cardId != activeCardId
        activeCardId = cardId
        activeCardName = cardName
        activeCardToken = cardToken
        cardCreateMode = false
        try {
            AgentApi.setAutomationCard(cardId, cardToken)
        } catch (_: Exception) { }
        // POINT 24: card switch → naya card locked ho to PIN lagega
        // (sirf selected card unlock hota hai — multi-card).
        if (switched) pinPromptShownFor = null
        try { refreshLockBtn() } catch (_: Exception) { }
    }

    /** POINT 24: sign-out → chat ka card state saaf (lockAll pehle ho chuka). */
    fun onLoggedOut() {
        activeCardId = null
        activeCardName = null
        activeCardToken = null
        pinPromptShownFor = null
        cardCachedDetails.clear()
        try { refreshLockBtn() } catch (_: Exception) { }
    }

    /**
     * v24 #2: Through Agent card-create — agent ek-ek karke poochhega
     * (voice Q&A). Saaf Hinglish intro; server agent validate + samjhaye.
     */
    fun startAgentCardCreate(prefill: Map<String, String>) {
        autoPlanArmed = false
        activeCategory = null
        cardCreateMode = true
        // v29 P1/P1c: naya kaam — asked-dedupe reset; prefill cache me
        // (ye details user pehle de chuka — dobara na maangi jayen).
        // sessionDetails bhi saaf (purane work ki memory leak NAHI) +
        // prefs bhi.
        askedKeys.clear()
        sessionDetails.clear()
        clearWorkMemory("card_create")
        cardCachedDetails.clear()
        cardCachedDetails.putAll(prefill)
        post {
            try { categoryChip.visibility = View.GONE } catch (_: Exception) { }
            addAssistantBubble(
                "🪪 Chalo, tumhara FormMitra Card banate hain!\n\n" +
                    "Main ek-ek karke poochhunga — tum mic 🎤 se bolo ya likhkar do.\n" +
                    "Kuch galat bhar diya to main tokunga AUR samjhaunga kahan se sahi bharna hai, " +
                    "phir se bharwaunga. Ghabrao mat! 😊"
            )
            VoiceOutput.speak(
                context,
                "Chalo tumhara FormMitra Card banate hain. Main ek ek karke poochhunga."
            )
        }
        val sb = StringBuilder(
            "🪪 Naya FormMitra Card banana hai (card_create mode). " +
                "Ek-ek karke sawaal poochho: pehle card ka naam, phir basic details. " +
                "Har jawab validate karo; galat ho to toko, samjhao kahan se sahi bharna hai, phir se bharwao."
        )
        if (prefill.isNotEmpty()) {
            sb.append("\nPehle se mili details:")
            for ((k, v) in prefill) {
                sb.append("\n• ").append(DetailExtractor.label(k)).append(": ").append(v)
            }
        }
        sendMessage(sb.toString())
    }

    // send watchdog + retry
    private val sendWatchdog = Handler(Looper.getMainLooper())
    private var sendToken = 0
    private var lastFailedText: String? = null

    // verify-before-save: is session me verify ho chuki details
    private val sessionDetails = LinkedHashMap<String, String>()

    /**
     * v29 (P1-APP): per-work asked-keys dedupe — server jis key ke liye
     * poochh chuka (response ka `asked_for`), wo dobara nahi poochhega.
     * Naye work par clear (startCategoryChat newSession / startAgentCardCreate).
     * User jawab de → sendMessage me fresh extraction ke keys yahan se hatenge.
     */
    private val askedKeys = mutableSetOf<String>()

    /**
     * v29 (P1-APP): card se cached details (startCategoryChat ka prefill).
     * Har chat/act request me `known = cardCachedDetails + sessionDetails`
     * banta hai → server ko `known_details` ke roop me jata hai.
     */
    private val cardCachedDetails = LinkedHashMap<String, String>()

    // needs_user banner
    private lateinit var bannerBox: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var retryBtn: Button
    private var bannerGoal = ""
    private var bannerUrl = ""
    private var bannerCategory = ""

    // voice input
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var voiceFallbackTried = false
    private var voiceBaseText = ""

    // task status polling (sirf Home/chat visible ho tabhi)
    private val pollHandler = Handler(Looper.getMainLooper())
    private var polling = false
    /** Point 14: detail batch listener ek hi baar register ho. */
    private var detailBatchListenerRegistered = false
    // POINT 25: tracking action-offer.
    private var trackOfferListenerRegistered = false
    private val trackOfferCards = mutableMapOf<String, View>()
    // POINT 22: card unlock needed.
    private var cardUnlockListenerRegistered = false
    private val cardUnlockCards = mutableMapOf<String, View>()
    /** Dikhaye gaye batch cards (runId → card view) — duplicate na dikhe. */
    private val detailBatchCards = mutableMapOf<String, View>()
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            pollTaskStatus()
            pollHandler.postDelayed(this, 30_000)
        }
    }

    // v36 — LIVE ACTIVITY INDICATOR (user order 2026-09-26): transient
    // status line (thinking-indicator jaisa) — CHAT MESSAGE NAHI, history
    // me save nahi hota, messageList me add nahi hota. Engine
    // (AgentLoop) ke LiveActivity events se drive hota hai.
    private lateinit var liveActivityText: TextView
    private var liveActivityListenerRegistered = false
    private var liveActivityVisible = false
    private var liveBaseLabel = ""
    private var liveDots = 0
    /** v38 addition #4: agent active ho to Live button par dot. */
    private var liveBtn: Button? = null
    private val liveDotsHandler = Handler(Looper.getMainLooper())
    private val liveDotsRunnable = object : Runnable {
        override fun run() {
            if (!liveActivityVisible) return
            liveDots = (liveDots + 1) % 4
            liveActivityText.text = "✦ $liveBaseLabel" + ".".repeat(liveDots)
            liveDotsHandler.postDelayed(this, 450)
        }
    }
    private val liveActivityListener: (LiveActivity.Event) -> Unit = { e ->
        post { onLiveActivityEvent(e) }
    }

    companion object {
        /** MainActivity.onActivityResult se forward hota hai. */
        const val REQ_DOC_PICK = 1001
        /** MainActivity.onRequestPermissionsResult se forward hota hai. */
        const val REQ_VOICE_PERM = 1002
    }

    private val userBg = "#1A73E8"
    private val asstBg = "#F1F3F4"
    private val accent = "#0E7C5B"

    init {
        orientation = VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.WHITE)
        val pad = dp(12)

        // Header: title + Details button
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, dp(8), pad, dp(2))
        }
        header.addView(TextView(context).apply {
            text = "🤖 Mitra"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(context).apply {
            text = "📋 Details"
            textSize = 13f
            minimumWidth = 0
            setOnClickListener { showDetailsCard() }
        })
        // POINT 2+12 (merged): chat ↔ fullscreen live operator view toggle.
        // Yehi fullscreen view Profile ke "Live Operator" se khulta hai —
        // ek hi OperatorView, do entry points.
        // v38: usi me LIVE WebView (screenshot nahi, asli live).
        val liveButton = Button(context).apply {
            text = "🖥️ Live"
            textSize = 13f
            minimumWidth = 0
            setOnClickListener {
                try {
                    val i = android.content.Intent(
                        context,
                        com.formmitra.app.agent.OperatorView::class.java
                    )
                    // Active run ka context (agar ho) — live view wahi dikhaye.
                    com.formmitra.app.engine.FormRunService.activeTaskId
                        ?.let { i.putExtra("run_id", it) }
                    context.startActivity(i)
                } catch (t: Throwable) {
                    android.util.Log.e("FmChat", "live view open failed", t)
                    toast("⚠️ Live view nahi khul paya — dobara try karo")
                }
            }
        }
        liveBtn = liveButton
        header.addView(liveButton)
        // v38 addition #4: attach par pichle event se dot restore karo.
        try {
            LiveActivity.lastEvent()?.let { updateLiveDot(it) }
        } catch (_: Exception) { }
        // POINT 24 (revised): manual "Lock karo" — prominent, header me.
        // Unlock sirf yahan se ya sign-out se tootega (koi auto re-lock nahi).
        lockBtn = Button(context).apply {
            text = "🔓 Card kholo"
            textSize = 13f
            minimumWidth = 0
            visibility = View.GONE
            setOnClickListener { onLockBtnTapped() }
        }
        header.addView(lockBtn)
        addView(header)

        // v28 P6/P7: category picker + purane kaam row (Agent tab ke andar).
        val catRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, dp(2), pad, dp(2))
        }
        catRow.addView(Button(context).apply {
            text = "📂 Kaam (काम) chuno"
            textSize = 12f
            minimumWidth = 0
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showCategoryPicker() }
        })
        catRow.addView(Button(context).apply {
            text = "📜 Purane Kaam (पुराने काम)"
            textSize = 12f
            minimumWidth = 0
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showPastWork() }
        })
        // v41: user apni chat history khud delete kar sake (is kaam ki).
        catRow.addView(Button(context).apply {
            text = "🗑️"
            textSize = 12f
            minimumWidth = 0
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setOnClickListener { confirmClearChat() }
        })
        addView(catRow)

        // v20 Task 5: active category chip — ✕ dabao to category context hate
        categoryChip = TextView(context).apply {
            visibility = View.GONE
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A73E8"))
            background = with(UiKit) { context.chipBg() }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { clearCategory() }
        }
        addView(
            categoryChip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { setMargins(pad, dp(2), pad, dp(2)) }
        )

        // v14: CAPTCHA auto-solve consent toggle (default ON, persisted)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, dp(2), pad, dp(2))
            addView(TextView(context).apply {
                text = "🧩 CAPTCHA auto-solve"
                textSize = 13f
                setTextColor(Color.parseColor("#202124"))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(android.widget.Switch(context).apply {
                isChecked = com.formmitra.app.engine.CaptchaConsent.isEnabled(context)
                textSize = 12f
                setOnCheckedChangeListener { _, on ->
                    com.formmitra.app.engine.CaptchaConsent.setEnabled(context, on)
                    toast(if (on) "CAPTCHA auto-solve ON ✅" else "CAPTCHA auto-solve OFF — ab aap khud solve karenge")
                }
            })
        })

        // needs_user banner (polling se dikhega)
        bannerBox = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
            val d = GradientDrawable()
            d.setColor(Color.parseColor("#FFF3E0"))
            d.setStroke(dp(1), Color.parseColor("#E65100"))
            d.cornerRadius = dp(10).toFloat()
            background = d
            setPadding(pad, dp(8), pad, dp(8))
        }
        bannerText = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#4E342E"))
        }
        bannerBox.addView(bannerText)
        retryBtn = Button(context).apply {
            text = "Maine kar diya — dobara chalao"
            textSize = 14f
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, 0) }
            setOnClickListener { retryTask() }
        }
        bannerBox.addView(retryBtn)
        addView(
            bannerBox,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(pad, dp(2), pad, dp(2))
            }
        )

        scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f
            )
            // v20 Task 1: messages kam hon to bhi area bhara rahe — input
            // row hamesha neeche apni jagah par rahe
            isFillViewport = true
        }
        messageList = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(messageList)
        addView(scroll)

        // v36 — LIVE ACTIVITY INDICATOR (user order 2026-09-26): chat ke
        // neeche transient status line — thinking-indicator jaisa. CHAT
        // MESSAGE NAHI: messageList me add nahi hota, history me save nahi
        // hota. Kaam complete/rukne/fail par gayab ho jata hai.
        liveActivityText = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFF5B7C99.toInt())
            setPadding(pad, dp(2), pad, dp(2))
            gravity = Gravity.START
            visibility = View.GONE
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            )
        }
        addView(liveActivityText)

        // v27 (RC2): 2-row input area.
        // ROOT CAUSE: plain Button ka platform default minWidth=88dp hota hai —
        // 4 chaude buttons Row B me EditText ko dabaa dete the (360dp screen
        // par EditText ~0dp). Isliye type-box kabhi dikha hi nahi, aur live
        // voice transcription (jo input me likhta hai) bhi invisible thi.
        // Ab har button par minimumWidth=0 + compact text/padding — EditText
        // ko 360dp screen par ~190dp milta hai.
        // Manifest me windowSoftInputMode="adjustResize" hai, isliye keyboard
        // khulne par window shrink hogi aur ye area keyboard ke upar rahega.
        val inputArea = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, dp(4), pad, pad)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            )
        }
        // ---- Row A (slim): [Upload] [🔊 Awaaz ON/OFF] ----
        val toolRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val uploadBtn = Button(context).apply {
            text = "Upload"
            textSize = 13f
            minimumWidth = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(8), 0) }
            setOnClickListener { onAttachClick() }
        }
        toolRow.addView(uploadBtn)
        // 🔊 speaker toggle — agent ke jawab bol ke sunao (Row A me shift)
        val speakBtn = Button(context).apply {
            fun label(on: Boolean) = if (on) "🔊 Awaaz ON" else "🔇 Awaaz OFF"
            text = label(VoiceOutput.isEnabled(context))
            textSize = 13f
            minimumWidth = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val on = !VoiceOutput.isEnabled(context)
                VoiceOutput.setEnabled(context, on)
                text = label(on)
                if (on) VoiceOutput.speak(context, "Awaaz chalu hai")
            }
        }
        toolRow.addView(speakBtn)
        inputArea.addView(toolRow)
        // ---- Row B: [text weight=1] [🎤] [Bhejo ➤] ----
        val inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            )
            minimumHeight = dp(56)
        }
        input = EditText(context).apply {
            hint = "Yahan likho…"
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_SEND
            minimumHeight = dp(48)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            background = inputBg()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendMessage((v as TextView).text.toString())
                    true
                } else false
            }
            // v20 Task 1: focus milte hi messages neeche scroll — input
            // keyboard ke upar saaf dikhe
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) post { scrollToBottom() }
            }
            // L2: chat draft — type karte jao, app band ho to bhi bacha rahe
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, st: Int, c: Int, a: Int
                ) { }
                override fun onTextChanged(
                    s: CharSequence?, st: Int, b: Int, c: Int
                ) { }
                override fun afterTextChanged(s: android.text.Editable?) {
                    saveDraft(s?.toString().orEmpty())
                }
            })
            // Pichla draft restore karo (bheja nahi gaya tha)
            val draft = loadDraft()
            if (draft.isNotEmpty()) setText(draft)
        }
        inputRow.addView(input)
        micBtn = Button(context).apply {
            text = "🎤"
            textSize = 18f
            minimumWidth = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener { onMicClick() }
        }
        inputRow.addView(micBtn)
        sendBtn = Button(context).apply {
            text = "Bhejo ➤"
            textSize = 15f
            minimumWidth = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { sendMessage(input.text.toString()) }
        }
        inputRow.addView(sendBtn)
        inputArea.addView(inputRow)
        addView(inputArea)

        // v20 Task 2: button press feedback (tasteful, halka scale)
        UiKit.pressFeedback(uploadBtn)
        UiKit.pressFeedback(micBtn)
        UiKit.pressFeedback(speakBtn)
        UiKit.pressFeedback(sendBtn)

        // Greeting (v19): product ab tracking assistant hai — form-filling
        // direction user ne cancel kar di thi, isliye copy badli.
        // v28 P14: restart par pichli category yaad rahe — uski history
        // wapas lao (greeting sirf tab jab koi category kabhi chuni hi nahi).
        post {
            val lastCat = try {
                draftPrefs().getString("last_category", null)
            } catch (_: Exception) { null }
            if (lastCat != null && WorkCategories.of(lastCat) != null) {
                restoreLastCategory(lastCat)
            } else {
                addAssistantBubble(
                    "Namaste! 🙏 Main aapka tracking assistant hun — " +
                        "zameen tracking, sarkari jobs, scholarships ya " +
                        "resume me help chahiye to batao."
                )
            }
        }
    }

    /**
     * v28 P14: app restart par pichli active category wapas — history
     * screen par, chip dikhe, koi naya intro message NAHI (woh pehle
     * bheja ja chuka hai).
     */
    private fun restoreLastCategory(catKey: String) {
        try {
            activeCategory = catKey
            // v41: track-type bhi restore — history/memory namespace is par hai.
            val savedType = draftPrefs().getString("last_tracking_type", null)
            activeTrackingType = if (catKey == "track") savedType else null
            val label = WorkCategories.labelOf(catKey)
            val chipText = if (activeTrackingType != null)
                "🔖 $label › ${WorkCategories.trackLabelOf(activeTrackingType)}  ✕"
            else "🔖 $label  ✕"
            post {
                categoryChip.text = chipText
                categoryChip.visibility = View.VISIBLE
            }
            loadChatHistory(catKey, activeTrackingType)
            // v29 P1c (no-loss): rotation/kill/reopen par is work ki memory
            // wapas — user ko details dobara nahi deni padengi.
            restoreWorkMemory(workKeyFor(catKey, activeTrackingType) ?: catKey)
            // Card bhi wapas bind karo (selected card prefs me rehta hai).
            val selId = CardStore.selectedCardId(context)
            val selTok = selId?.let { CardStore.token(it) }
            if (selId != null && selTok != null) {
                setActiveCard(selId, null, selTok)
                Thread({
                    try {
                        val det = AgentApi.cardDetail(context, selId, selTok).json
                        val vals = CardSaveVerifier.storedValuesFrom(det)
                        post {
                            cardCachedDetails.clear()
                            cardCachedDetails.putAll(vals)
                        }
                    } catch (_: Exception) { }
                }, "fm-restore-card").start()
            }
        } catch (_: Exception) { }
    }

    /** v28 P14: active category yaad rakho (restart-proof). */
    private fun rememberCategory(catKey: String?) {
        try {
            val e = draftPrefs().edit()
            if (catKey == null) e.remove("last_category")
            else e.putString("last_category", catKey)
            e.apply()
        } catch (_: Exception) { }
    }

    /** v41: track-type bhi yaad rakho — history/memory namespace is par hai. */
    private fun rememberTrackingType(type: String?) {
        try {
            val e = draftPrefs().edit()
            if (type == null) e.remove("last_tracking_type")
            else e.putString("last_tracking_type", type)
            e.apply()
        } catch (_: Exception) { }
    }

    // ---------- bubbles ----------

    private fun bubbleBg(color: String, alignRight: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(Color.parseColor(color))
        d.cornerRadii = floatArrayOf(
            dp(16).toFloat(), dp(16).toFloat(),
            dp(16).toFloat(), dp(16).toFloat(),
            if (alignRight) dp(4).toFloat() else dp(16).toFloat(),
            if (alignRight) dp(4).toFloat() else dp(16).toFloat(),
            dp(16).toFloat(), dp(16).toFloat()
        )
        return d
    }

    private fun inputBg(): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(Color.parseColor("#F7F7F7"))
        d.setStroke(dp(1), Color.parseColor("#DDDDDD"))
        d.cornerRadius = dp(22).toFloat()
        return d
    }

    private fun addBubble(text: String, isUser: Boolean) {
        val wrap = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = if (isUser) Gravity.END else Gravity.START
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, dp(4)) }
        }
        val tv = TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(if (isUser) Color.WHITE else Color.parseColor("#202124"))
            background = bubbleBg(if (isUser) userBg else asstBg, isUser)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { width = LayoutParams.WRAP_CONTENT }
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
        }
        wrap.addView(tv)
        // v24 (C16): agent ki awaaz par HAR JAGAH repeat + mute.
        if (!isUser) {
            // bubble ka message text (neeche apply receivers `text` ko
            // shadow karte hain — isliye pehle capture).
            val msgText = text
            val vrow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.START
            }
            val repeatBtn = Button(context).apply {
                // NOTE: addBubble ka `text` param val hai — button label ke
                // liye explicit `this.text`.
                this.text = "🔁 Dobara suno (फिर सुनें)"
                textSize = 11f
                setOnClickListener {
                    VoiceOutput.init(context)
                    VoiceOutput.repeat(context, msgText)
                }
            }
            val muteBtn = Button(context).apply {
                this.text = if (VoiceOutput.isEnabled(context)) "🔇 Band karo (बंद)" else "🔊 Chalao (चालू)"
                textSize = 11f
                setOnClickListener {
                    val on = !VoiceOutput.isEnabled(context)
                    VoiceOutput.setEnabled(context, on)
                    this.text = if (on) "🔇 Band karo (बंद)" else "🔊 Chalao (चालू)"
                    toast(if (on) "Awaaz ON 🔊" else "Awaaz OFF 🔇")
                }
            }
            vrow.addView(repeatBtn)
            vrow.addView(
                muteBtn,
                LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(6), 0, 0, 0) }
            )
            wrap.addView(vrow)
        }
        messageList.addView(wrap)
        scrollToBottom()
    }

    private fun addUserBubble(text: String) = addBubble(text, true)
    private fun addAssistantBubble(text: String) = addBubble(text, false)

    /**
     * Error bubble — laal, neeche action button ke saath (Retry / Login).
     * Silent fail kabhi nahi: har failure user ko dikhega.
     */
    private fun addErrorBubble(
        text: String,
        actionLabel: String?,
        onAction: (() -> Unit)?
    ) {
        val wrap = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.START
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, dp(4)) }
        }
        val tv = TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#7A1F1F"))
            background = bubbleBg("#FDECEA", false)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.85).toInt()
        }
        wrap.addView(tv)
        if (actionLabel != null && onAction != null) {
            val b = Button(context).apply {
                this.text = actionLabel
                textSize = 13f
                setOnClickListener { onAction() }
            }
            wrap.addView(
                b,
                LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(4), 0, 0) }
            )
        }
        messageList.addView(wrap)
        scrollToBottom()
    }

    private fun showTyping() {
        val tv = TextView(context).apply {
            text = "…soch raha hai"
            textSize = 15f
            setTextColor(Color.parseColor("#80868B"))
            setTypeface(null, Typeface.ITALIC)
            background = bubbleBg(asstBg, false)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val lp = LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(4), 0, dp(4)) }
        messageList.addView(tv, lp)
        typingView = tv
        scrollToBottom()
    }

    private fun hideTyping() {
        typingView?.let { messageList.removeView(it) }
        typingView = null
    }

    private fun scrollToBottom() {
        post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- plan card ----------

    private fun addPlanCard(plan: JSONObject) {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            val d = GradientDrawable()
            d.setColor(Color.WHITE)
            d.setStroke(dp(2), Color.parseColor(accent))
            d.cornerRadius = dp(14).toFloat()
            background = d
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val lp = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(6), 0, dp(6)) }

        card.addView(TextView(context).apply {
            text = plan.optString("title", "Form")
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
        })

        val link = plan.optString("official_link", "")
        if (link.isNotEmpty()) {
            card.addView(TextView(context).apply {
                text = "🔗 $link"
                textSize = 13f
                setTextColor(Color.parseColor("#1A73E8"))
                paint.isUnderlineText = true
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(0, dp(6), 0, dp(4))
                setOnClickListener { onOpenLink(link) }
            })
        }

        val loginNeeded = plan.optBoolean("login_needed", false)
        card.addView(TextView(context).apply {
            text = if (loginNeeded) "🔒 Login lagega" else "🔓 Bina login"
            textSize = 13f
            setTextColor(Color.parseColor("#5F6368"))
            setPadding(0, 0, 0, dp(6))
        })

        val docs = plan.optJSONArray("required_docs")
        if (docs != null && docs.length() > 0) {
            card.addView(TextView(context).apply {
                text = "Kya-kya lagega:"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(2))
            })
            for (i in 0 until docs.length()) {
                val doc = docs.optString(i, "").trim()
                if (doc.isNotEmpty()) {
                    card.addView(TextView(context).apply {
                        text = "✓ $doc"
                        textSize = 14f
                        setPadding(dp(8), dp(2), 0, dp(2))
                    })
                }
            }
        }

        val steps = plan.optInt("estimated_steps", 0)
        if (steps > 0) {
            card.addView(TextView(context).apply {
                text = "≈ $steps steps lagenge"
                textSize = 13f
                setTextColor(Color.parseColor("#5F6368"))
                setPadding(0, dp(6), 0, 0)
            })
        }

        val warns = plan.optJSONArray("warnings")
        if (warns != null && warns.length() > 0) {
            for (i in 0 until warns.length()) {
                val w = warns.optString(i, "").trim()
                if (w.isNotEmpty()) {
                    card.addView(TextView(context).apply {
                        text = "⚠ $w"
                        textSize = 13f
                        setTextColor(Color.parseColor("#B06000"))
                        setPadding(0, dp(2), 0, dp(2))
                    })
                }
            }
        }

        // Shuru karo — seedha task (koi verify gate nahi)
        val startBtn = Button(context).apply {
            text = "▶ Shuru karo"
            textSize = 15f
            setTextColor(Color.WHITE)
            val d = GradientDrawable()
            d.setColor(Color.parseColor("#2E9E5B"))
            d.cornerRadius = dp(10).toFloat()
            background = d
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10), 0, 0) }
        }
        startBtn.setOnClickListener { onStartPlanClicked(plan, link, startBtn) }
        card.addView(startBtn)

        messageList.addView(card, lp)
        scrollToBottom()
    }

    /**
     * Plan ka "Shuru karo" flow — button tap ya category auto-start, dono
     * yahi aate hain. startBtn null = auto path (button state skip hota hai).
     *
     * PERMANENT FULL APPROVAL (2026-09-25): koi verify/Proceed gate NAHI —
     * agent seedha task banata hai. Galat detail hui to run ke beech
     * detail-request loop (K4) se maang lega.
     */
    // ---------- Point 14: SMART DETAIL COLLECTION (compact batch card) ----------
    //
    // Server "details_needed" ka compact batch bhejta hai (ya loop kind=input
    // par batch banata hai). NON-BLOCKING: user jab chahe bhare — koi modal
    // dialog nahi, kaam park hai (resume state saved). Ek hi card me saare
    // fields + har field ke liye "kyun chahiye" (simple Hinglish, jargon nahi).

    /** Tab khulne par pending batches dikhao (crash ke baad bhi bache hain). */
    private fun checkPendingDetailBatches() {
        try {
            val batches = DetailBatchStore.pending(context)
            for (b in batches) {
                if (!detailBatchCards.containsKey(b.runId)) {
                    addDetailBatchCard(b)
                }
            }
        } catch (_: Exception) { }
    }

    /** Ek compact card: saare fields (label + kyun + input) + Bhejo button. */
    private fun addDetailBatchCard(batch: DetailBatchStore.Batch) {
        try {
            if (detailBatchCards.containsKey(batch.runId)) return
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                val d = GradientDrawable()
                d.setColor(Color.parseColor("#FFF8E1"))
                d.setStroke(dp(2), Color.parseColor("#FFB300"))
                d.cornerRadius = dp(14).toFloat()
                background = d
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            val lp = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) }

            card.addView(TextView(context).apply {
                text = "📝 Kuch details chahiye"
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#202124"))
            })
            if (batch.taskName.isNotEmpty()) {
                card.addView(TextView(context).apply {
                    text = batch.taskName
                    textSize = 13f
                    setTextColor(Color.parseColor("#5F6368"))
                    setPadding(0, dp(2), 0, dp(6))
                })
            }

            // Har field: label + "kyun chahiye" + input
            val inputs = LinkedHashMap<String, EditText>()
            for (f in batch.fields) {
                card.addView(TextView(context).apply {
                    text = f.label
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#202124"))
                    setPadding(0, dp(8), 0, 0)
                })
                card.addView(TextView(context).apply {
                    // Kyun chahiye — simple Hinglish, jargon nahi
                    text = "❓ ${f.why}"
                    textSize = 13f
                    setTextColor(Color.parseColor("#5F6368"))
                    setPadding(0, 0, 0, dp(4))
                })
                if (f.type == "choice" && f.options.isNotEmpty()) {
                    // Choice: option buttons (ek tap me select)
                    val optRow = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                    }
                    var selected = ""
                    for (opt in f.options) {
                        val btn = Button(context).apply {
                            text = opt
                            textSize = 13f
                            minimumWidth = 0
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                        }
                        btn.setOnClickListener {
                            selected = opt
                            // Sabko reset, isko highlight
                            for (i in 0 until optRow.childCount) {
                                (optRow.getChildAt(i) as? Button)?.let {
                                    it.setBackgroundColor(Color.parseColor("#E0E0E0"))
                                }
                            }
                            btn.setBackgroundColor(Color.parseColor("#C8E6C9"))
                            inputs[f.key]?.setText(opt)
                        }
                        optRow.addView(btn)
                    }
                    // Hidden EditText taaki submit uniform rahe
                    val hidden = EditText(context).apply { visibility = View.GONE }
                    inputs[f.key] = hidden
                    card.addView(hidden)
                    card.addView(optRow)
                } else {
                    val et = EditText(context).apply {
                        hint = f.label
                        textSize = 15f
                        inputType = when (f.type) {
                            "phone" -> android.text.InputType.TYPE_CLASS_PHONE
                            "email" -> android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                            "number" -> android.text.InputType.TYPE_CLASS_NUMBER
                            else -> android.text.InputType.TYPE_CLASS_TEXT
                        }
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        val bg = GradientDrawable()
                        bg.setColor(Color.WHITE)
                        bg.setStroke(dp(1), Color.parseColor("#BDBDBD"))
                        bg.cornerRadius = dp(8).toFloat()
                        background = bg
                    }
                    // Pehle di hui value ho to dikhao (audit se)
                    val prev = batch.answers[f.key]
                    if (!prev.isNullOrEmpty()) et.setText(prev)
                    inputs[f.key] = et
                    card.addView(et)
                }
            }

            card.addView(TextView(context).apply {
                text = "Bhar ke Bhejo dabao — kaam apne aap aage badhega. ⏳"
                textSize = 13f
                setTextColor(Color.parseColor("#5F6368"))
                setPadding(0, dp(8), 0, 0)
            })

            val sendBtn = Button(context).apply {
                text = "Bhejo ➤"
                textSize = 15f
                setTextColor(Color.WHITE)
                val d = GradientDrawable()
                d.setColor(Color.parseColor("#2E9E5B"))
                d.cornerRadius = dp(10).toFloat()
                background = d
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(10), 0, 0) }
            }
            val statusView = TextView(context).apply {
                textSize = 13f
                setPadding(0, dp(6), 0, 0)
                visibility = View.GONE
            }
            card.addView(statusView)
            sendBtn.setOnClickListener {
                submitDetailBatch(batch, inputs, sendBtn, statusView)
            }
            card.addView(sendBtn)

            messageList.addView(card, lp)
            detailBatchCards[batch.runId] = card
            scrollToBottom()
        } catch (t: Throwable) {
            android.util.Log.e("FmDetailBatch", "card failed", t)
        }
    }

    /**
     * POINT 28: line me kaam ho to chat entry par ek baar bubble.
     * (Har entry par nahi — queue signature badle tabhi.)
     */
    private var lastQueueSig: String = ""
    private fun checkWorkQueue() {
        Thread({
            try {
                val sig = com.formmitra.app.engine.FormRunService
                    .queueSignature(context)
                if (sig.isEmpty() || sig == lastQueueSig) return@Thread
                lastQueueSig = sig
                val q = com.formmitra.app.engine.FormRunService
                    .queueSnapshot(context)
                if (q.isEmpty()) return@Thread
                val names = q.take(3).joinToString(", ") { "\"${it.name}\"" }
                val more = if (q.size > 3) " +${q.size - 3} aur" else ""
                val active = com.formmitra.app.engine.FormRunService
                    .activeTaskId
                post {
                    addBubble(
                        "📋 Line me ${q.size} kaam: $names$more\n" +
                            (if (active != null)
                                "Pehla khatam hote hi apne aap shuru honge."
                             else "Jald shuru honge."),
                        false
                    )
                    scrollToBottom()
                }
            } catch (_: Exception) { }
        }, "fm-queue-check").start()
    }

    /**
     * POINT 25: tracking action-offer card — "Naya certificate nikalun?
     * [Haan, shuru karo]". Haan → POST /api/agent/runs {goal, url} (koi
     * auto-run nahi — sirf user ke tap par).
     */
    private fun addTrackOfferCard(offer: TrackOffer.Offer) {
        try {
            if (trackOfferCards.containsKey(offer.offerId)) return
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                val d = GradientDrawable()
                d.setColor(Color.parseColor("#E8F5E9"))
                d.setStroke(dp(2), Color.parseColor("#2E9E5B"))
                d.cornerRadius = dp(14).toFloat()
                background = d
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            val lp = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) }

            card.addView(TextView(context).apply {
                text = TrackOfferPolicy.cardText(
                    offer.title, offer.detail, offer.question
                )
                textSize = 15f
                setTextColor(Color.parseColor("#202124"))
            })

            val btnRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            val acceptBtn = Button(context).apply {
                text = TrackOfferPolicy.acceptLabel()
                textSize = 14f
                setTextColor(Color.WHITE)
                val d = GradientDrawable()
                d.setColor(Color.parseColor("#2E9E5B"))
                d.cornerRadius = dp(10).toFloat()
                background = d
            }
            val declineBtn = Button(context).apply {
                text = TrackOfferPolicy.declineLabel()
                textSize = 14f
                layoutParams = LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(8) }
            }
            acceptBtn.setOnClickListener {
                acceptBtn.isEnabled = false
                declineBtn.isEnabled = false
                toast("🚀 Kaam shuru kar raha hun…")
                Thread({
                    val (runId, code, err) = try {
                        AgentApi.startActionRun(context, offer.goal, offer.url)
                    } catch (_: Exception) { Triple(null, -1, "Kaam shuru nahi ho paya — dobara try karo") }
                    post {
                        try {
                            messageList.removeView(card)
                            trackOfferCards.remove(offer.offerId)
                        } catch (_: Exception) { }
                        TrackOffer.dismissUnseen(context, offer.offerId)
                        if (runId != null) {
                            addBubble(TrackOfferPolicy.startedText(), false)
                            VoiceOutput.speak(context, "Kaam shuru ho gaya.")
                        } else if (code == 409) {
                            // v36 (point 10): 1-active limit — EXACT rejection
                            // + [Chal raha kaam dekho] / [Band karo] (user-dictated).
                            addOneActiveRejectionCard()
                        } else {
                            addBubble(err ?: TrackOfferPolicy.failedText(), false)
                            toast(err ?: TrackOfferPolicy.failedText())
                        }
                        scrollToBottom()
                    }
                }, "track-offer-accept").start()
            }
            declineBtn.setOnClickListener {
                try {
                    messageList.removeView(card)
                    trackOfferCards.remove(offer.offerId)
                } catch (_: Exception) { }
                TrackOffer.dismissUnseen(context, offer.offerId)
                toast("Theek hai — offer hata diya")
            }
            btnRow.addView(acceptBtn)
            btnRow.addView(declineBtn)
            card.addView(btnRow)

            messageList.addView(card, lp)
            trackOfferCards[offer.offerId] = card
            scrollToBottom()
        } catch (t: Throwable) {
            android.util.Log.e("FmTrackOffer", "card failed", t)
        }
    }

    /**
     * v36 (point 10): 1-active limit rejection card — chat me EXACT message
     * + user-dictated actions. Labels/message paraphrase NAHI.
     *
     * "Pehla kaam poora karo ya band karo, phir naya shuru karo."
     * [Chal raha kaam dekho] → active run ka live view kholo.
     * [Band karo] → active run turant roko (slot free).
     */
    private fun addOneActiveRejectionCard() {
        try {
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                val d = GradientDrawable()
                d.setColor(Color.parseColor("#FFF8E1"))
                d.setStroke(dp(2), Color.parseColor("#F9A825"))
                d.cornerRadius = dp(14).toFloat()
                background = d
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            val lp = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
            card.addView(TextView(context).apply {
                text = "⏳ Pehla kaam poora karo ya band karo, phir naya shuru karo."
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#202124"))
                setPadding(0, 0, 0, dp(10))
            })
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
            }
            val dekhoBtn = Button(context).apply {
                text = "Chal raha kaam dekho"
                textSize = 14f
                minimumWidth = 0
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                    .apply { rightMargin = dp(8) }
                setOnClickListener {
                    try {
                        val i = android.content.Intent(
                            context,
                            com.formmitra.app.agent.OperatorView::class.java
                        )
                        com.formmitra.app.engine.FormRunService.activeTaskId
                            ?.let { i.putExtra("run_id", it) }
                        context.startActivity(i)
                    } catch (_: Exception) {
                        toast("⚠️ Live view nahi khul paya — dobara try karo")
                    }
                }
            }
            val bandKaroBtn = Button(context).apply {
                text = "Band karo"
                textSize = 14f
                minimumWidth = 0
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    try {
                        com.formmitra.app.engine.FormRunService
                            .requestCancelActive(context)
                        addBubble(
                            "Chal raha kaam band kar diya ⏹️ — ab naya kaam shuru kar sakte ho.",
                            false
                        )
                        try {
                            messageList.removeView(card)
                        } catch (_: Exception) { }
                    } catch (_: Exception) {
                        toast("⚠️ Band nahi ho paya — dobara try karo")
                    }
                    scrollToBottom()
                }
            }
            row.addView(dekhoBtn)
            row.addView(bandKaroBtn)
            card.addView(row)
            messageList.addView(card, lp)
            scrollToBottom()
            try {
                VoiceOutput.speak(
                    context,
                    "Ek kaam pehle se chal raha hai. Pehla kaam poora karo ya band karo, phir naya shuru karo."
                )
            } catch (_: Exception) { }
        } catch (t: Throwable) {
            android.util.Log.e("FmOneActive", "rejection card failed", t)
            addBubble("⏳ Pehla kaam poora karo ya band karo, phir naya shuru karo.", false)
        }
    }

    // ================= POINTS 17 / 20 / 21 / 22 =================

    /** POINT 17: pending handoffs → "Iska status track karu?" cards. */
    private fun checkTrackHandoff() {
        Thread({
            try {
                val pend = TrackHandoffStore.takePending(context)
                if (pend.isNotEmpty()) {
                    post { pend.forEach { addTrackHandoffCard(it) } }
                }
            } catch (_: Exception) { }
        }, "fm-handoff-check").start()
    }

    private fun addTrackHandoffCard(h: TrackHandoffStore.Handoff) {
        try {
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                val d = GradientDrawable()
                d.setColor(Color.parseColor("#E3F2FD"))
                d.setStroke(dp(2), Color.parseColor("#1E88E5"))
                d.cornerRadius = dp(14).toFloat()
                background = d
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            val lp = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
            card.addView(TextView(context).apply {
                text = com.formmitra.app.engine.TrackHandoffPolicy
                    .cardText(h.workName)
                textSize = 15f
                setTextColor(Color.parseColor("#202124"))
            })
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            val yesBtn = Button(context).apply {
                text = com.formmitra.app.engine.TrackHandoffPolicy.acceptLabel()
                textSize = 14f
                setTextColor(Color.WHITE)
                val d = GradientDrawable()
                d.setColor(Color.parseColor("#1E88E5"))
                d.cornerRadius = dp(10).toFloat()
                background = d
            }
            val noBtn = Button(context).apply {
                text = com.formmitra.app.engine.TrackHandoffPolicy.declineLabel()
                textSize = 14f
                layoutParams = LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(8) }
            }
            yesBtn.setOnClickListener {
                yesBtn.isEnabled = false
                noBtn.isEnabled = false
                toast("🔍 Tracking shuru kar raha hun…")
                Thread({
                    // Server-provided auto-detected category: label se type
                    // (server jo type bhejta hai wahi store hota hai).
                    val type = com.formmitra.app.engine.TrackHandoffPolicy
                        .detectType(h.workName)
                    val (code, tid) = try {
                        AgentApi.createTracking(context, h.workName, type)
                    } catch (_: Exception) { -1 to null }
                    post {
                        try { messageList.removeView(card) } catch (_: Exception) { }
                        if (code in 200..299 && tid != null) {
                            val typeLabel =
                                WorkCategories.trackLabelOf(type)
                            addBubble(
                                com.formmitra.app.engine.TrackHandoffPolicy
                                    .trackedText(typeLabel),
                                false
                            )
                            VoiceOutput.speak(context, "Tracking shuru ho gayi.")
                        } else {
                            addBubble(
                                com.formmitra.app.engine.TrackHandoffPolicy
                                    .failedText(),
                                false
                            )
                        }
                        scrollToBottom()
                    }
                }, "fm-handoff-create").start()
            }
            noBtn.setOnClickListener {
                try { messageList.removeView(card) } catch (_: Exception) { }
                toast("Theek hai — tracking nahi ki")
            }
            row.addView(yesBtn)
            row.addView(noBtn)
            card.addView(row)
            messageList.addView(card, lp)
            scrollToBottom()
        } catch (t: Throwable) {
            android.util.Log.e("FmHandoff", "card failed", t)
        }
    }

    /** POINT 21: pending run summaries → end-of-work summary cards. */
    private fun checkRunSummaries() {
        Thread({
            try {
                val pend = RunSummaryStore.takePending(context)
                if (pend.isNotEmpty()) {
                    post { pend.forEach { addRunSummaryCard(it) } }
                }
            } catch (_: Exception) { }
        }, "fm-summary-check").start()
    }

    private fun addRunSummaryCard(s: RunSummaryStore.Summary) {
        try {
            val ok = s.status == "done"
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                val d = GradientDrawable()
                d.setColor(
                    Color.parseColor(if (ok) "#E8F5E9" else "#FFF3E0")
                )
                d.setStroke(
                    dp(2),
                    Color.parseColor(if (ok) "#2E9E5B" else "#EF6C00")
                )
                d.cornerRadius = dp(14).toFloat()
                background = d
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            val lp = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
            val sb = StringBuilder()
            sb.append(if (ok) "✅ Kaam poora: " else "⚠️ Kaam atka: ")
                .append(s.workName).append("\n\n")
            sb.append("✓ Ho gaya: ").append(s.doneText).append("\n")
            sb.append("⏳ Baaki: ").append(s.pendingText).append("\n")
            sb.append("👉 Tumhara agla kadam: ").append(s.nextAction)
            if (s.proofCount > 0) {
                sb.append("\n📸 Proof: ${s.proofCount} screenshots (History me dekho)")
            }
            card.addView(TextView(context).apply {
                text = sb.toString()
                textSize = 14f
                setTextColor(Color.parseColor("#202124"))
            })
            messageList.addView(card, lp)
            scrollToBottom()
        } catch (t: Throwable) {
            android.util.Log.e("FmSummary", "card failed", t)
        }
    }

    /** POINT 22: Card lock → one-tap unlock card (resume apne aap). */
    private fun addCardUnlockCard(need: CardUnlockNeeded.Need) {
        try {
            val key = "${need.runId}:${need.cardId}"
            if (cardUnlockCards.containsKey(key)) return
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                val d = GradientDrawable()
                d.setColor(Color.parseColor("#FFF8E1"))
                d.setStroke(dp(2), Color.parseColor("#FFB300"))
                d.cornerRadius = dp(14).toFloat()
                background = d
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            val lp = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
            card.addView(TextView(context).apply {
                text = "🔒 \"${need.taskName}\" ke beech Card lock ho gaya.\n\n" +
                    "Ek tap me kholo — kaam apne aap wahi se aage badhega, " +
                    "dobara shuru nahi hoga."
                textSize = 15f
                setTextColor(Color.parseColor("#202124"))
            })
            val openBtn = Button(context).apply {
                text = "🔓 Kholo aur resume karo"
                textSize = 14f
                setTextColor(Color.WHITE)
                val d = GradientDrawable()
                d.setColor(Color.parseColor("#2E9E5B"))
                d.cornerRadius = dp(10).toFloat()
                background = d
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(10), 0, 0) }
            }
            openBtn.setOnClickListener {
                openBtn.isEnabled = false
                val act = context as? Activity ?: return@setOnClickListener
                val cardJson = org.json.JSONObject()
                    .put("id", need.cardId)
                    .put("name", need.cardName)
                CardFlow.askPinAndUnlock(
                    act, cardJson,
                    onUnlocked = { _, cid, _, _ ->
                        CardUnlockNeeded.resolved(context, need.runId, need.cardId)
                        try { messageList.removeView(card) } catch (_: Exception) { }
                        cardUnlockCards.remove(key)
                        addBubble(
                            "🔓 Card khul gaya — kaam resume ho raha hai…",
                            false
                        )
                        // Resume: server run re-queue + wake (restart nahi).
                        Thread({
                            try {
                                AgentApi.runNow(context, need.runId)
                            } catch (_: Exception) { }
                            try {
                                com.formmitra.app.WakeWorker.enqueue(context)
                            } catch (_: Exception) { }
                        }, "fm-unlock-resume").start()
                        scrollToBottom()
                    },
                    onCreateNew = null
                )
                openBtn.isEnabled = true
            }
            card.addView(openBtn)
            messageList.addView(card, lp)
            cardUnlockCards[key] = card
            scrollToBottom()
        } catch (t: Throwable) {
            android.util.Log.e("FmCardUnlock", "card failed", t)
        }
    }

    /**
     * POINT 20: mid-run correction — active run ho aur message correction
     * lage to CorrectionStore me dalo, loop agle act() me apply karega.
     * Same run continue — STOP se alag. @return true = handle ho gaya.
     */
    private fun handleMidRunCorrection(text: String): Boolean {
        val runId =
            com.formmitra.app.engine.FormRunService.activeTaskId ?: return false
        if (!com.formmitra.app.engine.CorrectionPolicy.isCorrection(text)) {
            return false
        }
        val candidates = try {
            DetailExtractor.extract(text)
        } catch (_: Exception) { emptyMap() }
        if (candidates.isEmpty()) {
            post {
                addBubble(
                    "📝 Kaunsa field galat hai? Aise likho:\n" +
                        "\"mobile number galat hai, sahi 98765XXXXX hai\"",
                    false
                )
                scrollToBottom()
            }
            return true
        }
        for ((field, value) in candidates) {
            val label = try {
                DetailExtractor.label(field)
            } catch (_: Exception) { field }
            try {
                CorrectionStore.add(context, runId, field, label, value)
            } catch (_: Exception) { }
            post {
                addBubble(
                    com.formmitra.app.engine.CorrectionPolicy.notedText(label),
                    false
                )
                scrollToBottom()
            }
        }
        return true
    }

    /**
     * Batch submit: DetailStore (local) + server Card auto-save (tag ke
     * saath) + "save ho gaya" verify + run resume.
     */
    private fun submitDetailBatch(
        batch: DetailBatchStore.Batch,
        inputs: Map<String, EditText>,
        sendBtn: Button,
        statusView: TextView
    ) {
        val answers = LinkedHashMap<String, String>()
        for ((k, et) in inputs) {
            val v = et.text.toString().trim()
            if (v.isNotEmpty()) answers[k] = v
        }
        if (answers.isEmpty()) {
            toast("Kuch to bharo ✍️ — khaali nahi bhej sakte")
            return
        }
        sendBtn.isEnabled = false
        sendBtn.text = "⏳ Bhej rahe hain…"
        Thread {
            var savedLocal = false
            var savedCard = false
            // (1) Local: DetailStore (agle auto-fill ke liye)
            try {
                DetailStore.saveAll(context, answers)
                savedLocal = true
            } catch (_: Exception) { }
            // (2) Server: selected Card me auto-save (tag ke saath).
            // Server tagging karta hai; app "save ho gaya" verify dikhata hai.
            try {
                val cardId = AgentApi.automationCardId
                val token = AgentApi.automationCardToken
                if (!cardId.isNullOrEmpty() && !token.isNullOrEmpty()) {
                    val details = JSONObject()
                    for ((k, v) in answers) {
                        // Tag: agent se aaya detail (server tag karega)
                        details.put(k, JSONObject().put("value", v).put("tag", "agent"))
                    }
                    val res = AgentApi.patchCard(context, cardId, token, details)
                    savedCard = res.code in 200..299
                }
            } catch (_: Exception) { }
            // (3) Batch me answers persist (audit) + pending se hatao
            try {
                DetailBatchStore.saveAnswers(context, batch.runId, answers)
            } catch (_: Exception) { }
            post {
                statusView.visibility = View.VISIBLE
                statusView.text = when {
                    savedLocal && savedCard -> "✅ Save ho gaya — Card me bhi jod diya"
                    savedLocal -> "✅ Save ho gaya (Card me jodne me dikkat — baad me try karega)"
                    else -> "⚠️ Save nahi ho paya — dobara try karo"
                }
                statusView.setTextColor(
                    Color.parseColor(if (savedLocal) "#2E7D32" else "#C62828")
                )
                if (savedLocal) {
                    sendBtn.text = "✅ Bhej diya"
                    // Card hatao (thodi der me) + run resume trigger
                    postDelayed({
                        try {
                            messageList.removeView(detailBatchCards.remove(batch.runId))
                        } catch (_: Exception) { }
                    }, 3000)
                    resumeAfterDetails(batch.runId)
                } else {
                    sendBtn.isEnabled = true
                    sendBtn.text = "Bhejo ➤"
                }
            }
        }.start()
    }

    /**
     * Jawab mil gaya — parked run wapas shuru karo (usi step se).
     * AgentResume me step saved hai; WakeWorker usi se resume karega.
     */
    private fun resumeAfterDetails(runId: String) {
        Thread {
            try {
                // DetailBatchStore already answered mark kar chuka hai
                // (saveAnswers me). Ab run resume karo.
                val pending = com.formmitra.app.engine.AgentResume.checkPending(context)
                if (pending != null) {
                    // WakeWorker turant chalao — usi step se resume
                    try {
                        com.formmitra.app.WakeWorker.enqueue(context)
                    } catch (_: Exception) {
                        // Fallback: seedha FormRunService se resume
                        try {
                            val task = JSONObject()
                                .put("name", pending.goal)
                                .put("target_url", pending.url)
                                .put("run_id", pending.runId)
                                .put(
                                    "steps",
                                    org.json.JSONArray().put(
                                        JSONObject()
                                            .put("type", "agent_run")
                                            .put("goal", pending.goal)
                                            .put("url", pending.url)
                                            .put("start_step", pending.stepsTaken)
                                    )
                                )
                            com.formmitra.app.engine.FormRunService.startWithTask(
                                context, task
                            )
                        } catch (_: Exception) { }
                    }
                    post {
                        addAssistantBubble(
                            "✅ Details mil gayin — kaam wahi se aage badh raha hai jahan ruka tha. 🔄"
                        )
                    }
                } else {
                    post {
                        addAssistantBubble(
                            "✅ Details save ho gayin. Kaam jald shuru hoga."
                        )
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("FmDetailBatch", "resume failed", t)
            }
        }.start()
    }

    private fun onStartPlanClicked(plan: JSONObject, link: String, startBtn: Button?) {
        // v28 P12: tap par koi crash nahi — kuch gadbad ho to popup.
        // v35: generic toast nahi — ErrorCatcher me asli wajah + copy.
        try {
            startBtn?.isEnabled = false
            startBtn?.text = "⏳ Task ban raha hai…"
            // Seedha task banao — koi Proceed confirmation nahi.
            // Details server ke vault profile + chat history se aati hain;
            // galat hui to run ke beech detail-request loop (K4) maang lega.
            beginEnqueue(plan, link, startBtn)
        } catch (t: Throwable) {
            android.util.Log.e("FmStart", "onStartPlanClicked failed", t)
            try {
                com.formmitra.app.engine.ErrorCatcher.show(
                    context, "Kaam shuru karte waqt", t,
                    workName = try { plan.optString("title", "Form") } catch (_: Exception) { "" },
                    sessionId = "ui" + System.currentTimeMillis().toString(36)
                )
            } catch (_: Exception) {
                toast("⚠️ Shuru nahi ho paya — dobara try karo")
            }
            post { startBtn?.text = "▶ Shuru karo"; startBtn?.isEnabled = true }
        }
    }

    private fun beginEnqueue(plan: JSONObject, link: String, startBtn: Button?) {
        startBtn?.text = "⏳ Task ban raha hai…"
        try {
        enqueueTask(
            title = plan.optString("title", "Form"),
            url = link,
            // A: category task me jayegi → AgentLoop ke har act() me
            category = activeCategory.orEmpty(),
            // v29 P1: known details + asked keys task me → FormRunService →
            // AgentLoop ke har act() call me (server dobara sawaal na poochhe)
            knownDetails = LinkedHashMap<String, String>(cardCachedDetails)
                .also { it.putAll(sessionDetails) },
            askedAlready = askedKeys.toList(),
            onDone = { post { startBtn?.text = "▶ Shuru karo"; startBtn?.isEnabled = true } }
        )
        } catch (t: Throwable) {
            android.util.Log.e("FmStart", "beginEnqueue failed", t)
            try {
                com.formmitra.app.engine.ErrorCatcher.show(
                    context, "Kaam shuru karte waqt", t,
                    workName = try { plan.optString("title", "Form") } catch (_: Exception) { "" },
                    sessionId = "ui" + System.currentTimeMillis().toString(36)
                )
            } catch (_: Exception) {
                toast("⚠️ Shuru nahi ho paya — dobara try karo")
            }
            post { startBtn?.text = "▶ Shuru karo"; startBtn?.isEnabled = true }
        }
    }

    // ---------- flow ----------

    /**
     * PERMANENT FULL APPROVAL (2026-09-25): message me nayi personal details
     * dikhin to seedha session me rakho aur bhejo — koi verify/Proceed gate
     * nahi. Galat hui to user Profile me theek kar sakta hai.
     */
    fun sendMessage(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || waiting) return
        // POINT 20: mid-run correction — active run ho aur user kahe field
        // galat hai to loop me bhejo, same run continue (STOP se alag).
        if (handleMidRunCorrection(text)) return
        // POINT 18: destructive kaam (cancel/withdraw/delete/account-close)
        // HAMESHA confirmation — permanent automation approval ise bypass
        // NAHI kar sakta. "Haan karo" par hi aage badho.
        if (DestructivePolicy.isDestructive(text)) {
            val act = context as? Activity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                // User ka message pehle dikhao, phir gate.
                try { addUserBubble(text) } catch (_: Exception) { }
                DestructiveGate.confirm(act, text.take(80)) {
                    proceedAfterDestructiveConfirm(text, userBubbleShown = true)
                }
                return
            }
            // Activity nahi mili to safe side: ruko, user ko batao.
            post {
                addBubble(
                    "⚠️ Ye kaam permanent ho sakta hai — chat khula hone par " +
                        "dobara bolo, tab confirm karunga.",
                    false
                )
                scrollToBottom()
            }
            return
        }
        proceedAfterDestructiveConfirm(text)
    }

    /**
     * POINT 18: destructive confirm ke baad (ya non-destructive) ka normal
     * chat flow. Pehle yehi sendMessage ka body tha.
     */
    private fun proceedAfterDestructiveConfirm(
        text: String,
        userBubbleShown: Boolean = false
    ) {
        val candidates = DetailExtractor.extract(text)
        // v24-refine (AI-trained agent): sudhar chipke — purana value galat
        // tha (validate fail) ya naya value sahi hai → update karo. Nahi to
        // card draft me galat value hi chipki rehti ("correction-stick").
        val fresh = LinkedHashMap<String, String>()
        for ((k, v) in candidates) {
            val old = sessionDetails[k]
            if (old == null) {
                fresh[k] = v
            } else if (old != v &&
                (CardValidation.validateField(k, old) != null ||
                    CardValidation.validateField(k, v) == null)
            ) {
                fresh[k] = v
            }
        }
        if (fresh.isNotEmpty()) {
            sessionDetails.putAll(fresh)
            // v29 P1: user ne in keys ka jawab de diya → askedKeys se hatao
            // (server inhe dobara nahi poochhega).
            if (askedKeys.isNotEmpty()) askedKeys.removeAll(fresh.keys)
            // v29 P1c: jawab aate hi TURANT memory me — koi delay/queue nahi;
            // rotation/kill par bhi bacha rahe.
            persistWorkMemory()
            // v24-refine (AI-trained agent): card-create Q&A mode me turant
            // tok-sudhar — LOCAL validation, koi AI call nahi (quota bachat).
            // Ye sirf turant madad hai; message server ko bhi jayega (server
            // source of truth — wo bhi validate karega).
            if (cardCreateMode) {
                for ((k, v) in fresh) {
                    val err = CardValidation.validateField(k, v)
                    if (err != null) {
                        val label = DetailExtractor.label(k)
                        val tokMsg = "⚠️ $label theek nahi lag raha: $err\n\n" +
                            "Sahi karke dobara bhejo — main yahin hun 😊"
                        post {
                            addAssistantBubble(tokMsg)
                            try {
                                VoiceOutput.speak(
                                    context,
                                    "$label theek nahi lag raha. $err"
                                )
                            } catch (_: Exception) { }
                        }
                    }
                }
            }
            // v24 #4: user ne details di par koi active/selected card nahi —
            // details PendingDetails me SAFE (prefs, app kill par bhi), phir
            // create-card par redirect offer. Koi detail beech me ghumti nahi.
            if (activeCardId == null) {
                val tag = activeCategory ?: "chat"
                CardStore.pendingAddAll(context, fresh, tag)
                (context as? Activity)?.let { act ->
                    post {
                        CardFlow.offerCreateCardForDetails(
                            act, CardStore.pendingCount(context),
                            onAgentCreate = { prefill ->
                                onAgentCreateRequest?.invoke(prefill)
                            },
                            onCreated = { _, id, name, token ->
                                setActiveCard(id, name, token)
                            }
                        )
                    }
                }
            } else {
                // v29 P1.5 ROOT FIX: turant card me save — server ke
                // draft_profile echo ka wait NAHI. Pehle details sirf
                // session me rehti thin; server echo na aaye to card me
                // kabhi save hi nahi hoti thin ("show hota hai, save hua
                // show nahi hota").
                saveFreshToCard(fresh)
            }
        }
        // v28 P11: agent auto-detect → auto-save. KNOWN extraction ke BAAD
        // unknown labeled details (aadhar/PAN/voter/ration/...) dhoondo —
        // label + nontrivial value DONO hon tabhi (conservative) → tag ke
        // saath card me auto-save + confirmation bubble.
        // v28 P12: detector kabhi send flow ko tod na paye.
        try {
            val extras = ExtraDetailDetector.extractExtra(text)
            if (extras.isNotEmpty()) {
                autoSaveExtraDetails(extras)
            }
        } catch (_: Exception) { }
        doSend(text)
    }

    /**
     * v29 (P1.5 ROOT FIX): user ne nayi details di → TURANT active card me
     * PATCH (server echo ka wait nahi). Jo value card me pehle se wahi hai
     * use dobara nahi bhejte (noise nahi).
     *
     * v29 P2 (verify-after-write): PATCH ke baad storage se WAPAS padhkar
     * confirm hota hai (CardSaveVerifier) — tabhi "✓ save ho gaya".
     * Mismatch ho to LOUD error + pending me surakshit (khoyegi nahi) —
     * silent fail bilkul nahi.
     */
    private fun saveFreshToCard(fresh: Map<String, String>) {
        try {
            val cid = activeCardId ?: return
            val tok = activeCardToken ?: CardStore.token(cid) ?: return
            val tag = activeCategory ?: "chat"
            // Sirf nayi/badli values — card me pehle se wahi ho to skip.
            val toSave = LinkedHashMap<String, String>()
            for ((k, v) in fresh) {
                val ck = TagRegistry.normalizeTag(k)
                if (ck.isNotEmpty() && v.isNotEmpty() && cardCachedDetails[ck] != v) {
                    toSave[ck] = v
                }
            }
            if (toSave.isEmpty()) return
            val snapshot = LinkedHashMap(toSave)
            Thread({
                // v29 P2: verify-after-write.
                val res = CardSaveVerifier.saveAndVerify(
                    patch = { details ->
                        try {
                            AgentApi.patchCard(context, cid, tok, details).code
                        } catch (_: Exception) { -1 }
                    },
                    reread = {
                        try {
                            AgentApi.cardDetail(context, cid, tok).json
                        } catch (_: Exception) { null }
                    },
                    toSave = snapshot,
                    tag = tag
                )
                post {
                    when (res) {
                        is CardSaveVerifier.Result.Verified -> {
                            // Cache sync — dobara wahi PATCH na jaye.
                            cardCachedDetails.putAll(snapshot)
                            persistWorkMemory()
                            val names =
                                snapshot.keys.map { TagRegistry.labelOf(it) }
                            toast("✓ Card me save ho gaya: ${names.joinToString(", ")}")
                            // POINT 24: har auto-write history/note me dikhe
                            // (toast gayab ho jata hai — bubble rehta hai).
                            addAssistantBubble(
                                "💾 Ye detail Card me save ho gayi: " +
                                    names.joinToString(", ")
                            )
                        }
                        else -> {
                            // Fail/mismatch → pending me surakshit + LOUD error.
                            CardStore.pendingAddAll(context, snapshot, tag)
                            toast(
                                "❌ Card me SAVE NAHI HUA — " +
                                    "${CardSaveVerifier.loudReason(res)}. " +
                                    "Details surakshit hain, baad me phir try hogi."
                            )
                        }
                    }
                }
            }, "fm-fresh-save").start()
        } catch (t: Throwable) {
            android.util.Log.e("FmFresh", "saveFreshToCard failed", t)
        }
    }

    /**
     * L2: chat draft persistence — adhura likha message app band/crash
     * hone par bhi bacha rahe. Send hote hi clear.
     */
    private fun draftPrefs() =
        context.getSharedPreferences("formmitra_chat", Context.MODE_PRIVATE)

    private fun saveDraft(text: String) {
        try {
            draftPrefs().edit().putString("chat_draft", text.take(2000)).apply()
        } catch (_: Exception) { }
    }

    private fun loadDraft(): String = try {
        draftPrefs().getString("chat_draft", "").orEmpty()
    } catch (_: Exception) { "" }

    private fun clearDraft() {
        try { draftPrefs().edit().remove("chat_draft").apply() } catch (_: Exception) { }
    }

    /** Asli send — gate se guzarne ke baad. Retry bhi yahi aata hai. */
    private fun doSend(text: String, userBubbleShown: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || waiting) return
        // v29 zero-crash gate: neeche ka sync UI section agar kahin throw
        // kare to `waiting` hamesha ke liye true na reh jaye (send button
        // dead). Isliye try/catch + reset.
        try {
            waiting = true
            lastFailedText = t
            input.text.clear()
            clearDraft()
            sendBtn.isEnabled = false
            // POINT 18: destructive gate par bubble pehle dikh chuka.
            if (!userBubbleShown) addUserBubble(t)
            history.add("user" to t)
            saveChatHistory() // v28 P6: per-category chat history persist
            showTyping()
        } catch (e: Exception) {
            waiting = false
            try { sendBtn.isEnabled = true } catch (_: Exception) { }
            toast("⚠️ Bhejne me dikkat — dobara try karo")
            android.util.Log.e("FmSend", "doSend sync section failed", e)
            return
        }
        // Watchdog: 75s me jawab na aaye to stuck state todo + retry do
        val token = ++sendToken
        sendWatchdog.removeCallbacksAndMessages(null)
        sendWatchdog.postDelayed({
            if (waiting && token == sendToken) {
                waiting = false
                sendBtn.isEnabled = true
                hideTyping()
                addErrorBubble(
                    "⏳ Jawab aane me der ho rahi hai.",
                    "🔁 Dobara bhejo"
                ) { lastFailedText?.let { doSend(it) } }
            }
        }, 75_000)
        Thread {
            val res = try {
                // v20 Task 5: active category ho to body me `category` bhejo
                // v24: active card ho to body me `card_id` + X-Card-Token
                // v28: track me `tracking_type` (8 types ka context) bhi jayega
                // v29 P1: `known_details` (card cache + session merged —
                // server dobara wahi sawaal na poochhe) + `asked_already`
                // (is work me pehle poochhe gaye keys).
                val known = LinkedHashMap<String, String>(cardCachedDetails)
                known.putAll(sessionDetails)
                AgentApi.chat(
                    context, history.toList(), activeCategory,
                    activeCardId, activeCardToken, activeTrackingType,
                    knownDetails = known,
                    askedAlready = askedKeys.toList()
                )
            } catch (_: Exception) {
                AgentApi.ApiResult(-1, null)
            }
            post {
                if (token != sendToken) return@post // purana watchdog token
                sendWatchdog.removeCallbacksAndMessages(null)
                hideTyping()
                waiting = false
                sendBtn.isEnabled = true
                when (res.code) {
                    -1 -> addErrorBubble(
                        "📡 Internet nahi lag raha — message nahi gaya.",
                        "🔁 Dobara bhejo"
                    ) { lastFailedText?.let { doSend(it) } }
                    401 -> addErrorBubble(
                        "🔑 Pehle login karna hoga — tabhi agent baat karega.",
                        "🔑 Login karo"
                    ) { onOpenProfile() }
                    429 -> {
                        addAssistantBubble("Aaj ka limit khatam, kal try karo ⏳")
                        // K1: status/limit change — local fallback notification.
                        try {
                            NotifCenter.notify(
                                context, NotifCenter.Cat.STATUS,
                                "⏳ Aaj ka limit khatam",
                                "Agent ka daily limit poora ho gaya — kal phir try karo.",
                                deepTab = "/agent"
                            )
                        } catch (_: Exception) { }
                    }
                    200 -> {
                        lastFailedText = null
                        val json = res.json
                        val reply = json?.optString("reply", "")?.trim().orEmpty()
                        if (reply.isNotEmpty()) {
                            addAssistantBubble(reply)
                            history.add("assistant" to reply)
                            saveChatHistory() // v28 P6
                            VoiceOutput.speak(context, reply)
                        }
                        val plan = json?.optJSONObject("plan")
                        if (plan != null) {
                            addPlanCard(plan)
                            // A: category flow — plan aate hi start flow khud
                            // kholo (koi Proceed nahi). Normal chat me nahi.
                            if (autoPlanArmed) {
                                autoPlanArmed = false
                                onStartPlanClicked(
                                    plan, plan.optString("official_link", ""), null
                                )
                            }
                        }
                        val missing = json?.optJSONArray("missing_docs")
                        if (missing != null && missing.length() > 0) {
                            val names = (0 until missing.length())
                                .map { missing.optString(it, "").trim() }
                                .filter { it.isNotEmpty() }
                            if (names.isNotEmpty()) {
                                addAssistantBubble(
                                    "📎 Ye docs 📎 button se kabhi bhi bhej sakte ho: " +
                                        names.joinToString(", ")
                                )
                            }
                        }
                        // v29 P1: server ne in keys ke liye poocha → askedKeys
                        // me jodo (agla request inhe `asked_already` me
                        // bhejega — server dobara nahi poochhega).
                        try {
                            val askedArr = json?.optJSONArray("asked_for")
                            if (askedArr != null) {
                                for (i in 0 until askedArr.length()) {
                                    val k = askedArr.optString(i, "").trim()
                                    if (k.isNotEmpty()) {
                                        askedKeys.add(TagRegistry.normalizeTag(k))
                                    }
                                }
                                // v29 P1c: asked-keys bhi turant persist —
                                // rotation par dedupe state na khoye.
                                persistWorkMemory()
                            }
                        } catch (_: Exception) { }
                        if (reply.isEmpty() && plan == null) {
                            addErrorBubble(
                                "🤖 Jawab khaali aaya.",
                                "🔁 Dobara bhejo"
                            ) { lastFailedText?.let { doSend(it) } }
                        }
                        // v24: work details CARD me jayengi — purane profile store
                        // (AgentApi.saveProfile) me NAHI. Rules:
                        //  - active unlocked card ho → PATCH /api/cards/[id]
                        //    (tag = category, fail ho to bhi details chat
                        //    session me surakshit — khoyengi nahi).
                        //  - koi card nahi → pending me rakho + create-card
                        //    par bhejo (#4). Card system down (503) ho tab
                        //    bhi chat nahi rukegi — details pending me safe.
                        val draftObj = json?.optJSONObject("draft_profile")
                        if (json?.optBoolean("needs_confirmation", false) == true &&
                            draftObj != null && draftObj.length() > 0
                        ) {
                            val draftMap = LinkedHashMap<String, String>()
                            for (k in DetailExtractor.orderedKeys()) {
                                val v = draftObj.optString(k, "").trim()
                                if (v.isNotEmpty() && v != "null") draftMap[k] = v
                            }
                            val extraKeys = draftObj.keys()
                            while (extraKeys.hasNext()) {
                                val k = extraKeys.next()
                                if (!draftMap.containsKey(k)) {
                                    val v = draftObj.optString(k, "").trim()
                                    if (v.isNotEmpty() && v != "null") {
                                        // v29 P4: canonical key par lao.
                                        draftMap[TagRegistry.normalizeTag(k)] = v
                                    }
                                }
                            }
                            if (draftMap.isNotEmpty()) {
                                sessionDetails.putAll(draftMap)
                                // v29 P1c: draft wali details bhi turant
                                // memory me (rotation par na khoyen).
                                persistWorkMemory()
                                // v29 P1.5: jo card me pehle se wahi value hai
                                // use dobara mat bhejo (saveFreshToCard turant
                                // save kar chuka hoga) — double-PATCH noise nahi.
                                val newDraft = LinkedHashMap<String, String>()
                                for ((k, v) in draftMap) {
                                    if (cardCachedDetails[k] != v) newDraft[k] = v
                                }
                                val tag = activeCategory ?: "chat"
                                val cid = activeCardId
                                val tok = activeCardToken
                                    ?: cid?.let { CardStore.token(it) }
                                if (newDraft.isEmpty()) {
                                    // Sab pehle se card me saved — kuch nahi karna.
                                } else if (cid != null && !tok.isNullOrEmpty()) {
                                    val snapshot = LinkedHashMap(newDraft)
                                    Thread {
                                        // v29 P2: verify-after-write — "chat me
                                        // dikhna ≠ saved hona". Draft wali
                                        // details bhi re-read se confirm.
                                        val res = CardSaveVerifier.saveAndVerify(
                                            patch = { details ->
                                                try {
                                                    AgentApi.patchCard(context, cid, tok, details).code
                                                } catch (_: Exception) { -1 }
                                            },
                                            reread = {
                                                try {
                                                    AgentApi.cardDetail(context, cid, tok).json
                                                } catch (_: Exception) { null }
                                            },
                                            toSave = snapshot,
                                            tag = tag
                                        )
                                        post {
                                            when (res) {
                                                is CardSaveVerifier.Result.Verified -> {
                                                    cardCachedDetails.putAll(snapshot)
                                                    persistWorkMemory()
                                                    toast("✓ Details card me save ho gayi")
                                                    // POINT 24: auto-write
                                                    // history/note me dikhe.
                                                    val nm2 = snapshot.keys.joinToString(", ") {
                                                        TagRegistry.labelOf(it)
                                                    }
                                                    addAssistantBubble(
                                                        "💾 Ye detail Card me save ho gayi: $nm2"
                                                    )
                                                }
                                                else -> {
                                                    CardStore.pendingAddAll(context, snapshot, tag)
                                                    toast(
                                                        "❌ Card me SAVE NAHI HUA — " +
                                                            "${CardSaveVerifier.loudReason(res)} — " +
                                                            "details surakshit hain, baad me phir try hogi"
                                                    )
                                                }
                                            }
                                        }
                                    }.start()
                                } else {
                                    // #4: card nahi → pending (khoyengi nahi)
                                    // + create-card redirect.
                                    CardStore.pendingAddAll(context, newDraft, tag)
                                    post {
                                        val act = context as? Activity
                                        if (act != null) {
                                            CardFlow.offerCreateCardForDetails(
                                                act, newDraft.size,
                                                onAgentCreate = { prefill ->
                                                    onAgentCreateRequest?.invoke(prefill)
                                                },
                                                onCreated = { _, id, name, token ->
                                                    setActiveCard(id, name, token)
                                                    // Naya card bana → pending
                                                    // details tag ke saath
                                                    // isi card me (auto).
                                                    CardFlow.flushPendingDetails(
                                                        act, id, token
                                                    )
                                                }
                                            )
                                        } else {
                                            toast(
                                                "🪪 Card banao — ${newDraft.size} " +
                                                    "details surakshit rakhi hain"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        val savedArr = json?.optJSONArray("saved")
                        if (savedArr != null && savedArr.length() > 0) {
                            toast("✓ Details save ho gayi")
                        }
                    }
                    else -> addErrorBubble(
                        "⚠️ Baat nahi ho payi — message nahi gaya.",
                        "🔁 Dobara bhejo"
                    ) { lastFailedText?.let { doSend(it) } }
                }
            }
        }.start()
    }

    // ---------- v20 Task 5: category context ----------
    // ---------- v28 P6/P7/P8: category system ----------

    /**
     * Home ke work-category card se: category context set karo + intro
     * message bhejo (koi verify gate nahi — seedha bhejta hai).
     *
     * v28 (P8): Home se naya kaam start = HAMESHA fresh session.
     *  - newSession=true → is category ki purani chat (memory + screen +
     *    saved `chat_history_<category>`) SAAPH; nayi shuruaat.
     *  - Purane kaam ka continuation SIRF Agent tab → 📜 Purane Kaam se (P7).
     * trackingType: track category me 8 track-types ka context (P3) — null
     * matlab aam track chat (server generic track prompt dega).
     */
    fun startCategoryChat(
        category: String,
        label: String,
        prefill: Map<String, String>,
        cardId: String,
        cardName: String,
        cardToken: String,
        trackingType: String? = null,
        newSession: Boolean = false
    ) {
        // v28 P12: Agent tab creation par koi crash nahi — fail-soft toast.
        try {
        activeCategory = category
        rememberCategory(category) // v28 P14
        activeTrackingType = trackingType
        rememberTrackingType(trackingType) // v41: history namespace ke liye
        setActiveCard(cardId, cardName, cardToken)
        if (newSession) {
            // P8: purani chat saaf — bacha hua message/context kuch nahi.
            history.clear()
            // v29 P1: purane work ki details/asked-keys naye work me na
            // ghulein — naya work = nayi shuruaat.
            sessionDetails.clear()
            askedKeys.clear()
            // v29 P1c (isolation): prefs me bachi purani memory bhi saaf —
            // naye work par purani memory leak NAHI hogi.
            // v41: namespace me trackingType bhi (track ke types alag).
            clearWorkMemory(workKeyFor(category, trackingType) ?: category)
            try {
                draftPrefs().edit().remove(histKey(category, trackingType)).apply()
            } catch (_: Exception) { }
            post { messageList.removeAllViews() }
        } else {
            // v29 P1c (no-loss): resume par is work ki memory wapas —
            // rotation/kill ke baad bhi user ko dobara details nahi deni.
            restoreWorkMemory(workKeyFor(category, trackingType) ?: category)
        }
        // v29 P1: card details cache refresh (prefill = card se aayi
        // details, canonical keys). Har request me known_details me jayengi.
        cardCachedDetails.clear()
        cardCachedDetails.putAll(prefill)
        // B: card details session me rakho — agent dobara na maange.
        // Ye intro message ke saath server ko bhi jati hain (history me).
        if (prefill.isNotEmpty()) sessionDetails.putAll(prefill)
        // A: is category message ke jawab me plan aaye to auto-start flow.
        autoPlanArmed = true
        val chipText = if (trackingType != null)
            "🔖 $label › ${WorkCategories.trackLabelOf(trackingType)}  ✕"
        else "🔖 $label  ✕"
        post {
            categoryChip.text = chipText
            categoryChip.visibility = View.VISIBLE
        }
        val sb = StringBuilder("🔖 $label — is kaam me meri madad karo.")
        if (trackingType != null) {
            sb.append("\n🔍 Track type: ")
                .append(WorkCategories.trackLabelOf(trackingType))
        }
        sb.append("\n🪪 Card: $cardName (is card ki details use karo)")
        if (prefill.isNotEmpty()) {
            sb.append("\nCard se mili details:")
            for ((k, v) in prefill) {
                sb.append("\n• ").append(DetailExtractor.label(k)).append(": ").append(v)
            }
        }
        // v24 #1: three-way coordination — stage tracking shuru.
        trackRunStage(label, cardName)
        sendMessage(sb.toString())
        } catch (t: Throwable) {
            android.util.Log.e("FmCat", "startCategoryChat failed", t)
            try {
                com.formmitra.app.engine.ErrorCatcher.show(
                    context, "Kaam khulne me", t,
                    workName = label,
                    sessionId = "cat" + System.currentTimeMillis().toString(36)
                )
            } catch (_: Exception) {
                toast("⚠️ Kaam khulne me dikkat aayi — dobara try karo")
            }
        }
    }

    /** Chip ka ✕ — category context hatao, aam chat par wapas. */
    fun clearCategory() {
        saveChatHistory()
        // v29 P1c: jaate-jaate memory persist — resume par wapas milegi.
        persistWorkMemory()
        activeCategory = null
        rememberCategory(null) // v28 P14
        activeTrackingType = null
        rememberTrackingType(null) // v41
        post { categoryChip.visibility = View.GONE }
        toast("Category hatayi — ab aam chat")
    }

    // ---------- v41: user apni chat history khud delete kar sake ----------

    /** 🗑️ button — is kaam ki chat history delete karne ka confirmation. */
    private fun confirmClearChat() {
        val act = context as? Activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        val cat = activeCategory
        if (cat == null) {
            toast("Pehle koi kaam chuno, phir uski chat saaf kar sakte ho")
            return
        }
        val label = WorkCategories.labelOf(cat)
        val typeLabel = activeTrackingType?.let { " › ${WorkCategories.trackLabelOf(it)}" } ?: ""
        AlertDialog.Builder(act)
            .setTitle("🗑️ Chat saaf karein?")
            .setMessage("🔖 $label$typeLabel — is kaam ki baat-cheet (screen + saved history + yaad ki hui details) delete ho jayegi. Ye wapas nahi aayegi.\n\nDoosre kaam ki chat ko kuch nahi hoga.")
            .setPositiveButton("Saaf karo") { d, _ ->
                d.dismiss()
                clearCurrentChat()
            }
            .setNegativeButton("Rehne do", null)
            .show()
    }

    /** Is kaam (category + track-type) ki chat + memory — screen aur prefs dono se. */
    private fun clearCurrentChat() {
        val cat = activeCategory ?: return
        val type = activeTrackingType
        val wk = workKeyFor(cat, type) ?: cat
        try {
            history.clear()
            sessionDetails.clear()
            askedKeys.clear()
            cardCachedDetails.clear()
            post { messageList.removeAllViews() }
            val e = draftPrefs().edit()
            e.remove(histKey(cat, type))
            e.remove(memKey(wk, "session"))
            e.remove(memKey(wk, "asked"))
            e.apply()
            // Khaali state persist — dobara purani memory wapas na aaye.
            persistWorkMemory()
            toast("🗑️ Chat saaf ho gayi")
        } catch (t: Throwable) {
            android.util.Log.e("FmChat", "clearCurrentChat failed", t)
            toast("⚠️ Saaf karne me dikkat — dobara try karo")
        }
    }

    // ---------- v28 P6: per-category chat history (local) ----------

    /** local history key: chat_history_<category>_<type> (v41: type joda —
     *  track ke 8 types (zameen/scholarship/...) ab ek key share NAHI karte,
     *  isliye chat history mix nahi hogi. */
    private fun histKey(cat: String, type: String?) = "chat_history_${cat}_${type ?: "main"}"

    /** Is category ki history prefs me save karo (max 200 messages). */
    private fun saveChatHistory() {
        val cat = activeCategory ?: return
        try {
            val arr = JSONArray()
            for ((role, content) in history.takeLast(200)) {
                arr.put(JSONObject().put("role", role).put("content", content))
            }
            draftPrefs().edit().putString(histKey(cat, activeTrackingType), arr.toString()).apply()
        } catch (_: Exception) { }
    }

    /** Saved history wapas lao + screen par render karo. */
    private fun loadChatHistory(cat: String, type: String?) {
        history.clear()
        post { messageList.removeAllViews() }
        try {
            val raw = draftPrefs().getString(histKey(cat, type), null) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = o.optString("role", "")
                val content = o.optString("content", "")
                if (content.isEmpty()) continue
                history.add(role to content)
                post {
                    if (role == "user") addUserBubble(content)
                    else addAssistantBubble(content)
                }
            }
        } catch (_: Exception) { }
    }

    // ---------- v29 P1c: per-work memory persistence (isolation + no-loss) ----------

    /**
     * v29 P1c ROOT FIX — do directions:
     *  (a) ISOLATION: naya work = saaf session. clearWorkMemory() naye
     *      work par purani memory (memory + prefs dono) mitata hai —
     *      purani memory naye work me leak NAHI hoti.
     *  (b) NO-LOSS: isi work ki memory rotation/kill/reopen par bhi
     *      rehti hai. Har mutation ke baad persistWorkMemory() turant
     *      prefs me likhta hai; resume par restoreWorkMemory() wapas
     *      lata hai.
     * Per-work namespace: "chat_mem_<work>_session" / "_asked".
     * work = category, ya card-create mode me "card_create".
     */
    private fun workKey(): String? {
        val cat = activeCategory ?: if (cardCreateMode) "card_create" else null
        return workKeyFor(cat, activeTrackingType)
    }

    /**
     * v41: track ke 8 types ka work-memory namespace alag-alag.
     * Pehle sab "track" me ghulte the (zameen ki details scholarship me
     * leak hoti thi) — ab "track_zameen", "track_scholarship", ...
     */
    private fun workKeyFor(cat: String?, type: String?): String? {
        if (cat == null) return null
        return if (cat == "track") "track_${type ?: "main"}" else cat
    }

    private fun memKey(work: String, field: String) = "chat_mem_${work}_$field"

    /** Har mutation ke baad turant call karo — koi delay/queue nahi. */
    private fun persistWorkMemory() {
        val work = workKey() ?: return
        try {
            val e = draftPrefs().edit()
            e.putString(
                memKey(work, "session"),
                JSONObject(sessionDetails as Map<*, *>).toString()
            )
            e.putString(memKey(work, "asked"), askedKeys.joinToString(","))
            e.apply()
        } catch (_: Exception) { }
    }

    /** Resume path — prefs se is work ki memory wapas. */
    private fun restoreWorkMemory(work: String) {
        try {
            sessionDetails.clear()
            val raw = draftPrefs().getString(memKey(work, "session"), null)
            if (raw != null) {
                val o = JSONObject(raw)
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = o.optString(k, "")
                    // v29 P4: restore par keys canonicalize — purani memory
                    // me legacy "address" ho to "address_line" ban jaye.
                    val canon = TagRegistry.normalizeTag(k)
                    if (v.isNotEmpty() && canon.isNotEmpty()) {
                        sessionDetails[canon] = v
                    }
                }
            }
            askedKeys.clear()
            val asked =
                draftPrefs().getString(memKey(work, "asked"), "").orEmpty()
            if (asked.isNotEmpty()) {
                // v29 P4: asked-keys bhi canonicalize karke restore.
                for (k in asked.split(",")) {
                    val canon = TagRegistry.normalizeTag(k.trim())
                    if (canon.isNotEmpty()) askedKeys.add(canon)
                }
            }
        } catch (_: Exception) { }
    }

    /** Naya work — is work ki purani memory memory + prefs dono se saaf. */
    private fun clearWorkMemory(work: String) {
        try {
            draftPrefs().edit()
                .remove(memKey(work, "session"))
                .remove(memKey(work, "asked"))
                .apply()
        } catch (_: Exception) { }
    }

    /** Dialog rows ke liye shared row builder. */
    private fun dialogRow(
        act: Activity, icon: String, title: String, desc: String,
        onTap: () -> Unit
    ): View {
        return LinearLayout(act).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = with(UiKit) { act.tintCard("#FFFFFF", "#DADCE0") }
            addView(TextView(act).apply {
                text = icon
                textSize = 22f
                setPadding(0, 0, dp(10), 0)
            })
            addView(LinearLayout(act).apply {
                orientation = VERTICAL
                addView(TextView(act).apply {
                    text = title
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#202124"))
                })
                addView(TextView(act).apply {
                    text = desc
                    textSize = 11f
                    setTextColor(Color.parseColor("#5F6368"))
                })
            })
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
            UiKit.pressFeedback(this)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, dp(4), 0, dp(4))
            layoutParams = lp
        }
    }

    /**
     * P6: Agent tab me generic category picker — WorkCategories.ALL se
     * generated (nayi category yahan add karte hi sab jagah aa jayegi).
     * Category badlo → uski alag chat history khulti hai.
     */
    fun showCategoryPicker() {
        val act = context as? Activity ?: return
        val dlg = AlertDialog.Builder(act).create()
        val box = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        box.addView(TextView(act).apply {
            text = "📂 Kaam (काम) chuno —"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, 0, 0, dp(10))
        })
        for (cat in WorkCategories.ALL) {
            box.addView(dialogRow(act, cat.icon, cat.label, cat.desc) {
                dlg.dismiss()
                switchCategory(cat.key, cat.label)
            })
        }
        box.addView(Button(act).apply {
            text = "← Peeche (वापस)"
            minimumWidth = 0
            setOnClickListener { dlg.dismiss() }
        })
        dlg.setView(box)
        dlg.show()
    }

    /** Track category picker se chuna → 8 track-types me se type chuno. */
    private fun showPickerTrackTypes(act: Activity, onPick: (String?) -> Unit) {
        val dlg = AlertDialog.Builder(act).create()
        val box = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        box.addView(TextView(act).apply {
            text = "🔍 Track (ट्रैकिंग) — kaunsa type?"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, 0, 0, dp(10))
        })
        for (tt in WorkCategories.TRACK_TYPES) {
            box.addView(dialogRow(act, tt.icon, tt.label, tt.hint) {
                dlg.dismiss()
                onPick(tt.key)
            })
        }
        box.addView(dialogRow(act, "💬", "Aam Tracking (आम ट्रैकिंग)",
            "Koi khaas type nahi — aise hi baat karo") {
            dlg.dismiss()
            onPick(null)
        })
        box.addView(Button(act).apply {
            text = "← Peeche (वापस)"
            minimumWidth = 0
            setOnClickListener {
                dlg.dismiss()
                onPick(null) // type nahi chuna → aam track chat
            }
        })
        dlg.setView(box)
        dlg.show()
    }

    /** P6: category switch — purani category ki history save, nayi ki load. */
    private fun switchCategory(catKey: String, catLabel: String) {
        saveChatHistory()
        activeCategory = catKey
        rememberCategory(catKey) // v28 P14
        activeTrackingType = null
        autoPlanArmed = false
        if (catKey == "track") {
            val act = context as? Activity ?: return
            showPickerTrackTypes(act) { picked ->
                activeTrackingType = picked
                rememberTrackingType(picked) // v41
                finishCategorySwitch(catKey, catLabel)
            }
        } else {
            rememberTrackingType(null) // v41
            finishCategorySwitch(catKey, catLabel)
        }
    }

    private fun finishCategorySwitch(catKey: String, catLabel: String) {
        val chipText = if (activeTrackingType != null)
            "🔖 $catLabel › ${WorkCategories.trackLabelOf(activeTrackingType)}  ✕"
        else "🔖 $catLabel  ✕"
        post {
            categoryChip.text = chipText
            categoryChip.visibility = View.VISIBLE
        }
        loadChatHistory(catKey, activeTrackingType)
        val n = history.size
        toast(
            if (n > 0) "$catLabel — pichli baat-cheet wapas ($n)"
            else "$catLabel — nayi baat-cheet shuru karo"
        )
    }

    // ---------- v28 P7: 📜 Purane Kaam (category-filtered work history) ----------

    /**
     * Agent tab ke andar category ke purane kaam. Tap → wahi kaam/context
     * resume (server form-tasks se category filter karke laata hai).
     * Track category me trackings bhi (tap: status puchho; long-press: band karo).
     */
    /**
     * v41: Agent section category-wise → kaam-wise.
     * Pehle category chuno (agar active nahi), phir us category ke kaam
     * (tasks + trackings) dikhenge. Har kaam: tap = continue, lamba dabao =
     * delete (task) / band karo (tracking).
     */
    fun showPastWork() {
        val cat = activeCategory
        if (cat == null) {
            pickCategoryThenPastWork()
            return
        }
        loadPastWork(cat)
    }

    /** Koi category active nahi — pehle category chuno, phir uske kaam. */
    private fun pickCategoryThenPastWork() {
        val act = context as? Activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        val dlg = AlertDialog.Builder(act).create()
        val box = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        box.addView(TextView(act).apply {
            text = "📂 Pehle category chuno — phir uske kaam dikhenge"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, 0, 0, dp(10))
        })
        for (c in WorkCategories.ALL) {
            box.addView(dialogRow(act, c.icon, c.label, c.desc) {
                dlg.dismiss()
                loadPastWork(c.key)
            })
        }
        box.addView(Button(act).apply {
            text = "← Peeche (वापस)"
            minimumWidth = 0
            setOnClickListener { dlg.dismiss() }
        })
        dlg.setView(box)
        dlg.show()
    }

    /** Is category ke purane kaam (tasks + trackings) server se lao. */
    private fun loadPastWork(cat: String) {
        val act = context as? Activity ?: return
        toast("Purane kaam la raha hun…")
        Thread({
            val tasks = try {
                AgentApi.listTasks(context, cat)
            } catch (_: Exception) { emptyList<JSONObject>() }
            val trackings = if (cat == "track") {
                try { AgentApi.listTrackings(context) }
                catch (_: Exception) { emptyList<JSONObject>() }
            } else emptyList<JSONObject>()
            post { showPastWorkDialog(act, cat, tasks, trackings) }
        }, "fm-past-work").start()
    }

    private fun showPastWorkDialog(
        act: Activity, cat: String,
        tasks: List<JSONObject>, trackings: List<JSONObject>
    ) {
        // v28 P12: dead activity par dialog = BadTokenException → fail-soft.
        if (act.isFinishing || act.isDestroyed) return
        try {
        val dlg = AlertDialog.Builder(act).create()
        val box = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        box.addView(TextView(act).apply {
            text = "📜 Purane Kaam — ${WorkCategories.labelOf(cat)}"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, 0, 0, dp(10))
        })
        val list = LinearLayout(act).apply { orientation = VERTICAL }
        val scroll = ScrollView(act).apply { addView(list) }
        if (tasks.isEmpty() && trackings.isEmpty()) {
            list.addView(TextView(act).apply {
                text = "Abhi koi purana kaam nahi hai.\nNaya kaam Home → Kaam chuno se shuru karo."
                textSize = 14f
                setTextColor(Color.parseColor("#5F6368"))
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
        }
        for (t in tasks) {
            val title = t.optString("name", "Kaam").ifEmpty { "Kaam" }
            val status = t.optString("status", "").ifEmpty { "—" }
            // v41: lamba dabao = delete (server par cancelled + local pending
            // saaf — deleted kaam dobara kabhi start nahi hoga).
            val row = dialogRow(
                act, "📝", title,
                "Status: $status — tap: continue • lamba dabao: delete"
            ) {
                dlg.dismiss()
                resumeTask(t)
            }
            row.setOnLongClickListener {
                confirmDeleteTask(act, t) {
                    dlg.dismiss()
                    showPastWork()
                }
                true
            }
            list.addView(row)
        }
        if (trackings.isNotEmpty()) {
            list.addView(TextView(act).apply {
                text = "🔍 Meri Trackings"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#202124"))
                setPadding(0, dp(12), 0, dp(4))
            })
            for (tr in trackings) {
                val label = tr.optString("label", "Tracking").ifEmpty { "Tracking" }
                val tt = tr.optString("tracking_type", "")
                val st = tr.optString("status", "")
                val desc = buildString {
                    if (tt.isNotEmpty()) append(WorkCategories.trackLabelOf(tt)).append(" • ")
                    append(if (st == "active") "✅ Chal rahi hai" else "⏹ $st")
                    append(" — tap: status puchho • lamba dabao: band karo")
                }
                val row = dialogRow(act, "🔍", label, desc) {
                    dlg.dismiss()
                    resumeTracking(tr)
                }
                row.setOnLongClickListener {
                    confirmCancelTracking(act, tr) {
                        dlg.dismiss()
                        showPastWork()
                    }
                    true
                }
                list.addView(row)
            }
        }
        box.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320)
            )
        )
        box.addView(Button(act).apply {
            text = "← Band karo"
            minimumWidth = 0
            setOnClickListener { dlg.dismiss() }
        })
        dlg.setView(box)
        dlg.show()
        } catch (t: Throwable) {
            android.util.Log.e("FmPast", "showPastWorkDialog failed", t)
            toast("⚠️ Purane kaam khulne me dikkat — dobara try karo")
        }
    }

    /**
     * v41: user apna kaam delete kar sake.
     * - Server par run ko "cancelled" (terminal) mark — dobara kabhi
     *   claim/resume nahi hoga, chahe purana state bacha ho.
     * - Local AgentResume pending bhi saaf (agar yahi run tha).
     * - Active (chal raha) kaam delete nahi hoga — pehle BAND KARO.
     */
    private fun confirmDeleteTask(act: Activity, t: JSONObject, onDone: () -> Unit) {
        val title = t.optString("name", "kaam").ifEmpty { "kaam" }
        val runId = t.optString("run_id").ifEmpty { t.optString("id") }
        val status = t.optString("status", "")
        if (status == "running" || status == "in_progress" || status == "started") {
            AlertDialog.Builder(act)
                .setTitle("⏳ Pehle kaam band karo")
                .setMessage("\"$title\" abhi chal raha hai. Delete karne se pehle use BAND KARO (emergency stop), phir delete karo.")
                .setPositiveButton("Samajh gaya", null)
                .show()
            return
        }
        AlertDialog.Builder(act)
            .setTitle("🗑️ Kaam delete karein?")
            .setMessage("\"$title\" hamesha ke liye band ho jayega — ye dobara start nahi hoga, aur iski entry list se hat jayegi.")
            .setPositiveButton("Delete karo") { d, _ ->
                d.dismiss()
                Thread({
                    try {
                        if (runId.isNotEmpty()) {
                            AgentApi.updateRun(
                                context, runId, "cancelled", 0,
                                "User ne delete kiya", ""
                            )
                        }
                    } catch (_: Exception) { }
                    // Local pending bhi saaf — sirf agar yahi run pending tha.
                    try {
                        val pending = com.formmitra.app.engine.AgentResume.checkPending(context)
                        if (pending != null && pending.runId == runId) {
                            com.formmitra.app.engine.AgentResume.clear(context)
                        }
                    } catch (_: Exception) { }
                    post {
                        toast("🗑️ Kaam delete ho gaya")
                        onDone()
                    }
                }, "fm-delete-task").start()
            }
            .setNegativeButton("Rehne do", null)
            .show()
    }

    /** Task resume — wahi kaam/context agent ko wapas do. */
    private fun resumeTask(t: JSONObject) {        val title = t.optString("name", "kaam").ifEmpty { "kaam" }
        val status = t.optString("status", "")
        val url = t.optString("target_url", t.optString("url", ""))
        val sb = StringBuilder("📜 Purana kaam continue karo: \"$title\"")
        if (status.isNotEmpty()) sb.append(" (status: $status)")
        if (url.isNotEmpty()) sb.append("\nLink: $url")
        sb.append(
            "\nJahan ruka tha wahan se aage badhao — jo ho gaya wo dobara " +
                "mat karo, jo detail chahiye maango."
        )
        sendMessage(sb.toString())
    }

    /** Tracking resume — taaza status puchho. */
    private fun resumeTracking(tr: JSONObject) {
        val label = tr.optString("label", "tracking").ifEmpty { "tracking" }
        sendMessage(
            "🔍 Is tracking ka taaza status batao: \"$label\". " +
                "Agar koi naya update ho to detail me batao."
        )
    }

    /** Tracking band karo — confirmation ke saath (har delete par confirm). */
    private fun confirmCancelTracking(
        act: Activity, tr: JSONObject, onDone: () -> Unit
    ) {
        // v28 P12: dead activity guard.
        if (act.isFinishing || act.isDestroyed) return
        val label = tr.optString("label", "tracking").ifEmpty { "tracking" }
        val id = tr.optString("id", "")
        try {
        AlertDialog.Builder(act)
            .setTitle("⏹ Tracking band karo?")
            .setMessage(
                "\"$label\"\n\nBand karne ke baad is par naye updates " +
                    "aana band ho jayenge."
            )
            .setPositiveButton("Haan, band karo") { _, _ ->
                toast("Band kar raha hun…")
                Thread({
                    val code = try {
                        AgentApi.cancelTracking(context, id)
                    } catch (_: Exception) { -1 }
                    post {
                        toast(
                            if (code in 200..299) "⏹ Tracking band ho gayi"
                            else "⚠️ Band nahi ho payi — baad me try karo"
                        )
                        onDone()
                    }
                }, "fm-cancel-tracking").start()
            }
            .setNegativeButton("Rehne do", null)
            .show()
        } catch (t: Throwable) {
            android.util.Log.e("FmPast", "confirmCancelTracking failed", t)
            toast("⚠️ Dobara try karo")
        }
    }

    // ---------- v28 P11: agent auto-detect → auto-save ----------

    /**
     * Chat me nayi labeled detail (aadhar/PAN/voter/…) dikhi → tag ke
     * saath card me AUTO-SAVE. Conservative: label + nontrivial value DONO
     * hon tabhi (ExtraDetailDetector). Save par confirmation bubble.
     * Card unlock na ho → pending + create-card offer (detail ghumti nahi).
     */
    private fun autoSaveExtraDetails(extras: Map<String, String>) {
        val tag = activeCategory ?: "chat"
        val cid = activeCardId
        val tok = activeCardToken ?: cid?.let { CardStore.token(it) }
        // v29 P4: canonical key par lao (detector pehle se canonical/
        // fallback deta hai; normalize idempotent hai).
        val canon = LinkedHashMap<String, String>()
        for ((k, v) in extras) {
            val ck = TagRegistry.normalizeTag(k)
            if (ck.isNotEmpty() && v.isNotEmpty()) canon[ck] = v
        }
        if (canon.isEmpty()) return
        if (cid != null && !tok.isNullOrEmpty()) {
            val snapshot = LinkedHashMap(canon)
            Thread({
                // v29 P2: verify-after-write — detector wali details bhi
                // re-read se confirm (chat me dikhna ≠ saved hona).
                val res = CardSaveVerifier.saveAndVerify(
                    patch = { details ->
                        try {
                            AgentApi.patchCard(context, cid, tok, details).code
                        } catch (_: Exception) { -1 }
                    },
                    reread = {
                        try {
                            AgentApi.cardDetail(context, cid, tok).json
                        } catch (_: Exception) { null }
                    },
                    toSave = snapshot,
                    tag = tag
                )
                post {
                    when (res) {
                        is CardSaveVerifier.Result.Verified -> {
                            cardCachedDetails.putAll(snapshot)
                            persistWorkMemory()
                            val msg = "✅ Card me save ho gaya: " +
                                snapshot.keys.joinToString(", ") {
                                    TagRegistry.labelOf(it)
                                }
                            addAssistantBubble(msg)
                            history.add("assistant" to msg)
                            saveChatHistory()
                        }
                        else -> {
                            CardStore.pendingAddAll(context, snapshot, tag)
                            toast(
                                "❌ Card me SAVE NAHI HUA — " +
                                    "${CardSaveVerifier.loudReason(res)} — " +
                                    "detail surakshit hai, baad me phir try hogi"
                            )
                        }
                    }
                }
            }, "fm-extra-save").start()
        } else {
            CardStore.pendingAddAll(context, canon, tag)
            (context as? Activity)?.let { act ->
                post {
                    CardFlow.offerCreateCardForDetails(
                        act, CardStore.pendingCount(context),
                        onAgentCreate = { prefill ->
                            onAgentCreateRequest?.invoke(prefill)
                        },
                        onCreated = { _, id, name, token ->
                            setActiveCard(id, name, token)
                        }
                    )
                }
            }
        }
    }

    // ---------- verify dialog ----------

    /**
     * Details ka edit card: arrange karke dikhao, har field editable.
     * SIRF user-initiated edit ke liye ("Meri details" → "Theek karo") —
     * automation flow me iska koi gate nahi hai.
     */
    private fun showVerifyDialog(
        fields: Map<String, String>,
        title: String,
        subtitle: String,
        positiveLabel: String,
        onProceed: (Map<String, String>) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val act = context as? Activity ?: run { onCancel(); return }
        val layout = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(48, 24, 48, 8)
        }
        layout.addView(TextView(act).apply {
            text = subtitle
            textSize = 14f
            setTextColor(Color.parseColor("#5F6368"))
            setPadding(0, 0, 0, dp(12))
        })
        val edits = LinkedHashMap<String, EditText>()
        for (k in DetailExtractor.orderedKeys()) {
            val v = fields[k] ?: continue
            layout.addView(TextView(act).apply {
                text = DetailExtractor.label(k)
                textSize = 13f
                setTextColor(Color.parseColor("#80868B"))
                setPadding(0, dp(6), 0, 0)
            })
            val et = EditText(act).apply {
                setText(v)
                textSize = 16f
                setTextColor(Color.parseColor("#202124"))
            }
            layout.addView(et)
            edits[k] = et
        }
        // unknown extra fields (agar ho)
        for ((k, v) in fields) {
            if (edits.containsKey(k)) continue
            layout.addView(TextView(act).apply {
                text = DetailExtractor.label(k)
                textSize = 13f
                setTextColor(Color.parseColor("#80868B"))
                setPadding(0, dp(6), 0, 0)
            })
            val et = EditText(act).apply { setText(v); textSize = 16f }
            layout.addView(et)
            edits[k] = et
        }
        val dlg = AlertDialog.Builder(act)
            .setTitle(title)
            .setView(ScrollView(act).apply { addView(layout) })
            .setCancelable(false)
            .setPositiveButton(positiveLabel, null)
            .setNegativeButton("❌ Mat bhejo", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val final = LinkedHashMap<String, String>()
                edits.forEach { (k, et) ->
                    val v = et.text.toString().trim()
                    if (v.isNotEmpty()) final[k] = v
                }
                dlg.dismiss()
                onProceed(final)
            }
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dlg.dismiss()
                onCancel()
            }
        }
        dlg.show()
    }

    /** 📋 Details — vault + session ki details arrange karke dikhao + theek karo. */
    private fun showDetailsCard() {
        toast("Details la raha hun…")
        Thread {
            val merged = LinkedHashMap<String, String>()
            try {
                val vault = AgentApi.profile(context)
                if (vault != null) {
                    for (k in DetailExtractor.orderedKeys()) {
                        val v = vault.optString(k, "").trim()
                        if (v.isNotEmpty() && v != "null") merged[k] = v
                    }
                }
            } catch (_: Exception) { }
            for ((k, v) in sessionDetails) merged[k] = v
            post {
                val act = context as? Activity ?: return@post
                if (merged.isEmpty()) {
                    AlertDialog.Builder(act)
                        .setTitle("📋 Meri details")
                        .setMessage(
                            "Abhi koi details save nahi hain.\n\n" +
                                "Chat me apna naam, phone, email wagera batao — " +
                                "bhejne se pehle verify card aayega."
                        )
                        .setPositiveButton("Theek hai", null)
                        .show()
                    return@post
                }
                val layout = LinearLayout(act).apply {
                    orientation = VERTICAL
                    setPadding(48, 24, 48, 8)
                }
                for (k in DetailExtractor.orderedKeys()) {
                    val v = merged[k] ?: continue
                    layout.addView(TextView(act).apply {
                        text = DetailExtractor.label(k)
                        textSize = 13f
                        setTextColor(Color.parseColor("#80868B"))
                        setPadding(0, dp(6), 0, 0)
                    })
                    layout.addView(TextView(act).apply {
                        text = v
                        textSize = 16f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#202124"))
                    })
                }
                AlertDialog.Builder(act)
                    .setTitle("📋 Meri details")
                    .setView(ScrollView(act).apply { addView(layout) })
                    .setPositiveButton("Band karo", null)
                    .setNeutralButton("✏️ Theek karo", null)
                    .create().apply {
                        setOnShowListener {
                            getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                                dismiss()
                                showVerifyDialog(
                                    merged,
                                    title = "✏️ Details theek karo",
                                    subtitle = "Jo galat hai use theek karo, phir Save dabao.",
                                    positiveLabel = "💾 Save karo",
                                    onProceed = { verified ->
                                        sessionDetails.putAll(verified)
                                        // v29 P1c: verify-dialog wali details
                                        // bhi turant memory me.
                                        persistWorkMemory()
                                        val diffs = verified.filter { (k, v) -> merged[k] != v }
                                        if (diffs.isNotEmpty()) {
                                            doSend(
                                                "Meri in details ko update kar do: " +
                                                    diffs.entries.joinToString(", ") {
                                                        "${DetailExtractor.label(it.key)}: ${it.value}"
                                                    }
                                            )
                                        } else {
                                            toast("Koi badlav nahi")
                                        }
                                    }
                                )
                            }
                        }
                        show()
                    }
            }
        }.start()
    }

    // L5: double-tap guard — ek (title,url) ka enqueue ek waqt me ek hi
    private val enqueueInFlight = mutableSetOf<String>()

    private fun enqueueTask(
        title: String,
        url: String,
        category: String = "",
        knownDetails: Map<String, String> = emptyMap(),
        askedAlready: List<String> = emptyList(),
        onDone: () -> Unit
    ) {
        val flightKey = "$title|$url"
        if (!enqueueInFlight.add(flightKey)) {
            post { onDone() }
            return
        }
        Thread {
            try {
            var msg: String
            // v36 (point 10): 1-active PRE-CHECK — chat me turant EXACT
            // rejection card (service ke notification ka wait nahi; aur
            // "shuru ho gaya" jhooth nahi). Owner exempt (unlimited).
            // Service-side claim + server 409 backup me hain (race/multi-device).
            val localActive =
                com.formmitra.app.engine.FormRunService.activeTaskId
            if (localActive != null &&
                !com.formmitra.app.engine.FormRunService.isOwnerDevice(context)
            ) {
                post {
                    addOneActiveRejectionCard()
                    onDone()
                }
                return@Thread
            }
            if (url.isEmpty()) {
                msg = "Is form ka official link nahi mila — dobara pucho."
            } else {
                // PRECHECK: pehle dekho ho sakta hai ya nahi (+ pichhla experience).
                val verdict = runPrecheck(url, title)
                if (verdict != null && !verdict.feasible) {
                    msg = PrecheckLogic.verdictText(verdict) +
                        "\n\nIsliye shuru nahi kiya — koi aur tareeka batao ya link badlo."
                    post {
                        addAssistantBubble(msg)
                        VoiceOutput.speak(context, "Ye kaam nahi ho sakta. ${verdict.reason}".take(300))
                        onDone()
                    }
                    return@Thread
                }
                if (verdict != null) {
                    val vt = PrecheckLogic.verdictText(verdict)
                    post {
                        addAssistantBubble(vt)
                        VoiceOutput.speak(context, vt.take(300))
                    }
                }
                val (code, taskId) = AgentApi.createTask(
                    context, title, url, category, knownDetails, askedAlready
                )
                // A: category → task threading (device-local backup bhi;
                // step JSON me bhi gayi — FormRunService wahan se uthayega)
                if (!taskId.isNullOrEmpty()) CategoryStore.saveForTask(context, taskId, category)
                msg = if (taskId.isNullOrEmpty()) {
                    val offline = code == -1 || code >= 500
                    when {
                        code == 401 -> "Pehle Profile tab me login karo 🔑"
                        offline && com.formmitra.app.engine.Standalone.isConfigured(context) ->
                            startStandaloneTask(title, url)
                        code == -1 -> "Internet nahi hai 📡 — offline kaam tayyar nahi hai."
                        else -> "Kaam shuru nahi ho paya — dobara try karo."
                    }
                } else {
                    val runCode = AgentApi.runNow(context, taskId)
                    if (runCode in 200..299) {
                        // I3 (app-first): 30-min periodic ka wait nahi — turant
                        // claim ke liye one-time poll kick karo.
                        // v35: kick fail ho to user ko batao (pehle silent tha —
                        // session band rehta tha). 30-min poll backup hai.
                        val kicked = com.formmitra.app.Scheduler.kickNow(context)
                        "Background me shuru ho gaya ✅ — phone turant uthayega. " +
                            "History tab me progress dekho." +
                            if (!kicked)
                                "\n\n(Turant wala signal nahi gaya — 30 min wali " +
                                    "check me uthega.)"
                            else ""
                    } else if (runCode == -1) {
                        "Internet nahi hai 📡"
                    } else {
                        "Task ban gaya par shuru nahi ho paya. " +
                            "Baad me dobara try karo."
                    }
                }
            }
            post {
                addAssistantBubble(msg)
                onDone()
            }
            // v28 P12: try/finally me CATCH nahi tha — andar koi bhi
            // exception = uncaught = app crash. Ab pakdo + user ko batao.
            // v35: GENERIC message nahi — ErrorCatcher popup me ASLI wajah +
            // Copy button + Dobara-try (user ka order: har dikkat pakdo).
            } catch (t: Throwable) {
                android.util.Log.e("FmEnqueue", "enqueueTask failed", t)
                val sid = "q" + System.currentTimeMillis().toString(36)
                post {
                    try {
                        com.formmitra.app.engine.ErrorCatcher.show(
                            context,
                            "Kaam shuru karte waqt",
                            t,
                            workName = title,
                            sessionId = sid,
                            retry = {
                                enqueueTask(title, url, category, knownDetails, askedAlready, onDone)
                            }
                        )
                    } catch (_: Exception) {
                        addErrorBubble(
                            "⚠️ Kaam shuru karte waqt dikkat aayi — dobara try karo.",
                            "🔁 Dobara try karo"
                        ) { enqueueTask(title, url, category, knownDetails, askedAlready, onDone) }
                    }
                    onDone()
                }
            } finally {
            enqueueInFlight.remove(flightKey)
        }
        }.start()
    }

    /**
     * Offline task: StandaloneStore me save + FormRunService seedha chalao.
     * Server bilkul involve nahi — brain = saved key (StandaloneBrain).
     */
    private fun startStandaloneTask(title: String, url: String): String {
        return try {
            val id = com.formmitra.app.engine.StandaloneStore.create(context, title, url)
            val task = JSONObject()
                .put("name", title)
                .put("target_url", url)
                .put("run_id", id)
                .put("standalone", true)
                .put(
                    "steps",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "agent_run")
                            .put("goal", title)
                            .put("url", url)
                    )
                )
            com.formmitra.app.engine.FormRunService.startWithTask(context, task)
            "Internet nahi hai — phone me hi shuru kiya ✅\n" +
                "Progress notification me dikhega."
        } catch (e: Exception) {
            "Standalone task shuru nahi ho paya — dobara try karo."
        }
    }

    /**
     * POST /api/agent/precheck → Verdict?.
     * null = check nahi ho paya (network/401 — proceed anyway, purana flow).
     */
    private fun runPrecheck(url: String, goal: String): PrecheckLogic.Verdict? {
        // v24-refine (quota discipline): pehle CACHE (24h) — repeat task par
        // ZERO budget kharch. Order pakka: cache → local → (tabhi) server.
        try {
            PrecheckCache.get(context, url, goal)?.let { cached ->
                AiUsage.logPatternHit(AiUsage.P_PRECHECK_CACHE)
                return PrecheckLogic.parse(jsonToMap(cached))
            }
        } catch (_: Exception) { }
        return try {
            AiUsage.logPrecheck(AiUsage.R_NEW_TASK)
            val res = AgentApi.precheck(context, url, goal)
            if (res.code == 401) {
                post { addAssistantBubble("Precheck ke liye login chahiye 🔑 — bina check ke shuru kar raha hu.") }
                null
            } else if (res.code !in 200..299 || res.json == null) {
                null
            } else {
                try { PrecheckCache.put(context, url, goal, res.json!!) }
                catch (_: Exception) { }
                PrecheckLogic.parse(jsonToMap(res.json!!))
            }
        } catch (_: Exception) {
            null
        }
    }

    /** JSONObject → Map (PrecheckLogic ke liye, org.json-free). */
    private fun jsonToMap(o: JSONObject): Map<String, Any?> {
        val m = HashMap<String, Any?>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.opt(k)
            m[k] = when (v) {
                is JSONObject -> jsonToMap(v)
                is JSONArray -> (0 until v.length()).map { idx ->
                    val e = v.opt(idx)
                    if (e is JSONObject) jsonToMap(e) else e
                }
                JSONObject.NULL -> null
                else -> v
            }
        }
        return m
    }

    // ---------- needs_user banner + polling ----------

    /** MainActivity.selectTab se — Home dikha to polling shuru. */
    fun onTabShown() {
        // TTS engine warm karo taaki pehla jawab turant bole (lost na ho)
        try { VoiceOutput.init(context) } catch (_: Exception) { }
        // Point 14: detail batch listener (ek baar) + pending batches dikhao
        try {
            if (!detailBatchListenerRegistered) {
                detailBatchListenerRegistered = true
                DetailBatchStore.onRaised { batch ->
                    post { addDetailBatchCard(batch) }
                }
            }
            checkPendingDetailBatches()
        } catch (_: Exception) { }
        // POINT 25: tracking action-offer listener (ek baar) + unseen offers.
        try {
            if (!trackOfferListenerRegistered) {
                trackOfferListenerRegistered = true
                TrackOffer.addListener { offer ->
                    post { addTrackOfferCard(offer) }
                }
            }
            Thread({
                try {
                    val unseen = TrackOffer.takeUnseen(context)
                    if (unseen.isNotEmpty()) {
                        post { unseen.forEach { addTrackOfferCard(it) } }
                    }
                } catch (_: Exception) { }
            }, "track-offer-unseen").start()
        } catch (_: Exception) { }
        // POINT 28: line me kaam ho to entry par ek baar dikhao.
        try { checkWorkQueue() } catch (_: Exception) { }
        // POINT 17: Apply poora → "Iska status track karu?" card.
        try { checkTrackHandoff() } catch (_: Exception) { }
        // POINT 21: end-of-work summary cards.
        try { checkRunSummaries() } catch (_: Exception) { }
        // POINT 22: run ke beech Card lock → one-tap unlock card.
        try {
            if (!cardUnlockListenerRegistered) {
                cardUnlockListenerRegistered = true
                CardUnlockNeeded.addListener { need ->
                    post { addCardUnlockCard(need) }
                }
            }
            Thread({
                try {
                    val needs = CardUnlockNeeded.takePending(context)
                    if (needs.isNotEmpty()) {
                        post { needs.forEach { addCardUnlockCard(it) } }
                    }
                } catch (_: Exception) { }
            }, "fm-cardunlock-pending").start()
        } catch (_: Exception) { }
        // POINT 24 (revised): chat entry par Card PIN (ek baar) —
        // unlock persistent hai, dobara nahi maangega.
        try { ensureCardUnlockOnEntry() } catch (_: Exception) { }
        // v36 — LIVE ACTIVITY INDICATOR: listener ek baar register karo;
        // fresh last event ho to turant seed karo (chat khulne par bhi
        // chal raha kaam dikhe).
        try {
            if (!liveActivityListenerRegistered) {
                liveActivityListenerRegistered = true
                LiveActivity.addListener(liveActivityListener)
            }
            val lastLive = LiveActivity.lastEvent()
            if (lastLive != null) onLiveActivityEvent(lastLive)
        } catch (_: Exception) { }
        if (polling) return
        polling = true
        pollHandler.post(pollRunnable)
    }

    /** Agent tab chhupa to polling band. */
    fun onTabHidden() {
        polling = false
        pollHandler.removeCallbacks(pollRunnable)
        // v36 — LIVE ACTIVITY INDICATOR: chat chhupa to listener hatao +
        // indicator chhupao (transient hai — wapas aane par fresh seed hoga).
        try {
            if (liveActivityListenerRegistered) {
                liveActivityListenerRegistered = false
                LiveActivity.removeListener(liveActivityListener)
            }
            hideLiveActivity()
        } catch (_: Exception) { }
    }

    // ============ POINT 24 (REVISED): PERSISTENT CARD UNLOCK ============
    //
    // Chat entry par ek baar PIN → unlock persistent (chat band / app
    // background / background automation par dobara PIN nahi). Unlock sirf
    // manual "Lock karo" ya sign-out se tootega — koi auto re-lock nahi.

    /**
     * Chat entry par card unlock pakka karo. Valid token (memory ya
     * encrypted restore) ho to kuch nahi; nahi to ek baar PIN dialog
     * (dismiss kar sakta hai — nag nahi karenge).
     */
    private fun ensureCardUnlockOnEntry() {
        val act = context as? Activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        val cid = activeCardId ?: CardStore.selectedCardId(context)
        if (cid.isNullOrEmpty()) {
            // POINT 27 EXTEND: koi card hi nahi (PIN set karne ka mauka hi
            // nahi mila) → chat entry par "Card banao + PIN set karo".
            try {
                PinSetupFlow.ensureCardOrSetup(act) { newCardId ->
                    if (!newCardId.isNullOrEmpty()) {
                        activeCardId = newCardId
                        try { ensureCardUnlockOnEntry() } catch (_: Exception) { }
                    }
                    try { refreshLockBtn() } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            try { refreshLockBtn() } catch (_: Exception) { }
            return
        }
        if (activeCardId == null) activeCardId = cid
        // Token: memory → encrypted persist (app restart ke baad bhi).
        val tok = activeCardToken ?: CardStore.tokenOrRestore(context, cid)
        if (!tok.isNullOrEmpty()) {
            if (activeCardToken == null) {
                activeCardToken = tok
                try { AgentApi.setAutomationCard(cid, tok) } catch (_: Exception) { }
            }
            maybeShowUnlockNote(cid)
            try { refreshLockBtn() } catch (_: Exception) { }
            return
        }
        // Token nahi — PIN chahiye (pehli baar, ya 30-min server TTL ke baad).
        if (pinPromptShownFor == cid) {
            try { refreshLockBtn() } catch (_: Exception) { }
            return
        }
        pinPromptShownFor = cid
        val cardJson = JSONObject()
            .put("id", cid)
            .put("name", activeCardName ?: "Card")
        CardFlow.askPinAndUnlock(
            act, cardJson,
            onUnlocked = { prefill, id, name, token ->
                activeCardId = id
                activeCardName = name
                activeCardToken = token
                try { AgentApi.setAutomationCard(id, token) } catch (_: Exception) { }
                if (prefill.isNotEmpty()) {
                    cardCachedDetails.clear()
                    cardCachedDetails.putAll(prefill)
                }
                maybeShowUnlockNote(id)
                try { refreshLockBtn() } catch (_: Exception) { }
            }
        )
        try { refreshLockBtn() } catch (_: Exception) { }
    }

    /** Unlock ke baad subtle note — EK BAAR per unlock (revised text). */
    private fun maybeShowUnlockNote(cardId: String) {
        if (cardId.isEmpty()) return
        try {
            if (CardStore.unlockNoteShown(context, cardId)) return
            CardStore.markUnlockNoteShown(context, cardId)
            addAssistantBubble(CardUnlockPolicy.unlockNoteText())
        } catch (_: Exception) { }
    }

    /** Header lock button ka state (prominent manual lock). */
    private fun refreshLockBtn() {
        if (!::lockBtn.isInitialized) return
        val cid = activeCardId ?: CardStore.selectedCardId(context)
        if (cid.isNullOrEmpty()) {
            lockBtn.visibility = View.GONE
            return
        }
        lockBtn.visibility = View.VISIBLE
        lockBtn.text =
            if (CardStore.isUnlocked(context, cid)) "🔒 Lock karo"
            else "🔓 Card kholo"
    }

    private fun onLockBtnTapped() {
        val cid = activeCardId ?: CardStore.selectedCardId(context)
        if (cid.isNullOrEmpty()) return
        if (!CardStore.isUnlocked(context, cid)) {
            // Locked → kholne ka rasta.
            pinPromptShownFor = null
            try { ensureCardUnlockOnEntry() } catch (_: Exception) { }
            return
        }
        val act = context as? Activity ?: return
        AlertDialog.Builder(act)
            .setTitle("🔒 Card lock karu?")
            .setMessage(
                "Card lock ho jayega — dobara kholne ke liye PIN lagega.\n" +
                    "Background kaam me Card chahiye hoga to ruk jayega."
            )
            .setPositiveButton("🔒 Haan, lock karo") { _, _ -> lockCardNow(cid) }
            .setNegativeButton("Rehne do", null)
            .show()
    }

    private fun lockCardNow(cardId: String) {
        CardStore.lock(context, cardId)
        // CONTRACT SYNC: server ko bhi lock batao (token server par mare).
        Thread({
            try { AgentApi.lockCard(context, cardId) } catch (_: Exception) { }
        }, "fm-card-lock").start()
        activeCardToken = null
        pinPromptShownFor = null
        try { AgentApi.setAutomationCard(cardId, null) } catch (_: Exception) { }
        try { refreshLockBtn() } catch (_: Exception) { }
        val nm = activeCardName ?: "Card"
        toast("🔒 Card lock ho gaya")
        addAssistantBubble(CardUnlockPolicy.lockDoneText(nm))
    }

    private fun pollTaskStatus() {
        Thread {
            val runs = try { AgentApi.listRuns(context) } catch (_: Exception) { null }
            val prompt = UserPrompt.pendingRequest()
            post {
                handlePollResult(runs)
                if (prompt != null) {
                    val act = context as? Activity
                    // v29 zero-crash gate: finishing/destroyed activity par
                    // dialog show() = BadTokenException = UI-thread crash.
                    if (act != null && !act.isFinishing && !act.isDestroyed &&
                        !PromptDialog.isShowing(prompt.runId)
                    ) {
                        try {
                            PromptDialog.show(act, prompt)
                        } catch (_: Exception) { }
                    }
                }
            }
        }.start()
    }

    private fun runFd(item: JSONObject): JSONObject? = item.optJSONObject("form_data")

    private fun runGoal(item: JSONObject): String {
        val g = runFd(item)?.optString("goal", "").orEmpty()
        if (g.isNotEmpty()) return g
        return item.optString("name", "Form").ifEmpty { "Form" }
    }

    private fun runUrl(item: JSONObject): String {
        val u = runFd(item)?.optString("url", "").orEmpty()
        if (u.isNotEmpty()) return u
        return item.optString("target_url", "")
    }

    private fun runSummary(item: JSONObject): String {
        val fd = runFd(item)
        val s = fd?.optString("summary", "").orEmpty()
        if (s.isNotEmpty()) return s
        return fd?.optString("error", "").orEmpty()
    }

    private fun handlePollResult(runs: JSONArray?) {
        if (runs == null || runs.length() == 0) {
            hideBanner()
            // v36 — LIVE ACTIVITY INDICATOR: koi run nahi → indicator bhi
            // gayab (fresh event na ho to).
            try { pollLiveActivityFallback(false) } catch (_: Exception) { }
            return
        }
        val latest = runs.optJSONObject(0)
        if (latest == null) {
            hideBanner()
            return
        }
        val status = latest.optString("status", "")
        // v24 #1: agent + operator + AI three-way coordination — har stage
        // par user ko saaf Hinglish status (bubble + awaaz). Ye poll har 30s
        // latest run dekhta hai; tracked category run ka stage badle to
        // announce karo (repeat + mute bubble buttons se).
        announceRunStage(latest, status)
        // v36 refine point 1: pre-flight plan USER-VISIBLE (chat me).
        // Plan bana ho to ek baar dikhao — "ye link khulegi, ye ye steps
        // honge" — galat lage to user shuru me hi pakad le (misunderstanding
        // check ka practical roop).
        try {
            val runId = latest.optString("run_id").ifEmpty { latest.optString("id") }
            val planTxt = com.formmitra.app.agent.PlanStore.takePlan(context, runId)
            if (planTxt != null) {
                addAssistantBubble(
                    "📋 Plan taiyar hai:\n$planTxt\n\n" +
                        "Galat lage to turant batao — main rok kar theek karunga. " +
                        "Sahi lage to kuch mat karo, kaam shuru ho raha hai."
                )
            }
        } catch (_: Exception) { }
        // A+C: stuck / blocker / needs_user — saaf rukho, batao, awaaz me sunao.
        // "failed" bhi blocker hai (pehle chup-chaap gayab ho jata tha).
        if (status == "needs_user" || status == "needs_attention" || status == "failed") {
            bannerGoal = runGoal(latest)
            bannerUrl = runUrl(latest)
            bannerCategory = runFd(latest)?.optString("category", "").orEmpty()
            val reason = runSummary(latest).ifEmpty { "Agent ko aapki madad chahiye" }
            val low = reason.lowercase()
            bannerText.text =
                if (low.contains("otp") || low.contains("login")) {
                    "⚠️ OTP/Login: site par khud complete karein\n$reason"
                } else {
                    "⚠️ $reason"
                }
            if (bannerBox.visibility != View.VISIBLE) {
                bannerBox.visibility = View.VISIBLE
                scrollToBottom()
            }
            // C: voice help mode — sirf stuck par trigger hota hai, normal
            // flow me nahi. TTS mute ho to sirf banner text, awaaz nahi.
            val runId = latest.optString("run_id").ifEmpty { latest.optString("id") }
            VoiceHelp.announceStuck(context, runId, status, bannerGoal, reason) {
                bannerText.text = it
            }
        } else {
            hideBanner()
        }
        // v36 — LIVE ACTIVITY INDICATOR (spec point 5): server-poll fallback.
        // Engine ka LiveActivity event miss hua ho aur server par run active
        // dikhe to generic label — naya heavy infra nahi, existing 30s poll.
        try {
            val active = status == "running" || status == "queued" ||
                status == "in_progress" || status == "needs_user" ||
                status == "needs_attention"
            pollLiveActivityFallback(active)
        } catch (_: Exception) { }
    }

    private fun hideBanner() {
        if (::bannerBox.isInitialized) bannerBox.visibility = View.GONE
    }

    // ---------- v36: LIVE ACTIVITY INDICATOR (user order 2026-09-26) ----------
    //
    // "User ko chat me dikhe jab agent kaam karega — kya kar raha hai,
    //  neeche show ho... Likha hua MESSAGE nahi, sirf jaise 'thinking' me
    //  thinking show hota hai par message nahi aata — waise hi."
    //
    // Ye CHAT MESSAGE NAHI HAI — transient status line hai. messageList me
    // add nahi hota, isliye history me kabhi save nahi hota. Kaam
    // complete/rukne/fail hone par gayab (spec point 4).

    /** LiveActivity event aaya — label dikhao ya (terminal par) chhupao. */
    private fun onLiveActivityEvent(e: LiveActivity.Event) {
        if (!::liveActivityText.isInitialized) return
        // v38 addition #4: Live button par active-dot.
        try { updateLiveDot(e) } catch (_: Exception) { }
        if (LiveActivity.isTerminal(e)) {
            hideLiveActivity()
            return
        }
        // Stale event (90s se purana) ignore — process-restart ke baad ka
        // purana label flash na ho.
        if (System.currentTimeMillis() - e.at > LiveActivity.STALE_MS) return
        val label = LiveActivity.labelFor(e.key) ?: run {
            hideLiveActivity()
            return
        }
        showLiveActivity(label)
    }

    /** Transient indicator dikhao — dots animate hote hain (thinking jaisa). */
    private fun showLiveActivity(label: String) {
        if (!::liveActivityText.isInitialized) return
        liveBaseLabel = label
        liveDots = 0
        liveActivityText.text = "✦ $label"
        if (!liveActivityVisible) {
            liveActivityVisible = true
            liveActivityText.visibility = View.VISIBLE
            liveDotsHandler.post(liveDotsRunnable)
        }
    }

    /** Indicator gayab — kaam khatam/ruka/fail ya gate band. */
    private fun hideLiveActivity() {
        liveActivityVisible = false
        liveDotsHandler.removeCallbacks(liveDotsRunnable)
        if (::liveActivityText.isInitialized) liveActivityText.visibility = View.GONE
    }

    /**
     * v38 addition #4 — Live button par state dot: agent background me
     * kaam kar raha ho (fresh non-terminal event) to "● 🖥️ Live",
     * warna plain "🖥️ Live". Sirf display hai — toggle ka kaam nahi badalta.
     */
    private fun updateLiveDot(e: LiveActivity.Event) {
        val btn = liveBtn ?: return
        try {
            val active = !LiveActivity.isTerminal(e) &&
                System.currentTimeMillis() - e.at <= LiveActivity.STALE_MS
            val want = if (active) "● 🖥️ Live" else "🖥️ Live"
            if (btn.text.toString() != want) btn.text = want
        } catch (_: Exception) { }
    }

    /** Server poll fallback (spec point 5): engine event miss hua ho aur
     *  server par run active dikhe to generic label — koi naya infra nahi. */
    private fun pollLiveActivityFallback(activeRun: Boolean) {
        if (!::liveActivityText.isInitialized) return
        if (!activeRun) {
            // Koi active run nahi aur fresh event bhi nahi → chhupao.
            val last = LiveActivity.lastEvent()
            val fresh = last != null &&
                System.currentTimeMillis() - last.at <= LiveActivity.STALE_MS &&
                !LiveActivity.isTerminal(last)
            if (!fresh) hideLiveActivity()
            return
        }
        val last = LiveActivity.lastEvent()
        val fresh = last != null &&
            System.currentTimeMillis() - last.at <= LiveActivity.STALE_MS
        if (!fresh && !liveActivityVisible) {
            showLiveActivity("Kaam kar raha hai")
        }
    }

    // ---------- v24 #1: three-way coordination (agent + operator + AI) ----------
    //
    // Jab automation operator atke, AI ke saath milkar aage badhe — app me
    // ye dikhna chahiye: atakne par user ko saaf Hinglish status
    // ("AI se samajh raha hun..."), phir naya plan execute ho; teeno ka
    // coordination toote nahi, aur user ko har stage par pata rahe kya ho
    // raha hai (repeat + mute wali awaaz ke saath).
    //
    // Implementation: category kaam shuru hote hi trackRunStage(); har 30s
    // poll me latest run ka stage badle to chat bubble + voice announce.

    private var trackedLabel: String? = null
    private var trackedCardName: String? = null
    private var trackedSinceMs: Long = 0L
    private var lastStageKey: String? = null
    private var lastMilestone: Int = 0

    private fun trackRunStage(label: String, cardName: String) {
        trackedLabel = label
        trackedCardName = cardName
        trackedSinceMs = System.currentTimeMillis()
        lastStageKey = null
        lastMilestone = 0
    }

    private fun stopTracking() {
        trackedLabel = null
        trackedCardName = null
        lastStageKey = null
        lastMilestone = 0
    }

    private fun announceRunStage(latest: JSONObject, status: String) {
        val label = trackedLabel ?: return
        val card = trackedCardName ?: "Card"
        // 30 min se purana tracking — band karo (stale).
        if (System.currentTimeMillis() - trackedSinceMs > 30 * 60 * 1000L) {
            stopTracking()
            return
        }
        // Ye run hamara hai? — tracking shuru hone ke aas-paas bana ho.
        val createdAt = latest.optString("created_at", "")
        val ours = try {
            if (createdAt.isEmpty()) true // field na ho to latest ko apna mano
            else {
                val t = java.time.Instant.parse(createdAt).toEpochMilli()
                t >= trackedSinceMs - 120_000L
            }
        } catch (_: Exception) { true }
        if (!ours) return

        val runId = latest.optString("run_id").ifEmpty { latest.optString("id") }
        val steps = runFd(latest)?.optInt("steps_taken", 0) ?: 0
        val key = "$runId|$status"
        val prevKey = lastStageKey

        // Milestone: har 5 steps par halki khabar (spam nahi).
        val milestone = (steps / 5) * 5
        if (status == "running" || status == "in_progress") {
            if (milestone > lastMilestone && milestone > 0) {
                lastMilestone = milestone
                val msg = "⚙️ Step $steps — kaam chal raha hai 🪪 $card"
                addAssistantBubble(msg)
                // Milestone par awaaz nahi (zyada bolega) — bubble hi kaafi.
            }
        }

        if (key == prevKey) return
        lastStageKey = key
        when (status) {
            "running", "in_progress", "started" -> {
                if (prevKey == null) {
                    val msg = "🚀 Kaam shuru — 🪪 $card\n" +
                        "Main steps chala raha hun, tum dekhte raho. " +
                        "Atkunga to 🧠 AI se samajhkar naya plan banaunga."
                    addAssistantBubble(msg)
                    VoiceOutput.speak(context, "Kaam shuru ho gaya. Main steps chala raha hun.")
                } else if (prevKey.endsWith("needs_user") ||
                    prevKey.endsWith("needs_attention")
                ) {
                    // Atakne ke baad wapas chala — AI ke saath naya plan.
                    val msg = "🔄 Naya plan mil gaya — 🧠 AI ke saath milkar phir se try kar raha hun."
                    addAssistantBubble(msg)
                    VoiceOutput.speak(context, "Naya plan mil gaya. Phir se try kar raha hun.")
                }
            }
            "needs_user", "needs_attention" -> {
                // Banner + VoiceHelp pehle se stuck announce karte hain —
                // yahan sirf coordination bubble (double awaaz nahi).
                addAssistantBubble(
                    "😟 Main yahan atak gaya hun — 🧠 AI se dobara samajh raha hun.\n" +
                        "Upar banner me dekho — tumhari madad chahiye to wahan batao."
                )
            }
            "done", "completed", "success" -> {
                val msg = "✅ Ho gaya! ($label)\nProof History me dekh sakte ho."
                addAssistantBubble(msg)
                VoiceOutput.speak(context, "Ho gaya! Kaam poora ho gaya.")
                stopTracking()
            }
            "failed", "error", "cancelled" -> {
                addAssistantBubble(
                    "❌ Ye kaam poora nahi ho paya.\n" +
                        "Upar banner me wajah dekho — 🔁 se dobara try kar sakte ho."
                )
                stopTracking()
            }
        }
    }

    private fun retryTask() {
        // v39: preflight-fail retry — plan bana hi nahi tha to URL khaali
        // hoga; aise me goal se dobara shuru karo (preflight URL khud
        // nikalega). Dono khaali hon tabhi "link nahi mila".
        if (bannerUrl.isEmpty() && bannerGoal.isEmpty()) {
            toast("Link nahi mila — dobara chal nahi sakta")
            return
        }
        retryBtn.isEnabled = false
        Thread {
            try {
            var msg: String
            val verdict = runPrecheck(bannerUrl, bannerGoal)
            if (verdict != null && !verdict.feasible) {
                msg = PrecheckLogic.verdictText(verdict) + "\n\nRetry nahi kiya."
                post {
                    addAssistantBubble(msg)
                    VoiceOutput.speak(context, "Ye kaam nahi ho sakta. ${verdict.reason}".take(300))
                    retryBtn.isEnabled = true
                }
                return@Thread
            }
            val (code, taskId) = AgentApi.createTask(context, bannerGoal, bannerUrl, bannerCategory)
            if (!taskId.isNullOrEmpty()) CategoryStore.saveForTask(context, taskId, bannerCategory)
            msg = if (taskId.isNullOrEmpty()) {
                when (code) {
                    -1 -> "Internet nahi hai 📡"
                    401 -> "Pehle Profile tab me login karo 🔑"
                    else -> "Kaam shuru nahi ho paya — dobara try karo."
                }
            } else {
                val runCode = AgentApi.runNow(context, taskId)
                if (runCode in 200..299) {
                    // I3 (app-first): turant claim ke liye one-time poll kick.
                    com.formmitra.app.Scheduler.kickNow(context)
                    "Dobara shuru ho gaya ✅ — agent ab kaam karega."
                } else if (runCode == -1) {
                    "Internet nahi hai 📡"
                } else {
                    "Task ban gaya par shuru nahi ho paya — baad me dobara try karo."
                }
            }
            post {
                addAssistantBubble(msg)
                retryBtn.isEnabled = true
                hideBanner()
            }
            // v28 P12: uncaught = crash — pakdo + user ko batao.
            } catch (t: Throwable) {
                android.util.Log.e("FmRetry", "retryTask failed", t)
                post {
                    addErrorBubble(
                        "⚠️ Dobara chalate waqt dikkat aayi.",
                        "🔁 Dobara try karo"
                    ) { retryTask() }
                    retryBtn.isEnabled = true
                }
            }
        }.start()
    }

    // ---------- voice input ----------

    private fun onMicClick() {
        if (listening) {
            stopListening()
            return
        }
        val act = context as? Activity ?: return
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            act.requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VOICE_PERM
            )
            return
        }
        startListening("hi-IN")
    }

    /** MainActivity.onRequestPermissionsResult se forward hota hai. */
    fun onVoicePermissionResult(granted: Boolean) {
        if (granted) startListening("hi-IN")
        else toast("Mic permission chahiye 🎤 — Settings me de do")
    }

    private fun startListening(lang: String) {
        val ctx = context
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            toast("Is phone me voice nahi mila")
            return
        }
        stopListening()
        voiceFallbackTried = lang != "hi-IN"
        voiceBaseText = try { input.text.toString() } catch (_: Exception) { "" }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(voiceListener)
                val ri = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // Live transcription: bolte waqt hi text dikhe
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(
                        "android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS",
                        2500
                    )
                    putExtra(
                        "android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS",
                        2500
                    )
                }
                startListening(ri)
            }
            listening = true
            micBtn.text = "⏹"
            input.hint = "🎤 Sun raha hun… bolo"
        } catch (_: Exception) {
            stopListening()
            toast("Voice shuru nahi hua")
        }
    }

    private fun stopListening() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) { }
        recognizer = null
        listening = false
        try {
            micBtn.text = "🎤"
            input.hint = "Yahan likho…"
        } catch (_: Exception) { }
    }

    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        /** Bolte waqt live text input me dikhao — user ko pata chale sun raha hai. */
        override fun onPartialResults(partialResults: Bundle?) {
            if (!listening) return
            val t = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (t.isNotEmpty()) {
                input.setText(if (voiceBaseText.isBlank()) t else "$voiceBaseText $t")
                input.setSelection(input.text.length)
            }
        }

        override fun onError(error: Int) {
            if (!voiceFallbackTried) {
                // hi-IN fail → en-IN ek baar try karo
                startListening("en-IN")
                return
            }
            stopListening()
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "Suna nahi gaya — thoda saaf aur paas se bolo 🎤"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Voice ke liye internet chahiye 📡"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Mic permission nahi mili 🎤"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "Voice busy hai — 2 second ruk ke dobara dabao"
                else -> "Voice me dikkat — dobara try karo"
            }
            toast(msg)
        }

        override fun onResults(results: Bundle?) {
            val t = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            stopListening()
            if (t.isNotEmpty()) {
                val cur = voiceBaseText.ifBlank { input.text.toString() }
                val full = if (cur.isBlank()) t else "$cur $t"
                // RC1 FIX (v26): bola hua seedha agent ko bhejo — pehle
                // sirf input me text set hota tha, sendMessage kabhi nahi hota tha.
                sendMessage(full)
            } else {
                toast("Kuch suna nahi gaya — dobara bolo")
            }
        }
    }

    // ---------- document picker (koi bhi file) ----------

    private fun onAttachClick() {
        val act = context as? Activity ?: return
        // Limited type selector hataya — user koi bhi document bhej sakta hai
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            act.startActivityForResult(intent, REQ_DOC_PICK)
        } catch (_: Exception) {
            toast("File picker nahi khula")
        }
    }

    /** MainActivity.onActivityResult se forward hota hai. */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_DOC_PICK) return
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            // POINT 16: compress note bhi lo — picker result me dikhega.
            val (savedName, docNote) = try {
                DocsStore.saveDocWithNote(context, uri)
            } catch (_: Exception) { null to "" }
            post {
                if (!savedName.isNullOrEmpty()) {
                    val msg = if (docNote.isNotEmpty()) docNote
                    else "Document save ho gaya ✅ — agent upload step me istemaal hoga"
                    toast(msg)
                    // v20 Task 3: "Kaun sa document hai?" — type device-local
                    // save hota hai, server ko kabhi nahi jata (doc-privacy).
                    val act = context as? Activity
                    if (act != null) {
                        UiKit.askDocType(act) { type ->
                            DocsStore.setDocType(context, savedName, type)
                            toast("🏷️ $type ke roop me save hua")
                        }
                    }
                } else {
                    toast("File save nahi hui — dobara try karo")
                }
            }
        }.start()
    }

    /**
     * v29 zero-crash gate: toast kabhi crash na kare — context destroyed
     * Activity ho to Toast.makeText throw karta hai (UI thread par =
     * app crash). Har call site protected.
     */
    private fun toast(msg: String) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}

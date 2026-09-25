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
 * - Verify-before-save gate: personal details wala message PEHLE verify card
 *   me dikhta hai — Proceed dabane par hi server ko jata hai (bina Proceed
 *   ke save ho hi nahi sakta). Edit ka option built-in.
 * - "Shuru karo" se pehle bhi details ka verify card (vault + session merge).
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

    // send watchdog + retry
    private val sendWatchdog = Handler(Looper.getMainLooper())
    private var sendToken = 0
    private var lastFailedText: String? = null

    // verify-before-save: is session me verify ho chuki details
    private val sessionDetails = LinkedHashMap<String, String>()

    // needs_user banner
    private lateinit var bannerBox: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var retryBtn: Button
    private var bannerGoal = ""
    private var bannerUrl = ""

    // voice input
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var voiceFallbackTried = false
    private var voiceBaseText = ""

    // task status polling (sirf Home/chat visible ho tabhi)
    private val pollHandler = Handler(Looper.getMainLooper())
    private var polling = false
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            pollTaskStatus()
            pollHandler.postDelayed(this, 30_000)
        }
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
            setOnClickListener { showDetailsCard() }
        })
        addView(header)

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
        }
        messageList = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(messageList)
        addView(scroll)

        // Input row: [📎] [text] [🎤] [🔊] [➤]
        val inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, dp(6), pad, pad)
        }
        val attachBtn = Button(context).apply {
            text = "📎"
            textSize = 18f
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(4), 0) }
            setOnClickListener { onAttachClick() }
        }
        inputRow.addView(attachBtn)
        input = EditText(context).apply {
            hint = "Yahan likho…"
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_SEND
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            background = inputBg()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendMessage((v as TextView).text.toString())
                    true
                } else false
            }
        }
        inputRow.addView(input)
        micBtn = Button(context).apply {
            text = "🎤"
            textSize = 18f
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener { onMicClick() }
        }
        inputRow.addView(micBtn)
        // 🔊 speaker toggle — agent ke jawab bol ke sunao
        val speakBtn = Button(context).apply {
            text = if (VoiceOutput.isEnabled(context)) "🔊" else "🔇"
            textSize = 18f
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener {
                val on = !VoiceOutput.isEnabled(context)
                VoiceOutput.setEnabled(context, on)
                text = if (on) "🔊" else "🔇"
                if (on) VoiceOutput.speak(context, "Awaaz chalu hai")
            }
        }
        inputRow.addView(speakBtn)
        sendBtn = Button(context).apply {
            text = "➤"
            textSize = 18f
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8), 0, 0, 0) }
            setOnClickListener { sendMessage(input.text.toString()) }
        }
        inputRow.addView(sendBtn)
        addView(inputRow)

        // Greeting
        post {
            addAssistantBubble(
                "Namaste! 🙏 Kaun sa form bharna hai? Batao, " +
                    "main link + kya-kya lagega nikal deta hoon."
            )
        }
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

        // Shuru karo — verify gate ke saath (details ho to pehle verify card)
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
        startBtn.setOnClickListener {
            startBtn.isEnabled = false
            startBtn.text = "⏳ Details la raha hun…"
            Thread {
                // Vault profile + session details merge karke verify card
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
                    if (merged.isEmpty()) {
                        beginEnqueue(plan, link, startBtn)
                    } else {
                        showVerifyDialog(
                            merged,
                            title = "✔️ Aage badhne se pehle verify karo",
                            subtitle = "Agent inhi details se kaam karega. " +
                                "Proceed dabane par hi task banega.",
                            positiveLabel = "✅ Proceed",
                            onProceed = { verified ->
                                // User ne theek kiya ho to server ko correction bhejo
                                val diffs = verified.filter { (k, v) -> merged[k] != v }
                                if (diffs.isNotEmpty()) {
                                    sessionDetails.putAll(verified)
                                    doSend(
                                        "Meri in details ko theek kar do: " +
                                            diffs.entries.joinToString(", ") {
                                                "${DetailExtractor.label(it.key)}: ${it.value}"
                                            }
                                    )
                                }
                                beginEnqueue(plan, link, startBtn)
                            },
                            onCancel = {
                                startBtn.text = "▶ Shuru karo"
                                startBtn.isEnabled = true
                            }
                        )
                    }
                }
            }.start()
        }
        card.addView(startBtn)

        messageList.addView(card, lp)
        scrollToBottom()
    }

    private fun beginEnqueue(plan: JSONObject, link: String, startBtn: Button) {
        startBtn.text = "⏳ Task ban raha hai…"
        enqueueTask(
            title = plan.optString("title", "Form"),
            url = link,
            onDone = { post { startBtn.text = "▶ Shuru karo"; startBtn.isEnabled = true } }
        )
    }

    // ---------- flow ----------

    /**
     * Verify-before-save gate: message me nayi personal details dikhin to
     * PEHLE verify card — Proceed par hi message server ko jayega.
     */
    fun sendMessage(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || waiting) return
        val candidates = DetailExtractor.extract(text)
        val fresh = candidates.filterKeys { !sessionDetails.containsKey(it) }
        if (fresh.isNotEmpty()) {
            showVerifyDialog(
                fresh,
                title = "✔️ Details verify karo",
                subtitle = "Ye details save hongi. Sahi hain to Proceed dabao — " +
                    "bina Proceed ke kuch save nahi hoga.",
                positiveLabel = "✅ Sahi hai — bhejo",
                onProceed = { verified ->
                    sessionDetails.putAll(verified)
                    var finalText = text
                    val edited = verified.filter { (k, v) -> candidates[k] != v }
                    if (edited.isNotEmpty()) {
                        finalText += "\n(Sahi: " + edited.entries.joinToString(", ") {
                            "${DetailExtractor.label(it.key)}=${it.value}"
                        } + ")"
                    }
                    doSend(finalText)
                },
                onCancel = { toast("Message nahi bheja — details save nahi hui") }
            )
            return
        }
        doSend(text)
    }

    /** Asli send — gate se guzarne ke baad. Retry bhi yahi aata hai. */
    private fun doSend(text: String) {
        val t = text.trim()
        if (t.isEmpty() || waiting) return
        waiting = true
        lastFailedText = t
        input.text.clear()
        sendBtn.isEnabled = false
        addUserBubble(t)
        history.add("user" to t)
        showTyping()
        // Watchdog: 75s me jawab na aaye to stuck state todo + retry do
        val token = ++sendToken
        sendWatchdog.removeCallbacksAndMessages(null)
        sendWatchdog.postDelayed({
            if (waiting && token == sendToken) {
                waiting = false
                sendBtn.isEnabled = true
                hideTyping()
                addErrorBubble(
                    "⏳ Server se jawab nahi aaya (timeout).",
                    "🔁 Dobara bhejo"
                ) { lastFailedText?.let { doSend(it) } }
            }
        }, 75_000)
        Thread {
            val res = try {
                AgentApi.chat(context, history.toList())
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
                    429 -> addAssistantBubble("Aaj ka limit khatam, kal try karo ⏳")
                    200 -> {
                        lastFailedText = null
                        val json = res.json
                        val reply = json?.optString("reply", "")?.trim().orEmpty()
                        if (reply.isNotEmpty()) {
                            addAssistantBubble(reply)
                            history.add("assistant" to reply)
                            VoiceOutput.speak(context, reply)
                        }
                        val plan = json?.optJSONObject("plan")
                        if (plan != null) addPlanCard(plan)
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
                        if (reply.isEmpty() && plan == null) {
                            addErrorBubble(
                                "🤖 Jawab khaali aaya.",
                                "🔁 Dobara bhejo"
                            ) { lastFailedText?.let { doSend(it) } }
                        }
                        // VERIFY-BEFORE-SAVE contract (server 4c5e1df2): bina
                        // confirmed:true ke chat draft_profile + needs_confirmation
                        // wapas karta hai — usi existing verify dialog me dikhao.
                        // Proceed = PUT /api/agent/profile se save, Cancel = kuch nahi.
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
                                    if (v.isNotEmpty() && v != "null") draftMap[k] = v
                                }
                            }
                            if (draftMap.isNotEmpty()) {
                                showVerifyDialog(
                                    draftMap,
                                    title = "✔️ Details verify karo",
                                    subtitle = "AI ne ye details nikali hain. Sahi hain to Proceed dabao — " +
                                        "bina Proceed ke kuch save nahi hoga.",
                                    positiveLabel = "✅ Sahi hai — save karo",
                                    onProceed = { verified ->
                                        sessionDetails.putAll(verified)
                                        Thread {
                                            val ok = try {
                                                AgentApi.saveProfile(context, verified)
                                            } catch (_: Exception) { false }
                                            post {
                                                toast(if (ok) "✓ Details save ho gayi" else "⚠️ Save me dikkat — baad me try karo")
                                            }
                                        }.start()
                                    },
                                    onCancel = { toast("Details save nahi hui") }
                                )
                            }
                        }
                        val savedArr = json?.optJSONArray("saved")
                        if (savedArr != null && savedArr.length() > 0) {
                            toast("✓ Details save ho gayi")
                        }
                    }
                    else -> addErrorBubble(
                        "⚠️ Server se dikkat (code ${res.code}) — message nahi gaya.",
                        "🔁 Dobara bhejo"
                    ) { lastFailedText?.let { doSend(it) } }
                }
            }
        }.start()
    }

    // ---------- verify dialog ----------

    /**
     * Details ka verify card: arrange karke dikhao, har field editable,
     * Proceed par hi aage badho. Bina Proceed ke kuch nahi hota.
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

    private fun enqueueTask(title: String, url: String, onDone: () -> Unit) {
        Thread {
            var msg: String
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
                val (code, taskId) = AgentApi.createTask(context, title, url)
                msg = if (taskId.isNullOrEmpty()) {
                    val offline = code == -1 || code >= 500
                    when {
                        code == 401 -> "Pehle Profile tab me login karo 🔑"
                        offline && com.formmitra.app.engine.Standalone.isConfigured(context) ->
                            startStandaloneTask(title, url)
                        code == -1 -> "Internet nahi hai 📡 — server bhi nahi mil raha. " +
                            "Standalone ab server-managed hai."
                        else -> "Task ban nahi paya (code $code). Dobara try karo."
                    }
                } else {
                    val runCode = AgentApi.runNow(context, taskId)
                    if (runCode in 200..299) {
                        "Background me shuru ho gaya ✅ — 30 min ke andar " +
                            "phone uthayega. History tab me progress dekho."
                    } else if (runCode == -1) {
                        "Internet nahi hai 📡"
                    } else {
                        "Task ban gaya par run nahi hua (code $runCode). " +
                            "Baad me dobara try karo."
                    }
                }
            }
            post {
                addAssistantBubble(msg)
                onDone()
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
            "Server nahi mil raha — standalone mode me shuru kiya ✅\n" +
                "Progress notification me dikhega."
        } catch (e: Exception) {
            "Standalone task shuru nahi hua: ${(e.message ?: "error").take(120)}"
        }
    }

    /**
     * POST /api/agent/precheck → Verdict?.
     * null = check nahi ho paya (network/401 — proceed anyway, purana flow).
     */
    private fun runPrecheck(url: String, goal: String): PrecheckLogic.Verdict? {
        return try {
            val res = AgentApi.precheck(context, url, goal)
            if (res.code == 401) {
                post { addAssistantBubble("Precheck ke liye login chahiye 🔑 — bina check ke shuru kar raha hu.") }
                null
            } else if (res.code !in 200..299 || res.json == null) {
                null
            } else {
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
        if (polling) return
        polling = true
        pollHandler.post(pollRunnable)
    }

    /** Home chhupa to polling band. */
    fun onTabHidden() {
        polling = false
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun pollTaskStatus() {
        Thread {
            val runs = try { AgentApi.listRuns(context) } catch (_: Exception) { null }
            val prompt = UserPrompt.pendingRequest()
            post {
                handlePollResult(runs)
                if (prompt != null) {
                    val act = context as? Activity
                    if (act != null && !PromptDialog.isShowing(prompt.runId)) {
                        PromptDialog.show(act, prompt)
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
            return
        }
        val latest = runs.optJSONObject(0)
        if (latest == null) {
            hideBanner()
            return
        }
        val status = latest.optString("status", "")
        if (status == "needs_user" || status == "needs_attention") {
            bannerGoal = runGoal(latest)
            bannerUrl = runUrl(latest)
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
        } else {
            hideBanner()
        }
    }

    private fun hideBanner() {
        if (::bannerBox.isInitialized) bannerBox.visibility = View.GONE
    }

    private fun retryTask() {
        if (bannerUrl.isEmpty()) {
            toast("Link nahi mila — dobara chal nahi sakta")
            return
        }
        retryBtn.isEnabled = false
        Thread {
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
            val (code, taskId) = AgentApi.createTask(context, bannerGoal, bannerUrl)
            msg = if (taskId.isNullOrEmpty()) {
                when (code) {
                    -1 -> "Internet nahi hai 📡"
                    401 -> "Pehle Profile tab me login karo 🔑"
                    else -> "Task ban nahi paya (code $code). Dobara try karo."
                }
            } else {
                val runCode = AgentApi.runNow(context, taskId)
                if (runCode in 200..299) {
                    "Dobara shuru ho gaya ✅ — agent ab kaam karega."
                } else if (runCode == -1) {
                    "Internet nahi hai 📡"
                } else {
                    "Task ban gaya par run nahi hua (code $runCode)."
                }
            }
            post {
                addAssistantBubble(msg)
                retryBtn.isEnabled = true
                hideBanner()
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
                input.setText(if (cur.isBlank()) t else "$cur $t")
                input.setSelection(input.text.length)
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
            val ok = DocsStore.saveDoc(context, uri) != null
            post {
                if (ok) {
                    toast("Document save ho gaya ✅ — agent upload step me istemaal hoga")
                } else {
                    toast("File save nahi hui — dobara try karo")
                }
            }
        }.start()
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}

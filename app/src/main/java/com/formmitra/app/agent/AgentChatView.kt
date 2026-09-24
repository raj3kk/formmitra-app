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
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import com.formmitra.app.engine.Standalone
import com.formmitra.app.engine.UserPrompt
import com.formmitra.app.engine.PrecheckLogic
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * FormMitra v3 Phase 1 — native intake chat UI.
 *
 * "Agent" tab ab WebView /agent nahi, ye native view dikhata hai.
 * Phase 1+ chat: greeting → user msg → POST /api/agent/chat → reply bubble +
 * plan card (agar plan aaya) → "Shuru karo" → createTask + runNow.
 * Run history + proof, needs_user banner + resume, Hindi voice input,
 * standalone mode settings (Groq key, encrypted) — sab yahin hai.
 */
class AgentChatView(
    context: Context,
    private val onOpenLink: (String) -> Unit
) : LinearLayout(context) {

    private val history = mutableListOf<Pair<String, String>>()
    private val messageList: LinearLayout
    private val scroll: ScrollView
    private val input: EditText
    private val sendBtn: Button
    private val micBtn: Button
    private var typingView: View? = null
    private var waiting = false

    // needs_user banner
    private lateinit var bannerBox: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var retryBtn: Button
    private var bannerGoal = ""
    private var bannerUrl = ""

    // standalone status chip
    private lateinit var standaloneChip: TextView

    // voice input
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var voiceFallbackTried = false

    // task status polling (sirf Agent tab visible ho tabhi)
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

        // Header: title + History button
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
            text = "📜 History"
            textSize = 13f
            setOnClickListener { showHistory() }
        })
        header.addView(Button(context).apply {
            text = "⚙️"
            textSize = 13f
            setOnClickListener { showStandaloneSettings() }
        })
        addView(header)

        // Standalone status chip
        standaloneChip = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#5F6368"))
            setPadding(pad, dp(2), pad, dp(2))
        }
        addView(standaloneChip)
        refreshStandaloneChip()

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

        // Suggestion chips
        val chipScroll = HorizontalScrollView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            )
            isHorizontalScrollBarEnabled = false
        }
        val chipRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(pad, dp(4), pad, dp(4))
        }
        listOf("Caste certificate", "PAN card", "Scholarship", "Passport").forEach { label ->
            val chip = TextView(context).apply {
                text = label
                textSize = 13f
                setTextColor(Color.parseColor(accent))
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = chipBg()
                setOnClickListener { sendMessage(label) }
            }
            val lp = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(8), 0) }
            chipRow.addView(chip, lp)
        }
        chipScroll.addView(chipRow)
        addView(chipScroll)

        // Input row: [📎] [text] [🎤] [➤]
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
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            background = inputBg()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnEditorActionListener { v, _, _ -> sendMessage((v as TextView).text.toString()); true }
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
        // 🔊 speaker toggle — agent ke jawab bol ke sunao (Phase 5 voice)
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

    private fun chipBg(): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(Color.parseColor("#E8F5F0"))
        d.setStroke(dp(1), Color.parseColor(accent))
        d.cornerRadius = dp(20).toFloat()
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
            // Bubble max ~80% width
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
        }
        wrap.addView(tv)
        messageList.addView(wrap)
        scrollToBottom()
    }

    private fun addUserBubble(text: String) = addBubble(text, true)
    private fun addAssistantBubble(text: String) = addBubble(text, false)

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

        // Title
        card.addView(TextView(context).apply {
            text = plan.optString("title", "Form")
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
        })

        // Official link
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

        // Login badge
        val loginNeeded = plan.optBoolean("login_needed", false)
        card.addView(TextView(context).apply {
            text = if (loginNeeded) "🔒 Login lagega" else "🔓 Bina login"
            textSize = 13f
            setTextColor(Color.parseColor("#5F6368"))
            setPadding(0, 0, 0, dp(6))
        })

        // Kya-kya lagega checklist
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

        // Estimated steps
        val steps = plan.optInt("estimated_steps", 0)
        if (steps > 0) {
            card.addView(TextView(context).apply {
                text = "≈ $steps steps lagenge"
                textSize = 13f
                setTextColor(Color.parseColor("#5F6368"))
                setPadding(0, dp(6), 0, 0)
            })
        }

        // Warnings
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

        // Shuru karo button
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
            startBtn.text = "⏳ Task ban raha hai…"
            enqueueTask(
                title = plan.optString("title", "Form"),
                url = link,
                onDone = { post { startBtn.text = "▶ Shuru karo"; startBtn.isEnabled = true } }
            )
        }
        card.addView(startBtn)

        messageList.addView(card, lp)
        scrollToBottom()
    }

    // ---------- flow ----------

    fun sendMessage(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || waiting) return
        waiting = true
        input.text.clear()
        sendBtn.isEnabled = false
        addUserBubble(text)
        history.add("user" to text)
        showTyping()
        Thread {
            val res = AgentApi.chat(context, history.toList())
            post {
                hideTyping()
                waiting = false
                sendBtn.isEnabled = true
                when (res.code) {
                    -1 -> addAssistantBubble("Internet nahi hai 📡")
                    401 -> addAssistantBubble("Pehle Profile tab me login karo 🔑")
                    429 -> addAssistantBubble("Aaj ka limit khatam, kal try karo ⏳")
                    200 -> {
                        val json = res.json
                        val reply = json?.optString("reply", "")?.trim().orEmpty()
                        if (reply.isNotEmpty()) {
                            addAssistantBubble(reply)
                            history.add("assistant" to reply)
                            // Agent ka jawab bol ke bhi sunao (voice output)
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
                                    "📎 Ye docs profile me ready rakho: " +
                                        names.joinToString(", ")
                                )
                            }
                        }
                        if (reply.isEmpty() && plan == null) {
                            addAssistantBubble("Kuch gadbad hui, dobara bolo.")
                        }
                    }
                    else -> addAssistantBubble("Kuch gadbad hui, dobara bolo.")
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
                            "Standalone ke liye Profile me apni Groq API key save karo."
                        else -> "Task ban nahi paya (code $code). Dobara try karo."
                    }
                } else {
                    val runCode = AgentApi.runNow(context, taskId)
                    if (runCode in 200..299) {
                        "Background me shuru ho gaya ✅ — 30 min ke andar " +
                            "phone uthayega. /admin/forms me progress dekho."
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
     * Server bilkul involve nahi — brain = user ki Groq key (StandaloneBrain).
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
                "(tumhari Groq key se, bina server ke). Progress notification me dikhega."
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

    // ---------- runs history ----------

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

    private fun runDate(item: JSONObject): String {
        val c = item.optString("created_at", "")
        return if (c.length >= 10) c.substring(0, 10) else c
    }

    private fun statusBadge(s: String): String = when (s) {
        "done" -> "✅ Ho gaya"
        "needs_user", "needs_attention" -> "⚠️ Dhyaan chahiye"
        "failed" -> "❌ Fail"
        "vetoed" -> "🛑 Roka gaya"
        "in_progress", "running", "progress" -> "⏳ Chal raha"
        "queued" -> "⏳ Queue me"
        else -> s.ifEmpty { "?" }
    }

    private fun showHistory() {
        Thread {
            val runs = AgentApi.listRuns(context)
            post {
                if (runs == null) {
                    toast("History nahi mili 📡")
                    return@post
                }
                if (runs.length() == 0) {
                    toast("Abhi koi run nahi hai")
                    return@post
                }
                val items = (0 until runs.length())
                    .map { runs.optJSONObject(it) ?: JSONObject() }
                val labels = items.map { item ->
                    "${runGoal(item)}\n${statusBadge(item.optString("status", ""))} • ${runDate(item)}"
                }.toTypedArray()
                AlertDialog.Builder(context)
                    .setTitle("📜 Runs history")
                    .setItems(labels) { _, which -> showRunDetail(items[which]) }
                    .setNegativeButton("Band karo", null)
                    .show()
            }
        }.start()
    }

    private fun showRunDetail(item: JSONObject) {
        val fd = runFd(item)
        val goal = runGoal(item)
        val url = runUrl(item)
        val steps = fd?.optInt("steps_taken", -1) ?: -1
        val summary = runSummary(item)
        val proof = fd?.optString("proof_url", "").orEmpty()
        val started = fd?.optString("started_at", "").orEmpty()
        val finished = fd?.optString("finished_at", "").orEmpty()
        val sb = StringBuilder()
        sb.append("Status: ${statusBadge(item.optString("status", ""))}\n")
        if (url.isNotEmpty()) sb.append("Link: $url\n")
        if (steps >= 0) sb.append("Steps: $steps\n")
        if (started.isNotEmpty()) sb.append("Shuru: $started\n")
        if (finished.isNotEmpty()) sb.append("Khatm: $finished\n")
        if (summary.isNotEmpty()) sb.append("\n$summary")
        val dlg = AlertDialog.Builder(context)
            .setTitle(goal)
            .setMessage(sb.toString())
            .setNegativeButton("Band karo", null)
        if (proof.isNotEmpty()) {
            dlg.setNeutralButton("🖼️ Proof dekho") { _, _ -> onOpenLink(proof) }
        }
        dlg.show()
    }

    // ---------- standalone mode settings ----------

    private fun isStandaloneConfigured(): Boolean = try {
        Standalone.isConfigured(context)
    } catch (_: Exception) {
        false
    }

    /** @return true = key save ho gayi, false = fail. */
    private fun standaloneSaveKey(key: String): Boolean {
        return try {
            Standalone.saveKey(context, key)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun standaloneClearKey() {
        try {
            Standalone.clearKey(context)
        } catch (_: Exception) {
            // best effort
        }
    }

    private fun refreshStandaloneChip() {
        standaloneChip.text = if (isStandaloneConfigured())
            "Standalone: ON (key saved) ✅"
        else
            "Standalone: OFF"
    }

    private fun showStandaloneSettings() {
        val configured = isStandaloneConfigured()
        val keyInput = EditText(context).apply {
            hint = if (configured) "•••••••• (nayi key yahan likho)"
                   else "Groq API key yahan likho"
            textSize = 15f
            inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            val p = dp(16)
            setPadding(p, dp(8), p, 0)
        }
        body.addView(TextView(context).apply {
            text = "Server ya net na ho to app aapki Groq API key se seedha AI se " +
                "baat karegi. Key sirf aapke phone me encrypted rehti hai."
            textSize = 14f
            setTextColor(Color.parseColor("#5F6368"))
        })
        body.addView(
            keyInput,
            LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10), 0, 0) }
        )
        val srcRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        srcRow.addView(TextView(context).apply {
            text = "Key: console.groq.com → API Keys (free)"
            textSize = 13f
            setTextColor(Color.parseColor("#5F6368"))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        srcRow.addView(Button(context).apply {
            text = "Link kholo"
            textSize = 13f
            setOnClickListener { onOpenLink("https://console.groq.com/keys") }
        })
        body.addView(srcRow)
        val dlg = AlertDialog.Builder(context)
            .setTitle("Standalone mode (bina server)")
            .setView(body)
            .setPositiveButton("Save") { _, _ ->
                val key = keyInput.text.toString().trim()
                if (key.isEmpty()) {
                    toast("Key khaali hai")
                    return@setPositiveButton
                }
                if (standaloneSaveKey(key)) {
                    keyInput.setText("")
                    refreshStandaloneChip()
                    toast("Key save ho gayi ✅")
                } else {
                    toast("Key save nahi hui — dobara try karo")
                }
            }
            .setNegativeButton("Band karo", null)
        if (configured) {
            dlg.setNeutralButton("Hatao") { _, _ ->
                standaloneClearKey()
                refreshStandaloneChip()
                toast("Key hata di gayi")
            }
        }
        dlg.show()
    }

    // ---------- needs_user banner + polling ----------

    /** MainActivity.selectTab se — Agent tab dikha to polling shuru. */
    fun onTabShown() {
        // TTS engine warm karo taaki pehla jawab turant bole (lost na ho)
        try { VoiceOutput.init(context) } catch (_: Exception) { }
        refreshStandaloneChip()
        if (polling) return
        polling = true
        pollHandler.post(pollRunnable)
    }

    /** Agent tab chhupa to polling band. */
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
                // Interactive prompt khula ho to popup dikhao (OTP/payment/choice)
                if (prompt != null) {
                    val act = context as? Activity
                    if (act != null && !PromptDialog.isShowing(prompt.runId)) {
                        PromptDialog.show(act, prompt)
                    }
                }
            }
        }.start()
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
            // Retry se pehle bhi precheck (pichhle experience se faisla)
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

    // ---------- voice input (Hindi) ----------

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
        else toast("Mic permission chahiye 🎤")
    }

    private fun startListening(lang: String) {
        val ctx = context
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            toast("Voice nahi mila")
            return
        }
        stopListening()
        voiceFallbackTried = lang != "hi-IN"
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
                }
                startListening(ri)
            }
            listening = true
            micBtn.text = "⏹"
        } catch (_: Exception) {
            toast("Voice nahi mila")
        }
    }

    private fun stopListening() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) { }
        recognizer = null
        listening = false
        micBtn.text = "🎤"
    }

    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
            if (!voiceFallbackTried) {
                // hi-IN fail → en-IN ek baar try karo
                startListening("en-IN")
            } else {
                stopListening()
                toast("Suna nahi gaya, dobara bolo")
            }
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull()?.trim().orEmpty()
            stopListening()
            if (text.isNotEmpty()) {
                val cur = input.text.toString()
                input.setText(if (cur.isBlank()) text else "$cur $text")
                input.setSelection(input.text.length)
            }
        }
    }

    // ---------- document picker ----------

    private fun onAttachClick() {
        val act = context as? Activity ?: return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
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
            val name = DocsStore.saveDoc(context, uri)
            post {
                if (name != null) {
                    toast("Saved: $name — agent upload step me istemaal hoga")
                } else {
                    toast("File save nahi hui")
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

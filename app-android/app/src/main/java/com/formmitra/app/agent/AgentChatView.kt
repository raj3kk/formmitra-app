package com.formmitra.app.agent

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * FormMitra v3 Phase 1 — native intake chat UI.
 *
 * "Agent" tab ab WebView /agent nahi, ye native view dikhata hai.
 * Flow: greeting → user msg → POST /api/agent/chat → reply bubble +
 * plan card (agar plan aaya) → "Shuru karo" → createTask + runNow.
 *
 * Sirf Phase 1: chat + plan + task enqueue. Koi browser operator /
 * captcha / standalone / voice yahan nahi hai.
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
    private var typingView: View? = null
    private var waiting = false

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

        // Input row
        val inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(pad, dp(6), pad, pad)
        }
        input = EditText(context).apply {
            hint = "Yahan likho…"
            textSize = 15f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            background = inputBg()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnEditorActionListener { v, _, _ -> sendMessage((v as TextView).text.toString()); true }
        }
        inputRow.addView(input)
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
                val (code, taskId) = AgentApi.createTask(context, title, url)
                msg = if (taskId.isNullOrEmpty()) {
                    when (code) {
                        -1 -> "Internet nahi hai 📡"
                        401 -> "Pehle Profile tab me login karo 🔑"
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

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}

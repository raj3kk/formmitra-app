package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * v43 — 📋 Kaam tab (Agent + History ka merge).
 *
 * User ka order: "Agent tab remove karo, history bhi remove karo — dono ka
 * merge ek Work tab ho. Usme jo work bane uska history ho, us par tap karo
 * to us chat me chale jao, wahan se kaam aage badha sako."
 *
 * Har work card par:
 *  - 📝 Kya ban raha hai (kaam ka naam)
 *  - 🏷️ Category (apply / track / resume)
 *  - 🗂️ Kaun se FormMitra card se (context me card_name ho to)
 *  - 👤 User ka naam (vault profile se)
 *  - Status badge (🟢 Chal raha / ✅ Poora / ⏹ Band / ❌ Fail)
 *  - 💬 Chat kholo (us kaam ki chat me jao, wahan se aage badhao)
 *  - ⏹ Band karo (chal raha kaam ho to — automation turant rukegi)
 *  - 🗑️ Delete (sacchi delete — server se hard delete)
 *
 * Upar "➕ Naya kaam" button — nayi chat shuru.
 * Agar koi kaam chal raha ho aur naya shuru karo → purana dikhega,
 * rokne ka option, phir naya shuru hoga.
 */
class WorkTabView(context: Context) : LinearLayout(context) {

    /** Work card tap → us kaam ki chat kholo (MainActivity handle karega). */
    var onOpenWorkChat: ((JSONObject) -> Unit)? = null
    /** ➕ Naya kaam → nayi agent chat. */
    var onNewWork: (() -> Unit)? = null

    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private var userName: String = ""
    private var tasks: List<JSONObject> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor(FmTheme.CREAM))

        // v43 UI: Rich emerald header + gold strip.
        addView(with(FmTheme) {
            context.richHeader(
                "Mere Kaam",
                "Kisi kaam par tap karo → uski chat khulegi, wahan se aage badhao."
            )
        })

        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val newWorkBtn = Button(context).apply {
            with(FmTheme) { primaryCta("➕ Naya kaam shuru karo") }
            setOnClickListener {
                toast("Naya kaam shuru kar rahe hain…", FmToast.INFO)
                onNewWork?.invoke()
            }
        }
        newWorkBtn.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        )
        body.addView(newWorkBtn)
        val guideBtn = Button(context).apply {
            with(FmTheme) { softOutline("❓ Agent kaise kaam karta hai?") }
            setOnClickListener { showAgentGuide(context as Activity) }
        }
        guideBtn.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); bottomMargin = dp(8) }
        body.addView(guideBtn)
        val scroll = ScrollView(context).apply {
            addView(list)
            // v43 UI: neeche scroll = tab bar chhupao, upar = dikhao.
            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                try {
                    (context as? com.formmitra.app.MainActivity)
                        ?.onContentScrolled(scrollY - oldScrollY)
                } catch (_: Exception) { }
            }
        }
        body.addView(
            scroll,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    fun onTabShown() {
        refresh()
    }

    fun refresh() {
        renderLoading()
        Thread({
            val fetched = try {
                AgentApi.listTasks(context)
            } catch (_: Exception) { emptyList() }
            // v43: active-first, phir naya pehle (v42 wala arrange).
            val sorted = fetched.sortedWith(
                compareByDescending<JSONObject> { rank(it) }
                    .thenByDescending { it.optString("updated_at", it.optString("created_at", "")) }
            )
            val name = try {
                AgentApi.profile(context)?.optString("name", "").orEmpty()
            } catch (_: Exception) { "" }
            postUi {
                tasks = sorted
                userName = name
                render()
            }
        }, "fm-worktab-load").start()
    }

    private fun rank(t: JSONObject): Int = when (t.optString("status", "").lowercase()) {
        "running", "in_progress", "started", "queued" -> 3
        "needs_user" -> 2
        "done", "completed", "success" -> 1
        else -> 0
    }

    private fun renderLoading() {
        list.removeAllViews()
        list.addView(TextView(context).apply {
            text = "Kaam load ho rahe hain…"
            textSize = 14f
            setTextColor(Color.parseColor("#5F6368"))
        })
    }

    private fun render() {
        list.removeAllViews()
        if (tasks.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Abhi koi kaam nahi hai.\n\"➕ Naya kaam shuru karo\" se shuru karo."
                textSize = 14f
                setTextColor(Color.parseColor("#5F6368"))
                setPadding(dp(4), dp(16), dp(4), dp(16))
            })
            return
        }
        for (t in tasks) {
            list.addView(workCard(t))
        }
    }

    private fun badgeFor(status: String): String = when (status.lowercase()) {
        "running", "in_progress", "started" -> "🟢 Chal raha"
        "queued" -> "🕐 Line me"
        "needs_user" -> "🙋 Aapka jawab chahiye"
        "done", "completed", "success" -> "✅ Poora hua"
        "cancelled", "canceled", "stopped" -> "⏹ Band"
        "failed", "error" -> "❌ Fail"
        "needs_attention" -> "⚠️ Dhyan do"
        else -> if (status.isEmpty()) "—" else status
    }

    private fun categoryLabel(cat: String): String = when (cat.lowercase()) {
        "apply" -> "📝 Aavedan"
        "track" -> "🔍 Tracking"
        "resume" -> "📄 Resume"
        else -> if (cat.isEmpty()) "" else "🏷️ $cat"
    }

    private fun workCard(t: JSONObject): View {
        val act = context as? Activity ?: return View(context)
        val name = t.optString("name", "Kaam").ifEmpty { "Kaam" }
        val status = t.optString("status", "")
        val category = t.optString("category", "")
        val cardName = t.optJSONObject("context")?.optString("card_name", "").orEmpty()
        val created = t.optString("created_at", "").take(10)
        val taskId = t.optString("id", "")
        val isActive = status.lowercase() in setOf("running", "in_progress", "started", "queued", "needs_user")

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            // v43 UI: rich card — active kaam par gold left strip.
            background = with(FmTheme) { context.richCard() }
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(5), 0, dp(5))
            layoutParams = lp
            try { elevation = dp(2).toFloat() } catch (_: Exception) { }
        }
        // Active kaam: gold accent bar upar.
        if (isActive) {
            card.addView(android.view.View(context).apply {
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(3)
                ).apply { bottomMargin = dp(8) }
                background = with(FmTheme) { context.goldGradient() }
            })
        }
        // Title + badge
        card.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "📝 $name"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(FmTheme.INK))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })
            val badgeKind = when {
                isActive -> "success"
                status.lowercase().contains("cancel") -> "error"
                else -> "info"
            }
            addView(with(FmTheme) { context.statusBadge(badgeFor(status), badgeKind) })
        })
        // Meta lines
        val meta = StringBuilder()
        val catLbl = categoryLabel(category)
        if (catLbl.isNotEmpty()) meta.append("$catLbl  ")
        if (cardName.isNotEmpty()) meta.append("🗂️ $cardName  ")
        if (userName.isNotEmpty()) meta.append("👤 $userName")
        if (meta.isNotEmpty()) {
            card.addView(TextView(context).apply {
                with(FmTheme) { hintText(meta.toString().trim(), 12f) }
                setPadding(0, dp(4), 0, 0)
            })
        }
        if (created.isNotEmpty()) {
            card.addView(TextView(context).apply {
                text = "📅 $created"
                textSize = 11f
                setTextColor(Color.parseColor(FmTheme.INK_FAINT))
            })
        }
        // Buttons
        val btnRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        btnRow.addView(Button(context).apply {
            text = "💬 Chat kholo"
            textSize = 12f
            minWidth = 0
            setTextColor(Color.parseColor(FmTheme.EMERALD_DEEP))
            setOnClickListener { onOpenWorkChat?.invoke(t) }
        })
        if (isActive) {
            btnRow.addView(Button(context).apply {
                text = "⏹ Band karo"
                textSize = 12f
                minWidth = 0
                setTextColor(Color.parseColor(FmTheme.WARNING))
                setOnClickListener { confirmCancelWork(act, t) }
            })
        }
        btnRow.addView(Button(context).apply {
            text = "🗑️"
            textSize = 12f
            minWidth = 0
            setTextColor(Color.parseColor(FmTheme.ERROR))
            setOnClickListener { confirmDeleteWork(act, t) }
        })
        card.addView(btnRow)
        // Tap feedback: halka scale.
        card.setOnTouchListener { v, e ->
            try {
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start()
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            } catch (_: Exception) { }
            false
        }
        return card
    }

    /** ⏹ Band karo — server par task+run cancel + local automation roko. */
    private fun confirmCancelWork(act: Activity, t: JSONObject) {
        if (act.isFinishing || act.isDestroyed) return
        val name = t.optString("name", "Kaam").ifEmpty { "Kaam" }
        AlertDialog.Builder(act)
            .setTitle("⏹ Kaam band karo?")
            .setMessage("\"$name\"\n\nBand karne ke baad automation turant ruk jayegi.")
            .setPositiveButton("Haan, band karo") { _, _ ->
                toast("Band kar raha hun…")
                Thread({
                    val taskId = t.optString("id", "")
                    var ok = false
                    try {
                        // 1. Local automation roko (agar yehi chal raha ho).
                        try {
                            com.formmitra.app.engine.FormRunService.requestCancelActive(context)
                        } catch (_: Exception) { }
                        // 2. Server par task + runs cancel.
                        ok = AgentApi.cancelTask(context, taskId)
                    } catch (_: Exception) { }
                    postUi {
                        if (ok) toast("⏹ Kaam band ho gaya", FmToast.SUCCESS)
                        else toast("Band nahi ho paya — dobara try karo", FmToast.ERROR)
                        refresh()
                    }
                }, "fm-work-cancel").start()
            }
            .setNegativeButton("Rehne do", null)
            .show()
    }

    /** 🗑️ Delete — server se sacchi delete (hard delete). */
    private fun confirmDeleteWork(act: Activity, t: JSONObject) {
        if (act.isFinishing || act.isDestroyed) return
        val name = t.optString("name", "Kaam").ifEmpty { "Kaam" }
        val status = t.optString("status", "")
        // Chal raha kaam — pehle band karo.
        if (status.lowercase() in setOf("running", "in_progress", "started")) {
            toast("Pehle kaam band karo, phir delete karo ⏹")
            return
        }
        AlertDialog.Builder(act)
            .setTitle("🗑️ Kaam delete karo?")
            .setMessage("\"$name\"\n\nYe hamesha ke liye delete ho jayega.")
            .setPositiveButton("Delete karo") { _, _ ->
                // Turant UI se hatao (server ka wait nahi).
                tasks = tasks.filter { it.optString("id") != t.optString("id") }
                render()
                Thread({
                    val ok = try {
                        AgentApi.deleteTask(context, t.optString("id", ""))
                    } catch (_: Exception) { false }
                    postUi {
                        if (ok) toast("🗑️ Delete ho gaya", FmToast.SUCCESS)
                        else {
                            toast("Delete nahi ho paya — wapas la raha hun", FmToast.ERROR)
                            refresh()
                        }
                    }
                }, "fm-work-delete").start()
            }
            .setNegativeButton("Rehne do", null)
            .show()
    }

    /** ❓ Agent guide — Hindi+English me samjhao agent kya-kya karta hai. */
    private fun showAgentGuide(act: Activity) {
        if (act.isFinishing || act.isDestroyed) return
        val guide = """
🤖 Mitra Agent — kaise kaam karta hai?
(How the agent works)

📱 DESKTOP MODE
Agent hamesha desktop browser me kaam karta hai — jaise computer par kholta hai. Poora page dikhta hai, buttons bade dikhte hain.

👆 TAP (click)
• Button ya link par tap karta hai
• Hindi page par sahi jagah tap hota hai

📜 SCROLL
• Neeche/upar scroll karke chhupi cheezen dhundhta hai
• Form ke saare fields tak pahunchta hai

👀 READ (padhna)
• Page padhkar samajhta hai kahan hai, kya bharna hai
• Error aaye to samajhkar sahi karta hai

✍️ FORM FILL
• Aapke card ki details se form bharta hai
• Galat detail kabhi nahi bharta
• Submit karke result check karta hai

🧭 NAVIGATION
• Sahi official link par jata hai
• Galat page par jaye to wapas aata hai

⏳ WAIT
• Page load hone ka intezar karta hai
• Jaldi-baazi me galat step nahi deta

🛡️ SAFETY
• OTP, login password, payment — ye aapse poochhega
• Payment bina aapki permission ke kabhi nahi
• Har kaam ka proof rakhta hai

💬 Aap bas chat me batao kya karna hai — baaki agent sambhal lega!
        """.trimIndent()
        AlertDialog.Builder(act)
            .setTitle("❓ Agent Guide")
            .setMessage(guide)
            .setPositiveButton("Samajh gaya 👍", null)
            .show()
    }

    /** v43 UI: animated rich toast. */
    private fun toast(msg: String, type: String = FmToast.INFO) {
        try {
            FmToast.show(context as? android.app.Activity, msg, type)
        } catch (_: Exception) { }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun postUi(fn: () -> Unit) {
        try { post(Runnable(fn)) } catch (_: Exception) { }
    }
}

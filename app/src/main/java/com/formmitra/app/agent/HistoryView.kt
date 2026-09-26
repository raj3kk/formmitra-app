package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.formmitra.app.WakeWorker
import com.formmitra.app.engine.AgentResume
import com.formmitra.app.engine.UserPrompt
import com.formmitra.app.engine.UserText
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * HistoryView — "cafe wala hisaab" + K2/K3/K4.
 *
 * GET /api/agent/runs se aata hai (server history — delete nahi hota).
 *
 * K3 — filter tabs: All | Pending | Approved | Successful (counts ke saath).
 *   Pending    = running / queued / needs_user / needs_attention / needs_admin
 *                (+ device-local pending detail-requests — K4)
 *   Approved   = user ne approval/payment di thi (payment.status approved/paid/verified)
 *   Successful = done
 * Har entry par status badge + last-update time ("2 ghante pehle").
 *
 * K2 — tap → detail/track screen:
 *   - Adhoore run par "▶️ Yahi se resume karo" — naya task NAHI;
 *     runNow(task_id) + WakeWorker → agent usi step se continue karta hai
 *     (G2 resume). User back karke chala jaye tab bhi automation background
 *     me chalta rehta hai; yahan live status dikhta hai.
 *   - Proof URL ho to "📸 Proof dekho".
 *
 * K4 — Pending tab ke sabse upar device-local pending prompts
 * ("⚠️ Ek detail chahiye") jab tak detail na mile. "✋ Detail do" se
 * dialog khulta hai (ya resume se agent wahi sawal dobara poochta hai) —
 * loop kabhi nahi toot-ta.
 */
class HistoryView(context: Context) : LinearLayout(context) {

    private enum class Tab(val label: String) {
        ALL("All"), PENDING("Pending"), APPROVED("Approved"), SUCCESSFUL("Successful")
    }

    private val listBox: LinearLayout
    private val statusTv: TextView
    private val tabRow: LinearLayout
    private val inboxBtn: Button
    private var loading = false
    private var activeTab = Tab.ALL
    private var allRuns: JSONArray? = null
    // L5: double-tap guard — ek run ka resume ek waqt me ek hi baar
    private val resumeInFlight = mutableSetOf<String>()
    private var promptEntries: List<PendingPromptStore.Entry> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(12), dp(12), dp(12), dp(12))

        // Header: title + 🔔 inbox (unread count)
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "📜 History"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        inboxBtn = Button(context).apply {
            text = "🔔"
            textSize = 18f
            background = with(UiKit) { context.softBtnBg() }
            setOnClickListener {
                NotifInboxView.show(context) { refreshInboxBtn() }
            }
        }
        with(UiKit) { pressFeedback(inboxBtn) }
        header.addView(inboxBtn)
        addView(header)
        statusTv = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(2), 0, dp(6))
        }
        addView(statusTv)

        // K3: filter tabs
        tabRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        }
        addView(tabScroll)

        val refresh = Button(context).apply {
            text = "🔄 Refresh"
            textSize = 14f
            setTextColor(Color.parseColor("#1A73E8"))
            background = with(UiKit) { context.softBtnBg() }
            setOnClickListener { load() }
        }
        with(UiKit) { pressFeedback(refresh) }
        addView(refresh, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(6), 0, 0)
        })
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listBox = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(listBox)
        addView(scroll)
        refreshInboxBtn()
    }

    fun onTabShown() {
        refreshInboxBtn()
        load()
    }
    fun onTabHidden() {}

    /** MainActivity deep link: is run ki detail kholo. */
    fun openRunDetail(runId: String) {
        if (runId.isEmpty()) return
        // Pehle cached runs me dhoondo; na mile to load karke.
        val found = findRun(runId)
        if (found != null) {
            post { showDetail(found) }
        } else {
            activeTab = Tab.ALL
            load(onDone = { post { findRun(runId)?.let { showDetail(it) } } })
        }
    }

    /** MainActivity deep link: is run ka pending prompt entry dikhao. */
    fun openPromptEntry(runId: String) {
        if (runId.isEmpty()) return
        activeTab = Tab.PENDING
        load(onDone = {
            post {
                // In-memory pending ho to dialog seedha kholo.
                val req = try { UserPrompt.pendingRequest() } catch (_: Exception) { null }
                val act = context as? Activity
                if (req != null && req.runId == runId && act != null) {
                    try {
                        PromptDialog.show(act, req)
                        return@post
                    } catch (_: Exception) { }
                }
                toast("⚠️ Detail pending hai — neeche entry par tap karo", long = true)
            }
        })
    }

    // ---------- loading + filtering ----------

    private fun load(onDone: (() -> Unit)? = null) {
        if (loading) return
        loading = true
        statusTv.text = "Load ho raha hai…"
        Thread {
            val runs = try { AgentApi.listRuns(context) } catch (_: Exception) { null }
            val prompts = try { PendingPromptStore.all(context) } catch (_: Exception) { emptyList() }
            post {
                loading = false
                allRuns = runs
                promptEntries = prompts
                // L5: status mismatch fix — server run terminal (done/failed/
                // vetoed) ho chuka par local AgentResume me ab bhi pending
                // dikhe to stale local state saaf karo.
                try { reconcileLocalState(runs) } catch (_: Exception) { }
                render()
                onDone?.invoke()
            }
        }.start()
    }

    /**
     * L5: local (AgentResume) vs server (runs API) status mismatch.
     * Server terminal kahe aur local pending dikhaye → local saaf.
     */
    private fun reconcileLocalState(runs: JSONArray?) {
        if (runs == null) return
        val pending = try { AgentResume.checkPending(context) } catch (_: Exception) { null }
            ?: return
        val pid = pending.runId.ifEmpty { pending.taskId }
        if (pid.isEmpty()) return
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            val rid = r.optString("run_id", r.optString("id", ""))
            if (rid != pid) continue
            val st = r.optString("status", "").lowercase()
            if (st in listOf("done", "failed", "vetoed", "cancelled")) {
                try { AgentResume.clear(context) } catch (_: Exception) { }
            }
            return
        }
    }

    private fun findRun(runId: String): JSONObject? {
        val runs = allRuns ?: return null
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            if (r.optString("run_id", "") == runId ||
                r.optString("id", "") == runId ||
                r.optString("task_id", "") == runId
            ) return r
        }
        return null
    }

    private fun statusOf(r: JSONObject): String =
        r.optString("status", "?").lowercase().ifEmpty { "?" }

    private fun isPendingStatus(s: String) = s in setOf(
        "running", "queued", "needs_user", "needs_attention", "needs_admin"
    )

    private fun isApproved(r: JSONObject): Boolean {
        val ps = r.optJSONObject("payment")?.optString("status", "")?.lowercase() ?: ""
        return ps in setOf("approved", "paid", "verified")
    }

    private fun filterRuns(): List<JSONObject> {
        val runs = allRuns ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            val s = statusOf(r)
            val keep = when (activeTab) {
                Tab.ALL -> true
                Tab.PENDING -> isPendingStatus(s)
                Tab.APPROVED -> isApproved(r)
                Tab.SUCCESSFUL -> s == "done"
            }
            if (keep) out.add(r)
        }
        return out
    }

    private fun tabCount(t: Tab): Int {
        val runs = allRuns ?: return 0
        var n = 0
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            val s = statusOf(r)
            val keep = when (t) {
                Tab.ALL -> true
                Tab.PENDING -> isPendingStatus(s)
                Tab.APPROVED -> isApproved(r)
                Tab.SUCCESSFUL -> s == "done"
            }
            if (keep) n++
        }
        if (t == Tab.PENDING) n += promptEntries.size
        return n
    }

    // ---------- rendering ----------

    private fun render() {
        listBox.removeAllViews()
        renderTabs()
        val runs = allRuns
        if (runs == null) {
            statusTv.text = "Load nahi hua — internet/login check karo"
            return
        }
        val filtered = filterRuns()
        val promptN = if (activeTab == Tab.PENDING || activeTab == Tab.ALL) promptEntries.size else 0
        statusTv.text = when (activeTab) {
            Tab.ALL -> "${runs.length()} kaam • ${promptN} detail pending"
            Tab.PENDING -> "${filtered.size} adhoore kaam • ${promptN} detail pending"
            Tab.APPROVED -> "${filtered.size} approved"
            Tab.SUCCESSFUL -> "${filtered.size} successful"
        }
        if (filtered.isEmpty() && promptEntries.isEmpty()) {
            listBox.addView(TextView(context).apply {
                text = when (activeTab) {
                    Tab.PENDING -> "Koi adhoora kaam nahi — sab track par hai ✅"
                    Tab.APPROVED -> "Abhi koi approval wala kaam nahi."
                    Tab.SUCCESSFUL -> "Abhi koi kaam poora nahi hua."
                    Tab.ALL -> "Abhi koi kaam nahi hua. Agent tab se shuru karo."
                }
                setPadding(0, dp(16), 0, 0)
                setTextColor(Color.GRAY)
            })
            return
        }
        // K4: pending prompts sabse upar (Pending + All tab me)
        if (activeTab == Tab.PENDING || activeTab == Tab.ALL) {
            for (p in promptEntries) {
                val card = promptCard(p)
                listBox.addView(card)
                with(UiKit) { appear(card) }
            }
        }
        for ((i, r) in filtered.withIndex()) {
            val row = runRow(r)
            listBox.addView(row)
            with(UiKit) { appear(row, delayMs = (i * 45L).coerceAtMost(400L)) }
        }
    }

    private fun renderTabs() {
        tabRow.removeAllViews()
        for (t in Tab.values()) {
            val on = t == activeTab
            val b = Button(context).apply {
                text = "${t.label} (${tabCount(t)})"
                textSize = 13f
                setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(
                    if (on) Color.WHITE else Color.parseColor("#5F6368")
                )
                background = with(UiKit) {
                    if (on) context.primaryBtnBg() else context.softBtnBg()
                }
                layoutParams = LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ).apply {
                    val m = with(UiKit) { context.dp(4) }
                    setMargins(m, m, m, dp(8))
                }
                setOnClickListener {
                    activeTab = t
                    render()
                }
            }
            with(UiKit) { pressFeedback(b) }
            tabRow.addView(b)
        }
    }

    private fun refreshInboxBtn() {
        try {
            val n = NotifStore.unreadCount(context)
            inboxBtn.text = if (n > 0) "🔔($n)" else "🔔"
        } catch (_: Exception) { }
    }

    // ---------- K4: pending prompt card ----------

    private fun promptCard(p: PendingPromptStore.Entry): LinearLayout {
        val kindIcon = when (p.kind) {
            "otp" -> "🔢"
            "payment" -> "💰"
            "choice" -> "🔘"
            "document" -> "📄"
            "login" -> "🔐"
            else -> "✋"
        }
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = with(UiKit) { context.tintCard("#FFF8E1", "#F9A825") }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(6), 0, dp(6))
            }
            isClickable = true
            isFocusable = true
        }
        with(UiKit) { pressFeedback(card) }
        card.addView(TextView(context).apply {
            text = "$kindIcon Ek detail chahiye — ${p.title.ifEmpty { "agent ka sawal" }}"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#7B5E00"))
        })
        if (p.message.isNotEmpty()) {
            card.addView(TextView(context).apply {
                text = p.message
                textSize = 13f
                setTextColor(Color.parseColor("#5F6368"))
                maxLines = 2
            })
        }
        card.addView(TextView(context).apply {
            text = "⏳ ${relTime(p.ts)} • jab tak detail na mile, yahi rahega"
            textSize = 12f
            setTextColor(Color.parseColor("#9AA0A6"))
        })
        // Buttons row
        val btnRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val giveBtn = Button(context).apply {
            text = "✋ Detail do"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = with(UiKit) { context.primaryBtnBg() }
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { openPromptDialog(p) }
        }
        with(UiKit) { pressFeedback(giveBtn) }
        btnRow.addView(giveBtn)
        val resumeBtn = Button(context).apply {
            text = "▶️ Resume"
            textSize = 13f
            setTextColor(Color.parseColor("#1A73E8"))
            background = with(UiKit) { context.softBtnBg() }
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            setOnClickListener { resumePromptRun(p) }
        }
        with(UiKit) { pressFeedback(resumeBtn) }
        btnRow.addView(resumeBtn)
        val dismissBtn = Button(context).apply {
            text = "✖️"
            textSize = 13f
            background = with(UiKit) { context.softBtnBg() }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                // L3b: destructive confirm — prompt hatane se agent wahi
                // ruka rahega; galti se tap par confirm mango.
                AlertDialog.Builder(context)
                    .setTitle("Prompt hatao?")
                    .setMessage(
                        "Ye sawal hata diya jayega — agent isi step par " +
                            "ruka rahega jab tak dobara resume na karo."
                    )
                    .setPositiveButton("🗑️ Hatao") { d, _ ->
                        try {
                            PendingPromptStore.clear(context, p.runId)
                            NotifCenter.cancel(context, NotifCenter.Cat.DETAIL, p.runId)
                            NotifCenter.cancel(context, NotifCenter.Cat.APPROVAL, p.runId)
                        } catch (_: Exception) { }
                        d.dismiss()
                        load()
                    }
                    .setNegativeButton("Rehne do", null)
                    .show()
            }
        }
        btnRow.addView(dismissBtn)
        card.addView(btnRow)
        card.setOnClickListener { openPromptDialog(p) }
        return card
    }

    /** In-memory pending ho to dialog seedha kholo, nahi to resume ka rasta batao. */
    /**
     * v29 zero-crash gate: toast kabhi crash na kare — context destroyed
     * Activity ho to Toast.makeText throw karta hai (UI thread = crash).
     */
    private fun toast(msg: String, long: Boolean = false) {
        try {
            Toast.makeText(
                context, msg,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) { }
    }

    private fun openPromptDialog(p: PendingPromptStore.Entry) {
        val req = try { UserPrompt.pendingRequest() } catch (_: Exception) { null }
        val act = context as? Activity
        if (req != null && req.runId == p.runId && act != null) {
            try {
                PromptDialog.show(act, req)
                return
            } catch (_: Exception) { }
        }
        // App restart ho chuki / loop timeout par ruk gaya — resume karo,
        // agent usi step se wahi sawal dobara poochhega (loop nahi toot-ta).
        AlertDialog.Builder(context)
            .setTitle("✋ Detail pending hai")
            .setMessage(
                "\"${p.title}\"\n\n" +
                    "Agent abhi is sawal ka intezaar kar raha hai (ya ruk gaya hai). " +
                    "Resume dabao — agent usi step se wahi sawal dobara poochhega, " +
                    "shuru se nahi."
            )
            .setPositiveButton("▶️ Resume karo") { d, _ ->
                d.dismiss()
                resumePromptRun(p)
            }
            .setNegativeButton("Baad me", null)
            .show()
    }

    /** Pending prompt wale run ko resume — WorkingMode ON + WakeWorker. */
    private fun resumePromptRun(p: PendingPromptStore.Entry) {
        // L5: double-tap → double resume nahi
        val flightKey = "prompt:${p.runId}"
        if (!resumeInFlight.add(flightKey)) return
        toast("Resume ho raha hai…")
        Thread({
            try {
                val pending = try { AgentResume.checkPending(context) } catch (_: Exception) { null }
                val match = pending != null &&
                    (pending.runId == p.runId || pending.taskId == p.runId)
                if (match) {
                    // Local pending state hai — usi step se resume.
                    WorkingMode.setEnabled(context, true)
                    WakeWorker.enqueue(context)
                    post {
                        toast(
                            "▶️ Usi step se resume ho raha hai — sawal phir aayega",
                            long = true
                        )
                    }
                } else {
                    // Local state nahi — server run ko re-queue karne ki koshish.
                    val run = findRun(p.runId)
                    val taskId = run?.optString("task_id", "") ?: ""
                    var ok = false
                    if (taskId.isNotEmpty()) {
                        val code = try { AgentApi.runNow(context, taskId) } catch (_: Exception) { -1 }
                        ok = code in 200..299
                    }
                    WorkingMode.setEnabled(context, true)
                    WakeWorker.enqueue(context)
                    post {
                        toast(
                            if (ok) "▶️ Resume ho raha hai — agent wahi sawal poochhega"
                            else "▶️ Wake bhej diya — agent jald wahi sawal poochhega",
                            long = true
                        )
                    }
                }
            } catch (t: Throwable) {
                post {
                    try {
                        com.formmitra.app.engine.ErrorCatcher.show(
                            context, "Resume karte waqt", t,
                            sessionId = "hst" + System.currentTimeMillis().toString(36)
                        )
                    } catch (_: Exception) {
                        toast("⚠️ Resume me dikkat — dobara try karo")
                    }
                }
            } finally {
                resumeInFlight.remove(flightKey)
            }
        }, "fm-prompt-resume").start()
    }

    // ---------- run rows (K2/K3) ----------

    private fun badgeFor(status: String): Pair<String, String> = when (status) {
        "done" -> "✅ Ho gaya" to "#0E7C5B"
        "failed" -> "❌ Fail" to "#B00020"
        "vetoed" -> "🛑 Roka gaya" to "#B06000"
        "needs_user" -> "✋ Detail chahiye" to "#B06000"
        "needs_attention" -> "⚠️ Dhyaan do" to "#B06000"
        "needs_admin" -> "🔐 Admin/Captcha" to "#B06000"
        "running" -> "⏳ Chal raha" to "#1565C0"
        "queued" -> "🕘 Queue me" to "#5F6368"
        else -> "• $status" to "#555555"
    }

    private fun runRow(r: JSONObject): LinearLayout {
        val status = statusOf(r)
        val (badge, color) = badgeFor(status)
        val goal = r.optString("goal", r.optString("task_name", "Kaam")).take(60)
        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = with(UiKit) { context.tintCard("#FFFFFF", "#E2E5EA") }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(6), 0, dp(6))
            }
            isClickable = true
            isFocusable = true
        }
        with(UiKit) { pressFeedback(row) }
        // Title + badge chip
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(context).apply {
            text = goal
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#202124"))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 2
        })
        topRow.addView(badgeChip(badge, color))
        row.addView(topRow)
        // Meta: live status + steps + time
        val meta = StringBuilder()
        val steps = r.optInt("steps_taken", -1)
        if (status == "running" && steps >= 0) meta.append("step $steps • ")
        else if (steps >= 0) meta.append("$steps steps • ")
        val updated = r.optString("finished_at", "")
            .ifEmpty { r.optString("updated_at", "") }
            .ifEmpty { r.optString("created_at", "") }
        val rt = relTimeStr(updated)
        if (rt.isNotEmpty()) meta.append(rt)
        val pay = r.optJSONObject("payment")?.optString("status", "")
        if (!pay.isNullOrEmpty() && pay != "none") meta.append(" • 💰$pay")
        if (isApproved(r)) meta.append(" • 👍approved")
        row.addView(TextView(context).apply {
            text = meta.toString().trim().trimEnd('•').trim()
            textSize = 12f
            setTextColor(Color.GRAY)
        })
        // v37 ONE-TAP REPEAT — "🔁 Phir se karo": naya run, same goal +
        // card/device details reuse; pattern ho to seedha replay
        // (planning/sawal nahi). Chal rahe (pending) run par nahi — uske
        // liye detail me "Yahi se resume karo" hai.
        if (!isPendingStatus(status)) {
            val repeatBtn = Button(context).apply {
                text = "🔁 Phir se karo"
                textSize = 13f
                setTextColor(Color.parseColor("#1A73E8"))
                background = with(UiKit) { context.softBtnBg() }
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, dp(6), 0, 0)
                }
                setOnClickListener { repeatRun(r) }
            }
            with(UiKit) { pressFeedback(repeatBtn) }
            row.addView(repeatBtn)
        }
        row.setOnClickListener { showDetail(r) }
        return row
    }

    private fun badgeChip(label: String, color: String): TextView {
        val bg = when (color) {
            "#0E7C5B" -> "#E6F4EA"
            "#B00020" -> "#FCE8E6"
            "#B06000" -> "#FEF7E0"
            "#1565C0" -> "#E8F0FE"
            else -> "#F1F3F4"
        }
        return TextView(context).apply {
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(color))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bg))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        }
    }

    // ---------- detail / track screen (K2) ----------

    private fun showDetail(r: JSONObject) {
        val status = statusOf(r)
        val runId = r.optString("run_id", r.optString("id", ""))
        val taskId = r.optString("task_id", "")
        val sb = StringBuilder()
        val (badge, _) = badgeFor(status)
        sb.append("Status: ").append(badge).append("\n")
        sb.append("Goal: ").append(r.optString("goal", "-")).append("\n")
        sb.append("URL: ").append(r.optString("url", r.optString("target_url", "-"))).append("\n")
        sb.append("Steps: ").append(r.optInt("steps_taken", 0)).append("\n")
        r.optString("created_at", "").ifEmpty { null }?.let { sb.append("Shuru: $it\n") }
        r.optString("finished_at", "").ifEmpty { null }?.let { sb.append("Khatm: $it\n") }
        val summary = r.optString("summary", "").ifEmpty { r.optString("error", "") }
        // L3a: technical summary user ko mat dikhao — simple Hinglish
        if (summary.isNotEmpty()) sb.append("\n${UserText.friendly(summary)}\n")
        val pay = r.optJSONObject("payment")
        if (pay != null && pay.optString("status", "none") != "none") {
            sb.append("\n💰 Payment: ${pay.optString("status")}")
            pay.optString("amount", "").ifEmpty { null }?.let { sb.append(" • ₹$it") }
            pay.optString("merchant", "").ifEmpty { null }?.let { sb.append(" • $it") }
            pay.optString("method", "").ifEmpty { null }?.let { sb.append(" • via $it") }
            pay.optString("reason", "").ifEmpty { null }?.let { sb.append("\nWajah: $it") }
            pay.optString("txn_ref", "").ifEmpty { null }?.let { sb.append("\nTxn: $it") }
            sb.append("\n")
        }
        val proof = r.optString("proof_url", "")
        val tv = TextView(context).apply {
            text = sb.toString()
            textSize = 14f
            setPadding(40, 24, 40, 8)
        }
        val b = AlertDialog.Builder(context)
            .setTitle("📜 Kaam ki detail")
            .setView(ScrollView(context).apply { addView(tv) })
            .setPositiveButton("Band karo", null)
        if (proof.isNotEmpty()) {
            b.setNeutralButton("📸 Proof dekho") { _, _ ->
                toast("Proof: $proof", long = true)
            }
        }
        // K2: adhoore run par resume — naya task NAHI, usi run ka track.
        if (isPendingStatus(status) && (taskId.isNotEmpty() || runId.isNotEmpty())) {
            b.setNegativeButton("▶️ Yahi se resume karo") { d, _ ->
                d.dismiss()
                resumeRun(r, runId, taskId)
            }
        } else {
            // v37 ONE-TAP REPEAT — poore/purane kaam ko ek tap me dobara.
            b.setNegativeButton("🔁 Phir se karo") { d, _ ->
                d.dismiss()
                repeatRun(r)
            }
        }
        b.show()
    }

    /**
     * K2: resume — naya task shuru NAHI hota. Server run ko re-queue
     * (runNow) + WakeWorker wake → agent usi step se continue, History me
     * live status dikhta rehta hai.
     */
    private fun resumeRun(r: JSONObject, runId: String, taskId: String) {
        val goal = r.optString("goal", "Kaam")
        AlertDialog.Builder(context)
            .setTitle("▶️ Resume karo?")
            .setMessage(
                "\"${goal.take(60)}\"\n\n" +
                    "Naya kaam shuru NAHI hoga — yehi run usi step se " +
                    "aage badhega. App band karke chale jao, background me " +
                    "chalta rahega; History me live status dikhega."
            )
            .setPositiveButton("▶️ Haan, resume karo") { d, _ ->
                d.dismiss()
                // L5: double-tap → double resume nahi
                val flightKey = "run:$runId:$taskId"
                if (!resumeInFlight.add(flightKey)) return@setPositiveButton
                toast("Resume ho raha hai…")
                Thread({
                    try {
                        var ok = false
                        if (taskId.isNotEmpty()) {
                            val code = try { AgentApi.runNow(context, taskId) }
                            catch (_: Exception) { -1 }
                            ok = code in 200..299
                        } else {
                            // Local pending state se resume (G2).
                            val pending = try { AgentResume.checkPending(context) }
                            catch (_: Exception) { null }
                            ok = pending != null &&
                                (pending.runId == runId || pending.taskId == runId)
                        }
                        WorkingMode.setEnabled(context, true)
                        WakeWorker.enqueue(context)
                        resumeInFlight.remove(flightKey)
                        post {
                            toast(
                                if (ok) "▶️ Resume ho gaya — History me track karo"
                                else "▶️ Wake bhej diya — jald resume hoga",
                                long = true
                            )
                            load()
                        }
                    } catch (t: Throwable) {
                        resumeInFlight.remove(flightKey)
                        post {
                            try {
                                com.formmitra.app.engine.ErrorCatcher.show(
                                    context, "Resume karte waqt", t,
                                    sessionId = "hst" + System.currentTimeMillis().toString(36)
                                )
                            } catch (_: Exception) {
                                toast("⚠️ Resume me dikkat — dobara try karo")
                            }
                        }
                    }
                }, "fm-run-resume").start()
            }
            .setNegativeButton("Rehne do", null)
            .show()
    }

    // ---------- v37: ONE-TAP REPEAT ("🔁 Phir se karo") ----------
    //
    // History entry se naya run:
    //  - same goal + card/device/user-memory details reuse (knownDetails —
    //    pata wali details dobara NAHI poochhi jayengi)
    //  - learned (local) ya global (playbook) pattern ho → AgentLoop seedha
    //    replay karega (planning/sawal nahi); hash mismatch → verify-then-
    //    adapt; nayi value → EK baar batch me poochhega
    //  - payment/destructive → gate (confirm dialog; loop me existing
    //    PaymentFlow/DestructiveGate)
    //  - IdempotencyGuard: double-tap/duplicate run nahi

    private fun repeatRun(r: JSONObject) {
        val goal = r.optString("goal", r.optString("task_name", "")).trim()
        val url = r.optString("url", r.optString("target_url", "")).trim()
        if (goal.isEmpty() || url.isEmpty()) {
            toast("Ye entry dobara nahi ho sakti — goal/link missing hai")
            return
        }
        // Double-tap guard (resumeInFlight shared set).
        val flightKey = "repeat:$goal|$url"
        if (!resumeInFlight.add(flightKey)) return
        toast("Phir se taiyaar ho raha hai…")
        Thread({
            try {
                // known details: device-saved + user-memory facts (card
                // values unlock ke baad loop khud uthayega).
                val known = LinkedHashMap<String, String>()
                try {
                    known.putAll(DetailStore.loadAll(context))
                } catch (_: Exception) { }
                try {
                    known.putAll(
                        com.formmitra.app.engine.RunMemory.userFacts(context)
                    )
                } catch (_: Exception) { }
                // Pattern eligibility (local + global) — background thread.
                val host = try {
                    java.net.URL(url).host.lowercase()
                } catch (_: Exception) {
                    ""
                }
                val localEligible = try {
                    val pk = LearnLogic.workPatternKey(goal, host)
                    val e = if (pk.isNotEmpty()) WorkPatternStore.find(context, pk) else null
                    e != null && LearnLogic.shouldReplay(
                        e.optInt("success", 0), e.optInt("fail", 0),
                        e.optLong("updated_at", 0), System.currentTimeMillis()
                    )
                } catch (_: Exception) {
                    false
                }
                val globalEligible = try {
                    GlobalPlaybook.resolve(context, goal, host, "", "") != null
                } catch (_: Exception) {
                    false
                }
                val plan = RepeatRun.decide(r, known, localEligible, globalEligible)
                post {
                    resumeInFlight.remove(flightKey)
                    if (plan == null) {
                        toast("Dobara nahi ho paya — entry me kami hai")
                        return@post
                    }
                    startRepeat(plan)
                }
            } catch (t: Throwable) {
                post {
                    resumeInFlight.remove(flightKey)
                    try {
                        com.formmitra.app.engine.ErrorCatcher.show(
                            context, "Phir se karte waqt", t,
                            sessionId = "rpt" + System.currentTimeMillis().toString(36)
                        )
                    } catch (_: Exception) {
                        toast("⚠️ Dobara shuru me dikkat — phir try karo")
                    }
                }
            }
        }, "fm-repeat").start()
    }

    /**
     * Repeat plan ko run me badlo. Gate ho to confirm dialog (payment →
     * loop ka existing PaymentFlow/DestructiveGate sambhalega).
     */
    private fun startRepeat(plan: RepeatRun.RepeatPlan) {
        val go: () -> Unit = go@{
            // IdempotencyGuard — duplicate run nahi (nayi runId).
            val acquired = try {
                com.formmitra.app.engine.IdempotencyGuard.tryAcquire(
                    context, "", plan.goal, plan.url
                )
            } catch (_: Exception) {
                true
            }
            if (!acquired) {
                toast("Ye kaam abhi-abhi shuru hua hai — duplicate nahi banaya")
                return@go
            }
            toast("🔁 Phir se shuru ho raha hai…")
            Thread({
                try {
                    val (code, taskId) = AgentApi.createTask(
                        context, plan.goal, plan.url, plan.category, plan.knownDetails
                    )
                    if (taskId.isNullOrEmpty()) {
                        post {
                            toast(
                                if (code == 401) "Pehle Profile tab me login karo 🔑"
                                else "Kaam shuru nahi ho paya — dobara try karo"
                            )
                        }
                        return@Thread
                    }
                    val runCode = try {
                        AgentApi.runNow(context, taskId)
                    } catch (_: Exception) {
                        -1
                    }
                    try {
                        WorkingMode.setEnabled(context, true)
                    } catch (_: Exception) { }
                    try {
                        WakeWorker.enqueue(context)
                    } catch (_: Exception) { }
                    try {
                        com.formmitra.app.Scheduler.kickNow(context)
                    } catch (_: Exception) { }
                    post {
                        toast(
                            if (runCode in 200..299)
                                "🔁 Phir se shuru ho gaya — History me track karo"
                            else "Task ban gaya — jald shuru hoga",
                            long = true
                        )
                        load()
                    }
                } catch (t: Throwable) {
                    post {
                        try {
                            com.formmitra.app.engine.ErrorCatcher.show(
                                context, "Phir se karte waqt", t,
                                sessionId = "rpt" + System.currentTimeMillis().toString(36)
                            )
                        } catch (_: Exception) {
                            toast("⚠️ Dobara shuru me dikkat — phir try karo")
                        }
                    }
                }
            }, "fm-repeat-start").start()
        }
        if (plan.needsGate) {
            // Payment/destructive repeat → shuru se pehle confirm.
            AlertDialog.Builder(context)
                .setTitle("🔁 Phir se karo?")
                .setMessage(
                    "\"${plan.goal.take(60)}\"\n\n${plan.gateReason}\n\nJari rakhu?"
                )
                .setPositiveButton("✅ Haan, karo") { d, _ ->
                    d.dismiss()
                    go()
                }
                .setNegativeButton("Rehne do", null)
                .show()
        } else {
            go()
        }
    }

    // ---------- relative time ----------

    /** ISO-8601 / epoch / raw → "5 min pehle" jaisa text. */
    private fun relTimeStr(raw: String): String {
        if (raw.isEmpty()) return ""
        val ts = parseTime(raw)
        return if (ts > 0) relTime(ts) else raw.take(16)
    }

    private fun relTime(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        if (diff < 0) return "abhi"
        return when {
            diff < 60_000 -> "abhi"
            diff < 3_600_000 -> "${diff / 60_000} min pehle"
            diff < 86_400_000 -> "${diff / 3_600_000} ghante pehle"
            diff < 7 * 86_400_000 -> "${diff / 86_400_000} din pehle"
            else -> try {
                SimpleDateFormat("dd MMM", Locale("en", "IN")).format(Date(ts))
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun parseTime(raw: String): Long {
        // epoch millis / seconds
        try {
            val n = raw.trim().toLong()
            return if (n > 1_000_000_000_000L) n else n * 1000
        } catch (_: Exception) { }
        // ISO-8601
        val fmts = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (f in fmts) {
            try {
                val sdf = SimpleDateFormat(f, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val d = sdf.parse(raw.trim())
                if (d != null) return d.time
            } catch (_: Exception) { }
        }
        return 0
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

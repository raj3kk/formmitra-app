package com.formmitra.app.agent

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * HistoryView — "cafe wala hisaab": user ne agent se kya-kya karwaya, kab,
 * kya hua (done/failed/vetoed/needs_user), payment hua ya nahi, proof kahan hai.
 * GET /api/agent/runs se aata hai (server history — delete nahi hota).
 */
class HistoryView(context: Context) : LinearLayout(context) {

    private val listBox: LinearLayout
    private val statusTv: TextView
    private var loading = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(12), dp(12), dp(12), dp(12))

        addView(TextView(context).apply {
            text = "📜 History — agent ne kya-kya kiya"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        })
        statusTv = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
        }
        addView(statusTv)
        val refresh = Button(context).apply {
            text = "🔄 Refresh"
            setOnClickListener { load() }
        }
        addView(refresh)
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listBox = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(listBox)
        addView(scroll)
    }

    fun onTabShown() = load()
    fun onTabHidden() {}

    private fun load() {
        if (loading) return
        loading = true
        statusTv.text = "Load ho raha hai…"
        Thread {
            val runs = try { AgentApi.listRuns(context) } catch (_: Exception) { null }
            post {
                loading = false
                render(runs)
            }
        }.start()
    }

    private fun render(runs: JSONArray?) {
        listBox.removeAllViews()
        if (runs == null) {
            statusTv.text = "Load nahi hua — internet/login check karo"
            return
        }
        statusTv.text = "${runs.length()} kaam mile"
        if (runs.length() == 0) {
            listBox.addView(TextView(context).apply {
                text = "Abhi koi kaam nahi hua. Agent tab se shuru karo."
                setPadding(0, dp(16), 0, 0)
            })
            return
        }
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            listBox.addView(runRow(r))
        }
    }

    private fun runRow(r: JSONObject): LinearLayout {
        val status = r.optString("status", "?")
        val (icon, color) = when (status) {
            "done" -> "✅" to "#0E7C5B"
            "failed" -> "❌" to "#B00020"
            "vetoed" -> "🛑" to "#B06000"
            "needs_user" -> "⚠️" to "#B06000"
            "running" -> "⏳" to "#1565C0"
            else -> "•" to "#555555"
        }
        val goal = r.optString("goal", r.optString("task_name", "Kaam")).take(60)
        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.parseColor("#F6F6F6"))
            (layoutParams as? LayoutParams)?.setMargins(0, dp(6), 0, dp(6))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(6), 0, dp(6))
            }
        }
        row.addView(TextView(context).apply {
            text = "$icon $goal"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(color))
        })
        val meta = StringBuilder("$status")
        val steps = r.optInt("steps_taken", -1)
        if (steps >= 0) meta.append(" • $steps steps")
        r.optString("created_at", "").take(16).ifEmpty { null }?.let { meta.append(" • $it") }
        val pay = r.optJSONObject("payment")?.optString("status", "")
        if (!pay.isNullOrEmpty() && pay != "none") meta.append(" • 💰$pay")
        row.addView(TextView(context).apply {
            text = meta.toString()
            textSize = 12f
            setTextColor(Color.GRAY)
        })
        row.setOnClickListener { showDetail(r) }
        return row
    }

    private fun showDetail(r: JSONObject) {
        val sb = StringBuilder()
        sb.append("Status: ").append(r.optString("status", "?")).append("\n")
        sb.append("Goal: ").append(r.optString("goal", "-")).append("\n")
        sb.append("URL: ").append(r.optString("url", r.optString("target_url", "-"))).append("\n")
        sb.append("Steps: ").append(r.optInt("steps_taken", 0)).append("\n")
        r.optString("created_at", "").ifEmpty { null }?.let { sb.append("Shuru: $it\n") }
        r.optString("finished_at", "").ifEmpty { null }?.let { sb.append("Khatm: $it\n") }
        val summary = r.optString("summary", "").ifEmpty { r.optString("error", "") }
        if (summary.isNotEmpty()) sb.append("\n$summary\n")
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
                Toast.makeText(context, "Proof: $proof", Toast.LENGTH_LONG).show()
            }
        }
        b.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

package com.formmitra.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * RunSummaryStore — POINT 21: kaam khatam hote hi end-of-work summary.
 * Service (background) save karta hai, chat entry par card dikhta hai:
 * ho gaya / baaki / tumhara agla kadam / proof.
 */
object RunSummaryStore {

    private const val PREFS = "formmitra_run_summary"
    private const val KEY = "pending"

    data class Summary(
        val runId: String,
        val workName: String,
        val status: String, // done | failed | ...
        val doneText: String,
        val pendingText: String,
        val nextAction: String,
        val proofCount: Int,
        val at: Long
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, s: Summary) {
        if (s.runId.isEmpty()) return
        try {
            val arr = try {
                JSONArray(prefs(ctx).getString(KEY, null) ?: "[]")
            } catch (_: Exception) { JSONArray() }
            // Dedupe: same run dobara nahi.
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optString("runId") == s.runId) return
            }
            arr.put(
                JSONObject()
                    .put("runId", s.runId).put("workName", s.workName)
                    .put("status", s.status).put("doneText", s.doneText)
                    .put("pendingText", s.pendingText)
                    .put("nextAction", s.nextAction)
                    .put("proofCount", s.proofCount)
                    .put("at", s.at)
            )
            // Cap 20.
            while (arr.length() > 20) arr.remove(0)
            prefs(ctx).edit().putString(KEY, arr.toString()).apply()
        } catch (_: Exception) { }
    }

    fun takePending(ctx: Context): List<Summary> {
        val out = mutableListOf<Summary>()
        try {
            val p = prefs(ctx)
            val arr = JSONArray(p.getString(KEY, null) ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Summary(
                        o.optString("runId"), o.optString("workName"),
                        o.optString("status"), o.optString("doneText"),
                        o.optString("pendingText"), o.optString("nextAction"),
                        o.optInt("proofCount"), o.optLong("at")
                    )
                )
            }
            p.edit().remove(KEY).apply()
        } catch (_: Exception) { }
        return out
    }
}

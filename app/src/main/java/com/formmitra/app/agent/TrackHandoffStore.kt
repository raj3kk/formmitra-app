package com.formmitra.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * TrackHandoffStore — POINT 17: Apply poora hone par "Iska status track
 * karu?" ka pending handoff. Service (background) mark karta hai, chat
 * entry par card dikhta hai. Ek run par ek hi baar (dedupe).
 */
object TrackHandoffStore {

    private const val PREFS = "formmitra_track_handoff"
    private const val KEY = "pending"

    data class Handoff(
        val runId: String,
        val workName: String,
        val category: String,
        val doneAt: Long
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Apply run poora → handoff pending karo (sirf apply category). */
    fun markDone(ctx: Context, runId: String, workName: String, category: String) {
        if (runId.isEmpty() || category != "apply") return
        try {
            val arr = allRaw(ctx)
            // Dedupe: ye run pehle se pending/dikha hua.
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optString("runId") == runId) return
            }
            arr.put(
                JSONObject()
                    .put("runId", runId)
                    .put("workName", workName)
                    .put("category", category)
                    .put("doneAt", System.currentTimeMillis())
            )
            prefs(ctx).edit().putString(KEY, arr.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun allRaw(ctx: Context): JSONArray = try {
        JSONArray(prefs(ctx).getString(KEY, null) ?: "[]")
    } catch (_: Exception) { JSONArray() }

    /** Chat entry par pending handoffs lo (le liye → saaf). */
    fun takePending(ctx: Context): List<Handoff> {
        val out = mutableListOf<Handoff>()
        try {
            val arr = allRaw(ctx)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Handoff(
                        o.optString("runId"), o.optString("workName"),
                        o.optString("category"), o.optLong("doneAt")
                    )
                )
            }
            prefs(ctx).edit().remove(KEY).apply()
        } catch (_: Exception) { }
        return out
    }
}

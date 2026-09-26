package com.formmitra.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * CorrectionStore — POINT 20: mid-run field corrections.
 * Chat (AgentChatView) likhta hai, AgentLoop har act() se pehle padhta
 * hai (take = consume). Same run continue — STOP se alag.
 */
object CorrectionStore {

    private const val PREFS = "formmitra_corrections"

    data class Correction(
        val field: String,
        val label: String,
        val value: String,
        val at: Long
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(runId: String) = "corr_$runId"

    fun add(ctx: Context, runId: String, field: String, label: String, value: String) {
        if (runId.isEmpty() || field.isEmpty()) return
        try {
            val arr = try {
                JSONArray(prefs(ctx).getString(key(runId), null) ?: "[]")
            } catch (_: Exception) { JSONArray() }
            // Same field dobara → purana hatao (nayi value jeetegi).
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("field") != field) kept.put(o)
            }
            kept.put(
                JSONObject()
                    .put("field", field).put("label", label)
                    .put("value", value)
                    .put("at", System.currentTimeMillis())
            )
            prefs(ctx).edit().putString(key(runId), kept.toString()).apply()
        } catch (_: Exception) { }
    }

    /** Loop ke liye nikalo (consume — dobara apply nahi hoga). */
    fun take(ctx: Context, runId: String): List<Correction> {
        val out = mutableListOf<Correction>()
        if (runId.isEmpty()) return out
        try {
            val p = prefs(ctx)
            val arr = JSONArray(p.getString(key(runId), null) ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Correction(
                        o.optString("field"), o.optString("label"),
                        o.optString("value"), o.optLong("at")
                    )
                )
            }
            p.edit().remove(key(runId)).apply()
        } catch (_: Exception) { }
        return out
    }

    fun hasPending(ctx: Context, runId: String): Boolean = try {
        val arr = JSONArray(prefs(ctx).getString(key(runId), null) ?: "[]")
        arr.length() > 0
    } catch (_: Exception) { false }
}

package com.formmitra.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * CardUnlockNeeded — POINT 22: run ke beech Card lock ho jaye to chat me
 * one-tap unlock popup. Unlock hote hi run apne aap resume — restart nahi.
 *
 * AgentLoop raise karta hai (card access 401/403 ya token invalid par),
 * AgentChatView card dikhata hai, unlock par AgentResume se run resume.
 */
object CardUnlockNeeded {

    private const val PREFS = "formmitra_card_unlock_needed"
    private const val KEY = "pending"

    data class Need(
        val runId: String,
        val taskName: String,
        val cardId: String,
        val cardName: String,
        val at: Long
    )

    private val listeners = mutableListOf<(Need) -> Unit>()

    fun addListener(l: (Need) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Need) -> Unit) { listeners.remove(l) }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Loop se raise karo (dedupe: same run+card ek baar). */
    fun raise(ctx: Context, runId: String, taskName: String, cardId: String, cardName: String) {
        if (runId.isEmpty() || cardId.isEmpty()) return
        try {
            val arr = try {
                JSONArray(prefs(ctx).getString(KEY, null) ?: "[]")
            } catch (_: Exception) { JSONArray() }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("runId") == runId &&
                    o.optString("cardId") == cardId
                ) return
            }
            arr.put(
                JSONObject()
                    .put("runId", runId).put("taskName", taskName)
                    .put("cardId", cardId).put("cardName", cardName)
                    .put("at", System.currentTimeMillis())
            )
            prefs(ctx).edit().putString(KEY, arr.toString()).apply()
            val need = Need(runId, taskName, cardId, cardName, System.currentTimeMillis())
            listeners.toList().forEach { l ->
                try { l(need) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    /** Chat entry par pending needs lo. */
    fun takePending(ctx: Context): List<Need> {
        val out = mutableListOf<Need>()
        try {
            val p = prefs(ctx)
            val arr = JSONArray(p.getString(KEY, null) ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Need(
                        o.optString("runId"), o.optString("taskName"),
                        o.optString("cardId"), o.optString("cardName"),
                        o.optLong("at")
                    )
                )
            }
            p.edit().remove(KEY).apply()
        } catch (_: Exception) { }
        return out
    }

    /** Unlock ho gaya → need hatao. */
    fun resolved(ctx: Context, runId: String, cardId: String) {
        try {
            val p = prefs(ctx)
            val arr = JSONArray(p.getString(KEY, null) ?: "[]")
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!(o.optString("runId") == runId &&
                        o.optString("cardId") == cardId)
                ) kept.put(o)
            }
            p.edit().putString(KEY, kept.toString()).apply()
        } catch (_: Exception) { }
    }
}

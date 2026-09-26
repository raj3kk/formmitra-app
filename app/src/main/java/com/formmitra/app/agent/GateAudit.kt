package com.formmitra.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * GateAudit — gate/OTP/detail-batch decisions ka chhota audit log.
 * POINT 15 (gate audit) + POINT 26 (OTP auto vs manual audit) dono isi
 * par banenge. Run-wise, prefs me persist (app restart par bhi).
 *
 * Entry: {t: epoch ms, kind: "otp"|"login"|"payment"|"choice"|"document"|...,
 *          event: "auto_read"|"manual"|"approved"|"declined"|"expired"|...,
 *          detail: "..."} — secrets (OTP/PIN/password) KABHI NAHI.
 *
 * v36 refine point 4 — GATES AUDIT TRAIL: har gate ke liye
 *   kab aaya (t_open) + kitni der ruka (wait_ms) + kisne jawab diya
 *   (answered_by: "user"|"agent"|"auto") — history me.
 * openGate() gate khulne par, closeGate() jawab milne par.
 */
object GateAudit {

    private const val PREFS = "formmitra_gate_audit"
    private const val MAX_PER_RUN = 100

    /** Kisne gate ka jawab diya. */
    const val BY_USER = "user"   // user ne khud tap/type karke jawab diya
    const val BY_AGENT = "agent" // agent ne khud resolve kiya (auto-read OTP, card se)
    const val BY_AUTO = "auto"   // system ne (timeout/expire/deny-default)

    private fun key(runId: String) = "audit_$runId"

    fun log(ctx: Context, runId: String, kind: String, event: String, detail: String = "") {
        if (runId.isEmpty()) return
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = try {
                JSONArray(p.getString(key(runId), null) ?: "[]")
            } catch (_: Exception) { JSONArray() }
            arr.put(
                JSONObject()
                    .put("t", System.currentTimeMillis())
                    .put("kind", kind)
                    .put("event", event)
                    .put("detail", detail.take(200))
            )
            // Cap: purane entries hatao.
            while (arr.length() > MAX_PER_RUN) arr.remove(0)
            p.edit().putString(key(runId), arr.toString()).apply()
        } catch (_: Exception) { }
    }

    /**
     * Gate khula — kab aaya record karo. @return gateId (closeGate me do).
     */
    fun openGate(ctx: Context, runId: String, kind: String, title: String = ""): String {
        if (runId.isEmpty()) return ""
        val gateId = "g${System.currentTimeMillis().toString(36)}${(0..999).random()}"
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = try {
                JSONArray(p.getString(key(runId), null) ?: "[]")
            } catch (_: Exception) { JSONArray() }
            arr.put(
                JSONObject()
                    .put("gate_id", gateId)
                    .put("t_open", System.currentTimeMillis())
                    .put("kind", kind)
                    .put("event", "opened")
                    .put("status", "open")
                    .put("title", title.take(120))
            )
            while (arr.length() > MAX_PER_RUN) arr.remove(0)
            p.edit().putString(key(runId), arr.toString()).apply()
        } catch (_: Exception) { }
        return gateId
    }

    /**
     * Gate band — kitni der ruka + kisne jawab diya record karo.
     * @param decision "approved"|"declined"|"answered"|"expired"|...
     * @param answeredBy BY_USER | BY_AGENT | BY_AUTO
     */
    fun closeGate(
        ctx: Context, runId: String, gateId: String,
        decision: String, answeredBy: String, detail: String = ""
    ) {
        if (runId.isEmpty() || gateId.isEmpty()) return
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = p.getString(key(runId), null) ?: return
            val arr = try { JSONArray(raw) } catch (_: Exception) { return }
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("gate_id") == gateId && o.optString("status") == "open") {
                    val tOpen = o.optLong("t_open", now)
                    o.put("t_close", now)
                    o.put("wait_ms", (now - tOpen).coerceAtLeast(0))
                    o.put("event", "closed")
                    o.put("status", "closed")
                    o.put("decision", decision.take(60))
                    o.put(
                        "answered_by",
                        if (answeredBy in setOf(BY_USER, BY_AGENT, BY_AUTO)) answeredBy
                        else BY_AUTO
                    )
                    if (detail.isNotEmpty()) o.put("detail", detail.take(200))
                    break
                }
            }
            p.edit().putString(key(runId), arr.toString()).apply()
        } catch (_: Exception) { }
    }

    /** Pure helper — wait duration (selftest). */
    fun waitMs(tOpen: Long, tClose: Long): Long = (tClose - tOpen).coerceAtLeast(0)

    /** Pure helper — wait ko user-bhasha me ("2 min 30 sec ruka"). */
    fun waitText(waitMs: Long): String {
        val s = waitMs / 1000
        return when {
            s < 5 -> "turant"
            s < 60 -> "$s sec ruka"
            else -> "${s / 60} min ${s % 60} sec ruka"
        }
    }

    fun forRun(ctx: Context, runId: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        if (runId.isEmpty()) return out
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(p.getString(key(runId), null) ?: "[]")
            for (i in 0 until arr.length()) {
                (arr.optJSONObject(i))?.let { out.add(it) }
            }
        } catch (_: Exception) { }
        return out
    }

    fun clear(ctx: Context, runId: String) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(key(runId)).apply()
        } catch (_: Exception) { }
    }
}

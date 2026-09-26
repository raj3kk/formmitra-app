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
 */
object GateAudit {

    private const val PREFS = "formmitra_gate_audit"
    private const val MAX_PER_RUN = 100

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

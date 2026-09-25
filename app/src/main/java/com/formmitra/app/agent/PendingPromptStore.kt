package com.formmitra.app.agent

import android.content.Context
import com.formmitra.app.engine.UserPrompt
import org.json.JSONArray
import org.json.JSONObject

/**
 * PendingPromptStore — K4: agent → user detail-request loop kabhi na toote.
 *
 * UserPrompt.ask() jab bhi agent ka sawal uthata hai (otp/input/choice/
 * payment/document/login), FmApp ka listener yahan entry save karta hai:
 * {runId, kind, title, message, time}.
 *
 * - App kill/reboot ke baad bhi entry bachi rehti hai (SharedPreferences).
 * - User jawab de (PromptDialog → UserPrompt.answer) ya cancel kare to
 *   entry hat ti hai — tabhi notification bhi cancel hoti hai.
 * - Timeout par (user ne jawab nahi diya) entry REHTI HAI — loop toota nahi:
 *   History ke Pending tab me "⚠️ Ek detail chahiye" card dikhta hai,
 *   "▶️ Resume karo" se agent usi step se wapas wahi sawal poochta hai.
 * - 24 ghante se purani entries auto-prune (stale nahi dikhengi).
 *
 * Koi secret value yahan KABHI save nahi hoti — sirf sawal ka title/message
 * (jaise "OTP daalo"), user ka jawab nahi.
 */
object PendingPromptStore {
    private const val PREFS = "fm_pending_prompts"
    private const val KEY = "prompts"
    private const val MAX = 10
    private const val STALE_MS = 24L * 60 * 60 * 1000

    data class Entry(
        val runId: String,
        val kind: String,
        val title: String,
        val message: String,
        val ts: Long
    )

    /** Sawal utha — save karo (same runId dedupe). */
    fun raise(ctx: Context, req: UserPrompt.Request) {
        try {
            val items = loadRaw(ctx).toMutableList()
            items.removeAll { it.runId == req.runId }
            items.add(
                0, Entry(
                    runId = req.runId,
                    kind = req.kind,
                    title = req.title.take(80),
                    message = req.message.take(200),
                    ts = System.currentTimeMillis()
                )
            )
            persist(ctx, items.take(MAX))
        } catch (_: Exception) { }
    }

    /** Jawab mil gaya / cancel hua — entry hatao. */
    fun clear(ctx: Context, runId: String) {
        try {
            val items = loadRaw(ctx).filter { it.runId != runId }
            persist(ctx, items)
        } catch (_: Exception) { }
    }

    /**
     * L5: remote (FCM) se aaya prompt — local UserPrompt.Request nahi hai,
     * phir bhi Pending tab + deep-link resume ke liye persist karo taaki
     * tap par khaali History na khule.
     */
    fun raiseRemote(
        ctx: Context, runId: String, kind: String, title: String, message: String
    ) {
        try {
            val items = loadRaw(ctx).toMutableList()
            items.removeAll { it.runId == runId }
            items.add(
                0, Entry(
                    runId = runId,
                    kind = kind,
                    title = title.take(80),
                    message = message.take(200),
                    ts = System.currentTimeMillis()
                )
            )
            persist(ctx, items.take(MAX))
        } catch (_: Exception) { }
    }

    /** Sab pending sawal (naye se purane), stale prune ke saath. */
    fun all(ctx: Context): List<Entry> = try {
        val fresh = loadRaw(ctx).filter {
            System.currentTimeMillis() - it.ts < STALE_MS
        }
        if (fresh.size != loadRaw(ctx).size) persist(ctx, fresh)
        fresh
    } catch (_: Exception) {
        emptyList()
    }

    fun get(ctx: Context, runId: String): Entry? =
        all(ctx).firstOrNull { it.runId == runId }

    fun hasPending(ctx: Context): Boolean = all(ctx).isNotEmpty()

    // ---------- internal ----------

    private fun loadRaw(ctx: Context): List<Entry> {
        val raw = try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<Entry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val runId = o.optString("runId", "")
                if (runId.isEmpty()) continue
                out.add(
                    Entry(
                        runId = runId,
                        kind = o.optString("kind", "input"),
                        title = o.optString("title", ""),
                        message = o.optString("message", ""),
                        ts = o.optLong("ts", 0)
                    )
                )
            }
        } catch (_: Exception) { }
        return out
    }

    private fun persist(ctx: Context, items: List<Entry>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject()
                    .put("runId", it.runId)
                    .put("kind", it.kind)
                    .put("title", it.title)
                    .put("message", it.message)
                    .put("ts", it.ts)
            )
        }
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply()
        } catch (_: Exception) { }
    }
}

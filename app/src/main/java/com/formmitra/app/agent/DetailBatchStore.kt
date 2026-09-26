package com.formmitra.app.agent

import android.content.Context
import com.formmitra.app.engine.DetailBatchLogic
import org.json.JSONArray
import org.json.JSONObject

/**
 * DetailBatchStore — SMART DETAIL COLLECTION (point 14) ka persistence.
 *
 * Server "details_needed" ka compact batch bhejta hai (ya loop kind="input"
 * par batch banata hai). Ye store:
 *  - batch ko SharedPreferences me rakhta hai (app close/crash par bache),
 *  - user ke jawab bhi rakhta hai (history/audit ke liye),
 *  - AgentChatView ko listener se batata hai taaki EKI compact card dikhe.
 *
 * Koi secret value (OTP/password) yahan KABHI save nahi hoti — sirf
 * non-sensitive details (naam, pata, phone...). OTP/password wale gates
 * UserPrompt wale existing blocking flow me hi rehte hain.
 */
object DetailBatchStore {
    private const val PREFS = "fm_detail_batches"
    private const val KEY = "batches"
    private const val MAX = 10
    private const val STALE_MS = 24L * 60 * 60 * 1000

    data class Batch(
        val runId: String,
        val taskName: String,
        val fields: List<DetailBatchLogic.Field>,
        val askedAt: Long,
        /** key → user ka diya jawab (audit ke liye). */
        val answers: Map<String, String> = emptyMap(),
        /** true = user ne jawab de diya (resume ho chuka / hone wala). */
        val answered: Boolean = false
    )

    // ---------- listeners (loop ko block nahi karte) ----------

    private val raisedListeners =
        java.util.concurrent.CopyOnWriteArrayList<(Batch) -> Unit>()
    private val answeredListeners =
        java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    /** Naya batch utha — AgentChatView compact card dikhayega. */
    fun onRaised(l: (Batch) -> Unit) {
        raisedListeners.add(l)
    }

    /** Jawab mil gaya — run resume trigger hoga. */
    fun onAnswered(l: (String) -> Unit) {
        answeredListeners.add(l)
    }

    private fun fireRaised(b: Batch) {
        for (l in raisedListeners) {
            try { l(b) } catch (_: Exception) { }
        }
    }

    private fun fireAnswered(runId: String) {
        if (runId.isEmpty()) return
        for (l in answeredListeners) {
            try { l(runId) } catch (_: Exception) { }
        }
    }

    // ---------- CRUD ----------

    /** Naya batch uthao (same runId dedupe) + listeners ko batao. */
    fun raise(
        ctx: Context,
        runId: String,
        taskName: String,
        fields: List<DetailBatchLogic.Field>
    ): Batch {
        val batch = Batch(
            runId = runId,
            taskName = taskName.take(80),
            fields = fields,
            askedAt = System.currentTimeMillis()
        )
        try {
            val items = loadRaw(ctx).toMutableList()
            items.removeAll { it.runId == runId }
            items.add(0, batch)
            persist(ctx, items.take(MAX))
        } catch (_: Exception) { }
        try { fireRaised(batch) } catch (_: Exception) { }
        return batch
    }

    /**
     * User ne jawab diya — answers save karo (audit), batch ko answered
     * mark karo, listeners ko batao (resume trigger).
     * @return true = batch mila aur save hua.
     */
    fun saveAnswers(
        ctx: Context, runId: String, answers: Map<String, String>
    ): Boolean {
        var found = false
        try {
            val items = loadRaw(ctx).toMutableList()
            val idx = items.indexOfFirst { it.runId == runId }
            if (idx >= 0) {
                val old = items[idx]
                // Khali jawab mat rakho — sirf bhare hue
                val clean = answers.filter { it.key.isNotEmpty() && it.value.trim().isNotEmpty() }
                items[idx] = old.copy(
                    answers = old.answers + clean,
                    answered = true
                )
                persist(ctx, items)
                found = true
            }
        } catch (_: Exception) { }
        if (found) {
            try { fireAnswered(runId) } catch (_: Exception) { }
        }
        return found
    }

    /** Batch hatao (resume ho gaya / cancel). */
    fun clear(ctx: Context, runId: String) {
        try {
            val items = loadRaw(ctx).filter { it.runId != runId }
            persist(ctx, items)
        } catch (_: Exception) { }
    }

    /** Ek batch lao (stale nahi). */
    fun get(ctx: Context, runId: String): Batch? =
        all(ctx).firstOrNull { it.runId == runId }

    /** Sab pending (unanswered) batches, naye pehle. */
    fun pending(ctx: Context): List<Batch> =
        all(ctx).filter { !it.answered }

    fun hasPending(ctx: Context): Boolean = pending(ctx).isNotEmpty()

    // ---------- internal ----------

    private fun all(ctx: Context): List<Batch> = try {
        val fresh = loadRaw(ctx).filter {
            System.currentTimeMillis() - it.askedAt < STALE_MS
        }
        if (fresh.size != loadRaw(ctx).size) persist(ctx, fresh)
        fresh
    } catch (_: Exception) {
        emptyList()
    }

    private fun loadRaw(ctx: Context): List<Batch> {
        val raw = try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]"
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<Batch>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val runId = o.optString("runId", "")
                if (runId.isEmpty()) continue
                val fields = ArrayList<DetailBatchLogic.Field>()
                val farr = o.optJSONArray("fields")
                if (farr != null) {
                    for (j in 0 until farr.length()) {
                        val fo = farr.optJSONObject(j) ?: continue
                        val key = fo.optString("key", "")
                        if (key.isEmpty()) continue
                        val opts = ArrayList<String>()
                        val oarr = fo.optJSONArray("options")
                        if (oarr != null) {
                            for (k in 0 until oarr.length()) {
                                opts.add(oarr.optString(k, ""))
                            }
                        }
                        fields.add(
                            DetailBatchLogic.Field(
                                key = key,
                                label = fo.optString("label", key),
                                why = fo.optString("why", ""),
                                type = fo.optString("type", "text"),
                                options = opts.filter { it.isNotEmpty() }
                            )
                        )
                    }
                }
                val answers = LinkedHashMap<String, String>()
                val aobj = o.optJSONObject("answers")
                if (aobj != null) {
                    val keys = aobj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        answers[k] = aobj.optString(k, "")
                    }
                }
                out.add(
                    Batch(
                        runId = runId,
                        taskName = o.optString("taskName", ""),
                        fields = fields,
                        askedAt = o.optLong("askedAt", 0),
                        answers = answers,
                        answered = o.optBoolean("answered", false)
                    )
                )
            }
        } catch (_: Exception) { }
        return out
    }

    private fun persist(ctx: Context, items: List<Batch>) {
        val arr = JSONArray()
        for (b in items) {
            val farr = JSONArray()
            for (f in b.fields) {
                farr.put(
                    JSONObject()
                        .put("key", f.key)
                        .put("label", f.label)
                        .put("why", f.why)
                        .put("type", f.type)
                        .put("options", JSONArray(f.options))
                )
            }
            val aobj = JSONObject()
            for ((k, v) in b.answers) aobj.put(k, v)
            arr.put(
                JSONObject()
                    .put("runId", b.runId)
                    .put("taskName", b.taskName)
                    .put("fields", farr)
                    .put("askedAt", b.askedAt)
                    .put("answers", aobj)
                    .put("answered", b.answered)
            )
        }
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply()
        } catch (_: Exception) { }
    }
}

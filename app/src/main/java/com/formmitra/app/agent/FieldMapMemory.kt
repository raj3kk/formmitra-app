package com.formmitra.app.agent

import android.content.Context
import org.json.JSONObject

/**
 * FieldMapMemory — v24-refine: "sahi field mapping" yaad rakho.
 *
 * AI (brain) ne ek baar sahi bataya — is site ke is field (selector) me
 * is source ka value jata hai — AUR fill read-back se VERIFY hua →
 * pattern save. Agli baar wahi situation: pattern match → consistency
 * check local (AI call nahi); drift dikhe → brain ko mapping-note
 * (history) → AI khud correct karega.
 *
 * NOTE: parallel memory system NAHI — ChoiceMemory jaisa hi ghar
 * (SharedPreferences JSON), sirf is pattern-type ke liye. Task me
 * "sahi field mapping" naam se manga gaya pattern hai.
 *
 * Privacy: sirf source KEY (jaise "phone") + selector save hota hai —
 * VALUE kabhi nahi (value user ka data hai, pattern me nahi).
 */
object FieldMapMemory {
    private const val PREFS = "formmitra_fieldmap"
    private const val KEY = "fieldmap_v1"
    private const val MAX_ENTRIES = 200

    private fun readAll(ctx: Context): JSONObject = try {
        JSONObject(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: "{}"
        )
    } catch (_: Exception) { JSONObject() }

    private fun writeAll(ctx: Context, obj: JSONObject) {
        try {
            // bloat guard
            var o = obj
            if (o.length() > MAX_ENTRIES) {
                val keys = o.keys()
                val list = ArrayList<String>()
                while (keys.hasNext()) list.add(keys.next())
                o = JSONObject()
                for (k in list.takeLast(MAX_ENTRIES)) {
                    o.put(k, obj.optJSONObject(k))
                }
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, o.toString()).apply()
        } catch (_: Exception) { }
    }

    /** Pattern key — LearnLogic.fieldMapKey (pure, testable). */
    fun key(domain: String, selectorMode: String, selectorValue: String): String =
        LearnLogic.fieldMapKey(domain, selectorMode, selectorValue)

    /** @return seekha hua source key ya null. */
    fun get(ctx: Context, key: String): String? {
        if (key.isEmpty()) return null
        return try {
            readAll(ctx).optJSONObject(key)?.optString("src")?.ifEmpty { null }
        } catch (_: Exception) { null }
    }

    /** Seekho: (domain|selector) → source key. Sirf VERIFIED fill par
     *  bulao — latest verified mapping jeet-ti hai. */
    fun save(ctx: Context, key: String, src: String) {
        if (key.isEmpty() || src.isEmpty()) return
        try {
            val all = readAll(ctx)
            val e = all.optJSONObject(key) ?: JSONObject()
            e.put("src", src.take(60))
            e.put("hits", e.optInt("hits", 0) + 1)
            e.put("at", System.currentTimeMillis())
            all.put(key, e)
            writeAll(ctx, all)
        } catch (_: Exception) { }
    }

    fun clear(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply()
        } catch (_: Exception) { }
    }
}

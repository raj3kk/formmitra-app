package com.formmitra.app.agent

import android.content.Context
import org.json.JSONObject

/**
 * PrecheckCache — v24-refine quota discipline.
 *
 * /api/agent/precheck heuristic hai (AI nahi), PAR 20/day budget me
 * ginata hai. Same (url, goal) 24h me dobara aaye → cache se jawab,
 * ZERO budget kharch. Order pakka: cache → local → (tabhi) server.
 *
 * Verdict JSON as-is save hota hai (PrecheckLogic.parse wahi padhta hai).
 */
object PrecheckCache {
    private const val PREFS = "formmitra_precheck_cache"
    private const val KEY = "precheck_v1"
    private const val MAX_ENTRIES = 50

    private fun readAll(ctx: Context): JSONObject = try {
        JSONObject(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: "{}"
        )
    } catch (_: Exception) { JSONObject() }

    /** Cache hit → verdict JSONObject ya null (miss/stale). */
    fun get(ctx: Context, url: String, goal: String): JSONObject? {
        return try {
            val key = LearnLogic.precheckCacheKey(url, goal)
            val e = readAll(ctx).optJSONObject(key) ?: return null
            val at = e.optLong("at", 0)
            if (!LearnLogic.isCacheFresh(at, System.currentTimeMillis())) {
                // stale → saaf karo
                val all = readAll(ctx)
                all.remove(key)
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, all.toString()).apply()
                return null
            }
            e.optJSONObject("verdict")
        } catch (_: Exception) { null }
    }

    fun put(ctx: Context, url: String, goal: String, verdict: JSONObject) {
        try {
            val key = LearnLogic.precheckCacheKey(url, goal)
            val all = readAll(ctx)
            all.put(
                key,
                JSONObject().put("at", System.currentTimeMillis())
                    .put("verdict", verdict)
            )
            if (all.length() > MAX_ENTRIES) {
                val keys = all.keys()
                val list = ArrayList<String>()
                while (keys.hasNext()) list.add(keys.next())
                val pruned = JSONObject()
                for (k in list.takeLast(MAX_ENTRIES)) pruned.put(k, all.optJSONObject(k))
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, pruned.toString()).apply()
            } else {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, all.toString()).apply()
            }
        } catch (_: Exception) { }
    }

    fun clear(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply()
        } catch (_: Exception) { }
    }
}

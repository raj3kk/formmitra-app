package com.formmitra.app.agent

import android.content.Context
import org.json.JSONObject
import java.time.Instant

/**
 * CardStore (v24) — FormMitra Card ka app-side state.
 *
 *  - Card tokens (unlock ke baad, ~30 min valid): SIRF memory me, disk par
 *    kabhi nahi (PIN/token persist nahi hota — security).
 *  - Selected card id: prefs me (kaam shuru karne se pehle card mandatory).
 *  - PendingDetails (#4): user ne details di par koi active card nahi —
 *    details prefs me JSON ke roop me SAFE rehti hain (app kill hone par
 *    bhi), card bante hi tag ke saath usi card me flush hoti hain.
 *    Koi bhi di hui detail beech me ghumti NAHI.
 */
object CardStore {

    private const val PREFS = "formmitra_cards"
    private const val KEY_SELECTED = "selected_card_id"
    private const val KEY_PENDING = "pending_details_json"

    private data class Token(val value: String, val expiresAtMs: Long)
    private val tokens = HashMap<String, Token>()
    private val lock = Any()

    // ---------- card tokens (memory-only) ----------

    /** Unlock response ka expires_at (ISO) parse karke token rakho. */
    fun putToken(cardId: String, token: String, expiresAtIso: String?) {
        val exp = try {
            if (!expiresAtIso.isNullOrEmpty()) Instant.parse(expiresAtIso).toEpochMilli()
            else System.currentTimeMillis() + 30 * 60 * 1000L
        } catch (_: Exception) {
            System.currentTimeMillis() + 30 * 60 * 1000L
        }
        synchronized(lock) { tokens[cardId] = Token(token, exp) }
    }

    /** Valid token ya null (expired/missing → dobara PIN maango). */
    fun token(cardId: String): String? = synchronized(lock) {
        val t = tokens[cardId] ?: return null
        // 60s ka safety margin — expire hone se pehle hi invalid mano.
        if (System.currentTimeMillis() + 60_000L >= t.expiresAtMs) {
            tokens.remove(cardId)
            return null
        }
        t.value
    }

    fun clearToken(cardId: String) {
        synchronized(lock) { tokens.remove(cardId) }
    }

    // ---------- selected card ----------

    fun selectedCardId(ctx: Context): String? = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, null)?.ifEmpty { null }
    } catch (_: Exception) { null }

    fun setSelectedCardId(ctx: Context, id: String?) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SELECTED, id ?: "").apply()
        } catch (_: Exception) { }
    }

    // ---------- PendingDetails (#4: details kabhi khoye nahi) ----------

    /**
     * field → (value, tag). JSON: {field: {"v": value, "t": tag}}.
     * prefs me persist — app band/kill hone par bhi surakshit.
     */
    fun pendingAdd(ctx: Context, field: String, value: String, tag: String) {
        if (field.isEmpty() || value.isEmpty()) return
        try {
            val cur = pendingGetRaw(ctx)
            cur.put(field, JSONObject().put("v", value).put("t", tag))
            saveRaw(ctx, cur)
        } catch (_: Exception) { }
    }

    fun pendingAddAll(ctx: Context, fields: Map<String, String>, tag: String) {
        if (fields.isEmpty()) return
        try {
            val cur = pendingGetRaw(ctx)
            for ((k, v) in fields) {
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    cur.put(k, JSONObject().put("v", v).put("t", tag))
                }
            }
            saveRaw(ctx, cur)
        } catch (_: Exception) { }
    }

    /** field → Pair(value, tag). */
    fun pendingAll(ctx: Context): LinkedHashMap<String, Pair<String, String>> {
        val out = LinkedHashMap<String, Pair<String, String>>()
        try {
            val cur = pendingGetRaw(ctx)
            val keys = cur.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = cur.optJSONObject(k) ?: continue
                val v = o.optString("v", "")
                if (v.isNotEmpty()) out[k] = v to o.optString("t", "")
            }
        } catch (_: Exception) { }
        return out
    }

    fun pendingCount(ctx: Context): Int = pendingAll(ctx).size

    fun pendingClear(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_PENDING).apply()
        } catch (_: Exception) { }
    }

    private fun pendingGetRaw(ctx: Context): JSONObject = try {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING, null)
        if (s.isNullOrEmpty()) JSONObject() else JSONObject(s)
    } catch (_: Exception) { JSONObject() }

    private fun saveRaw(ctx: Context, o: JSONObject) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING, o.toString()).apply()
    }
}

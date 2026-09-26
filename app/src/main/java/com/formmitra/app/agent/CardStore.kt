package com.formmitra.app.agent

import android.content.Context
import com.formmitra.app.engine.CardUnlockPolicy
import com.formmitra.app.engine.CryptoVault
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
    /**
     * v29 P4: pending me jaane wali har key yahin canonicalize hoti hai —
     * legacy "address" → "address_line" vagera. Callers par bharosa nahi.
     */
    fun pendingAdd(ctx: Context, field: String, value: String, tag: String) {
        val canon = TagRegistry.normalizeTag(field)
        if (canon.isEmpty() || value.isEmpty()) return
        try {
            val cur = pendingGetRaw(ctx)
            cur.put(canon, JSONObject().put("v", value).put("t", tag))
            saveRaw(ctx, cur)
        } catch (_: Exception) { }
    }

    fun pendingAddAll(ctx: Context, fields: Map<String, String>, tag: String) {
        if (fields.isEmpty()) return
        try {
            val cur = pendingGetRaw(ctx)
            for ((k, v) in fields) {
                val canon = TagRegistry.normalizeTag(k)
                if (canon.isNotEmpty() && v.isNotEmpty()) {
                    cur.put(canon, JSONObject().put("v", v).put("t", tag))
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

    // ============ POINT 24 (REVISED): PERSISTENT CARD UNLOCK ============
    //
    // User ka faisla: unlock persistent hai — chat band / app background /
    // background automation par dobara PIN NAHI. Unlock SIRF tootega jab
    // (a) user khud "Lock karo" kare, ya (b) sign-out ho. KOI auto re-lock
    // nahi (idle-timeout rule hata diya gaya).
    //
    // Note: server ka signed card_token (30-min TTL) existing mechanism hi
    // hai — naya crypto nahi. Token ko CryptoVault (existing) me encrypted
    // persist karte hain taaki app restart par bhi 30-min window me PIN na
    // lage. Token expire ho jaye to unlock FLAG phir bhi ON rehta hai;
    // agla card access PIN se naya token banayega (server boundary).

    private const val KEY_UNLOCKED_SET = "unlocked_card_ids"
    private const val KEY_PINFAIL_PREFIX = "pin_fail_"
    private const val KEY_NOTE_PREFIX = "unlock_note_shown_"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun unlockedIds(ctx: Context): MutableSet<String> = try {
        HashSet(prefs(ctx).getStringSet(KEY_UNLOCKED_SET, emptySet()) ?: emptySet())
    } catch (_: Exception) { HashSet() }

    private fun saveUnlockedIds(ctx: Context, ids: Set<String>) {
        try {
            prefs(ctx).edit().putStringSet(KEY_UNLOCKED_SET, HashSet(ids)).apply()
        } catch (_: Exception) { }
    }

    /** PIN se unlock safal → persistent unlock ON (note flag reset). */
    fun setUnlocked(ctx: Context, cardId: String) {
        if (cardId.isEmpty()) return
        val ids = unlockedIds(ctx)
        ids.add(cardId)
        saveUnlockedIds(ctx, ids)
        // Naya unlock → note ek baar phir dikhega.
        try { prefs(ctx).edit().remove(KEY_NOTE_PREFIX + cardId).apply() } catch (_: Exception) { }
    }

    /** Persistent unlock ON hai? (manual lock / sign-out ne toda nahi). */
    fun isUnlocked(ctx: Context, cardId: String): Boolean =
        cardId.isNotEmpty() && unlockedIds(ctx).contains(cardId)

    /**
     * Manual "Lock karo" — unlock FLAG + token (memory + encrypted) saaf.
     * Dobara kholne par PIN lagega.
     */
    fun lock(ctx: Context, cardId: String) {
        if (cardId.isEmpty()) return
        val ids = unlockedIds(ctx)
        ids.remove(cardId)
        saveUnlockedIds(ctx, ids)
        clearToken(cardId)
        try {
            CryptoVault.clearSecure(ctx, "card_token_$cardId")
            CryptoVault.clearSecure(ctx, "card_token_exp_$cardId")
            prefs(ctx).edit().remove(KEY_NOTE_PREFIX + cardId).apply()
        } catch (_: Exception) { }
    }

    /** Sign-out → SAARE cards lock (koi unlock persist nahi). */
    fun lockAll(ctx: Context) {
        val ids = unlockedIds(ctx)
        for (id in ids) {
            clearToken(id)
            try {
                CryptoVault.clearSecure(ctx, "card_token_$id")
                CryptoVault.clearSecure(ctx, "card_token_exp_$id")
                prefs(ctx).edit().remove(KEY_NOTE_PREFIX + id).apply()
            } catch (_: Exception) { }
        }
        saveUnlockedIds(ctx, emptySet())
    }

    private fun parseExpMs(expiresAtIso: String?): Long = try {
        if (expiresAtIso.isNullOrEmpty()) System.currentTimeMillis() + 30 * 60 * 1000L
        else Instant.parse(expiresAtIso).toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis() + 30 * 60 * 1000L
    }

    /**
     * putToken + encrypted persist (app restart par bhi token bache).
     * CardFlow.unlock safal hone par yahi call karo.
     */
    fun putToken(ctx: Context, cardId: String, token: String, expiresAtIso: String?) {
        putToken(cardId, token, expiresAtIso)
        try {
            val expMs = parseExpMs(expiresAtIso)
            CryptoVault.putSecureSync(ctx, "card_token_$cardId", token)
            CryptoVault.putSecureSync(ctx, "card_token_exp_$cardId", expMs.toString())
        } catch (_: Exception) { }
    }

    /**
     * Valid token: pehle memory, phir encrypted persist (app restart ke
     * baad). Expired/missing → null (PIN se naya token chahiye).
     */
    fun tokenOrRestore(ctx: Context, cardId: String): String? {
        token(cardId)?.let { return it }
        return try {
            val tok = CryptoVault.getSecure(ctx, "card_token_$cardId")
            val expMs = CryptoVault.getSecure(ctx, "card_token_exp_$cardId")?.toLongOrNull() ?: 0L
            if (tok.isNullOrEmpty() ||
                !CardUnlockPolicy.isTokenFresh(expMs, System.currentTimeMillis())
            ) {
                // Expired/corrupt → saaf karo, dobara PIN lagega.
                try {
                    CryptoVault.clearSecure(ctx, "card_token_$cardId")
                    CryptoVault.clearSecure(ctx, "card_token_exp_$cardId")
                } catch (_: Exception) { }
                null
            } else {
                // Memory me wapas rakho (60s skew ke saath).
                val iso = try {
                    Instant.ofEpochMilli(expMs).toString()
                } catch (_: Exception) { null }
                putToken(cardId, tok, iso)
                tok
            }
        } catch (_: Exception) { null }
    }

    // ---------- PIN attempt guard (5 galat / 15 min → temporary lock) ----------

    /** Abhi PIN blocked hai? → blocked-until ms, ya 0. */
    fun pinBlockedUntilMs(ctx: Context, cardId: String): Long = try {
        val raw = prefs(ctx).getString(KEY_PINFAIL_PREFIX + cardId, null)
        CardUnlockPolicy.blockedUntilMs(
            CardUnlockPolicy.parseAttemptState(raw), System.currentTimeMillis()
        )
    } catch (_: Exception) { 0L }

    /** PIN attempt ka result darj karo (sahi → counter reset). */
    fun recordPinAttempt(ctx: Context, cardId: String, ok: Boolean) {
        try {
            val raw = prefs(ctx).getString(KEY_PINFAIL_PREFIX + cardId, null)
            val next = CardUnlockPolicy.recordAttempt(
                CardUnlockPolicy.parseAttemptState(raw),
                System.currentTimeMillis(), ok
            )
            val e = prefs(ctx).edit()
            val k = KEY_PINFAIL_PREFIX + cardId
            val f = CardUnlockPolicy.formatAttemptState(next)
            if (f == null) e.remove(k) else e.putString(k, f)
            e.apply()
        } catch (_: Exception) { }
    }

    // ---------- unlock note (ek baar per unlock) ----------

    fun unlockNoteShown(ctx: Context, cardId: String): Boolean = try {
        prefs(ctx).getBoolean(KEY_NOTE_PREFIX + cardId, false)
    } catch (_: Exception) { false }

    fun markUnlockNoteShown(ctx: Context, cardId: String) {
        try {
            prefs(ctx).edit().putBoolean(KEY_NOTE_PREFIX + cardId, true).apply()
        } catch (_: Exception) { }
    }
}

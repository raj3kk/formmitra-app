package com.formmitra.app.agent

import android.content.Context
import com.formmitra.app.engine.CryptoVault
import org.json.JSONObject

/**
 * DetailStore — saari details EK jagah, device-encrypted (G3).
 *
 * Vault profile (server) + category popup details + chat details — sab
 * yahan merge hokar rehte hain (CryptoVault AES-256-GCM, device-local).
 * Resume/failure/refill par INHI saved details ko use karo — user se
 * baar-baar mat maango.
 *
 * KABHI persist nahi hote: otp, password, passcode, pin, cvv, card_number
 * (BLOCKED) — ye sirf memory me rehte hain, run ke baad bhool jao.
 */
object DetailStore {
    private const val KEY = "persisted_details_v1"

    /** In keys ki values device par save HI nahi hongi. */
    private val BLOCKED = setOf(
        "otp", "password", "passcode", "pin", "cvv",
        "card_number", "cardnumber", "upi_pin"
    )

    /** Nayi details merge karke save karo (blocked keys chhodkar). */
    fun saveAll(ctx: Context, map: Map<String, String>) {
        if (map.isEmpty()) return
        try {
            val cur = loadAll(ctx).toMutableMap()
            var changed = false
            for ((k, v) in map) {
                val kl = k.trim().lowercase()
                if (kl.isEmpty() || kl in BLOCKED) continue
                val vv = v.trim()
                if (vv.isEmpty()) continue
                if (cur[k] != vv) {
                    cur[k] = vv
                    changed = true
                }
            }
            if (changed) persist(ctx, cur)
        } catch (_: Exception) { }
    }

    /** Category popup ki details bhi yahi (keys hi detail keys hain). */
    fun saveCategoryPrefill(ctx: Context, map: Map<String, String>) = saveAll(ctx, map)

    /** Sab saved details (decrypted). */
    fun loadAll(ctx: Context): Map<String, String> {
        return try {
            val raw = CryptoVault.getSecure(ctx.applicationContext, KEY) ?: return emptyMap()
            val obj = JSONObject(raw)
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "").trim()
                if (k.lowercase() !in BLOCKED && v.isNotEmpty()) out[k] = v
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Refill ke liye value dhoondo (G4): exact key → label match
     * (DetailExtractor.label) → contains match. Khali = nahi mili.
     */
    fun findValue(ctx: Context, hint: String): String {
        val h = hint.trim()
        if (h.isEmpty()) return ""
        val all = loadAll(ctx)
        if (all.isEmpty()) return ""
        all[h]?.let { return it }
        val hl = h.lowercase()
        for ((k, v) in all) {
            if (DetailExtractor.label(k).lowercase() == hl) return v
        }
        for ((k, v) in all) {
            val kl = k.lowercase()
            if (kl.contains(hl) || hl.contains(kl)) return v
        }
        return ""
    }

    fun clear(ctx: Context) {
        try {
            CryptoVault.clearSecure(ctx.applicationContext, KEY)
        } catch (_: Exception) { }
    }

    private fun persist(ctx: Context, map: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        CryptoVault.putSecure(ctx.applicationContext, KEY, obj.toString())
    }
}

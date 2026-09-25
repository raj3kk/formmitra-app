package com.formmitra.app.agent

import android.content.Context
import com.formmitra.app.engine.CryptoVault
import org.json.JSONObject

/**
 * SiteCredentialStore — website login credentials ka device-encrypted vault.
 *
 * L1-UPGRADE: login ab "manual" nahi — pehli baar user de (dialog me
 * "save karo" tick ke saath), uske baad AgentLoop isi domain par
 * apne aap login bharega. Credentials SIRF is phone me AES-256-GCM
 * encrypted rehte hain — server/AI ko kabhi nahi jate (sirf page me
 * locally bhare jate hain).
 */
object SiteCredentialStore {
    private const val KEY = "site_creds_v1"

    private fun readAll(ctx: Context): JSONObject {
        val raw = CryptoVault.getSecure(ctx, KEY) ?: return JSONObject()
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }

    /** Synchronous save (commit) — app band/crash ho to bhi pakka save. */
    fun save(ctx: Context, domain: String, username: String, password: String) {
        val d = domain.trim().lowercase()
        if (d.isEmpty() || username.isEmpty() || password.isEmpty()) return
        val all = readAll(ctx)
        all.put(
            d,
            JSONObject().put("u", username).put("p", password)
                .put("saved_at", System.currentTimeMillis())
        )
        CryptoVault.putSecureSync(ctx, KEY, all.toString())
    }

    fun get(ctx: Context, domain: String): Pair<String, String>? {
        val o = readAll(ctx).optJSONObject(domain.trim().lowercase()) ?: return null
        val u = o.optString("u", "")
        val p = o.optString("p", "")
        return if (u.isNotEmpty() && p.isNotEmpty()) u to p else null
    }

    fun has(ctx: Context, domain: String): Boolean = get(ctx, domain) != null

    /** Ek domain hatao, ya domain=null → sab hatao. */
    fun clear(ctx: Context, domain: String? = null) {
        if (domain == null) {
            CryptoVault.clearSecure(ctx, KEY)
            return
        }
        val all = readAll(ctx)
        all.remove(domain.trim().lowercase())
        CryptoVault.putSecureSync(ctx, KEY, all.toString())
    }

    fun domains(ctx: Context): List<String> {
        val all = readAll(ctx)
        val out = ArrayList<String>()
        val keys = all.keys()
        while (keys.hasNext()) out.add(keys.next())
        return out.sorted()
    }

    /** URL → host (www. hata kar). */
    fun domainOf(url: String): String {
        return try {
            var h = java.net.URL(url).host ?: ""
            h = h.trim().lowercase()
            if (h.startsWith("www.")) h = h.removePrefix("www.")
            h
        } catch (_: Exception) { "" }
    }
}

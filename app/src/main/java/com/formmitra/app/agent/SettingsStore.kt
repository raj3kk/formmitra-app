package com.formmitra.app.agent

import android.content.Context
import org.json.JSONObject

/**
 * SettingsStore — POINT 29: saari user settings ek jagah.
 * Local SharedPreferences (turant lagu) + pending-sync queue (server API
 * aate hi reinstall-safe sync ke liye).
 */
object SettingsStore {

    private const val PREFS = "formmitra_settings"
    private const val K_SYNC_PENDING = "sync_pending_json"

    // ---- Keys ----
    const val K_SMS_OTP = "sms_otp_autoread"
    const val K_NOTIF_TRACKING = "notif_tracking"
    const val K_NOTIF_GATES = "notif_gates"
    const val K_NOTIF_DONE = "notif_done"
    const val K_LIVE_QUALITY = "live_quality" // "auto" | "saver" | "full"
    const val K_LIVE_WIFI_ONLY = "live_wifi_only"
    const val K_CAPTCHA_AUTO = "captcha_auto"

    /** Server-sync hone wali keys (pending queue me aur bhi keys ho sakti
     * hain jo sirf local hain — ye sirf /api/settings wali hain). */
    val SYNCED_KEYS = setOf(K_CAPTCHA_AUTO, K_LIVE_QUALITY, K_LIVE_WIFI_ONLY)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getBool(ctx: Context, key: String, def: Boolean): Boolean = try {
        prefs(ctx).getBoolean(key, def)
    } catch (_: Exception) { def }

    fun setBool(ctx: Context, key: String, value: Boolean) {
        try {
            prefs(ctx).edit().putBoolean(key, value).apply()
            markSyncPending(ctx, key, if (value) "1" else "0")
        } catch (_: Exception) { }
    }

    fun getString(ctx: Context, key: String, def: String): String = try {
        prefs(ctx).getString(key, def) ?: def
    } catch (_: Exception) { def }

    fun setString(ctx: Context, key: String, value: String) {
        try {
            prefs(ctx).edit().putString(key, value).apply()
            markSyncPending(ctx, key, value)
        } catch (_: Exception) { }
    }

    /** Server sync ke liye pending changes. */
    fun pendingSync(ctx: Context): Map<String, String> = try {
        val j = JSONObject(prefs(ctx).getString(K_SYNC_PENDING, "{}") ?: "{}")
        val out = LinkedHashMap<String, String>()
        val ks = j.keys()
        while (ks.hasNext()) {
            val k = ks.next()
            out[k] = j.optString(k, "")
        }
        out
    } catch (_: Exception) { emptyMap() }

    fun pendingCount(ctx: Context): Int = pendingSync(ctx).size

    fun clearSyncPending(ctx: Context, keys: Set<String>) {
        try {
            val p = prefs(ctx)
            val j = JSONObject(p.getString(K_SYNC_PENDING, "{}") ?: "{}")
            for (k in keys) j.remove(k)
            p.edit().putString(K_SYNC_PENDING, j.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun markSyncPending(ctx: Context, key: String, value: String) {
        try {
            val p = prefs(ctx)
            val j = JSONObject(p.getString(K_SYNC_PENDING, "{}") ?: "{}")
            j.put(key, value)
            p.edit().putString(K_SYNC_PENDING, j.toString()).apply()
        } catch (_: Exception) { }
    }

    // ============ POINT 29: server sync mapping (pure — selftestable) ============

    /**
     * Ek local key/value → server PATCH body ka tukda.
     * Server shape (/api/settings): { automation:{captcha_auto_solve},
     * live_view:{quality, wifi_only_full} }.
     * Unknown key → khaali body (server ko nahi bhejna).
     */
    fun serverPatchBody(key: String, value: String): JSONObject {
        val body = JSONObject()
        when (key) {
            K_CAPTCHA_AUTO ->
                body.put("automation", JSONObject().put("captcha_auto_solve", value == "1"))
            K_LIVE_QUALITY ->
                body.put("live_view", JSONObject().put("quality", value))
            K_LIVE_WIFI_ONLY ->
                body.put("live_view", JSONObject().put("wifi_only_full", value == "1"))
        }
        return body
    }

    /**
     * Pending queue → ek combined server PATCH body.
     * Sirf SYNCED_KEYS wali entries; baaki local-only keys ignore.
     */
    fun mergePendingToBody(pending: Map<String, String>): JSONObject {
        val automation = JSONObject()
        val liveView = JSONObject()
        for ((k, v) in pending) {
            when (k) {
                K_CAPTCHA_AUTO -> automation.put("captcha_auto_solve", v == "1")
                K_LIVE_QUALITY -> liveView.put("quality", v)
                K_LIVE_WIFI_ONLY -> liveView.put("wifi_only_full", v == "1")
            }
        }
        val body = JSONObject()
        if (automation.length() > 0) body.put("automation", automation)
        if (liveView.length() > 0) body.put("live_view", liveView)
        return body
    }

    /**
     * Server GET {settings} → local key/value pairs.
     * Unknown fields / galat quality value ignore (server sanitize karta
     * hai, phir bhi app-side guard).
     */
    fun localPairsFromServer(settings: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val a = settings.optJSONObject("automation")
        if (a != null && a.has("captcha_auto_solve")) {
            out[K_CAPTCHA_AUTO] = if (a.optBoolean("captcha_auto_solve", true)) "1" else "0"
        }
        val l = settings.optJSONObject("live_view")
        if (l != null) {
            val q = l.optString("quality", "")
            if (q == "auto" || q == "full" || q == "saver") out[K_LIVE_QUALITY] = q
            if (l.has("wifi_only_full")) {
                out[K_LIVE_WIFI_ONLY] = if (l.optBoolean("wifi_only_full", true)) "1" else "0"
            }
        }
        return out
    }

    /**
     * Server se aayi settings ko local prefs me lagao — sync-pending mark
     * NAHI karte (warna server↔app ping-pong chalega).
     */
    fun applyServerSettings(ctx: Context, settings: JSONObject) {
        try {
            val pairs = localPairsFromServer(settings)
            if (pairs.isEmpty()) return
            val e = prefs(ctx).edit()
            for ((k, v) in pairs) {
                if (k == K_LIVE_QUALITY) e.putString(k, v)
                else e.putBoolean(k, v == "1")
            }
            e.apply()
        } catch (_: Exception) { }
    }

    // ============ POINT 29: cache-only clear (pure JVM core — selftestable) ============

    /**
     * root ke ANDAR ki saari files/folders delete karo (root khud nahi).
     * @return freed bytes.
     */
    fun deleteFilesUnder(root: java.io.File): Long {
        var freed = 0L
        fun del(f: java.io.File) {
            try {
                if (f.isDirectory) f.listFiles()?.forEach { del(it) }
                if (f.isFile) freed += f.length()
                if (f != root) f.delete()
            } catch (_: Exception) { }
        }
        try {
            root.listFiles()?.forEach { del(it) }
        } catch (_: Exception) { }
        return freed
    }

    /**
     * Sirf cache saaf karo — cacheDir (+ externalCacheDir).
     * Kaam ka data (filesDir/docs) kabhi nahi chhuta.
     * @return freed bytes.
     */
    fun clearCacheOnly(ctx: Context): Long {
        var freed = 0L
        try {
            ctx.cacheDir?.let { if (it.isDirectory) freed += deleteFilesUnder(it) }
        } catch (_: Exception) { }
        try {
            ctx.externalCacheDir?.let { if (it.isDirectory) freed += deleteFilesUnder(it) }
        } catch (_: Exception) { }
        return freed
    }

    /** Storage used (docs + cache), bytes me. */
    fun storageUsedBytes(ctx: Context): Long = try {
        var total = 0L
        fun walk(f: java.io.File) {
            try {
                if (f.isFile) total += f.length()
                else f.listFiles()?.forEach { walk(it) }
            } catch (_: Exception) { }
        }
        walk(DocsStore.docsDir(ctx))
        ctx.cacheDir?.let { walk(it) }
        total
    } catch (_: Exception) { 0L }

    fun formatBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        else -> "%.1f MB".format(b / (1024.0 * 1024.0))
    }
}

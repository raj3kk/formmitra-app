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

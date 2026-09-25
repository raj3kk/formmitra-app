package com.formmitra.app

import android.content.Context
import android.webkit.CookieManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL

// 6-hour digest: logged-in user ke alerts check karo, naye alerts par notification.
// Koi error / 401 / empty = silently success (user logged in nahi hai — no notification).
class DigestWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    companion object {
        const val CHANNEL_ID = "formmitra_alerts"
        const val PREFS = "formmitra_prefs"
        const val SEEN_KEY = "seen_digest_ids"
    }

    override fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getStringSet(SEEN_KEY, emptySet())?.toMutableSet()
                ?: mutableSetOf()

            val cookie = try {
                CookieManager.getInstance().getCookie(BuildConfig.SITE_URL)
            } catch (_: Exception) {
                null
            }
            val url = URL(BuildConfig.SITE_URL.trimEnd('/') + "/api/app/digest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 20000
                requestMethod = "GET"
                if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
            }
            if (conn.responseCode != 200) return Result.success()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val alerts = org.json.JSONObject(body).optJSONArray("alerts")
                ?: return Result.success()
            if (alerts.length() == 0) return Result.success()

            for (i in 0 until alerts.length()) {
                val a = alerts.optJSONObject(i) ?: continue
                val id = a.optString("id")
                if (id.isEmpty() || seen.contains(id)) continue

                val title = a.optString("title", "FormMitra")
                val text = a.optString("body", "")
                val path = a.optString("url", "/")
                val deep = BuildConfig.SITE_URL.trimEnd('/') + path

                // L5: NotifCenter se — channel + Profile on/off + inbox +
                // badge + deep link, sab ek jagah.
                try {
                    com.formmitra.app.agent.NotifCenter.notify(
                        applicationContext,
                        com.formmitra.app.agent.NotifCenter.Cat.STATUS,
                        title, text,
                        deepTab = "/agent",
                        key = "digest_$id",
                        deepUrl = deep
                    )
                } catch (_: Exception) { }
                seen.add(id)
            }

            val merged = if (seen.size > 50) seen.take(50).toSet() else seen
            prefs.edit().putStringSet(SEEN_KEY, merged).apply()
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }
}

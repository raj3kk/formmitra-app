package com.formmitra.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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

            val nm = applicationContext
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "FormMitra Alerts",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }

            var notifIdx = 0
            for (i in 0 until alerts.length()) {
                val a = alerts.optJSONObject(i) ?: continue
                val id = a.optString("id")
                if (id.isEmpty() || seen.contains(id)) continue

                val title = a.optString("title", "FormMitra")
                val text = a.optString("body", "")
                val path = a.optString("url", "/")
                val deep = BuildConfig.SITE_URL.trimEnd('/') + path

                val tapIntent = Intent(applicationContext, MainActivity::class.java).apply {
                    putExtra("deep_url", deep)
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pi = PendingIntent.getActivity(
                    applicationContext, 2000 + i, tapIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notif: Notification = if (Build.VERSION.SDK_INT >= 26) {
                    Notification.Builder(applicationContext, CHANNEL_ID)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build()
                } else {
                    // minSdk 26 — unreachable, keeps compiler happy on old branches
                    Notification.Builder(applicationContext)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build()
                }
                nm.notify(3000 + notifIdx, notif)
                notifIdx++
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

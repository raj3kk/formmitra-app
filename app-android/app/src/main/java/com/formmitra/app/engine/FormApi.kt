package com.formmitra.app.engine

import android.content.Context
import android.webkit.CookieManager
import com.formmitra.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * FormMitra form-tasks server API — client side.
 *
 *  - Device identity: stable UUID, SharedPreferences "fm_device_id",
 *    header "X-Device-Id".
 *  - Session: CookieManager.getInstance().getCookie(SITE_URL) → "Cookie"
 *    header (DigestWorker wala pattern).
 */
object FormApi {

    private const val PREFS = "formmitra_prefs"
    private const val DEVICE_KEY = "fm_device_id"
    private const val TIMEOUT_MS = 25_000

    fun deviceId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = prefs.getString(DEVICE_KEY, null)
        if (id.isNullOrEmpty()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(DEVICE_KEY, id).apply()
        }
        return id
    }

    private fun sessionCookie(): String? = try {
        CookieManager.getInstance().getCookie(BuildConfig.SITE_URL)
    } catch (_: Exception) {
        null
    }

    private fun open(path: String, method: String, ctx: Context): HttpURLConnection {
        val url = URL(BuildConfig.SITE_URL.trimEnd('/') + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = method
            setRequestProperty("X-Device-Id", deviceId(ctx))
            setRequestProperty("Accept", "application/json")
            sessionCookie()?.let { setRequestProperty("Cookie", it) }
        }
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    /** GET /api/app/form-tasks/next → task JSONObject ya null. */
    fun nextTask(ctx: Context): JSONObject? {
        val conn = open("/api/app/form-tasks/next", "GET", ctx)
        return try {
            if (conn.responseCode != 200) return null
            val body = readBody(conn)
            val task = JSONObject(body).optJSONObject("task") ?: return null
            if (task.optString("id", "").isEmpty()) null else task
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** POST /api/app/form-tasks/runs/{runId}/report */
    fun report(ctx: Context, runId: String, payload: JSONObject): Boolean {
        val conn = open("/api/app/form-tasks/runs/$runId/report", "POST", ctx)
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /** POST /api/app/form-tasks/runs/{runId}/captcha — screenshot handoff. */
    fun captcha(ctx: Context, runId: String, screenshotB64: String, note: String): Boolean {
        val payload = JSONObject()
            .put("screenshot_b64", screenshotB64)
            .put("note", note)
        val conn = open("/api/app/form-tasks/runs/$runId/captcha", "POST", ctx)
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}

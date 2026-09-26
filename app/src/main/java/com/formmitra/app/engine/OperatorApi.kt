package com.formmitra.app.engine

import android.content.Context
import com.formmitra.app.BuildConfig
import com.formmitra.app.agent.AgentApi
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Operator console HTTP client (shared contract — server child implement
 * kar raha hai, EXACT paths):
 *
 *  POST /api/agent/operator/state   {device_id, screenshot_url, page_url,
 *                                    desktop, ts}
 *  GET  /api/agent/operator/state?device_id= → {screenshot_url, page_url,
 *                                    desktop, updated_at}
 *  POST /api/agent/operator/command {device_id, command, params} → server
 *       realtime channel par "operator_command" broadcast karega; app
 *       OperatorCommandReceiver se automation WebView par execute karega.
 *
 * Header pattern FormApi jaisa: X-Device-Id + session Cookie.
 * Sab best-effort: fail ho to false/null, caller loop nahi tootega.
 */
object OperatorApi {

    private const val TIMEOUT_MS = 20_000

    private fun open(path: String, method: String, ctx: Context): HttpURLConnection {
        val url = URL(BuildConfig.SITE_URL.trimEnd('/') + path)
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = method
            setRequestProperty("X-Device-Id", FormApi.deviceId(ctx))
            setRequestProperty("Accept", "application/json")
            AgentApi.sessionCookie()?.let { setRequestProperty("Cookie", it) }
        }
    }

    private fun postJson(path: String, ctx: Context, body: JSONObject): Int {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(path, "POST", ctx)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            conn.responseCode
        } catch (_: Exception) {
            -1
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    /**
     * POST /api/agent/operator/state.
     * @return true = server ne 2xx diya.
     */
    fun postState(
        ctx: Context,
        screenshotUrl: String,
        pageUrl: String,
        desktop: Boolean,
        ts: Long
    ): Boolean {
        val body = JSONObject()
            .put("device_id", FormApi.deviceId(ctx))
            .put("screenshot_url", screenshotUrl)
            .put("page_url", pageUrl)
            .put("desktop", desktop)
            .put("ts", ts)
        return postJson("/api/agent/operator/state", ctx, body) in 200..299
    }

    /**
     * POST /api/agent/operator/command — operator console (in-app
     * OperatorView ya web console) se command bhejo.
     * @return true = server ne 2xx diya (broadcast ke liye accept).
     */
    fun postCommand(ctx: Context, command: String, params: JSONObject): Boolean {
        val body = JSONObject()
            .put("device_id", FormApi.deviceId(ctx))
            .put("command", command)
            .put("params", params)
        return postJson("/api/agent/operator/command", ctx, body) in 200..299
    }

    /** GET /api/agent/operator/state?device_id= → state JSONObject ya null. */
    fun getState(ctx: Context): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val device = java.net.URLEncoder.encode(FormApi.deviceId(ctx), "UTF-8")
            conn = open("/api/agent/operator/state?device_id=$device", "GET", ctx)
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (text.isBlank()) null else JSONObject(text)
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }
}

package com.formmitra.app.agent

import android.content.Context
import android.webkit.CookieManager
import com.formmitra.app.BuildConfig
import com.formmitra.app.engine.FormApi
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * FormMitra v3 Phase 1 — Intake chat API client.
 *
 * Contract (server worker):
 *  - POST /api/agent/chat  req {messages:[{role,content}], profile_summary?}
 *    res {reply, plan|null, missing_docs[]}; 401 not-logged-in; 429 rate-limited.
 *  - POST /api/app/form-tasks  req {name, target_url, steps:[{type:"goto",url}]}
 *    (step shape validateTask se — goto needs "url"; payment veto applies)
 *    res task with id.
 *  - PATCH /api/app/form-tasks/{id}  req {status:"queued"} = "Run now".
 *  - Session auth: WebView CookieManager cookies + X-Device-Id header
 *    (FormApi.open() pattern).
 *
 * Sab network calls background thread pe hon; caller handle kare.
 * code: HTTP code, -1 = network/parse failure (offline).
 */
object AgentApi {

    private const val TIMEOUT_MS = 30_000

    data class ApiResult(val code: Int, val json: JSONObject?)

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
            setRequestProperty("X-Device-Id", FormApi.deviceId(ctx))
            setRequestProperty("Accept", "application/json")
            sessionCookie()?.let { setRequestProperty("Cookie", it) }
        }
        return conn
    }

    private fun post(path: String, ctx: Context, body: JSONObject): ApiResult {
        val conn = open(path, "POST", ctx)
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { if (text.isNotBlank()) JSONObject(text) else null }
            catch (_: Exception) { null }
            ApiResult(code, json)
        } catch (_: Exception) {
            ApiResult(-1, null)
        } finally {
            conn.disconnect()
        }
    }

    /** POST /api/agent/chat — poora history bhejo, reply + plan|null + missing_docs wapas. */
    fun chat(ctx: Context, messages: List<Pair<String, String>>): ApiResult {
        val arr = JSONArray()
        for ((role, content) in messages) {
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        return post("/api/agent/chat", ctx, JSONObject().put("messages", arr))
    }

    /** POST /api/app/form-tasks — sirf goto step; returns (code, taskId). */
    fun createTask(ctx: Context, name: String, url: String): Pair<Int, String?> {
        val steps = JSONArray().put(JSONObject().put("type", "goto").put("url", url))
        val body = JSONObject()
            .put("name", name)
            .put("target_url", url)
            .put("steps", steps)
        val res = post("/api/app/form-tasks", ctx, body)
        val id = res.json?.let {
            val a = it.optString("id", "")
            if (a.isNotEmpty()) a else it.optString("task_id", "").ifEmpty { null }
        }
        return res.code to id
    }

    /** PATCH /api/app/form-tasks/{id} {status:"queued"} — "Run now". */
    fun runNow(ctx: Context, taskId: String): Int {
        val conn = open("/api/app/form-tasks/$taskId", "PATCH", ctx)
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("status", "queued")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (_: Exception) {
            -1
        } finally {
            conn.disconnect()
        }
    }
}

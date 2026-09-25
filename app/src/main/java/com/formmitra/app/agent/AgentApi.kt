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

    /** WebView session cookie — FormApi bhi istemal karta hai. */
    fun sessionCookie(): String? = try {
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

    private fun post(path: String, ctx: Context, body: JSONObject): ApiResult =
        postWithTimeout(path, ctx, body, TIMEOUT_MS)

    /**
     * POST with custom timeout. open() lazy-connect karta hai, isliye
     * timeouts yahan override karna safe hai (connect se pehle).
     */
    private fun postWithTimeout(
        path: String,
        ctx: Context,
        body: JSONObject,
        timeoutMs: Int
    ): ApiResult {
        val conn = open(path, "POST", ctx)
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
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

    /**
     * POST /api/agent/act — brain step.
     * Request: {goal, url, page_title, page_analysis, dom_snapshot,
     *           screenshot_b64, history[], stuck_count, run_id}
     * Response: {step{action, selector{mode,value}, value, option, url, key,
     *                  text, confidence, reason, requires_user, blocked_reason,
     *                  user_prompt, result_summary}, provider, model}
     * 401 = not logged in, 429 = rate limited. Timeout 60s (server AI call).
     * v14: transient failures (network/-1 ya 5xx) par ek bounded retry (2s
     * backoff) — 401/429/4xx par kabhi retry nahi.
     */
    fun act(ctx: Context, body: JSONObject): ApiResult =
        postTransientRetry("/api/agent/act", ctx, body, 60_000)

    /**
     * POST /api/agent/captcha — CAPTCHA protocol (AI sirf analyze karta hai).
     * Request analyze: {mode, screenshot_b64, dom_snippet, url, attempt}
     * Request verify:  {mode, screenshot_b64, dom_snippet, url, attempt, verify_hint}
     * Timeout 60s. Transient-only retry act() jaisa.
     */
    fun captcha(ctx: Context, body: JSONObject): ApiResult =
        postTransientRetry("/api/agent/captcha", ctx, body, 60_000)

    /**
     * Transient-only bounded retry: network fail (-1) ya HTTP 5xx par ek
     * baar 2s backoff ke saath retry. 401/429/4xx par seedha wapas.
     */
    private fun postTransientRetry(
        path: String,
        ctx: Context,
        body: JSONObject,
        timeoutMs: Int
    ): ApiResult {
        val first = postWithTimeout(path, ctx, body, timeoutMs)
        val transient = first.code == -1 || first.code in 500..599
        if (!transient) return first
        try {
            Thread.sleep(2000)
        } catch (_: Exception) { }
        return postWithTimeout(path, ctx, body, timeoutMs)
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
        // AI agent mode: pehla step agent_run — AgentLoop har step khud
        // decide karta hai (Phase 2 brain). Fixed goto nahi.
        val steps = JSONArray().put(
            JSONObject()
                .put("type", "agent_run")
                .put("goal", name)
                .put("url", url)
        )
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

    // ---------------- Phase 2: run reporting + vault + proof ----------------
    //
    // Ye methods RunReporter (engine) aur UI agent dono use karte hain.
    // Server contract:
    //  POST  /api/agent/runs   {goal, url, task_id, device_id} → {run_id}
    //  PATCH /api/agent/runs   {run_id, status, steps_taken, summary, error}
    //  POST  /api/agent/proof  {run_id, screenshot_b64} → {url}
    //  GET   /api/agent/runs   → {runs: [...]}
    //  GET   /api/agent/profile → {profile: {...} | null}

    private fun patch(path: String, ctx: Context, body: JSONObject): ApiResult {
        val conn = open(path, "PATCH", ctx)
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

    /**
     * PUT /api/agent/profile — vault profile save.
     * VERIFY-BEFORE-SAVE contract (server app/api/agent/profile/route.ts):
     * confirmed:false = draft mode (save NAHI hota); confirmed:true/absent = save.
     * Ye call hamesha user ke Proceed (verify dialog) ke BAAD hoti hai, isliye
     * confirmed:true explicit bhejo — intent unambiguous rahe.
     * @return true agar 2xx aaya.
     */
    fun saveProfile(ctx: Context, fields: Map<String, String>): Boolean {
        val body = JSONObject()
        for ((k, v) in fields) body.put(k, v)
        body.put("confirmed", true)
        val res = put("/api/agent/profile", ctx, body)
        return res.code in 200..299
    }

    private fun put(path: String, ctx: Context, body: JSONObject): ApiResult {
        val conn = open(path, "PUT", ctx)
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

    private fun get(path: String, ctx: Context): ApiResult {
        val conn = open(path, "GET", ctx)
        return try {
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

    /**
     * POST /api/agent/runs — naya agent-run banao.
     * @return run_id, ya null (network fail / 401 / 429 / bad response).
     */
    fun createRun(ctx: Context, goal: String, url: String, taskId: String): String? {
        val body = JSONObject()
            .put("goal", goal)
            .put("url", url)
            .put("task_id", taskId)
            .put("device_id", FormApi.deviceId(ctx))
        val res = post("/api/agent/runs", ctx, body)
        if (res.code !in 200..299) return null
        return res.json?.optString("run_id", "")?.ifEmpty { null }
    }

    /**
     * PATCH /api/agent/runs — terminal state report.
     * @return true = server ne accept kiya (2xx).
     */
    fun updateRun(
        ctx: Context,
        runId: String,
        status: String,
        stepsTaken: Int,
        summary: String,
        error: String
    ): Boolean {
        val body = JSONObject()
            .put("run_id", runId)
            .put("status", status)
            .put("steps_taken", stepsTaken)
            .put("summary", summary.take(2000))
            .put("error", error.take(2000))
        val res = patch("/api/agent/runs", ctx, body)
        return res.code in 200..299
    }

    /**
     * POST /api/agent/proof — final screenshot proof upload.
     * @return proof URL, ya null.
     */
    fun uploadProof(ctx: Context, runId: String, b64: String): String? {
        if (b64.isEmpty()) return null
        val body = JSONObject()
            .put("run_id", runId)
            .put("screenshot_b64", b64)
        val res = postWithTimeout("/api/agent/proof", ctx, body, 60_000)
        if (res.code !in 200..299) return null
        return res.json?.optString("url", "")?.ifEmpty { null }
    }

    /**
     * GET /api/agent/runs — apne agent-runs ki list (naye se purane).
     * @return JSONArray ya null.
     */
    fun listRuns(ctx: Context): JSONArray? {
        val res = get("/api/agent/runs?limit=20", ctx)
        if (res.code !in 200..299) return null
        return res.json?.optJSONArray("runs")
    }

    /**
     * GET /api/agent/profile — vault profile (LocalFallback offline fill ke liye).
     * @return profile JSONObject ya null.
     */
    fun profile(ctx: Context): JSONObject? {
        val res = get("/api/agent/profile", ctx)
        if (res.code !in 200..299) return null
        val p = res.json?.optJSONObject("profile")
        return p ?: res.json
    }

    /**
     * POST /api/agent/precheck — "ye kaam ho sakta hai ya nahi?" pehle check.
     * @return poora response JSONObject ya null (network fail).
     */
    fun precheck(ctx: Context, url: String, goal: String): ApiResult {
        val body = JSONObject()
            .put("url", url)
            .put("goal", goal.take(500))
        return post("/api/agent/precheck", ctx, body)
    }

    /**
     * GET /api/agent/site-memory — is user ki seekhi hui site memories.
     * @return JSONArray ya null.
     */
    fun siteMemory(ctx: Context): JSONArray? {
        val res = get("/api/agent/site-memory", ctx)
        if (res.code !in 200..299) return null
        return res.json?.optJSONArray("memories")
    }

    /**
     * PATCH /api/agent/runs — payment status update (device verify ke baad).
     * payment: {status, amount, merchant, upi_id, txn_ref, verified_at}
     */
    fun patchPayment(ctx: Context, runId: String, payment: JSONObject): Boolean {
        if (runId.isEmpty()) return false
        val body = JSONObject()
            .put("run_id", runId)
            .put("payment", payment)
        val res = patch("/api/agent/runs", ctx, body)
        return res.code in 200..299
    }
}

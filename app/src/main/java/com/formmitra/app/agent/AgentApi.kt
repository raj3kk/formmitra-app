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

    private fun open(path: String, method: String, ctx: Context): HttpURLConnection =
        open(path, method, ctx, null)

    /**
     * v24: card-scoped calls ke liye X-Card-Token header (unlock ke baad
     * milta hai, ~30 min valid). Header naam contract me "x-card-token"
     * (HTTP headers case-insensitive hain).
     */
    private fun open(
        path: String,
        method: String,
        ctx: Context,
        cardToken: String?
    ): HttpURLConnection {
        val url = URL(BuildConfig.SITE_URL.trimEnd('/') + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = method
            setRequestProperty("X-Device-Id", FormApi.deviceId(ctx))
            setRequestProperty("Accept", "application/json")
            sessionCookie()?.let { setRequestProperty("Cookie", it) }
            if (!cardToken.isNullOrEmpty()) {
                setRequestProperty("X-Card-Token", cardToken)
            }
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
    ): ApiResult = postWithTimeout(path, ctx, body, timeoutMs, null)

    /** v24: cardToken diya to X-Card-Token header jayega.
     * v28 P12: open() try ke ANDAR — URL/deviceId/cookie setup me koi
     * exception aaye to ApiResult(-1) (koi uncaught nahi). */
    private fun postWithTimeout(
        path: String,
        ctx: Context,
        body: JSONObject,
        timeoutMs: Int,
        cardToken: String?
    ): ApiResult {
        return try {
            val conn = open(path, "POST", ctx, cardToken)
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            try {
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
                try { conn.disconnect() } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            ApiResult(-1, null)
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
        postTransientRetry("/api/agent/act", ctx, body, 60_000, automationCardToken)

    /**
     * POST /api/agent/captcha — CAPTCHA protocol (AI sirf analyze karta hai).
     * Request analyze: {mode, screenshot_b64, dom_snippet, url, attempt}
     * Request verify:  {mode, screenshot_b64, dom_snippet, url, attempt, verify_hint}
     * Timeout 60s. Transient-only retry act() jaisa.
     */
    fun captcha(ctx: Context, body: JSONObject): ApiResult =
        postTransientRetry("/api/agent/captcha", ctx, body, 60_000, automationCardToken)

    /**
     * v24: card-bound automation ke liye token. AgentChatView card select
     * karke kaam shuru kare to set karta hai (unlock ke baad mila token);
     * act()/captcha() calls me X-Card-Token jata hai taaki server brain
     * card ka data fetch karke form bhar sake (C15). Category hatane par
     * AgentChatView ise null karta hai.
     */
    @Volatile var automationCardToken: String? = null

    /**
     * v24: card-bound automation bind/unbind. Card select + unlock ke baad
     * AgentChatView.setActiveCard() se call hota hai; act()/captcha() me
     * X-Card-Token jata hai taaki server brain card ka data fetch karke
     * form bhar sake. null = unbind.
     */
    fun setAutomationCard(cardId: String?, cardToken: String?) {
        automationCardId = cardId
        automationCardToken = cardToken
    }

    @Volatile var automationCardId: String? = null

    /**
     * Transient-only bounded retry: network fail (-1) ya HTTP 5xx par ek
     * baar 2s backoff ke saath retry. 401/429/4xx par seedha wapas.
     */
    private fun postTransientRetry(
        path: String,
        ctx: Context,
        body: JSONObject,
        timeoutMs: Int
    ): ApiResult = postTransientRetry(path, ctx, body, timeoutMs, null)

    /** v24: cardToken diya to X-Card-Token header ke saath retry. */
    private fun postTransientRetry(
        path: String,
        ctx: Context,
        body: JSONObject,
        timeoutMs: Int,
        cardToken: String?
    ): ApiResult {
        val first = postWithTimeout(path, ctx, body, timeoutMs, cardToken)
        val transient = first.code == -1 || first.code in 500..599
        if (!transient) return first
        try {
            Thread.sleep(2000)
        } catch (_: Exception) { }
        return postWithTimeout(path, ctx, body, timeoutMs, cardToken)
    }

    /** POST /api/agent/chat — poora history bhejo, reply + plan|null + missing_docs wapas.
     * v20: category optional — work-wise category context (apply_track,
     * zamin_track, resume_create, job_find, scholarship). Server isi field
     * se category-wise sawaal puchhta hai. null = purana flow (unchanged).
     * v24: cardId/cardToken — card-bound chat (C14): body me card_id jata
     * hai + X-Card-Token header, taaki server card ka data use kare.
     * v28: trackingType — track category me 8 track-types ka context
     * (zameen/job/scholarship/...) server ko jata hai.
     * v29 (P1-APP): knownDetails — is work me user jo details de chuka
     * (card + session merged, canonical keys) body me `known_details`
     * JSONObject ke roop me; server dobara wahi sawaal NAHI poochhega.
     * askedAlready — is work me pehle poochhe gaye keys (asked_for dedupe);
     * server inhe repeat nahi karega. */
    fun chat(
        ctx: Context,
        messages: List<Pair<String, String>>,
        category: String? = null,
        cardId: String? = null,
        cardToken: String? = null,
        trackingType: String? = null,
        knownDetails: Map<String, String> = emptyMap(),
        askedAlready: List<String> = emptyList()
    ): ApiResult {
        val arr = JSONArray()
        for ((role, content) in messages) {
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject().put("messages", arr)
        if (!category.isNullOrEmpty()) body.put("category", category)
        if (!cardId.isNullOrEmpty()) body.put("card_id", cardId)
        if (!trackingType.isNullOrEmpty()) body.put("tracking_type", trackingType)
        // v29 P1: known details — server inhe "pehle se mili hui" maane.
        if (knownDetails.isNotEmpty()) {
            val kd = JSONObject()
            for ((k, v) in knownDetails) {
                if (k.isNotEmpty() && v.isNotEmpty()) kd.put(k, v)
            }
            if (kd.length() > 0) body.put("known_details", kd)
        }
        // v29 P1: pehle poochhe gaye keys — server dobara na poochhe.
        if (askedAlready.isNotEmpty()) {
            body.put("asked_already", JSONArray(askedAlready.filter { it.isNotEmpty() }))
        }
        return postWithTimeout("/api/agent/chat", ctx, body, TIMEOUT_MS, cardToken)
    }

    /** POST /api/app/form-tasks — sirf goto step; returns (code, taskId).
     *  category: agent_run step + top-level me jata hai taaki AgentLoop ke
     *  har /api/agent/act call me category pahunche (category-wise automation).
     *  v29 (P1): knownDetails/askedAlready — agent_run step + top-level me
     *  jate hain taaki FormRunService → AgentLoop → har act() call me
     *  known_details/asked_already pahunche (server dobara sawaal na poochhe). */
    fun createTask(
        ctx: Context,
        name: String,
        url: String,
        category: String = "",
        knownDetails: Map<String, String> = emptyMap(),
        askedAlready: List<String> = emptyList()
    ): Pair<Int, String?> {
        // AI agent mode: pehla step agent_run — AgentLoop har step khud
        // decide karta hai (Phase 2 brain). Fixed goto nahi.
        val firstStep = JSONObject()
            .put("type", "agent_run")
            .put("goal", name)
            .put("url", url)
        if (category.isNotEmpty()) firstStep.put("category", category)
        // v29 P1: known details + asked keys step me (FormRunService inhe
        // padhkar AgentLoop ko dega).
        if (knownDetails.isNotEmpty()) {
            val kd = JSONObject()
            for ((k, v) in knownDetails) {
                if (k.isNotEmpty() && v.isNotEmpty()) kd.put(k, v)
            }
            if (kd.length() > 0) firstStep.put("known_details", kd)
        }
        if (askedAlready.isNotEmpty()) {
            firstStep.put("asked_already", JSONArray(askedAlready.filter { it.isNotEmpty() }))
        }
        val steps = JSONArray().put(firstStep)
        val body = JSONObject()
            .put("name", name)
            .put("target_url", url)
            .put("steps", steps)
        if (category.isNotEmpty()) body.put("category", category)
        // v29 P1: top-level par bhi (server-side visibility ke liye).
        firstStep.optJSONObject("known_details")?.let { body.put("known_details", it) }
        firstStep.optJSONArray("asked_already")?.let { body.put("asked_already", it) }
        val res = post("/api/app/form-tasks", ctx, body)
        // v27 RC1 FIX: server `{ task: { id } }` (nested) bhejta hai —
        // top-level `id`/`task_id` kabhi nahi hota tha, isliye task server
        // par banne ke baad bhi app ko id null milta tha ("ban nahi paya").
        // Pehle nested, phir top-level fallback.
        val id = res.json?.let { j ->
            val nested = j.optJSONObject("task")?.optString("id", "").orEmpty()
            when {
                nested.isNotEmpty() -> nested
                j.optString("id", "").isNotEmpty() -> j.optString("id")
                j.optString("task_id", "").isNotEmpty() -> j.optString("task_id")
                else -> null
            }
        }
        return res.code to id
    }

    /**
     * v28 P7: GET /api/app/form-tasks?category=<key> — category ke purane
     * kaam ("📜 Purane Kaam"). Tap → wahi kaam/context resume hota hai.
     * category khaali ho to saare tasks.
     */
    fun listTasks(ctx: Context, category: String = ""): List<JSONObject> {
        val path = if (category.isNotEmpty())
            "/api/app/form-tasks?category=$category"
        else "/api/app/form-tasks"
        val res = try { get(path, ctx) } catch (_: Exception) { return emptyList() }
        if (res.code !in 200..299) return emptyList()
        val out = mutableListOf<JSONObject>()
        try {
            val arr = res.json?.optJSONArray("tasks") ?: JSONArray()
            for (i in 0 until arr.length()) {
                (arr.optJSONObject(i))?.let { out.add(it) }
            }
        } catch (_: Exception) { }
        return out
    }

    /**
     * v28 P5: POST /api/app/trackings — unified tracking banao.
     * {label, type, details} → (code, trackingId).
     * Server contract: /api/app/trackings expects `type`, `label`,
     * `details` — purane `tracking_type`/`params` aliases bhi bhejte
     * hain (server purana build ho to bhi chale). Root-cause: pehle
     * sirf aliases bhejte the, server ignore kar deta tha.
     * Example: ("Zameen — Khata 569", "zameen",
     *           {khata:"569", khesra:"1563", mauza:"Damgara"}).
     */
    fun createTracking(
        ctx: Context,
        label: String,
        trackingType: String,
        params: Map<String, String> = emptyMap()
    ): Pair<Int, String?> {
        val body = JSONObject()
            .put("label", label)
            .put("type", trackingType)
            .put("tracking_type", trackingType)
        val p = JSONObject()
        for ((k, v) in params) p.put(k, v)
        body.put("details", p)
        body.put("params", p)
        val res = post("/api/app/trackings", ctx, body)
        val id = res.json?.let { j ->
            val nested = j.optJSONObject("tracking")?.optString("id", "").orEmpty()
            when {
                nested.isNotEmpty() -> nested
                j.optString("id", "").isNotEmpty() -> j.optString("id")
                j.optString("tracking_id", "").isNotEmpty() -> j.optString("tracking_id")
                else -> null
            }
        }
        return res.code to id
    }

    /**
     * POINT 25: POST /api/agent/runs {goal, url} — tracking action-offer
     * tap par operator action shuru karo. @return run_id ya null.
     * (Payment/checkout goal server khud reject karta hai.)
     */
    fun startActionRun(ctx: Context, goal: String, url: String): String? {
        val body = JSONObject().put("goal", goal)
        if (url.isNotEmpty()) body.put("url", url)
        val res = try { post("/api/agent/runs", ctx, body) }
        catch (_: Exception) { return null }
        if (res.code !in 200..299) return null
        return res.json?.optString("run_id", null)?.ifEmpty { null }
    }

    /**
     * POINT 27: POST /api/cards/[id]/pin-reset/request — PIN bhool gaye?
     * Account email + devices par OTP. @return (code, errorOrNull).
     */
    fun requestCardPinOtp(ctx: Context, cardId: String): Pair<Int, String?> {
        val res = try { post("/api/cards/$cardId/pin-reset/request", ctx, JSONObject()) }
        catch (_: Exception) { return -1 to "Network dikkat — dobara try karo" }
        if (res.code in 200..299) return res.code to null
        val err = res.json?.optString("error", "").orEmpty()
            .ifEmpty { "OTP nahi bheja ja saka (code ${res.code})" }
        return res.code to err
    }

    /**
     * POINT 27: POST /api/cards/[id]/pin-reset/confirm {otp, new_pin}.
     * (Server confirm endpoint pending ho to 404 → saaf message.)
     * @return (code, errorOrNull).
     */
    fun confirmCardPinReset(
        ctx: Context, cardId: String, otp: String, newPin: String
    ): Pair<Int, String?> {
        val body = JSONObject().put("otp", otp).put("new_pin", newPin)
        val res = try { post("/api/cards/$cardId/pin-reset/confirm", ctx, body) }
        catch (_: Exception) { return -1 to "Network dikkat — dobara try karo" }
        if (res.code in 200..299) return res.code to null
        if (res.code == 404) {
            return 404 to "⚠️ Ye suvidha server par jald aa rahi hai — thodi der me try karo"
        }
        val err = res.json?.optString("error", "").orEmpty()
            .ifEmpty { "PIN set nahi ho paya (code ${res.code})" }
        return res.code to err
    }

    /** v28 P5: GET /api/app/trackings — meri saari trackings (active + cancelled). */
    fun listTrackings(ctx: Context): List<JSONObject> {
        val res = try { get("/api/app/trackings", ctx) }
        catch (_: Exception) { return emptyList() }
        if (res.code !in 200..299) return emptyList()
        val out = mutableListOf<JSONObject>()
        try {
            val arr = res.json?.optJSONArray("trackings") ?: JSONArray()
            for (i in 0 until arr.length()) {
                (arr.optJSONObject(i))?.let { out.add(it) }
            }
        } catch (_: Exception) { }
        return out
    }

    /** v28 P5: PATCH /api/app/trackings/[id] {status:"cancelled"} — tracking band karo. */
    fun cancelTracking(ctx: Context, trackingId: String): Int =
        try {
            patch(
                "/api/app/trackings/$trackingId", ctx,
                JSONObject().put("status", "cancelled")
            ).code
        } catch (_: Exception) { -1 }

    /** PATCH /api/app/form-tasks/{id} {status:"queued"} — "Run now". */
    fun runNow(ctx: Context, taskId: String): Int =
        try {
            patch(
                "/api/app/form-tasks/$taskId", ctx,
                JSONObject().put("status", "queued")
            ).code
        } catch (_: Exception) { -1 }

    // ---------------- Phase 2: run reporting + vault + proof ----------------
    //
    // Ye methods RunReporter (engine) aur UI agent dono use karte hain.
    // Server contract:
    //  POST  /api/agent/runs   {goal, url, task_id, device_id} → {run_id}
    //  PATCH /api/agent/runs   {run_id, status, steps_taken, summary, error}
    //  POST  /api/agent/proof  {run_id, screenshot_b64} → {url}
    //  GET   /api/agent/runs   → {runs: [...]}
    //  GET   /api/agent/profile → {profile: {...} | null}

    /** v28 P12: open() try ke ANDAR — koi uncaught nahi. */
    private fun patch(path: String, ctx: Context, body: JSONObject): ApiResult {
        return try {
            val conn = open(path, "PATCH", ctx)
            try {
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
                try { conn.disconnect() } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            ApiResult(-1, null)
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

    /**
     * PUT /api/agent/profile — A-to-Z vault profile form (v20).
     * CONTRACT: `confirmed` field BILKUL MAT bhejo — absent par server save
     * karta hai. Sab fields optional: sirf non-empty fields bhejo
     * (partial save — khaali fields server ki values ko overwrite nahi karte).
     * @return true agar 2xx aaya.
     */
    fun saveProfileForm(ctx: Context, fields: Map<String, String>): Boolean {
        val body = JSONObject()
        for ((k, v) in fields) {
            val t = v.trim()
            if (t.isNotEmpty()) body.put(k, t)
        }
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

    /** v28 P12: open() try ke ANDAR — koi uncaught nahi. */
    private fun get(path: String, ctx: Context): ApiResult {
        return try {
            val conn = open(path, "GET", ctx)
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val json = try { if (text.isNotBlank()) JSONObject(text) else null }
                catch (_: Exception) { null }
                ApiResult(code, json)
            } catch (_: Exception) {
                ApiResult(-1, null)
            } finally {
                try { conn.disconnect() } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            ApiResult(-1, null)
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

    // ---------------- v24: FormMitra Cards (frozen contract) ----------------
    //
    //  GET    /api/cards → {cards:[{id, formmitra_id, name, created_at,
    //                             details_keys, docs_count}]}
    //  POST   /api/cards {name, pin(4-8 digits), via:"manual"|"agent",
    //                     details?} → 201 {card:{id, formmitra_id, name,
    //                     created_at}} ; 4 cards par 400 {error:"card_limit"}
    //  POST   /api/cards/[id]/unlock {pin} → 200 {ok:true, card_token,
    //                     expires_at} / 403
    //  GET    /api/cards/[id] (x-card-token) → full card + details
    //                     {field:{value,tag,updated_at}}
    //  PATCH  /api/cards/[id] (x-card-token) {details:{field:value|{value,tag}}}
    //  DELETE /api/cards/[id] {confirm:true}
    //  GET    /api/cards/[id]/documents (x-card-token)
    //  POST   /api/cards/[id]/documents (x-card-token) — multipart file + tag
    //  DELETE /api/cards/[id]/documents/[docId] (x-card-token) {confirm:true}
    // Session-auth: bina login 401.

    /** GET /api/cards — apne cards ki list (FormMitra ID ke saath). */
    fun cards(ctx: Context): ApiResult = get("/api/cards", ctx)

    /**
     * POINT 29: GET /api/settings → {settings}.
     * Server-persisted prefs: notifications / live_view / automation
     * (captcha_auto_solve). Session-auth (401 bina login).
     */
    fun userSettings(ctx: Context): ApiResult = get("/api/settings", ctx)

    /**
     * POINT 29: PATCH /api/settings (partial body) → {settings}.
     * Sirf SettingsStore.SYNCED_KEYS wali values bhejo.
     */
    fun patchSettings(ctx: Context, body: JSONObject): ApiResult =
        patch("/api/settings", ctx, body)

    /**
     * POST /api/cards — naya card.
     * @return ApiResult (201 → json.card; 400 error:"card_limit" → 4 ho gaye)
     */
    fun createCard(
        ctx: Context,
        name: String,
        pin: String,
        via: String,
        details: Map<String, String> = emptyMap()
    ): ApiResult {
        val body = JSONObject()
            .put("name", name)
            .put("pin", pin)
            .put("via", via)
        if (details.isNotEmpty()) {
            val d = JSONObject()
            for ((k, v) in details) d.put(k, v)
            body.put("details", d)
        }
        return post("/api/cards", ctx, body)
    }

    /** POST /api/cards/[id]/unlock {pin} → 200 {ok, card_token, expires_at}. */
    fun unlockCard(ctx: Context, cardId: String, pin: String): ApiResult =
        post("/api/cards/$cardId/unlock", ctx, JSONObject().put("pin", pin))

    /**
     * CONTRACT SYNC (2026-09-26): server card lock endpoints.
     * POST /api/cards/[id]/lock → server-side lock (token invalidate).
     * POST /api/cards/lock-all → saare cards lock.
     * Local CardStore.lockAll ke SAATH server ko bhi batao taaki token
     * server par bhi mar jaye (sirf local lock aadha kaam hai).
     */
    fun lockCard(ctx: Context, cardId: String): ApiResult =
        post(
            com.formmitra.app.engine.CardEndpoints.lock(cardId),
            ctx, JSONObject()
        )

    fun lockAllCards(ctx: Context): ApiResult =
        post(com.formmitra.app.engine.CardEndpoints.LOCK_ALL, ctx, JSONObject())

    /** GET /api/cards/[id] — full card + details (x-card-token). */
    fun cardDetail(ctx: Context, cardId: String, token: String): ApiResult =
        getWithToken("/api/cards/$cardId", ctx, token)

    /**
     * PATCH /api/cards/[id] — details merge (x-card-token).
     * details: JSONObject {field: value | {value, tag}}.
     */
    fun patchCard(ctx: Context, cardId: String, token: String, details: JSONObject): ApiResult =
        patchWithToken(
            "/api/cards/$cardId", ctx,
            JSONObject().put("details", details), token
        )

    /** DELETE /api/cards/[id] {confirm:true}. */
    fun deleteCard(ctx: Context, cardId: String): ApiResult =
        delete("/api/cards/$cardId", ctx, JSONObject().put("confirm", true))

    /** GET /api/cards/[id]/documents (x-card-token). */
    fun cardDocs(ctx: Context, cardId: String, token: String): ApiResult =
        getWithToken("/api/cards/$cardId/documents", ctx, token)

    /**
     * POST /api/cards/[id]/documents — file upload (x-card-token).
     * multipart/form-data: file (binary) + tag (text). Server isi ko
     * accept kare (frozen contract me body format nahi tha — ye standard
     * file-upload format hai; conformance check me verify hoga).
     */
    fun uploadCardDoc(
        ctx: Context,
        cardId: String,
        token: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        tag: String
    ): ApiResult {
        val boundary = "fm${System.currentTimeMillis()}"
        val conn = open("/api/cards/$cardId/documents", "POST", ctx, token)
        return try {
            conn.doOutput = true
            conn.connectTimeout = 60_000
            conn.readTimeout = 60_000
            conn.setRequestProperty(
                "Content-Type", "multipart/form-data; boundary=$boundary"
            )
            conn.outputStream.use { out ->
                fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                w("--$boundary\r\n")
                w("Content-Disposition: form-data; name=\"tag\"\r\n\r\n")
                w("$tag\r\n")
                w("--$boundary\r\n")
                w("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
                w("Content-Type: ${mime.ifEmpty { "application/octet-stream" }}\r\n\r\n")
                out.write(bytes)
                w("\r\n--$boundary--\r\n")
                out.flush()
            }
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

    /** DELETE /api/cards/[id]/documents/[docId] {confirm:true} (x-card-token). */
    fun deleteCardDoc(
        ctx: Context,
        cardId: String,
        docId: String,
        token: String
    ): ApiResult = deleteWithToken(
        "/api/cards/$cardId/documents/$docId", ctx,
        JSONObject().put("confirm", true), token
    )

    /**
     * POST /api/agent/verify — final submit se pehle AI image-verification
     * (C15). Req {run_id, screenshot_b64, note} → {ok, verdict?, reason?}.
     * 401 bina login. Server deploy na hua ho to 404 — caller fail-soft
     * rakhe (verify na ho to bhi automation na ruke, bas log).
     */
    /**
     * POST /api/agent/verify — AI image verification (v24 C15).
     *
     * SERVER CONTRACT (root fix — purana {run_id, screenshot_b64, note}
     * shape server se match nahi karta tha; server 400 deta tha):
     *   Body: {image_base64: string, checklist: string[]}
     *   Response: {ok: boolean, issues: string[]} (issues = Hinglish lines)
     */
    fun verifySubmit(
        ctx: Context,
        screenshotB64: String,
        checklist: List<String>
    ): ApiResult = postWithTimeout(
        "/api/agent/verify", ctx,
        JSONObject()
            .put("image_base64", screenshotB64)
            .put("checklist", JSONArray(checklist.take(20).map { it.take(200) })),
        60_000, automationCardToken
    )

    // ---------- v24 token-aware helpers ----------

    private fun getWithToken(path: String, ctx: Context, token: String): ApiResult {
        val conn = open(path, "GET", ctx, token)
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

    private fun patchWithToken(
        path: String,
        ctx: Context,
        body: JSONObject,
        token: String
    ): ApiResult {
        val conn = open(path, "PATCH", ctx, token)
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

    private fun delete(path: String, ctx: Context, body: JSONObject): ApiResult =
        deleteWithToken(path, ctx, body, null)

    private fun deleteWithToken(
        path: String,
        ctx: Context,
        body: JSONObject,
        token: String?
    ): ApiResult {
        val conn = open(path, "DELETE", ctx, token)
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
}

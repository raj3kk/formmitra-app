package com.formmitra.app.engine

import android.content.Context
import com.formmitra.app.agent.AgentApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * AgentLoop — AI brain loop (Phase 2).
 *
 * Har iteration:
 *   1. DOM snapshot (fields/buttons/page text, max 60 fields) + URL/title
 *   2. CAPTCHA safety net: dikha to /api/agent/captcha se analyze karwa ke
 *      user ko handoff (AI sirf analyze karta hai — auto-type contract me nahi)
 *   3. Screenshot — har 3rd step pe (heavy hai, har step pe nahi)
 *   4. POST /api/agent/act {goal, url, page_title, dom_snapshot,
 *      screenshot_b64, history, stuck_count, run_id} → {step{...}, provider, model}
 *   5. Client-side validate (AgentLoopLogic) → invalid = error count
 *   6. Stuck detection: pichhle 3 signatures same, ya lagatar 2 errors
 *      → stuck_count++ (server 120b model pe escalate karega)
 *   7. FormEngine.runAgentStep se execute (veto + timeout uske andar)
 *   8. done/needs_user/vetoed → finish; stuck_count>=4 → needs_user handoff
 *
 * Payment/checkout = hard veto (runAgentStep ke andar VetoCheck + live page
 * scan — kabhi bypass nahi). Background thread pe chalao (blocking calls).
 */
@Suppress("UNCHECKED_CAST")
object AgentLoop {

    fun runAgentTask(
        ctx: Context,
        engine: FormEngine,
        goal: String,
        startUrl: String,
        runId: String,
        maxSteps: Int = 40,
        onProgress: (Int) -> Unit = {},
        onOfflineMode: () -> Unit = {},
        onStandaloneMode: () -> Unit = {}
    ): FormEngine.RunResult {
        val stepsLog = JSONArray()
        val history = ArrayList<JSONObject>()
        var stuckCount = 0
        var consecErrors = 0
        var stepsTaken = 0
        val recentSigs = ArrayList<String>()

        // Local task: runId khaali ho to local-<timestamp> (standalone mode —
        // UI agent local task banate waqt khud bhi yehi format bhej sakta hai)
        val effectiveRunId = runId.ifEmpty { "local-${System.currentTimeMillis()}" }

        // Resume state: agent_run shuru hote hi save (kill/reboot ke baad
        // UI agent checkPending se resume karega). Terminal par clear.
        try { AgentResume.save(ctx, goal, startUrl, effectiveRunId) } catch (_: Exception) { }
        // Server-side run record (best-effort — fail ho to bina reporting chalao)
        var agentRunId: String? = null
        try { agentRunId = RunReporter.createRun(ctx, goal, startUrl, effectiveRunId) } catch (_: Exception) { }

        fun logStep(i: Int, action: String, ok: Boolean, detail: String) {
            stepsLog.put(
                JSONObject()
                    .put("index", i)
                    .put("type", action)
                    .put("ok", ok)
                    .put("detail", detail.take(300))
            )
        }

        fun pushHistory(
            action: String,
            stepMap: Map<String, Any?>,
            result: String,
            detail: String
        ) {
            val sel = stepMap["selector"] as? Map<String, Any?>
            val selStr = if (sel != null) "${sel["mode"]}:${sel["value"]}" else ""
            history.add(
                JSONObject()
                    .put("action", action)
                    .put("selector", selStr)
                    .put("result", result)
                    .put("detail", detail.take(300))
            )
        }

        fun finish(status: String, summary: String): FormEngine.RunResult {
            // terminal state: resume clear + server ko report (dono best-effort)
            try { AgentResume.clear(ctx) } catch (_: Exception) { }
            try {
                RunReporter.updateRun(
                    ctx, agentRunId ?: "", status, stepsTaken,
                    summary = if (status == "done") summary else "",
                    error = if (status == "done") "" else summary
                )
            } catch (_: Exception) { }
            return FormEngine.RunResult(status, summary, stepsLog)
        }

        /** Mirror screenshot — UI agent ka live view (best-effort). */
        fun mirrorShot() {
            try { engine.writeMirrorPng(ctx) } catch (_: Exception) { }
        }

        /** Error note karo; true = ab user ko handoff karna chahiye. */
        fun noteError(i: Int, action: String, msg: String): Boolean {
            consecErrors++
            if (consecErrors >= 2) {
                stuckCount++
                consecErrors = 0
            }
            logStep(i, action, false, msg)
            return stuckCount >= AgentActions.STUCK_MAX
        }

        // Pre-run veto: goal + start URL
        VetoCheck.find("$goal $startUrl")?.let {
            return finish("vetoed", "PAYMENT VETO (start): '$it' mila — '$goal'")
        }

        try {
            engine.start()
        } catch (e: Exception) {
            return finish("failed", "browser start nahi hua: ${e.message}")
        }
        try {
            // Pehla navigation: start URL (veto + timeout runAgentStep ke andar)
            if (startUrl.isNotEmpty()) {
                try {
                    val d = engine.runAgentStep(
                        JSONObject().put("type", "goto").put("url", startUrl)
                    )
                    logStep(0, "goto", true, d.toString().take(200))
                    pushHistory("goto", mapOf("action" to "goto", "url" to startUrl), "ok", "opened")
                } catch (e: FormEngine.VetoException) {
                    return finish("vetoed", "PAYMENT VETO: ${e.message}")
                } catch (e: Exception) {
                    return finish("failed", "start URL nahi khula: ${e.message}")
                }
            }

            var currentUrl = startUrl
            val captchaAttempts = intArrayOf(0)
            for (i in 1..maxSteps) {
                onProgress(i)

                // (a) DOM snapshot
                val snap = try {
                    engine.domSnapshot()
                } catch (_: Exception) {
                    JSONObject()
                }
                val url = snap.optString("url", "").ifEmpty { currentUrl }
                val title = snap.optString("title", "")
                currentUrl = url

                // (b) CAPTCHA safety net (+ auto-solve, max 3 attempts)
                val capNote = handleCaptcha(ctx, engine, url, snap, captchaAttempts)
                if (capNote != null) {
                    logStep(i, "captcha", false, capNote.take(200))
                    return finish("needs_user", capNote)
                }

                // (c) screenshot har 3rd step pe (+ mirror file UI agent ke liye)
                val shot = if (i % 3 == 1) {
                    try {
                        val b64 = engine.capturePngBase64()
                        if (b64.isNotEmpty()) mirrorShot()
                        b64
                    } catch (_: Exception) {
                        ""
                    }
                } else ""

                // (d) act call
                val hArr = JSONArray()
                history.takeLast(15).forEach { hArr.put(it) }
                val reqBody = JSONObject()
                    .put("goal", goal)
                    .put("url", url)
                    .put("page_title", title)
                    .put(
                        "dom_snapshot", JSONObject()
                            .put("fields", snap.optJSONArray("fields") ?: JSONArray())
                            .put("buttons", snap.optJSONArray("buttons") ?: JSONArray())
                            .put("page_text", snap.optString("page_text", ""))
                    )
                    .put("screenshot_b64", shot)
                    .put("history", hArr)
                    .put("stuck_count", stuckCount)
                    .put("run_id", runId)
                var res: AgentApi.ApiResult = try {
                    AgentApi.act(ctx, reqBody)
                } catch (_: Exception) {
                    AgentApi.ApiResult(-1, null)
                }
                // Server unreachable (network fail ya HTTP 5xx) + user ki Groq
                // key saved hai → standalone brain (direct Groq, no server).
                // 401 (login) / 429 (limit) par NAHI — wo needs_user handoff.
                if ((res.code == -1 || res.code in 500..599) && Standalone.isConfigured(ctx)) {
                    logStep(i, "act", false, "server down (code=${res.code}) → standalone brain")
                    try { onStandaloneMode() } catch (_: Exception) { }
                    val sbStep: JSONObject? = try {
                        StandaloneBrain.decide(ctx, reqBody)
                    } catch (_: Exception) {
                        null
                    }
                    res = if (sbStep != null) {
                        AgentApi.ApiResult(200, JSONObject().put("step", sbStep))
                    } else {
                        AgentApi.ApiResult(-1, null)
                    }
                }
                // AI unreachable (network fail ya HTTP 5xx) → deterministic
                // offline fallback. 401 (login) / 429 (limit) par NAHI —
                // wo needs_user handoff hain.
                if (res.code == -1 || res.code in 500..599) {
                    logStep(i, "act", false, "AI unreachable (code=${res.code}) → offline fallback")
                    try { onOfflineMode() } catch (_: Exception) { }
                    val fbRes = try {
                        LocalFallback.run(ctx, engine, goal) { p -> onProgress(p) }
                    } catch (e: Exception) {
                        FormEngine.RunResult(
                            "failed",
                            "offline fallback crash: ${(e.message ?: "error").take(150)}",
                            JSONArray()
                        )
                    }
                    val fbSteps = fbRes.stepResults
                    for (j in 0 until fbSteps.length()) {
                        val o = fbSteps.optJSONObject(j)
                        if (o != null) stepsLog.put(o)
                    }
                    stepsTaken += fbSteps.length()
                    return finish(fbRes.status, fbRes.summary)
                }
                if (res.code == 401) {
                    return finish("needs_user", "Login chahiye — app me login karke phir try karein")
                }
                if (res.code == 429) {
                    return finish("needs_user", "Aaj ka AI limit khatam ho gaya — kal phir try karein")
                }
                val stepJson = if (res.code == 200) res.json?.optJSONObject("step") else null
                if (stepJson == null) {
                    pushHistory("act", emptyMap(), "error", "server code=${res.code}")
                    if (noteError(i, "act", "server se step nahi mila (code=${res.code})")) {
                        return finish(
                            "needs_user",
                            "Server se jawab nahi mil raha — phas gaya hoon, aap dekh lein"
                        )
                    }
                    continue
                }
                val stepMap = jsonToMap(stepJson)
                val action = (stepMap["action"] as? String)?.trim() ?: ""

                // (e) terminal actions — execute nahi hote
                when (action) {
                    "done" -> {
                        val summary = ((stepMap["result_summary"] as? String)?.ifEmpty { null }
                            ?: (stepMap["reason"] as? String)?.ifEmpty { null }
                            ?: "Ho gaya ✅")
                        // Submission proof: final screenshot server pe (best-effort)
                        val proofNote = try {
                            val finalShot = engine.capturePngBase64()
                            if (finalShot.isNotEmpty()) mirrorShot()
                            val proofUrl = RunReporter.uploadProof(
                                ctx, agentRunId ?: "", finalShot
                            )
                            if (!proofUrl.isNullOrEmpty()) "\n📸 proof saved" else ""
                        } catch (_: Exception) {
                            ""
                        }
                        return finish("done", "$summary$proofNote")
                    }
                    "needs_user" -> return finish(
                        "needs_user",
                        ((stepMap["user_prompt"] as? String)?.ifEmpty { null }
                            ?: "Aapki zaroorat hai — app khol ke dekh lein")
                    )
                    "vetoed" -> return finish(
                        "vetoed",
                        ((stepMap["blocked_reason"] as? String)?.ifEmpty { null }
                            ?: "Server ne roka — payment/safety")
                    )
                }

                // (f) client-side validate
                val vErr = validateAgentStep(stepMap)
                if (vErr != null) {
                    pushHistory(action, stepMap, "error", "invalid: $vErr")
                    if (noteError(i, action, "invalid step: $vErr")) {
                        return finish(
                            "needs_user",
                            "Galat step aa raha hai baar-baar — phas gaya hoon, aap dekh lein"
                        )
                    }
                    continue
                }

                // (g) stuck: pichhle 3 signatures same
                val sig = agentStepSig(stepMap)
                recentSigs.add(sig)
                if (recentSigs.size > AgentActions.STUCK_REPEATS) recentSigs.removeAt(0)
                if (recentSigs.size == AgentActions.STUCK_REPEATS &&
                    recentSigs.all { it == sig }
                ) {
                    recentSigs.clear()
                    stuckCount++
                    pushHistory(action, stepMap, "stuck", "same action 3 baar")
                    logStep(i, action, false, "stuck: same action 3 baar (stuck_count=$stuckCount)")
                    if (stuckCount >= AgentActions.STUCK_MAX) {
                        return finish(
                            "needs_user",
                            "Ek hi jagah ghoom raha hoon — phas gaya, aap dekh lein"
                        )
                    }
                    continue
                }

                // (h) execute (veto + timeout runAgentStep ke andar)
                try {
                    val specMap = agentStepToSpec(stepMap)
                    val detail = engine.runAgentStep(mapToJson(specMap))
                    // fill ka read-back verify fail ho to error ki tarah gino
                    if (action == "fill" && !detail.optBoolean("verified", true)) {
                        val msg = "fill verify fail: expected='${
                            detail.optString("expected", "").take(40)
                        }' actual='${detail.optString("value", "").take(40)}'"
                        pushHistory(action, stepMap, "error", msg)
                        if (noteError(i, action, msg)) {
                            return finish(
                                "needs_user",
                                "Fill verify baar-baar fail ho raha hai — phas gaya hoon, aap dekh lein"
                            )
                        }
                        continue
                    }
                    consecErrors = 0
                    stepsTaken++
                    val dStr = detail.toString().take(300)
                    logStep(i, action, true, dStr)
                    pushHistory(action, stepMap, "ok", dStr)
                } catch (e: FormEngine.VetoException) {
                    logStep(i, action, false, "VETO: ${e.message}")
                    return finish("vetoed", "PAYMENT VETO: ${e.message}")
                } catch (e: Exception) {
                    val msg = (e.message ?: "error").take(200)
                    pushHistory(action, stepMap, "error", msg)
                    if (noteError(i, action, msg)) {
                        return finish(
                            "needs_user",
                            "Baar-baar error aa raha hai — phas gaya hoon, aap dekh lein"
                        )
                    }
                }
            }
            return finish(
                "needs_user",
                "$maxSteps steps ho gaye, kaam poora nahi hua — aap dekh lein"
            )
        } finally {
            // session cookies persist karo (login bana rahe)
            try { android.webkit.CookieManager.getInstance().flush() } catch (_: Exception) { }
            try {
                engine.stop()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * CAPTCHA safety net + AUTO-SOLVE (user-authorized: "tum solve kro har ek baar").
     *
     * AI (/api/agent/captcha) SIRF analyze karta hai — type/instruction/
     * characters/position. Solve Operator (ye engine) karta hai:
     *   - image_captcha + characters → input box dhoondh ke type karo
     *   - checkbox (recaptcha/hcaptcha/turnstile) → widget pe tap karo
     * null = koi captcha nahi, ya solve-attempt ho gaya (loop continue);
     * String = user ko dikhane wala note (loop needs_user pe finish).
     * Ek run me max 3 solve-attempts — uske baad user handoff.
     */
    private fun handleCaptcha(
        ctx: Context,
        engine: FormEngine,
        url: String,
        snap: JSONObject,
        attempts: IntArray
    ): String? {
        val det = try {
            engine.detectCaptcha()
        } catch (_: Exception) {
            return null
        }
        if (!det.optBoolean("found", false)) {
            attempts[0] = 0 // captcha gaya — counter reset
            return null
        }
        if (attempts[0] >= 3) {
            return "CAPTCHA 3 baar try kiya, solve nahi hua — aap khud solve karke task dobara chalayein"
        }
        val widgets = det.optJSONArray("widgets") ?: JSONArray()
        val widget = widgets.optJSONObject(0)
        val kind = widget?.optString("kind", "unknown") ?: "unknown"
        val shot = try {
            val b64 = engine.capturePngBase64()
            if (b64.isNotEmpty()) {
                try { engine.writeMirrorPng(ctx) } catch (_: Exception) { }
            }
            b64
        } catch (_: Exception) {
            ""
        }
        val cres = try {
            AgentApi.captcha(
                ctx, JSONObject()
                    .put("screenshot_b64", shot)
                    .put("widget_kind", kind)
                    .put("page_url", url)
            )
        } catch (_: Exception) {
            return "CAPTCHA aaya hai ($kind) — analysis nahi ho paya, aap khud solve karein"
        }
        val cinfo = cres.json?.optJSONObject("captcha")
        val action = cinfo?.optString("action", "needs_user") ?: "needs_user"
        val instruction = cinfo?.optString("instruction", "")
            ?.ifEmpty { "CAPTCHA aaya hai ($kind)" } ?: "CAPTCHA aaya hai ($kind)"

        when (action) {
            "type_text" -> {
                val chars = cinfo?.optString("characters", "") ?: ""
                if (chars.isEmpty()) return "$instruction — aap khud likhein"
                val sel = findCaptchaInput(snap, widget)
                    ?: return "$instruction — input box nahi mila, aap khud likhein: $chars"
                return try {
                    engine.runAgentStep(
                        JSONObject()
                            .put("type", "fill")
                            .put("selector", JSONObject()
                                .put("mode", sel.first).put("value", sel.second))
                            .put("text", chars)
                    )
                    attempts[0]++
                    null // solve-attempt ho gaya — loop continue, agle round me re-check
                } catch (e: FormEngine.VetoException) {
                    "Payment page — safety ke liye rok diya"
                } catch (_: Exception) {
                    "$instruction — type nahi ho paya, aap khud likhein: $chars"
                }
            }
            "click_checkbox" -> {
                val rect = widget?.optJSONObject("rect")
                val cx = rect?.optDouble("x", Double.NaN) ?: Double.NaN
                val cy = rect?.optDouble("y", Double.NaN) ?: Double.NaN
                val w = rect?.optDouble("w", 0.0) ?: 0.0
                val h = rect?.optDouble("h", 0.0) ?: 0.0
                if (cx.isNaN() || cy.isNaN()) return "$instruction — aap khud tick karein"
                val tapped = try {
                    engine.tapAt((cx + w / 2).toFloat(), (cy + h / 2).toFloat())
                } catch (_: Exception) {
                    false
                }
                if (!tapped) return "$instruction — tap nahi hua, aap khud tick karein"
                attempts[0]++
                try { Thread.sleep(2500) } catch (_: Exception) { }
                return null // loop continue — agle round me dekho tick hua ya nahi
            }
            else -> return "$instruction — aap khud solve karein, phir task dobara chalayein"
        }
    }

    /**
     * CAPTCHA input box dhoondho: pehle label/placeholder/aria/name/id me
     * "captcha" wala field; nahi to widget ke sabse paas wala text input.
     * Returns Pair(selectorMode, selectorValue) ya null.
     */
    private fun findCaptchaInput(
        snap: JSONObject,
        widget: JSONObject?
    ): Pair<String, String>? {
        val fields = snap.optJSONArray("fields") ?: return null
        data class F(
            val mode: String, val value: String,
            val cx: Double, val cy: Double, val captchaish: Boolean
        )
        val list = ArrayList<F>()
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: continue
            val tag = f.optString("tag", "")
            if (tag != "input" && tag != "textarea") continue
            val t = f.optString("type", "").lowercase()
            if (t == "hidden" || t == "submit" || t == "button" || t == "checkbox" || t == "radio") continue
            val label = f.optString("label", "")
            val ph = f.optString("placeholder", "")
            val aria = f.optString("aria", "")
            val id = f.optString("id", "")
            val nm = f.optString("name", "")
            val blob = "$label $ph $aria $nm $id".lowercase()
            val captchaish = blob.contains("captcha")
            val mode: String
            val value: String
            when {
                id.isNotEmpty() -> { mode = "id"; value = id }
                nm.isNotEmpty() -> { mode = "name"; value = nm }
                ph.isNotEmpty() -> { mode = "placeholder"; value = ph }
                label.isNotEmpty() -> { mode = "label"; value = label }
                aria.isNotEmpty() -> { mode = "aria"; value = aria }
                else -> continue
            }
            val r = f.optJSONObject("rect")
            val cx = (r?.optDouble("x", 0.0) ?: 0.0) + (r?.optDouble("w", 0.0) ?: 0.0) / 2
            val cy = (r?.optDouble("y", 0.0) ?: 0.0) + (r?.optDouble("h", 0.0) ?: 0.0) / 2
            list.add(F(mode, value, cx, cy, captchaish))
        }
        if (list.isEmpty()) return null
        list.firstOrNull { it.captchaish }?.let { return it.mode to it.value }
        // widget ke sabse paas wala
        val wr = widget?.optJSONObject("rect")
        val wx = (wr?.optDouble("x", 0.0) ?: 0.0) + (wr?.optDouble("w", 0.0) ?: 0.0) / 2
        val wy = (wr?.optDouble("y", 0.0) ?: 0.0) + (wr?.optDouble("h", 0.0) ?: 0.0) / 2
        val nearest = list.minByOrNull {
            val dx = it.cx - wx; val dy = it.cy - wy; dx * dx + dy * dy
        } ?: return null
        val dx = nearest.cx - wx; val dy = nearest.cy - wy
        if (dx * dx + dy * dy > 500 * 500) return null // bahut door — galat field me type nahi karenge
        return nearest.mode to nearest.value
    }

    private fun jsonToMap(o: JSONObject): Map<String, Any?> {
        val m = HashMap<String, Any?>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            m[k] = when (val v = o.opt(k)) {
                is JSONObject -> jsonToMap(v)
                is JSONArray -> v // arrays (fields/buttons) yahan map nahi hote
                JSONObject.NULL -> null
                else -> v
            }
        }
        return m
    }

    private fun mapToJson(m: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) {
            when (v) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    o.put(k, mapToJson(v as Map<String, Any?>))
                }
                null -> o.put(k, JSONObject.NULL)
                else -> o.put(k, v)
            }
        }
        return o
    }
}

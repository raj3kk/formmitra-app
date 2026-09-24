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
        // User se mile values (otp/email/phone/choice) — agle act() calls me context.
        // DHYAAN: OTP/password kabhi userProvided me NAHI aate (neeche
        // handleServerPrompt me sensitiveKeys me daal ke filter hote hain) —
        // wo sirf page me locally bhare jate hain, AI/server ko kabhi nahi bheje jate.
        val userProvided = JSONObject()
        val sensitiveKeys = mutableSetOf<String>()
        // Payment prompt ek run me ek hi baar — doosri baar seedha vetoed.
        var paymentPrompted = false

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

        // Pre-run: goal/start-URL me payment keyword = form-fee expected hai.
        // Yahan ROKO MAT — loop aage badhega; asli payment page aane par
        // mid-loop veto assisted payment flow (user approval) chalayega.
        // (Purana behavior turant vetoed-finish tha — wo legit fee wale
        //  formon ko shuru hone se pehle hi maar deta tha.)
        VetoCheck.find("$goal $startUrl")?.let {
            logStep(0, "precheck", true, "payment keyword '$it' — fee expected, assisted flow ready")
            pushHistory("precheck", mapOf("keyword" to it), "ok", "payment expected")
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
                    // Start URL hi payment page hai (user ne payment link diya) →
                    // assisted payment flow. Verify hua to goto retry (veto
                    // suppress), nahi to pehle jaisa finish.
                    if (!paymentPrompted) {
                        paymentPrompted = true
                        val payRes = handlePaymentPrompt(
                            ctx, engine, startUrl, agentRunId ?: effectiveRunId
                        )
                        if (payRes == "verified") {
                            engine.paymentVerifiedOnce = true
                            pushHistory(
                                "goto",
                                mapOf("action" to "goto", "url" to startUrl),
                                "ok", "payment verified, goto retry"
                            )
                            try {
                                val d2 = engine.runAgentStep(
                                    JSONObject().put("type", "goto").put("url", startUrl)
                                )
                                logStep(0, "goto", true, d2.toString().take(200))
                            } catch (_: Exception) { }
                            consecErrors = 0
                        } else {
                            return finish(
                                if (payRes == "declined") "vetoed" else "needs_user",
                                payMessage(payRes)
                            )
                        }
                    } else {
                        return finish("vetoed", "PAYMENT VETO: ${e.message}")
                    }
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
                    // OTP/password yahan se filtered — AI/server ko kabhi nahi jate
                    .put("user_provided", filteredUserProvided(userProvided, sensitiveKeys))
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
                val stepMap = engine.jsonToMap(stepJson)
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
                    "needs_user" -> {
                        // Structured prompt (server ne user_prompt object bheja)
                        // → popup dikhao, user bhare, loop AAGE badhega (terminal nahi).
                        val promptObj = stepMap["user_prompt"] as? Map<String, Any?>
                        if (promptObj != null) {
                            val ok = handleServerPrompt(
                                ctx, engine, promptObj,
                                agentRunId ?: effectiveRunId, userProvided, sensitiveKeys, history
                            )
                            if (!ok) {
                                return finish(
                                    "needs_user",
                                    "Aapka jawab nahi mila / mana kiya — kaam ruka hai, app khol ke dekhein"
                                )
                            }
                            consecErrors = 0
                            continue
                        }
                        return finish(
                            "needs_user",
                            ((stepMap["user_prompt"] as? String)?.ifEmpty { null }
                                ?: "Aapki zaroorat hai — app khol ke dekh lein")
                        )
                    }
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
                    // Payment page beech me aaya → user se approval lo (assisted
                    // payment). Agent KHUD kabhi pay nahi karta — user apne UPI
                    // app se karta hai, phir agent site par verify karta hai.
                    if (!paymentPrompted) {
                        paymentPrompted = true
                        val payRes = handlePaymentPrompt(
                            ctx, engine, url, agentRunId ?: effectiveRunId
                        )
                        if (payRes == "verified") {
                            // Verify ke baad: live-page veto suppress (receipt par
                            // "payment" text hota hai), loop continue. Step-blob
                            // veto ab bhi active — doosri payment hamesha vetoed.
                            engine.paymentVerifiedOnce = true
                            pushHistory(action, stepMap, "ok", "payment user ne kiya, site par verified")
                            consecErrors = 0
                            continue
                        }
                        return finish(
                            if (payRes == "declined") "vetoed" else "needs_user",
                            payMessage(payRes)
                        )
                    }
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

    // =====================================================================
    // Interactive user prompts (OTP / input / choice / payment).
    // Loop BLOCK karke user ka jawab wait karta hai; jawab mile to kaam
    // aage badhta hai. Timeout/cancel → false → caller needs_user finish.
    // =====================================================================

    /**
     * Server ke structured user_prompt ko popup me badlo.
     * true = jawab mila aur apply ho gaya (loop continue kare);
     * false = timeout / cancel / mana.
     */
    private fun handleServerPrompt(
        ctx: Context,
        engine: FormEngine,
        prompt: Map<String, Any?>,
        runId: String,
        userProvided: JSONObject,
        sensitiveKeys: MutableSet<String>,
        history: ArrayList<JSONObject>
    ): Boolean {
        val kind = (prompt["kind"] as? String)?.trim()?.ifEmpty { null } ?: "input"
        val req = buildPromptRequest(runId, kind, prompt)
        notifyPrompt(ctx, "🤖 ${req.title}", req.message)
        if (kind == "payment") {
            return doPaymentFlow(ctx, engine, req, runId) == "verified"
        }
        val ansStr = UserPrompt.ask(req) ?: return false
        val ans = try { JSONObject(ansStr) } catch (_: Exception) { return false }
        if (!ans.optBoolean("approved", false)) return false
        when (kind) {
            "choice" -> {
                val choice = ans.optString("choice", "")
                if (choice.isNotEmpty()) userProvided.put("choice", choice)
                history.add(
                    JSONObject().put("action", "user_choice")
                        .put("result", "ok").put("detail", choice.take(200))
                )
            }
            else -> { // otp | input
                // field key -> type (sensitive = otp/password: KABHI server/AI
                // ko mat bhejo, sirf page me locally bharo)
                val fieldTypes = ((prompt["fields"] as? List<*>) ?: emptyList<Any>())
                    .mapNotNull { it as? Map<String, Any?> }
                    .associate {
                        ((it["key"] as? String) ?: "value") to
                            ((it["type"] as? String) ?: "text")
                    }
                val ansKeys = ans.keys().asSequence()
                    .filter { it != "approved" }.toList()
                var filledAny = false
                var secretDone = false
                for (k in ansKeys) {
                    val v = ans.optString(k, "")
                    if (v.isEmpty()) continue
                    val t = fieldTypes[k] ?: "text"
                    if (isSensitiveField(k, t)) {
                        // OTP/password: userProvided me NAHI — sirf local fill.
                        sensitiveKeys.add(k)
                        if (fillSecretLocally(engine, prompt, v)) {
                            filledAny = true
                            secretDone = true
                            history.add(
                                JSONObject().put("action", "fill_otp_local")
                                    .put("result", "ok")
                                    .put("detail", "OTP/password page me bhara (value hidden)")
                            )
                        } else {
                            history.add(
                                JSONObject().put("action", "fill_otp_local")
                                    .put("result", "error")
                                    .put("detail", "OTP field nahi mila page par")
                            )
                        }
                    } else {
                        userProvided.put(k, v)
                        filledAny = true
                    }
                }
                // Non-sensitive value + fill_selector (purana behavior):
                // page me bharo, value userProvided me bhi rahegi.
                @Suppress("UNCHECKED_CAST")
                val sel = prompt["fill_selector"] as? Map<String, Any?>
                if (!secretDone && sel != null) {
                    val firstPlain = ansKeys.firstOrNull { k ->
                        !isSensitiveField(k, fieldTypes[k] ?: "text") &&
                            ans.optString(k, "").isNotEmpty()
                    }?.let { ans.optString(it, "") }
                    if (!firstPlain.isNullOrEmpty()) {
                        try {
                            val detail = engine.runAgentStep(
                                JSONObject().put("type", "fill")
                                    .put(
                                        "selector", JSONObject()
                                            .put("mode", sel["mode"] as? String ?: "css")
                                            .put("value", sel["value"] as? String ?: "")
                                    )
                                    .put("text", firstPlain)
                            )
                            val ok = detail.optBoolean("verified", true)
                            history.add(
                                JSONObject().put("action", "fill_user_value")
                                    .put("result", if (ok) "ok" else "error")
                                    .put("detail", "user value bhara (verified=$ok)".take(200))
                            )
                            filledAny = ok
                        } catch (_: Exception) {
                            filledAny = false
                        }
                    }
                }
                if (!filledAny) {
                    // value userProvided me hai — agle act() me brain use karega
                    // (sensitive keys yahan kabhi nahi aate — wo upar filter hain)
                    history.add(
                        JSONObject().put("action", "user_input")
                            .put("result", "ok")
                            .put("detail", "user ne diya: ${userProvided.keys().asSequence().toList()}".take(200))
                    )
                }
            }
        }
        return true
    }

    /** OTP/password jaisi sensitive field? — ye values KABHI AI/server ko nahi jati. */
    private fun isSensitiveField(key: String, type: String): Boolean {
        val t = type.lowercase()
        if (t == "otp" || t == "password") return true
        val k = key.lowercase()
        return k.contains("otp") || k.contains("password") || k.contains("passwd") ||
            k.contains("cvv") || k.contains("card_pin") || k == "pin"
    }

    /**
     * OTP/password ko page me LOCAL bharo (server/AI ko value kabhi nahi bheji jati).
     * fill_selector (server ne diya) → nahi to page par OTP field auto-detect.
     * true = bhara + verified.
     */
    private fun fillSecretLocally(
        engine: FormEngine,
        prompt: Map<String, Any?>,
        value: String
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        val sel = prompt["fill_selector"] as? Map<String, Any?>
        var mode = sel?.get("mode") as? String ?: ""
        var selVal = sel?.get("value") as? String ?: ""
        if (mode.isEmpty() || selVal.isEmpty()) {
            val found = findOtpField(engine)
            if (found == null) return false
            mode = found.first
            selVal = found.second
        }
        return try {
            val detail = engine.runAgentStep(
                JSONObject().put("type", "fill")
                    .put(
                        "selector",
                        JSONObject().put("mode", mode).put("value", selVal)
                    )
                    .put("text", value)
            )
            detail.optBoolean("verified", true)
        } catch (_: Exception) {
            false
        }
    }

    /** Page par OTP field dhoondho (label/placeholder/aria/name/id me "otp"). */
    private fun findOtpField(engine: FormEngine): Pair<String, String>? {
        val snap = try { engine.domSnapshot() } catch (_: Exception) { return null }
        val fields = snap.optJSONArray("fields") ?: return null
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: continue
            val tag = f.optString("tag", "")
            if (tag != "input" && tag != "textarea") continue
            val t = f.optString("type", "").lowercase()
            if (t == "hidden" || t == "submit" || t == "button" ||
                t == "checkbox" || t == "radio" || t == "file"
            ) continue
            val blob = (f.optString("label", "") + " " + f.optString("placeholder", "") +
                " " + f.optString("aria", "") + " " + f.optString("name", "") +
                " " + f.optString("id", "")).lowercase()
            if (!blob.contains("otp")) continue
            val id = f.optString("id", "")
            val nm = f.optString("name", "")
            val ph = f.optString("placeholder", "")
            val label = f.optString("label", "")
            val aria = f.optString("aria", "")
            return when {
                id.isNotEmpty() -> "id" to id
                nm.isNotEmpty() -> "name" to nm
                ph.isNotEmpty() -> "placeholder" to ph
                label.isNotEmpty() -> "label" to label
                aria.isNotEmpty() -> "aria" to aria
                else -> continue
            }
        }
        return null
    }

    /** user_provided ka filtered copy — sensitive keys kabhi server/AI ko nahi jate. */
    private fun filteredUserProvided(
        src: JSONObject,
        sensitive: Set<String>
    ): JSONObject {
        val o = JSONObject()
        val keys = src.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (sensitive.contains(k)) continue
            if (isSensitiveField(k, "")) continue
            o.put(k, src.opt(k))
        }
        return o
    }

    /** Veto pe payment page → amount nikalo → approval+QR+countdown → verify. */
    private fun handlePaymentPrompt(
        ctx: Context,
        engine: FormEngine,
        pageUrl: String,
        runId: String
    ): String {
        val pageText = try {
            engine.domSnapshot().optString("page_text", "")
        } catch (_: Exception) {
            ""
        }
        val amount = PaymentFlow.parseAmount(pageText) ?: ""
        val merchant = try {
            java.net.URL(pageUrl).host ?: "site"
        } catch (_: Exception) {
            "site"
        }
        val req = UserPrompt.Request(
            runId = runId,
            kind = "payment",
            title = "Payment approval",
            message = "Site par payment page aaya hai. Aap khud apne UPI app se pay karoge — agent kabhi khud payment nahi karta.",
            payment = UserPrompt.Payment(amount, merchant, ""),
            timeoutSec = 600
        )
        notifyPrompt(ctx, "💰 Payment approval", "₹$amount — $merchant")
        return doPaymentFlow(ctx, engine, req, runId)
    }

    /**
     * Payment flow: server ko 'requested' → user jawab wait → 'paid_claimed' →
     * site par success keywords verify → 'verified'.
     * @return verified | declined | timeout | unverified
     */
    private fun doPaymentFlow(
        ctx: Context,
        engine: FormEngine,
        req: UserPrompt.Request,
        runId: String
    ): String {
        val pay = req.payment
        try {
            AgentApi.patchPayment(
                ctx, runId,
                JSONObject().put("status", "requested")
                    .put("amount", pay?.amount ?: "")
                    .put("merchant", pay?.merchant ?: "")
                    .put("upi_id", pay?.upiId ?: "")
            )
        } catch (_: Exception) { }
        val ansStr = UserPrompt.ask(req)
        if (ansStr == null) {
            try {
                AgentApi.patchPayment(
                    ctx, runId,
                    JSONObject().put("status", "failed").put("reason", "timeout")
                )
            } catch (_: Exception) { }
            return "timeout"
        }
        val ans = try { JSONObject(ansStr) } catch (_: Exception) { JSONObject() }
        if (!ans.optBoolean("approved", false)) {
            try {
                AgentApi.patchPayment(
                    ctx, runId,
                    JSONObject().put("status", "failed").put("reason", "declined")
                )
            } catch (_: Exception) { }
            return "declined"
        }
        if (!ans.optBoolean("payment_done", false)) {
            try {
                AgentApi.patchPayment(
                    ctx, runId,
                    JSONObject().put("status", "failed")
                        .put("reason", ans.optString("reason", "unknown"))
                )
            } catch (_: Exception) { }
            return "timeout"
        }
        val method = ans.optString("method", "")
        try {
            AgentApi.patchPayment(
                ctx, runId,
                JSONObject().put("status", "paid_claimed").put("method", method)
            )
        } catch (_: Exception) { }
        // Verify: site par success keywords dhoondo (max 3 round, 3s gap)
        repeat(3) {
            val text = try {
                engine.domSnapshot().optString("page_text", "")
            } catch (_: Exception) {
                ""
            }
            if (PaymentFlow.isSuccessText(text)) {
                try {
                    AgentApi.patchPayment(
                        ctx, runId,
                        JSONObject().put("status", "verified")
                            .put("verified_at", System.currentTimeMillis())
                    )
                } catch (_: Exception) { }
                return "verified"
            }
            try { Thread.sleep(3000) } catch (_: Exception) { }
        }
        try {
            AgentApi.patchPayment(
                ctx, runId,
                JSONObject().put("status", "failed").put("reason", "unverified")
            )
        } catch (_: Exception) { }
        return "unverified"
    }

    private fun payMessage(res: String): String = when (res) {
        "declined" -> "Aapne payment se mana kiya — kaam roka gaya 🛑"
        "timeout" -> "Payment ka time khatm ho gaya — dobara try karein ⏳"
        "unverified" -> "Payment verify nahi hua site par — aap khud check karke retry karein ⚠️"
        else -> "Payment poora nahi hua — kaam ruka ⚠️"
    }

    /** Prompt map → UserPrompt.Request. */
    private fun buildPromptRequest(
        runId: String,
        kind: String,
        prompt: Map<String, Any?>
    ): UserPrompt.Request {
        val fields = ((prompt["fields"] as? List<*>) ?: emptyList<Any>())
            .mapNotNull { it as? Map<String, Any?> }
            .map {
                UserPrompt.Field(
                    (it["key"] as? String) ?: "value",
                    (it["label"] as? String) ?: "Likho",
                    (it["type"] as? String) ?: "text"
                )
            }
        val options = ((prompt["options"] as? List<*>) ?: emptyList<Any>())
            .mapNotNull { (it as? String)?.ifEmpty { null } }
        @Suppress("UNCHECKED_CAST")
        val payMap = prompt["payment"] as? Map<String, Any?>
        val payment = if (kind == "payment" || payMap != null) {
            UserPrompt.Payment(
                (payMap?.get("amount") as? String) ?: "",
                (payMap?.get("merchant") as? String) ?: "",
                (payMap?.get("upi_id") as? String) ?: ""
            )
        } else null
        return UserPrompt.Request(
            runId = runId,
            kind = kind,
            title = (prompt["title"] as? String)?.ifEmpty { null } ?: "Madad chahiye",
            message = (prompt["message"] as? String)?.ifEmpty { null }
                ?: "Agent ko aapki zaroorat hai",
            fields = fields,
            options = options,
            payment = payment,
            timeoutSec = (prompt["timeout_s"] as? Number)?.toLong() ?: 600L
        )
    }

    /** Prompt aaye to notification bhi (user kisi bhi tab/app me ho). */
    private fun notifyPrompt(ctx: Context, title: String, msg: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            val chId = "fm_prompts"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        chId, "Agent sawal",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val intent = android.content.Intent(
                ctx, com.formmitra.app.MainActivity::class.java
            ).putExtra("open_tab", "/agent")
            val pi = android.app.PendingIntent.getActivity(
                ctx, 99, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val nb = android.app.Notification.Builder(ctx, chId)
                .setContentTitle(title)
                .setContentText(msg.take(120))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true)
            nm.notify(9011, nb.build())
        } catch (_: Exception) { }
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

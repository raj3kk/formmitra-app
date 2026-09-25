package com.formmitra.app.engine

import android.content.Context
import com.formmitra.app.agent.AgentApi
import com.formmitra.app.agent.ChoiceMemory
import com.formmitra.app.agent.DetailStore
import com.formmitra.app.agent.DocumentAutoPick
import com.formmitra.app.agent.FlowAnnouncer
import com.formmitra.app.agent.NotifCenter
import com.formmitra.app.agent.SiteCredentialStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v14: CAPTCHA auto-solve consent — default ON (persisted). OFF ho to
 * AgentLoop CAPTCHA ko chhoota tak nahi, seedha needs_user handoff.
 */
object CaptchaConsent {
    private const val PREFS = "formmitra_agent_prefs"
    private const val KEY = "captcha_auto_solve"

    fun isEnabled(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)
    } catch (_: Exception) {
        true
    }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, enabled).apply()
        } catch (_: Exception) { }
    }
}

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
        onStandaloneMode: () -> Unit = {},
        /** true = server ko chhodo, har step StandaloneBrain (user ki Groq key) se */
        forceStandalone: Boolean = false,
        /** Category-wise full automation: har act() call me server brain ko
         *  category pata chale (apply_track / zamin_track / resume_create /
         *  job_find / scholarship). */
        category: String = "",
        /**
         * G2 (Background Working Mode): resume par ye step already ho chuke
         * hain — loop (startStep + 1) se continue karega, shuru se nahi.
         */
        startStep: Int = 0
    ): FormEngine.RunResult {
        val stepsLog = JSONArray()
        val history = ArrayList<JSONObject>()
        var stuckCount = 0
        var consecErrors = 0
        var stepsTaken = startStep.coerceAtLeast(0)
        val recentSigs = ArrayList<String>()
        // User se mile values (otp/email/phone/choice) — agle act() calls me context.
        // DHYAAN: OTP/password kabhi userProvided me NAHI aate (neeche
        // handleServerPrompt me sensitiveKeys me daal ke filter hote hain) —
        // wo sirf page me locally bhare jate hain, AI/server ko kabhi nahi bheje jate.
        val userProvided = JSONObject()
        val sensitiveKeys = mutableSetOf<String>()
        // Payment prompt ek run me ek hi baar — doosri baar seedha vetoed.
        var paymentPrompted = false
        // Login/document prompt bhi ek run me ek hi baar (proactive triggers ke liye)
        var loginPrompted = false
        var docPrompted = false

        // Local task: runId khaali ho to local-<timestamp> (standalone mode —
        // UI agent local task banate waqt khud bhi yehi format bhej sakta hai)
        val effectiveRunId = runId.ifEmpty { "local-${System.currentTimeMillis()}" }

        // Resume state: agent_run shuru hote hi save (kill/reboot ke baad
        // WakeWorker checkPending se USI STEP se resume karega). Terminal par
        // clear (finish() me — needs_user/failed par rakha jata hai).
        try {
            AgentResume.save(ctx, goal, startUrl, effectiveRunId, effectiveRunId, category)
        } catch (_: Exception) { }
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
            // G2: needs_user / failed / needs_admin par resume state RAKHO —
            // WakeWorker ya user-jawab par usi step se continue hoga.
            // Sirf true terminal (done/cancelled/vetoed) par clear.
            if (status == "done" || status == "cancelled" || status == "vetoed") {
                try { AgentResume.clear(ctx) } catch (_: Exception) { }
            } else {
                try {
                    AgentResume.updateProgress(ctx, stepsTaken, summary)
                } catch (_: Exception) { }
            }
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
        // Category-wise full automation marker (steps me dikhega)
        if (category.isNotEmpty()) {
            logStep(0, "category", true, "category=$category — poora automation, max $maxSteps steps")
        }

        try {
            engine.start()
        } catch (e: Exception) {
            return finish("failed", "browser start nahi hua: ${e.message}")
        }
        // Screenshot parallel capture ke liye background worker (DOM ke saath
        // ek saath — dono WebView UI-thread queue pe safe hain). try se PEHLE
        // declare (finally me visible rahe).
        val shotExec = Executors.newSingleThreadExecutor()
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
            // G2 resume: startStep steps pehle ho chuke — (startStep + 1) se continue.
            for (i in (stepsTaken + 1)..maxSteps) {
                onProgress(i)

                // (a)+(c) PARALLEL: DOM snapshot + screenshot ek saath
                // (screenshot har 3rd step pe + mirror file UI agent ke liye)
                val shotFuture = if (i % 3 == 1) {
                    shotExec.submit(Callable<String> {
                        try {
                            engine.capturePngBase64()
                        } catch (_: Exception) {
                            ""
                        }
                    })
                } else null
                val snap = try {
                    engine.domSnapshot()
                } catch (_: Exception) {
                    JSONObject()
                }
                val shot = try {
                    shotFuture?.get(20, TimeUnit.SECONDS) ?: ""
                } catch (_: Exception) {
                    ""
                }
                if (shot.isNotEmpty()) mirrorShot()
                val url = snap.optString("url", "").ifEmpty { currentUrl }
                val title = snap.optString("title", "")
                currentUrl = url

                // (b) CAPTCHA safety net (v14 full protocol: max 3 attempts)
                val capNote = handleCaptcha(ctx, engine, url, snap, captchaAttempts)
                if (capNote != null) {
                    logStep(i, "captcha", false, capNote.take(200))
                    return finish("needs_user", capNote)
                }

                // (d) act call — page_analysis ke saath (Operator pehle
                // page samjhata hai; AI is block + screenshot se samajh ke
                // action chunta hai)
                val hArr = JSONArray()
                history.takeLast(15).forEach { hArr.put(it) }
                val reqBody = JSONObject()
                    .put("goal", goal)
                    .put("url", url)
                    .put("page_title", title)
                    .put(
                        "page_analysis", buildPageAnalysis(
                            snap, url,
                            buildStuckReport(stuckCount, recentSigs, history)
                        )
                    )
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
                    // Category-wise automation: brain har step par jaane kaam
                    // kis category ka hai (khali ho to field nahi bhejte)
                    .apply { if (category.isNotEmpty()) put("category", category) }
                    // OTP/password yahan se filtered — AI/server ko kabhi nahi jate
                    .put("user_provided", filteredUserProvided(userProvided, sensitiveKeys))
                // forceStandalone (offline task): server ko chhodo, seedha user ki
                // Groq key se StandaloneBrain. Server unreachable fallback neeche
                // (per-step) waise bhi hai; ye poore run ka standalone mode hai.
                var res: AgentApi.ApiResult = if (forceStandalone && Standalone.isConfigured(ctx)) {
                    try { onStandaloneMode() } catch (_: Exception) { }
                    logStep(i, "act", false, "standalone mode → StandaloneBrain (Groq direct)")
                    val sbStep: JSONObject? = try {
                        StandaloneBrain.decide(ctx, reqBody)
                    } catch (_: Exception) {
                        null
                    }
                    if (sbStep != null) {
                        AgentApi.ApiResult(200, JSONObject().put("step", sbStep))
                    } else {
                        AgentApi.ApiResult(-1, null)
                    }
                } else try {
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
                            if (!proofUrl.isNullOrEmpty()) {
                                // K1: document ready — proof taiyaar, tap par History.
                                try {
                                    NotifCenter.notify(
                                        ctx, NotifCenter.Cat.DOC,
                                        "📄 Document ready",
                                        "$goal — submission proof save ho gaya.",
                                        deepTab = "/history",
                                        deepRunId = agentRunId ?: effectiveRunId,
                                        key = "proof-${agentRunId ?: effectiveRunId}"
                                    )
                                } catch (_: Exception) { }
                                "\n📸 proof saved"
                            } else ""
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
                    // Login page par atke ho? → credentials maango (ek baar),
                    // local-only fill, same run continue.
                    if (!loginPrompted && hasPasswordField(engine)) {
                        loginPrompted = true
                        val loginOk = askLoginProactively(
                            ctx, engine, agentRunId ?: effectiveRunId,
                            sensitiveKeys, history
                        )
                        if (loginOk) {
                            stuckCount = 0
                            consecErrors = 0
                            recentSigs.clear()
                            continue
                        }
                    }
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
                    // G2: har successful step par progress persist — kill/reboot
                    // par WakeWorker usi step se resume karega.
                    try {
                        AgentResume.updateProgress(ctx, stepsTaken, dStr)
                    } catch (_: Exception) { }
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
                    // Upload me file missing → user se document maango (ek baar),
                    // same run continue. Dobara fail → neeche noteError path.
                    if (action == "upload" && !docPrompted &&
                        (msg.contains("file nahi mili") || msg.contains("upload:"))
                    ) {
                        docPrompted = true
                        val docOk = askDocumentProactively(
                            ctx, engine, agentRunId ?: effectiveRunId,
                            userProvided, history
                        )
                        if (docOk) {
                            consecErrors = 0
                            continue
                        }
                    }
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
            try { shotExec.shutdownNow() } catch (_: Exception) { }
            // v14: run khatam/stop — mirror screenshot saaf karo (stale live
            // view na dikhe; naya run nayi image banayega)
            try { java.io.File(ctx.cacheDir, "agent_mirror.png").delete() } catch (_: Exception) { }
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
        // NOTE: duplicate notification nahi — UserPrompt.ask par FmApp ka
        // raised-listener NotifCenter se notify + PendingPrompt persist
        // karta hai (K1/K4). Yahan sirf automation logic.
        if (kind == "payment") {
            return doPaymentFlow(ctx, engine, req, runId) == "verified"
        }
        // ---- L1-UPGRADE: puchhne se PEHLE permanent automation ----
        if (kind == "login") {
            // Saved credentials ho to apne aap login — user se mat puchho
            if (tryAutoLogin(ctx, engine, runId, sensitiveKeys, history)) return true
        }
        if (kind == "document") {
            // Vault me sahi document ho to apne aap attach
            if (tryAutoDocument(ctx, engine, req.docType, userProvided, history)) return true
        }
        if (kind == "choice") {
            return handleChoicePrompt(ctx, engine, req, userProvided, history)
        }
        if (kind == "device_auth") {
            // User-gated #2 (biometric/device PIN): sirf user de sakta hai.
            // Maximum assistance: seedha dialog + TTS announcement.
            FlowAnnouncer.say(
                ctx,
                "Ab aapko apne phone par fingerprint ya PIN dena hai — baaki sab taiyar hai."
            )
            val devStr = UserPrompt.ask(req) ?: return false
            val devAns = try { JSONObject(devStr) } catch (_: Exception) { return false }
            val ok = devAns.optBoolean("approved", false)
            history.add(
                JSONObject().put("action", "device_auth")
                    .put("result", if (ok) "ok" else "cancelled")
            )
            return ok
        }
        // ---- L1-UPGRADE: input — DetailStore (device-local saved details)
        // se jo pata ho wo seedha bharo; sirf jo NA mile uske liye puchho
        // (dialog me pata values pre-filled dikhengi). ----
        var askReq = req
        if (kind == "input") {
            if (tryAutoFillInput(ctx, engine, req, prompt, userProvided, sensitiveKeys, history)) {
                return true
            }
            val prefill = collectKnownInputValues(ctx, req)
            if (prefill.isNotEmpty()) askReq = req.copy(prefill = prefill)
        }
        val ansStr = UserPrompt.ask(askReq) ?: return false
        val ans = try { JSONObject(ansStr) } catch (_: Exception) { return false }
        if (!ans.optBoolean("approved", false)) return false
        when (kind) {
            "login" -> return handleLoginPrompt(ctx, engine, req, ans, sensitiveKeys, history)
            "document" -> return handleDocumentPrompt(ctx, engine, req, ans, userProvided, history)
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
                        // L1-UPGRADE: non-sensitive jawab DetailStore me yaad
                        // rakho — agli baar puchhe bina apne aap bharega.
                        // (OTP/password kabhi save nahi hote.)
                        if (kind == "input") {
                            try {
                                DetailStore.saveAll(ctx, mapOf(k to v))
                            } catch (_: Exception) { }
                        }
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

    // =====================================================================
    // login / document prompts (v10) — same-run handoff, secrets local-only
    // =====================================================================

    /**
     * Login prompt: username+password DIALOG se aaye (ans), dono SENSITIVE.
     * OTP pattern: userProvided me NAHI, server/AI ko NAHI, history me value
     * NAHI — sirf page me locally bharo, phir submit dabane ki koshish karo.
     * true = kuch bhara (loop continue kare).
     */
    /**
     * L1-UPGRADE: saved site credentials se apne aap login.
     * true = credentials mile aur page me bhar diye (user se nahi puchha).
     * false = saved nahi hain → caller user se puchega.
     */
    private fun tryAutoLogin(
        ctx: Context,
        engine: FormEngine,
        runId: String,
        sensitiveKeys: MutableSet<String>,
        history: ArrayList<JSONObject>
    ): Boolean {
        val domain = try {
            SiteCredentialStore.domainOf(engine.pageUrl())
        } catch (_: Exception) { "" }
        if (domain.isEmpty()) return false
        val creds = try {
            SiteCredentialStore.get(ctx, domain)
        } catch (_: Exception) { null } ?: return false
        val ok = fillLoginFields(engine, creds.first, creds.second, sensitiveKeys, history)
        if (ok) {
            history.add(
                JSONObject().put("action", "fill_login_auto")
                    .put("result", "ok")
                    .put("detail", "saved credentials se auto-login (domain=$domain, value hidden)")
            )
            FlowAnnouncer.say(ctx, "Login apne aap ho raha hai — $domain")
        }
        return ok
    }

    /**
     * L1-UPGRADE: vault document apne aap attach.
     * true = unambiguous doc mila aur set/upload ho gaya.
     */
    private fun tryAutoDocument(
        ctx: Context,
        engine: FormEngine,
        docTypeHint: String,
        userProvided: JSONObject,
        history: ArrayList<JSONObject>
    ): Boolean {
        val pick = try {
            DocumentAutoPick.pick(ctx, docTypeHint)
        } catch (_: Exception) { null } ?: return false
        val ok = applyDocument(engine, pick, userProvided, history)
        if (ok) {
            history.add(
                JSONObject().put("action", "document_auto")
                    .put("result", "ok")
                    .put("detail", "vault se apne aap chuna (name hidden, local-only)")
            )
            FlowAnnouncer.say(ctx, "Document apne aap attach ho raha hai.")
        }
        return ok
    }

    /**
     * L1-UPGRADE: input prompt auto-resolve — DetailStore me saved details
     * se jo fields mil jayein unhe page me seedha bharo (mat puchho).
     * Sab fields mile + fill_selector ho → true (koi prompt nahi).
     * Partial/unknown → false (caller prefill ke saath puchega).
     */
    private fun collectKnownInputValues(
        ctx: Context, req: UserPrompt.Request
    ): Map<String, String> {
        val fields = req.fields.ifEmpty {
            listOf(UserPrompt.Field("value", "Likho", "text"))
        }
        val known = LinkedHashMap<String, String>()
        for (f in fields) {
            val v = try { DetailStore.findValue(ctx, f.key) } catch (_: Exception) { "" }
            val v2 = if (v.isNotEmpty()) v else try {
                DetailStore.findValue(ctx, f.label)
            } catch (_: Exception) { "" }
            if (v2.isNotEmpty()) known[f.key] = v2
        }
        return known
    }

    private fun tryAutoFillInput(
        ctx: Context,
        engine: FormEngine,
        req: UserPrompt.Request,
        prompt: Map<String, Any?>,
        userProvided: JSONObject,
        sensitiveKeys: MutableSet<String>,
        history: ArrayList<JSONObject>
    ): Boolean {
        val fields = req.fields.ifEmpty {
            listOf(UserPrompt.Field("value", "Likho", "text"))
        }
        val known = collectKnownInputValues(ctx, req)
        if (known.size != fields.size || known.isEmpty()) return false
        @Suppress("UNCHECKED_CAST")
        val sel = prompt["fill_selector"] as? Map<String, Any?>
        val mode = sel?.get("mode") as? String ?: ""
        val selVal = sel?.get("value") as? String ?: ""
        if (mode.isEmpty() || selVal.isEmpty()) return false
        var okAll = true
        for ((k, v) in known) {
            val t = fields.firstOrNull { it.key == k }?.type ?: "text"
            val ok = try {
                val detail = engine.runAgentStep(
                    JSONObject().put("type", "fill")
                        .put(
                            "selector",
                            JSONObject().put("mode", mode).put("value", selVal)
                        )
                        .put("text", v)
                )
                detail.optBoolean("verified", true)
            } catch (_: Exception) { false }
            if (isSensitiveField(k, t)) sensitiveKeys.add(k)
            else try { userProvided.put(k, v) } catch (_: Exception) { }
            okAll = okAll && ok
        }
        if (okAll) {
            history.add(
                JSONObject().put("action", "fill_input_auto").put("result", "ok")
                    .put("detail", "DetailStore se apne aap bhara (values hidden)")
            )
            FlowAnnouncer.say(ctx, "Details apne aap bhar di hain.")
            return true
        }
        return false
    }

    /**
     * L1-UPGRADE: choice prompt — pehle yaad kiya hua option apne aap.
     * Yaad na ho to user se puchho aur jawab yaad rakho (agli baar auto).
     */
    private fun handleChoicePrompt(
        ctx: Context,
        engine: FormEngine,
        req: UserPrompt.Request,
        userProvided: JSONObject,
        history: ArrayList<JSONObject>
    ): Boolean {
        val domain = try {
            SiteCredentialStore.domainOf(engine.pageUrl())
        } catch (_: Exception) { "" }
        val scope = ChoiceMemory.scope(domain, req.title)
        val remembered = try { ChoiceMemory.get(ctx, scope) } catch (_: Exception) { null }
        if (!remembered.isNullOrEmpty() && req.options.contains(remembered)) {
            userProvided.put("choice", remembered)
            history.add(
                JSONObject().put("action", "user_choice")
                    .put("result", "ok (auto, remembered)")
                    .put("detail", remembered.take(200))
            )
            FlowAnnouncer.say(ctx, "Pichli baar wala option apne aap chun liya: $remembered")
            return true
        }
        val ansStr = UserPrompt.ask(req) ?: return false
        val ans = try { JSONObject(ansStr) } catch (_: Exception) { return false }
        if (!ans.optBoolean("approved", false)) return false
        val choice = ans.optString("choice", "")
        if (choice.isNotEmpty()) {
            userProvided.put("choice", choice)
            try { ChoiceMemory.save(ctx, scope, choice) } catch (_: Exception) { }
        }
        history.add(
            JSONObject().put("action", "user_choice")
                .put("result", "ok").put("detail", choice.take(200))
        )
        return true
    }

    /** Login fields bharo + submit dabao (auto aur manual dono yahi use karte hain). */
    private fun fillLoginFields(
        engine: FormEngine,
        username: String,
        password: String,
        sensitiveKeys: MutableSet<String>,
        history: ArrayList<JSONObject>
    ): Boolean {
        if (username.isEmpty() || password.isEmpty()) return false
        sensitiveKeys.add("username")
        sensitiveKeys.add("password")
        var filledAny = false
        val uField = findLoginField(engine, "username")
        if (uField != null) {
            try {
                val d = engine.runAgentStep(
                    JSONObject().put("type", "fill")
                        .put(
                            "selector",
                            JSONObject().put("mode", uField.first).put("value", uField.second)
                        )
                        .put("text", username)
                )
                if (d.optBoolean("verified", true)) filledAny = true
            } catch (_: Exception) { }
        }
        val pField = findLoginField(engine, "password")
        if (pField != null) {
            try {
                val d = engine.runAgentStep(
                    JSONObject().put("type", "fill")
                        .put(
                            "selector",
                            JSONObject().put("mode", pField.first).put("value", pField.second)
                        )
                        .put("text", password)
                )
                if (d.optBoolean("verified", true)) filledAny = true
            } catch (_: Exception) { }
        }
        var submitted = false
        if (filledAny) {
            submitted = tryTapSubmit(engine)
        }
        history.add(
            JSONObject().put("action", "fill_login_local")
                .put("result", if (filledAny) "ok" else "error")
                .put("detail", "login page me bhara (value hidden), submit=$submitted")
        )
        return filledAny
    }

    private fun handleLoginPrompt(
        ctx: Context,
        engine: FormEngine,
        req: UserPrompt.Request,
        ans: JSONObject,
        sensitiveKeys: MutableSet<String>,
        history: ArrayList<JSONObject>
    ): Boolean {
        val username = ans.optString("username", "").trim()
        val password = ans.optString("password", "")
        val ok = fillLoginFields(engine, username, password, sensitiveKeys, history)
        // L1-UPGRADE: user ne "save karo" tick kiya → agli baar apne aap login
        if (ok && ans.optBoolean("save_login", false)) {
            try {
                val domain = SiteCredentialStore.domainOf(engine.pageUrl())
                if (domain.isNotEmpty()) {
                    SiteCredentialStore.save(ctx, domain, username, password)
                    history.add(
                        JSONObject().put("action", "login_saved")
                            .put("result", "ok")
                            .put("detail", "credentials encrypted save (domain=$domain)")
                    )
                }
            } catch (_: Exception) { }
        }
        return ok
    }

    /**
     * Document prompt: ans = {doc: "<vault filename>"}. Filename SIRF device
     * par rehta hai (engine.selectedDoc) — server/AI ko kabhi nahi jata.
     * userProvided me sirf "document_available"=true flag jata hai taaki brain
     * ko pata chale document ready hai; upload ke liye brain {"type":"upload"}
     * bheje (bina naam) aur engine locally-selected file use karega.
     */
    /** Document set + turant upload (auto aur manual dono yahi use karte hain). */
    private fun applyDocument(
        engine: FormEngine,
        doc: String,
        userProvided: JSONObject,
        history: ArrayList<JSONObject>
    ): Boolean {
        if (doc.isEmpty()) return false
        val file = engine.docFile(doc)
        if (file == null) {
            history.add(
                JSONObject().put("action", "document")
                    .put("result", "error")
                    .put("detail", "document file nahi mili (vault me check karo)")
            )
            return false
        }
        // Filename local-only: engine me set (upload fallback ke liye),
        // server/AI ko SIRF flag jayega.
        engine.setSelectedDoc(doc)
        userProvided.put("document_available", true)
        // Page par file input dikh raha ho to turant upload kar do
        var uploaded = false
        try {
            if (hasFileInput(engine)) {
                val d = engine.runAgentStep(
                    JSONObject().put("type", "upload")
                )
                uploaded = d.optBoolean("uploaded", false)
            }
        } catch (_: Exception) { }
        history.add(
            JSONObject().put("action", "document")
                .put("result", "ok")
                .put(
                    "detail",
                    "document mila (name hidden, local-only), uploaded=$uploaded"
                )
        )
        return true
    }

    private fun handleDocumentPrompt(
        ctx: Context,
        engine: FormEngine,
        req: UserPrompt.Request,
        ans: JSONObject,
        userProvided: JSONObject,
        history: ArrayList<JSONObject>
    ): Boolean {
        val doc = ans.optString("doc", "").trim()
        return applyDocument(engine, doc, userProvided, history)
    }

    /** Page par login field dhoondho: which = "username" | "password". */
    private fun findLoginField(engine: FormEngine, which: String): Pair<String, String>? {
        val snap = try { engine.domSnapshot() } catch (_: Exception) { return null }
        val fields = snap.optJSONArray("fields") ?: return null
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: continue
            if (f.optString("tag", "") != "input") continue
            val t = f.optString("type", "").lowercase()
            if (t == "hidden" || t == "submit" || t == "button" || t == "checkbox" ||
                t == "radio" || t == "file"
            ) continue
            val blob = (f.optString("label", "") + " " + f.optString("placeholder", "") +
                " " + f.optString("aria", "") + " " + f.optString("name", "") +
                " " + f.optString("id", "")).lowercase()
            val match = if (which == "password") {
                t == "password" || blob.contains("password") || blob.contains("passwd")
            } else {
                t != "password" && (blob.contains("user") || blob.contains("email") ||
                    blob.contains("e-mail") || blob.contains("phone") ||
                    blob.contains("mobile") || blob.contains("login"))
            }
            if (!match) continue
            val id = f.optString("id", "")
            val nm = f.optString("name", "")
            val ph = f.optString("placeholder", "")
            return when {
                id.isNotEmpty() -> "id" to id
                nm.isNotEmpty() -> "name" to nm
                ph.isNotEmpty() -> "placeholder" to ph
                else -> continue
            }
        }
        return null
    }

    /** Page par password field hai? (proactive login-prompt trigger ke liye) */
    private fun hasPasswordField(engine: FormEngine): Boolean =
        findLoginField(engine, "password") != null

    /** Page par <input type=file> hai? */
    private fun hasFileInput(engine: FormEngine): Boolean {
        val snap = try { engine.domSnapshot() } catch (_: Exception) { return false }
        val fields = snap.optJSONArray("fields") ?: return false
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: continue
            if (f.optString("tag", "") == "input" &&
                f.optString("type", "").lowercase() == "file"
            ) return true
        }
        return false
    }

    /**
     * Login/Submit button dhoondh ke tap karo. true = tap bhej diya.
     * (User ne dialog me "Login bhardo" dabakar submit approve kiya hai.)
     */
    private fun tryTapSubmit(engine: FormEngine): Boolean {
        val snap = try { engine.domSnapshot() } catch (_: Exception) { return false }
        val buttons = snap.optJSONArray("buttons") ?: return false
        val keys = listOf(
            "login", "log in", "sign in", "signin", "submit", "continue",
            "आगे", "लॉगिन", "प्रवेश"
        )
        for (i in 0 until buttons.length()) {
            val b = buttons.optJSONObject(i) ?: continue
            val text = b.optString("text", "").lowercase()
            if (keys.none { text.contains(it) }) continue
            val rect = b.optJSONObject("rect") ?: continue
            val x = (rect.optDouble("x", -1.0) + rect.optDouble("w", 0.0) / 2).toFloat()
            val y = (rect.optDouble("y", -1.0) + rect.optDouble("h", 0.0) / 2).toFloat()
            if (x < 0 || y < 0) continue
            return try { engine.tapAt(x, y) } catch (_: Exception) { false }
        }
        return false
    }

    /** Upload fail (file missing) → user se document maango, same run continue. */
    private fun askDocumentProactively(
        ctx: Context,
        engine: FormEngine,
        runId: String,
        userProvided: JSONObject,
        history: ArrayList<JSONObject>
    ): Boolean {
        // Pehle se doc mil chuka ho aur file maujood ho → dobara mat puchho
        // (filename local-only: engine.selectedDoc se check)
        val existingAvailable = userProvided.optBoolean("document_available", false)
        if (existingAvailable && engine.selectedDocFile() != null) return true
        // L1-UPGRADE: vault me unambiguous doc ho to apne aap attach — puchho mat
        if (tryAutoDocument(ctx, engine, "", userProvided, history)) return true
        val req = UserPrompt.Request(
            runId = runId,
            kind = "document",
            title = "Document chahiye",
            message = "Is form me document upload karna hai. Vault se chuno ya naya upload karo.",
            timeoutSec = 600L
        )
        val ansStr = UserPrompt.ask(req) ?: return false
        val ans = try { JSONObject(ansStr) } catch (_: Exception) { return false }
        if (!ans.optBoolean("approved", false)) return false
        return handleDocumentPrompt(ctx, engine, req, ans, userProvided, history)
    }

    /** Login form par atke → saved credentials se apne aap, nahi to user se maango. */
    private fun askLoginProactively(
        ctx: Context,
        engine: FormEngine,
        runId: String,
        sensitiveKeys: MutableSet<String>,
        history: ArrayList<JSONObject>
    ): Boolean {
        // L1-UPGRADE: saved ho to apne aap login — dialog mat dikhao
        if (tryAutoLogin(ctx, engine, runId, sensitiveKeys, history)) return true
        val req = UserPrompt.Request(
            runId = runId,
            kind = "login",
            title = "Login chahiye",
            message = "Ye page login maang raha hai. Pehli baar details do — " +
                "\"save karo\" tick karoge to agli baar apne aap login hoga.",
            fields = listOf(
                UserPrompt.Field("username", "Username / Email", "text"),
                UserPrompt.Field("password", "Password", "password")
            ),
            timeoutSec = 600L
        )
        val ansStr = UserPrompt.ask(req) ?: return false
        val ans = try { JSONObject(ansStr) } catch (_: Exception) { return false }
        if (!ans.optBoolean("approved", false)) return false
        return handleLoginPrompt(ctx, engine, req, ans, sensitiveKeys, history)
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
        var fields = ((prompt["fields"] as? List<*>) ?: emptyList<Any>())
            .mapNotNull { it as? Map<String, Any?> }
            .map {
                UserPrompt.Field(
                    (it["key"] as? String) ?: "value",
                    (it["label"] as? String) ?: "Likho",
                    (it["type"] as? String) ?: "text"
                )
            }
        // login kind: server ne fields na diye hon to default username+password
        // (dono sensitive — dialog me mic nahi, values local-only)
        if (kind == "login" && fields.isEmpty()) {
            fields = listOf(
                UserPrompt.Field("username", "Username / Email", "text"),
                UserPrompt.Field("password", "Password", "password")
            )
        }
        // otp kind: default field key "otp" ho taaki loop ishe hamesha
        // sensitive maane (sirf page me locally bhare, server/AI ko kabhi na bheje)
        if (kind == "otp" && fields.isEmpty()) {
            fields = listOf(
                UserPrompt.Field("otp", "OTP", "otp")
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
            docType = (prompt["doc_type"] as? String)?.ifEmpty { null } ?: "",
            timeoutSec = (prompt["timeout_s"] as? Number)?.toLong() ?: 600L
        )
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
    /**
     * v14 CAPTCHA full protocol — AI sirf analyze karta hai, Operator execute:
     *   1. Operator DOM detect (captchaDetect) → AI vision confirm (analyze me).
     *   2. /api/agent/captcha (analyze): screenshot + dom_snippet + url + attempt.
     *   3. Operator executeCaptchaSequence: har step execute, naya screenshot.
     *   4. /api/agent/captcha (verify): solved? nahi → corrected next_action.
     *   5. Low-confidence (<0.5) targets skip + dobara analyze.
     *   6. Max 3 attempts → structured needs_user.
     * Consent toggle default ON; OFF → bina koshish needs_user.
     *
     * null = koi captcha nahi, ya solve ho gaya (loop continue);
     * String = user ko dikhane wala note (loop needs_user pe finish).
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
        // Consent OFF → turant user handoff (koi auto-solve nahi)
        if (!CaptchaConsent.isEnabled(ctx)) {
            return "CAPTCHA aaya hai — auto-solve aapne settings me band kiya hai. " +
                "Page khol ke CAPTCHA khud solve kar lein, phir task dobara chalayein"
        }
        val widgets = det.optJSONArray("widgets") ?: JSONArray()
        val widget = widgets.optJSONObject(0)
        val kind = widget?.optString("kind", "unknown") ?: "unknown"
        val domSnippet = domSnippetForCaptcha(det)

        while (attempts[0] < 3) {
            attempts[0]++
            val attempt = attempts[0]
            // (1) ANALYZE: AI sirf batata hai (type + targets + sequence)
            val shot = captchaShot(ctx, engine)
            val ares = try {
                AgentApi.captcha(
                    ctx, JSONObject()
                        .put("mode", "analyze")
                        .put("screenshot_b64", shot)
                        .put("dom_snippet", domSnippet)
                        .put("url", url)
                        .put("attempt", attempt)
                )
            } catch (_: Exception) {
                AgentApi.ApiResult(-1, null)
            }
            if (ares.code != 200 || ares.json == null) {
                // AI unreachable — ye attempt fail; dobara analyze ya handoff
                if (attempt >= 3) break
                continue
            }
            val cap = ares.json!!.optJSONObject("captcha") ?: JSONObject()
            if (cap.optBoolean("is_access_wall", false)) {
                return "Ye page access-wall hai (login wall) — CAPTCHA auto-solve yahan allowed nahi. " +
                    "Aap khud login karke task dobara chalayein"
            }
            // (2) EXECUTE: Operator har step khud karta hai
            executeCaptchaSequence(ctx, engine, cap, snap, widget)
            // (3) VERIFY: naya screenshot → AI verify karta hai
            val vshot = captchaShot(ctx, engine)
            val vres = try {
                AgentApi.captcha(
                    ctx, JSONObject()
                        .put("mode", "verify")
                        .put("screenshot_b64", vshot)
                        .put("dom_snippet", domSnippet)
                        .put("url", url)
                        .put("attempt", attempt)
                        .put("verify_hint", cap.optString("verify_hint", ""))
                )
            } catch (_: Exception) {
                AgentApi.ApiResult(-1, null)
            }
            val vcap = vres.json?.optJSONObject("captcha")
            val solved = vres.code == 200 && vcap?.optBoolean("solved", false) == true
            if (solved) {
                attempts[0] = 0
                return null
            }
            val nextAction = vcap?.optString("next_action", "") ?: ""
            // corrected next_action mila to turant apply (bina naye analyze ke)
            if (nextAction.isNotEmpty() && attempt < 3) {
                applyCorrectedCaptchaAction(ctx, engine, nextAction, cap, snap, widget)
            }
        }
        return "CAPTCHA 3 baar try kiya ($kind), solve nahi hua — aap khud solve " +
            "karke task dobara chalayein"
    }

    /** CAPTCHA screenshots: uncapped (coords linear rahein) + mirror UI ke liye. */
    private fun captchaShot(ctx: Context, engine: FormEngine): String {
        return try {
            val b64 = engine.captureCaptchaPngBase64()
            if (b64.isNotEmpty()) {
                try { engine.writeMirrorPng(ctx) } catch (_: Exception) { }
            }
            b64
        } catch (_: Exception) {
            ""
        }
    }

    /** detectCaptcha widgets ke HTML se capped snippet (AI analyze ke liye). */
    private fun domSnippetForCaptcha(det: JSONObject): String {
        val sb = StringBuilder()
        val widgets = det.optJSONArray("widgets") ?: JSONArray()
        for (i in 0 until widgets.length()) {
            if (sb.length > 3000) break
            val w = widgets.optJSONObject(i) ?: continue
            sb.append("[")
                .append(w.optString("kind", "?"))
                .append("] ")
                .append(w.optString("html", "").take(600))
                .append("\n")
        }
        return sb.toString().take(3200)
    }

    /**
     * v14: AI ki action_sequence execute karo. Har step Operator khud karta
     * hai; confidence < 0.5 wale targets skip (dobara analyze hoga).
     * @return true = kam se kam ek step execute hua.
     */
    private fun executeCaptchaSequence(
        ctx: Context,
        engine: FormEngine,
        cap: JSONObject,
        snap: JSONObject,
        widget: JSONObject?
    ): Boolean {
        val seq = cap.optJSONArray("action_sequence") ?: JSONArray()
        var didAny = false
        if (seq.length() == 0) {
            // sequence nahi aayi → type-based legacy fallback
            return executeCaptchaLegacy(ctx, engine, cap, snap, widget)
        }
        for (i in 0 until seq.length()) {
            val st = seq.optJSONObject(i) ?: continue
            val op = st.optString("op", "")
            try {
                when (op) {
                    "tap" -> {
                        val t = st.optJSONObject("target") ?: continue
                        if (t.optDouble("confidence", 1.0) < 0.5) continue // skip, re-analyze
                        val x = t.optDouble("x", -1.0)
                        val y = t.optDouble("y", -1.0)
                        if (x < 0 || y < 0) continue
                        if (engine.tapNormalized(x, y)) {
                            didAny = true
                            Thread.sleep(700)
                        }
                    }
                    "type" -> {
                        val text = st.optString("text", "")
                        if (text.isEmpty()) continue
                        if (typeIntoCaptchaInput(engine, snap, widget, text)) {
                            didAny = true
                            Thread.sleep(700)
                        }
                    }
                    "slide" -> {
                        val from = st.optJSONObject("from")
                        val to = st.optJSONObject("to")
                        if (from == null || to == null) continue
                        if (engine.swipeNormalized(
                                from.optDouble("x", 0.0), from.optDouble("y", 0.0),
                                to.optDouble("x", 0.0), to.optDouble("y", 0.0)
                            )
                        ) {
                            didAny = true
                            Thread.sleep(1000)
                        }
                    }
                    "wait" -> {
                        val secs = st.optDouble("seconds", 2.0).coerceIn(0.5, 10.0)
                        Thread.sleep((secs * 1000).toLong())
                        didAny = true
                    }
                }
            } catch (_: Exception) { }
        }
        return didAny
    }

    /**
     * action_sequence khaali ho to type-based fallback (legacy action field).
     */
    private fun executeCaptchaLegacy(
        ctx: Context,
        engine: FormEngine,
        cap: JSONObject,
        snap: JSONObject,
        widget: JSONObject?
    ): Boolean {
        val type = cap.optString("type", "")
        val targets = cap.optJSONArray("targets") ?: JSONArray()
        fun targetXY(idx: Int): Pair<Double, Double>? {
            val t = targets.optJSONObject(idx) ?: return null
            if (t.optDouble("confidence", 1.0) < 0.5) return null
            val x = t.optDouble("x", -1.0); val y = t.optDouble("y", -1.0)
            return if (x >= 0 && y >= 0) x to y else null
        }
        return try {
            when (type) {
                "checkbox" -> {
                    val xy = targetXY(0) ?: widgetCenter(widget) ?: return false
                    val ok = engine.tapNormalized(xy.first, xy.second)
                    if (ok) Thread.sleep(2500)
                    ok
                }
                "text_entry" -> {
                    val chars = cap.optString("characters", "")
                    if (chars.isEmpty()) return false
                    typeIntoCaptchaInput(engine, snap, widget, chars)
                }
                "image_select" -> {
                    var any = false
                    for (i in 0 until targets.length()) {
                        val xy = targetXY(i) ?: continue
                        if (engine.tapNormalized(xy.first, xy.second)) {
                            any = true
                            Thread.sleep(500)
                        }
                    }
                    any
                }
                "puzzle_slide" -> {
                    val a = targetXY(0); val b = targetXY(1)
                    if (a == null || b == null) return false
                    val ok = engine.swipeNormalized(a.first, a.second, b.first, b.second)
                    if (ok) Thread.sleep(1000)
                    ok
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Widget rect ka center (CSS px) — checkbox fallback ke liye. */
    private fun widgetCenter(widget: JSONObject?): Pair<Double, Double>? {
        val rect = widget?.optJSONObject("rect") ?: return null
        val x = rect.optDouble("x", Double.NaN)
        val y = rect.optDouble("y", Double.NaN)
        if (x.isNaN() || y.isNaN()) return null
        return (x + rect.optDouble("w", 0.0) / 2) to (y + rect.optDouble("h", 0.0) / 2)
    }

    /**
     * Verify ka corrected next_action turant apply karo (naye analyze ka
     * wait kiye bina). Formats: "tap:x,y" ya "type:<text>".
     */
    private fun applyCorrectedCaptchaAction(
        ctx: Context,
        engine: FormEngine,
        nextAction: String,
        cap: JSONObject,
        snap: JSONObject,
        widget: JSONObject?
    ) {
        try {
            val na = nextAction.trim()
            when {
                na.startsWith("tap:") -> {
                    val parts = na.removePrefix("tap:").split(",")
                    if (parts.size == 2) {
                        val x = parts[0].toDoubleOrNull()
                        val y = parts[1].toDoubleOrNull()
                        if (x != null && y != null) {
                            engine.tapNormalized(x, y)
                            Thread.sleep(700)
                        }
                    }
                }
                na.startsWith("type:") -> {
                    val text = na.removePrefix("type:")
                    if (text.isNotEmpty()) typeIntoCaptchaInput(engine, snap, widget, text)
                }
            }
        } catch (_: Exception) { }
    }

    /** CAPTCHA characters ko page ke input me locally type karo (AI ko nahi bhejte). */
    private fun typeIntoCaptchaInput(
        engine: FormEngine,
        snap: JSONObject,
        widget: JSONObject?,
        text: String
    ): Boolean {
        val sel = findCaptchaInput(snap, widget) ?: return false
        return try {
            engine.runAgentStep(
                JSONObject()
                    .put("type", "fill")
                    .put("selector", JSONObject()
                        .put("mode", sel.first).put("value", sel.second))
                    .put("text", text)
            )
            Thread.sleep(700)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * v14 Operator page-understanding: har /api/agent/act call se pehle
     * Operator page ka analysis banata hai — AI screenshot ke SAATH is block
     * ko padh ke pehle page SAMAJHTA hai, phir action chunta hai.
     * - page_kind (+ confidence): login | otp | captcha | payment | form |
     *   success | error | listing | unknown
     * - visible_summary: page par kya dikh raha hai (2-4 lines)
     * - stuck_report: atka hua? kahan? kyun?
     * - candidate_elements: label/type/state ke saath (max 20)
     */
    private fun buildPageAnalysis(
        snap: JSONObject,
        url: String,
        stuckReport: String
    ): JSONObject {
        val pageText = snap.optString("page_text", "")
        val title = snap.optString("title", "")
        val (kind, kindConf) = inferPageKind(snap, url, title, pageText)
        return JSONObject()
            .put("page_kind", kind)
            .put("page_kind_confidence", kindConf)
            .put("visible_summary", visibleSummary(snap, title, pageText))
            .put("stuck_report", stuckReport)
            .put("candidate_elements", candidateElements(snap))
    }

    /** DOM text + fields + URL se page kind infer karo. */
    private fun inferPageKind(
        snap: JSONObject,
        url: String,
        title: String,
        pageText: String
    ): Pair<String, Double> {
        val t = "$title $pageText $url".lowercase()
        val fields = snap.optJSONArray("fields") ?: JSONArray()
        var hasPassword = false
        var hasOtpish = false
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: continue
            val ty = f.optString("type", "").lowercase()
            val blob = "${f.optString("label", "")} ${f.optString("placeholder", "")} " +
                "${f.optString("aria", "")} ${f.optString("name", "")} " +
                "${f.optString("id", "")}".lowercase()
            if (ty == "password") hasPassword = true
            if (blob.contains("otp") || blob.contains("one-time") ||
                blob.contains("verification code") || blob.contains("verify code")
            ) hasOtpish = true
        }
        fun has(vararg kws: String) = kws.any { t.contains(it) }
        return when {
            hasPassword -> "login" to 0.9
            hasOtpish || has("enter otp", "otp bheja", "otp sent") -> "otp" to 0.85
            has("captcha", "i'm not a robot", "i am not a robot") -> "captcha" to 0.85
            has("payment", "upi", "pay now", "checkout", "card number", "cvv",
                "netbanking", "bhugtan", "पेमेंट") -> "payment" to 0.85
            has("success", "thank you", "submitted", "ho gaya", "mil gaya",
                "confirmed") -> "success" to 0.75
            has("error", "404", "not found", "something went wrong",
                "try again later") -> "error" to 0.7
            fields.length() > 0 -> "form" to 0.7
            else -> "unknown" to 0.4
        }
    }

    /** Page par kya dikh raha hai — compact summary (AI ke liye). */
    private fun visibleSummary(
        snap: JSONObject,
        title: String,
        pageText: String
    ): String {
        val sb = StringBuilder()
        if (title.isNotEmpty()) sb.append("Title: ").append(title.take(120)).append("\n")
        val text = pageText.trim().replace("\\s+".toRegex(), " ").take(600)
        if (text.isNotEmpty()) sb.append("Text: ").append(text).append("\n")
        val fields = snap.optJSONArray("fields") ?: JSONArray()
        val buttons = snap.optJSONArray("buttons") ?: JSONArray()
        sb.append("Fields: ").append(fields.length())
            .append(", Buttons: ").append(buttons.length())
        val labels = ArrayList<String>()
        for (i in 0 until buttons.length().coerceAtMost(8)) {
            val b = buttons.optJSONObject(i) ?: continue
            val lbl = b.optString("label", "").trim().take(30)
            if (lbl.isNotEmpty()) labels.add(lbl)
        }
        if (labels.isNotEmpty()) sb.append("\nButtons: ").append(labels.joinToString(" | "))
        return sb.toString().take(1000)
    }

    /** Candidate elements: label/type/state (max 20) — AI inhi me se chunta hai. */
    private fun candidateElements(snap: JSONObject): JSONArray {
        val out = JSONArray()
        fun push(tag: String, f: JSONObject) {
            if (out.length() >= 20) return
            val label = (f.optString("label", "").ifEmpty {
                f.optString("placeholder", "")
            }.ifEmpty { f.optString("aria", "") }
                .ifEmpty { f.optString("text", "") }
                .ifEmpty { f.optString("id", "") }).trim().take(60)
            val rect = f.optJSONObject("rect")
            out.put(
                JSONObject()
                    .put("tag", tag)
                    .put("label", label)
                    .put("type", f.optString("type", "").take(20))
                    .put("state", elementState(f))
                    .put("x", rect?.optDouble("x", 0.0) ?: 0.0)
                    .put("y", rect?.optDouble("y", 0.0) ?: 0.0)
            )
        }
        val fields = snap.optJSONArray("fields") ?: JSONArray()
        for (i in 0 until fields.length()) {
            fields.optJSONObject(i)?.let { push(it.optString("tag", "input"), it) }
        }
        val buttons = snap.optJSONArray("buttons") ?: JSONArray()
        for (i in 0 until buttons.length()) {
            buttons.optJSONObject(i)?.let { push("button", it) }
        }
        return out
    }

    /** Element state: visible/enabled/checked/selected/disabled. */
    private fun elementState(f: JSONObject): String {
        val parts = ArrayList<String>()
        val rect = f.optJSONObject("rect")
        val w = rect?.optDouble("w", 0.0) ?: 0.0
        val h = rect?.optDouble("h", 0.0) ?: 0.0
        parts.add(if (w > 0 && h > 0) "visible" else "hidden")
        if (f.optBoolean("disabled", false)) parts.add("disabled") else parts.add("enabled")
        if (f.optBoolean("checked", false)) parts.add("checked")
        val sel = f.optString("selected", "")
        if (sel.isNotEmpty()) parts.add("selected=$sel")
        return parts.joinToString(",")
    }

    /**
     * v14 stuck_report: atka hua? kahan? kyun? — history ke aakhri
     * error/stuck entries se banta hai.
     */
    private fun buildStuckReport(
        stuckCount: Int,
        recentSigs: ArrayList<String>,
        history: ArrayList<JSONObject>
    ): String {
        if (stuckCount == 0 && recentSigs.isEmpty()) return "not stuck"
        val sb = StringBuilder()
        sb.append("stuck_count=").append(stuckCount)
        if (recentSigs.isNotEmpty()) {
            sb.append("; repeating=").append(recentSigs.takeLast(3).joinToString(" ~ ").take(300))
        }
        val fails = ArrayList<String>()
        for (i in history.size - 1 downTo 0) {
            if (fails.size >= 3) break
            val h = history[i]
            val r = h.optString("result", "")
            if (r == "error" || r == "stuck") {
                fails.add(
                    "${h.optString("action", "?")}: ${h.optString("detail", "").take(120)}"
                )
            }
        }
        if (fails.isNotEmpty()) sb.append("; last_failures=[").append(fails.joinToString(" | ")).append("]")
        return sb.toString().take(800)
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

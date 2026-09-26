package com.formmitra.app.engine

import android.content.Context
import com.formmitra.app.agent.AgentApi
import com.formmitra.app.agent.CardJson
import com.formmitra.app.agent.ChoiceMemory
import com.formmitra.app.agent.DetailStore
import com.formmitra.app.agent.DocumentAutoPick
import com.formmitra.app.agent.FieldMapMemory
import com.formmitra.app.agent.FlowAnnouncer
import com.formmitra.app.agent.LearnLogic
import com.formmitra.app.agent.LiveActivity
import com.formmitra.app.agent.NotifCenter
import com.formmitra.app.agent.SiteCredentialStore
import com.formmitra.app.agent.WorkPatternStore
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
         * v29 (P1-APP): is work me user jo details de chuka (card + session
         * merged, canonical keys) — har /api/agent/act call me
         * `known_details` jayega taaki brain dobara wahi detail na maange.
         */
        knownDetails: Map<String, String> = emptyMap(),
        /**
         * v29 (P1-APP): is work me pehle poochhe gaye keys (asked_for
         * dedupe) — har act() call me `asked_already` jayega.
         */
        askedAlready: List<String> = emptyList(),
        /**
         * G2 (Background Working Mode): resume par ye step already ho chuke
         * hain — loop (startStep + 1) se continue karega, shuru se nahi.
         */
        startStep: Int = 0,
        /**
         * POINT 21: har proof screenshot par callback (summary card me
         * "kitne proof" dikhane ke liye).
         */
        onProof: () -> Unit = {},
        /**
         * v36 SMART COORDINATION: kaam shuru hone se pehle bana pre-flight
         * plan ka Hinglish summary — UI ise user ko dikhaye (notification /
         * chat). Plan banta hai to call hota hai, nahi to nahi.
         */
        onPlan: (String) -> Unit = {}
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
        // v24-refine (AI-training + quota): har act() call ka reason
        // (nayi/stuck/complex) + stuck-diagnosis ka state.
        var actReason = AiUsage.R_NEW_STEP
        var stuckDiagnosed = false
        var lastDiagnosis: List<String> = emptyList()
        // v36 SMART COORDINATION: escalation ladder state.
        // pattern (zero AI) → agent_plan → ai_single_step → user_gate.
        var ladderLevel = EscalationLadder.L_PATTERN
        // v36: pre-flight plan (kaam shuru hone se pehle AI se ek baar me).
        var preflightPlan: PreflightPlan.Plan? = null
        var planContextSent = false
        var planMissingDetails: List<String> = emptyList()
        // v36: learned work-pattern replay (zero AI).
        var patternSteps: JSONArray? = null
        var patternKey = ""
        // v36 refine point 3: pattern save ke waqt ka page-structure hash
        // (replay se pehle compare — site badli to replay skip).
        var patternEntryHash = ""
        var startPageHash = ""
        // v36 refine point 2: per-step fail count (same step 2-3 baar fail →
        // AI single-step help → phir bhi fail → user gate; infinite loop kabhi nahi).
        val stepFailCounts = LinkedHashMap<String, Int>()
        // v36: is run ke verified steps — done par work-pattern save hoga
        // (sirf source KEYs, personal values kabhi nahi).
        val patternStepsCollected = JSONArray()
        // v37 GLOBAL PLAYBOOK + AI MIND state.
        // runMemory: server working memory (run start par GET — kya ho chuka).
        // userFacts: user-memory facts (private, sirf apna — dobara sawal nahi).
        // inferredState/District: playbook key ke liye (card/device/memory se).
        // globalPatternId: global pattern use hua to outcome report ke liye.
        // memDecisions/memEvidence/memFailures/memGates: PATCH append ke liye
        // jama (har call-site par read+write — MemoryWiring pin).
        var runMemory: JSONObject? = null
        var userFacts: Map<String, String> = emptyMap()
        var inferredState = ""
        var inferredDistrict = ""
        var globalPatternId = ""
        // v37: run-memory se kitne steps pehle ho chuke (pattern replay inhe skip karega)
        var memoryResumeSteps = 0
        val memDecisions = JSONArray()
        val memEvidence = JSONArray()
        val memFailures = JSONArray()
        val memGates = JSONArray()

        // Local task: runId khaali ho to local-<timestamp> (standalone mode —
        // UI agent local task banate waqt khud bhi yehi format bhej sakta hai)
        val effectiveRunId = runId.ifEmpty { "local-${System.currentTimeMillis()}" }

        // Resume state: agent_run shuru hote hi save (kill/reboot ke baad
        // WakeWorker checkPending se USI STEP se resume karega). Terminal par
        // clear (finish() me — needs_user/failed par rakha jata hai).
        try {
            AgentResume.save(ctx, goal, startUrl, effectiveRunId, effectiveRunId, category)
        } catch (_: Exception) { }
        // v24-refine (AI-training + quota discipline): run-scoped AI-usage
        // hisaab reset.
        AiUsage.reset()
        // v36 — LIVE ACTIVITY INDICATOR (user order 2026-09-26): chat me
        // neeche transient status line — kaam shuru.
        try { LiveActivity.emitState(effectiveRunId, LiveActivity.STATE_STARTED) }
        catch (_: Exception) { }
        // (site-memory block neeche hai — local funs ke baad)
        // Server-side run record (best-effort — fail ho to bina reporting chalao)
        var agentRunId: String? = null
        try { agentRunId = RunReporter.createRun(ctx, goal, startUrl, effectiveRunId) } catch (_: Exception) { }
        // (v37 AI MIND memory read — local funs ke BAAD, finish() se pehle)

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

        /**
         * v37 AI MIND — har call-site par outcome wapas likho (PATCH append).
         * Accumulate bhi hota hai (finish par final append) + turant server
         * ko bhi bheja jata hai — BEST-EFFORT, fail ho to chup-chaap.
         * kind: "ai_decisions" | "evidence" | "failures" | "gates".
         */
        fun memoryNote(kind: String, entry: JSONObject) {
            try {
                val arr = when (kind) {
                    "ai_decisions" -> memDecisions
                    "evidence" -> memEvidence
                    "failures" -> memFailures
                    "gates" -> memGates
                    else -> return
                }
                arr.put(entry)
                // v37: memory ka STABLE key = effectiveRunId (task ka run_id —
                // WakeWorker resume par wahi task aata hai). agentRunId har
                // resume par NAYA server run banata hai, isliye us par
                // likha memory resume par kabhi wapas nahi milta.
                val rid = effectiveRunId
                if (rid.isNotEmpty()) {
                    RunMemory.appendRun(
                        ctx, rid, JSONObject().put(kind, JSONArray().put(entry))
                    )
                }
            } catch (_: Exception) { }
        }

        // v37 AI MIND — run start par memory READ (MemoryWiring "run_start":
        // read+write pin). Yahan:
        //  - run-memory GET: completed_steps pehle ho chuke hon to skip
        //    karke wahin se continue (resume).
        //  - user-memory facts: pata hai to dobara mat poochho (detail
        //    collection + preflight missing-filter me use).
        //  - state/district inference (playbook key): card → known →
        //    device → user-memory. Genuinely missing → preflight missing
        //    me EK baar poochha jayega.
        // Sab best-effort: memory na mile to run waise bhi chalta hai.
        try {
            val mem = RunMemory.getRun(ctx, effectiveRunId)
            if (mem != null) {
                runMemory = mem
                val done = RunMemory.completedSteps(mem)
                memoryResumeSteps = RunMemory.resumeFrom(mem)
                if (done.isNotEmpty()) {
                    logStep(
                        0, "memory", true,
                        "run-memory: pehle ${done.size} steps ho chuke (resume) — yahan se continue"
                    )
                    if (stepsTaken < memoryResumeSteps) stepsTaken = memoryResumeSteps
                }
            }
            val uf = RunMemory.userFacts(ctx)
            if (uf.isNotEmpty()) userFacts = uf
        } catch (_: Exception) { }
        try {
            val detailMap = LinkedHashMap<String, String>()
            // (1) Card details (unlock ke baad ka active card) — NESTED
            // {card:{details}} shape → CardJson.detailsOf (AGENTS.md lesson).
            try {
                val cid = AgentApi.automationCardId
                val ctok = AgentApi.automationCardToken
                if (!cid.isNullOrEmpty() && !ctok.isNullOrEmpty()) {
                    val res = AgentApi.cardDetail(ctx, cid, ctok)
                    if (res.code in 200..299) {
                        val det = CardJson.detailsOf(res.json)
                        detailMap.putAll(StateInference.flattenDetails(det))
                    }
                }
            } catch (_: Exception) { }
            // (2) is work me pehle mili details (3) device-saved (4) user-memory
            for ((k, v) in knownDetails) {
                if (v.isNotEmpty()) detailMap.putIfAbsent(k, v)
            }
            try {
                for ((k, v) in com.formmitra.app.agent.DetailStore.loadAll(ctx)) {
                    if (v.isNotEmpty()) detailMap.putIfAbsent(k, v)
                }
            } catch (_: Exception) { }
            for ((k, v) in userFacts) detailMap.putIfAbsent(k, v)
            val (st, dt) = StateInference.fromSources(detailMap)
            inferredState = st
            inferredDistrict = dt
        } catch (_: Exception) { }
        // v37: run open — memory me darj (run_start ka WRITE side).
        try {
            RunMemory.appendRun(
                ctx, effectiveRunId,
                JSONObject().put(
                    "run_start", JSONObject()
                        .put("goal", goal.take(120))
                        .put("at", System.currentTimeMillis())
                        .put("resume_steps", memoryResumeSteps)
                )
            )
        } catch (_: Exception) { }

        fun finish(status: String, summary: String): FormEngine.RunResult {
            // v36 — LIVE ACTIVITY INDICATOR: kaam khatam/ruka/fail →
            // chat ka transient indicator GAYAB. needs_user = gate label.
            try {
                when (status) {
                    "done" -> LiveActivity.emitState(effectiveRunId, LiveActivity.STATE_DONE)
                    "needs_user" -> LiveActivity.emitGate(effectiveRunId, "needs_user")
                    else -> LiveActivity.emitState(effectiveRunId, LiveActivity.STATE_FAILED)
                }
            } catch (_: Exception) { }
            // v24-refine (quota discipline): run ke end par AI-usage ka
            // saaf hisaab steps-log me — kitni AI calls (kyun-kyn) aur
            // kitni pattern-hits (kitni calls bachi).
            try { logStep(stepsTaken, "ai_usage", true, AiUsage.summary()) }
            catch (_: Exception) { }
            // v36 user order (2026-09-26): token/model/cost ki jaankari user
            // ko KAHIN nahi dikhegi — isliye tokenSummary() steps-log me NAHI
            // jata. Asli hisaab server-side ledger me (admin panel, admin-only).
            // v36: kaam POORA hua → verified workflow pattern seekho
            // (agli baar zero-AI replay). Sirf source KEYs — personal
            // values kabhi save nahi hote.
            if (status == "done") {
                try {
                    if (patternKey.isNotEmpty() && patternStepsCollected.length() >= 2) {
                        WorkPatternStore.save(
                            ctx, patternKey, patternStepsCollected,
                            preflightPlan?.promptVersion.orEmpty(),
                            startPageHash,
                            startUrl,
                            preflightPlan?.expectedProofs ?: emptyList()
                        )
                        logStep(
                            stepsTaken, "pattern_learned", true,
                            "tareeka seekh liya — agli baar bina AI ke (${patternStepsCollected.length()} steps)"
                        )
                        // v37 GLOBAL PLAYBOOK — seekha hua tareeka server ko
                        // propose karo (sanitizer andar: PII/value mile to
                        // propose HI nahi hota). Best-effort.
                        val proposed = try {
                            com.formmitra.app.agent.GlobalPlaybook.proposePattern(
                                ctx, goal,
                                SiteCredentialStore.domainOf(startUrl),
                                inferredState, inferredDistrict,
                                patternStepsCollected,
                                preflightPlan?.expectedProofs ?: emptyList(),
                                verified = true, retries = 0, userCorrected = false
                            )
                        } catch (_: Exception) {
                            false
                        }
                        logStep(
                            stepsTaken, "playbook_propose", proposed,
                            if (proposed) "shared playbook me bheja"
                            else "propose nahi hua (sanitizer/network) — local pattern bana raha"
                        )
                    }
                } catch (_: Exception) { }
            }
            // v37: global pattern use hua tha → server ko outcome batao
            // (success/fail) — best-effort.
            try {
                if (globalPatternId.isNotEmpty()) {
                    com.formmitra.app.agent.GlobalPlaybook.reportOutcome(
                        ctx, globalPatternId, status == "done"
                    )
                    logStep(
                        stepsTaken, "playbook_outcome", true,
                        "global pattern outcome: ${if (status == "done") "success" else "fail"}"
                    )
                    globalPatternId = ""
                }
            } catch (_: Exception) { }
            // v37 AI MIND — run finish par final memory append (read+write):
            // jama ki hui ai_decisions/evidence/failures/gates + terminal.
            // Key = effectiveRunId (run-start read ke barabar — stable).
            try {
                val rid = effectiveRunId
                if (rid.isNotEmpty()) {
                    RunMemory.appendRun(
                        ctx, rid, JSONObject()
                            .put("ai_decisions", memDecisions)
                            .put("evidence", memEvidence)
                            .put("failures", memFailures)
                            .put("gates", memGates)
                            .put(
                                "terminal", JSONObject()
                                    .put("status", status)
                                    .put("steps_taken", stepsTaken)
                                    .put("summary", summary.take(300))
                            )
                    )
                }
            } catch (_: Exception) { }
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
            // v37: failure wapas memory me (failures) — agli baar wahi
            // galti na dohrao.
            memoryNote(
                "failures", JSONObject()
                    .put("step", i).put("action", action)
                    .put("reason", msg.take(200))
            )
            // v24-refine: agla act() dobara-plan reason ke saath logged hoga.
            actReason = AiUsage.R_REPLAN_FAIL
            // v36 LADDER: pehli stuck → agent_plan se ai_single_step
            // (audit ke saath; user gate sirf STUCK_MAX par).
            if (stuckCount == 1 && ladderLevel == EscalationLadder.L_AGENT) {
                logStep(
                    i, "ladder", true, EscalationLadder.auditEntry(
                        ladderLevel, EscalationLadder.L_AI_STEP, action,
                        "step atak/fail — AI se single-step help", false
                    ).toString().take(300)
                )
                ladderLevel = EscalationLadder.L_AI_STEP
            }
            return stuckCount >= AgentActions.STUCK_MAX
        }

        /**
         * v36 LADDER ka aakhri level: user gate — audit ke saath
         * needs_user finish. Iske upar kuch nahi (user hi final authority).
         */
        fun finishUserGate(i: Int, action: String, msg: String): FormEngine.RunResult {
            logStep(
                i, "ladder", true, EscalationLadder.auditEntry(
                    ladderLevel, EscalationLadder.L_USER, action,
                    "agent/AI se nahi hua — aapki madad chahiye", false
                ).toString().take(300)
            )
            ladderLevel = EscalationLadder.L_USER
            return finish("needs_user", msg)
        }

        /**
         * v36: learned work-pattern ka EK failed step repair — SINGLE-STEP
         * AI help (ladder: ai_single_step). Poora re-plan NAHI, sirf is
         * step ke liye AI. @return true = repair hua, replay continue kare.
         */
        fun repairPatternStep(
            stepIdx: Int,
            failedType: String,
            failedSelector: String,
            pageUrl: String
        ): Boolean {
            val fromLevel = ladderLevel
            ladderLevel = EscalationLadder.L_AI_STEP
            logStep(
                stepIdx + 1, "ladder", true, EscalationLadder.auditEntry(
                    fromLevel, EscalationLadder.L_AI_STEP,
                    "$failedType($failedSelector)",
                    "pattern step fail — AI se sirf is step ki help", false
                ).toString().take(300)
            )
            return try {
                AiUsage.logAct(AiUsage.R_REPLAN_FAIL)
                val snap = try { engine.domSnapshot() } catch (_: Exception) { JSONObject() }
                val res = AgentApi.act(
                    ctx, JSONObject()
                        .put("goal", goal)
                        .put("url", pageUrl)
                        .put("page_title", snap.optString("title", ""))
                        .put(
                            "dom_snapshot", JSONObject()
                                .put("fields", snap.optJSONArray("fields") ?: JSONArray())
                                .put("buttons", snap.optJSONArray("buttons") ?: JSONArray())
                                .put("page_text", snap.optString("page_text", "").take(2000))
                        )
                        .put("ladder_level", EscalationLadder.L_AI_STEP)
                        .put(
                            "repair_step", JSONObject()
                                .put("index", stepIdx + 1)
                                .put("type", failedType)
                                .put("selector", failedSelector)
                        )
                        .put("stuck_count", 1)
                        .put("run_id", effectiveRunId)
                )
                // v36: per-work model/token ledger — har AI call ka model +
                // provider darj (server "usage" de to measured tokens bhi).
                if (res.code == 200) {
                    try {
                        AiUsage.logModelCall(
                            res.json?.optString("model", "") ?: "",
                            res.json?.optString("provider", "") ?: ""
                        )
                        val usage = res.json?.optJSONObject("usage")
                        if (usage != null) {
                            AiUsage.logMeasuredTokens(
                                usage.optLong("input_tokens", 0),
                                usage.optLong("output_tokens", 0)
                            )
                        }
                    } catch (_: Exception) { }
                }
                val stepJson = if (res.code == 200) res.json?.optJSONObject("step") else null
                if (stepJson == null) return false
                val stepMap = engine.jsonToMap(stepJson)
                val action = (stepMap["action"] as? String)?.trim().orEmpty()
                // v36 — LIVE ACTIVITY INDICATOR: har step se PEHLE chat me
                // transient label (thinking-indicator jaisa — message NAHI).
                try {
                    if (action.isNotEmpty() && action != "done" && action != "needs_user") {
                        LiveActivity.emitStep(agentRunId ?: effectiveRunId, action)
                    }
                } catch (_: Exception) { }
                // needs_user/done yahan repair nahi — caller ladder neeche jayega
                if (action.isEmpty() || action == "needs_user" || action == "done") return false
                val specMap = agentStepToSpec(stepMap)
                val detail = engine.runAgentStep(mapToJson(specMap))
                logStep(stepIdx + 1, "ai_single_step", true, "repair ok: $action")
                pushHistory(action, stepMap, "ok", "single-step repair: ${detail.toString().take(150)}")
                stepsTaken++
                true
            } catch (_: Exception) {
                false
            }
        }

        /**
         * v36: learned work-pattern replay — ZERO AI.
         * Har stored step deterministic execute (veto + timeout runAgentStep
         * ke andar). fill/select ka value aaj ke sources se (userProvided +
         * DetailStore) — value_src sirf KEY hai, value kabhi store nahi hui.
         * Ek step fail → repairPatternStep (single-step AI); repair bhi
         * fail → false (caller normal AI loop par jayega).
         *
         * v37: skipDone — run-memory resume: pehle N steps ho chuke hon to
         * wahin se continue (shuru se replay nahi).
         */
        fun replayWorkPattern(pageUrl: String, skipDone: Int = 0): Boolean {
            val steps = patternSteps ?: return false
            // Aaj ke value sources (OTP/password kabhi nahi — filtered)
            val valueSources = LinkedHashMap<String, String>()
            try {
                val uk = userProvided.keys()
                while (uk.hasNext()) {
                    val k = uk.next()
                    valueSources[k] = userProvided.optString(k, "")
                }
                valueSources.putAll(DetailStore.loadAll(ctx))
            } catch (_: Exception) { }
            val total = steps.length()
            var idx = skipDone.coerceIn(0, total)
            if (idx > 0) {
                logStep(
                    idx, "pattern_replay", true,
                    "resume: pehle $idx steps ho chuke — yahan se continue"
                )
            }
            while (idx < total) {
                val s = steps.optJSONObject(idx) ?: return false
                val type = s.optString("type", "")
                // v36 — LIVE ACTIVITY INDICATOR: replay step se pehle label.
                try {
                    if (type.isNotEmpty()) LiveActivity.emitStep(effectiveRunId, type)
                } catch (_: Exception) { }
                val sel = s.optJSONObject("selector") ?: JSONObject()
                val selValue = sel.optString("value", "")
                val spec = JSONObject()
                    .put("type", type)
                    .put(
                        "selector", JSONObject()
                            .put("mode", sel.optString("mode", "css").ifEmpty { "css" })
                            .put("value", selValue)
                    )
                if (type == "fill" || type == "select") {
                    val v = valueSources[s.optString("value_src", "")].orEmpty()
                    if (v.isEmpty()) {
                        logStep(idx + 1, "pattern_replay", false, "aaj ka value nahi mila (src)")
                        if (!repairPatternStep(idx, type, selValue, pageUrl)) return false
                        idx++
                        continue
                    }
                    if (type == "fill") spec.put("text", v) else spec.put("option", v)
                } else if (type == "goto") {
                    spec.put("url", selValue)
                }
                var stepOk = true
                try {
                    val d = engine.runAgentStep(spec)
                    if (type == "fill" && !d.optBoolean("verified", true)) {
                        logStep(idx + 1, "pattern_replay", false, "fill verify fail")
                        stepOk = false
                    } else {
                        logStep(idx + 1, "pattern_replay", true, d.toString().take(200))
                        pushHistory(
                            type,
                            mapOf(
                                "action" to type,
                                "selector" to mapOf("mode" to sel.optString("mode"), "value" to selValue)
                            ),
                            "ok", "pattern replay (zero AI)"
                        )
                        stepsTaken++
                    }
                } catch (e: FormEngine.VetoException) {
                    // Veto (payment/login-wall) → normal loop ka assisted flow sambhalega
                    logStep(idx + 1, "pattern_replay", false, "veto: ${(e.message ?: "").take(120)}")
                    return false
                } catch (_: Exception) {
                    logStep(idx + 1, "pattern_replay", false, "step fail")
                    stepOk = false
                }
                if (!stepOk) {
                    if (!repairPatternStep(idx, type, selValue, pageUrl)) return false
                }
                idx++
            }
            return true
        }

        // v24-refine (AI-training): site-memory — server ki seekhi hui
        // yaadein, run shuru me EK call (per-step nahi). Fail-heavy site →
        // brain ko history[0] me chetavni (contract change nahi — history
        // act() body me pehle se jati hai; ye wahi "site memory" mechanism
        // hai jo user ne manga tha).
        try {
            val mems = AgentApi.siteMemory(ctx)
            if (mems != null) {
                val host = SiteCredentialStore.domainOf(startUrl)
                for (i in 0 until mems.length()) {
                    val m = mems.optJSONObject(i) ?: continue
                    if (!m.optString("host").equals(host, true)) continue
                    val gt = m.optString("goal_type")
                    if (category.isNotEmpty() && gt.isNotEmpty() && !gt.equals(category, true)) continue
                    val sc = m.optInt("success_count")
                    val fc = m.optInt("fail_count")
                    if (fc > sc && fc >= 2) {
                        val le = m.optString("last_error").take(200)
                        history.add(
                            JSONObject().put("action", "site_memory")
                                .put("result", "warn")
                                .put("detail", "Is site par pichli baar $fc baar fail hua ($sc baar safal). Aakhri dikkat: $le. Extra dhyan do.")
                        )
                        logStep(0, "site_memory", true, "fail-heavy site: fail=$fc success=$sc")
                    }
                    break
                }
            }
        } catch (_: Exception) { }

        // v36 SMART COORDINATION — kaam shuru hone se PEHLE:
        // (1) learned work-pattern? → zero-AI replay (navigation ke baad)
        // (2) nahi → pre-flight plan (EK AI call, fixed prompt pack);
        //     uske baad agent plan khud follow karega.
        try {
            patternKey = LearnLogic.workPatternKey(goal, SiteCredentialStore.domainOf(startUrl))
        } catch (_: Exception) {
            patternKey = ""
        }
        try {
            val e = if (patternKey.isNotEmpty()) WorkPatternStore.find(ctx, patternKey) else null
            if (e != null && LearnLogic.shouldReplay(
                    e.optInt("success", 0), e.optInt("fail", 0),
                    e.optLong("updated_at", 0), System.currentTimeMillis()
                )
            ) {
                patternSteps = WorkPatternStore.stepsOf(e)
                patternEntryHash = e.optString("page_hash", "")
                AiUsage.logPatternHit(AiUsage.P_WORK_PATTERN)
                ladderLevel = EscalationLadder.L_PATTERN
                logStep(
                    0, "ladder", true,
                    "pattern: seekha hua tareeka mila — bina AI ke replay (${patternSteps?.length()} steps)"
                )
            }
        } catch (_: Exception) { }
        // v37 GLOBAL PLAYBOOK — local eligible pattern nahi → server ka
        // shared pattern (confidence > 0.5 ya status live/trial) → replay.
        // Nahi to null → AI path (preflight). Sirf network fail par skip.
        if (patternSteps == null) {
            try {
                val site = SiteCredentialStore.domainOf(startUrl)
                val gp = com.formmitra.app.agent.GlobalPlaybook.resolve(
                    ctx, goal, site, inferredState, inferredDistrict
                )
                if (gp != null) {
                    patternSteps = gp.steps
                    patternEntryHash = gp.pageHash
                    globalPatternId = gp.id
                    AiUsage.logPatternHit("global_playbook")
                    ladderLevel = EscalationLadder.L_PATTERN
                    logStep(
                        0, "ladder", true,
                        "global playbook: shared tareeka mila — bina AI ke replay (${gp.steps.length()} steps)"
                    )
                    memoryNote(
                        "ai_decisions", JSONObject()
                            .put("kind", "global_pattern")
                            .put("pattern_id", gp.id)
                            .put("steps", gp.steps.length())
                            .put("confidence", gp.confidence)
                    )
                }
            } catch (_: Exception) { }
        }
        if (patternSteps == null) {
            try {
                val kdObj = JSONObject()
                for ((k, v) in knownDetails) {
                    if (k.isNotEmpty() && v.isNotEmpty()) kdObj.put(k, "yes")
                }
                val pfRes = AgentApi.preflight(
                    ctx, JSONObject()
                        .put("goal", goal)
                        .put("known_details", kdObj)
                        .put("run_id", effectiveRunId)
                )
                // v36 refine point 5: pre-flight AI call bhi budget me gini jati hai.
                AiUsage.logPreflight()
                // v36 — LIVE ACTIVITY INDICATOR: plan banne se pehle label.
                try { LiveActivity.emitStep(effectiveRunId, "preflight_plan") }
                catch (_: Exception) { }
                try {
                    AiUsage.logModelCall(
                        pfRes.json?.optString("model", "") ?: "",
                        pfRes.json?.optString("provider", "") ?: ""
                    )
                } catch (_: Exception) { }
                val plan = PreflightPlan.parse(pfRes.json?.optJSONObject("plan"))
                if (plan != null) {
                    preflightPlan = plan
                    val summ = PreflightPlan.hinglishSummary(plan)
                    logStep(0, "preflight_plan", true, summ.take(300))
                    try {
                        onPlan(summ)
                    } catch (_: Exception) { }
                    planMissingDetails = run {
                        // v37 AI MIND — pata hai to dobara mat poochho:
                        // knownDetails + device DetailStore + user-memory
                        // facts. Sirf GENUINELY missing keys hi missing.
                        val knownKeys = LinkedHashSet<String>()
                        knownKeys.addAll(knownDetails.keys)
                        try {
                            knownKeys.addAll(
                                com.formmitra.app.agent.DetailStore.loadAll(ctx).keys
                            )
                        } catch (_: Exception) { }
                        knownKeys.addAll(userFacts.keys)
                        val out = plan.missingDetails
                            .filter { it !in knownKeys }.toMutableList()
                        // v37: playbook key ke liye state/district chahiye —
                        // genuinely missing ho to EK baar poochho (batch
                        // mechanism — dobara-dobara sawal nahi).
                        if (inferredState.isEmpty() && "state" !in knownKeys && "state" !in out) {
                            out.add("state")
                        }
                        if (inferredDistrict.isEmpty() && "district" !in knownKeys && "district" !in out) {
                            out.add("district")
                        }
                        out
                    }
                    // v37: preflight AI call ka outcome memory me (ai_decisions).
                    memoryNote(
                        "ai_decisions", JSONObject()
                            .put("kind", "preflight_plan")
                            .put("steps", plan.steps.size)
                            .put("gates", plan.gates.map { it.kind }.joinToString(",").take(100))
                            .put("missing", planMissingDetails.joinToString(",").take(200))
                    )
                } else if (forceStandalone && Standalone.isConfigured(ctx)) {
                    // Standalone/offline task: server by design unreachable —
                    // preflight ho hi nahi sakta; step-by-step standalone
                    // brain se aage badho (ye fallback jaan-boojhkar hai).
                    logStep(0, "preflight_plan", false, "standalone mode — server preflight skip, step-by-step")
                } else {
                    // v36 user order: preflight parse fail = ASLI failure +
                    // Catcher me ASLI wajah (HTTP code + server jawab ka
                    // khulasa — chup-chaap "purane step-by-step mode" nahi).
                    try {
                        val diag = StringBuilder("preflight parse fail")
                            .append("; http=").append(pfRes.code)
                        val rawPlan = pfRes.json?.opt("plan")
                        diag.append("; plan field=").append(
                            if (rawPlan == null) "missing"
                            else rawPlan.javaClass.simpleName
                        )
                        val srvErr = pfRes.json?.optString("error", "")
                        if (!srvErr.isNullOrEmpty()) {
                            diag.append("; server error=").append(srvErr.take(300))
                        }
                        ErrorCatcher.report(
                            ctx, "Pre-flight plan",
                            RuntimeException(diag.toString()),
                            goal, effectiveRunId
                        )
                    } catch (_: Exception) { }
                    return finish(
                        "failed",
                        "Pre-flight plan nahi ban paya — kaam shuru nahi hua. Dobara try karo."
                    )
                }
            } catch (t: Throwable) {
                if (forceStandalone && Standalone.isConfigured(ctx)) {
                    logStep(0, "preflight_plan", false, "standalone mode — preflight skip")
                } else {
                    // v36 user order: preflight call fail = ASLI failure +
                    // Catcher me asli wajah (poori exception chain).
                    // (server chahiye tha, mila nahi — chup fallback nahi).
                    try {
                        ErrorCatcher.report(
                            ctx, "Pre-flight plan", t, goal, effectiveRunId
                        )
                    } catch (_: Exception) { }
                    return finish(
                        "failed",
                        "Pre-flight call fail ho gayi — kaam shuru nahi hua. Dobara try karo."
                    )
                }
            }
            ladderLevel = EscalationLadder.L_AGENT
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
                        // v37: payment gate memory me (gates).
                        memoryNote(
                            "gates", JSONObject()
                                .put("kind", "payment")
                                .put("at_step", 0)
                                .put("result", payRes.take(40))
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
                // v36 refine point 3: start page ka structure hash — done par
                // pattern ke saath save hoga (agli baar replay se pehle check).
                if (startPageHash.isEmpty()) {
                    try {
                        val h = com.formmitra.app.agent.PageStructureHash.ofSnapshot(
                            startUrl, engine.domSnapshot()
                        )
                        if (h.isNotEmpty()) startPageHash = h
                    } catch (_: Exception) { }
                }
            }

            // v36: learned work-pattern replay — ZERO AI. Sab steps safal →
            // done (pattern aur pakka hota hai). Ek bhi fail → ladder neeche
            // (agent_plan: normal AI loop khud sambhalega).
            // v36 refine point 3: replay se PEHLE page-structure hash check —
            // site badal gayi ho to 2-3 wasted replay attempts nahi, seedha
            // AI plan par. Sirf pattern ho tabhi ek local domSnapshot (AI call nahi).
            if (patternSteps != null && patternKey.isNotEmpty() && patternEntryHash.isNotEmpty()) {
                val curHash = try {
                    com.formmitra.app.agent.PageStructureHash.ofSnapshot(startUrl, engine.domSnapshot())
                } catch (_: Exception) { "" }
                if (curHash.isNotEmpty()) startPageHash = curHash
                if (curHash.isNotEmpty() && curHash != patternEntryHash) {
                    logStep(
                        0, "ladder", true, EscalationLadder.auditEntry(
                            EscalationLadder.L_PATTERN, EscalationLadder.L_AGENT,
                            "", "page structure badal gayi (site update?) — purana tareeka skip, AI plan banayega",
                            false
                        ).toString().take(300)
                    )
                    ladderLevel = EscalationLadder.L_AGENT
                    patternSteps = null
                }
            }
            if (patternSteps != null && patternKey.isNotEmpty()) {
                // v37: memory-resume — pehle ho chuke steps skip.
                val replayOk = try {
                    replayWorkPattern(startUrl, memoryResumeSteps)
                } catch (_: Exception) {
                    false
                }
                if (replayOk) {
                    try {
                        WorkPatternStore.recordSuccess(ctx, patternKey)
                    } catch (_: Exception) { }
                    try {
                        onProgress(maxSteps)
                    } catch (_: Exception) { }
                    return finish(
                        "done",
                        "Pichli baar wala tareeka kaam kar gaya — bina AI ke poora ho gaya ✅ " +
                            "(${(patternSteps?.length() ?: 0)} steps, 0 AI calls)"
                    )
                }
                val stillValid = try {
                    WorkPatternStore.recordFail(ctx, patternKey)
                } catch (_: Exception) {
                    false
                }
                logStep(
                    0, "ladder", true, EscalationLadder.auditEntry(
                        EscalationLadder.L_PATTERN, EscalationLadder.L_AGENT,
                        "", "pattern replay fail — agent khud plan follow karega",
                        !stillValid
                    ).toString().take(300)
                )
                ladderLevel = EscalationLadder.L_AGENT
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
                if (shot.isNotEmpty()) {
                    mirrorShot()
                    // POINT 21: proof screenshot ginao (summary card).
                    try { onProof() } catch (_: Exception) { }
                }
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
                //
                // POINT 20: mid-run corrections — user ne kaha koi field
                // galat hai + sahi value di. userProvided me dalo (sensitive
                // filtering automatic) + history me note taaki brain SIRF
                // ye field update kare — baaki same run continue (STOP nahi).
                try {
                    val cors = com.formmitra.app.agent.CorrectionStore
                        .take(ctx, runId)
                    for (c in cors) {
                        userProvided.put(c.field, c.value)
                        if (com.formmitra.app.engine.CorrectionPolicy
                                .isSensitive(c.field)
                        ) sensitiveKeys.add(c.field)
                        pushHistory(
                            "user_correction",
                            mapOf("field" to c.field, "label" to c.label),
                            "ok",
                            "User ne kaha '${c.label}' galat hai — sahi " +
                                "value user_provided me hai. SIRF ye field " +
                                "update karo, baaki same run continue rakho."
                        )
                        logStep(i, "user_correction", false, c.label.take(80))
                    }
                } catch (_: Exception) { }
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
                    // v29 (P1-APP): is work me pehle se mili details — brain
                    // inhe "already known" maane, dobara na maange.
                    // (khali ho to field nahi bhejte)
                    .apply {
                        if (knownDetails.isNotEmpty()) {
                            val kd = JSONObject()
                            for ((k, v) in knownDetails) {
                                if (k.isNotEmpty() && v.isNotEmpty()) kd.put(k, v)
                            }
                            if (kd.length() > 0) put("known_details", kd)
                        }
                        if (askedAlready.isNotEmpty()) {
                            put("asked_already", JSONArray(askedAlready.filter { it.isNotEmpty() }))
                        }
                    }
                    // OTP/password yahan se filtered — AI/server ko kabhi nahi jate
                    .put("user_provided", filteredUserProvided(userProvided, sensitiveKeys))
                    // v37 AI MIND — har act() call me memory summary (read):
                    // brain ko yaad rahe kya ho chuka, kaun se gates aaye.
                    .apply {
                        val ms = try { RunMemory.summary(runMemory) } catch (_: Exception) { "" }
                        if (ms.isNotEmpty()) put("memory_summary", ms)
                    }
                    // v36: pre-flight plan context — SIRF pehle act() call me
                    // (brain plan ke hisaab se chale; VALUES nahi bhejte
                    // taaki AI invent na kare).
                    .apply {
                        val pf = preflightPlan
                        if (!planContextSent && pf != null) {
                            put("preflight_steps", PreflightPlan.stepsContextJson(pf))
                            if (planMissingDetails.isNotEmpty()) {
                                put("plan_missing", JSONArray(planMissingDetails))
                            }
                            planContextSent = true
                        }
                    }
                // forceStandalone (offline task): server ko chhodo, seedha user ki
                // Groq key se StandaloneBrain. Server unreachable fallback neeche
                // (per-step) waise bhi hai; ye poore run ka standalone mode hai.
                // v24-refine (quota discipline): har AI call ka reason logged —
                // debugging me pata chale call KYUN hua (nayi/stuck/complex).
                AiUsage.logAct(actReason)
                // v36 user order (2026-09-26): TOKEN CAP HATAYA — token kharch
                // par koi restriction/throttling/pause/block NAHI. Kaam kabhi
                // budget ki wajah se nahi rukega.
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
                // v36: per-work model/token ledger — har AI call ka model +
                // provider darj (server "usage" de to measured tokens bhi).
                if (res.code == 200) {
                    try {
                        AiUsage.logModelCall(
                            res.json?.optString("model", "") ?: "",
                            res.json?.optString("provider", "") ?: ""
                        )
                        val usage = res.json?.optJSONObject("usage")
                        if (usage != null) {
                            AiUsage.logMeasuredTokens(
                                usage.optLong("input_tokens", 0),
                                usage.optLong("output_tokens", 0)
                            )
                        }
                    } catch (_: Exception) { }
                }
                val stepJson = if (res.code == 200) res.json?.optJSONObject("step") else null
                if (stepJson == null) {
                    pushHistory("act", emptyMap(), "error", "server code=${res.code}")
                    if (noteError(i, "act", "server se step nahi mila (code=${res.code})")) {
                        return finishUserGate(i, "act",
                            "Jawab nahi mil raha — atak gaya hoon, aap dekh lein"
                        )
                    }
                    continue
                }
                val stepMap = engine.jsonToMap(stepJson)
                val action = (stepMap["action"] as? String)?.trim() ?: ""
                // v36 — LIVE ACTIVITY INDICATOR: har step se PEHLE chat me
                // transient label (thinking-indicator jaisa — message NAHI).
                try {
                    if (action.isNotEmpty() && action != "done" && action != "needs_user") {
                        LiveActivity.emitStep(agentRunId ?: effectiveRunId, action)
                    }
                } catch (_: Exception) { }
                // CONTRACT SYNC (2026-09-26): act response ka top-level
                // work_summary (running summary) — done step me fallback.

                // (e) terminal actions — execute nahi hote
                when (action) {
                    "done" -> {
                        // CONTRACT SYNC (2026-09-26): act route response ka
                        // field "work_summary" hai; purana "result_summary"
                        // fallback ke liye rakha hai.
                        val respSummary =
                            res.json?.optString("work_summary", "").orEmpty()
                        val summary = ((stepMap["work_summary"] as? String)?.ifEmpty { null }
                            ?: respSummary.ifEmpty { null }
                            ?: (stepMap["result_summary"] as? String)?.ifEmpty { null }
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
                        // Point 14: kind="input" ab NON-BLOCKING batch hai —
                        // handleServerPrompt batch utha ke false deta hai.
                        val promptObj = stepMap["user_prompt"] as? Map<String, Any?>
                        if (promptObj != null) {
                            val runIdNow = agentRunId ?: effectiveRunId
                            // v34: 0 = continue, 1 = needs_user, 2 = OTP parked
                            // (non-blocking — queue aage badhegi, jawab par auto-resume).
                            val pr = handleServerPrompt(
                                ctx, engine, promptObj,
                                runIdNow, userProvided, sensitiveKeys, history
                            )
                            // v37: user gate (otp/login/payment/device_auth/
                            // destructive) memory me (gates) — kya manga gaya.
                            if (pr != 0) {
                                memoryNote(
                                    "gates", JSONObject()
                                        .put("kind", (promptObj["kind"] as? String).orEmpty())
                                        .put("at_step", i)
                                )
                            }
                            if (pr == 2) {
                                val site = OtpPark.parkedSite(ctx).ifEmpty { "site" }
                                return finish(
                                    "needs_user",
                                    "[otp_parked] OTP ka intezaar hai ($site) — SMS aate hi apne aap bhar jayega, " +
                                        "ya app me bhar do. Baaki kaam chalta rahega; jawab milte hi ye kaam " +
                                        "apne aap aage badhega."
                                )
                            }
                            if (pr != 0) {
                                // Batch utha? (non-blocking) → park message.
                                // Nahi → purana timeout/decline message.
                                val batched = try {
                                    com.formmitra.app.agent.DetailBatchStore
                                        .get(ctx, runIdNow) != null
                                } catch (_: Exception) { false }
                                val pkind = (promptObj["kind"] as? String).orEmpty()
                                return finish(
                                    "needs_user",
                                    when {
                                        batched -> "[details_batch] Kuch details chahiye — app me bhar dein, kaam apne aap aage badhega"
                                        pkind == "otp" -> "OTP verify nahi ho saka — galat/expire OTP ya jawab nahi mila. " +
                                            "App khol ke sahi OTP do, kaam wahin se aage badhega."
                                        else -> "Aapka jawab nahi mila / mana kiya — kaam ruka hai, app khol ke dekhein"
                                    }
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
                            ?: "Rok diya gaya — payment/safety")
                    )
                    // CONTRACT SYNC (2026-09-26): correct_field — server ka
                    // mid-run correction step (web/another device se aaya
                    // correction). CorrectionStore me dalo; agle act() me
                    // userProvided ke through apply hoga (sensitive
                    // filtering automatic). Execute nahi hota — continue.
                    "correct_field" -> {
                        val field = (stepMap["field"] as? String)
                            .orEmpty().trim()
                        val value = (stepMap["value"] as? String).orEmpty()
                        val label = (stepMap["label"] as? String)
                            .orEmpty().ifEmpty { field }
                        if (field.isNotEmpty()) {
                            try {
                                com.formmitra.app.agent.CorrectionStore.add(
                                    ctx, runId, field, label, value
                                )
                                if (com.formmitra.app.engine.CorrectionPolicy
                                        .isSensitive(field)
                                ) sensitiveKeys.add(field)
                                userProvided.put(field, value)
                            } catch (_: Exception) { }
                            pushHistory(
                                "correct_field",
                                mapOf("field" to field, "label" to label),
                                "ok",
                                "Server se correction aaya — '$label' update " +
                                    "karke same run continue."
                            )
                            logStep(i, "correct_field", false, label.take(80))
                        } else {
                            pushHistory(
                                "correct_field", stepMap, "error",
                                "field khaali — ignore"
                            )
                        }
                        consecErrors = 0
                        continue
                    }
                    // Point 14 (SMART DETAIL COLLECTION): server ka compact
                    // batch — ek saath saari details (one-by-one drip nahi).
                    // NON-BLOCKING: batch uthao, run park karo (resume state
                    // rakho), thread ko wait mat karwao. User jawab de to
                    // WakeWorker usi step se resume karega (DetailStore me
                    // jawab milenge → auto-fill → loop aage badhega).
                    // NOTE: contract sync me "details_needed" whitelist me
                    // aayega (BRAIN_ALLOWED_ACTIONS) — tab tak ye handler
                    // validation se pehle chalta hai (terminal actions ki
                    // tarah), taaki server bheje to kaam kare.
                    "details_needed" -> {
                        val fields = DetailBatchLogic.validateStep(stepMap)
                        if (fields == null) {
                            // Galat batch — purane flow par raho
                            pushHistory(
                                "details_needed", stepMap, "error",
                                "khali/galat batch — ignore"
                            )
                            continue
                        }
                        val runIdNow = agentRunId ?: effectiveRunId
                        val handled = handleDetailsNeeded(
                            ctx, engine, fields, runIdNow, goal,
                            userProvided, history, extraKnown = userFacts
                        )
                        if (!handled) {
                            // v37: detail gate memory me (gates) — kya manga gaya.
                            memoryNote(
                                "gates", JSONObject()
                                    .put("kind", "details_needed")
                                    .put("fields", fields.map { it.key }.joinToString(",").take(200))
                            )
                            // Batch uth gaya — run park (resume state rakha).
                            // UI ko batane ke liye summary me marker.
                            return finish(
                                "needs_user",
                                "[details_batch] Kuch details chahiye — app me bhar dein, kaam apne aap aage badhega"
                            )
                        }
                        // v37: auto-fill hua (user-memory/device se) — evidence.
                        memoryNote(
                            "evidence", JSONObject()
                                .put("kind", "details_autofill")
                                .put("fields", fields.map { it.key }.joinToString(",").take(200))
                        )
                        consecErrors = 0
                        continue
                    }
                }

                // (f) client-side validate
                val vErr = validateAgentStep(stepMap)
                if (vErr != null) {
                    pushHistory(action, stepMap, "error", "invalid: $vErr")
                    if (noteError(i, action, "invalid step: $vErr")) {
                        return finishUserGate(i, action,
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
                        var msg = "Ek hi jagah ghoom raha hoon — phas gaya, aap dekh lein"
                        // v24-refine: operator ne jo dekha, agent ko sahi
                        // shabdon me — AI diagnosis bhi user tak.
                        if (lastDiagnosis.isNotEmpty()) {
                            msg += " (AI ki raay: ${lastDiagnosis.first().take(150)})"
                        }
                        return finish("needs_user", msg)
                    }
                    // v24-refine (AI-training): ataki situation → AI
                    // escalation — /api/agent/verify se diagnosis (EK call
                    // per stuck episode, har step par nahi — quota discipline).
                    if (!stuckDiagnosed) {
                        stuckDiagnosed = true
                        val dshot = try { engine.capturePngBase64() }
                        catch (_: Exception) { "" }
                        lastDiagnosis = AiTrainer.diagnoseStuck(
                            ctx, goal, dshot,
                            "ek hi action 3 baar ho chuka hai ($sig)"
                        )
                        if (lastDiagnosis.isNotEmpty()) {
                            val d = lastDiagnosis.joinToString(" | ").take(300)
                            pushHistory(
                                "ai_diagnosis",
                                mapOf("action" to "ai_diagnosis"),
                                "stuck-help", d
                            )
                            logStep(i, "ai_diagnosis", true, d.take(200))
                            FlowAnnouncer.say(
                                ctx,
                                "Ruk gaya tha — AI se nayi raay li, dobara koshish karta hun"
                            )
                        } else {
                            logStep(i, "ai_diagnosis", false, "diagnosis nahi mili — seedha replan")
                        }
                    }
                    actReason = AiUsage.R_STUCK_RETRY
                    continue
                }

                // (g2) v24-refine (AI-training): execute se PEHLE local
                // sanity — AI ka diya target page par abhi zinda hai?
                // (koi AI call nahi — quota bachat.) Stale → blind execute
                // NAHI; wajah history me → agli act() dobara plan karegi.
                val sanityErr = AiTrainer.preExecuteSanity(action, stepMap, engine)
                if (sanityErr != null) {
                    pushHistory(action, stepMap, "stale_target", sanityErr.take(300))
                    logStep(i, action, false, sanityErr.take(200))
                    recentSigs.add(sig)
                    if (recentSigs.size > AgentActions.STUCK_REPEATS) recentSigs.removeAt(0)
                    actReason = AiUsage.R_REPLAN_STALE
                    continue
                }

                // (g3) v24-refine (AI-training): field-mapping check — seekha
                // hua pattern (domain|selector → source) match ho aur value
                // consistent ho → local OK (AI call nahi). Drift dikhe →
                // brain ko mapping-note (history) → AI khud correct karega.
                if (action == "fill") {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val fsel = stepMap["selector"] as? Map<String, Any?>
                        val fmode = ((fsel?.get("mode") as? String)?.ifEmpty { "css" }) ?: "css"
                        val fval = (fsel?.get("value") as? String).orEmpty()
                        val curVal = (stepMap["value"] as? String).orEmpty()
                        if (fval.isNotEmpty() && curVal.isNotEmpty()) {
                            val host = SiteCredentialStore.domainOf(currentUrl)
                            val learnedSrc = FieldMapMemory.get(
                                ctx, FieldMapMemory.key(host, fmode, fval)
                            )
                            if (learnedSrc != null) {
                                val srcVal = userProvided.optString(learnedSrc, "").ifEmpty {
                                    try { DetailStore.findValue(ctx, learnedSrc) }
                                    catch (_: Exception) { "" }
                                }
                                val note = AiTrainer.mappingNote(learnedSrc, curVal, srcVal)
                                if (note != null) {
                                    pushHistory(action, stepMap, "mapping_note", note.take(300))
                                    logStep(i, "fieldmap", true, "mapping drift — brain ko bataya")
                                } else {
                                    AiUsage.logPatternHit(AiUsage.P_FIELDMAP)
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }

                // (h) execute (veto + timeout runAgentStep ke andar)
                // (g2) v24 C15 + AI-training: FINAL SUBMIT se pehle AI
                // image-verification (complex judgment → AI call, reason logged).
                // Submit intent: server ka explicit "verify_submit" action, ya
                // click/press jisme strong final-submit hint ho (SubmitIntent).
                // FAIL CLOSED: har non-explicit-approval par submit NAHI —
                // 404, malformed, HTTP error, network error sab par.
                // Request contract: {image_base64, checklist[]} →
                // Response: {ok, issues[]}.
                if (AgentActions.SubmitIntent.shouldVerify(stepMap)) {
                    val vshot = try { engine.capturePngBase64() }
                    catch (_: Exception) { "" }
                    AiUsage.logVerify(AiUsage.R_FINAL_SUBMIT)
                    val checklist = listOf(
                        "form ke saare zaroori fields bhare hue dikh rahe hain, koi khaali nahi",
                        "koi laal error ya warning message nahi dikh raha",
                        "yeh '${goal.take(100)}' ka final submit hai — sab taiyaar hai"
                    )
                    val vres = try {
                        AgentApi.verifySubmit(ctx, downscaleShot(vshot), checklist)
                    } catch (_: Exception) { AgentApi.ApiResult(-1, null) }
                    val vnote: String
                    val vok: Boolean
                    when {
                        vres.code !in 200..299 -> {
                            // FAIL CLOSED: 404 (endpoint nahi), 400/5xx,
                            // network fail — har non-approval par submit NAHI.
                            vnote = "verify nahi ho payi (code=${vres.code}) — " +
                                "FAIL CLOSED, submit nahi kiya"
                            vok = false
                        }
                        else -> {
                            val ok = vres.json?.optBoolean("ok", false) == true
                            val issues = try {
                                val arr = vres.json?.optJSONArray("issues")
                                (0 until (arr?.length() ?: 0))
                                    .mapNotNull {
                                        arr?.optString(it, "")?.trim()
                                            ?.takeIf { s -> s.isNotEmpty() }
                                    }.take(5).joinToString(" | ").take(300)
                            } catch (_: Exception) { "" }
                            vnote = if (ok) "AI: sab theek"
                            else "AI issues: ${issues.ifEmpty { "ok=false, wajah nahi mili" }}"
                            vok = ok
                        }
                    }
                    logStep(i, "verify_submit", vok, vnote.take(200))
                    pushHistory(action, stepMap, if (vok) "ok" else "rejected", vnote.take(300))
                    // v37: AI verify call ka outcome memory me (evidence).
                    memoryNote(
                        "evidence", JSONObject()
                            .put("kind", "verify_submit")
                            .put("step", i).put("ok", vok)
                            .put("note", vnote.take(150))
                    )
                    if (!vok) {
                        return finish(
                            "needs_user",
                            "Submit se pehle AI verification pass nahi hui — " +
                                "maine submit NAHI kiya. Aap dekh lein."
                        )
                    }
                }
                try {
                    val specMap = agentStepToSpec(stepMap)
                    val detail = engine.runAgentStep(mapToJson(specMap))
                    // fill ka read-back verify fail ho to error ki tarah gino
                    if (action == "fill" && !detail.optBoolean("verified", true)) {
                        val msg = "fill verify fail: expected='${
                            detail.optString("expected", "").take(40)
                        }' actual='${detail.optString("value", "").take(40)}'"
                        // v24-refine (AI-training): mapping problem ho sakta
                        // hai — brain ko DOBARA MAP karne ko kaho; wahi
                        // selector blind mat dohrao (stuck-check pakdega).
                        pushHistory(
                            action, stepMap, "mapping_mismatch",
                            "$msg — card-field → page-field mapping galat lag " +
                                "rahi hai; selector/field dobara map karo, " +
                                "wahi selector blind mat dohrao"
                        )
                        if (noteError(i, action, msg)) {
                        return finishUserGate(i, action,
                            "Fill verify baar-baar fail ho raha hai — phas gaya hoon, aap dekh lein"
                        )
                        }
                        continue
                    }
                    consecErrors = 0
                    stepsTaken++
                    // v36: verified step → work-pattern ke liye collect.
                    // Sirf source KEY (card/user/detail ka naam) — personal
                    // VALUE kabhi nahi. goto ka URL selector me (mode=url).
                    try {
                        val stype = (specMap["type"] as? String).orEmpty()
                        val sel = specMap["selector"] as? Map<String, Any?>
                        var smode = ((sel?.get("mode") as? String)?.ifEmpty { "css" }) ?: "css"
                        var sval = (sel?.get("value") as? String).orEmpty()
                        if (stype == "goto") {
                            smode = "url"
                            sval = (specMap["url"] as? String).orEmpty()
                        }
                        var vsrc = ""
                        if (stype == "fill") {
                            val filledVal = (specMap["text"] as? String).orEmpty()
                            val sources = LinkedHashMap<String, String>()
                            val uk2 = userProvided.keys()
                            while (uk2.hasNext()) {
                                val k = uk2.next()
                                sources[k] = userProvided.optString(k, "")
                            }
                            try {
                                sources.putAll(DetailStore.loadAll(ctx))
                            } catch (_: Exception) { }
                            vsrc = LearnLogic.attributeSource(filledVal, sources) ?: ""
                        }
                        if (stype.isNotEmpty() && sval.isNotEmpty()) {
                            patternStepsCollected.put(
                                JSONObject()
                                    .put("type", stype)
                                    .put(
                                        "selector", JSONObject()
                                            .put("mode", smode)
                                            .put("value", sval)
                                    )
                                    .put("value_src", vsrc)
                            )
                        }
                    } catch (_: Exception) { }
                    // v24-refine: safal step → agla act() nayi situation;
                    // stuck-episode khatm (naya diagnosis episode shuru hoga).
                    actReason = AiUsage.R_NEW_STEP
                    stuckDiagnosed = false
                    lastDiagnosis = emptyList()
                    val dStr = detail.toString().take(300)
                    logStep(i, action, true, dStr)
                    pushHistory(action, stepMap, "ok", dStr)
                    // v37: safal act-step ka outcome memory me (ai_decisions).
                    memoryNote(
                        "ai_decisions", JSONObject()
                            .put("kind", "act_step")
                            .put("step", i).put("action", action)
                            .put("ok", true).put("detail", dStr.take(150))
                    )
                    // v24-refine (AI-training): fill VERIFIED → seekho:
                    // (domain|selector) → source key. Agli baar wahi
                    // situation me pattern match → consistency local
                    // (AI call nahi); drift par brain ko mapping-note
                    // (history) → AI khud correct karega. VALUE kabhi
                    // save nahi — sirf source KEY.
                    if (action == "fill") {
                        try {
                            val filledVal = (stepMap["value"] as? String).orEmpty()
                            @Suppress("UNCHECKED_CAST")
                            val sel = stepMap["selector"] as? Map<String, Any?>
                            val smode = ((sel?.get("mode") as? String)?.ifEmpty { "css" }) ?: "css"
                            val sval = (sel?.get("value") as? String).orEmpty()
                            if (filledVal.isNotEmpty() && sval.isNotEmpty()) {
                                val sources = LinkedHashMap<String, String>()
                                val uk = userProvided.keys()
                                while (uk.hasNext()) {
                                    val k = uk.next()
                                    sources[k] = userProvided.optString(k, "")
                                }
                                try { sources.putAll(DetailStore.loadAll(ctx)) } catch (_: Exception) { }
                                val src = LearnLogic.attributeSource(filledVal, sources)
                                if (src != null) {
                                    val host = SiteCredentialStore.domainOf(currentUrl)
                                    FieldMapMemory.save(
                                        ctx,
                                        FieldMapMemory.key(host, smode, sval),
                                        src
                                    )
                                    logStep(i, "fieldmap", true, "seekha: field → '$src'")
                                }
                            }
                        } catch (_: Exception) { }
                    }
                    // G2: har successful step par progress persist — kill/reboot
                    // par WakeWorker usi step se resume karega.
                    try {
                        AgentResume.updateProgress(ctx, stepsTaken, dStr)
                    } catch (_: Exception) { }
                    // v36 refine point 2: step safal → per-step fail count reset
                    // (lagatar fail hi stuck hai).
                    stepFailCounts.clear()
                } catch (e: FormEngine.VetoException) {
                    logStep(i, action, false, "VETO: ${e.message}")
                    // v37 AI MIND — payment gate: READ — memory me pichla
                    // payment gate tha? (dobara gate par bhi prompt HOGA —
                    // safety kabhi skip nahi; sirf note me darj hota hai.)
                    val prevPayGate = try {
                        val gates = runMemory?.optJSONArray("gates")
                        (0 until (gates?.length() ?: 0)).any {
                            gates?.optJSONObject(it)?.optString("kind") == "payment"
                        }
                    } catch (_: Exception) {
                        false
                    }
                    // v37 AI MIND — payment gate memory me (gates). WRITE.
                    memoryNote(
                        "gates", JSONObject()
                            .put("kind", "payment")
                            .put("at_step", i)
                            .put("url", url.take(150))
                            .put("repeat_gate", prevPayGate)
                    )
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
                    // v36 refine point 2: per-STEP stuck detection — EK step
                    // 2-3 baar fail → AI single-step help → phir bhi fail →
                    // user ko saaf batao. Infinite retry loop KABHI nahi
                    // (maxSteps + STUCK_MAX backstop alag se hain).
                    val stepSig = try {
                        val sel = stepMap["selector"] as? Map<*, *>
                        "$action|${(sel?.get("value") as? String).orEmpty()}"
                    } catch (_: Exception) { "$action|" }
                    val stepFails = (stepFailCounts[stepSig] ?: 0) + 1
                    stepFailCounts[stepSig] = stepFails
                    when (com.formmitra.app.agent.LearnLogic.stepEscalation(stepFails)) {
                        com.formmitra.app.agent.LearnLogic.STEP_USER_GATE -> {
                            return finishUserGate(
                                i, action,
                                "Ye step 3 baar fail ho gaya — aage badhne ke liye aapki madad chahiye"
                            )
                        }
                        com.formmitra.app.agent.LearnLogic.STEP_AI_HELP -> {
                            logStep(
                                i, "ladder", true, EscalationLadder.auditEntry(
                                    ladderLevel, EscalationLadder.L_AI_STEP, action,
                                    "same step 2 baar fail — AI se single-step help", false
                                ).toString().take(300)
                            )
                            ladderLevel = EscalationLadder.L_AI_STEP
                            actReason = AiUsage.R_STUCK_RETRY
                        }
                    }
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
                        return finishUserGate(i, action,
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
    // Point 14 — SMART DETAIL COLLECTION (non-blocking).
    //
    // Server "details_needed" ka compact batch bhejta hai (ek saath, drip
    // nahi). Ye handler:
    //  1. Pehle DetailStore/userProvided me dekhta hai — sab mile to turant
    //     apply (koi sawal nahi, loop aage badhta hai).
    //  2. Nahi mile to EKI batch uthata hai (DetailBatchStore — persisted,
    //     crash-safe) + notification + chat card listener → user jab chahe
    //     jawab de (koi blocking dialog nahi, koi 10-min timeout nahi).
    //  3. Batch uthne par FALSE deta hai → caller run park karta hai
    //     (AgentResume me step saved). Jawab aane par WakeWorker usi step
    //     se resume karega — tab (1) me sab mil jayega.
    //
    // true = sab details mil gayin (loop continue kare);
    // false = batch uth gaya, run park karo.
    // =====================================================================

    private fun handleDetailsNeeded(
        ctx: Context,
        engine: FormEngine,
        fields: List<DetailBatchLogic.Field>,
        runId: String,
        taskName: String,
        userProvided: JSONObject,
        history: ArrayList<JSONObject>,
        /**
         * v37: extra known values (user-memory facts) — pata hai to dobara
         * mat poochho. Default khaali (purane callers unchanged).
         */
        extraKnown: Map<String, String> = emptyMap()
    ): Boolean {
        // (1) Kya sab kuch pehle se pata hai? (userProvided + DetailStore +
        //     v37 user-memory facts)
        val known = LinkedHashMap<String, String>()
        try {
            val ks = userProvided.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                val v = userProvided.optString(k, "")
                if (v.isNotEmpty()) known[k] = v
            }
        } catch (_: Exception) { }
        // DetailStore (device-local saved details) bhi dekho
        try {
            val stored = DetailStore.loadAll(ctx)
            for ((k, v) in stored) {
                if (v.isNotEmpty() && !known.containsKey(k)) known[k] = v
            }
        } catch (_: Exception) { }
        // v37: user-memory facts (private, sirf apna) — pata hai to sawal nahi
        for ((k, v) in extraKnown) {
            if (v.isNotEmpty() && !known.containsKey(k)) known[k] = v
        }
        val missing = DetailBatchLogic.missingFields(fields, known)
        if (missing.isEmpty()) {
            // Sab mil gaya — userProvided me dalo (agle act() me jayega)
            for (f in fields) {
                val v = known[f.key].orEmpty()
                if (v.isNotEmpty()) {
                    try { userProvided.put(f.key, v) } catch (_: Exception) { }
                }
            }
            history.add(
                JSONObject().put("action", "details_autofill")
                    .put("result", "ok")
                    .put("detail", "batch auto-fill: ${fields.map { it.key }}".take(200))
            )
            try {
                AiUsage.logPatternHit(AiUsage.P_DETAIL)
            } catch (_: Exception) { }
            return true
        }
        // (2) Batch uthao — persisted + notification + chat card.
        // Sirf missing fields puchho (pata wale dobara nahi).
        raiseDetailBatch(ctx, runId, taskName, missing, history)
        try {
            FlowAnnouncer.say(
                ctx, "Kuch details chahiye — app khol ke bhar do, kaam apne aap aage badhega."
            )
        } catch (_: Exception) { }
        return false
    }

    /**
     * Shared batch-raiser (handleDetailsNeeded + handleServerPrompt/input
     * dono use karte hain): batch persist + history log + compact
     * notification. Koi thread block nahi hota.
     */
    private fun raiseDetailBatch(
        ctx: Context,
        runId: String,
        taskName: String,
        fields: List<DetailBatchLogic.Field>,
        history: ArrayList<JSONObject>
    ) {
        try {
            com.formmitra.app.agent.DetailBatchStore.raise(
                ctx, runId, taskName, fields
            )
        } catch (_: Exception) { }
        history.add(
            JSONObject().put("action", "detail_asked")
                .put("result", "pending")
                .put(
                    "detail",
                    "batch: ${fields.map { "${it.key} (${it.why})" }}".take(300)
                )
        )
        try {
            val summary = DetailBatchLogic.compactSummary(taskName, fields)
            com.formmitra.app.agent.NotifCenter.notify(
                ctx, com.formmitra.app.agent.NotifCenter.Cat.DETAIL,
                "📝 Kuch details chahiye",
                summary.take(200),
                deepTab = "/agent",
                deepRunId = runId,
                key = "details_$runId"
            )
        } catch (_: Exception) { }
    }

    // =====================================================================
    // Interactive user prompts (OTP / input / choice / payment).
    // Loop BLOCK karke user ka jawab wait karta hai; jawab mile to kaam
    // aage badhta hai. Timeout/cancel → false → caller needs_user finish.
    //
    // Point 14: kind="input" AB BLOCK NAHI KARTA — wo upar handleDetailsNeeded
    // wala non-blocking batch flow use karta hai. Blocking sirf gates ke
    // liye: otp | login | device_auth | payment | document (ye pehle se hain,
    // inko nahi toda).
    // =====================================================================

    /**
     * v24 C15: verify ke liye screenshot chhota karo (720px, JPEG-70) —
     * POST halka rahe. Fail ho to original b64 (verify wala waise bhi
     * fail-closed hai).
     */
    private fun downscaleShot(b64: String, maxW: Int = 720): String {
        if (b64.isEmpty()) return ""
        return try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val opts = android.graphics.BitmapFactory.Options()
                .apply { inSampleSize = 2 }
            var bmp = android.graphics.BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, opts
            ) ?: return b64
            if (bmp.width > maxW) {
                val h = (bmp.height * maxW / bmp.width).coerceAtLeast(1)
                val scaled = android.graphics.Bitmap.createScaledBitmap(
                    bmp, maxW, h, true
                )
                if (scaled != bmp) bmp.recycle()
                bmp = scaled
            }
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            bmp.recycle()
            android.util.Base64.encodeToString(
                out.toByteArray(), android.util.Base64.NO_WRAP
            )
        } catch (_: Exception) {
            b64
        }
    }

    /**
     * Server ke structured user_prompt ko popup me badlo.
     * v34: Int return — 0 = jawab mila aur apply ho gaya (loop continue);
     * 1 = timeout / cancel / mana (needs_user finish);
     * 2 = OTP parked (non-blocking — run park, queue aage, jawab par auto-resume).
     */
    private fun handleServerPrompt(
        ctx: Context,
        engine: FormEngine,
        prompt: Map<String, Any?>,
        runId: String,
        userProvided: JSONObject,
        sensitiveKeys: MutableSet<String>,
        history: ArrayList<JSONObject>
    ): Int {
        // CONTRACT SYNC (2026-09-26): kind canonicalize — server "choice"
        // ko "option_choice" bhejta hai; purana "choice" bhi accept.
        val kind = ServerKinds.canonicalize(prompt["kind"] as? String)
            ?: "input"
        // v34: OTP popup me site ka naam — engine se host nikalo.
        val otpSite = if (kind == "otp") siteHost(engine) else ""
        val req = buildPromptRequest(runId, kind, prompt, otpSite)
        // NOTE: duplicate notification nahi — UserPrompt.ask par FmApp ka
        // raised-listener NotifCenter se notify + PendingPrompt persist
        // karta hai (K1/K4). Yahan sirf automation logic.
        if (kind == "payment") {
            return if (doPaymentFlow(ctx, engine, req, runId) == "verified") 0 else 1
        }
        // ---- L1-UPGRADE: puchhne se PEHLE permanent automation ----
        if (kind == "login") {
            // Saved credentials ho to apne aap login — user se mat puchho
            if (tryAutoLogin(ctx, engine, runId, sensitiveKeys, history)) return 0
            // POINT 19: saved login hai par auto-fill nahi hua → user ko
            // CHOICE do (retry / naya login / saved hatao). true = ho gaya.
            if (offerSavedLoginChoice(ctx, engine, runId, sensitiveKeys, history)) {
                return 0
            }
        }
        if (kind == "document") {
            // Vault me sahi document ho to apne aap attach
            if (tryAutoDocument(ctx, engine, req.docType, userProvided, history)) return 0
        }
        if (kind == "choice" || kind == ServerKinds.OPTION_CHOICE) {
            return if (handleChoicePrompt(ctx, engine, req, userProvided, history)) 0 else 1
        }
        // CONTRACT SYNC (2026-09-26): destructive_confirm — server ka
        // destructive gate. HAMESHA user confirmation; permanent automation
        // approval ise bypass NAHI kar sakta. Background-safe (UserPrompt).
        if (kind == ServerKinds.DESTRUCTIVE_CONFIRM) {
            return if (handleDestructiveConfirm(ctx, runId, prompt, history)) 0 else 1
        }
        if (kind == "device_auth") {
            // User-gated #2 (biometric/device PIN): sirf user de sakta hai.
            // Maximum assistance: seedha dialog + TTS announcement.
            FlowAnnouncer.say(
                ctx,
                "Ab aapko apne phone par fingerprint ya PIN dena hai — baaki sab taiyar hai."
            )
            val devStr = UserPrompt.ask(req) ?: return 1
            val devAns = try { JSONObject(devStr) } catch (_: Exception) { return 1 }
            val ok = devAns.optBoolean("approved", false)
            history.add(
                JSONObject().put("action", "device_auth")
                    .put("result", if (ok) "ok" else "cancelled")
            )
            return if (ok) 0 else 1
        }
        // POINT 22: run ke beech Card lock → chat me one-tap unlock popup.
        // Run park hota hai (false); unlock par apne aap resume — restart nahi.
        if (kind == "card_unlock" || kind == "card_unlock_needed") {
            val cardId = (prompt["card_id"] as? String).orEmpty()
                .ifEmpty { (prompt["cardId"] as? String).orEmpty() }
            val cardName = (prompt["card_name"] as? String).orEmpty()
                .ifEmpty { "Card" }
            try {
                val taskLabel = (prompt["title"] as? String).orEmpty()
                    .ifEmpty { "kaam" }
                com.formmitra.app.agent.CardUnlockNeeded.raise(
                    ctx, runId, taskLabel.take(80), cardId, cardName
                )
                FlowAnnouncer.say(
                    ctx, "Card lock ho gaya hai — chat me kholo, kaam apne aap aage badhega."
                )
                history.add(
                    JSONObject().put("action", "card_unlock_needed")
                        .put("result", "parked")
                        .put("detail", "card $cardId lock — user unlock karega to resume")
                )
            } catch (_: Exception) { }
            return 1
        }
        // ---- L1-UPGRADE: input — DetailStore (device-local saved details)
        // se jo pata ho wo seedha bharo; sirf jo NA mile uske liye puchho.
        // Point 14: NA mile to BLOCKING dialog NAHI — non-blocking batch
        // (DetailBatchStore) uthao aur turant FALSE (caller run park karega,
        // resume state rakha jayega; 10-min timeout wala block hata diya).
        // Gates (otp/login/device_auth/payment/document) pehle jaise hi
        // blocking rahenge — unko nahi toda. ----
        var askReq = req
        if (kind == "input") {
            if (tryAutoFillInput(ctx, engine, req, prompt, userProvided, sensitiveKeys, history)) {
                return 0
            }
            val fields = DetailBatchLogic.parseFields(prompt["fields"])
            if (fields.isNotEmpty()) {
                raiseDetailBatch(ctx, runId, req.title, fields, history)
                return 1
            }
            val prefill = collectKnownInputValues(ctx, req)
            if (prefill.isNotEmpty()) askReq = req.copy(prefill = prefill)
        }
        // v34 (Phase 2A, point 6): OTP gate NON-BLOCKING.
        // Pehle: UserPrompt.ask run-thread ko 600s tak block karta tha —
        // queue ka agla kaam atka rehta tha.
        // Ab: jawab pehle se aaya ho (parked answer / SMS auto-fill) to
        // turant apply; nahi to prompt uthao + run PARK (2) — queue aage
        // badhegi, jawab (manual dialog / SMS) aate hi OtpPark.onAnswered
        // usi step se resume karega.
        if (kind == "otp") {
            val site = otpSite
            val pre = UserPrompt.consumeAnswer(runId)
            if (pre != null) {
                UserPrompt.resolveConsumed(runId)
                OtpPark.unpark(ctx)
                val preAns = try { JSONObject(pre) } catch (_: Exception) { return 1 }
                if (!preAns.optBoolean("approved", false)) return 1
                return applyOtpAnswer(
                    ctx, engine, prompt, runId, askReq, preAns,
                    userProvided, sensitiveKeys, history, site
                )
            }
            OtpPark.park(ctx, runId, site, 0)
            UserPrompt.raiseOnly(askReq)
            history.add(
                JSONObject().put("action", "otp_parked").put("result", "parked")
                    .put("detail", "OTP ka intezaar ($site) — queue aage badhegi, jawab par auto-resume")
            )
            return 2
        }
        val ansStr = UserPrompt.ask(askReq) ?: return 1
        val ans = try { JSONObject(ansStr) } catch (_: Exception) { return 1 }
        if (!ans.optBoolean("approved", false)) return 1
        when (kind) {
            "login" -> return if (handleLoginPrompt(ctx, engine, req, ans, sensitiveKeys, history)) 0 else 1
            "document" -> return if (handleDocumentPrompt(ctx, engine, req, ans, userProvided, history)) 0 else 1
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
        return 0
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
            // v24-refine: learned pattern match → ZERO AI call yahan.
            AiUsage.logPatternHit(AiUsage.P_LOGIN)
            FlowAnnouncer.say(ctx, "Login apne aap ho raha hai — $domain")
        }
        return ok
    }

    /**
     * POINT 19: saved login hai par auto-fill nahi hua (ya fail hua) →
     * user ko CHOICE: "saved login use karo" (dobara try) / "naya login do"
     * / "saved hatao". Username mask dikhta hai, password kabhi nahi.
     * @return true = login ho gaya (aage badho); false = manual dialog dikhao.
     */
    private fun offerSavedLoginChoice(
        ctx: Context,
        engine: FormEngine,
        runId: String,
        sensitiveKeys: MutableSet<String>,
        history: ArrayList<JSONObject>
    ): Boolean {
        val domain = try {
            com.formmitra.app.agent.SiteCredentialStore.domainOf(engine.pageUrl())
        } catch (_: Exception) { "" }
        if (domain.isEmpty()) return false
        val creds = try {
            com.formmitra.app.agent.SiteCredentialStore.get(ctx, domain)
        } catch (_: Exception) { null } ?: return false
        val maskedUser = maskUsername(creds.first)
        val req = UserPrompt.Request(
            runId = runId,
            kind = "choice",
            title = "Login kaise karu?",
            message = "$domain ke liye saved login hai ($maskedUser). " +
                "Password kabhi dikhaya nahi jata.",
            options = listOf(
                "saved login use karo",
                "naya login do",
                "saved hatao"
            )
        )
        val ansStr = try { UserPrompt.ask(req) } catch (_: Exception) { null }
            ?: return false
        val ans = try { JSONObject(ansStr) } catch (_: Exception) { return false }
        if (!ans.optBoolean("approved", false)) return false
        return when (ans.optString("choice", "")) {
            "saved login use karo" -> {
                // Dobara try (transient fail ho sakta tha).
                val ok = fillLoginFields(
                    engine, creds.first, creds.second, sensitiveKeys, history
                )
                if (ok) {
                    history.add(
                        JSONObject().put("action", "fill_login_retry")
                            .put("result", "ok")
                            .put("detail", "saved credentials retry (domain=$domain)")
                    )
                    com.formmitra.app.engine.AiUsage.logPatternHit(
                        com.formmitra.app.engine.AiUsage.P_LOGIN
                    )
                }
                ok
            }
            "saved hatao" -> {
                try {
                    com.formmitra.app.agent.SiteCredentialStore
                        .clear(ctx, domain)
                } catch (_: Exception) { }
                history.add(
                    JSONObject().put("action", "saved_login_deleted")
                        .put("result", "ok").put("detail", "domain=$domain")
                )
                false // manual dialog ab naya login lega
            }
            else -> false // "naya login do" → manual dialog
        }
    }

    /** Username mask: "r•••@gmail.com" — poora kabhi nahi. */
    private fun maskUsername(u: String): String {
        if (u.isEmpty()) return "•••"
        val at = u.indexOf('@')
        return if (at > 1) {
            u[0] + "•••" + u.substring(at)
        } else if (u.length > 4) {
            u.take(2) + "•••" + u.takeLast(2)
        } else {
            "•••"
        }
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
            // v24-refine: learned pattern match → ZERO AI call yahan.
            AiUsage.logPatternHit(AiUsage.P_DOC)
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
            // v24-refine: learned pattern match → ZERO AI call yahan.
            AiUsage.logPatternHit(AiUsage.P_DETAIL)
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
            // v24-refine: learned pattern match → ZERO AI call yahan.
            AiUsage.logPatternHit(AiUsage.P_CHOICE)
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

    /**
     * CONTRACT SYNC (2026-09-26): destructive_confirm — server ka
     * destructive gate (cancel/withdraw/delete/account-close).
     *
     * HAMESHA blocking confirmation — permanent automation approval ise
     * bypass NAHI kar sakta, koi "hamesha allow" nahi. UserPrompt se
     * background-safe: notification → dialog → jawab.
     *
     * @return true = user ne "Haan karo" dabaya (aage badho);
     *         false = mana/timeout (run park).
     */
    private fun handleDestructiveConfirm(
        ctx: Context,
        runId: String,
        prompt: Map<String, Any?>,
        history: ArrayList<JSONObject>
    ): Boolean {
        val what = (prompt["message"] as? String).orEmpty()
            .ifEmpty { (prompt["title"] as? String).orEmpty() }
            .ifEmpty { (prompt["action_label"] as? String).orEmpty() }
            .ifEmpty { (prompt["label"] as? String).orEmpty() }
            .ifEmpty { "ye kaam" }
        val req = UserPrompt.Request(
            runId = runId,
            kind = ServerKinds.DESTRUCTIVE_CONFIRM,
            title = "Pakka karna hai?",
            message = what
        )
        // v36 refine point 4: gate kab aaya + kitni der ruka + kisne jawab diya.
        val dgId = try {
            com.formmitra.app.agent.GateAudit.openGate(
                ctx, runId, "destructive_confirm", what.take(120)
            )
        } catch (_: Exception) { "" }
        // v36 — LIVE ACTIVITY INDICATOR: gate-specific label chat me.
        try { LiveActivity.emitGate(runId, "destructive_confirm") } catch (_: Exception) { }
        val ansStr = try { UserPrompt.ask(req) } catch (_: Exception) { null }
            ?: return false
        val ans = try { JSONObject(ansStr) } catch (_: Exception) { return false }
        val ok = ans.optBoolean("approved", false)
        try {
            if (dgId.isNotEmpty()) {
                com.formmitra.app.agent.GateAudit.closeGate(
                    ctx, runId, dgId,
                    if (ok) "approved" else "declined",
                    com.formmitra.app.agent.GateAudit.BY_USER, what.take(120)
                )
            }
        } catch (_: Exception) { }
        history.add(
            JSONObject().put("action", "destructive_confirm")
                .put("result", if (ok) "approved" else "declined")
                .put("detail", what.take(200))
        )
        // Quality bar 6: har gate decision audit trail me.
        try {
            com.formmitra.app.agent.GateAudit.log(
                ctx, "destructive_confirm", runId,
                if (ok) "approved" else "declined", what.take(200)
            )
        } catch (_: Exception) { }
        if (!ok) {
            try {
                FlowAnnouncer.say(ctx, "Theek hai — kuch nahi kiya.")
            } catch (_: Exception) { }
        }
        return ok
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
     * v34 (Phase 2A): OTP target — single field ya multi-box digit group.
     */
    private sealed class OtpTarget {
        data class Single(val mode: String, val value: String) : OtpTarget()
        data class Boxes(val boxes: List<Pair<String, String>>) : OtpTarget()
    }

    /**
     * OTP/password ko page me LOCAL bharo (server/AI ko value kabhi nahi bheji jati).
     * fill_selector (server ne diya) → nahi to OtpFieldDetect auto-detect
     * (single field + multi-box per-digit fill).
     * true = bhara; verify alag step (submitOtpAndVerify).
     */
    private fun fillSecretLocally(
        engine: FormEngine,
        prompt: Map<String, Any?>,
        value: String
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        val sel = prompt["fill_selector"] as? Map<String, Any?>
        val mode = sel?.get("mode") as? String ?: ""
        val selVal = sel?.get("value") as? String ?: ""
        if (mode.isNotEmpty() && selVal.isNotEmpty()) return fillOne(engine, mode, selVal, value)
        return when (val target = findOtpTarget(engine)) {
            null -> false
            is OtpTarget.Single -> fillOne(engine, target.mode, target.value, value)
            is OtpTarget.Boxes -> {
                // OTP lamba ho boxes se → fit nahi hoga (saaf fail).
                if (value.length > target.boxes.size) return false
                var ok = true
                target.boxes.forEachIndexed { i, b ->
                    val digit = if (i < value.length) value[i].toString() else ""
                    if (digit.isNotEmpty() && !fillOne(engine, b.first, b.second, digit)) ok = false
                }
                ok
            }
        }
    }

    private fun fillOne(engine: FormEngine, mode: String, selVal: String, text: String): Boolean =
        try {
            val detail = engine.runAgentStep(
                JSONObject().put("type", "fill")
                    .put(
                        "selector",
                        JSONObject().put("mode", mode).put("value", selVal)
                    )
                    .put("text", text)
            )
            detail.optBoolean("verified", true)
        } catch (_: Exception) {
            false
        }

    /** v34: OtpFieldDetect adapter — page par OTP field (single/multi-box) dhoondho. */
    private fun findOtpTarget(engine: FormEngine): OtpTarget? {
        val snap = try { engine.domSnapshot() } catch (_: Exception) { return null }
        val fields = snap.optJSONArray("fields") ?: return null
        val fs = (0 until fields.length()).mapNotNull { i ->
            val f = fields.optJSONObject(i) ?: return@mapNotNull null
            OtpFieldDetect.Field(
                tag = f.optString("tag", ""),
                type = f.optString("type", ""),
                label = f.optString("label", ""),
                placeholder = f.optString("placeholder", ""),
                aria = f.optString("aria", ""),
                name = f.optString("name", ""),
                id = f.optString("id", ""),
                maxLen = f.optInt("maxlength", -1)
            )
        }
        // Multi-box pehle (common OTP UI), phir best single field.
        val boxes = OtpFieldDetect.boxGroup(fs)
        if (boxes != null) {
            return OtpTarget.Boxes(boxes.map { selectorMode(it) to selectorValue(it) })
        }
        val best = OtpFieldDetect.bestField(fs) ?: return null
        return OtpTarget.Single(selectorMode(best), selectorValue(best))
    }

    private fun selectorMode(f: OtpFieldDetect.Field): String = when {
        f.id.isNotEmpty() -> "id"
        f.name.isNotEmpty() -> "name"
        f.placeholder.isNotEmpty() -> "placeholder"
        f.label.isNotEmpty() -> "label"
        f.aria.isNotEmpty() -> "aria"
        else -> "id"
    }

    private fun selectorValue(f: OtpFieldDetect.Field): String = when {
        f.id.isNotEmpty() -> f.id
        f.name.isNotEmpty() -> f.name
        f.placeholder.isNotEmpty() -> f.placeholder
        f.label.isNotEmpty() -> f.label
        f.aria.isNotEmpty() -> f.aria
        else -> ""
    }

    /** Current page ka host — OTP popup me site pehchan ke liye ("example.gov.in ke liye OTP"). */
    private fun siteHost(engine: FormEngine): String = try {
        val h = java.net.URL(engine.pageUrl()).host.orEmpty().lowercase()
        h.removePrefix("www.")
    } catch (_: Exception) { "" }

    /**
     * v34 (Phase 2A, points 4+5): OTP bharne ke baad Verify/Submit dabao,
     * result classify karo; FAILURE par 1 retry (total 2 attempts).
     * true = success confirm; false = failure/unknown.
     */
    private fun submitOtpAndVerify(engine: FormEngine): Boolean {
        repeat(2) { attempt ->
            val before = try { engine.domSnapshot() } catch (_: Exception) { return false }
            val beforeUrl = before.optString("url", "")
            val beforeText = before.optString("page_text", "")
            val btns = before.optJSONArray("buttons")
            val btnTexts = (0 until (btns?.length() ?: 0))
                .mapNotNull { btns?.optJSONObject(it)?.optString("text", "") }
            val btnText = OtpFieldDetect.submitButtonText(btnTexts)
            val clicked = if (btnText != null) {
                try {
                    engine.runAgentStep(
                        JSONObject().put("type", "click").put(
                            "selector",
                            JSONObject().put("mode", "text").put("value", btnText)
                        )
                    )
                    true
                } catch (_: Exception) { false }
            } else {
                // Koi button na mile → Enter dabao (OTP forms aksar Enter par submit hote hain).
                engine.dispatchEnterKey()
            }
            if (!clicked) return false
            try { Thread.sleep(3000) } catch (_: Exception) { }
            val after = try { engine.domSnapshot() } catch (_: Exception) { null }
            val result = OtpFieldDetect.classifyResult(
                beforeUrl, after?.optString("url", "") ?: "",
                beforeText, after?.optString("page_text", "") ?: ""
            )
            if (result == OtpFieldDetect.Result.SUCCESS) return true
            if (result == OtpFieldDetect.Result.FAILURE && attempt == 0) {
                try { Thread.sleep(1500) } catch (_: Exception) { }
                return@repeat
            }
            return false
        }
        return false
    }

    /**
     * v34: aaye hue OTP jawab ko apply karo — field bharo → verify dabao →
     * result check → galat OTP par 1 baar dobara maango.
     * Return: 0 = success (loop continue), 1 = needs_user finish, 2 = dobara parked.
     */
    private fun applyOtpAnswer(
        ctx: Context,
        engine: FormEngine,
        prompt: Map<String, Any?>,
        runId: String,
        askReq: UserPrompt.Request,
        ans: JSONObject,
        userProvided: JSONObject,
        sensitiveKeys: MutableSet<String>,
        history: ArrayList<JSONObject>,
        site: String
    ): Int {
        // field key -> type (sensitive = otp/password: KABHI server/AI ko
        // mat bhejo, sirf page me locally bharo) — else-branch wala pattern.
        val fieldTypes = ((prompt["fields"] as? List<*>) ?: emptyList<Any?>())
            .mapNotNull { it as? Map<String, Any?> }
            .associate {
                ((it["key"] as? String) ?: "value") to
                    ((it["type"] as? String) ?: "text")
            }
        var otpValue = ""
        var otpKey = ""
        val keys = ans.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val t = fieldTypes[k].orEmpty()
            if (isSensitiveField(k, t)) {
                otpValue = ans.optString(k, "")
                otpKey = k
                break
            }
        }
        // Fallback: pehli non-empty string value.
        if (otpValue.isEmpty()) {
            val keys2 = ans.keys()
            while (keys2.hasNext()) {
                val k = keys2.next()
                if (k == "approved") continue
                val v = ans.optString(k, "")
                if (v.isNotEmpty()) { otpValue = v; otpKey = k; break }
            }
        }
        if (otpValue.isEmpty()) {
            history.add(
                JSONObject().put("action", "otp_fill").put("result", "empty")
                    .put("detail", "OTP jawab khaali mila — dobara maanga jayega")
            )
            return reaskOtp(ctx, runId, askReq, history, site)
        }
        if (otpKey.isNotEmpty()) sensitiveKeys.add(otpKey)
        // OTP kabhi server/AI/history/notifications me nahi jata — sirf local fill.
        if (!OtpParser.looksLikeOtp(otpValue)) {
            history.add(
                JSONObject().put("action", "otp_fill").put("result", "invalid")
                    .put("detail", "OTP format theek nahi laga — dobara maanga jayega")
            )
            return reaskOtp(ctx, runId, askReq, history, site)
        }
        if (!fillSecretLocally(engine, prompt, otpValue)) {
            history.add(
                JSONObject().put("action", "otp_fill").put("result", "field_not_found")
                    .put("detail", "Page par OTP field nahi mila ($site)")
            )
            return 1
        }
        history.add(
            JSONObject().put("action", "otp_fill").put("result", "ok")
                .put("detail", "OTP bhara ($site) — verify dabakar result check ho raha hai")
        )
        if (submitOtpAndVerify(engine)) {
            history.add(
                JSONObject().put("action", "otp_verify").put("result", "success")
                    .put("detail", "OTP verify ho gaya ($site)")
            )
            return 0
        }
        // Verify fail → 1 baar dobara OTP maango (parked), phir needs_user.
        history.add(
            JSONObject().put("action", "otp_verify").put("result", "failed")
                .put("detail", "OTP galat/expire lag raha hai ($site)")
        )
        return reaskOtp(ctx, runId, askReq, history, site)
    }

    /**
     * Galat/expire OTP par 1 baar dobara maango (site ka naam saaf dikhe).
     * Limit khatam → 1 (needs_user, caller saaf message dega).
     */
    private fun reaskOtp(
        ctx: Context,
        runId: String,
        askReq: UserPrompt.Request,
        history: ArrayList<JSONObject>,
        site: String
    ): Int {
        val retry = OtpPark.parkedRetry(ctx)
        if (retry >= 1) {
            OtpPark.unpark(ctx)
            return 1
        }
        val again = askReq.copy(
            message = "Pichla OTP galat tha ya expire ho gaya — naya OTP do.\n" +
                "Site: $site"
        )
        OtpPark.park(ctx, runId, site, retry + 1)
        UserPrompt.raiseOnly(again)
        history.add(
            JSONObject().put("action", "otp_reask").put("result", "parked")
                .put("detail", "Naya OTP maanga gaya ($site)")
        )
        return 2
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
        // v36 refine point 4: gate KAB aaya — openGate UserPrompt.ask se
        // PEHLE (wait tab se gina jata hai jab user ko gate dikhta hai).
        val payGateId = try {
            com.formmitra.app.agent.GateAudit.openGate(
                ctx, runId, "payment",
                "amount=${pay?.amount ?: ""} merchant=${pay?.merchant ?: ""}"
            )
        } catch (_: Exception) { "" }
        // v36 — LIVE ACTIVITY INDICATOR: gate-specific label chat me.
        try { LiveActivity.emitGate(runId, "payment") } catch (_: Exception) { }
        val ansStr = UserPrompt.ask(req)
        // POINT 15 + QUALITY BAR 6: har payment decision audit trail me.
        // v36 refine point 4: KITNI DER ruka + KISNE jawab diya — closeGate
        // (wait_ms + answered_by history me).
        fun auditPayment(status: String, extra: String = "") {
            try {
                com.formmitra.app.agent.GateAudit.log(
                    ctx,
                    "payment",
                    runId,
                    if (status == "verified") "verified" else "decided",
                    "status=$status amount=${pay?.amount ?: ""} " +
                        "merchant=${pay?.merchant ?: ""} $extra".trim()
                )
            } catch (_: Exception) { }
        }
        fun closePayGate(decision: String, by: String) {
            try {
                if (payGateId.isNotEmpty()) {
                    com.formmitra.app.agent.GateAudit.closeGate(
                        ctx, runId, payGateId, decision, by,
                        "amount=${pay?.amount ?: ""}"
                    )
                }
            } catch (_: Exception) { }
        }
        if (ansStr == null) {
            try {
                AgentApi.patchPayment(
                    ctx, runId,
                    JSONObject().put("status", "failed").put("reason", "timeout")
                )
            } catch (_: Exception) { }
            auditPayment("failed", "reason=timeout")
            closePayGate("expired", com.formmitra.app.agent.GateAudit.BY_AUTO)
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
            auditPayment("failed", "reason=declined")
            closePayGate("declined", com.formmitra.app.agent.GateAudit.BY_USER)
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
            auditPayment("failed", "reason=${ans.optString("reason", "unknown")}")
            closePayGate("declined", com.formmitra.app.agent.GateAudit.BY_USER)
            return "timeout"
        }
        val method = ans.optString("method", "")
        try {
            AgentApi.patchPayment(
                ctx, runId,
                JSONObject().put("status", "paid_claimed").put("method", method)
            )
        } catch (_: Exception) { }
        auditPayment("paid_claimed", "method=$method")
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
                auditPayment("verified", "site-success-text mila")
                closePayGate("approved", com.formmitra.app.agent.GateAudit.BY_USER)
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
        auditPayment("failed", "reason=unverified")
        closePayGate("approved_unverified", com.formmitra.app.agent.GateAudit.BY_USER)
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
        prompt: Map<String, Any?>,
        site: String = ""
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
        // v34: OTP popup me site saaf dikhe — "example.gov.in ke liye OTP".
        val baseMsg = (prompt["message"] as? String)?.ifEmpty { null }
            ?: "Agent ko aapki zaroorat hai"
        val fullMsg = if (kind == "otp" && site.isNotEmpty() &&
            !baseMsg.contains(site)
        ) "$baseMsg\nSite: $site" else baseMsg
        return UserPrompt.Request(
            runId = runId,
            kind = kind,
            title = (prompt["title"] as? String)?.ifEmpty { null } ?: "Madad chahiye",
            message = fullMsg,
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

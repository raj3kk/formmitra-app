import com.formmitra.app.agent.AgentApi
import com.formmitra.app.agent.GateAudit
import com.formmitra.app.agent.LearnLogic
import com.formmitra.app.agent.LiveActivity
import com.formmitra.app.agent.PageStructureHash
import com.formmitra.app.agent.WorkPatternStore
import com.formmitra.app.engine.AiUsage
import com.formmitra.app.engine.ErrorCatcher
import com.formmitra.app.engine.EscalationLadder
import com.formmitra.app.engine.IdempotencyGuard
import com.formmitra.app.engine.PreflightPlan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Self-test: v36 SMART COORDINATION pure logic.
//  - PreflightPlan.parse / hinglishSummary / stepsContextJson
//  - EscalationLadder (pattern → agent_plan → ai_single_step → user_gate)
//  - LearnLogic work-pattern key / confidence / shouldReplay
//  - AiUsage model/token ledger
// Compile: kotlinc -cp <android.jar> <src>/engine/PreflightPlan.kt
//   <src>/engine/EscalationLadder.kt <src>/engine/AiUsage.kt
//   <src>/agent/LearnLogic.kt tools/selftest/SelfTestV36.kt -d out
//   && java -cp out:<org.json>:<stdlib>:<android.jar> SelfTestV36Kt

var failures = 0

fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}

fun validPlanJson(): JSONObject = JSONObject()
    .put("goal_hinglish", "caste certificate ke liye apply")
    .put("start_url", "https://serviceonline.bihar.gov.in/")
    .put(
        "steps", JSONArray()
            .put(JSONObject().put("n", 1).put("action", "goto").put("target", "portal home").put("value", "").put("note_hinglish", "portal kholo"))
            .put(JSONObject().put("n", 2).put("action", "fill").put("target", "Naam wala field").put("value", "Ram").put("note_hinglish", "naam bharo"))
            .put(JSONObject().put("n", 3).put("action", "click").put("target", "Submit button").put("value", "").put("note_hinglish", "submit karo"))
    )
    .put("expected_proofs", JSONArray().put("acknowledgement number dikhe"))
    .put(
        "gates", JSONArray()
            .put(JSONObject().put("kind", "otp").put("at_step", 2).put("title_hinglish", "Aadhaar OTP").put("blocking", true))
    )
    .put("missing_details", JSONArray().put("full_name").put("aadhar_no"))
    .put("estimated_minutes", 10)

fun main() {
    // ============ PreflightPlan.parse ============
    val plan = PreflightPlan.parse(validPlanJson())
    check("preflight parse valid", plan != null)
    check("preflight goal", plan?.goalHinglish == "caste certificate ke liye apply")
    check("preflight start_url", plan?.startUrl == "https://serviceonline.bihar.gov.in/")
    check("preflight 3 steps", plan?.steps?.size == 3)
    check("preflight step2 fill", plan?.steps?.get(1)?.action == "fill")
    check("preflight gate otp blocking", plan?.gates?.size == 1 && plan?.gates?.get(0)?.blocking == true)
    check("preflight missing 2", plan?.missingDetails?.size == 2)
    check("preflight minutes", plan?.estimatedMinutes == 10)

    check("preflight null → null", PreflightPlan.parse(null) == null)
    check("preflight unknown → null", PreflightPlan.parse(JSONObject().put("unknown", true)) == null)
    check("preflight http rejected", PreflightPlan.parse(validPlanJson().put("start_url", "http://example.com/")) == null)
    check(
        "preflight payment url rejected",
        PreflightPlan.parse(validPlanJson().put("start_url", "https://pay.example.com/checkout")) == null
    )
    check(
        "preflight no steps → null",
        PreflightPlan.parse(validPlanJson().put("steps", JSONArray())) == null
    )
    check(
        "preflight bad action dropped → null",
        PreflightPlan.parse(
            validPlanJson().put(
                "steps", JSONArray().put(
                    JSONObject().put("n", 1).put("action", "hack").put("target", "x")
                        .put("value", "").put("note_hinglish", "")
                )
            )
        ) == null
    )
    // gate blocking rule: choice kabhi blocking nahi
    val choiceGate = PreflightPlan.parse(
        validPlanJson().put(
            "gates", JSONArray().put(
                JSONObject().put("kind", "choice").put("at_step", 1)
                    .put("title_hinglish", "chuno").put("blocking", true)
            )
        )
    )
    check(
        "preflight choice gate not blocking",
        choiceGate != null && choiceGate.gates.size == 1 && !choiceGate.gates[0].blocking
    )
    // minutes clamp
    val big = PreflightPlan.parse(validPlanJson().put("estimated_minutes", 999))
    check("preflight minutes clamped", big?.estimatedMinutes == 30)

    // ============ hinglishSummary / stepsContextJson ============
    val summ = PreflightPlan.hinglishSummary(plan!!)
    check("summary has goal", summ.contains("caste certificate"))
    check("summary has step count", summ.contains("3 steps"))
    check("summary has gate warning", summ.contains("Aadhaar OTP"))
    check("summary no jargon", !summ.contains("https://") && !summ.contains("JSON"))
    val ctxJson = PreflightPlan.stepsContextJson(plan)
    check("context 3 steps", ctxJson.length() == 3)
    check(
        "context no values",
        (0 until ctxJson.length()).all { !ctxJson.getJSONObject(it).has("value") }
    )

    // ============ EscalationLadder ============
    check("ladder valid", EscalationLadder.isValid("pattern") && !EscalationLadder.isValid("bogus"))
    check(
        "ladder order",
        EscalationLadder.next("pattern") == "agent_plan" &&
            EscalationLadder.next("agent_plan") == "ai_single_step" &&
            EscalationLadder.next("ai_single_step") == "user_gate" &&
            EscalationLadder.next("user_gate") == "user_gate"
    )
    check("ladder unknown → pattern", EscalationLadder.next("bogus") == "pattern")
    check(
        "ladder usesAi",
        !EscalationLadder.usesAi("pattern") && EscalationLadder.usesAi("agent_plan") &&
            EscalationLadder.usesAi("ai_single_step") && !EscalationLadder.usesAi("user_gate")
    )
    val audit = EscalationLadder.auditEntry("pattern", "agent_plan", "fill(naaam)", "replay fail", true)
    check(
        "ladder audit fields",
        audit.optString("from") == "pattern" && audit.optString("to") == "agent_plan" &&
            audit.optString("failed_step") == "fill(naaam)" &&
            audit.optBoolean("pattern_invalidated") && audit.optLong("at") > 0
    )
    check("ladder hinglish", EscalationLadder.hinglishName("pattern").isNotEmpty())

    // ============ LearnLogic: work patterns ============
    val k1 = LearnLogic.workPatternKey("Caste certificate apply karo!", "ServiceOnline.Bihar.gov.in")
    val k2 = LearnLogic.workPatternKey("caste certificate apply", "serviceonline.bihar.gov.in")
    check("pattern key normalized", k1 == k2)
    check("pattern key has domain", k1.endsWith("@serviceonline.bihar.gov.in"))
    check(
        "normalize goal",
        LearnLogic.normalizeGoal("  Caste, Certificate APPLY karo!! ") == "caste certificate apply"
    )
    check("confidence 5/0", LearnLogic.patternConfidence(5, 0) == 1.0)
    check("confidence 1/0", LearnLogic.patternConfidence(1, 0) == 0.8)
    check("confidence 0/1", LearnLogic.patternConfidence(0, 1) == 0.5)
    check("confidence 0/2", LearnLogic.patternConfidence(0, 2) == 0.0)
    val now = System.currentTimeMillis()
    val day = 24L * 60 * 60 * 1000
    check("replay fresh confident", LearnLogic.shouldReplay(5, 0, now - day, now))
    check("replay new pattern", LearnLogic.shouldReplay(1, 0, now - day, now))
    check("replay no on fail", !LearnLogic.shouldReplay(5, 1, now - day, now))
    check("replay no when invalid", !LearnLogic.shouldReplay(5, 2, now - day, now))
    check("replay no when stale", !LearnLogic.shouldReplay(5, 0, now - 31 * day, now))
    check("replay no when zero success", !LearnLogic.shouldReplay(0, 0, now - day, now))

    // ============ AiUsage: model/token ledger ============
    AiUsage.reset()
    AiUsage.logModelCall("qwen3.8-27b", "groq")
    AiUsage.logModelCall("qwen3.8-27b", "groq")
    AiUsage.logModelCall("", "")
    val mc = AiUsage.modelCallSummary()
    check("model calls counted", mc["groq/qwen3.8-27b"] == 2 && mc["ai/unknown"] == 1)
    val ts1 = AiUsage.tokenSummary()
    check("token summary models", ts1.contains("qwen3.8-27b"))
    check("token summary andaza", ts1.contains("(andaza"))
    AiUsage.logMeasuredTokens(1500, 300)
    val ts2 = AiUsage.tokenSummary()
    check("token summary measured", ts2.contains("measured tokens") && !ts2.contains("(andaza"))
    AiUsage.reset()
    check("model ledger reset", AiUsage.modelCallSummary().isEmpty())
    check("token summary zero ai", AiUsage.tokenSummary().contains("zero AI"))

    // ============ v36 refine point 2: per-step stuck escalation ============
    check("step 1 fail → continue", LearnLogic.stepEscalation(1) == LearnLogic.STEP_OK)
    check("step 2 fail → AI help", LearnLogic.stepEscalation(2) == LearnLogic.STEP_AI_HELP)
    check("step 3 fail → user gate", LearnLogic.stepEscalation(3) == LearnLogic.STEP_USER_GATE)
    check("step 5 fail → user gate", LearnLogic.stepEscalation(5) == LearnLogic.STEP_USER_GATE)

    // ============ v36 refine point 3: page-structure hash ============
    val h1 = PageStructureHash.of(
        "https://example.com/form?token=abc",
        listOf("Naam", "Pata", "Mobile Number"),
        listOf("Submit", "Reset")
    )
    val h2 = PageStructureHash.of(
        "https://example.com/form?token=xyz",
        listOf("mobile number", "naam", "pata"),  // order/case alag — same structure
        listOf("reset", "submit")
    )
    check("page hash stable (order/case/query-proof)", h1 == h2 && h1.length == 8)
    val h3 = PageStructureHash.of(
        "https://example.com/form",
        listOf("Naam", "Pata", "Email"),  // ek field badla
        listOf("Submit", "Reset")
    )
    check("page hash changes on structure change", h3 != h1)
    val h4 = PageStructureHash.of(
        "https://example.com/form",
        listOf("Naam", "Pata", "Mobile Number"),
        listOf("Submit", "Reset", "Naya Button")  // button badla
    )
    check("page hash changes on button change", h4 != h1)
    // snapshot se — values hash me nahi jati
    val snap = JSONObject()
        .put("fields", JSONArray()
            .put(JSONObject().put("label", "Naam").put("name", "nm").put("type", "text"))
            .put(JSONObject().put("hint", "Mobile").put("name", "mob").put("type", "tel")))
        .put("buttons", JSONArray().put(JSONObject().put("text", "Submit")))
    val hs1 = PageStructureHash.ofSnapshot("https://example.com/a", snap)
    check("snapshot hash ok", hs1.length == 8)
    // values nahi hain snapshot me — label-only hash deterministic
    check("snapshot hash deterministic", PageStructureHash.ofSnapshot("https://example.com/a", snap) == hs1)

    // ============ v36 refine point 4: gates audit trail ============
    check("gate wait 0", GateAudit.waitMs(1000, 1000) == 0L)
    check("gate wait normal", GateAudit.waitMs(1000, 65000) == 64000L)
    check("gate wait negative → 0", GateAudit.waitMs(5000, 1000) == 0L)
    check("wait text turant", GateAudit.waitText(3000) == "turant")
    check("wait text sec", GateAudit.waitText(30000) == "30 sec ruka")
    check("wait text min", GateAudit.waitText(150000) == "2 min 30 sec ruka")
    check(
        "answered_by consts",
        GateAudit.BY_USER == "user" && GateAudit.BY_AGENT == "agent" && GateAudit.BY_AUTO == "auto"
    )

    // ============ v36 user order (2026-09-26): TOKEN CAP HATAYA ============
    // Token kharch par koi restriction/throttling/pause/block NAHI.
    // Ledger SIRF ginta hai (internal hisaab); user ko kuch nahi dikhta.
    AiUsage.reset()
    // 2 calls ka andaza: 2 × 1.5K × 0.00020 (qwen3.8-27b) = 0.0006
    AiUsage.logModelCall("qwen3.8-27b", "groq")
    AiUsage.logModelCall("qwen3.8-27b", "groq")
    val est = AiUsage.currentCostUsd()
    check("cost estimate sane", est > 0.0005 && est < 0.001)
    // measured tokens path
    AiUsage.logMeasuredTokens(100000, 20000)  // 120K tokens × 0.00035 = 0.042
    check("measured cost", AiUsage.currentCostUsd() > 0.04 && AiUsage.currentCostUsd() < 0.05)
    // chahe kharch kitna bhi ho — koi block function exist hi nahi karta
    // (isOverBudget/budgetMessage hata diye gaye — compile hi iska proof hai)
    val ledgerSumm = AiUsage.tokenSummary()
    check("ledger summary internal", ledgerSumm.contains("groq/qwen3.8-27b"))
    AiUsage.reset()

    // ============ v36 refine point 8: idempotency ============
    val nowMs = System.currentTimeMillis()
    check(
        "same runId → duplicate",
        IdempotencyGuard.isDuplicate(setOf("r1"), emptyMap(), "r1", "g@h", nowMs)
    )
    check(
        "other runId ok",
        !IdempotencyGuard.isDuplicate(setOf("r1"), emptyMap(), "r2", "g@h", nowMs)
    )
    check(
        "same goal within 10min → duplicate",
        IdempotencyGuard.isDuplicate(
            emptySet(), mapOf("goal@host" to nowMs - 5 * 60 * 1000),
            "r9", "goal@host", nowMs
        )
    )
    check(
        "same goal after 11min → ok",
        !IdempotencyGuard.isDuplicate(
            emptySet(), mapOf("goal@host" to nowMs - 11 * 60 * 1000),
            "r9", "goal@host", nowMs
        )
    )
    check(
        "empty keys → not duplicate",
        !IdempotencyGuard.isDuplicate(emptySet(), emptyMap(), "", "", nowMs)
    )
    val gk1 = IdempotencyGuard.goalKey("  Caste CERTIFICATE apply!! ", "https://ServiceOnline.Bihar.gov.in/x?y=1")
    val gk2 = IdempotencyGuard.goalKey("caste certificate apply", "https://serviceonline.bihar.gov.in/z")
    check("goalKey normalized", gk1 == gk2)

    // ============ v36 catcher order: POORA detail present ============
    // 100+ line wala deep stack banao — copy/report me sab hona chahiye.
    // NOTE (root-fix 2026-09-26): deep() ko ASLI me throw karna chahiye —
    // pehle ye sirf exception BANATA tha (throw nahi), isliye `boom`
    // "unreachable" wali chhoti exception ban jati thi aur 7 checks fail
    // hote the. Test fixture ka dosh tha, ErrorCatcher ka nahi.
    fun deep(n: Int): Nothing {
        if (n <= 0) throw RuntimeException("test boom otp=123456 password=secret123")
        try {
            deep(n - 1)
        } catch (t: Throwable) {
            throw RuntimeException("wrap $n", t)
        }
    }
    val boom = try { deep(80); RuntimeException("unreachable") }
    catch (t: Throwable) { t }
    val tech = ErrorCatcher.technicalDetail(boom)
    val traceLines = try {
        val sw = java.io.StringWriter()
        boom.printStackTrace(java.io.PrintWriter(sw))
        sw.toString().lines().size
    } catch (_: Exception) { 0 }
    check("catcher: trace deep (>60 lines)", traceLines > 60)
    check("catcher: error class present", tech.contains("Error class: java.lang.RuntimeException"))
    check("catcher: message present", tech.contains("Message:"))
    check("catcher: error code present", tech.contains("Error code: RUNTIMEEXCE"))
    check("catcher: jahan hua present", tech.contains("Jahan hua:") && tech.contains("at "))
    check("catcher: cause chain present", tech.contains("Cause chain:"))
    check("catcher: full stack untruncated", tech.contains("wrap 1"))
    check(
        "catcher: secrets masked",
        !tech.contains("123456") && !tech.contains("secret123") && tech.contains("****")
    )
    check(
        "catcher: non-secret raw dikhta hai",
        tech.contains("test boom")  // secret ke aas-paas ka normal text raw
    )
    val wh = ErrorCatcher.whereHappened(boom)
    check("catcher: whereHappened frames", wh.isNotEmpty() && wh.size <= 5 && wh[0].startsWith("at "))
    val code1 = ErrorCatcher.errorCode(boom)
    val code2 = ErrorCatcher.errorCode(boom)
    check("catcher: error code stable", code1 == code2 && code1.contains("-"))
    // formatReport (Copy button) me bhi poora detail
    val rep = ErrorCatcher.formatReport("Test jagah", "Test kaam", "s1", boom)
    check("catcher: report has full detail", rep.contains("Error class:") && rep.contains("wrap 1"))
    check("catcher: report masks secrets", !rep.contains("123456") && rep.contains("****"))
    check("catcher: report header", rep.contains("FormMitra error report") && rep.contains("Test jagah"))

    // ============ v36: LIVE ACTIVITY INDICATOR (user order 2026-09-26) ============
    // spec 3: har step-type ka fixed simple-Hinglish label
    val stepLabels = mapOf(
        "step:goto" to "Website khol raha hai",
        "step:set_desktop" to "Website khol raha hai",
        "step:research" to "Website dhoondh raha hai",
        "step:fill" to "Form bhar raha hai",
        "step:select" to "Option chun raha hai",
        "step:toggle" to "Option chun raha hai",
        "step:click" to "Button daba raha hai",
        "step:press" to "Button daba raha hai",
        "step:upload" to "Document laga raha hai",
        "step:scroll" to "Page dekh raha hai",
        "step:screenshot" to "Jaanch raha hai",
        "step:captcha_detect" to "Captcha dekh raha hai",
        "step:captcha_solve" to "Captcha solve kar raha hai",
        "step:captcha" to "Captcha solve kar raha hai",
        "step:verify_submit" to "Submit se pehle jaanch raha hai",
        "step:wait_for_text" to "Intezaar kar raha hai",
        "step:wait_for_element" to "Intezaar kar raha hai",
        "step:wait_for_navigation" to "Intezaar kar raha hai",
        "step:back" to "Peeche ja raha hai",
        "step:forward" to "Aage badh raha hai",
        "step:preflight_plan" to "Plan bana raha hai",
        "step:ai_diagnosis" to "Soch raha hai",
        "step:ai_single_step" to "Soch raha hai",
        "step:precheck" to "Taiyaari kar raha hai",
        "step:site_memory" to "Details taiyaar kar raha hai",
        "step:fieldmap" to "Details taiyaar kar raha hai",
        "step:correct_field" to "Details taiyaar kar raha hai",
        "step:some_future_type" to "Kaam kar raha hai", // unknown → generic
        "step:" to "Kaam kar raha hai"
    )
    for ((k, want) in stepLabels) check("live label $k", LiveActivity.labelFor(k) == want)
    // spec 4: gate-specific labels
    val gateLabels = mapOf(
        "gate:otp" to "OTP ka intezaar hai",
        "gate:payment" to "Payment approval ka intezaar hai",
        "gate:choice" to "Tumhare jawab ka intezaar hai",
        "gate:option_choice" to "Tumhare jawab ka intezaar hai",
        "gate:input" to "Detail ka intezaar hai",
        "gate:fields" to "Detail ka intezaar hai",
        "gate:detail" to "Detail ka intezaar hai",
        "gate:login" to "Login ka intezaar hai",
        "gate:document" to "Document ka intezaar hai",
        "gate:destructive_confirm" to "Confirm ka intezaar hai",
        "gate:device_auth" to "Verification ka intezaar hai",
        "gate:needs_user" to "Rukawat aayi — tumse poochh raha hai",
        "state:state_started" to "Kaam shuru ho raha hai"
    )
    for ((k, want) in gateLabels) check("live label $k", LiveActivity.labelFor(k) == want)
    // spec 4: complete/rukne/fail par indicator GAYAB (label null)
    check("live terminal done → null", LiveActivity.labelFor("state:state_done") == null)
    check("live terminal failed → null", LiveActivity.labelFor("state:state_failed") == null)
    check("live terminal stopped → null", LiveActivity.labelFor("state:state_stopped") == null)
    check("live isTerminal done", LiveActivity.isTerminal(LiveActivity.Event("r", "state:state_done")))
    check("live isTerminal gate false", !LiveActivity.isTerminal(LiveActivity.Event("r", "gate:otp")))
    // spec 3: koi technical jargon nahi
    for ((k, _) in stepLabels + gateLabels) {
        val l = LiveActivity.labelFor(k) ?: continue
        val clean = !l.contains("DOM") && !l.contains("selector") &&
            !l.contains("API") && !l.contains("JSON") && !l.contains("http")
        check("live no-jargon $k", clean)
    }
    // event bus: emit → listener ko milta hai, kram me
    run {
        val seen = mutableListOf<LiveActivity.Event>()
        val l: (LiveActivity.Event) -> Unit = { seen.add(it) }
        LiveActivity.addListener(l)
        try {
            LiveActivity.emitStep("r1", "goto")
            LiveActivity.emitStep("r1", "fill")
            LiveActivity.emitGate("r1", "otp")
            LiveActivity.emitState("r1", LiveActivity.STATE_DONE)
            check("live bus 4 events", seen.size == 4)
            check("live bus step key", seen[0].key == "step:goto")
            check("live bus gate key", seen[2].key == "gate:otp")
            check("live bus runId", seen.all { it.runId == "r1" })
            check("live bus terminal last", LiveActivity.isTerminal(seen[3]))
            check("live lastEvent", LiveActivity.lastEvent()?.key == "state:state_done")
        } finally {
            LiveActivity.removeListener(l)
        }
    }
    // spec 6: indicator message history me PERSIST NAHI hota —
    // LiveActivity me koi storage/persistence code nahi; chat ka
    // showLiveActivity messageList ko chhuta tak nahi.
    run {
        val appDir = System.getProperty("fm.app.dir", ".")
        // Comments hatao — sirf ASLI code me persistence check karo.
        fun codeOnly(src: String): String {
            var s = src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            s = s.lines().filter { !it.trimStart().startsWith("//") }.joinToString("\n")
            return s
        }
        val busSrc = codeOnly(File("$appDir/app/src/main/java/com/formmitra/app/agent/LiveActivity.kt").readText())
        for (w in listOf("SharedPreferences", "openFileOutput", "RunSummaryStore", "messageList", "History", "Room", "SQLite")) {
            check("live no-persist: $w", !busSrc.contains(w))
        }
        val chatSrc = codeOnly(File("$appDir/app/src/main/java/com/formmitra/app/agent/AgentChatView.kt").readText())
        val showFn = chatSrc.substringAfter("private fun showLiveActivity(").substringBefore("\n    private fun ")
        val hideFn = chatSrc.substringAfter("private fun hideLiveActivity(").substringBefore("\n    private fun ")
        for ((nm, fn) in listOf("show" to showFn, "hide" to hideFn)) {
            check(
                "live $nm not a message",
                !fn.contains("messageList") && !fn.contains("addAssistantBubble") &&
                    !fn.contains("addUserBubble") && !fn.contains("RunSummaryStore")
            )
        }
    }

    // ============ v37 future-proofing: origin hash (WorkPatternStore) ============
    // Record me raw device id KABHI nahi — sirf one-way hash.
    val oh1 = WorkPatternStore.originHash("install-uuid-abc-123")
    val oh2 = WorkPatternStore.originHash("install-uuid-abc-123")
    val oh3 = WorkPatternStore.originHash("install-uuid-xyz-999")
    check("origin hash deterministic", oh1 == oh2)
    check("origin hash one-way (raw nahi dikhta)", oh1 != "install-uuid-abc-123" && !oh1.contains("abc"))
    check("origin hash 64 hex chars", oh1.length == 64 && oh1.all { it in '0'..'9' || it in 'a'..'f' })
    check("origin hash unique per install", oh1 != oh3)
    check("scope consts", WorkPatternStore.SCOPE_LOCAL == "local" && WorkPatternStore.SCOPE_GLOBAL == "global")

    // ============ point 10: 1-active-session rule (AgentApi.isActiveRunStatus) ============
    // Active = running / needs_user / queued — server findActiveUserRun ke barabar.
    // Terminal (done/failed/vetoed/cancelled) kabhi active nahi.
    check("active running", AgentApi.isActiveRunStatus("running"))
    check("active needs_user", AgentApi.isActiveRunStatus("needs_user"))
    check("active queued", AgentApi.isActiveRunStatus("queued"))
    check("inactive done", !AgentApi.isActiveRunStatus("done"))
    check("inactive failed", !AgentApi.isActiveRunStatus("failed"))
    check("inactive vetoed", !AgentApi.isActiveRunStatus("vetoed"))
    check("inactive cancelled", !AgentApi.isActiveRunStatus("cancelled"))
    check("inactive empty", !AgentApi.isActiveRunStatus(""))
    // v36 point-10 UX strings — source me maujood (reject + actions).
    run {
        val appDir = System.getProperty("fm.app.dir", ".")
        fun codeOnly(src: String): String {
            var s = src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            s = s.lines().filter { !it.trimStart().startsWith("//") }.joinToString("\n")
            return s
        }
        val frsSrc = codeOnly(File("$appDir/app/src/main/java/com/formmitra/app/engine/FormRunService.kt").readText())
        // v36 point 10: EXACT user-dictated strings (purane "Pehle use poora
        // karo" / "Dekho" hata diye gaye — exact wording user ka order hai).
        check("limit msg present", frsSrc.contains("Pehla kaam poora karo ya band karo, phir naya shuru karo."))
        check("limit actions", frsSrc.contains("\"Chal raha kaam dekho\"") && frsSrc.contains("\"Band karo\""))
        check("limit cancel action", frsSrc.contains("ACTION_CANCEL_ACTIVE"))
        check("fresh start not queued", frsSrc.contains("isUserFreshStart(task)"))
    }

    // ============ v36 catcher: untruncated (sirf secrets masked) ============
    run {
        val appDir = System.getProperty("fm.app.dir", ".")
        fun codeOnly(src: String): String {
            var s = src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            s = s.lines().filter { !it.trimStart().startsWith("//") }.joinToString("\n")
            return s
        }
        val ecSrc = codeOnly(File("$appDir/app/src/main/java/com/formmitra/app/engine/ErrorCatcher.kt").readText())
        check("catcher report no cap", !ecSrc.contains(".take(48_000)") && !ecSrc.contains(".take(20_000)"))
        check("catcher cause no depth cap", !ecSrc.contains("depth < 10"))
        check("catcher cause cycle-safe", ecSrc.contains("IdentityHashMap"))
        // ============ v36: preflight fail = ASLI failure (silent fallback hata) ============
        val loopSrc = codeOnly(File("$appDir/app/src/main/java/com/formmitra/app/engine/AgentLoop.kt").readText())
        check("preflight no silent fallback", !loopSrc.contains("purane step-by-step mode me") && !loopSrc.contains("purane mode me"))
        check("preflight fail loud", loopSrc.contains("Pre-flight plan nahi ban paya"))
        check("preflight fail catcher report", loopSrc.contains("ErrorCatcher.report") && loopSrc.contains("Pre-flight plan"))
        check("preflight standalone exempt", loopSrc.contains("forceStandalone && Standalone.isConfigured(ctx)"))
        // ============ v36: pattern record — URL + proof rules ============
        val wpsSrc = codeOnly(File("$appDir/app/src/main/java/com/formmitra/app/agent/WorkPatternStore.kt").readText())
        check("pattern stores url", wpsSrc.contains(".put(\"url\", recUrl)"))
        check("pattern stores proof_rules", wpsSrc.contains(".put(\"proof_rules\", recProofs)"))
        check("pattern url/proof preserved", wpsSrc.contains("prev?.optString(\"url\"") && wpsSrc.contains("prev?.optJSONArray(\"proof_rules\")"))
        check("pattern url sanitized", wpsSrc.contains("sanitizePatternUrl") && wpsSrc.contains("sanitizePatternUrl(url)"))
        // ============ v36 point 10: exact 1-active strings + owner unlimited ============
        val frsSrc = codeOnly(File("$appDir/app/src/main/java/com/formmitra/app/engine/FormRunService.kt").readText())
        check("1active exact message", frsSrc.contains("Pehla kaam poora karo ya band karo, phir naya shuru karo."))
        check("1active exact view action", frsSrc.contains("\"Chal raha kaam dekho\""))
        check("1active exact stop action", frsSrc.contains("\"Band karo\""))
        check("1active no old label", !frsSrc.contains("\"Dekho\", dekh") && !frsSrc.contains("Pehle use poora karo"))
        check("owner unlimited flag", frsSrc.contains("isOwnerDevice") && frsSrc.contains("setOwnerDevice"))
        check("owner queued not rejected", frsSrc.contains("owner unlimited: fresh start queued"))
        val acvSrc = codeOnly(File("$appDir/app/src/main/java/com/formmitra/app/agent/AgentChatView.kt").readText())
        check("chat rejection card", acvSrc.contains("addOneActiveRejectionCard"))
        check("chat precheck active", acvSrc.contains("FormRunService.activeTaskId"))
        // ============ v36: gate audit completeness (login/document/device_auth) ============
        val pdSrc = codeOnly(File("$appDir/app/src/main/java/com/formmitra/app/agent/PromptDialog.kt").readText())
        check("gate audit login", pdSrc.contains("\"login\", req.title.take(120)"))
        check("gate audit document", pdSrc.contains("\"document\", req.title.take(120)"))
        check("gate audit device_auth", pdSrc.contains("\"device_auth\", req.title.take(120)"))
    }

    println(if (failures == 0) "SELFTESTS-OK" else "SELFTESTS-FAILED: $failures")
}

import com.formmitra.app.engine.AgentActions
import com.formmitra.app.engine.AiUsage
import com.formmitra.app.engine.StepParser
import com.formmitra.app.engine.agentStepSig
import com.formmitra.app.engine.agentStepToSpec
import com.formmitra.app.engine.validateAgentStep
import com.formmitra.app.agent.LearnLogic

// Self-test: AgentLoopLogic (pure Kotlin, no Android).
// Compile: kotlinc FormStepLogic.kt AgentLoopLogic.kt AiUsage.kt LearnLogic.kt SelfTestAgent.kt -d out && java -cp out SelfTestAgentKt

var failures = 0

fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}

fun sel(mode: String, value: String) = mapOf("mode" to mode, "value" to value)

fun main() {
    // ---- 1. validateAgentStep ----
    check(
        "fill ok",
        validateAgentStep(mapOf("action" to "fill", "selector" to sel("label", "Full Name"), "value" to "Ravi")) == null
    )
    check(
        "fill missing selector",
        validateAgentStep(mapOf("action" to "fill", "value" to "Ravi")) == "fill needs selector.value"
    )
    check(
        "fill empty selector value",
        validateAgentStep(mapOf("action" to "fill", "selector" to sel("css", ""), "value" to "x")) == "fill needs selector.value"
    )
    check(
        "fill missing value",
        validateAgentStep(mapOf("action" to "fill", "selector" to sel("css", "#a"))) == "fill needs value"
    )
    check(
        "bad selector mode",
        validateAgentStep(mapOf("action" to "click", "selector" to sel("xpath", "//a"))) == "bad selector mode 'xpath'"
    )
    check(
        "unknown action",
        validateAgentStep(mapOf("action" to "hack")) == "unknown action 'hack'"
    )
    check(
        "missing action",
        validateAgentStep(mapOf("value" to "x")) == "action missing"
    )
    check(
        "low confidence",
        validateAgentStep(mapOf("action" to "click", "selector" to sel("text", "Submit"), "confidence" to 0.2)) == "confidence too low (0.2)"
    )
    check("done terminal ok", validateAgentStep(mapOf("action" to "done")) == null)
    check(
        "needs_user terminal ok",
        validateAgentStep(mapOf("action" to "needs_user", "user_prompt" to "OTP batao")) == null
    )
    check(
        "select needs option",
        validateAgentStep(mapOf("action" to "select", "selector" to sel("name", "state"))) == "select needs option"
    )
    check(
        "goto needs url",
        validateAgentStep(mapOf("action" to "goto")) == "goto needs url"
    )
    check(
        "wait_for_text needs text",
        validateAgentStep(mapOf("action" to "wait_for_text")) == "wait_for_text needs text"
    )
    check(
        "wait_for_navigation ok (no selector needed)",
        validateAgentStep(mapOf("action" to "wait_for_navigation")) == null
    )
    check(
        "press ok",
        validateAgentStep(mapOf("action" to "press", "selector" to sel("css", "input"), "key" to "Enter")) == null
    )

    // ---- 2. agentStepSig (stuck detection) ----
    val a = mapOf("action" to "fill", "selector" to sel("label", "Name"), "value" to "Ravi")
    val b = mapOf("action" to "fill", "selector" to sel("label", "Name"), "value" to "Ravi")
    val c = mapOf("action" to "fill", "selector" to sel("label", "Name"), "value" to "Amit")
    val d = mapOf("action" to "click", "selector" to sel("label", "Name"), "value" to "Ravi")
    check("same sig", agentStepSig(a) == agentStepSig(b))
    check("diff sig on value", agentStepSig(a) != agentStepSig(c))
    check("diff sig on action", agentStepSig(a) != agentStepSig(d))

    // ---- 3. agentStepToSpec + StepParser round-trip ----
    val spec = agentStepToSpec(a)
    check("fill value->text", spec["text"] == "Ravi")
    check("fill type", spec["type"] == "fill")
    val parsed = StepParser.parse(spec)
    check(
        "fill round-trip StepSpec",
        parsed.type == "fill" && parsed.text == "Ravi" &&
            parsed.selectorValue == "Name" && parsed.selectorMode == "label"
    )
    val gspec = agentStepToSpec(mapOf("action" to "goto", "url" to "https://example.gov.in"))
    val gparsed = StepParser.parse(gspec)
    check("goto round-trip", gparsed.type == "goto" && gparsed.url == "https://example.gov.in")
    val pspec = agentStepToSpec(mapOf("action" to "press", "selector" to sel("css", "input")))
    check("press default key", StepParser.parse(pspec).key == "Enter")
    val wspec = agentStepToSpec(mapOf("action" to "wait_for_text", "text" to "success"))
    check("wait_for_text mapping", StepParser.parse(wspec).text == "success")
    try {
        agentStepToSpec(mapOf("action" to "done"))
        check("done has no spec mapping", false)
    } catch (e: IllegalArgumentException) {
        check("done has no spec mapping", true)
    }

    // ---- 4. constants ----
    check("stuck repeats = 3", AgentActions.STUCK_REPEATS == 3)
    check("stuck max = 4", AgentActions.STUCK_MAX == 4)

    // ---- 5. v14: back/forward actions (AI allowlist + spec mapping) ----
    check(
        "back allowed",
        validateAgentStep(mapOf("action" to "back")) == null
    )
    check(
        "forward allowed",
        validateAgentStep(mapOf("action" to "forward")) == null
    )
    val bspec = agentStepToSpec(mapOf("action" to "back"))
    check("back spec mapping", StepParser.parse(bspec).type == "back")
    val fspec = agentStepToSpec(mapOf("action" to "forward"))
    check("forward spec mapping", StepParser.parse(fspec).type == "forward")

    // ---- 6. v21: server-brain contract sync (upload/scroll/back/screenshot/captcha_solve) ----
    check(
        "scroll allowed (selector optional)",
        validateAgentStep(mapOf("action" to "scroll")) == null
    )
    check(
        "scroll with selector allowed",
        validateAgentStep(mapOf("action" to "scroll", "selector" to sel("css", "#more"))) == null
    )
    check(
        "screenshot allowed",
        validateAgentStep(mapOf("action" to "screenshot")) == null
    )
    check(
        "captcha_solve allowed",
        validateAgentStep(mapOf("action" to "captcha_solve")) == null
    )
    check(
        "captcha_detect allowed",
        validateAgentStep(mapOf("action" to "captcha_detect")) == null
    )
    val scspec = agentStepToSpec(mapOf("action" to "scroll", "selector" to sel("css", "#more")))
    check("scroll spec mapping", StepParser.parse(scspec).type == "scroll")
    val shspec = agentStepToSpec(mapOf("action" to "screenshot"))
    check("screenshot spec mapping", StepParser.parse(shspec).type == "screenshot")
    val csspec = agentStepToSpec(mapOf("action" to "captcha_solve"))
    check("captcha_solve spec mapping", StepParser.parse(csspec).type == "captcha_solve")
    val cdspec = agentStepToSpec(mapOf("action" to "captcha_detect"))
    check("captcha_detect spec mapping", StepParser.parse(cdspec).type == "captcha_detect")

    // ---- v24-refine: AiUsage (quota discipline — har AI call ka reason) ----
    AiUsage.reset()
    check("aiusage zero", AiUsage.totalAiCalls() == 0 && AiUsage.totalPatternHits() == 0)
    AiUsage.logAct(AiUsage.R_NEW_STEP)
    AiUsage.logAct(AiUsage.R_NEW_STEP)
    AiUsage.logAct(AiUsage.R_REPLAN_FAIL)
    AiUsage.logVerify(AiUsage.R_FINAL_SUBMIT)
    AiUsage.logPrecheck(AiUsage.R_NEW_TASK)
    AiUsage.logPatternHit(AiUsage.P_CHOICE)
    AiUsage.logPatternHit(AiUsage.P_DETAIL)
    check("aiusage act count", AiUsage.getActCalls() == 3)
    check("aiusage verify count", AiUsage.getVerifyCalls() == 1)
    check("aiusage precheck count", AiUsage.getPrecheckCalls() == 1)
    check("aiusage reason new_step", AiUsage.getReasonCount(AiUsage.R_NEW_STEP) == 2)
    check("aiusage reason replan", AiUsage.getReasonCount(AiUsage.R_REPLAN_FAIL) == 1)
    check("aiusage pattern total", AiUsage.totalPatternHits() == 2)
    check("aiusage pattern kind", AiUsage.getPatternHits(AiUsage.P_CHOICE) == 1)
    val summ = AiUsage.summary()
    check(
        "aiusage summary",
        summ.contains("AI calls: 5") && summ.contains("new_step×2") &&
            summ.contains("pattern-hits: 2")
    )
    AiUsage.reset()
    check("aiusage reset", AiUsage.totalAiCalls() == 0 && AiUsage.totalPatternHits() == 0)

    // ---- v24-refine: LearnLogic (seekho-ek-baar → khud-karo) ----
    check(
        "fieldmap key shape",
        LearnLogic.fieldMapKey("Example.com", "Label", " Phone Number ") ==
            "example.com|label:Phone Number"
    )
    check(
        "drift null when no pattern",
        LearnLogic.fieldMapDriftNote(null, "999", "999") == null
    )
    check(
        "drift null when consistent",
        LearnLogic.fieldMapDriftNote("phone", "9812345678", "9812345678") == null
    )
    check(
        "drift null when empty value",
        LearnLogic.fieldMapDriftNote("phone", "", "9812345678") == null
    )
    val driftNote = LearnLogic.fieldMapDriftNote("phone", "123", "9812345678")
    check(
        "drift note on mismatch",
        driftNote != null && driftNote.contains("mapping_note") && driftNote.contains("phone")
    )
    check(
        "attribute source found",
        LearnLogic.attributeSource(
            "9812345678",
            mapOf("phone" to "9812345678", "name" to "Ravi")
        ) == "phone"
    )
    check(
        "attribute source unknown",
        LearnLogic.attributeSource("xyz", mapOf("phone" to "9812345678")) == null
    )
    check(
        "attribute source empty",
        LearnLogic.attributeSource("", mapOf("phone" to "")) == null
    )
    check(
        "precheck key normalized",
        LearnLogic.precheckCacheKey("HTTPS://X.com/Apply ", "Track Zamin") ==
            "https://x.com/apply|track zamin"
    )
    val nowMs = 1_700_000_000_000L
    check("cache fresh", LearnLogic.isCacheFresh(nowMs - 1000, nowMs))
    check("cache stale 25h", !LearnLogic.isCacheFresh(nowMs - 25 * 60 * 60 * 1000L, nowMs))
    check("cache zero", !LearnLogic.isCacheFresh(0, nowMs))

    if (failures > 0) {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    } else {
        println("ALL PASS")
    }
}

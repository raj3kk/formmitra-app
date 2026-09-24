import com.formmitra.app.engine.AgentActions
import com.formmitra.app.engine.StepParser
import com.formmitra.app.engine.agentStepSig
import com.formmitra.app.engine.agentStepToSpec
import com.formmitra.app.engine.validateAgentStep

// Self-test: AgentLoopLogic (pure Kotlin, no Android).
// Compile: kotlinc FormStepLogic.kt AgentLoopLogic.kt SelfTestAgent.kt -d out && java -cp out SelfTestAgentKt

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

    if (failures > 0) {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    } else {
        println("ALL PASS")
    }
}

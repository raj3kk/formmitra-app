import com.formmitra.app.engine.AgentActions
import com.formmitra.app.engine.StepParser
import com.formmitra.app.engine.ServerKinds
import com.formmitra.app.engine.PlanDetails
import com.formmitra.app.engine.PinActions
import com.formmitra.app.engine.OperatorCommands
import com.formmitra.app.engine.ServerEvents
import com.formmitra.app.engine.CardEndpoints
import com.formmitra.app.engine.agentStepToSpec

// CONTRACT SYNC selftest (server authoritative vocabulary, 2026-09-26).
// Har naya server action/kind yahan PINNED count me — server badle to
// ye test FAIL hoga (v21 wali "unknown action" galti dobara nahi).

var pass = 0
var fail = 0
fun check(name: String, cond: Boolean) {
    if (cond) { pass++; println("PASS $name") }
    else { fail++; println("FAIL $name") }
}

fun main() {
    // ---------- A. Brain act actions (server act.ts) ----------
    val serverActions = setOf(
        "fill", "select", "toggle", "press", "click", "upload", "scroll",
        "back", "screenshot", "captcha_solve", "set_desktop", "research",
        "goto", "wait_for_text", "wait_for_element", "wait_for_navigation",
        "done", "needs_user"
    )
    check("actions_server_count_18", serverActions.size == 18)
    for (a in serverActions) {
        check("action_whitelisted_$a", a in AgentActions.ALL)
    }
    // Pinned: 18 server + 4 app-internal (forward, captcha_detect,
    // verify_submit, vetoed) = 22.
    check("actions_all_pinned_22", AgentActions.ALL.size == 22)
    // Terminal actions execute nahi hote.
    check("terminal_done", "done" in AgentActions.TERMINAL)
    check("terminal_needs_user", "needs_user" in AgentActions.TERMINAL)
    check("terminal_vetoed", "vetoed" in AgentActions.TERMINAL)
    check("terminal_count_3", AgentActions.TERMINAL.size == 3)

    // ---------- TO_SPEC_TYPE: har non-terminal ka mapping ----------
    val nonTerminal = AgentActions.ALL - AgentActions.TERMINAL
    check("specmap_count_19", AgentActions.TO_SPEC_TYPE.size == 19)
    for (a in nonTerminal) {
        check("specmap_has_$a", a in AgentActions.TO_SPEC_TYPE)
    }
    // Spot: verify_submit click ki tarah execute hota hai.
    check("specmap_verify_submit_click",
        AgentActions.TO_SPEC_TYPE["verify_submit"] == "click")

    // ---------- StepParser.TYPES: har spec type ka executor type ----------
    check("steptypes_pinned_19", StepParser.TYPES.size == 19)
    for (t in AgentActions.TO_SPEC_TYPE.values.toSet()) {
        check("steptype_has_$t", t in StepParser.TYPES)
    }

    // ---------- agentStepToSpec: end-to-end mapping ----------
    val spec = agentStepToSpec(
        mapOf(
            "action" to "fill",
            "selector" to mapOf("mode" to "label", "value" to "Name"),
            "value" to "Raj"
        )
    )
    check("spec_fill_type", spec["type"] == "fill")
    check("spec_fill_text", spec["text"] == "Raj")
    val specResearch = agentStepToSpec(
        mapOf("action" to "research", "research_query" to "passport status")
    )
    check("spec_research", specResearch["type"] == "research" &&
        specResearch["text"] == "passport status")

    // ---------- B. needs_user kinds ----------
    check("kinds_count_6", ServerKinds.NEEDS_USER_KINDS.size == 6)
    for (k in listOf("otp", "login", "payment", "option_choice",
        "destructive_confirm", "input")) {
        check("kind_valid_$k", ServerKinds.isValid(k))
    }
    // Server "choice" bhejta tha → canonical "option_choice".
    check("canon_choice", ServerKinds.canonicalize("choice") == "option_choice")
    check("canon_OPTION", ServerKinds.canonicalize("OPTION") == "option_choice")
    check("canon_destructive",
        ServerKinds.canonicalize("destructive_confirm") == "destructive_confirm")
    check("canon_bogus_null", ServerKinds.canonicalize("bogus") == null)
    check("canon_empty_null", ServerKinds.canonicalize("") == null)
    check("canon_null_null", ServerKinds.canonicalize(null) == null)
    // App-internal kinds bhi valid (loop khud use karta hai).
    check("canon_document", ServerKinds.canonicalize("document") == "document")
    check("canon_device_auth",
        ServerKinds.canonicalize("device_auth") == "device_auth")

    // ---------- C. Plan fields: details_needed / blocking_details ----------
    val plan = mapOf<String, Any?>(
        "details_needed" to listOf("full_name", "mobile_number", "dob"),
        "blocking_details" to "mobile_number, dob"
    )
    check("plan_needed",
        PlanDetails.neededKeys(plan) == listOf("full_name", "mobile_number", "dob"))
    check("plan_blocking",
        PlanDetails.blockingKeys(plan) == listOf("mobile_number", "dob"))
    check("plan_empty", PlanDetails.neededKeys(emptyMap()).isEmpty())
    val (known, missing) = PlanDetails.partition(
        listOf("full_name", "mobile_number", "dob"),
        mapOf("full_name" to "Raj Kumar")
    )
    check("plan_partition_known", known == listOf("full_name"))
    check("plan_partition_missing",
        missing == listOf("mobile_number", "dob"))

    // ---------- F. PIN actions ----------
    check("pin_count_4", PinActions.ALL.size == 4)
    check("pin_setup", PinActions.SETUP == "pin_setup")
    check("pin_change", PinActions.CHANGE == "pin_change")
    check("pin_reset_req", PinActions.RESET_REQUEST == "pin_reset_request")
    check("pin_reset_confirm", PinActions.RESET_CONFIRM == "pin_reset_confirm")

    // ---------- G. Operator commands ----------
    check("opcmd_count_12", OperatorCommands.ALL.size == 12)
    for (c in listOf("tap", "scroll", "swipe", "type", "fill", "select",
        "back", "forward", "reload", "screenshot", "set_desktop",
        "captcha_request")) {
        check("opcmd_valid_$c", OperatorCommands.isValid(c))
    }
    check("opcmd_invalid", !OperatorCommands.isValid("hack"))
    check("opcmd_case_insensitive", OperatorCommands.isValid("TAP"))

    // ---------- E. Server events ----------
    check("events_count_2", ServerEvents.ALL.size == 2)
    check("event_action_offer", ServerEvents.ACTION_OFFER == "action_offer")
    check("event_unlock",
        ServerEvents.CARD_UNLOCK_NEEDED == "card_unlock_needed")

    // ---------- H. REST endpoints ----------
    check("ep_lock", CardEndpoints.lock("abc123") == "/api/cards/abc123/lock")
    check("ep_unlock",
        CardEndpoints.unlock("abc123") == "/api/cards/abc123/unlock")
    check("ep_lockall", CardEndpoints.LOCK_ALL == "/api/cards/lock-all")
    check("ep_opcmd", CardEndpoints.OP_COMMAND == "/api/agent/operator/command")
    check("ep_opstate", CardEndpoints.OP_STATE == "/api/agent/operator/state")

    println("CONTRACT_DONE pass=$pass fail=$fail")
}

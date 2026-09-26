package com.formmitra.app.engine

/**
 * AgentLoopLogic — AI agent loop ka pure-Kotlin hissa (ZERO Android imports).
 *
 * FormStepLogic.kt wale pattern pe: ye file JVM pe kotlinc se seedha compile
 * hoti hai taaki self-test (validate, stuck-signature, step-spec mapping)
 * real phone ke bina verify ho sake. AgentLoop (Android side) isi ko use
 * karta hai — logic yahan single source of truth hai.
 *
 * Server contract (/api/agent/act response ka "step"):
 *   {action, selector{mode,value}, value, option, url, key, text,
 *    confidence, reason, requires_user, blocked_reason, user_prompt,
 *    result_summary}
 */
object AgentActions {
    /** Server se aane wale allowed actions (contract whitelist).
     *  v21: server brain ab scroll/screenshot/captcha_* bhi bhej sakta hai
     *  (BRAIN_ALLOWED_ACTIONS) — ye set usi se sync hai. */
    val ALL = setOf(
        "fill", "select", "toggle", "press", "click", "goto",
        "wait_for_text", "wait_for_element", "wait_for_navigation",
        "back", "forward",
        "upload", "scroll", "screenshot", "captcha_detect", "captcha_solve",
        // v24 C15: final submit — click se pehle AI image-verification
        // (POST /api/agent/verify) ke baad hi execute hota hai.
        "verify_submit",
        // v31: server brain ke naye actions — contract sync (server act.ts).
        "set_desktop", "research",
        "done", "needs_user", "vetoed"
    )
    /** Terminal actions — execute nahi hote, loop finish karte hain. */
    val TERMINAL = setOf("done", "needs_user", "vetoed")
    /** Jin actions ke liye selector.value zaroori hai. */
    val NEEDS_SELECTOR = setOf(
        "fill", "select", "toggle", "press", "click", "wait_for_element",
        "verify_submit"
    )
    /** FormEngine ke supported selector modes (finderJs wala set). */
    val SELECTOR_MODES = setOf(
        "css", "id", "name", "label", "placeholder", "text", "aria", "hint"
    )
    /** Server action -> StepSpec type (1:1 mapping). */
    val TO_SPEC_TYPE = mapOf(
        "fill" to "fill",
        "select" to "select",
        "toggle" to "toggle",
        "press" to "press",
        "click" to "click",
        "goto" to "goto",
        "wait_for_text" to "wait_for_text",
        "wait_for_element" to "wait_for_element",
        "wait_for_navigation" to "wait_for_navigation",
        "back" to "back",
        "forward" to "forward",
        "upload" to "upload",
        "scroll" to "scroll",
        "screenshot" to "screenshot",
        "captcha_detect" to "captcha_detect",
        "captcha_solve" to "captcha_solve",
        // v24 C15: verify_submit execute hota hai click ki tarah — par
        // usse PEHLE AgentLoop me AI image-verification hoti hai.
        "verify_submit" to "click",
        // v31: contract sync — executor FormEngine.executeStep me hai.
        "set_desktop" to "set_desktop",
        "research" to "research"
    )

    /**
     * v24 C15 — SubmitIntent: ye step final submit hai ya nahi?
     *
     * Server explicit bhej sakta hai: action="verify_submit".
     * Safety net: click/press step jiske reason/label/text/selector me
     * final-submit jaisa strong hint ho (submit application, place order,
     * pay now...). Akele "submit" shabd par trigger NAHI (search-form
     * jaise false positive se bachne ke liye) — wahan server ko explicit
     * verify_submit bhejna chahiye.
     */
    object SubmitIntent {
        private val HINTS = listOf(
            "final submit", "submit application", "submit form",
            "place order", "pay now", "confirm payment", "proceed to pay",
            "make payment", "confirm booking", "confirm and pay",
            "book now", "apply now", "complete application"
        )

        @Suppress("UNCHECKED_CAST")
        fun shouldVerify(step: Map<String, Any?>): Boolean {
            val action = (step["action"] as? String)?.trim() ?: ""
            if (action == "verify_submit") return true
            if (action != "click" && action != "press") return false
            val sel = step["selector"] as? Map<String, Any?>
            val blob = buildString {
                append((step["reason"] as? String) ?: "")
                append(' ')
                append((step["label"] as? String) ?: "")
                append(' ')
                append((step["text"] as? String) ?: "")
                append(' ')
                append((sel?.get("value") as? String) ?: "")
            }.lowercase()
            return HINTS.any { it in blob }
        }
    }
    /** Confidence isse kam ho to step reject (andha action nahi chalega). */
    const val MIN_CONFIDENCE = 0.55
    /** Lagatar itne repeat signatures = stuck. */
    const val STUCK_REPEATS = 3
    /** stuck_count itna ho to user ko handoff. */
    const val STUCK_MAX = 4
}

/**
 * Server step validate karo. null = OK, String = reject reason.
 * Client-side whitelist — server misbehave kare tab bhi unsafe ya bekaar
 * action execute nahi hoga. Payment veto iske baad FormEngine me lagta hai.
 */
@Suppress("UNCHECKED_CAST")
fun validateAgentStep(step: Map<String, Any?>): String? {
    val action = (step["action"] as? String)?.trim() ?: ""
    if (action.isEmpty()) return "action missing"
    if (action !in AgentActions.ALL) return "unknown action '$action'"
    if (action in AgentActions.TERMINAL) return null // execute nahi hota
    if (action in AgentActions.NEEDS_SELECTOR) {
        val sel = step["selector"] as? Map<String, Any?>
        val v = (sel?.get("value") as? String)?.trim() ?: ""
        if (v.isEmpty()) return "$action needs selector.value"
        val mode = ((sel?.get("mode") as? String)?.trim()?.ifEmpty { "css" }) ?: "css"
        if (mode !in AgentActions.SELECTOR_MODES) return "bad selector mode '$mode'"
    }
    when (action) {
        "fill" ->
            if ((step["value"] as? String).isNullOrEmpty()) return "fill needs value"
        "select" ->
            if ((step["option"] as? String).isNullOrEmpty()) return "select needs option"
        "goto" ->
            if ((step["url"] as? String).isNullOrEmpty()) return "goto needs url"
        "wait_for_text" ->
            if ((step["text"] as? String).isNullOrEmpty()) return "wait_for_text needs text"
        "upload" ->
            if ((step["doc"] as? String).isNullOrEmpty() &&
                (step["path"] as? String).isNullOrEmpty()
            ) return "upload needs doc or path"
        // v31: research bina query ke bekaar hai.
        "research" ->
            if ((step["research_query"] as? String).isNullOrEmpty()) return "research needs research_query"
    }
    val conf = (step["confidence"] as? Number)?.toDouble() ?: 1.0
    if (conf < AgentActions.MIN_CONFIDENCE) return "confidence too low ($conf)"
    return null
}

/** Stuck detection ke liye step signature: action|mode:value|value|option|url */@Suppress("UNCHECKED_CAST")
fun agentStepSig(step: Map<String, Any?>): String {
    val sel = step["selector"] as? Map<String, Any?>
    val mode = (sel?.get("mode") as? String) ?: ""
    val sval = (sel?.get("value") as? String) ?: ""
    return listOf(
        (step["action"] as? String) ?: "",
        "$mode:$sval",
        (step["value"] as? String) ?: "",
        (step["option"] as? String) ?: "",
        (step["url"] as? String) ?: ""
    ).joinToString("|")
}

/**
 * Server step -> FormEngine StepSpec map (StepParser.parse ke liye).
 * NOTE: StepSpec ka "text" field fill ka value hota hai; server "value"
 * bhejta hai — mapping yahin hota hai.
 */
@Suppress("UNCHECKED_CAST")
fun agentStepToSpec(step: Map<String, Any?>): Map<String, Any?> {
    val action = (step["action"] as? String)?.trim() ?: ""
    val specType = AgentActions.TO_SPEC_TYPE[action]
        ?: throw IllegalArgumentException("no spec mapping for action '$action'")
    val out = HashMap<String, Any?>()
    out["type"] = specType
    val sel = step["selector"] as? Map<String, Any?>
    if (sel != null) {
        out["selector"] = mapOf(
            "mode" to (((sel["mode"] as? String)?.ifEmpty { "css" }) ?: "css"),
            "value" to (sel["value"] as? String ?: "")
        )
    }
    when (action) {
        "fill" -> out["text"] = step["value"] as? String ?: ""
        "wait_for_text" -> out["text"] = step["text"] as? String ?: ""
        "select" -> out["option"] = step["option"] as? String ?: ""
        "press" -> out["key"] = ((step["key"] as? String)?.ifEmpty { "Enter" } ?: "Enter")
        "goto" -> out["url"] = step["url"] as? String ?: ""
        "upload" -> {
            out["doc"] = step["doc"] as? String ?: ""
            out["path"] = step["path"] as? String ?: ""
        }
        // v31: set_desktop ka desktop_enabled ("true"/"false") StepSpec.state
        // me jata hai; research ki query text me.
        "set_desktop" -> out["state"] =
            ((step["desktop_enabled"] as? String)?.ifEmpty { "true" } ?: "true")
        "research" -> out["text"] = step["research_query"] as? String ?: ""
    }
    return out
}

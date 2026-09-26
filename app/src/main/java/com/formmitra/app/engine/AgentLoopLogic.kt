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
     *  AUTHORITATIVE (server act.ts, 2026-09-26 sync):
     *    fill, select, toggle, press, click, upload, scroll, back,
     *    screenshot, captcha_solve, set_desktop, research, goto,
     *    wait_for_text, wait_for_element, wait_for_navigation,
     *    done, needs_user
     *  App-internal extensions (server kabhi nahi bhejta, loop khud
     *  use karta hai — whitelist me rehna safe hai):
     *    forward, captcha_detect, verify_submit, vetoed
     */
    val ALL = setOf(
        "fill", "select", "toggle", "press", "click", "upload", "scroll",
        "back", "screenshot", "captcha_solve", "set_desktop", "research",
        "goto", "wait_for_text", "wait_for_element", "wait_for_navigation",
        "done", "needs_user",
        // App-internal extensions (neeche dekho).
        "forward", "captcha_detect", "verify_submit", "vetoed"
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
 * ServerKinds — needs_user ke kinds (AUTHORITATIVE, server 2026-09-26).
 * Server "choice" ko "option_choice" me canonicalize karta hai — app
 * dono accept kare (purana server "choice" bheje to bhi chale).
 */
object ServerKinds {
    const val OTP = "otp"
    const val LOGIN = "login"
    const val PAYMENT = "payment"
    const val OPTION_CHOICE = "option_choice"
    const val DESTRUCTIVE_CONFIRM = "destructive_confirm"
    const val INPUT = "input"

    /** needs_user ke saare valid kinds. */
    val NEEDS_USER_KINDS = setOf(
        OTP, LOGIN, PAYMENT, OPTION_CHOICE, DESTRUCTIVE_CONFIRM, INPUT
    )

    /**
     * Kind canonicalize karo: "choice" → "option_choice" (server bhi
     * yehi karta hai). GateLogic.normKind ke choice aliases
     * (option/options/select_option) bhi chalte hain — yahan inline hai
     * taaki ye file selftest me akele compile ho sake (GateLogic par
     * dependency nahi). Unknown kind → null (caller reject karega).
     */
    fun canonicalize(raw: String?): String? {
        val k = raw?.trim()?.ifEmpty { null } ?: return null
        val lower = k.lowercase()
        // choice family → option_choice (server canonicalization).
        if (lower == "choice" || lower == "option" || lower == "options" ||
            lower == "select_option"
        ) return OPTION_CHOICE
        // App-internal kinds (server nahi bhejta, loop khud use karta hai).
        if (lower == "document" || lower == "device_auth" ||
            lower == "card_unlock" || lower == "card_unlock_needed"
        ) return lower
        return if (lower in NEEDS_USER_KINDS) lower else null
    }

    /** Kya ye valid needs_user kind hai (canonical form me)? */
    fun isValid(kind: String?): Boolean = canonicalize(kind) != null
}

/**
 * PlanDetails — plan ke detail fields (AUTHORITATIVE, server 2026-09-26).
 * Plan me: details_needed (canonical detail keys ki list),
 * blocking_details (wo keys jinke bina kaam aage nahi badhega).
 */
object PlanDetails {
    /**
     * Plan map se keys nikalo. List<String> ya comma-string dono chalenge.
     */
    fun keysOf(plan: Map<String, Any?>, field: String): List<String> {
        val raw = plan[field] ?: return emptyList()
        return when (raw) {
            is List<*> -> raw.mapNotNull { (it as? String)?.trim() }
                .filter { it.isNotEmpty() }
            is String -> raw.split(",").map { it.trim() }
                .filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    fun neededKeys(plan: Map<String, Any?>): List<String> =
        keysOf(plan, "details_needed")

    fun blockingKeys(plan: Map<String, Any?>): List<String> =
        keysOf(plan, "blocking_details")

    /**
     * Keys ko baanto: pehle se pata (known map me non-empty) vs missing.
     * @return (knownKeys, missingKeys)
     */
    fun partition(
        keys: List<String>,
        known: Map<String, String>
    ): Pair<List<String>, List<String>> {
        val knownKeys = ArrayList<String>()
        val missing = ArrayList<String>()
        for (k in keys) {
            if (known[k].orEmpty().isNotEmpty()) knownKeys.add(k)
            else missing.add(k)
        }
        return knownKeys to missing
    }
}

/**
 * PinActions — PIN lifecycle actions (AUTHORITATIVE, server 2026-09-26).
 */
object PinActions {
    const val SETUP = "pin_setup"
    const val CHANGE = "pin_change"
    const val RESET_REQUEST = "pin_reset_request"
    const val RESET_CONFIRM = "pin_reset_confirm"

    val ALL = setOf(SETUP, CHANGE, RESET_REQUEST, RESET_CONFIRM)
}

/**
 * OperatorCommands — /api/agent/operator/command ke commands
 * (AUTHORITATIVE, server 2026-09-26).
 */
object OperatorCommands {
    val ALL = setOf(
        "tap", "scroll", "swipe", "type", "fill", "select",
        "back", "forward", "reload", "screenshot", "set_desktop",
        "captcha_request"
    )

    fun isValid(cmd: String?): Boolean =
        cmd?.trim()?.lowercase() in ALL
}

/**
 * ServerEvents — server→app broadcast events (AUTHORITATIVE, 2026-09-26).
 */
object ServerEvents {
    const val ACTION_OFFER = "action_offer"
    const val CARD_UNLOCK_NEEDED = "card_unlock_needed"

    val ALL = setOf(ACTION_OFFER, CARD_UNLOCK_NEEDED)
}

/**
 * CardEndpoints — card/operator REST endpoints (AUTHORITATIVE, 2026-09-26).
 * {id} ko actual card id se replace karo.
 */
object CardEndpoints {
    const val LOCK = "/api/cards/{id}/lock"
    const val LOCK_ALL = "/api/cards/lock-all"
    const val UNLOCK = "/api/cards/{id}/unlock"
    const val OP_COMMAND = "/api/agent/operator/command"
    const val OP_STATE = "/api/agent/operator/state"

    fun lock(cardId: String): String = LOCK.replace("{id}", cardId)
    fun unlock(cardId: String): String = UNLOCK.replace("{id}", cardId)
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

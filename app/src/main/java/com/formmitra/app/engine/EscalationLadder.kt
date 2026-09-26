package com.formmitra.app.engine

import org.json.JSONObject

/**
 * EscalationLadder — v36 formal escalation ladder (PURE Kotlin, self-testable).
 *
 * User ka order: "AI, agent, operator smartly coordination use ho."
 * Har kaam isi seedhi par chadhta hai — level skip nahi hota:
 *
 *   1. pattern        — learned work-pattern replay (ZERO AI call)
 *   2. agent_plan     — agent pre-flight plan khud follow karta hai
 *   3. ai_single_step — ek failed step ke liye AI se sirf us step ki help
 *   4. user_gate      — user se pucho (OTP/login/payment/choice/correction)
 *
 * Har transition audit hoti hai: from → to, failed step, reason, timestamp,
 * pattern invalidated hua ya nahi. Audit steps-log me jati hai.
 */
object EscalationLadder {

    /** Learned work-pattern replay — zero AI. */
    const val L_PATTERN = "pattern"

    /** Agent pre-flight plan khud follow kar raha hai. */
    const val L_AGENT = "agent_plan"

    /** Ek failed step ke liye AI se single-step help. */
    const val L_AI_STEP = "ai_single_step"

    /** User gate — OTP/login/payment/choice/correction. */
    const val L_USER = "user_gate"

    private val ORDER = listOf(L_PATTERN, L_AGENT, L_AI_STEP, L_USER)

    fun isValid(level: String): Boolean = level in ORDER

    /**
     * Agla level. Last level (user_gate) par wahi rehta hai — uske upar
     * kuch nahi (user hi final authority hai).
     */
    fun next(level: String): String {
        val i = ORDER.indexOf(level)
        return if (i < 0) L_PATTERN else ORDER[minOf(i + 1, ORDER.size - 1)]
    }

    /** Kya ye level AI call karta hai? (pattern aur user_gate nahi karte) */
    fun usesAi(level: String): Boolean = level == L_AGENT || level == L_AI_STEP

    /**
     * Audit entry — steps-log me jati hai.
     * @param fromLevel pichla level, @param toLevel naya level
     * @param failedStep kaun sa step fail hua ("" = n/a)
     * @param reason Hinglish me wajah
     * @param patternInvalidated kya learned pattern invalid hua
     */
    fun auditEntry(
        fromLevel: String,
        toLevel: String,
        failedStep: String,
        reason: String,
        patternInvalidated: Boolean = false
    ): JSONObject = JSONObject()
        .put("type", "escalation")
        .put("from", fromLevel)
        .put("to", toLevel)
        .put("failed_step", failedStep)
        .put("reason", reason)
        .put("pattern_invalidated", patternInvalidated)
        .put("at", System.currentTimeMillis())

    /** Ladder ka Hinglish naam — user ko dikhane ke liye. */
    fun hinglishName(level: String): String = when (level) {
        L_PATTERN -> "seekha hua tareeka"
        L_AGENT -> "agent khud"
        L_AI_STEP -> "AI se ek step ki help"
        L_USER -> "aapki madad"
        else -> level
    }
}

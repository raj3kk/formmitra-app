package com.formmitra.app.agent

/**
 * LearnLogic — v24-refine learned-pattern PURE logic (koi Android import
 * nahi → self-test me seedha compile hota hai).
 *
 * User: "AI ek baar bata de, phir wahi situation me khud se kare; jab tak
 * khud se na ho tab AI se help le."
 *
 * Ye file us behavior ke pure faisle rakhti hai:
 *  - pattern key banana (kaun se signals → kaun sa pattern)
 *  - field-mapping drift pakadna (galat mapping → AI se correct karwao)
 *  - value ka source pehchanna (kahan se aaya — card/user/detail)
 *  - precheck cache key + freshness (quota bachat)
 *
 * Storage (SharedPreferences) FieldMapMemory / PrecheckCache me hai —
 * ye sirf faisle hain. Alag parallel memory system NAHI: yehi logic
 * existing stores (ChoiceMemory-shape) ke upar baithta hai.
 */
object LearnLogic {

    /**
     * Field-mapping pattern key: kaun se signals → kaun sa pattern.
     * domain + selector (mode:value) — AI ne is field me is source ka
     * value bhara tha aur fill VERIFY hua tha.
     */
    fun fieldMapKey(domain: String, selectorMode: String, selectorValue: String): String =
        "${domain.trim().lowercase()}|" +
            "${selectorMode.trim().lowercase()}:" +
            selectorValue.trim().take(200)

    /**
     * Mapping drift check.
     * @param learnedSrc pehle seekha source (null = koi pattern nahi — nayi situation)
     * @param curValue brain ab jo value bharna chahta hai
     * @param srcValue learned source ka AAJ ka value
     * @return null = consistent/unknown (aage badho); String = brain ke liye
     *         mapping-note (history me jayega — AI khud correct karega)
     */
    fun fieldMapDriftNote(
        learnedSrc: String?,
        curValue: String,
        srcValue: String
    ): String? {
        if (learnedSrc.isNullOrEmpty()) return null // nayi situation — rokna nahi
        if (curValue.isEmpty()) return null
        if (srcValue.isNotEmpty() && curValue == srcValue) return null // consistent
        // Drift: pehle is field me learnedSrc jata tha, ab alag value —
        // galat mapping ho sakti hai → AI se correct karwao (block nahi,
        // brain faisla karega — value badalna user ka iraada bhi ho sakta hai).
        return "mapping_note: is field me pehle '$learnedSrc' ka value jata tha " +
            "(seekha hua pattern), ab alag value aa rahi hai — mapping dobara " +
            "confirm karo; galat field me mat bharo"
    }

    /**
     * Bhare gaye value ka source pehchano (kahan se aaya).
     * @return source key ya null (pata nahi — pattern nahi seekhenge)
     */
    fun attributeSource(
        value: String,
        sources: Map<String, String>
    ): String? {
        if (value.isEmpty()) return null
        for ((k, v) in sources) {
            if (v.isNotEmpty() && v == value) return k
        }
        return null
    }

    /** Precheck cache key: (url, goal) → ek hi task dobara. */
    fun precheckCacheKey(url: String, goal: String): String =
        url.trim().lowercase() + "|" + goal.trim().lowercase().take(120)

    /** 24h TTL — purana verdict dobara istemal nahi. */
    fun isCacheFresh(savedAt: Long, now: Long, ttlMs: Long = 24 * 60 * 60 * 1000L): Boolean =
        savedAt > 0 && savedAt <= now && now - savedAt < ttlMs

    // ================= v36: complete WORKFLOW patterns =================
    // (User: "jo ek baar jaan gaya dobara AI se na puche.")
    // Field-level patterns ke upar — poori site+task ka workflow.

    /**
     * Workflow pattern key: normalized goal + domain.
     * Same site + same task = same key (goal ke extra shabdon se key nahi
     * badalti — normalizeGoal chhote farq mita deta hai).
     */
    fun workPatternKey(goal: String, domain: String): String =
        normalizeGoal(goal) + "@" + domain.trim().lowercase().take(120)

    /**
     * Goal normalize: lowercase, extra space/punctuation saaf, 120 chars.
     * "Caste certificate apply karo" aur "caste certificate apply" ek jaise.
     *
     * v36 root-fix (selftest "pattern key normalized"): vinamra aagya-shabd
     * ("karo", "please" jaise) key nahi badalte — ye "extra shabd" hain,
     * task nahi. Iske bina "X karo" aur "X" alag pattern bante aur
     * zero-AI replay kabhi hit nahi karta.
     */
    fun normalizeGoal(goal: String): String =
        goal.trim().lowercase()
            .replace(Regex("[^a-z0-9\\u0900-\\u097F ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotEmpty() && it !in GOAL_STOP_WORDS }
            .joinToString(" ")
            .take(120)

    /** Aagya/vinamrata ke shabd — pattern key me mayne nahi rakhte. */
    private val GOAL_STOP_WORDS = setOf(
        "karo", "karein", "kijiye", "kije", "please", "kripya"
    )

    /**
     * Pattern confidence: 0.0 (invalid) .. 1.0 (poora bharosa).
     *  - fail >= 2 → 0.0 (invalid — WorkPatternStore ise delete karta hai)
     *  - fail == 1 → 0.5 (ek mauka aur)
     *  - success >= 3, fail == 0 → 1.0 (pakka pattern)
     *  - success >= 1, fail == 0 → 0.8 (seekha hua, aur verify hoga)
     */
    fun patternConfidence(success: Int, fail: Int): Double = when {
        fail >= 2 -> 0.0
        fail == 1 -> 0.5
        success >= 3 -> 1.0
        success >= 1 -> 0.8
        else -> 0.0
    }

    /**
     * Kya ye pattern abhi replay karna chahiye (ZERO AI)?
     *  - confidence >= 0.8
     *  - fail == 0
     *  - last verified 30 din ke andar (purana pattern stale ho sakta hai)
     */
    fun shouldReplay(
        success: Int,
        fail: Int,
        updatedAt: Long,
        now: Long,
        maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000
    ): Boolean {
        if (patternConfidence(success, fail) < 0.8) return false
        if (fail != 0) return false
        if (updatedAt <= 0 || updatedAt > now) return false
        if (now - updatedAt > maxAgeMs) return false
        return true
    }

    // ---- v36 refine point 2: per-step stuck escalation ----
    /** Ek step fail ho to aage kya: 0=continue, 1=AI single-step help, 2=user gate. */
    const val STEP_OK = 0
    const val STEP_AI_HELP = 1
    const val STEP_USER_GATE = 2

    /**
     * Same step N baar fail → escalation level.
     * 2 fails → AI se single-step help; 3 fails → user ko saaf batao.
     * Infinite retry loop KABHI nahi (loop ka maxSteps + STUCK_MAX backstop
     * alag se hain).
     */
    fun stepEscalation(failCount: Int): Int = when {
        failCount >= 3 -> STEP_USER_GATE
        failCount >= 2 -> STEP_AI_HELP
        else -> STEP_OK
    }
}

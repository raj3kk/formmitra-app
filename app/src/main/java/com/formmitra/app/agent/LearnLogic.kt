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
}

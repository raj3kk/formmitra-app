package com.formmitra.app.engine

/**
 * AiUsage — v24-refine AI quota discipline (PURE Kotlin, self-testable).
 *
 * User ka guardrail: "AI ki help limit me ho, unnecessary AI use nahi —
 * token khatm ho jayega."
 *
 * Har AI call ka REASON logged hota hai (nayi situation? ataki? complex
 * judgment?) taaki debugging me pata chale call KYUN hua. Learned-pattern
 * hits alag gine jate hain — wo AI calls BACHATE hain (zero AI call).
 *
 * Server limits (fixed): chat/profile 20/day, act/captcha 300/day/user,
 * owner unlimited. Ye client-side hisaab hai — server apna limit khud
 * lagata hai (429 par client crash nahi, saaf Hinglish message).
 *
 * NOTE: run-scoped — AgentLoop.runAgentTask shuru me reset() hota hai.
 */
object AiUsage {
    // ---- AI-call reasons ----
    /** Nayi situation: pehli baar step plan maanga. */
    const val R_NEW_STEP = "new_step"
    /** Ataki situation: pichla step fail → dobara plan. */
    const val R_REPLAN_FAIL = "replan_after_fail"
    /** Stale plan: AI ka target page par nahi mila → dobara plan. */
    const val R_REPLAN_STALE = "replan_after_stale"
    /** Repeated failure: stuck → dobara koshish. */
    const val R_STUCK_RETRY = "stuck_retry"
    /** Complex judgment: final submit se pehle image-verification. */
    const val R_FINAL_SUBMIT = "final_submit"
    /** Ataki situation: stuck-diagnosis (verify). */
    const val R_STUCK_DIAGNOSIS = "stuck_diagnosis"
    /** Naya task: precheck (heuristic, par 20/day budget me). */
    const val R_NEW_TASK = "new_task"

    // ---- learned-pattern hit kinds (AI call BACHI) ----
    const val P_CHOICE = "choice_memory"
    const val P_LOGIN = "login_store"
    const val P_DOC = "doc_autopick"
    const val P_DETAIL = "detail_autofill"
    const val P_PRECHECK_CACHE = "precheck_cache"
    const val P_FIELDMAP = "fieldmap_consistent"

    @Volatile private var actCalls = 0
    @Volatile private var verifyCalls = 0
    @Volatile private var precheckCalls = 0
    private val reasonCounts = LinkedHashMap<String, Int>()
    private val patternHits = LinkedHashMap<String, Int>()

    @Synchronized fun reset() {
        actCalls = 0
        verifyCalls = 0
        precheckCalls = 0
        reasonCounts.clear()
        patternHits.clear()
    }

    @Synchronized fun logAct(reason: String) {
        actCalls++
        reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
    }

    @Synchronized fun logVerify(reason: String) {
        verifyCalls++
        reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
    }

    @Synchronized fun logPrecheck(reason: String) {
        precheckCalls++
        reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
    }

    /** Learned pattern match → kaam bina AI ke hua (quota bachat). */
    @Synchronized fun logPatternHit(kind: String) {
        patternHits[kind] = (patternHits[kind] ?: 0) + 1
    }

    @Synchronized fun getActCalls(): Int = actCalls
    @Synchronized fun getVerifyCalls(): Int = verifyCalls
    @Synchronized fun getPrecheckCalls(): Int = precheckCalls
    @Synchronized fun getReasonCount(reason: String): Int = reasonCounts[reason] ?: 0
    @Synchronized fun getPatternHits(kind: String): Int = patternHits[kind] ?: 0
    @Synchronized fun totalAiCalls(): Int = actCalls + verifyCalls + precheckCalls
    @Synchronized fun totalPatternHits(): Int = patternHits.values.sum()

    /**
     * Run ke end par steps-log me jata hai — saaf hisaab: kitni AI calls
     * (kyun-kyn) aur kitni pattern-hits (kitni calls bachi).
     */
    @Synchronized fun summary(): String {
        val reasons = reasonCounts.entries.joinToString(", ") { "${it.key}×${it.value}" }
        val hits = patternHits.entries.joinToString(", ") { "${it.key}×${it.value}" }
        return "AI calls: ${totalAiCalls()} " +
            "(act=$actCalls, verify=$verifyCalls, precheck=$precheckCalls" +
            (if (reasons.isNotEmpty()) "; $reasons" else "") + ") | " +
            "pattern-hits: ${totalPatternHits()}" +
            (if (hits.isNotEmpty()) " ($hits)" else "") +
            " — itne maukon par AI poochhna nahi pada"
    }
}

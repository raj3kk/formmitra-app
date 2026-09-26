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
 * v36 user order (2026-09-26) — "Token ke basic par restriction NAHI":
 * token kharch par KOI cap/throttling/pause/block NAHI hoga. Ye object
 * SIRF ginta hai (per-work model/token ledger), rokta kabhi nahi.
 * Token/model/cost ki jaankari user ko KAHIN nahi dikhegi — na history,
 * na summary, na kisi screen par. Hisaab SIRF admin panel par dikhta hai
 * (server-side ledger; admin-only). Token BACHAT design se hoti hai
 * (fixed prompts, learned patterns, zero-AI replay, sirf zaroori DOM) —
 * pabandi se nahi.
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
    /** v36 refine point 5: pre-flight plan AI call (hisaab me gini jati hai). */
    const val R_PREFLIGHT = "preflight_plan"

    // ---- learned-pattern hit kinds (AI call BACHI) ----
    const val P_CHOICE = "choice_memory"
    const val P_LOGIN = "login_store"
    const val P_DOC = "doc_autopick"
    const val P_DETAIL = "detail_autofill"
    const val P_PRECHECK_CACHE = "precheck_cache"
    const val P_FIELDMAP = "fieldmap_consistent"
    /** v36: poora workflow pattern replay (zero AI call). */
    const val P_WORK_PATTERN = "work_pattern_replay"

    @Volatile private var actCalls = 0
    @Volatile private var verifyCalls = 0
    @Volatile private var precheckCalls = 0
    private val reasonCounts = LinkedHashMap<String, Int>()
    private val patternHits = LinkedHashMap<String, Int>()

    // ---- v36: per-work model/token ledger ----
    // Har AI call ka model + provider yahan darj hota hai (AgentLoop act()
    // response se "provider"/"model" padh ke logModelCall karta hai).
    // Actual token counts server deta hai jab available ho (act response me
    // "usage" field); nahi to cost estimate per-call rate se lagti hai aur
    // "(andaza)" mark hota hai — measured jaisa kabhi nahi dikhaya jata.
    //
    // v36 user order (2026-09-26): TOKEN CAP HATAYA — is ledger par koi
    // budget/block nahi lagta. Ye SIRF internal hisaab hai; user ko iski
    // koi jaankari kahin nahi dikhti (admin panel par server-side ledger
    // dikhta hai — admin-only).
    private val modelCalls = LinkedHashMap<String, Int>()
    private var measuredInputTokens = 0L
    private var measuredOutputTokens = 0L

    /**
     * Approx per-1K-token rates (USD) — server model_cost.ts se liye gaye.
     * CLIENT-SIDE ANDAZA hai; kahin user ko nahi dikhaya jata.
     */
    private val MODEL_RATES = mapOf(
        "qwen3.8-27b" to 0.00020,
        "qwen2.5-72b" to 0.00035,
        "deepseek-r1" to 0.00055,
        "gemini-2.5-flash" to 0.00030,
        "gemini-3-flash" to 0.00040
    )

    @Synchronized fun reset() {
        actCalls = 0
        verifyCalls = 0
        precheckCalls = 0
        reasonCounts.clear()
        patternHits.clear()
        modelCalls.clear()
        measuredInputTokens = 0
        measuredOutputTokens = 0
    }

    /** Pre-flight plan AI call — hisaab me gini jati hai (koi cap nahi). */
    @Synchronized fun logPreflight(reason: String = R_PREFLIGHT) {
        reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
    }

    /**
     * Pure cost estimate (USD) — measured tokens hain to unse, nahi to
     * per-call rate se ANDAZA. Selftest me covered.
     */
    fun estimateCostUsd(
        models: Map<String, Int>,
        measuredIn: Long,
        measuredOut: Long
    ): Double {
        if (measuredIn > 0 || measuredOut > 0) {
            // Measured: blended rate se andaza (server asli billing karta hai).
            val totalK = (measuredIn + measuredOut) / 1000.0
            return totalK * 0.00035
        }
        var est = 0.0
        for ((key, n) in models) {
            val model = key.substringAfter("/", "unknown").lowercase()
            val rate = MODEL_RATES.entries.firstOrNull { model.contains(it.key) }?.value
                ?: 0.00035
            est += n * 1.5 * rate
        }
        return est
    }

    /** Ab tak ka andaza kharch (USD) — SIRF internal hisaab, user ko nahi dikhta. */
    @Synchronized fun currentCostUsd(): Double =
        estimateCostUsd(LinkedHashMap(modelCalls), measuredInputTokens, measuredOutputTokens)

    // v36 user order (2026-09-26): TOKEN CAP HATAYA — isOverBudget() aur
    // budgetMessage() hata diye gaye. Token kharch zyada ho jaye to kaam
    // RUKTA/BLOCK nahi hota — koi token-based throttling/pause nahi.

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

    // ---- v36: per-work model/token ledger ----

    /** Har AI call ke baad: kaun se model/provider ne jawab diya. */
    @Synchronized fun logModelCall(model: String, provider: String) {
        val m = model.trim().ifEmpty { "unknown" }
        val p = provider.trim().ifEmpty { "ai" }
        val key = "$p/$m"
        modelCalls[key] = (modelCalls[key] ?: 0) + 1
    }

    /**
     * Server ne actual token counts diye (act response ka "usage" field):
     * {input_tokens, output_tokens}. Ye MEASURED hain.
     */
    @Synchronized fun logMeasuredTokens(inputTokens: Long, outputTokens: Long) {
        if (inputTokens > 0) measuredInputTokens += inputTokens
        if (outputTokens > 0) measuredOutputTokens += outputTokens
    }

    @Synchronized fun modelCallSummary(): Map<String, Int> = LinkedHashMap(modelCalls)

    /**
     * Per-work model/token hisaab — INTERNAL ONLY (debugging).
     * v36 user order (2026-09-26): user ko token/model/cost ki jaankari
     * KAHIN nahi dikhegi — ye string kisi user-facing surface par nahi jati.
     * Asli hisaab server-side ledger me hai (admin panel — admin-only).
     * Measured tokens hain to wahi; nahi to per-call rate se ANDAZA
     * ("(andaza)" saaf likha — measured jaisa kabhi nahi).
     */
    @Synchronized fun tokenSummary(): String {
        if (modelCalls.isEmpty()) return "AI calls: 0 — poora kaam seekhe hue tareeke se (zero AI)"
        val models = modelCalls.entries.joinToString(", ") { "${it.key}×${it.value}" }
        val costPart = if (measuredInputTokens > 0 || measuredOutputTokens > 0) {
            "measured tokens: in=$measuredInputTokens out=$measuredOutputTokens"
        } else {
            // Andaza: har call ~1.5K tokens (prompt+DOM) @ us model ki rate
            val est = estimateCostUsd(modelCalls, 0, 0)
            "lagbhag kharch: \$${"%.4f".format(est)} (andaza — asli billing server par)"
        }
        return "models: $models | $costPart"
    }

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

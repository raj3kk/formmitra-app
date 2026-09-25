package com.formmitra.app.engine

import android.content.Context
import com.formmitra.app.agent.AgentApi
import com.formmitra.app.agent.LearnLogic

/**
 * AiTrainer — v24-refine: operator ka "AI trainer".
 *
 * User: "AI se trained ho operator, agent dono" + "seekho-ek-baar, phir
 * khud-karo, atko-tab-AI" + "AI ki help limit me ho".
 *
 * Iska matlab code me:
 *  1. Har step AI-guided (server brain /api/agent/act — ye loop me pehle
 *     se hai), PAR execute se pehle LOCAL sanity (bina AI call).
 *  2. Galat/stale step → blind repeat NAHI → turant re-plan (brain ko
 *     wajah history me).
 *  3. Ataki situation (repeated failure) → AI escalation: /api/agent/verify
 *     se diagnosis (screenshot + checklist, EK call per stuck episode —
 *     har step par nahi), phir brain nayi strategy banata hai.
 *  4. Har AI call ka reason AiUsage me logged (quota discipline).
 *
 * Koi naya server endpoint nahi — sirf existing /api/agent/act,
 * /api/agent/verify, /api/agent/site-memory.
 */
object AiTrainer {

    /** Kin actions par execute se pehle target-alive check (local). */
    private val SANITY_ACTIONS = setOf(
        "fill", "select", "toggle", "press", "click", "verify_submit"
    )
    // wait_for_*/upload/goto par NAHI: wait ka target baad me aata hai,
    // upload ka input hidden ho sakta hai, goto me selector nahi hota.

    /**
     * Execute se PEHLE local sanity — AI ka diya target page par abhi
     * zinda hai ya nahi. (koi AI call nahi — quota bachat.)
     * @return null = OK; String = stale reason (blind execute mat karo)
     */
    @Suppress("UNCHECKED_CAST")
    fun preExecuteSanity(
        action: String,
        stepMap: Map<String, Any?>,
        engine: FormEngine
    ): String? {
        if (action !in SANITY_ACTIONS) return null
        val sel = stepMap["selector"] as? Map<String, Any?> ?: return null
        val mode = ((sel["mode"] as? String)?.ifEmpty { "css" }) ?: "css"
        val value = (sel["value"] as? String)?.trim().orEmpty()
        if (value.isEmpty()) return null // validateAgentStep pehle pakdega
        return try {
            if (engine.selectorAlive(mode, value)) null
            else "AI-trainer: '$mode:${value.take(80)}' ka target page par " +
                "nahi mila (stale plan) — blind execute nahi, AI se dobara poocha"
        } catch (_: Exception) {
            null // check khud fail → block mat karo
        }
    }

    /**
     * Ataki situation → AI escalation (ONE verify call per stuck episode).
     * /api/agent/verify ka documented purpose: screenshot + checklist →
     * AI verification. Yahan checklist ataki state par hai — AI ki doosri
     * raay (Hinglish issues) jo agle act() me history ke saath jayegi,
     * taaki brain nayi strategy banaye.
     * @return Hinglish issue lines (empty = fail-soft, seedha replan)
     */
    fun diagnoseStuck(
        ctx: Context,
        goal: String,
        screenshotB64: String,
        stuckDetail: String
    ): List<String> {
        if (screenshotB64.isEmpty()) return emptyList()
        AiUsage.logVerify(AiUsage.R_STUCK_DIAGNOSIS)
        val checklist = listOf(
            "page par abhi kya dikh raha hai — kaun sa form/field adhura hai",
            "yeh kaam atka hua hai: ${goal.take(120)} — $stuckDetail",
            "kaam aage badhane ke liye ab kaun sa action lena chahiye"
        )
        val res = try {
            AgentApi.verifySubmit(ctx, screenshotB64, checklist)
        } catch (_: Exception) {
            AgentApi.ApiResult(-1, null)
        }
        if (res.code !in 200..299) return emptyList()
        val arr = res.json?.optJSONArray("issues") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s.take(300))
            if (out.size >= 5) break
        }
        return out
    }

    /**
     * Field-mapping drift → brain ke liye note (pure logic LearnLogic me).
     * Block nahi karta — brain faisla karega (value badalna user ka
     * iraada bhi ho sakta hai). History me jayega → AI correct karega.
     */
    fun mappingNote(
        learnedSrc: String?,
        curValue: String,
        srcValue: String
    ): String? = LearnLogic.fieldMapDriftNote(learnedSrc, curValue, srcValue)
}

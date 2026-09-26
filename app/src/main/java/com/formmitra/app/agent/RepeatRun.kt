package com.formmitra.app.agent

import com.formmitra.app.engine.DestructivePolicy
import org.json.JSONObject

/**
 * RepeatRun — v37 ONE-TAP REPEAT ("🔁 Phir se karo").
 *
 * History ki har entry se naya run:
 *  - same goal/task + card/device details reuse (knownDetails)
 *  - learned (local) ya global (server playbook) pattern ho → seedha
 *    replay — dobara planning / dobara sawal NAHI
 *  - site badli ho (page hash mismatch) → verify-then-adapt (existing
 *    PageStructureHash + repairPatternStep machinery)
 *  - nayi value chahiye → EK baar poochho (existing details_needed batch)
 *  - payment step → gate (existing DestructiveGate/PaymentFlow)
 *  - idempotency: IdempotencyGuard (FormRunService me har run par lagta hai;
 *    yahan double-tap guard alag se)
 *
 * Pure decision logic (decide) — selftest me covered. Run-starting
 * (network/UI) HistoryView.repeatRun me hai.
 */
object RepeatRun {

    data class RepeatPlan(
        val goal: String,
        val url: String,
        val category: String,
        val knownDetails: Map<String, String>,
        /** true = planning skip, seedha pattern replay */
        val skipPlanning: Boolean,
        /** true = start se pehle payment/destructive gate */
        val needsGate: Boolean,
        /** gate ki wajah (user ko dikhane layak, simple Hinglish) */
        val gateReason: String
    )

    /**
     * History entry se repeat plan banao — PURE.
     *
     * @param entry history ka run JSONObject (goal/url/category/payment)
     * @param knownDetails card + device + user-memory se mili details
     * @param localEligible local WorkPatternStore pattern replay-layak hai
     * @param globalEligible server playbook pattern replay-layak hai
     */
    fun decide(
        entry: JSONObject,
        knownDetails: Map<String, String>,
        localEligible: Boolean,
        globalEligible: Boolean
    ): RepeatPlan? {
        return try {
            val goal = entry.optString("goal", entry.optString("task_name", "")).trim()
            val url = entry.optString("url", entry.optString("target_url", "")).trim()
            if (goal.isEmpty() || url.isEmpty()) return null
            val category = entry.optString("category", "")
            // Payment step tha / hai → gate. Existing DestructiveGate/
            // PaymentFlow use hoga (AgentLoop ke andar); yahan sirf flag.
            val payStatus = entry.optJSONObject("payment")?.optString("status", "")
                .orEmpty().lowercase()
            val hadPayment = payStatus.isNotEmpty() && payStatus != "none"
            val gateNeeded = hadPayment || DestructivePolicy.isDestructive(goal)
            val reason = when {
                hadPayment -> "Is kaam me payment tha — shuru karne se pehle confirm kar lo"
                else -> "Ye kaam dobara karne se pehle confirm kar lo"
            }
            RepeatPlan(
                goal = goal,
                url = url,
                category = category,
                knownDetails = knownDetails,
                // Learned/global pattern ho to planning skip — seedha replay.
                // Site badli (hash mismatch) to replay khud verify-then-adapt
                // karega (PageStructureHash check + repairPatternStep).
                skipPlanning = localEligible || globalEligible,
                needsGate = gateNeeded,
                gateReason = reason
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Do goal same kaam hain? (repeat duplicate-check / UI label ke liye.)
     * IdempotencyGuard.goalKey jaisa normalize — yahan self-contained.
     */
    fun sameWork(a: JSONObject, b: JSONObject): Boolean {
        return try {
            val ga = a.optString("goal", "").trim().lowercase()
            val gb = b.optString("goal", "").trim().lowercase()
            val ua = a.optString("url", a.optString("target_url", "")).trim().lowercase()
            val ub = b.optString("url", b.optString("target_url", "")).trim().lowercase()
            ga.isNotEmpty() && ga == gb && ua.isNotEmpty() && ua == ub
        } catch (_: Exception) {
            false
        }
    }
}

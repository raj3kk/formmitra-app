package com.formmitra.app.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * PreflightPlan — v36 PRE-FLIGHT PLAN (PURE Kotlin, self-testable).
 *
 * Kaam shuru hone se PEHLE AI se ek baar me poora plan:
 *   start_url, ordered steps[] (action/target/value), expected_proofs[],
 *   gates[] (otp/login/payment/choice), missing_details[].
 *
 * Uske baad agent plan khud follow karta hai — har step par AI call NAHI.
 * Ek learned step fail ho to sirf us step ke liye AI (single-step), poora
 * re-plan kabhi nahi.
 *
 * Server contract: POST /api/agent/preflight → {plan|null, prompt_version}.
 * Parse-fail = null (caller purane behavior par — plan ke bina bhi kaam
 * rukna nahi chahiye, bas AI purane step-by-step mode me chalega).
 */
object PreflightPlan {

    data class Step(
        val n: Int,
        val action: String,
        val target: String,
        val value: String,
        val note: String
    )

    data class Gate(
        val kind: String,
        val atStep: Int,
        val title: String,
        val blocking: Boolean
    )

    data class Plan(
        val goalHinglish: String,
        val startUrl: String,
        val steps: List<Step>,
        val expectedProofs: List<String>,
        val gates: List<Gate>,
        val missingDetails: List<String>,
        val estimatedMinutes: Int,
        val promptVersion: String
    )

    private val STEP_ACTIONS = setOf(
        "goto", "fill", "select", "click", "upload", "scroll", "wait_for_text"
    )
    private val GATE_KINDS = setOf("otp", "login", "payment", "choice", "document")

    /** Strict parse — fail = null. */
    fun parse(raw: JSONObject?): Plan? {
        if (raw == null) return null
        try {
            if (raw.optBoolean("unknown", false)) return null
            val startUrl = raw.optString("start_url", "").trim()
            if (!startUrl.startsWith("https://", ignoreCase = true)) return null
            // payment/checkout/tout links kabhi start_url nahi
            val low = startUrl.lowercase()
            if (low.contains("payment") || low.contains("checkout") ||
                low.contains("razorpay") || low.contains("cashfree") ||
                low.contains("pay-") || low.contains("tout")
            ) return null
            val steps = ArrayList<Step>()
            val arr = raw.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until minOf(arr.length(), 15)) {
                val o = arr.optJSONObject(i) ?: continue
                val action = o.optString("action", "").trim().lowercase()
                if (action !in STEP_ACTIONS) continue
                val target = o.optString("target", "").trim()
                if (target.isEmpty()) continue
                steps.add(
                    Step(
                        n = if (o.has("n")) o.optInt("n", steps.size + 1) else steps.size + 1,
                        action = action,
                        target = target,
                        value = o.optString("value", "").trim(),
                        note = o.optString("note_hinglish", "").trim()
                    )
                )
            }
            if (steps.isEmpty()) return null
            val gates = ArrayList<Gate>()
            val garr = raw.optJSONArray("gates") ?: JSONArray()
            for (i in 0 until minOf(garr.length(), 8)) {
                val o = garr.optJSONObject(i) ?: continue
                val kind = o.optString("kind", "").trim().lowercase()
                if (kind !in GATE_KINDS) continue
                gates.add(
                    Gate(
                        kind = kind,
                        atStep = maxOf(1, o.optInt("at_step", 1)),
                        title = o.optString("title_hinglish", "").trim().ifEmpty { kind },
                        blocking = o.optBoolean("blocking", false) &&
                            (kind == "otp" || kind == "login" || kind == "payment")
                    )
                )
            }
            val missing = ArrayList<String>()
            val marr = raw.optJSONArray("missing_details") ?: JSONArray()
            for (i in 0 until minOf(marr.length(), 8)) {
                val k = marr.optString(i, "").trim()
                if (k.isNotEmpty() && k !in missing) missing.add(k)
            }
            val proofs = ArrayList<String>()
            val parr = raw.optJSONArray("expected_proofs") ?: JSONArray()
            for (i in 0 until minOf(parr.length(), 8)) {
                val p = parr.optString(i, "").trim()
                if (p.isNotEmpty()) proofs.add(p)
            }
            return Plan(
                goalHinglish = raw.optString("goal_hinglish", "").trim().ifEmpty { "kaam" },
                startUrl = startUrl,
                steps = steps,
                expectedProofs = proofs,
                gates = gates,
                missingDetails = missing,
                estimatedMinutes = raw.optInt("estimated_minutes", 5).coerceIn(2, 30),
                promptVersion = raw.optString("prompt_version", "")
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * User ko dikhane layak simple Hinglish summary — kaam shuru hone se
     * pehle notification/chat me. Koi technical jargon nahi.
     */
    fun hinglishSummary(p: Plan): String {
        val sb = StringBuilder()
        sb.append("📋 Plan taiyar: ${p.goalHinglish}\n")
        sb.append("${p.steps.size} steps, lagbhag ${p.estimatedMinutes} min\n")
        val show = p.steps.take(5)
        for (s in show) sb.append("• ${s.n}. ${s.target}\n")
        if (p.steps.size > 5) sb.append("• ... aur ${p.steps.size - 5} steps\n")
        val blocking = p.gates.filter { it.blocking }
        if (blocking.isNotEmpty()) {
            sb.append("⚠️ Beech me aapki zaroorat padegi: ")
            sb.append(blocking.joinToString(", ") { it.title })
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    /**
     * Plan ke steps ko act-loop ke liye compact JSON — brain ko context.
     * [{n, action, target}] — value nahi (AI value invent na kare).
     */
    fun stepsContextJson(p: Plan): JSONArray {
        val arr = JSONArray()
        for (s in p.steps) {
            arr.put(
                JSONObject()
                    .put("n", s.n)
                    .put("action", s.action)
                    .put("target", s.target)
            )
        }
        return arr
    }
}

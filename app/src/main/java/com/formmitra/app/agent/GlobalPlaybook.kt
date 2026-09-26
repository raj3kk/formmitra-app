package com.formmitra.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * GlobalPlaybook — v37 "Global Playbook" ka APP-side client.
 *
 * Server endpoints (dusra worker bana raha hai — contract):
 *  - GET  /api/agent/playbook?task=&site=&state=&district=
 *       → {pattern: {id,task,site,state,district,steps[],proof_rules[],
 *                     confidence,success_count,page_hash,status} | null}
 *  - POST /api/agent/playbook {proposal:{...}} → {ok} | {error}
 *       (outcome report: {outcome:{pattern_id, success}} — best-effort)
 *
 * Server ZERO-DDL hai; patterns me VALUES/PII KABHI nahi aati — sirf
 * valueKey (value ka source naam, jaise "full_name"). Isliye propose se
 * PEHLE client-side SANITIZER chalta hai: step me koi value-like content
 * (10+ digit number, email, otp/password/pin valueKey) mila → propose HI
 * MAT KARO (log karo, return false).
 *
 * Selection order (AgentLoop me):
 *   1. local eligible pattern (WorkPatternStore) → wahi
 *   2. server/global pattern (confidence > 0.5 ya status live/trial) → replay
 *   3. nahi to null → AI path (preflight plan)
 *
 * Sab network calls background thread pe; caller handle kare.
 */
object GlobalPlaybook {

    data class PlaybookPattern(
        val id: String,
        val task: String,
        val site: String,
        val state: String,
        val district: String,
        val steps: JSONArray,
        val proofRules: List<String>,
        val confidence: Double,
        val successCount: Int,
        val pageHash: String,
        val status: String
    )

    /** Sanitizer ka natija — PURE. preview me blocked values "[BLOCKED]" se masked. */
    data class SanitizeResult(val ok: Boolean, val reasons: List<String>, val preview: String)

    /**
     * Server se pattern lao (cache pehle, network baad me).
     * @return pattern ya null (server par nahi hai / network fail / 404).
     */
    fun resolve(
        ctx: Context,
        task: String,
        site: String,
        state: String,
        district: String
    ): PlaybookPattern? {
        return try {
            val key = Logic.cacheKey(task, site, state, district)
            // (1) local cache — 24h fresh
            val cached = WorkPatternStore.findGlobal(ctx, key)
            if (cached != null) {
                val age = System.currentTimeMillis() - cached.optLong("cached_at", 0)
                if (age in 1 until 24L * 60 * 60 * 1000) {
                    val p = fromJson(cached.optJSONObject("pattern"))
                    if (p != null && Logic.eligibleServer(p)) return p
                }
            }
            // (2) network
            val p = fetchPattern(ctx, task, site, state, district) ?: return null
            try {
                WorkPatternStore.saveGlobal(ctx, key, toCacheJson(p))
            } catch (_: Exception) { }
            p
        } catch (_: Exception) {
            null
        }
    }

    /** GET /api/agent/playbook?task=&site=&state=&district= — auth AgentApi pattern se. */
    fun fetchPattern(
        ctx: Context,
        task: String,
        site: String,
        state: String,
        district: String
    ): PlaybookPattern? {
        return try {
            val res = AgentApi.playbookGet(ctx, task, site, state, district)
            if (res.code !in 200..299) return null
            val p = res.json?.optJSONObject("pattern") ?: return null
            val parsed = fromJson(p)
            if (parsed != null && Logic.eligibleServer(parsed)) parsed else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Server ko pattern propose karo — SANITIZER pehle.
     * @param steps local verified steps (WorkPatternStore-cleaned):
     *   [{type, selector{mode,value}, value_src}] — VALUE kabhi nahi.
     * @return true = server ne accept kiya (2xx); false = blocked by
     *   sanitizer / network fail / server reject.
     */
    fun proposePattern(
        ctx: Context,
        task: String,
        site: String,
        state: String,
        district: String,
        steps: JSONArray,
        proofRules: List<String>,
        verified: Boolean,
        retries: Int,
        userCorrected: Boolean
    ): Boolean {
        return try {
            // SANITIZER: value-like content mila → propose HI MAT KARO.
            val check = Logic.sanitize(steps)
            if (!check.ok) {
                try {
                    android.util.Log.w(
                        "FmPlaybook",
                        "propose BLOCKED (PII/value-like): " + check.reasons.joinToString("; ").take(300)
                    )
                } catch (_: Exception) { }
                return false
            }
            val stepsArr = JSONArray()
            for (i in 0 until steps.length()) {
                val s = steps.optJSONObject(i) ?: continue
                val sel = s.optJSONObject("selector") ?: JSONObject()
                stepsArr.put(
                    JSONObject()
                        .put("type", s.optString("type", ""))
                        .put(
                            "selector", JSONObject()
                                .put("mode", sel.optString("mode", ""))
                                .put("value", sel.optString("value", ""))
                        )
                        .put("valueKey", s.optString("value_src", ""))
                )
            }
            if (stepsArr.length() < 2) return false
            val body = JSONObject().put(
                "proposal", JSONObject()
                    .put("task", task.take(200))
                    .put("site", site.take(200))
                    .put("state", state.take(100))
                    .put("district", district.take(100))
                    .put("steps", stepsArr)
                    .put("proof_rules", JSONArray(proofRules.take(8).map { it.take(200) }))
                    .put("verified", verified)
                    .put("retries", retries)
                    .put("userCorrected", userCorrected)
            )
            val res = AgentApi.playbookPost(ctx, body)
            res.code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Outcome report — run finish par pattern use hua tha to server ko
     * outcome batao (success/fail). BEST-EFFORT: fail ho to chup-chaap
     * ignore (run ka result is par depend nahi karta).
     */
    fun reportOutcome(ctx: Context, patternId: String, success: Boolean) {
        if (patternId.isEmpty()) return
        try {
            AgentApi.playbookPost(ctx, Logic.outcomeBody(patternId, success))
        } catch (_: Exception) { }
    }

    private fun fromJson(p: JSONObject?): PlaybookPattern? {
        if (p == null) return null
        return try {
            val steps = p.optJSONArray("steps") ?: JSONArray()
            if (steps.length() == 0) return null
            val proofs = ArrayList<String>()
            val parr = p.optJSONArray("proof_rules") ?: JSONArray()
            for (i in 0 until parr.length()) {
                val s = parr.optString(i, "").trim()
                if (s.isNotEmpty()) proofs.add(s)
            }
            PlaybookPattern(
                id = p.optString("id", ""),
                task = p.optString("task", ""),
                site = p.optString("site", ""),
                state = p.optString("state", ""),
                district = p.optString("district", ""),
                steps = steps,
                proofRules = proofs,
                confidence = p.optDouble("confidence", 0.0),
                successCount = p.optInt("success_count", p.optInt("successCount", 0)),
                pageHash = p.optString("page_hash", p.optString("pageHash", "")),
                status = p.optString("status", "").lowercase()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun toCacheJson(p: PlaybookPattern): JSONObject {
        val proofs = JSONArray()
        for (r in p.proofRules) proofs.put(r)
        return JSONObject()
            .put(
                "pattern", JSONObject()
                    .put("id", p.id)
                    .put("task", p.task)
                    .put("site", p.site)
                    .put("state", p.state)
                    .put("district", p.district)
                    .put("steps", p.steps)
                    .put("proof_rules", proofs)
                    .put("confidence", p.confidence)
                    .put("success_count", p.successCount)
                    .put("page_hash", p.pageHash)
                    .put("status", p.status)
            )
            .put("cached_at", System.currentTimeMillis())
    }

    // ================= PURE LOGIC (selftest me covered) =================

    object Logic {

        /** v37: global cache key = task+site+state+district (normalized). */
        fun cacheKey(task: String, site: String, state: String, district: String): String {
            fun n(s: String) = s.trim().lowercase()
                .replace(Regex("[^a-z0-9\\u0900-\\u097F ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim().take(120)
            return "gp|" + n(task) + "|" + n(site) + "|" + n(state) + "|" + n(district)
        }

        /**
         * Client-side SANITIZER — propose se PEHLE.
         * Block agar kisi step me value-like content ho:
         *  - 10+ consecutive digits (phone/aadhaar/account jaisa)
         *  - email jaisa text
         *  - valueKey (value_src) otp/password/pin jaisa (credential/secret)
         *
         * NOTE: selector values scan NAHI hote (wo page structure hain,
         * values nahi). Scan hote hain: value/text/option/url/value_src.
         */
        fun sanitize(steps: JSONArray): SanitizeResult {
            val reasons = ArrayList<String>()
            val previewParts = ArrayList<String>()
            val digits10 = Regex("\\d{10,}")
            val email = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
            val secretKey = Regex("otp|password|passwd|pwd|pin|cvv|secret|token", RegexOption.IGNORE_CASE)
            for (i in 0 until steps.length()) {
                val s = try { steps.optJSONObject(i) } catch (_: Exception) { null }
                    ?: continue
                val label = "step${i + 1}(${s.optString("type", "?")})"
                var blocked = false
                // valueKey (value_src) — credential-ish key kabhi server par nahi
                val vk = s.optString("value_src", s.optString("valueKey", "")).trim()
                if (vk.isNotEmpty() && secretKey.containsMatchIn(vk)) {
                    reasons.add("$label: secret valueKey '$vk'")
                    blocked = true
                }
                // value-like fields
                val vals = listOf(
                    s.optString("value", ""), s.optString("text", ""),
                    s.optString("option", ""), s.optString("url", "")
                )
                for (v in vals) {
                    if (v.isEmpty()) continue
                    if (digits10.containsMatchIn(v)) {
                        reasons.add("$label: 10+ digit number")
                        blocked = true
                        break
                    }
                    if (email.containsMatchIn(v)) {
                        reasons.add("$label: email jaisa text")
                        blocked = true
                        break
                    }
                }
                // Masked preview: blocked step ki value kabhi preview me nahi
                previewParts.add(if (blocked) "$label: [BLOCKED]" else "$label: ok")
            }
            return SanitizeResult(reasons.isEmpty(), reasons, previewParts.joinToString("; "))
        }

        /**
         * Kya server pattern replay ke layak hai?
         * confidence > 0.5 YA status live/trial.
         */
        fun eligibleServer(p: PlaybookPattern?): Boolean {
            if (p == null) return false
            if (p.steps.length() == 0) return false
            return p.confidence > 0.5 || p.status == "live" || p.status == "trial"
        }

        /**
         * Selection: local eligible pattern nahi → server pattern
         * (eligibleServer) → nahi to NONE (AI path).
         *
         * @param localEligible true agar WorkPatternStore ka pattern
         *   LearnLogic.shouldReplay pass karta hai.
         * @param localSuccessRate local pattern ki success rate (tie-break)
         * @param server server/global pattern (ya null)
         */
        fun select(
            localEligible: Boolean,
            localSuccessRate: Double,
            server: PlaybookPattern?
        ): Selection {
            // Local eligible ho to wahi jeet-ta hai (device ka seekha hua
            // tareeka sabse tez + sabse personal-fit).
            if (localEligible) return Selection.LOCAL
            // Nahi to server pattern — sirf eligibleServer (confidence > 0.5
            // ya status live/trial) pass kare to.
            return if (eligibleServer(server)) Selection.GLOBAL else Selection.NONE
        }

        enum class Selection { LOCAL, GLOBAL, NONE }

        /**
         * Do patterns me behtar kaun (tie → higher confidence).
         * @return "a" ya "b".
         */
        fun betterOf(
            aRate: Double, aConf: Double,
            bRate: Double, bConf: Double
        ): String {
            if (aRate != bRate) return if (aRate > bRate) "a" else "b"
            return if (aConf >= bConf) "a" else "b"
        }

        /** Outcome report body: {outcome:{pattern_id, success}}. */
        fun outcomeBody(patternId: String, success: Boolean): JSONObject =
            JSONObject().put(
                "outcome", JSONObject()
                    .put("pattern_id", patternId)
                    .put("success", success)
            )
    }
}

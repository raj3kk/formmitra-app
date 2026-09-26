package com.formmitra.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * WorkPatternStore — v36 COMPLETE WORKFLOW pattern storage.
 *
 * User ka order: "jo ek baar jaan gaya dobara AI se na puche."
 *
 * Ek verified site+task ka POORA workflow pattern yahan save hota hai:
 *   key = normalized goal + domain
 *   entry = { steps:[{type, selector{mode,value}, value_src}],
 *             success, fail, confidence, updated_at, prompt_version }
 *
 * RULES:
 *  - Sirf VERIFIED (kaam poora hua, status=done) workflow save hota hai.
 *  - Personal VALUES kabhi save nahi — sirf value ka SOURCE KEY
 *    (card/user/detail ka naam, jaise "full_name"). Replay ke waqt value
 *    aaj ke sources se uthayi jati hai.
 *  - OTP/password/Card PIN/token wale steps kabhi pattern me nahi.
 *  - Ek failed step → confidence girti hai; fail >= 2 → pattern invalid.
 *  - Same task dobara → ZERO AI call replay.
 */
object WorkPatternStore {

    private const val PREFS = "formmitra_work_patterns"
    private const val MAX_STEPS = 30

    /** Kaun se step types pattern me aa sakte hain (gate/terminal kabhi nahi). */
    private val REPLAYABLE = setOf(
        "goto", "fill", "select", "toggle", "press", "click",
        "scroll", "wait_for_text", "wait_for_element"
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context): JSONObject {
        return try {
            JSONObject(prefs(ctx).getString("patterns", "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun saveAll(ctx: Context, all: JSONObject) {
        try {
            prefs(ctx).edit().putString("patterns", all.toString()).apply()
        } catch (_: Exception) { }
    }

    // ============ v37 Global Playbook future-proofing (2026-09-26) ============
    // User order: v37 me server-side shared pattern library ("Global
    // Playbook") aayega. Uske liye data model abhi se taiyar:
    //  1. Record me KABHI user-specific values nahi — sirf site+task key,
    //     selectors/actions (structure), proof rules, stats. Field VALUES,
    //     PII, credentials — kuch nahi. (Ye rule pehle se hai — save() me
    //     value_src sirf SOURCE KEY rakhta hai, value kabhi nahi.)
    //  2. `origin`: kaunse device/install se seekha — HASHED id (raw id
    //     record me KABHI nahi jata, one-way SHA-256).
    //  3. `scope`: "local" default; v37 me "global" promote hoga.
    // Koi extra feature NAHI — sirf data model, taaki v37 me migration
    // karni na pade.

    /** Raw install id → one-way hash. Raw id record me KABHI nahi jata. */
    /**
     * v36/v37: pattern record ke liye URL sanitize — query string aur
     * fragment hatao (wahan session token / personal data ho sakta hai).
     * Sirf scheme+host+path rehta hai. Invalid → "".
     */
    fun sanitizePatternUrl(raw: String): String {
        val u = raw.trim()
        if (u.isEmpty()) return ""
        return try {
            val uri = java.net.URI(u)
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            if (scheme.isEmpty() || host.isEmpty()) return ""
            val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
            "$scheme://$host$path"
        } catch (_: Exception) {
            ""
        }
    }

    fun originHash(raw: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            raw.hashCode().toUInt().toString(16)
        }
    }

    /**
     * Is device/install ka stable origin hash.
     * Raw UUID sirf private prefs me rehta hai — pattern record me sirf
     * hash jata hai (PII nahi, reverse nahi ho sakta).
     */
    fun installOrigin(ctx: Context): String {
        return try {
            val p = prefs(ctx)
            var raw = p.getString("install_uuid", null)
            if (raw.isNullOrEmpty()) {
                raw = java.util.UUID.randomUUID().toString()
                p.edit().putString("install_uuid", raw).apply()
            }
            originHash(raw)
        } catch (_: Exception) {
            originHash("unknown-install")
        }
    }

    /** v37: pattern promote hua to scope badlega ("local" → "global"). */
    const val SCOPE_LOCAL = "local"
    const val SCOPE_GLOBAL = "global"

    fun find(ctx: Context, key: String): JSONObject? {
        if (key.isEmpty()) return null
        return try {
            load(ctx).optJSONObject(key)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Verified workflow save karo.
     * @param steps JSONArray of {type, selector:{mode,value}, value_src}
     *   (value_src sirf fill/select ke liye — source KEY, value kabhi nahi)
     * @param url kaam shuru hone wala page URL (pattern = site+task key;
     *   URL record me bhi taaki checklist poora ho — replay key se hota hai)
     * @param proofRules preflight ke expected_proofs (kaam poora hone ka
     *   saboot kya hoga) — replay-verify ke kaam aayenge
     */
    fun save(
        ctx: Context, key: String, steps: JSONArray, promptVersion: String,
        pageHash: String = "", url: String = "", proofRules: List<String> = emptyList()
    ) {
        if (key.isEmpty()) return
        try {
            val clean = JSONArray()
            for (i in 0 until minOf(steps.length(), MAX_STEPS)) {
                val s = steps.optJSONObject(i) ?: continue
                val type = s.optString("type", "").trim().lowercase()
                if (type !in REPLAYABLE) continue
                val sel = s.optJSONObject("selector")
                val mode = sel?.optString("mode", "")?.trim()?.lowercase().orEmpty()
                val value = sel?.optString("value", "")?.trim().orEmpty()
                if (mode.isEmpty() || value.isEmpty()) continue
                // value_src bhi ho to rakho (value kabhi nahi)
                clean.put(
                    JSONObject()
                        .put("type", type)
                        .put("selector", JSONObject().put("mode", mode).put("value", value))
                        .put("value_src", s.optString("value_src", ""))
                )
            }
            if (clean.length() < 2) return // 1-step pattern ka koi matlab nahi
            val all = load(ctx)
            val prev = all.optJSONObject(key)
            // v37 future-proofing: origin (hashed install id — raw kabhi nahi)
            // aur scope ("local" default). Dobara save par purana origin/scope
            // bana rehta hai (v37 promote kare to scope wahi badlega).
            val origin = prev?.optString("origin", "").orEmpty()
                .ifEmpty { installOrigin(ctx) }
            val scope = prev?.optString("scope", SCOPE_LOCAL).orEmpty()
                .ifEmpty { SCOPE_LOCAL }
            // Checklist: URL + proof rules bhi record me. Dobara save par
            // purane bana rehte hain (naya khaali ho to).
            // v37 future-proofing: URL me query/fragment kabhi nahi — wahan
            // session token ya personal data ho sakta hai (PII/credential
            // record me KABHI nahi). Sirf scheme+host+path.
            val recUrl = sanitizePatternUrl(url)
                .ifEmpty { prev?.optString("url", "").orEmpty() }
            val prevProofs = prev?.optJSONArray("proof_rules")
            val recProofs = JSONArray()
            for (p in proofRules) {
                val s = p.trim()
                if (s.isNotEmpty()) recProofs.put(s)
            }
            if (recProofs.length() == 0 && prevProofs != null) {
                for (i in 0 until prevProofs.length()) {
                    recProofs.put(prevProofs.optString(i, ""))
                }
            }
            all.put(
                key,
                JSONObject()
                    .put("steps", clean)
                    .put("success", (prev?.optInt("success", 0) ?: 0) + 1)
                    .put("fail", 0)
                    .put("confidence", 1.0)
                    .put("updated_at", System.currentTimeMillis())
                    .put("prompt_version", promptVersion)
                    // v36 refine point 3: replay se pehle page-structure
                    // hash check — site badal gayi to replay skip.
                    .put("page_hash", pageHash)
                    // Checklist: site+task key ke saath URL bhi.
                    .put("url", recUrl)
                    // Checklist: kaam poora hone ke saboot ke niyam
                    // (preflight expected_proofs se).
                    .put("proof_rules", recProofs)
                    // v37 Global Playbook: origin (hashed) + scope.
                    .put("origin", origin)
                    .put("scope", scope)
            )
            saveAll(ctx, all)
        } catch (_: Exception) { }
    }

    /** Replay safal → success badhao. */
    fun recordSuccess(ctx: Context, key: String) {
        if (key.isEmpty()) return
        try {
            val all = load(ctx)
            val e = all.optJSONObject(key) ?: return
            e.put("success", e.optInt("success", 0) + 1)
            e.put("fail", 0)
            e.put("confidence", 1.0)
            e.put("updated_at", System.currentTimeMillis())
            saveAll(ctx, all)
        } catch (_: Exception) { }
    }

    /**
     * Replay/step fail → fail badhao. fail >= 2 → pattern INVALID (delete).
     * @return true agar pattern ab bhi valid hai
     */
    fun recordFail(ctx: Context, key: String): Boolean {
        if (key.isEmpty()) return false
        return try {
            val all = load(ctx)
            val e = all.optJSONObject(key) ?: return false
            val fails = e.optInt("fail", 0) + 1
            if (fails >= 2) {
                all.remove(key)
                saveAll(ctx, all)
                false
            } else {
                e.put("fail", fails)
                e.put("confidence", 0.5)
                saveAll(ctx, all)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun stepsOf(entry: JSONObject): JSONArray =
        entry.optJSONArray("steps") ?: JSONArray()

    fun clear(ctx: Context) {
        try {
            prefs(ctx).edit().remove("patterns").apply()
        } catch (_: Exception) { }
    }
}

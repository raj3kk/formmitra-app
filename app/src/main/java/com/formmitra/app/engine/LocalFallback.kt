package com.formmitra.app.engine

import android.content.Context
import com.formmitra.app.agent.AgentApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * LocalFallback — AI-unreachable deterministic mode (Phase 2 gap fill).
 *
 * Jab /api/agent/act fail ho (network exception ya HTTP 5xx — 401 login aur
 * 429 limit par NAHI, wo needs_user hain), to AgentLoop is mode me chala
 * jata hai: bina AI ke, seedhe niyam se form bharo.
 *
 * Heuristic (max 15 steps):
 *   1. Vault profile lao (AgentApi.profile — best-effort).
 *   2. Har iteration: domSnapshot se KHAALI text inputs dhoondho →
 *      label/placeholder/aria/name/id me keyword match → vault value se bharo.
 *   3. Kuch naya na bhara ho → submit/next button dhoondh ke click karo.
 *   4. Submit ke baad URL badla ya success-shabd dikha → done,
 *      nahi to needs_user (bhar diya, result confirm nahi).
 *
 * Safety: har fill runAgentStep se hota hai → step veto + live page veto
 * lagu rehta hai. Payment page par VetoException → turant "vetoed".
 */
object LocalFallback {

    const val MAX_STEPS = 15

    /**
     * blob (label+placeholder+aria+name+id, lowercase) → vault profile field.
     * Order matter karta hai — pehla match jeetta hai.
     */
    private val KEYWORDS: List<Pair<List<String>, String>> = listOf(
        listOf("email", "e-mail") to "email",
        listOf("mobile", "phone", "contact number", "contact no") to "phone",
        listOf("pincode", "pin code", "postal", "zip") to "pincode",
        listOf("dob", "date of birth", "birth date", "janm") to "dob",
        listOf("address", "pata", "पता") to "address_line",
        listOf("village", "gaon", "गांव", "gram") to "village",
        listOf("district", "jila", "zilla", "जिला") to "district",
        listOf("state", "rajya", "राज्य") to "state",
        listOf("gender", "ling") to "gender",
        listOf("full name", "fullname", "your name", "applicant name", "name") to "full_name"
    )

    private val SUCCESS_WORDS = listOf(
        "success", "successful", "thank", "dhanyavad", "धन्यवाद",
        "submitted", "ho gaya", "completed", "confirmation",
        "reference", "acknowledg", "सफल"
    )

    /** Vault profile lao — field → value. Fail ho to khaali map. */
    private fun fetchVaultProfile(ctx: Context): Map<String, String> {
        return try {
            val p = AgentApi.profile(ctx) ?: return emptyMap()
            val out = HashMap<String, String>()
            val keys = p.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = p.optString(k, "").trim()
                if (v.isNotEmpty()) out[k] = v
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Field descriptor → vault value, ya null (koi keyword match nahi). */
    private fun matchValue(
        label: String, placeholder: String, aria: String,
        name: String, id: String, profile: Map<String, String>
    ): String? {
        val blob = "$label $placeholder $aria $name $id".lowercase()
        for ((words, field) in KEYWORDS) {
            if (words.any { blob.contains(it) }) {
                val v = profile[field]?.trim() ?: ""
                if (v.isNotEmpty()) return v
                return null // keyword mila par vault me value nahi — aur mat dhoondho
            }
        }
        return null
    }

    private fun pickSelector(f: JSONObject): Pair<String, String>? {
        val id = f.optString("id", "")
        val nm = f.optString("name", "")
        val ph = f.optString("placeholder", "")
        val label = f.optString("label", "")
        val aria = f.optString("aria", "")
        return when {
            id.isNotEmpty() -> "id" to id
            nm.isNotEmpty() -> "name" to nm
            ph.isNotEmpty() -> "placeholder" to ph
            label.isNotEmpty() -> "label" to label
            aria.isNotEmpty() -> "aria" to aria
            else -> null
        }
    }

    fun run(
        ctx: Context,
        engine: FormEngine,
        goal: String,
        onProgress: (Int) -> Unit = {}
    ): FormEngine.RunResult {
        val stepsLog = JSONArray()
        fun log(i: Int, action: String, ok: Boolean, detail: String) {
            stepsLog.put(
                JSONObject().put("index", i).put("type", action)
                    .put("ok", ok).put("detail", detail.take(300))
            )
        }

        // Pre-run veto (AI loop me ho chuka hota hai — yahan bhi safety)
        VetoCheck.find(goal)?.let {
            return FormEngine.RunResult(
                "vetoed", "PAYMENT VETO (offline start): '$it' mila — '$goal'", stepsLog
            )
        }

        val profile = fetchVaultProfile(ctx)
        if (profile.isEmpty()) {
            return FormEngine.RunResult(
                "needs_user",
                "Offline mode: server nahi mil raha aur vault profile khaali hai — " +
                    "profile me details save karke phir try karein",
                stepsLog
            )
        }

        var filledTotal = 0
        for (i in 1..MAX_STEPS) {
            onProgress(i)
            val snap = try {
                engine.domSnapshot()
            } catch (_: Exception) {
                JSONObject()
            }
            val fields = snap.optJSONArray("fields") ?: JSONArray()
            var filledThisRound = 0
            for (fi in 0 until fields.length()) {
                val f = fields.optJSONObject(fi) ?: continue
                val tag = f.optString("tag", "")
                if (tag != "input" && tag != "textarea") continue
                val t = f.optString("type", "").lowercase()
                if (t == "hidden" || t == "submit" || t == "button" || t == "checkbox" ||
                    t == "radio" || t == "file" || t == "password"
                ) continue
                // khaali hi bharo — bhara hua chhedo mat
                if (f.optString("value", "").isNotEmpty()) continue
                val value = matchValue(
                    f.optString("label", ""), f.optString("placeholder", ""),
                    f.optString("aria", ""), f.optString("name", ""),
                    f.optString("id", ""), profile
                ) ?: continue
                val sel = pickSelector(f) ?: continue
                val label = f.optString("label", "")
                    .ifEmpty { f.optString("placeholder", "").ifEmpty { f.optString("name", "") } }
                try {
                    val detail = engine.runAgentStep(
                        JSONObject()
                            .put("type", "fill")
                            .put(
                                "selector", JSONObject()
                                    .put("mode", sel.first).put("value", sel.second)
                            )
                            .put("text", value)
                    )
                    if (detail.optBoolean("verified", true)) {
                        filledThisRound++
                        filledTotal++
                        log(i, "fill", true, "offline fill: '$label' ✓")
                    } else {
                        log(i, "fill", false, "offline fill verify fail: '$label'")
                    }
                } catch (e: FormEngine.VetoException) {
                    log(i, "fill", false, "VETO: ${e.message}")
                    return FormEngine.RunResult(
                        "vetoed", "PAYMENT VETO (offline): ${e.message}", stepsLog
                    )
                } catch (e: Exception) {
                    log(i, "fill", false, "'$label': ${(e.message ?: "error").take(120)}")
                }
            }

            if (filledThisRound > 0) continue // agle round me aur khaali fields dekho

            // Kuch naya nahi bhara → submit/next dabao
            val beforeUrl = snap.optString("url", "")
            val clicked: String? = try {
                val r = engine.clickSubmitButton()
                log(i, "click", true, "offline submit: '${r.optString("text", "")}'")
                r.optString("text", "submit")
            } catch (e: FormEngine.VetoException) {
                return FormEngine.RunResult(
                    "vetoed", "PAYMENT VETO (offline): ${e.message}", stepsLog
                )
            } catch (e: Exception) {
                log(i, "click", false, "submit nahi mila: ${(e.message ?: "error").take(120)}")
                null
            }
            if (clicked == null) {
                return FormEngine.RunResult(
                    "needs_user",
                    "Offline mode: $filledTotal field(s) bhare, lekin submit button nahi mila — " +
                        "aap khud submit kar dein",
                    stepsLog
                )
            }
            // Submit ke baad settle + confirm
            try {
                Thread.sleep(4000)
            } catch (_: Exception) {
            }
            val after = try {
                engine.domSnapshot()
            } catch (_: Exception) {
                JSONObject()
            }
            val afterUrl = after.optString("url", "")
            val pageText = after.optString("page_text", "").lowercase()
            val urlChanged = beforeUrl.isNotEmpty() && afterUrl.isNotEmpty() && beforeUrl != afterUrl
            val successWord = SUCCESS_WORDS.any { pageText.contains(it) }
            return if (urlChanged || successWord) {
                FormEngine.RunResult(
                    "done",
                    "Offline mode: $filledTotal field(s) bhare, '$clicked' dabaya — ho gaya ✅",
                    stepsLog
                )
            } else {
                FormEngine.RunResult(
                    "needs_user",
                    "Offline mode: $filledTotal field(s) bhare, '$clicked' dabaya — " +
                        "confirmation nahi dikha, aap check kar lein",
                    stepsLog
                )
            }
        }
        return FormEngine.RunResult(
            "needs_user",
            "Offline mode: $MAX_STEPS steps ho gaye ($filledTotal filled) — aap dekh lein",
            stepsLog
        )
    }
}

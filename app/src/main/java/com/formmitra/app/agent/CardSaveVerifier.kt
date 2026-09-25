package com.formmitra.app.agent

import org.json.JSONObject

/**
 * CardSaveVerifier (v29 P2 — verify-after-write).
 *
 * PROBLEM (root cause): PATCH ka 2xx milte hi "✓ save ho gaya" bola jata
 * tha — par server ne asal me likha bhi ya nahi, ye kabhi verify nahi
 * hua. "Details show hota hai par save nahi hota" wala gap yahin se tha.
 *
 * FIX: PATCH → turant GET (cardDetail) → bheji hui har key ki value
 * storage se wapas padhkar compare. Sab match TABHI "✓ save ho gaya";
 * mismatch ho to LOUD error + retry (values pending me surakshit).
 *
 * "Chat me dikhna ≠ saved hona" — verify-dialog/draft wale flows bhi isi
 * helper se guzarte hain (AgentChatView: saveFreshToCard, draft_profile
 * handler, autoSaveExtraDetails; CardDetailView.saveAllDetails;
 * CardFlow.flushPendingDetails).
 *
 * verifyValues() PURE Kotlin (koi Android import nahi) → self-test me
 * cover hota hai.
 */
object CardSaveVerifier {

    sealed class Result {
        /** PATCH 2xx + re-read me saari values match. */
        object Verified : Result()

        /** PATCH 2xx tha, par re-read me value mili nahi / alag mili. */
        data class Mismatch(val detail: String) : Result()

        /** PATCH hi nahi hua (network/server). */
        data class PatchFailed(val code: Int) : Result()
    }

    /**
     * PURE: bheji hui values vs storage se wapas aayi values ka milan.
     * @param sent key → bheji hui value
     * @param stored key → storage se wapas padhi value
     */
    fun verifyValues(
        sent: Map<String, String>,
        stored: Map<String, String>
    ): Boolean {
        if (sent.isEmpty()) return false
        for ((k, v) in sent) {
            // v29: server echo me aas-paas whitespace ho sakta hai —
            // value ka matlab mile, to saved mano.
            if ((stored[k] ?: "").trim() != v.trim()) return false
        }
        return true
    }

    /**
     * PURE: hatayi hui keys ka milan — storage me honi hi nahi chahiye.
     */
    fun verifyDeleted(
        deleted: Set<String>,
        stored: Map<String, String>
    ): Boolean = deleted.all { stored[it].isNullOrEmpty() }

    /**
     * GET cardDetail JSON → key → value map (re-read ke liye).
     */
    fun storedValuesFrom(cardJson: JSONObject?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val det = cardJson?.optJSONObject("details") ?: return out
            val it = det.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = det.optJSONObject(k)?.optString("value", "").orEmpty()
            }
        } catch (_: Exception) { }
        return out
    }

    /**
     * PATCH + verify-after-write (flexible — prebuilt payload + alag tags
     * ya delete-NULL wale flows ke liye). Background thread par call karo.
     * @param patch details JSONObject le → HTTP code de
     * @param reread GET cardDetail → poora card JSON de
     * @param prebuilt PATCH me bheja jane wala poora details object
     * @param toVerify key → value (likhi hui, re-read se milani hai)
     * @param toVerifyDeleted hatayi hui keys (re-read me nahi honi chahiye)
     */
    fun saveAndVerify(
        patch: (JSONObject) -> Int,
        reread: () -> JSONObject?,
        prebuilt: JSONObject,
        toVerify: Map<String, String>,
        toVerifyDeleted: Set<String> = emptySet()
    ): Result {
        if (prebuilt.length() == 0) {
            return Result.Mismatch("kuch bheja hi nahi gaya")
        }
        val code = try { patch(prebuilt) } catch (_: Exception) { -1 }
        if (code !in 200..299) return Result.PatchFailed(code)
        val back = try { reread() } catch (_: Exception) { null }
        val stored = storedValuesFrom(back)
        if (toVerify.isNotEmpty() && !verifyValues(toVerify, stored)) {
            return Result.Mismatch("server ne likha, par wapas padhne par value mili nahi")
        }
        if (toVerifyDeleted.isNotEmpty() && !verifyDeleted(toVerifyDeleted, stored)) {
            return Result.Mismatch("hatayi hui detail ab bhi dikh rahi hai")
        }
        return Result.Verified
    }

    /**
     * PATCH + verify-after-write (simple — ek tag wale flows ke liye).
     * Background thread par call karo.
     */
    fun saveAndVerify(
        patch: (JSONObject) -> Int,
        reread: () -> JSONObject?,
        toSave: Map<String, String>,
        tag: String
    ): Result {
        if (toSave.isEmpty()) return Result.Mismatch("kuch bheja hi nahi gaya")
        val details = JSONObject()
        try {
            for ((k, v) in toSave) {
                details.put(k, JSONObject().put("value", v).put("tag", tag))
            }
        } catch (_: Exception) {
            return Result.Mismatch("request banane me dikkat")
        }
        return saveAndVerify(patch, reread, details, toSave)
    }

    /** Hinglish kaaran — LOUD error me dikhane ke liye. */
    fun loudReason(res: Result): String = when (res) {
        is Result.Verified -> "save ho gaya"
        is Result.PatchFailed -> when (res.code) {
            -1 -> "internet nahi lag raha"
            401, 403 -> "card ka session khatm ho gaya (card PIN se dobara kholo)"
            in 500..599 -> "server me dikkat"
            else -> "server ne mana kiya (code ${res.code})"
        }
        is Result.Mismatch -> res.detail
    }
}

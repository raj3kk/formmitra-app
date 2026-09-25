package com.formmitra.app.agent

import org.json.JSONObject

/**
 * CardJson (v30 ROOT FIX #1 — single parse point for card responses).
 *
 * Server GET/PATCH /api/cards/[id] hamesha NESTED shape bhejta hai
 * (server ka publicCard()):
 *   { card: { id, name, formmitra_id, details: { key: {value, tag} } } }
 *
 * App pehle 3 jagah response par top-level optJSONObject("details")
 * padhta tha → hamesha null → CardSaveVerifier "Mismatch",
 * CardDetailView khaali, CardFlow unlock-prefill khaali.
 *
 * detailsOf(): pehle "card" wrapper ke andar dekho, phir top-level
 * fallback. Koi bhi level missing → null (kabhi crash nahi).
 * Poore app me card-response wali har parse isse guzarni chahiye.
 */
object CardJson {

    /**
     * PURE: card response JSON se details object nikalo.
     * @param json poora API response (nested {card:{details}} ya legacy
     *        top-level {details})
     * @return details JSONObject, ya null (missing = koi exception nahi)
     */
    fun detailsOf(json: JSONObject?): JSONObject? {
        if (json == null) return null
        try {
            // v30: canonical server shape — card wrapper ke andar.
            val fromCard = json.optJSONObject("card")?.optJSONObject("details")
            if (fromCard != null) return fromCard
            // fallback: legacy/top-level shape (kuch mock ya purane
            // response me top-level "details" aa sakta hai).
            return json.optJSONObject("details")
        } catch (_: Exception) {
            return null
        }
    }
}

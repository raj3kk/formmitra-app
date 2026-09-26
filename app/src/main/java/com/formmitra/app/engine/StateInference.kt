package com.formmitra.app.engine

import org.json.JSONObject

/**
 * StateInference — v37: playbook fetch (task+site+state+district) ke liye
 * state/district kahaan se aaye.
 *
 * Sources (is order me):
 *  1. Card details (CardJson.detailsOf wali nested shape — "state" key ho to)
 *  2. DetailStore / knownDetails (device-local saved details)
 *  3. User-memory facts
 *
 * Genuinely missing → "" (caller phir EK baar poochhta hai — details_needed
 * batch mechanism; dobara-dobara sawal nahi).
 *
 * PURE Kotlin — selftest me covered. Values kabhi log nahi hote.
 */
object StateInference {

    private val STATE_KEYS = setOf("state", "rajya")
    private val DISTRICT_KEYS = setOf("district", "zilla", "jila")

    /** Card/API details JSON (nested {card:{details}} ya flat) se state. */
    fun inferState(details: JSONObject?): String =
        pick(details, STATE_KEYS)

    /** Card/API details JSON se district. */
    fun inferDistrict(details: JSONObject?): String =
        pick(details, DISTRICT_KEYS)

    /**
     * v37: CardJson.detailsOf() wali details object ko flat Map me badlo.
     * Values do shape me hoti hain: nested {value, tag} ya raw string.
     * PURE — selftest me covered. Values kabhi log nahi hote.
     */
    fun flattenDetails(details: JSONObject?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (details == null) return out
        return try {
            val it = details.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (k.isEmpty()) continue
                val nested = details.optJSONObject(k)
                val v = if (nested != null) {
                    nested.optString("value", "").trim()
                } else {
                    details.optString(k, "").trim()
                }
                if (v.isNotEmpty()) out[k] = v
            }
            out
        } catch (_: Exception) {
            out
        }
    }

    /**
     * Kayi sources se — pehla non-empty jeet-ta hai.
     * @return Pair(state, district)
     */
    fun fromSources(vararg sources: Map<String, String>?): Pair<String, String> {
        var state = ""
        var district = ""
        for (src in sources) {
            if (src == null) continue
            if (state.isEmpty()) {
                state = firstMatch(src, STATE_KEYS)
            }
            if (district.isEmpty()) {
                district = firstMatch(src, DISTRICT_KEYS)
            }
            if (state.isNotEmpty() && district.isNotEmpty()) break
        }
        return state to district
    }

    /**
     * CardJson.detailsOf() wali details object se seedha.
     * @return Pair(state, district)
     */
    fun fromCardDetails(details: JSONObject?): Pair<String, String> =
        inferState(details) to inferDistrict(details)

    private fun pick(details: JSONObject?, keys: Set<String>): String {
        if (details == null) return ""
        return try {
            val it = details.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (k.trim().lowercase() in keys) {
                    val v = details.optString(k, "").trim()
                    if (v.isNotEmpty()) return v
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun firstMatch(map: Map<String, String>, keys: Set<String>): String {
        for ((k, v) in map) {
            if (k.trim().lowercase() in keys && v.trim().isNotEmpty()) {
                return v.trim()
            }
        }
        return ""
    }
}

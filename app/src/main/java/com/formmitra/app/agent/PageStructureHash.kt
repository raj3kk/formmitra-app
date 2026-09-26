package com.formmitra.app.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * PageStructureHash — v36 refine point 3: pattern replay se PEHLE
 * page-structure hash check.
 *
 * Site badal gayi ho (naye fields, naye buttons, naya layout) to purana
 * pattern replay karne ke 2-3 wasted attempts nahi — seedha AI par jao.
 *
 * Hash me SIRF structure jata hai (field labels/hints/names + button
 * texts + URL path) — VALUES kabhi nahi (privacy: personal data hash me
 * bhi nahi).
 *
 * Pure logic (Android-free) — selftest me covered.
 */
object PageStructureHash {

    /**
     * @param url page URL (sirf host+path — query me token ho sakta hai)
     * @param fieldLabels field ke label/hint/name (sorted internally)
     * @param buttonTexts button texts (sorted internally)
     */
    fun of(url: String, fieldLabels: List<String>, buttonTexts: List<String>): String {
        val hostPath = try {
            val u = java.net.URL(url)
            (u.host + u.path).lowercase()
        } catch (_: Exception) {
            url.lowercase().substringBefore("?")
        }
        val fields = fieldLabels.map { norm(it) }.filter { it.isNotEmpty() }.sorted()
        val buttons = buttonTexts.map { norm(it) }.filter { it.isNotEmpty() }.sorted()
        val joined = buildString {
            append(hostPath).append('|')
            append(fields.joinToString(",")).append('|')
            append(buttons.joinToString(","))
        }
        return joined.hashCode().toUInt().toString(16).padStart(8, '0')
    }

    /** domSnapshot() wale JSONObject se seedha hash. */
    fun ofSnapshot(url: String, snap: JSONObject): String {
        val labels = mutableListOf<String>()
        val buttons = mutableListOf<String>()
        try {
            val fields: JSONArray = snap.optJSONArray("fields") ?: JSONArray()
            for (i in 0 until fields.length()) {
                val f = fields.optJSONObject(i) ?: continue
                labels.add(
                    listOf(
                        f.optString("label"), f.optString("hint"),
                        f.optString("name"), f.optString("placeholder")
                    ).firstOrNull { it.isNotBlank() }.orEmpty()
                )
            }
            val btns: JSONArray = snap.optJSONArray("buttons") ?: JSONArray()
            for (i in 0 until btns.length()) {
                val b = btns.optJSONObject(i) ?: continue
                buttons.add(b.optString("text").ifEmpty { b.optString("label") })
            }
        } catch (_: Exception) { }
        return of(url, labels, buttons)
    }

    private fun norm(s: String): String =
        s.trim().lowercase().replace(Regex("\\s+"), " ")
}

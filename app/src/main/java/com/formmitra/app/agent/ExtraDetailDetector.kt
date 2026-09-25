package com.formmitra.app.agent

/**
 * ExtraDetailDetector (v28, P11) — agent auto-detect → auto-save.
 *
 * Chat me user koi NAYI detail de jo card me nahi hai → auto-detect →
 * tag ke saath card me auto-save (user se extra step nahi).
 *
 * DetailExtractor KNOWN fields (phone/email/dob/...) nikalta hai — uske
 * BAAD ye unknown-detect path chalta hai: label-keyword list + pattern
 * "<label> [:,hai] <value>".
 *
 * CONSERVATIVE (card me kachra nahi):
 *  - label keyword MILA + value non-trivial (lambe/g meaningful) → save.
 *  - label mila par value khaali/chhoti/bekar → IGNORE.
 *  - value me "mera", "hai", "hain" jaise filler shabd akele hon → IGNORE.
 *
 * @return key → value (sirf pakki pakad).
 * v29: keys canonical (aadhar_no, pan_no, ...) ya stable fallback
 * (bank_account_no, ifsc, ...) — caller TagRegistry.normalizeTag se
 * guzarta hai (idempotent), UI label TagRegistry.labelOf se.
 */
object ExtraDetailDetector {

    /**
     * keyword → CANONICAL key (v29 P4). UI label chahiye to
     * TagRegistry.labelOf(key) use karo — yahan display label mat rakho
     * (pehle display label key ban raha tha, isliye card me alag-alag
     * naam se save hota tha).
     * Keyword match case-insensitive, word-boundary par (taaki "pan"
     * "company" me na mile).
     */
    private val KEYWORDS = linkedMapOf(
        "aadhar" to "aadhar_no",
        "aadhaar" to "aadhar_no",
        "adhar" to "aadhar_no",
        "pan" to "pan_no",
        "voter" to "voter_id",
        "ration" to "ration_card_no",
        "driving licence" to "driving_licence_no",
        "driving license" to "driving_licence_no",
        "dl no" to "driving_licence_no",
        "passport" to "passport_no",
        "bank account" to "bank_account_no",
        "account no" to "bank_account_no",
        "khata sankhya" to "bank_account_no",
        "ifsc" to "ifsc",
        "uan" to "uan_no",
        "esic" to "esic_no",
        "pf no" to "pf_no",
        "epf" to "pf_no",
        "pradhan mantri" to "yojana_labh"
    )

    /** Akele aaye to value NAHI maane jayenge. */
    private val FILLER = setOf(
        "mera", "meri", "mere", "hai", "hain", "ka", "ki", "ke",
        "number", "no", "card", "id", "wala", "wali", "ye", "yah",
        "the", "is", "my", "a", "an"
    )

    /**
     * Text me "<keyword> <sep> <value>" patterns dhoondo.
     * sep: ':', '-', '=', 'hai', 'hain', 'is', ya comma.
     */
    fun extractExtra(text: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (text.isBlank()) return out
        // Lambi multi-line me har line alag check karo.
        val lines = text.split("\n")
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.length < 4) continue
            for ((kw, canonKey) in KEYWORDS) {
                if (out.containsKey(canonKey)) continue
                val v = valueAfter(line, kw) ?: continue
                if (v.length >= 3 && !isFillerOnly(v)) {
                    out[canonKey] = v
                }
            }
        }
        return out
    }

    private fun valueAfter(line: String, kw: String): String? {
        // word-boundary keyword match (case-insensitive)
        val kwRe = Regex("(?i)(?<![a-zA-Z])" + Regex.escape(kw) + "(?![a-zA-Z])")
        val m = kwRe.find(line) ?: return null
        var rest = line.substring(m.range.last + 1).trim()
        // separators hatao: ':', '-', '=', ',', 'hai', 'hain', 'is', 'ka/ki/ke'
        rest = rest.replace(
            Regex("^(?:\\s*[:\\-=,]+\\s*|\\s+(?:hai|hain|is)\\b\\s*|\\s+)"),
            ""
        ).trim()
        // "number"/"no" jaise shabd keyword ke turant baad aaye to aage badho
        rest = rest.replace(Regex("(?i)^(number|no\\.?|card|id)\\b\\s*[:\\-]?\\s*"), "").trim()
        // value: line ke aakhir tak, par 60 chars cap; trailing punctuation hatao
        var v = rest.take(60).trim().trimEnd('.', ',', ';', ':', '!', '?')
        // "hai"/"है" aakhir me chipka ho to hatao
        v = v.replace(Regex("(?i)\\s+hai\\s*$"), "")
            .replace(Regex("\\s+है\\s*$"), "").trim()
        if (v.isEmpty()) return null
        return v
    }

    private fun isFillerOnly(v: String): Boolean {
        val words = v.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        // Sab shabd filler hon → bekar. Kam se kam ek meaningful token chahiye.
        val meaningful = words.filter { w ->
            w !in FILLER && w.length >= 2 && w.any { it.isLetterOrDigit() }
        }
        if (meaningful.isEmpty()) return true
        // Sirf 1-2 char ke tukde hon (jaise "ka") → bekar
        return meaningful.all { it.length < 3 && !it.any(Char::isDigit) }
    }
}

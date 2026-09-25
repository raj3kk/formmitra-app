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
 * @return tag-label → value (sirf pakki pakad).
 */
object ExtraDetailDetector {

    /**
     * keyword → display label (bilingual). Keyword match case-insensitive,
     * word-boundary par (taaki "pan" "company" me na mile).
     */
    private val KEYWORDS = linkedMapOf(
        "aadhar" to "Aadhar No. (आधार नं.)",
        "aadhaar" to "Aadhar No. (आधार नं.)",
        "adhar" to "Aadhar No. (आधार नं.)",
        "pan" to "PAN (पैन)",
        "voter" to "Voter ID (वोटर आईडी)",
        "ration" to "Ration Card (राशन कार्ड)",
        "driving licence" to "Driving Licence (ड्राइविंग लाइसेंस)",
        "driving license" to "Driving Licence (ड्राइविंग लाइसेंस)",
        "dl no" to "Driving Licence (ड्राइविंग लाइसेंस)",
        "passport" to "Passport (पासपोर्ट)",
        "bank account" to "Bank Account (बैंक खाता)",
        "account no" to "Bank Account (बैंक खाता)",
        "khata sankhya" to "Bank Account (बैंक खाता)",
        "ifsc" to "IFSC (आईएफएससी)",
        "uan" to "UAN (यूएएन)",
        "esic" to "ESIC (ईएसआईसी)",
        "pf no" to "PF No. (पीएफ नं.)",
        "epf" to "PF No. (पीएफ नं.)",
        "pradhan mantri" to "Yojana Labh (योजना लाभ)"
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
            for ((kw, label) in KEYWORDS) {
                if (out.containsKey(label)) continue
                val v = valueAfter(line, kw) ?: continue
                if (v.length >= 3 && !isFillerOnly(v)) {
                    out[label] = v
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

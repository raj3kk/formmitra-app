package com.formmitra.app.agent

/**
 * DetailExtractor — user ke message se personal details ke candidates
 * nikalta hai (verify-before-save gate ke liye).
 *
 * Server chat response me profile_updates echo NAHI karta aur auto-save
 * karta hai, isliye gate client-side lagaya gaya hai: details wala
 * message SERVER KO TABHI jata hai jab user verify card me Proceed dabaya.
 * Bina Proceed ke message hi nahi bhejta → save ho hi nahi sakta.
 *
 * Sirf strong patterns: phone (10-digit), email, dob, pincode, naam.
 * Galat pakad le to user dialog me theek/cancel kar sakta hai.
 */
object DetailExtractor {

    // v24 (D17): sab labels English (Hindi) bilingual.
    private val LABELS = linkedMapOf(
        "full_name" to "Naam (नाम)",
        "phone" to "Phone (फ़ोन)",
        "email" to "Email (ईमेल)",
        "dob" to "Janm tithi (जन्म तिथि)",
        "pincode" to "Pincode (पिनकोड)",
        "address" to "Pata (पता)",
        "father_name" to "Pita ka naam (पिता का नाम)",
        "mother_name" to "Mata ka naam (माता का नाम)",
        "gender" to "Ling (लिंग)",
        "village" to "Gaon (गांव)",
        "post" to "Post (डाकघर)",
        "district" to "Zila (ज़िला)",
        "state" to "Rajya (राज्य)",
        "qualification" to "Yogyata (योग्यता)",
        "occupation" to "Pesha (पेशा)",
        "category_caste" to "Category/Jati (श्रेणी/जाति)",
        "id_numbers" to "ID numbers (पहचान संख्या)",
        "khata" to "Khata (खाता)",
        "khesra" to "Khesra (खेसरा)",
        "mauza" to "Mauza (मौज़ा)"
    )

    fun label(key: String): String = LABELS[key] ?: key

    fun orderedKeys(): List<String> = LABELS.keys.toList()

    /**
     * @return field → value (sirf mile hue fields).
     */
    fun extract(text: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        var t = " $text "

        // Phone: 10-digit, optional +91 — lambi digit-run ka hissa na ho.
        // Beech me space/dash ho to bhi (user "98765 43210" likhta hai).
        val phoneRe = Regex("(?<!\\d)(?:\\+?91[\\s\\-]?)?([6-9][\\d\\s\\-]{8,13}\\d)(?!\\d)")
        phoneRe.find(t)?.let { m ->
            val digits = m.groupValues[1].filter { it.isDigit() }
            if (digits.length == 10) {
                out["phone"] = digits
                t = t.replace(m.value, " ")
            }
        }

        // Email
        val emailRe = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
        emailRe.find(t)?.let { m ->
            out["email"] = m.value
            t = t.replace(m.value, " ")
        }

        // DOB: dd/mm/yyyy, dd-mm-yy, dd.mm.yyyy
        val dobRe = Regex("\\b(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{2,4})\\b")
        dobRe.find(t)?.let { m ->
            normDob(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let { n ->
                out["dob"] = n
                t = t.replace(m.value, " ")
            }
        }
        // DOB ISO: yyyy-mm-dd
        if (!out.containsKey("dob")) {
            val isoRe = Regex("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b")
            isoRe.find(t)?.let { m ->
                normDob(m.groupValues[3], m.groupValues[2], m.groupValues[1])?.let { n ->
                    out["dob"] = n
                    t = t.replace(m.value, " ")
                }
            }
        }

        // Pincode: 6-digit, akela khada ho (phone ke baad bacha text)
        val pinRe = Regex("(?<!\\d)([1-9]\\d{5})(?!\\d)")
        pinRe.find(t)?.let { m ->
            out["pincode"] = m.groupValues[1]
            t = t.replace(m.value, " ")
        }

        // Naam: "mera naam X", "my name is X", "naam X"
        val nameRe = Regex(
            "(?i)(?:mera naam|my name is|naam)\\s+([A-Za-z\\u0900-\\u097F][A-Za-z\\u0900-\\u097F .]{1,39})"
        )
        nameRe.find(text)?.let { m ->
            var n = m.groupValues[1].trim().trimEnd('.', ',', '!', '?', ';', ':')
            // "hai" / "है" hatao: "Ram Kumar hai" → "Ram Kumar"
            n = n.replace(Regex("(?i)\\s+hai\\s*$"), "")
                .replace(Regex("\\s+है\\s*$"), "").trim()
            n = n.split("\\s+".toRegex()).take(4).joinToString(" ")
            if (n.length >= 2) out["full_name"] = n
        }

        return out
    }

    private fun normDob(d: String, m: String, y: String): String? {
        val dd = d.toIntOrNull() ?: return null
        val mm = m.toIntOrNull() ?: return null
        var yy = y.toIntOrNull() ?: return null
        if (yy < 100) yy += if (yy > 30) 1900 else 2000
        if (dd !in 1..31 || mm !in 1..12 || yy !in 1900..2026) return null
        return "%04d-%02d-%02d".format(yy, mm, dd)
    }
}

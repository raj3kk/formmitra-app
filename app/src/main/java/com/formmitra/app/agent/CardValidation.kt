package com.formmitra.app.agent

/**
 * CardValidation — FormMitra Card fields ka PURE-Kotlin validation
 * (koi Android import nahi → self-test me seedha compile hota hai).
 *
 * Server rules mirror (app/api/cards + profile normalize):
 *  - phone: 10 digits, 6–9 se shuru (+91/0 prefix strip)
 *  - pincode: 6 digits
 *  - dob: DD/MM/YYYY, DD-MM-YYYY, YYYY-MM-DD → YYYY-MM-DD
 *  - email: basic format
 *  - pin: 4–8 digits
 *
 * Galat ho to Hinglish me TOKO + SAMJHAO kahan se sahi bharna hai
 * (v24 #2: "agent sahi se baat kare" — manual form me bhi).
 */
object CardValidation {

    /** PIN 4–8 digits. */
    fun isValidPin(pin: String): Boolean = pin.matches(Regex("\\d{4,8}"))

    /**
     * @return error string (Hinglish: toko + samjhao) ya null (sahi/khaali).
     * Khaali = OK (card ke sab details optional hain).
     */
    fun validateField(key: String, raw: String): String? {
        if (raw.isEmpty()) return null
        return when (key) {
            "phone" -> {
                val ten = normalizePhone(raw)
                if (ten.length != 10 || ten[0] !in '6'..'9')
                    "10 ank ka mobile number likho (bina +91/0 ke, jaise 9876543210) — " +
                        "apne phone ke Contacts me apna number dekho"
                else null
            }
            "pincode" -> {
                val d = raw.filter { it.isDigit() }
                if (d.length != 6)
                    "6 ank ka pincode likho (jaise 110001) — " +
                        "apne post office / purane kagaz par dekho"
                else null
            }
            "dob" -> if (normalizeDob(raw) == null)
                "Janm tithi DD/MM/YYYY me likho (jaise 15/08/1990) — " +
                    "Aadhaar card par dekho"
            else null
            "email" ->
                if (!raw.matches(Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")))
                    "Email sahi likho (jaise naam@gmail.com)"
                else null
            else -> null
        }
    }

    /** Save karne layak normalized value (phone 10-digit, dob YYYY-MM-DD...). */
    fun normalizeField(key: String, raw: String): String = when (key) {
        "dob" -> normalizeDob(raw) ?: raw
        "phone" -> normalizePhone(raw)
        "pincode" -> raw.filter { it.isDigit() }
        else -> raw
    }

    fun normalizePhone(raw: String): String {
        val dd = raw.filter { it.isDigit() }
        return when {
            dd.length == 12 && dd.startsWith("91") -> dd.drop(2)
            dd.length == 11 && dd.startsWith("0") -> dd.drop(1)
            else -> dd
        }
    }

    fun normalizeDob(raw: String): String? {
        val t = raw.trim()
        // YYYY-MM-DD
        Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matchEntire(t)?.let {
            return normDob(it.groupValues[3], it.groupValues[2], it.groupValues[1])
        }
        // DD/MM/YYYY ya DD-MM-YYYY
        Regex("(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{2,4})").matchEntire(t)?.let {
            return normDob(it.groupValues[1], it.groupValues[2], it.groupValues[3])
        }
        return null
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

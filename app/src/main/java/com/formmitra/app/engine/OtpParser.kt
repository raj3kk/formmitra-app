package com.formmitra.app.engine

/**
 * OtpParser — v34 (Phase 2A): SMS se OTP nikalne ka robust parser.
 *
 * Purana parseOtp naazuk tha: "OTP: 12-34-56" ya "1 2 3 4 5 6" jaise
 * format toot jate the, aur keyword ke bina pehla 4-8 digit number
 * uthata tha (phone ke tukde tak).
 *
 * Usool (root):
 *  1. Pehle digits ke beech ke separators hatao: "12-34-56" -> "123456",
 *     "1 2 3 4 5 6" -> "123456". (Phone number "98765 43210" -> 10 digit
 *     banega — wo OTP length me nahi aata, reject.)
 *  2. 4-8 digit groups dhoondho (aage-peeche digit na ho).
 *  3. OTP keyword (otp/code/verification/verify/passcode/कोड/ओटीपी) ke
 *     sabse NAZDEEK wala group lo — keyword ho to phone-number jaisa
 *     lamba number kabhi OTP nahi.
 *  4. Keyword na ho to pehla 4-8 digit group (purana fallback, documented).
 *
 * ZERO Android imports — JVM self-test me seedha chalta hai.
 */
object OtpParser {

    private val KEYWORDS = listOf(
        "otp", "one-time", "one time", "verification", "verify",
        "passcode", "code", "कोड", "ओटीपी", "सत्यापन"
    )

    private val GROUP_RE = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")

    // digits ke beech space/dash/dot: "12-34-56" -> "123456"
    private val SEP_RE = Regex("(?<=\\d)[\\s\\-.\u2013\u2014]+(?=\\d)")

    /** SMS text se OTP nikalo (4-8 digit), ya null. */
    fun extract(msg: String?): String? {
        if (msg.isNullOrBlank()) return null
        val normalized = SEP_RE.replace(msg, "")
        val lower = normalized.lowercase()
        val kwIdx = KEYWORDS
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        val matches = GROUP_RE.findAll(normalized).toList()
        if (matches.isEmpty()) return null
        if (kwIdx == null) return matches.first().groupValues[1]
        return matches.minByOrNull {
            kotlin.math.abs(it.range.first - kwIdx)
        }?.groupValues?.get(1)
    }

    /** 4-8 digit OTP shape check (auto-fill gate). */
    fun looksLikeOtp(s: String): Boolean =
        s.matches(Regex("\\d{4,8}"))
}

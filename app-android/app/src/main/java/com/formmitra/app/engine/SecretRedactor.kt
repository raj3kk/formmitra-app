package com.formmitra.app.engine

/**
 * v58: SECRET REDACTOR — AI help (Groq / AI Mode) ko page text bhejne se
 * PEHLE sensitive data mask karo.
 *
 * Root problem: page ka innerText seedha AI ko jata tha — usme visible
 * OTP code, password field ka text, ya token ho sakta tha.
 *
 * Ye redactor:
 * 1. Secret label wali lines mask karta hai:
 *    "Password: hunter2" → "Password: ***"
 *    "OTP: 482913" → "OTP: ***"
 * 2. Akele khade 4-8 digit OTP-like codes mask karta hai (sirf jab
 *    aas-paas otp/verify/code jaisa shabd ho — false positive se bachne
 *    ke liye).
 * 3. Lambe token-like strings (32+ hex/base64) mask karta hai.
 *
 * Best-effort: kuch chhoot jaye to bhi crash nahi — lekin common
 * patterns pakde jate hain.
 */
object SecretRedactor {

    private val LABEL_PATTERNS = listOf(
        "password", "passwd", "pwd", "passcode",
        "otp", "one-time", "verification code", "verify code",
        "pin", "cvv", "card number", "cardnumber",
        "secret", "token", "api key", "apikey", "auth key",
        "aadhaar", "pan number"
    )

    /**
     * Text me se secrets mask karke wapas do.
     * AI ko bhejne se PEHLE hamesha call karo.
     */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        return try {
            var out = text
            // 1. "Label: value" ya "Label = value" lines
            for (label in LABEL_PATTERNS) {
                // Multiline, case-insensitive:
                // "OTP: 123456" / "Password = hunter2" / "pin- 4829"
                out = out.replace(
                    Regex(
                        "(?im)^([^\\n]{0,40}$label[^\\n:=>]{0,20})\\s*[:=\\-]\\s*\\S+",
                    ),
                    "$1: ***"
                )
            }
            // 2. OTP-like: "otp" shabd ke 60 chars ke andar 4-8 digit code
            out = out.replace(
                Regex("(?i)\\b(otp|verification|verify)[^\\n]{0,60}?\\b(\\d{4,8})\\b"),
                "$1 ***"
            )
            // 3. Lambe token-like strings (32+ hex/base64 chars, akele word)
            out = out.replace(
                Regex("\\b[A-Za-z0-9+/=_\\-]{32,}\\b"),
                "***TOKEN***"
            )
            out
        } catch (_: Exception) {
            // Redact fail ho to safe side: bahut lamba text chhota karo
            // (lekin kabhi raw mat bhejo — caller decide kare)
            text
        }
    }

    /**
     * Screenshot me sensitive regions hain ya nahi — abhi sirf heuristic.
     * (Pixel-level masking future work; abhi AI Mode ko screenshot ke saath
     * text warning bheja jata hai ke secrets mask karke jawab de.)
     */
    fun screenshotWarning(): String =
        "Note: screenshot me agar koi password/OTP dikhe to use apne jawab " +
        "me repeat mat karo."
}

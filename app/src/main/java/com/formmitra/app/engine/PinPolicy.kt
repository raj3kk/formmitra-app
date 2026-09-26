package com.formmitra.app.engine

/**
 * PinPolicy — POINT 27 (PIN reset + pehli-baar PIN setup) ka pure-Kotlin
 * hissa. ZERO Android imports — JVM self-test me seedha compile hota hai.
 *
 * Usool: PIN 4-8 digits; PIN kabhi plain me chat/log me nahi.
 */
object PinPolicy {

    /** Server rule: 4-8 digits. */
    fun isValidPin(pin: String): Boolean =
        pin.length in 4..8 && pin.all { it.isDigit() }

    fun pinsMatch(a: String, b: String): Boolean =
        a.isNotEmpty() && a == b

    /** OTP: 6 digits (server pin-reset challenge). */
    fun isValidOtp(otp: String): Boolean =
        otp.length == 6 && otp.all { it.isDigit() }

    // ---------- user-facing text (simple Hinglish) ----------

    fun invalidPinText(): String = "PIN 4 se 8 ank ka hona chahiye"

    fun mismatchText(): String = "Dono PIN ek jaise nahi — dobara likho"

    fun otpSentText(email: String): String =
        "📩 OTP bhej diya" +
            (if (email.isNotEmpty()) " — $email par dekho" else "") +
            ". 10 minute me kaam karega."

    fun resetDoneText(): String =
        "✅ Naya PIN set ho gaya — ab yahi PIN kaam karega."

    fun setupDoneText(cardName: String): String =
        "✅ Card \"$cardName\" ban gaya — yehi PIN aage kaam karega. " +
            "PIN kabhi kisi ko mat batana."

    fun setupTitle(): String = "🪪 Pehli baar: Card banao"

    fun resetTitle(): String = "🔑 PIN bhool gaye?"
}

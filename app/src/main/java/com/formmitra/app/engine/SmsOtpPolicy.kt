package com.formmitra.app.engine

/**
 * SmsOtpPolicy — POINT 26 (SMS OTP AUTO-READ) ka pure-Kotlin hissa.
 * ZERO Android imports — JVM self-test me seedha compile hota hai.
 *
 * Usool (root + user ka NO-NAGGING faisla):
 *  - Auto-read SIRF tab jab user opted-in HO aur usne deny NA kiya ho.
 *  - Consent dialog ek baar cancel/deny → denied persist → uske baad
 *    HAMESHA manual OTP popup (dobara consent prompt NAHI — no nagging).
 *  - User chahe to Settings/Profile ke toggle se wapas ON kar sakta hai
 *    (ON karte hi denied reset).
 *  - Consent ke bina SMS kabhi mat padho (safety rule).
 */
object SmsOtpPolicy {

    /** Auto-read ki koshish karni chahiye? */
    fun shouldAttempt(optIn: Boolean, denied: Boolean): Boolean =
        optIn && !denied

    /** Consent result ke baad denied state (ek baar deny = hamesha deny). */
    fun nextDenied(denied: Boolean, consentOk: Boolean): Boolean =
        denied || !consentOk

    /** Toggle ON karne par denied reset hota hai. */
    fun deniedAfterToggleOn(): Boolean = false

    // ---------- user-facing text (simple Hinglish) ----------

    fun autoReadNote(): String = "📩 OTP SMS se apne aap bhar diya"

    fun manualNote(): String = "✍️ OTP haath se bhara"

    fun deniedNote(): String =
        "📩 SMS auto-read band kar diya — ab OTP hamesha haath se bharna hoga. " +
            "Profile me jaakar wapas chalu kar sakte ho."

    fun toggleLabel(): String = "📩 SMS se OTP auto-padho"

    fun toggleHint(): String =
        "OTP wala SMS aaye to ek tap me khud bhar jayega. " +
            "Band ho to hamesha manual OTP popup aayega."
}

import com.formmitra.app.engine.SmsOtpPolicy

// POINT 26 (+clarification): SMS OTP auto-read — no-nagging rule tests.
var pass = 0
var fail = 0
fun check(name: String, cond: Boolean) {
    if (cond) { pass++; println("PASS: $name") }
    else { fail++; println("FAIL: $name") }
}

fun main() {
    // Opted-in + not denied → attempt
    check("attempt_ok", SmsOtpPolicy.shouldAttempt(true, false))
    // Opted-out → manual
    check("no_attempt_optout", !SmsOtpPolicy.shouldAttempt(false, false))
    // Denied → manual (HAMESHA)
    check("no_attempt_denied", !SmsOtpPolicy.shouldAttempt(true, true))
    check("no_attempt_both", !SmsOtpPolicy.shouldAttempt(false, true))

    // Deny ek baar → persist (no nagging)
    check("deny_sticks", SmsOtpPolicy.nextDenied(false, false))
    check("deny_sticky", SmsOtpPolicy.nextDenied(true, true))
    check("grant_keeps", !SmsOtpPolicy.nextDenied(false, true))

    // Toggle ON → denied reset
    check("toggle_on_resets", !SmsOtpPolicy.deniedAfterToggleOn())

    // Texts (Hinglish, saaf)
    check("auto_note", "SMS" in SmsOtpPolicy.autoReadNote())
    check("manual_note", "haath" in SmsOtpPolicy.manualNote())
    check("denied_note", "Profile" in SmsOtpPolicy.deniedNote())
    check("toggle_label", "OTP" in SmsOtpPolicy.toggleLabel())

    println("SMSOTP_DONE pass=$pass fail=$fail")
}

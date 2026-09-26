import com.formmitra.app.engine.ErrorCatcher

// v35: Advanced Catcher regression tests — masking, friendly messages,
// report format, start-failure contract (pure parts, no Android).
var pass = 0
var fail = 0
fun check(name: String, cond: Boolean) {
    if (cond) { pass++; println("PASS: $name") }
    else { fail++; println("FAIL: $name") }
}

fun main() {
    // ---- maskSecrets ----
    val otpMasked = ErrorCatcher.maskSecrets("Your OTP is 482913")
    check("otp_masked", "482913" !in otpMasked && "****" in otpMasked)
    val verMasked = ErrorCatcher.maskSecrets("verification code: 12-34-56")
    check("verification_masked", "12-34-56" !in verMasked)
    val pwdMasked = ErrorCatcher.maskSecrets("login failed password=hunter2x for user")
    check("password_masked", "hunter2x" !in pwdMasked && "password=****" in pwdMasked)
    val pinMasked = ErrorCatcher.maskSecrets("Card PIN: 4829 entered")
    check("pin_masked", "4829" !in pinMasked)
    val tokMasked = ErrorCatcher.maskSecrets("X-Card-Token: abcdef1234567890 sent")
    check("token_masked", "abcdef1234567890" !in tokMasked)
    val normal = ErrorCatcher.maskSecrets("goto https://example.com/page?x=42 failed")
    check("normal_untouched", normal == "goto https://example.com/page?x=42 failed")
    val keysKept = ErrorCatcher.maskSecrets("otp=123456")
    check("keys_kept", "otp" in keysKept.lowercase() && "123456" !in keysKept)

    // ---- friendlyMessage (jargon-free Hinglish) ----
    check(
        "friendly_timeout",
        ErrorCatcher.friendlyMessage(java.net.SocketTimeoutException("timed out")).contains("Time")
    )
    check(
        "friendly_network",
        ErrorCatcher.friendlyMessage(java.net.UnknownHostException("dns")).contains("Internet")
    )
    check(
        "friendly_security",
        ErrorCatcher.friendlyMessage(SecurityException("denied")).contains("Permission")
    )
    check(
        "friendly_webview",
        ErrorCatcher.friendlyMessage(IllegalStateException("android.webkit.WebView crash")).contains("Browser")
    )
    val def = ErrorCatcher.friendlyMessage(RuntimeException("weird"))
    check("friendly_default_no_jargon", "Exception" !in def && "dikkat" in def)

    // ---- shortCause ----
    val sc = ErrorCatcher.shortCause(IllegalStateException("Your OTP is 482913 boom"))
    check("shortcause_masked", "482913" !in sc && "IllegalStateException" in sc)

    // ---- formatReport ----
    val t = RuntimeException("Your OTP is 482913")
    t.stackTrace = arrayOf(
        StackTraceElement("com.formmitra.app.Foo", "bar", "Foo.kt", 42)
    )
    val rep = ErrorCatcher.formatReport(
        "Kaam shuru karte waqt", "Bijli Bill", "qabc123",
        t, mapOf("url" to "https://example.com")
    )
    check("report_has_kaam", "Kaam: Bijli Bill" in rep)
    check("report_has_time", "Time: " in rep)
    check("report_has_session", "Session: qabc123" in rep)
    check("report_has_where", "Jagah: Kaam shuru karte waqt" in rep)
    check("report_has_wajah", "Wajah: " in rep)
    check("report_no_otp", "482913" !in rep)
    check("report_has_trace", "Foo.bar" in rep)
    check("report_size_bound", rep.length <= 48_000)

    // ---- startFailureMessage contract ----
    val withCause = ErrorCatcher.startFailureMessage(IllegalStateException("WebView boom"))
    check("startfail_has_cause", "asli wajah" in withCause && "IllegalStateException" in withCause)
    val noCause = ErrorCatcher.startFailureMessage(null)
    check("startfail_null_safe", "FormEngine WebView create failed" in noCause)
    val masked = ErrorCatcher.startFailureMessage(RuntimeException("otp=482913 x"))
    check("startfail_masked", "482913" !in masked)

    println("RESULT pass=$pass fail=$fail")
}

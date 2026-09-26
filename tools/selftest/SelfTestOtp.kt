import com.formmitra.app.engine.OtpParser
import com.formmitra.app.engine.OtpFieldDetect
import com.formmitra.app.engine.OtpFieldDetect.Field
import com.formmitra.app.engine.OtpFieldDetect.Result

// v34 (Phase 2A): OTP chain root-fix — parser + field/button/result logic tests.
var pass = 0
var fail = 0
fun check(name: String, cond: Boolean) {
    if (cond) { pass++; println("PASS: $name") }
    else { fail++; println("FAIL: $name") }
}

fun F(tag: String = "input", type: String = "text", label: String = "", ph: String = "",
      aria: String = "", name: String = "", id: String = "", maxLen: Int = -1) =
    Field(tag, type, label, ph, aria, name, id, maxLen)

fun main() {
    // ---- OtpParser ----
    check("plain", OtpParser.extract("123456 is your OTP") == "123456")
    check("otp_prefix", OtpParser.extract("Your OTP is 789012") == "789012")
    check("dashed", OtpParser.extract("OTP: 12-34-56") == "123456")
    check("spaced", OtpParser.extract("Your code is 12 34 56") == "123456")
    check("single_spaced", OtpParser.extract("code: 1 2 3 4 5 6") == "123456")
    check("verification", OtpParser.extract("456789 is your verification code") == "456789")
    check("dot_sep", OtpParser.extract("OTP 12.34.56") == "123456")
    check("keyword_nearest", OtpParser.extract("Ref 99887. Your OTP is 443322") == "443322")
    // keyword ho to phone-number jaisa lamba number OTP nahi
    check("no_phone_as_otp", OtpParser.extract("OTP 556677 sent to 9876543210") == "556677")
    check("four_digit", OtpParser.extract("Your OTP is 4321") == "4321")
    check("eight_digit", OtpParser.extract("code 12345678") == "12345678")
    check("null_in", OtpParser.extract(null) == null)
    check("no_digits", OtpParser.extract("hello world") == null)
    check("too_long", OtpParser.extract("number 123456789012") == null)
    check("hindi_kw", OtpParser.extract("आपका कोड 654321 है") == "654321")
    check("looks_ok", OtpParser.looksLikeOtp("123456"))
    check("looks_bad", !OtpParser.looksLikeOtp("12ab56"))

    // ---- OtpFieldDetect.scoreField / bestField ----
    val otpF = F(label = "Enter OTP", id = "otp-input")
    val verF = F(ph = "Enter verification code", name = "vcode")
    val pinF = F(label = "PIN code", id = "pin")
    val nameF = F(label = "Full name", id = "name")
    val hidF = F(type = "hidden", id = "tok")
    check("best_otp", OtpFieldDetect.bestField(listOf(nameF, otpF)) == otpF)
    check("best_verification", OtpFieldDetect.bestField(listOf(pinF, verF)) == verF)
    check("hidden_excluded", OtpFieldDetect.bestField(listOf(hidF, nameF)) == null)
    check("score_otp_gt_code", OtpFieldDetect.scoreField(otpF) > OtpFieldDetect.scoreField(pinF))

    // ---- boxGroup ----
    val boxes = (1..6).map { F(id = "otp$it", maxLen = 1, aria = "Digit $it") }
    val mixed = listOf(nameF) + boxes + listOf(F(id = "other"))
    val g = OtpFieldDetect.boxGroup(mixed)
    check("box_group_6", g != null && g.size == 6)
    check("box_order", g?.first()?.id == "otp1" && g?.last()?.id == "otp6")
    check("box_too_few", OtpFieldDetect.boxGroup((1..3).map { F(id = "b$it", maxLen = 1) }) == null)
    check("box_no_maxlen", OtpFieldDetect.boxGroup(listOf(F(id = "a"), F(id = "b"))) == null)

    // ---- submitButtonText ----
    check("btn_verify", OtpFieldDetect.submitButtonText(listOf("Resend OTP", "Verify")) == "Verify")
    check("btn_continue", OtpFieldDetect.submitButtonText(listOf("Cancel", "Continue")) == "Continue")
    check("btn_hindi", OtpFieldDetect.submitButtonText(listOf("रद्द करें", "जारी रखें")) == "जारी रखें")
    check("btn_none", OtpFieldDetect.submitButtonText(listOf("Resend OTP", "Help")) == null)
    check("btn_exact_beats_partial", OtpFieldDetect.submitButtonText(listOf("Verify OTP now", "Submit")) == "Submit")

    // ---- classifyResult ----
    check("res_success_word",
        OtpFieldDetect.classifyResult("u/otp", "u/otp", "enter otp", "OTP verified successfully") == Result.SUCCESS)
    check("res_failure_word",
        OtpFieldDetect.classifyResult("u/otp", "u/otp", "enter otp", "Invalid OTP, try again") == Result.FAILURE)
    check("res_failure_priority",
        OtpFieldDetect.classifyResult("u/otp", "u/home", "enter otp", "Success? No — invalid code") == Result.FAILURE)
    check("res_success_url",
        OtpFieldDetect.classifyResult("u/otp", "u/dashboard", "enter your otp here", "welcome to dashboard") == Result.SUCCESS)
    check("res_unknown",
        OtpFieldDetect.classifyResult("u/otp", "u/otp", "enter otp", "please wait") == Result.UNKNOWN)
    check("res_hindi_fail",
        OtpFieldDetect.classifyResult("u/otp", "u/otp", "otp", "गलत OTP, पुनः प्रयास करें") == Result.FAILURE)

    println("OTP_DONE pass=$pass fail=$fail")
}

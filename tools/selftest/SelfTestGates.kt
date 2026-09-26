import com.formmitra.app.engine.GateLogic
import com.formmitra.app.engine.DocCompressPolicy

// Point 15 (gates) + Point 16 (400KB compression) ke pure-Kotlin tests.

var pass = 0
var fail = 0
fun check(name: String, cond: Boolean) {
    if (cond) { pass++; println("PASS $name") }
    else { fail++; println("FAIL $name") }
}

fun main() {
    // ---------- GateLogic: kind normalize ----------
    check("norm_choice", GateLogic.normKind("choice") == "option_choice")
    check("norm_option", GateLogic.normKind("OPTION") == "option_choice")
    check("norm_otp", GateLogic.normKind("otp") == "otp")
    check("norm_payment", GateLogic.normKind("payment") == "payment")
    check("isParked_otp", GateLogic.isParked("otp"))
    check("isParked_login", GateLogic.isParked("login"))
    check("isParked_payment", GateLogic.isParked("payment"))
    check("isParked_option", GateLogic.isParked("choice"))
    check("isParked_document", GateLogic.isParked("document"))
    check("notParked_device_auth", !GateLogic.isParked("device_auth"))
    check("notParked_bogus", !GateLogic.isParked("bogus"))

    // ---------- GateLogic: masking (req 10) ----------
    val m1 = GateLogic.maskSensitive("OTP gaya hai 9876543210 par")
    check("mask_phone", m1 == "OTP gaya hai 98••••••10 par")
    val m2 = GateLogic.maskSensitive("bhejo priyadarshiraj123@gmail.com par")
    check("mask_email", m2 == "bhejo pr••@gmail.com par")
    val m3 = GateLogic.maskSensitive("aapka OTP 482913 hai")
    check("mask_otp_near_word", m3 == "aapka OTP •••• hai")
    // Amount akela number hai — mask NAHI hona chahiye (req 4: exact amount dikhe)
    val m4 = GateLogic.maskSensitive("₹499.00 pay karo, 6 digit code alag hai")
    check("no_mask_amount", "499" in m4)
    val m5 = GateLogic.maskSensitive("koi sensitive nahi")
    check("mask_noop", m5 == "koi sensitive nahi")
    // Chhota number (4-digit pin jaisa par otp shabd nahi) — mask nahi
    val m6 = GateLogic.maskSensitive("order 1234 mil gaya")
    check("no_mask_random_4digit", "1234" in m6)

    // ---------- GateLogic: countdown / deadline ----------
    check("countdown_600", GateLogic.formatCountdown(600) == "10:00")
    check("countdown_61", GateLogic.formatCountdown(61) == "1:01")
    check("countdown_neg", GateLogic.formatCountdown(-5) == "0:00")
    val dl = GateLogic.deadlineMs(1_000_000L, 300L)
    check("deadline", dl == 1_300_000L)
    check("deadline_default", GateLogic.deadlineMs(0L, 0L) == 600_000L)
    check("expired_true", GateLogic.isExpired(2_000L, 1_000L))
    check("expired_false", !GateLogic.isExpired(500L, 1_000L))
    check("secleft", GateLogic.secLeft(1_000L, 61_000L) == 60L)

    // ---------- GateLogic: answer JSON ----------
    val j1 = GateLogic.gateAnswerJson("otp", GateLogic.D_OTP_FILLED, mapOf("otp" to "482913"))
    check("ans_otp_approved", "\"approved\":true" in j1 && "\"otp\":\"482913\"" in j1)
    val j2 = GateLogic.gateAnswerJson("payment", GateLogic.D_NOT_PAID, emptyMap())
    check("ans_notpaid", "\"approved\":false" in j2 && "\"payment_done\":false" in j2 && "user_not_paid" in j2)
    val j3 = GateLogic.gateAnswerJson("payment", GateLogic.D_PAID_CLAIMED, mapOf("method" to "qr"))
    check("ans_paid", "\"payment_done\":true" in j3)
    val j4 = GateLogic.gateAnswerJson("choice", GateLogic.D_OPTION_CHOSEN, mapOf("choice" to "Haan"))
    check("ans_choice", "\"choice\":\"Haan\"" in j4)
    val j5 = GateLogic.gateAnswerJson("otp", GateLogic.D_DECLINED, emptyMap())
    check("ans_declined", "\"approved\":false" in j5)

    // ---------- GateLogic: decisionOf ----------
    check("dec_paid", GateLogic.decisionOf("payment", mapOf("approved" to true, "payment_done" to true)) == GateLogic.D_PAID_CLAIMED)
    check("dec_notpaid", GateLogic.decisionOf("payment", mapOf("approved" to true, "payment_done" to false, "reason" to "user_not_paid")) == GateLogic.D_NOT_PAID)
    check("dec_declined", GateLogic.decisionOf("otp", mapOf("approved" to false)) == GateLogic.D_DECLINED)
    check("dec_otp", GateLogic.decisionOf("otp", mapOf("approved" to true, "gate_decision" to "otp_filled")) == GateLogic.D_OTP_FILLED)

    // ---------- GateLogic: text (Hinglish, jargon-free) ----------
    check("title_otp", GateLogic.gateTitle("otp") == "🔐 OTP chahiye")
    check("title_payment", GateLogic.gateTitle("payment") == "💰 Payment")
    check("parked_note_payment", "[gate] Payment pending" in GateLogic.parkedNote("payment"))
    check("cardhint_hardsafety", "kabhi khud payment nahi karta" in GateLogic.cardHint("payment"))
    val at = GateLogic.auditText("payment", GateLogic.D_PAID_VERIFIED, 1_700_000_000_000L, true)
    check("audit_verified", "Payment verify ho gaya" in at && "📸" in at)
    val at2 = GateLogic.auditText("otp", GateLogic.D_EXPIRED, 1_700_000_000_000L, false)
    check("audit_expired", "time khatm" in at2 && "📸" !in at2)
    check("hhmm_format", GateLogic.hhmm(1_700_000_000_000L).matches(Regex("\\d{2}:\\d{2}")))

    // ---------- DocCompressPolicy: magic detection ----------
    fun ba(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    check("kind_jpeg", DocCompressPolicy.detectKind(ba(0xFF, 0xD8, 0xFF, 0xE0, 1, 2)) == DocCompressPolicy.Kind.JPEG)
    check("kind_png", DocCompressPolicy.detectKind(ba(0x89, 0x50, 0x4E, 0x47, 1)) == DocCompressPolicy.Kind.PNG)
    val webp = ByteArray(12); webp[0] = 0x52; webp[1] = 0x49; webp[2] = 0x46; webp[3] = 0x46
    webp[8] = 0x57; webp[9] = 0x45; webp[10] = 0x42; webp[11] = 0x50
    check("kind_webp", DocCompressPolicy.detectKind(webp) == DocCompressPolicy.Kind.WEBP)
    check("kind_gif", DocCompressPolicy.detectKind(ba(0x47, 0x49, 0x46, 0x38)) == DocCompressPolicy.Kind.GIF)
    check("kind_bmp", DocCompressPolicy.detectKind(ba(0x42, 0x4D, 1, 2)) == DocCompressPolicy.Kind.BMP)
    check("kind_pdf", DocCompressPolicy.detectKind(ba(0x25, 0x50, 0x44, 0x46)) == DocCompressPolicy.Kind.PDF)
    check("kind_other", DocCompressPolicy.detectKind(ba(1, 2, 3, 4)) == DocCompressPolicy.Kind.OTHER)
    check("kind_tiny", DocCompressPolicy.detectKind(ByteArray(2)) == DocCompressPolicy.Kind.OTHER)

    // ---------- DocCompressPolicy: decide ----------
    val small = DocCompressPolicy.decide(100L * 1024L, DocCompressPolicy.Kind.JPEG)
    check("small_keep", small is DocCompressPolicy.Decision.Keep && (small as DocCompressPolicy.Decision.Keep).note.isEmpty())
    val exact = DocCompressPolicy.decide(DocCompressPolicy.MAX_BYTES, DocCompressPolicy.Kind.PNG)
    check("exact400_keep", exact is DocCompressPolicy.Decision.Keep)
    val bigJpeg = DocCompressPolicy.decide(2L * 1024L * 1024L, DocCompressPolicy.Kind.JPEG)
    check("big_jpeg_compress", bigJpeg is DocCompressPolicy.Decision.Compress)
    val ladder = (bigJpeg as DocCompressPolicy.Decision.Compress).steps
    check("ladder_first_fullsize_q92", ladder.first() == DocCompressPolicy.Step(1.0, 92))
    check("ladder_monotonic", ladder.zipWithNext().all { (a, b) -> b.quality <= a.quality || b.scale < a.scale })
    check("ladder_giveup_note", bigJpeg.giveUpNote.isNotEmpty() && "original" in bigJpeg.giveUpNote)
    val bigGif = DocCompressPolicy.decide(2L * 1024L * 1024L, DocCompressPolicy.Kind.GIF)
    check("big_gif_keep", bigGif is DocCompressPolicy.Decision.Keep && "GIF" in (bigGif as DocCompressPolicy.Decision.Keep).note)
    val bigPdf = DocCompressPolicy.decide(2L * 1024L * 1024L, DocCompressPolicy.Kind.PDF)
    check("big_pdf_keep", bigPdf is DocCompressPolicy.Decision.Keep && "original" in (bigPdf as DocCompressPolicy.Decision.Keep).note)
    val bigOther = DocCompressPolicy.decide(2L * 1024L * 1024L, DocCompressPolicy.Kind.OTHER)
    check("big_other_keep", bigOther is DocCompressPolicy.Decision.Keep)
    check("compressed_note", "400KB" in DocCompressPolicy.compressedNote() && "quality" in DocCompressPolicy.compressedNote())

    // ---------- DocCompressPolicy: quality guard ----------
    check("step_fullsize_always", DocCompressPolicy.stepAllowed(100, DocCompressPolicy.Step(1.0, 72)))
    check("step_downscale_ok", DocCompressPolicy.stepAllowed(1000, DocCompressPolicy.Step(0.85, 85)))
    check("step_downscale_blocked", !DocCompressPolicy.stepAllowed(900, DocCompressPolicy.Step(0.7, 80)))
    check("max_bytes_value", DocCompressPolicy.MAX_BYTES == 400L * 1024L)

    println("GATES_DONE pass=$pass fail=$fail")
}

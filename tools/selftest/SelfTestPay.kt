import com.formmitra.app.engine.PaymentFlow
import com.formmitra.app.engine.PrecheckLogic

// Self-test: PaymentFlow + PrecheckLogic (pure Kotlin, no Android).
// Compile: kotlinc PaymentFlow.kt PrecheckLogic.kt SelfTestPay.kt -d out && java -cp out SelfTestPayKt

var pfail = 0

fun pcheck(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); pfail++ }
}

fun main() {
    // ---- PaymentFlow.upiUri ----
    val uri = PaymentFlow.upiUri("test@okhdfc", "Test Shop", "499.00")
    pcheck("upi scheme", uri.startsWith("upi://pay?"))
    pcheck("upi pa", uri.contains("pa=test%40okhdfc") || uri.contains("pa=test@okhdfc"))
    pcheck("upi am", uri.contains("am=499.00"))
    pcheck("upi cu", uri.contains("cu=INR"))

    // ---- isValidUpiId ----
    pcheck("upi valid", PaymentFlow.isValidUpiId("shop@okhdfc"))
    pcheck("upi valid 2", PaymentFlow.isValidUpiId("98765@paytm"))
    pcheck("upi invalid no @", !PaymentFlow.isValidUpiId("shopokhdfc"))
    pcheck("upi invalid short", !PaymentFlow.isValidUpiId("a@b"))

    // ---- isValidAmount ----
    pcheck("amt valid", PaymentFlow.isValidAmount("499.00"))
    pcheck("amt valid int", PaymentFlow.isValidAmount("1200"))
    pcheck("amt invalid zero", !PaymentFlow.isValidAmount("0"))
    pcheck("amt invalid text", !PaymentFlow.isValidAmount("free"))
    pcheck("amt invalid 3 decimals", !PaymentFlow.isValidAmount("10.999"))

    // ---- parseAmount ----
    pcheck(
        "parse labeled total",
        PaymentFlow.parseAmount("Total: ₹1,299.00\nPay now") == "1299.00"
    )
    pcheck(
        "parse payable",
        PaymentFlow.parseAmount("Amount payable ₹ 250") == "250.00"
    )
    pcheck(
        "parse max fallback",
        PaymentFlow.parseAmount("₹100\n₹350\n₹200") == "350.00"
    )
    pcheck("parse none", PaymentFlow.parseAmount("hello world") == null)

    // ---- isSuccessText ----
    pcheck("success en", PaymentFlow.isSuccessText("Payment Successful! Thank you"))
    pcheck("success order", PaymentFlow.isSuccessText("Your order placed successfully"))
    pcheck("success hi", PaymentFlow.isSuccessText("भुगतान सफल रहा"))
    pcheck("not success", !PaymentFlow.isSuccessText("Please pay to continue"))

    // ---- formatCountdown ----
    pcheck("countdown 600", PaymentFlow.formatCountdown(600) == "10:00")
    pcheck("countdown 61", PaymentFlow.formatCountdown(61) == "1:01")
    pcheck("countdown 0", PaymentFlow.formatCountdown(0) == "0:00")
    pcheck("countdown neg", PaymentFlow.formatCountdown(-5) == "0:00")

    // ---- answerJson ----
    val aj = PaymentFlow.answerJson(mapOf("approved" to true, "otp" to "123456"))
    pcheck("answerJson approved", aj.contains("\"approved\":true"))
    pcheck("answerJson otp", aj.contains("\"otp\":\"123456\""))

    // ---- PrecheckLogic.parse ----
    val v = PrecheckLogic.parse(
        mapOf(
            "feasible" to true,
            "confidence" to 0.85,
            "reason" to "Site khul rahi hai",
            "needs" to listOf("login", "otp"),
            "learned" to mapOf("success_count" to 3, "fail_count" to 1, "avg_steps" to 12),
            "estimated_steps" to 10,
            "kb_link" to "https://example.com/form"
        )
    )
    pcheck("verdict not null", v != null)
    pcheck("verdict feasible", v!!.feasible)
    pcheck("verdict needs", v.needs == listOf("login", "otp"))
    pcheck("verdict learned", v.learnedNote?.contains("3 baar safal") == true)
    pcheck("verdict steps", v.estimatedSteps == 10)
    val vt = PrecheckLogic.verdictText(v)
    pcheck("verdictText feasible", vt.contains("Ho sakta hai"))
    pcheck("verdictText learned", vt.contains("Pichhla experience"))
    pcheck("verdictText needs otp", vt.contains("OTP"))

    val v2 = PrecheckLogic.parse(
        mapOf("feasible" to false, "confidence" to 0.2, "reason" to "Site block hai", "needs" to emptyList<String>())
    )
    pcheck("verdict infeasible", v2 != null && !v2.feasible)
    pcheck("verdictText infeasible", PrecheckLogic.verdictText(v2!!).contains("Nahi ho sakta"))
    pcheck("parse null", PrecheckLogic.parse(null) == null)

    if (pfail > 0) {
        println("FAILURES: $pfail")
        kotlin.system.exitProcess(1)
    }
    println("ALL PAY SELFTESTS PASSED")
}

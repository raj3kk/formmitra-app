import com.formmitra.app.agent.CardValidation
import com.formmitra.app.engine.AgentActions
import com.formmitra.app.engine.StepParser
import com.formmitra.app.engine.validateAgentStep

// Self-test: v24 Cards (CardValidation) + C15 verify_submit contract.
// Compile: kotlinc <src>/agent/CardValidation.kt <src>/engine/FormStepLogic.kt \
//   <src>/engine/AgentLoopLogic.kt tools/selftest/SelfTestCards.kt -d out \
//   && java -cp out SelfTestCardsKt

var failures = 0

fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}

fun main() {
    // ---- 1. PIN validation ----
    check("pin '1234' ok", CardValidation.isValidPin("1234"))
    check("pin '12345678' ok", CardValidation.isValidPin("12345678"))
    check("pin '123' fail", !CardValidation.isValidPin("123"))
    check("pin '123456789' fail", !CardValidation.isValidPin("123456789"))
    check("pin 'abcd' fail", !CardValidation.isValidPin("abcd"))
    check("pin '' fail", !CardValidation.isValidPin(""))

    // ---- 2. phone validation ----
    check("phone ok", CardValidation.validateField("phone", "9876543210") == null)
    check(
        "phone +91 strip ok",
        CardValidation.validateField("phone", "+91 98765 43210") == null
    )
    check("phone short fail", CardValidation.validateField("phone", "12345") != null)
    check(
        "phone bad start fail",
        CardValidation.validateField("phone", "5876543210") != null
    )
    check("phone empty ok (optional)", CardValidation.validateField("phone", "") == null)
    check(
        "phone normalize",
        CardValidation.normalizeField("phone", "+91-9876543210") == "9876543210"
    )

    // ---- 3. pincode validation ----
    check("pincode ok", CardValidation.validateField("pincode", "110001") == null)
    check("pincode 5 fail", CardValidation.validateField("pincode", "11000") != null)
    check(
        "pincode normalize",
        CardValidation.normalizeField("pincode", "110 001") == "110001"
    )

    // ---- 4. dob validation ----
    check("dob ok", CardValidation.validateField("dob", "15/08/1990") == null)
    check(
        "dob normalize",
        CardValidation.normalizeField("dob", "15/08/1990") == "1990-08-15"
    )
    check(
        "dob iso ok",
        CardValidation.normalizeField("dob", "1990-08-15") == "1990-08-15"
    )
    check("dob bad fail", CardValidation.validateField("dob", "32/13/1990") != null)
    check("dob text fail", CardValidation.validateField("dob", "kal") != null)

    // ---- 5. email validation ----
    check("email ok", CardValidation.validateField("email", "naam@gmail.com") == null)
    check("email bad fail", CardValidation.validateField("email", "naam@") != null)

    // ---- 6. C15: verify_submit contract ----
    check("TYPES has verify_submit", "verify_submit" in StepParser.TYPES)
    check("ALL has verify_submit", "verify_submit" in AgentActions.ALL)
    check(
        "TO_SPEC_TYPE verify_submit→click",
        AgentActions.TO_SPEC_TYPE["verify_submit"] == "click"
    )
    check(
        "NEEDS_SELECTOR has verify_submit",
        "verify_submit" in AgentActions.NEEDS_SELECTOR
    )
    val vs = mapOf(
        "action" to "verify_submit",
        "selector" to mapOf("mode" to "css", "value" to "#submit")
    )
    check("validate verify_submit ok", validateAgentStep(vs) == null)
    check(
        "SubmitIntent explicit",
        AgentActions.SubmitIntent.shouldVerify(vs)
    )
    val clickPay = mapOf(
        "action" to "click",
        "selector" to mapOf("mode" to "text", "value" to "Pay Now"),
        "reason" to "click Pay Now button to complete payment"
    )
    check("SubmitIntent pay-now hint", AgentActions.SubmitIntent.shouldVerify(clickPay))
    val clickSearch = mapOf(
        "action" to "click",
        "selector" to mapOf("mode" to "text", "value" to "Search"),
        "reason" to "click search button"
    )
    check(
        "SubmitIntent no false positive (search)",
        !AgentActions.SubmitIntent.shouldVerify(clickSearch)
    )
    val clickSubmitAlone = mapOf(
        "action" to "click",
        "selector" to mapOf("mode" to "text", "value" to "Submit"),
        "reason" to "click Submit"
    )
    check(
        "SubmitIntent bare 'submit' no trigger (documented)",
        !AgentActions.SubmitIntent.shouldVerify(clickSubmitAlone)
    )
    val fillStep = mapOf(
        "action" to "fill",
        "selector" to mapOf("mode" to "css", "value" to "#name"),
        "value" to "Pay Now"
    )
    check("SubmitIntent fill never", !AgentActions.SubmitIntent.shouldVerify(fillStep))

    if (failures > 0) {
        println("FAILURES: $failures")
        kotlin.system.exitProcess(1)
    }
    println("ALL PASS")
}

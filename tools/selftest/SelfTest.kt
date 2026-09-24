import com.formmitra.app.engine.RunPolicy
import com.formmitra.app.engine.StepParser
import com.formmitra.app.engine.VetoCheck

// Self-test: FormStepLogic (pure Kotlin, no Android).
// Compile: kotlinc FormStepLogic.kt SelfTest.kt -d out && java -cp out SelfTestKt

var failures = 0

fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}

fun main() {
    // ---- 1. VetoCheck: saare 18 keywords hit hone chahiye ----
    val kws = listOf(
        "checkout", "payment", "razorpay", "stripe", "upi",
        "pay now", "paynow", "buy now", "buynow",
        "place order", "placeorder", "add to cart", "addtocart",
        "purchase", "billing", "card number", "cardnumber", "cvv"
    )
    check("keyword count = 26 (18 EN + 8 HI)", VetoCheck.KEYWORDS.size == 26)
    for (kw in kws) {
        // spaced keywords apne spaced form me report hote hain
        // (workflows.ts jaisa) — compact input pe bhi hit hona chahiye.
        check("veto hits '$kw'", VetoCheck.find("click $kw button") != null)
    }
    // compact forms: "PayNow" me space nahi, phir bhi pakda jana chahiye
    check("compact 'paynow' in 'PayNow'", VetoCheck.find("PayNow") != null)
    check("compact 'placeorder'", VetoCheck.find("please placeorder here") != null)
    check("case-insensitive 'CHECKOUT'", VetoCheck.find("go to CHECKOUT page") == "checkout")
    check("cvv substring", VetoCheck.find("enter cvv code") == "cvv")
    // negatives
    check("no veto: 'your name'", VetoCheck.find("enter your name") == null)
    check("no veto: 'email address'", VetoCheck.find("email address") == null)
    check("no veto: empty", VetoCheck.find("") == null)
    check("no veto: null", VetoCheck.find(null) == null)
    // NOTE: 'upi' substring hai — 'stupid' me bhi milega (documented over-match,
    // fail-closed direction me safe hai; server-side allowlist isko refine kar sakta hai)
    check("upi substring documented", VetoCheck.find("stupid idea") == "upi")
    // Hindi keywords
    check("hindi 'भुगतान'", VetoCheck.find("कृपया भुगतान करें") == "भुगतान")
    check("hindi 'पेमेंट'", VetoCheck.find("पेमेंट पेज") == "पेमेंट")
    check("hindi 'खरीदें'", VetoCheck.find("अभी खरीदें") == "खरीदें")

    // ---- 2. StepParser: saare 14 types ----
    val types = listOf(
        "goto", "fill", "select", "toggle", "press", "click",
        "wait_for_element", "wait_for_text", "wait_for_navigation",
        "screenshot", "captcha_detect", "captcha_solve", "back", "forward"
    )
    check("type count = 15 (14 + upload)", StepParser.TYPES.size == 15)
    for (t in types) {
        val s = StepParser.parse(mapOf("type" to t))
        check("parse type '$t'", s.type == t)
    }
    // unknown type → clear error
    try {
        StepParser.parse(mapOf("type" to "hack_the_planet"))
        check("unknown type throws", false)
    } catch (e: IllegalArgumentException) {
        check("unknown type throws", e.message!!.contains("supported"))
    }
    // defaults + clamping
    val d = StepParser.parse(mapOf("type" to "fill"))
    check("default timeout 60", d.timeoutS == 60L)
    check("timeout clamp min", StepParser.parse(mapOf("type" to "fill", "timeout_s" to 1)).timeoutS == 5L)
    check("timeout clamp max", StepParser.parse(mapOf("type" to "fill", "timeout_s" to 999)).timeoutS == 300L)
    check("default seconds 2", d.seconds == 2L)
    check("default key Enter", StepParser.parse(mapOf("type" to "press")).key == "Enter")
    check("default state flip", d.state == "flip")
    check("state 'on' kept", StepParser.parse(mapOf("type" to "toggle", "state" to "ON")).state == "on")
    check("state garbage → flip", StepParser.parse(mapOf("type" to "toggle", "state" to "maybe")).state == "flip")
    // selector map
    val sel = StepParser.parse(
        mapOf("type" to "fill", "selector" to mapOf("mode" to "label", "value" to "Name"),
            "text" to "Priya")
    )
    check("selector parsed", sel.selectorMode == "label" && sel.selectorValue == "Name" && sel.text == "Priya")
    // vetoBlob me selector value bhi
    check("vetoBlob has selector", StepParser.vetoBlob(sel).contains("Name"))
    check("vetoBlob catches keyword in selector",
        VetoCheck.find(StepParser.vetoBlob(
            StepParser.parse(mapOf("type" to "click",
                "selector" to mapOf("mode" to "text", "value" to "Pay Now")))
        )) != null)

    // ---- 3. RunPolicy ----
    check("progressEvery(8)=1", RunPolicy.progressEvery(8) == 1)
    check("progressEvery(10)=1", RunPolicy.progressEvery(10) == 1)
    check("progressEvery(11)=3", RunPolicy.progressEvery(11) == 3)
    check("progressEvery(25)=3", RunPolicy.progressEvery(25) == 3)
    check("short task: step 4 reports", RunPolicy.shouldReportProgress(4, 8))
    check("long task: step 4 no report", !RunPolicy.shouldReportProgress(4, 25))
    check("long task: step 6 reports", RunPolicy.shouldReportProgress(6, 25))
    check("fill retry 1 ok", RunPolicy.fillMayRetry(1))
    check("fill retry 2 ok", RunPolicy.fillMayRetry(2))
    // attempt 3 ke baad aur retry nahi — loop 1..3 chalta hai, total 3 attempts
    check("fill no retry after 3rd", !RunPolicy.fillMayRetry(3))
    check("fill retry 4 denied", !RunPolicy.fillMayRetry(4))
    check("verify match", RunPolicy.fillVerified("abc", "abc"))
    check("verify mismatch", !RunPolicy.fillVerified("abc", "abd"))
    check("FILL_MAX_ATTEMPTS=3", RunPolicy.FILL_MAX_ATTEMPTS == 3)

    println()
    if (failures == 0) println("ALL TESTS PASSED") else println("$failures TESTS FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

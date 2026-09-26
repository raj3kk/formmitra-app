import com.formmitra.app.engine.DetailBatchLogic

// Self-test: DetailBatchLogic (point 14, pure Kotlin, no Android).
// Compile: kotlinc DetailBatchLogic.kt SelfTestDetails.kt -d out && java -cp out SelfTestDetailsKt

var failures = 0

fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}

fun main() {
    // ---- 1. parseField: server map se Field ----
    val f1 = DetailBatchLogic.parseField(
        mapOf("key" to "phone", "label" to "Phone number", "why" to "OTP aayega", "type" to "phone")
    )
    check("parseField basic", f1 != null && f1.key == "phone" && f1.label == "Phone number" && f1.why == "OTP aayega" && f1.type == "phone")

    // label/why missing → sensible Hinglish defaults (jargon nahi)
    val f2 = DetailBatchLogic.parseField(mapOf("key" to "pincode"))
    check("parseField defaults", f2 != null && f2.label == "Pincode" && f2.why.isNotEmpty() && !f2.why.contains("field", true))

    // why missing → defaultWhy (simple Hinglish)
    val f3 = DetailBatchLogic.parseField(mapOf("key" to "phone", "type" to "phone"))
    check("defaultWhy phone", f3 != null && f3.why.contains("OTP"))

    val f4 = DetailBatchLogic.parseField(mapOf("key" to "email"))
    check("defaultWhy email", f4 != null && f4.why.contains("mail", true))

    // khali key → null (skip)
    check("parseField empty key → null", DetailBatchLogic.parseField(mapOf("key" to "")) == null)
    check("parseField null → null", DetailBatchLogic.parseField(null) == null)
    check("parseField non-map → null", DetailBatchLogic.parseField("phone") == null)

    // choice type with options
    val f5 = DetailBatchLogic.parseField(
        mapOf("key" to "gender", "type" to "choice", "options" to listOf("Male", "Female", "Other"))
    )
    check("parseField choice", f5 != null && f5.type == "choice" && f5.options.size == 3)

    // ---- 2. parseFields: list ----
    val fields = DetailBatchLogic.parseFields(
        listOf(
            mapOf("key" to "name", "label" to "Naam"),
            mapOf("key" to ""),  // invalid → skip
            mapOf("key" to "phone", "type" to "phone"),
            "garbage"  // invalid → skip
        )
    )
    check("parseFields skips invalid", fields.size == 2 && fields[0].key == "name" && fields[1].key == "phone")
    check("parseFields null → empty", DetailBatchLogic.parseFields(null).isEmpty())
    check("parseFields non-list → empty", DetailBatchLogic.parseFields("x").isEmpty())

    // ---- 3. missingFields ----
    val known = mapOf("name" to "Amit", "phone" to "", "email" to "  ")
    val missing = DetailBatchLogic.missingFields(fields, known)
    check("missingFields", missing.size == 1 && missing[0].key == "phone")

    val allKnown = mapOf("name" to "Amit", "phone" to "98765")
    check("missingFields none", DetailBatchLogic.missingFields(fields, allKnown).isEmpty())

    // ---- 4. compactSummary: simple Hinglish, jargon-free ----
    val summary = DetailBatchLogic.compactSummary("Bihar RTPS", fields)
    check("summary has header", summary.contains("details chahiye", true))
    check("summary has task", summary.contains("Bihar RTPS"))
    check("summary has fields", summary.contains("Naam") && summary.contains("Phone"))
    check("summary has why", summary.contains("—") || summary.contains("-"))
    check("summary no jargon", !summary.contains("API", true) && !summary.contains("JSON", true))

    // ---- 5. validateStep ----
    val good = DetailBatchLogic.validateStep(
        mapOf("action" to "details_needed", "fields" to listOf(mapOf("key" to "phone")))
    )
    check("validateStep ok", good != null && good.size == 1)

    check("validateStep wrong action → null",
        DetailBatchLogic.validateStep(mapOf("action" to "fill")) == null)
    check("validateStep empty fields → null",
        DetailBatchLogic.validateStep(mapOf("action" to "details_needed", "fields" to emptyList<Any>())) == null)
    check("validateStep no fields → null",
        DetailBatchLogic.validateStep(mapOf("action" to "details_needed")) == null)

    // ---- 6. defaultWhy: har key par Hinglish, khali nahi ----
    val testKeys = listOf("name", "phone", "email", "pincode", "address", "dob", "aadhaar", "district", "father_name", "xyz_unknown")
    for (k in testKeys) {
        val w = DetailBatchLogic.defaultWhy(k, k)
        check("defaultWhy '$k' non-empty Hinglish", w.isNotEmpty() && w.length > 5)
    }

    if (failures > 0) {
        println("FAILURES: $failures")
        kotlin.system.exitProcess(1)
    } else {
        println("ALL DETAIL TESTS PASSED")
    }
}

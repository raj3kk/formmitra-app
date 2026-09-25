import com.formmitra.app.agent.CardSaveVerifier
import com.formmitra.app.agent.TagRegistry
import com.formmitra.app.agent.VoiceGenderHint

// Self-test: v29 P2/P3/P4 pure logic.
//  - P4: TagRegistry exact 25-key server taxonomy (order + labels + aliases)
//  - P2: CardSaveVerifier.verifyValues / verifyDeleted (verify-after-write)
//  - P3: VoiceGenderHint.hintOf (Google voice gender convention)
// Compile: kotlinc -cp <android.jar> <src>/agent/TagRegistry.kt
//   <src>/agent/CardSaveVerifier.kt <src>/agent/VoiceOutput.kt
//   tools/selftest/SelfTestV29.kt -d out
//   && java -cp out:<stdlib> SelfTestV29Kt

var failures = 0

fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}

fun main() {
    // ============ P4: exact 25-key taxonomy ============
    val keys = TagRegistry.orderedKeys()
    check("taxonomy has 25 keys", keys.size == 25)
    val expected = listOf(
        "aadhar_no", "pan_no", "voter_id", "ration_card_no",
        "driving_licence_no", "passport_no",
        "full_name", "father_name", "mother_name", "dob", "gender",
        "marital_status", "nationality",
        "phone", "email",
        "address_line", "village", "post", "district", "state",
        "pincode", "country",
        "qualification", "occupation", "category_caste"
    )
    check("taxonomy exact order", keys == expected)
    check("taxonomy no duplicates", keys.toSet().size == 25)

    // aliases → canonical
    check("alias address→address_line", TagRegistry.normalizeTag("address") == "address_line")
    check("alias hindi label", TagRegistry.normalizeTag("पूरा नाम") == "full_name")
    check("alias english label", TagRegistry.normalizeTag("Full Name") == "full_name")
    check("alias aadhaar", TagRegistry.normalizeTag("aadhaar") == "aadhar_no")
    check("alias pan card", TagRegistry.normalizeTag("PAN Card") == "pan_no")
    check("alias pincode", TagRegistry.normalizeTag("पिनकोड") == "pincode")
    check("alias dob", TagRegistry.normalizeTag("जन्म तिथि") == "dob")
    check("alias caste", TagRegistry.normalizeTag("जाति") == "category_caste")
    check("alias mobile", TagRegistry.normalizeTag("मोबाइल नंबर") == "phone")

    // canonical passthrough
    check("canonical passthrough", TagRegistry.normalizeTag("address_line") == "address_line")
    check("canonical passthrough 2", TagRegistry.normalizeTag("voter_id") == "voter_id")

    // labels bilingual
    check("label full_name", TagRegistry.labelOf("full_name") == "Full name (पूरा नाम)")
    check("label address_line", TagRegistry.labelOf("address_line") == "Address (पता)")
    check("label pincode", TagRegistry.labelOf("pincode") == "Pincode (पिनकोड)")

    // isCanonical
    check("isCanonical full_name", TagRegistry.isCanonical("full_name"))
    check("isCanonical address_line", TagRegistry.isCanonical("address_line"))
    check("not canonical bank", !TagRegistry.isCanonical("bank_account_no"))
    check("not canonical address(legacy)", !TagRegistry.isCanonical("address"))

    // ============ P2: verifyValues ============
    val sent = mapOf("full_name" to "Ravi Kumar", "phone" to "9876543210")
    check(
        "verify all match",
        CardSaveVerifier.verifyValues(
            sent,
            mapOf("full_name" to "Ravi Kumar", "phone" to "9876543210", "dob" to "1990-01-01")
        )
    )
    check(
        "verify trims whitespace",
        CardSaveVerifier.verifyValues(
            sent,
            mapOf("full_name" to "  Ravi Kumar ", "phone" to "9876543210")
        )
    )
    check(
        "verify mismatch value",
        !CardSaveVerifier.verifyValues(
            sent,
            mapOf("full_name" to "Ravi Kumar", "phone" to "9999999999")
        )
    )
    check(
        "verify missing key",
        !CardSaveVerifier.verifyValues(
            sent, mapOf("full_name" to "Ravi Kumar")
        )
    )
    check(
        "verify empty sent not-verified",
        !CardSaveVerifier.verifyValues(emptyMap(), mapOf("a" to "b"))
    )

    // verifyDeleted
    check(
        "deleted absent ok",
        CardSaveVerifier.verifyDeleted(
            setOf("email"), mapOf("full_name" to "Ravi")
        )
    )
    check(
        "deleted empty-string ok",
        CardSaveVerifier.verifyDeleted(
            setOf("email"), mapOf("email" to "")
        )
    )
    check(
        "deleted still present fail",
        !CardSaveVerifier.verifyDeleted(
            setOf("email"), mapOf("email" to "a@b.c")
        )
    )

    // ============ P3: VoiceGenderHint ============
    // Google convention: a/c/e female, b/d male
    check("hia-local female", VoiceGenderHint.hintOf("hi-in-x-hia-local") == 2)
    check("hib-local male", VoiceGenderHint.hintOf("hi-in-x-hib-local") == 1)
    check("hic-local female", VoiceGenderHint.hintOf("hi-in-x-hic-local") == 2)
    check("hid-local male", VoiceGenderHint.hintOf("hi-in-x-hid-local") == 1)
    check("ena-local female", VoiceGenderHint.hintOf("en-in-x-ena-local") == 2)
    check("enb-local male", VoiceGenderHint.hintOf("en-in-x-enb-local") == 1)
    // keywords
    check("keyword female", VoiceGenderHint.hintOf("en-US-female-1") == 2)
    check("keyword male", VoiceGenderHint.hintOf("en-US-male-2") == 1)
    check("keyword woman beats man", VoiceGenderHint.hintOf("voice-woman-1") == 2)
    // unknown
    check("unknown voice 0", VoiceGenderHint.hintOf("some-random-voice") == 0)
    check("empty voice 0", VoiceGenderHint.hintOf("") == 0)

    println("----")
    if (failures == 0) println("ALL V29 TESTS PASSED")
    else println("$failures FAILURES")
}

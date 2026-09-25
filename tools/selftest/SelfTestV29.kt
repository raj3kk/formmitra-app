import com.formmitra.app.agent.CardJson
import com.formmitra.app.agent.CardSaveVerifier
import com.formmitra.app.agent.TagRegistry
import com.formmitra.app.agent.VoiceGenderHint
import org.json.JSONObject

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

    // ============ v30: CardJson.detailsOf + storedValuesFrom (ROOT FIX #1)
    // Realistic server shape: GET /api/cards/[id] → {card:{details:{...}}}
    fun serverCardJson(): JSONObject {
        val det = JSONObject()
            .put("aadhar_no", JSONObject().put("value", "123456789012").put("tag", "Aadhar No."))
            .put("full_name", JSONObject().put("value", "Ravi Kumar").put("tag", "Full Name"))
        val card = JSONObject()
            .put("id", "c1")
            .put("name", "Test Card")
            .put("formmitra_id", "FM-0001")
            .put("details", det)
        return JSONObject().put("card", card)
    }
    val nested = serverCardJson()
    val dNested = CardJson.detailsOf(nested)
    check("detailsOf nested → aadhar_no value", dNested?.optJSONObject("aadhar_no")?.optString("value") == "123456789012")
    check("detailsOf nested → full_name value", dNested?.optJSONObject("full_name")?.optString("value") == "Ravi Kumar")
    check("detailsOf nested → tag", dNested?.optJSONObject("aadhar_no")?.optString("tag") == "Aadhar No.")
    // top-level fallback shape (legacy/mock)
    val topLevel = JSONObject().put("details", JSONObject().put("phone", JSONObject().put("value", "9999999999").put("tag", "Phone")))
    check("detailsOf fallback top-level", CardJson.detailsOf(topLevel)?.optJSONObject("phone")?.optString("value") == "9999999999")
    // missing card → null (crash nahi)
    check("detailsOf null input", CardJson.detailsOf(null) == null)
    check("detailsOf empty", CardJson.detailsOf(JSONObject()) == null)
    check("detailsOf card without details", CardJson.detailsOf(JSONObject().put("card", JSONObject().put("id", "c1"))) == null)
    // v29 ka bug yahin tha: top-level padhne se nested shape khaali milti thi
    check("detailsOf old-bug-shape returns null", JSONObject().put("card", JSONObject()).optJSONObject("details") == null)
    // storedValuesFrom — nested server shape par values milein
    val stored = CardSaveVerifier.storedValuesFrom(nested)
    check("storedValuesFrom nested aadhar_no", stored["aadhar_no"] == "123456789012")
    check("storedValuesFrom nested full_name", stored["full_name"] == "Ravi Kumar")
    check("storedValuesFrom nested 2 keys", stored.size == 2)
    check("storedValuesFrom top-level fallback", CardSaveVerifier.storedValuesFrom(topLevel)["phone"] == "9999999999")
    check("storedValuesFrom null → empty", CardSaveVerifier.storedValuesFrom(null).isEmpty())
    check("storedValuesFrom missing card → empty", CardSaveVerifier.storedValuesFrom(JSONObject()).isEmpty())
    // end-to-end: verify-after-write nested re-read par PASS (v29 ka false Mismatch)
    check(
        "verify nested re-read matches",
        CardSaveVerifier.verifyValues(
            mapOf("aadhar_no" to "123456789012", "full_name" to "Ravi Kumar"),
            CardSaveVerifier.storedValuesFrom(nested)
        )
    )

    println("----")
    if (failures == 0) println("ALL V29 TESTS PASSED")
    else println("$failures FAILURES")
}

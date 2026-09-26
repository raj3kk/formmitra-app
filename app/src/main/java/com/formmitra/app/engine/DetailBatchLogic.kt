package com.formmitra.app.engine

/**
 * DetailBatchLogic — SMART DETAIL COLLECTION (point 14) ka pure-Kotlin hissa
 * (ZERO Android imports — JVM self-test me seedha compile hota hai).
 *
 * Server "details_needed" ka compact batch bhejega (ek saath, one-by-one
 * drip nahi):
 *   {action:"details_needed", fields:[{key,label,why,type,options}]}
 *
 * Ye file:
 *  - fields parse karti hai (server Map se),
 *  - har field ke liye simple Hinglish "kyun chahiye" banati hai (agar
 *    server ne "why" na bheja ho to — koi jargon nahi),
 *  - batati hai kaun se fields abhi missing hain (auto-fill ke baad),
 *  - batch ko compact human-readable summary me badalti hai (chat card +
 *    notification ke liye).
 *
 * Blocking gates (otp/login/device_auth/payment) isme NAHI aate — wo
 * UserPrompt.ask() wale existing blocking flow me rehte hain.
 */
object DetailBatchLogic {

    /** Ek manga hua field. */
    data class Field(
        val key: String,
        val label: String,
        /** Simple Hinglish me kyun chahiye (jargon-free). */
        val why: String,
        /** text | phone | email | number | choice */
        val type: String,
        val options: List<String> = emptyList()
    )

    /** Field key → simple Hinglish label (server ne label na bheja ho to). */
    private val KEY_LABELS = mapOf(
        "name" to "Naam",
        "full_name" to "Poora naam",
        "first_name" to "Pehla naam",
        "last_name" to "Aakhri naam",
        "phone" to "Phone number",
        "mobile" to "Mobile number",
        "email" to "Email",
        "address" to "Pata",
        "pincode" to "Pincode",
        "pin_code" to "Pincode",
        "dob" to "Janm tithi",
        "date_of_birth" to "Janm tithi",
        "gender" to "Ling",
        "aadhaar" to "Aadhaar number",
        "aadhar" to "Aadhaar number",
        "pan" to "PAN number",
        "voter_id" to "Voter ID",
        "district" to "Zila",
        "state" to "Rajya",
        "block" to "Block",
        "village" to "Gaon",
        "panchayat" to "Panchayat",
        "father_name" to "Pita ka naam",
        "mother_name" to "Mata ka naam",
        "occupation" to "Kaam",
        "income" to "Aay",
        "category" to "Category",
        "caste" to "Jaati",
        "religion" to "Dharm"
    )

    /**
     * Field key → simple Hinglish "kyun chahiye" (server ne "why" na
     * bheja ho to). Generic fallback me jargon nahi — seedha matlab.
     */
    fun defaultWhy(key: String, label: String): String {
        val k = key.lowercase()
        return when {
            "phone" in k || "mobile" in k -> "Is par OTP/message aayega"
            "email" in k -> "Confirmation mail isi par aayega"
            "otp" in k -> "Aage badhne ke liye code chahiye"
            "pincode" in k || "pin_code" in k -> "Aapka area confirm karne ke liye"
            "address" in k -> "Form me pata bharna zaroori hai"
            "dob" in k || "birth" in k -> "Umar verify karne ke liye"
            "aadhaar" in k || "aadhar" in k -> "Pehchaan verify karne ke liye"
            "pan" in k -> "Pehchaan ke liye chahiye"
            "district" in k || "zila" in k -> "Aapka zila form me chahiye"
            "state" in k || "rajya" in k -> "Rajya form me chahiye"
            "village" in k || "gaon" in k -> "Gaon ka naam form me chahiye"
            "father" in k || "pita" in k -> "Pita ka naam form me manga hai"
            "mother" in k || "mata" in k -> "Mata ka naam form me manga hai"
            "name" in k -> "Naam ke bina form aage nahi badhega"
            else -> "$label form me manga hai, isliye chahiye"
        }
    }

    /** Server ke field map se Field banao (missing par sensible defaults). */
    @Suppress("UNCHECKED_CAST")
    fun parseField(raw: Any?): Field? {
        val m = raw as? Map<String, Any?> ?: return null
        val key = (m["key"] as? String)?.trim().orEmpty()
        if (key.isEmpty()) return null
        val label = (m["label"] as? String)?.trim()?.ifEmpty { null }
            ?: KEY_LABELS[key.lowercase()]
            ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }
        val why = (m["why"] as? String)?.trim()?.ifEmpty { null }
            ?: (m["reason"] as? String)?.trim()?.ifEmpty { null }
            ?: defaultWhy(key, label)
        val type = (m["type"] as? String)?.trim()?.lowercase()?.ifEmpty { null }
            ?: "text"
        val options = (m["options"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.ifEmpty { null } }
            ?: emptyList()
        return Field(key, label, why, type, options)
    }

    /** Server ke fields list se List<Field> (khali/invalid entries skip). */
    fun parseFields(raw: Any?): List<Field> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { parseField(it) }
    }

    /**
     * Kaun se fields abhi bhi missing hain?
     * @param known already-pata values (key → value, khali = missing)
     */
    fun missingFields(fields: List<Field>, known: Map<String, String>): List<Field> =
        fields.filter { f -> known[f.key].orEmpty().trim().isEmpty() }

    /**
     * Batch ka compact summary — chat card + notification ke liye.
     * Simple Hinglish, har field: "• Label — kyun chahiye".
     */
    fun compactSummary(taskName: String, fields: List<Field>): String {
        val sb = StringBuilder()
        sb.append("📝 Kuch details chahiye")
        if (taskName.isNotEmpty()) sb.append(" — $taskName")
        sb.append("\n\n")
        for (f in fields) {
            sb.append("• ${f.label} — ${f.why}\n")
        }
        sb.append("\nNeeche bhar ke Bhejo dabao — kaam apne aap aage badhega.")
        return sb.toString().trim()
    }

    /**
     * "details_needed" step valid hai? (kam se kam 1 valid field chahiye)
     * Invalid step par null = caller purane flow par rahe.
     */
    fun validateStep(stepMap: Map<String, Any?>): List<Field>? {
        val action = (stepMap["action"] as? String)?.trim() ?: return null
        if (action != "details_needed") return null
        val fields = parseFields(stepMap["fields"])
        return if (fields.isNotEmpty()) fields else null
    }
}

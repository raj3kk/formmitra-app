package com.formmitra.app.agent

/**
 * TagRegistry (v29 P4-APP) — canonical detail-tag registry.
 *
 * PROBLEM (root cause): details har jagah alag-alag naam se ja rahi
 * thin — chat extraction "phone", ExtraDetailDetector display label
 * "Aadhar No. (आधार नं.)", manual form "address", server "address_line".
 * Naam na milne par agent user se dobara wahi detail maangta tha.
 *
 * FIX: EK canonical key har detail ke liye. Wire format (chat body ka
 * `known_details`, card PATCH, pending store, act body) me hamesha
 * canonical key jayegi; UI me bilingual label dikhega. Free-text tag
 * (user-typed ya detector ka) normalizeTag() se canonical par aayega;
 * jo canonical me nahi hai wo sanitized fallback key banega.
 *
 * CONTRACT (server lib/agent/tag_registry.ts ke saath converge —
 * 2026-09-25): canonical keys EXACTLY ye 25 hain, isi order me, yehi
 * bilingual labels. Server `known_details` + `asked_already` padhta hai
 * aur KNOWN DETAILS block prompt me inject karta hai; `asked_for`
 * canonical keys me bhejta hai.
 *
 * PURE Kotlin (koi Android import nahi) → self-test me seedha compile.
 */
object TagRegistry {

    /**
     * Canonical key → bilingual label. EXACT 25 — server contract.
     * Order bhi server jaisa (grouped).
     */
    private val LABELS = linkedMapOf(
        "aadhar_no" to "Aadhar number (आधार नंबर)",
        "pan_no" to "PAN number (पैन नंबर)",
        "voter_id" to "Voter ID / EPIC (वोटर आईडी)",
        "ration_card_no" to "Ration card number (राशन कार्ड नंबर)",
        "driving_licence_no" to "Driving licence number (ड्राइविंग लाइसेंस नंबर)",
        "passport_no" to "Passport number (पासपोर्ट नंबर)",
        "full_name" to "Full name (पूरा नाम)",
        "father_name" to "Father's name (पिता का नाम)",
        "mother_name" to "Mother's name (माता का नाम)",
        "dob" to "Date of birth (जन्म तिथि)",
        "gender" to "Gender (लिंग)",
        "marital_status" to "Marital status (वैवाहिक स्थिति)",
        "nationality" to "Nationality (राष्ट्रीयता)",
        "phone" to "Phone / mobile number (मोबाइल नंबर)",
        "email" to "Email (ईमेल)",
        "address_line" to "Address (पता)",
        "village" to "Village / town / city (गांव / शहर)",
        "post" to "Post office (डाकघर)",
        "district" to "District (जिला)",
        "state" to "State (राज्य)",
        "pincode" to "Pincode (पिनकोड)",
        "country" to "Country (देश)",
        "qualification" to "Qualification / education (योग्यता)",
        "occupation" to "Occupation (पेशा)",
        "category_caste" to "Category / caste (श्रेणी / जाति)"
    )

    /**
     * Alias (normalized free text) → key. Pehle 25 canonical keys ke
     * alias; uske baad legacy/extra alias jo STABLE fallback keys par
     * le jate hain (purane card data + ExtraDetailDetector ke bank/ifsc/
     * uan/esic/pf/yojana keys + khata/khesra/mauza — taaki purani aur
     * nayi dono keys ek hi naam par rahen, do keys me na baten).
     */
    private val ALIASES = linkedMapOf(
        // ---- canonical aliases ----
        // aadhar_no
        "adhar no" to "aadhar_no",
        "aadhar" to "aadhar_no",
        "aadhaar" to "aadhar_no",
        "आधार" to "aadhar_no",
        "aadhar number" to "aadhar_no",
        "aadhaar number" to "aadhar_no",
        "adhar number" to "aadhar_no",
        "aadhar card" to "aadhar_no",
        "aadhar no" to "aadhar_no",
        // pan_no
        "pan" to "pan_no",
        "pan number" to "pan_no",
        "pan card" to "pan_no",
        "पैन" to "pan_no",
        // voter_id
        "voter" to "voter_id",
        "voter id" to "voter_id",
        "voter card" to "voter_id",
        "epic" to "voter_id",
        "वोटर" to "voter_id",
        // ration_card_no
        "ration" to "ration_card_no",
        "ration card" to "ration_card_no",
        "ration card no" to "ration_card_no",
        "राशन" to "ration_card_no",
        "ration card number" to "ration_card_no",
        // driving_licence_no
        "driving licence" to "driving_licence_no",
        "driving license" to "driving_licence_no",
        "dl no" to "driving_licence_no",
        "dl" to "driving_licence_no",
        "ड्राइविंग लाइसेंस" to "driving_licence_no",
        "driving licence number" to "driving_licence_no",
        // passport_no
        "passport" to "passport_no",
        "passport no" to "passport_no",
        "passport number" to "passport_no",
        "पासपोर्ट" to "passport_no",
        // full_name
        "name" to "full_name",
        "naam" to "full_name",
        "full name" to "full_name",
        "पूरा नाम" to "full_name",
        "नाम" to "full_name",
        "applicant name" to "full_name",
        "mera naam" to "full_name",
        // father_name
        "father name" to "father_name",
        "pita ka naam" to "father_name",
        "पिता" to "father_name",
        "father" to "father_name",
        "father s name" to "father_name",
        // mother_name
        "mother name" to "mother_name",
        "mata ka naam" to "mother_name",
        "माता" to "mother_name",
        "mother" to "mother_name",
        "mother s name" to "mother_name",
        // dob
        "janm tithi" to "dob",
        "birth date" to "dob",
        "date of birth" to "dob",
        "जन्म तिथि" to "dob",
        "dob" to "dob",
        // gender
        "ling" to "gender",
        "sex" to "gender",
        "लिंग" to "gender",
        "gender" to "gender",
        // marital_status
        "marital status" to "marital_status",
        "वैवाहिक स्थिति" to "marital_status",
        "married" to "marital_status",
        "unmarried" to "marital_status",
        "shaadi" to "marital_status",
        // nationality
        "nationality" to "nationality",
        "राष्ट्रीयता" to "nationality",
        "citizenship" to "nationality",
        "nagrikta" to "nationality",
        // phone
        "mobile" to "phone",
        "phone no" to "phone",
        "मोबाइल" to "phone",
        "mobile number" to "phone",
        "phone number" to "phone",
        "contact" to "phone",
        "contact number" to "phone",
        "मोबाइल नंबर" to "phone",
        // email
        "e-mail" to "email",
        "email id" to "email",
        "email address" to "email",
        "ईमेल" to "email",
        // address_line (legacy "address" bhi yahi aayega)
        "address" to "address_line",
        "pata" to "address_line",
        "पता" to "address_line",
        "permanent address" to "address_line",
        "address line" to "address_line",
        // village
        "gaon" to "village",
        "गांव" to "village",
        "village" to "village",
        "town" to "village",
        "city" to "village",
        "शहर" to "village",
        // post
        "post office" to "post",
        "डाकघर" to "post",
        "post" to "post",
        // district
        "zila" to "district",
        "जिला" to "district",
        "ज़िला" to "district",
        "district" to "district",
        // state
        "rajya" to "state",
        "राज्य" to "state",
        "state" to "state",
        // pincode
        "pin code" to "pincode",
        "pin" to "pincode",
        "पिनकोड" to "pincode",
        "postal code" to "pincode",
        "zip" to "pincode",
        "pincode" to "pincode",
        // country
        "country" to "country",
        "देश" to "country",
        "desh" to "country",
        // qualification
        "education" to "qualification",
        "yogyata" to "qualification",
        "योग्यता" to "qualification",
        "qualification" to "qualification",
        // occupation
        "pesha" to "occupation",
        "पेशा" to "occupation",
        "occupation" to "occupation",
        "profession" to "occupation",
        // category_caste
        "caste" to "category_caste",
        "category" to "category_caste",
        "jati" to "category_caste",
        "जाति" to "category_caste",
        "श्रेणी" to "category_caste",
        // ---- legacy/extra aliases → stable fallback keys ----
        // (canonical 25 me nahi, par purane card data + detector keys
        // ke liye stable naam — do keys me batna nahi chahiye)
        "bank account" to "bank_account_no",
        "account no" to "bank_account_no",
        "khata sankhya" to "bank_account_no",
        "account number" to "bank_account_no",
        "बैंक खाता" to "bank_account_no",
        "ifsc code" to "ifsc",
        "आईएफएससी" to "ifsc",
        "uan" to "uan_no",
        "यूएएन" to "uan_no",
        "esic" to "esic_no",
        "ईएसआईसी" to "esic_no",
        "pf no" to "pf_no",
        "epf" to "pf_no",
        "pf" to "pf_no",
        "पीएफ" to "pf_no",
        "pradhan mantri" to "yojana_labh",
        "yojana" to "yojana_labh",
        "योजना" to "yojana_labh",
        "khata no" to "khata",
        "खाता" to "khata",
        "khesra no" to "khesra",
        "खेसरा" to "khesra",
        "mauza" to "mauza",
        "मौजा" to "mauza",
        "मौज़ा" to "mauza",
        "id number" to "id_numbers",
        "id no" to "id_numbers",
        "पहचान" to "id_numbers"
    )

    /** Canonical keys, fixed order (form rendering ke liye). */
    fun orderedKeys(): List<String> = LABELS.keys.toList()

    /** true agar key canonical registry me hai. */
    fun isCanonical(key: String): Boolean = LABELS.containsKey(key)

    /**
     * Free-text tag → key.
     *  - canonical key seedha wapas (idempotent).
     *  - alias match → canonical (ya stable fallback).
     *  - purane display label ("Aadhar No. (आधार नं.)") → alias me ghul
     *    jayega (parenthesis + punctuation strip).
     *  - na mile → generic fallback: sanitized lowercase key
     *    (spaces → underscore).
     */
    fun normalizeTag(freeText: String): String {
        val t = freeText.trim()
        if (t.isEmpty()) return ""
        if (LABELS.containsKey(t)) return t
        var n = t.lowercase()
        // "(हिंदी)" wale hisse hatao — purane display labels ke liye.
        n = n.replace(Regex("\\(.*?\\)"), " ")
        // punctuation hatao (Devanagari + alnum + space rakho)
        n = n.replace(Regex("[^a-z0-9\\u0900-\\u097f ]"), " ")
        n = n.replace(Regex("\\s+"), " ").trim()
        if (n.isEmpty()) return ""
        if (LABELS.containsKey(n)) return n
        val alias = ALIASES[n]
        if (alias != null) return alias
        // generic fallback
        return n.replace(" ", "_")
    }

    /**
     * Bilingual label. Canonical → server-contract label.
     * Non-canonical (fallback) → readable prettify ("bank_account_no"
     * → "Bank account no").
     */
    fun labelOf(key: String): String {
        if (LABELS.containsKey(key)) return LABELS.getValue(key)
        val norm = normalizeTag(key)
        LABELS[norm]?.let { return it }
        if (norm.isEmpty()) return key
        return norm.split("_").joinToString(" ") { w ->
            w.replaceFirstChar { c -> c.uppercaseChar() }
        }
    }
}

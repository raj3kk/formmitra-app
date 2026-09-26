package com.formmitra.app.engine

/**
 * CorrectionPolicy — POINT 20 (MID-RUN CORRECTION) ka pure-Kotlin hissa.
 * ZERO Android imports.
 *
 * User kahe field galat hai + sahi value de → operator SIRF us field ko
 * update kare, baaki same run continue. STOP se alag (stop words par ye
 * trigger nahi hota).
 */
object CorrectionPolicy {

    private val CORRECTION_WORDS = listOf(
        "galat hai", "galat he", "sahi hai", "sahi he", "theek karo",
        "thik karo", "badlo", "badal do", "change karo", "sahi value",
        "wrong hai", "correct karo", "sudhar", "sudhaaro"
    )

    private val STOP_WORDS = listOf(
        "ruk jao", "ruk ja", "stop karo", "band karo", "cancel karo",
        "radd karo", "mat karo", "rehn do", "rehne do"
    )

    /** Kya ye message mid-run correction hai? (STOP nahi.) */
    fun isCorrection(text: String): Boolean {
        val l = text.lowercase()
        if (STOP_WORDS.any { it in l }) return false
        return CORRECTION_WORDS.any { it in l }
    }

    /** Sensitive field? (value chat/loop me mask rahegi.) */
    fun isSensitive(field: String): Boolean {
        val k = field.lowercase()
        return k.contains("otp") || k.contains("password") ||
            k.contains("pass") || k.contains("cvv") ||
            k.contains("pin") || k.contains("card")
    }

    fun notedText(label: String): String =
        "📝 Samajh gaya — \"$label\" ko update karke kaam aage badha raha hun. " +
            "Baaki sab same rahega, run wahi chalegi."

    fun appliedText(label: String): String =
        "✅ \"$label\" update ho gaya — kaam aage badh raha hai."
}

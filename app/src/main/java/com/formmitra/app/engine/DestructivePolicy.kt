package com.formmitra.app.engine

/**
 * DestructivePolicy — POINT 18 (DESTRUCTIVE ACTION GATE) ka pure-Kotlin
 * hissa. ZERO Android imports.
 *
 * Cancel/withdraw/delete/account-close jaise kaam HAMESHA non-waivable
 * confirmation maangte hain — permanent automation approval ise bypass
 * NAHI kar sakta. Saaf samjhao kya hoga, phir "Haan karo / Mat karo".
 */
object DestructivePolicy {

    private val KEYWORDS = listOf(
        "cancel", "withdraw", "delete", "remove", "account close",
        "close account", "radd", "wapas lo", "hatao", "mitao", "band karo",
        "unsubscribe", "terminate"
    )

    /** Kya ye action destructive gate maangta hai? */
    fun isDestructive(text: String): Boolean {
        val l = text.lowercase()
        return KEYWORDS.any { it in l }
    }

    fun title(): String = "⚠️ Pakka karna hai?"

    /** Simple Hinglish — kya hoga, saaf-saaf. */
    fun confirmText(what: String): String =
        "\"$what\"\n\nYe kaam PERMANENT ho sakta hai — wapas nahi hoga.\n" +
            "Soch lo, phir dabao."

    fun acceptLabel(): String = "Haan karo"
    fun declineLabel(): String = "Mat karo"

    fun doneText(what: String): String = "✅ \"$what\" — kar diya."
    fun cancelledText(): String = "Theek hai — kuch nahi kiya."
}

package com.formmitra.app.engine

/**
 * TrackHandoffPolicy — POINT 17 (APPLY → TRACKING HANDOFF) ka pure-Kotlin
 * hissa. ZERO Android imports.
 *
 * Apply poora hone par: "Iska status track karu?" — ek tap par unified
 * Track item (tracking Apply se alag rehti hai).
 */
object TrackHandoffPolicy {

    /** Label se tracking type ka andaza (server jo type bhejta hai wahi). */
    fun detectType(label: String): String {
        val l = label.lowercase()
        return when {
            listOf("zameen", "zamin", "khata", "khesra", "plot", "jameen")
                .any { it in l } -> "zameen"
            listOf("naukri", "job", "vacancy", "bharti", "sarkari")
                .any { it in l } -> "vacancy"
            listOf("scholarship", "chatravriti", "छात्रवृत्ति")
                .any { it in l } -> "scholarship"
            listOf("certificate", "praman", "प्रमाण").any { it in l } -> "certificate"
            listOf("yojana", "scheme").any { it in l } -> "scheme"
            listOf("document", "dastavej", "dastavez").any { it in l } -> "document"
            else -> "other"
        }
    }

    fun cardText(workName: String): String =
        "✅ \"$workName\" ho gaya!\n\n🔍 Iska status track karu? " +
            "Aage koi badlav hoga to khabar mil jayegi."

    fun acceptLabel(): String = "🔍 Haan, track karo"
    fun declineLabel(): String = "Nahi, rehne do"

    fun trackedText(typeLabel: String): String =
        "🔍 Tracking shuru — $typeLabel. Naya update aate hi bataunga."

    fun failedText(): String =
        "⚠️ Tracking shuru nahi ho payi — baad me History se try karo."
}

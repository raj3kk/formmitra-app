package com.formmitra.app.engine

/**
 * PrecheckLogic — pure Kotlin: /api/agent/precheck response ko verdict me badlo.
 * "Pehle check karo ho sakta hai ya nahi" — UI isi pe verdict card banata hai.
 * Input plain Map hai taaki org.json ke bina test ho sake.
 */
object PrecheckLogic {

    data class Verdict(
        val feasible: Boolean,
        val confidence: Double,
        val reason: String,
        val needs: List<String>,
        val learnedNote: String?,
        val estimatedSteps: Int?,
        val kbLink: String?
    )

    @Suppress("UNCHECKED_CAST")
    fun parse(m: Map<String, Any?>?): Verdict? {
        if (m == null) return null
        val needs = ((m["needs"] as? List<*>) ?: emptyList<Any>())
            .mapNotNull { (it as? String)?.trim()?.ifEmpty { null } }
        val learned = m["learned"] as? Map<String, Any?>
        val learnedNote = if (learned != null) {
            val succ = (learned["success_count"] as? Number)?.toInt() ?: 0
            val fail = (learned["fail_count"] as? Number)?.toInt() ?: 0
            val steps = (learned["avg_steps"] as? Number)?.toInt() ?: 0
            "Pichhla experience: $succ baar safal, $fail baar fail" +
                (if (steps > 0) " (~$steps steps)" else "")
        } else null
        val estSteps = (m["estimated_steps"] as? Number)?.toInt()?.takeIf { it >= 0 }
        return Verdict(
            feasible = m["feasible"] as? Boolean ?: false,
            confidence = (m["confidence"] as? Number)?.toDouble() ?: 0.0,
            reason = (m["reason"] as? String)?.trim()?.ifEmpty { "Pata nahi chala" }
                ?: "Pata nahi chala",
            needs = needs,
            learnedNote = learnedNote,
            estimatedSteps = estSteps,
            kbLink = (m["kb_link"] as? String)?.trim()?.ifEmpty { null }
        )
    }

    /** Chat bubble ke liye verdict text (Hinglish). */
    fun verdictText(v: Verdict): String {
        val sb = StringBuilder()
        if (v.feasible) {
            sb.append("✅ Ho sakta hai")
            val pct = (v.confidence * 100).toInt().coerceIn(0, 100)
            sb.append(" (~$pct% bharosa)")
        } else {
            sb.append("❌ Nahi ho sakta / mushkil hai")
        }
        sb.append("\n").append(v.reason)
        if (v.needs.isNotEmpty()) {
            val labels = mapOf(
                "login" to "🔑 login", "otp" to "📲 OTP",
                "payment" to "💰 payment", "docs" to "📎 documents"
            )
            sb.append("\nChahiye hoga: ")
            sb.append(v.needs.joinToString(", ") { labels[it] ?: it })
        }
        v.learnedNote?.let { sb.append("\n🧠 $it") }
        v.estimatedSteps?.let { sb.append("\n📝 ~$it steps lagenge") }
        return sb.toString()
    }
}

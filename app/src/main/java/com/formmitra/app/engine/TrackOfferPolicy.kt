package com.formmitra.app.engine

/**
 * TrackOfferPolicy — POINT 25 (TRACKING → ACTION OFFER) ka pure-Kotlin
 * hissa. ZERO Android imports — JVM self-test me seedha compile hota hai.
 *
 * Server contract (tracking/action-offers): FCM data {kind:"action_offer",
 * offer_id, offer_kind, goal, url} + realtime event "action_offer"
 * {offer_id, kind, title, detail, question, suggested_goal, suggested_url}.
 * Koi auto-run NAHI — offer sirf suggestion; tap par app
 * POST /api/agent/runs {goal, url} karta hai.
 */
object TrackOfferPolicy {

    /** Offer ke zaroori fields (bina inke card nahi). */
    fun isValid(offerId: String, question: String, goal: String): Boolean =
        offerId.isNotEmpty() && question.isNotEmpty() && goal.isNotEmpty()

    /** Kind → default sawaal (server question na bheje to fallback). */
    fun defaultQuestion(kind: String): String = when (kind) {
        "land_change" -> "Zameen ka naya record nikalun?"
        "new_vacancy" -> "Is vacancy me apply karun?"
        "new_scheme" -> "Is yojana me apply karun?"
        "deadline_near" -> "Deadline nazdeek hai — apply karun?"
        else -> "Is par action lun?"
    }

    /** Kind → default title (server title na bheje to fallback). */
    fun defaultTitle(kind: String): String = when (kind) {
        "land_change" -> "🌾 Zameen me badlav"
        "new_vacancy" -> "🏛️ Nayi vacancy"
        "new_scheme" -> "🎯 Nayi yojana"
        "deadline_near" -> "⏰ Deadline nazdeek"
        else -> "🔔 Tracking update"
    }

    /** Chat card ka text (simple Hinglish). */
    fun cardText(title: String, detail: String, question: String): String {
        val sb = StringBuilder("🔔 $title")
        if (detail.isNotEmpty()) sb.append("\n\n").append(detail)
        sb.append("\n\n❓ $question")
        return sb.toString()
    }

    /** Notification body (220 chars, server jaisa). */
    fun notifBody(detail: String, question: String): String {
        val b = if (detail.isNotEmpty()) "$detail — $question" else question
        return b.take(220)
    }

    fun acceptLabel(): String = "✅ Haan, shuru karo"
    fun declineLabel(): String = "⏭️ Rehne do"

    fun startedText(): String =
        "🚀 Kaam shuru ho gaya — History me progress dekho."

    fun failedText(): String =
        "⚠️ Kaam shuru nahi ho paya — dobara try karo."
}

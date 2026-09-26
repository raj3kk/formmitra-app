package com.formmitra.app.engine

/**
 * CardUnlockPolicy — POINT 24 (PERSISTENT CARD UNLOCK) ka pure-Kotlin
 * hissa. ZERO Android imports — JVM self-test me seedha compile hota hai.
 *
 * Usool (root, user ka faisla):
 *  - Chat entry par ek baar Card PIN → Card UNLOCK, aur UNLOCK HI RAHE —
 *    chat band ho, app background me jaye, background automation chale —
 *    dobara PIN nahi.
 *  - Unlock SIRF do surat me tootega: (a) user khud "Lock karo" kare,
 *    (b) user sign-out kare. KOI auto re-lock nahi (idle-timeout rule
 *    user ne reject kiya — background kaam atakna nahi chahiye).
 *  - Signed unlock token (server ka, 30-min TTL) existing mechanism hi
 *    rahega — naya crypto nahi. Token expire ho to wahi PIN se naya
 *    banta hai (server boundary); unlock FLAG phir bhi persistent.
 *  - Galat PIN 5 baar (15-min window) → temporary lock (server bhi yehi
 *    karta hai — app-side mirror, saaf Hinglish message ke saath).
 */
object CardUnlockPolicy {

    /** Galat PIN attempts ki limit (server ke barabar). */
    const val ATTEMPT_MAX = 5

    /** Attempt window: 15 minute (server ke barabar). */
    const val ATTEMPT_WINDOW_MS = 15L * 60L * 1000L

    /** Token expiry se pehle ka safety margin (stale token kabhi use nahi). */
    const val TOKEN_SKEW_MS = 60_000L

    /** Ek card ke PIN attempts ka state (prefs me "count|firstMs"). */
    data class AttemptState(val count: Int, val firstMs: Long)

    fun parseAttemptState(raw: String?): AttemptState? {
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split("|")
        if (parts.size != 2) return null
        val c = parts[0].toIntOrNull() ?: return null
        val f = parts[1].toLongOrNull() ?: return null
        if (c <= 0) return null
        return AttemptState(c, f)
    }

    fun formatAttemptState(s: AttemptState?): String? =
        if (s == null) null else "${s.count}|${s.firstMs}"

    /**
     * PIN entry abhi blocked hai? → blocked-until epoch ms, ya 0.
     * 5 galat attempts 15-min window me → window khatm hone tak block.
     * Window purani ho gayi → block nahi (state reset jaisa).
     */
    fun blockedUntilMs(state: AttemptState?, nowMs: Long): Long {
        if (state == null) return 0L
        if (nowMs - state.firstMs > ATTEMPT_WINDOW_MS) return 0L
        if (state.count < ATTEMPT_MAX) return 0L
        return state.firstMs + ATTEMPT_WINDOW_MS
    }

    /**
     * Ek PIN attempt ka result state me jodo.
     * Sahi PIN (ok=true) → state CLEAR (null).
     * Galat PIN → count badhao; window purani ho to nayi window shuru.
     */
    fun recordAttempt(
        state: AttemptState?,
        nowMs: Long,
        ok: Boolean
    ): AttemptState? {
        if (ok) return null
        if (state == null || nowMs - state.firstMs > ATTEMPT_WINDOW_MS) {
            return AttemptState(1, nowMs)
        }
        return AttemptState(state.count + 1, state.firstMs)
    }

    /** Signed card token abhi usable hai? (60s skew ke saath). */
    fun isTokenFresh(expiresAtMs: Long, nowMs: Long): Boolean =
        expiresAtMs > 0 && nowMs + TOKEN_SKEW_MS < expiresAtMs

    // ---------- user-facing text (simple Hinglish, ek baar / saaf) ----------

    /** Unlock ke baad subtle note — EK BAAR (revised text). */
    fun unlockNoteText(): String =
        "🔓 Card unlock hai — background kaam me bhi istemal hoga."

    /** 5 galat PIN → temporary lock message. */
    fun pinBlockedText(): String =
        "🔒 5 baar galat PIN dala — 15 minute ke liye lock hai. " +
            "Phir try karo."

    /** Manual lock confirm. */
    fun lockDoneText(cardName: String): String =
        "🔒 $cardName lock ho gaya. Dobara kholne ke liye PIN lagega."

    /** Chat entry par PIN maangne ka card text. */
    fun entryPinText(cardName: String): String =
        "🔒 $cardName locked hai.\n" +
            "Card kholne ke liye PIN dalo — phir dobara nahi maangega " +
            "(jab tak tum khud lock na karo ya sign-out na karo)."
}

package com.formmitra.app.engine

/**
 * WorkQueue — POINT 28 (PARALLEL WORK QUEUE) ka pure-Kotlin hissa.
 * ZERO Android imports — JVM self-test me seedha compile hota hai.
 *
 * Usool:
 *  - Doosra kaam pehle ko MAARTA nahi — line me lagta hai.
 *  - Pehla atka ho (10 min bina progress, user ka wait nahi) → doosra
 *    shuru ho sakta hai (stuck run park hota hai, mara nahi jata).
 *  - Har kaam ki alag history entry barkarar (run_id alag-alag).
 */
object WorkQueue {

    /** Itne ms bina progress + user-wait nahi → stuck. */
    const val STUCK_AFTER_MS: Long = 10 * 60 * 1000L

    data class Entry(
        val runId: String,
        val name: String,
        val taskJson: String,
        val enqueuedAt: Long
    )

    /** Line me lagao (same runId dobara nahi). */
    fun enqueue(q: List<Entry>, e: Entry): List<Entry> {
        if (e.runId.isEmpty()) return q
        if (q.any { it.runId == e.runId }) return q
        return q + e
    }

    /** Agla nikalo → (entry?, baaki queue). */
    fun dequeue(q: List<Entry>): Pair<Entry?, List<Entry>> =
        if (q.isEmpty()) null to q else q.first() to q.drop(1)

    fun remove(q: List<Entry>, runId: String): List<Entry> =
        q.filter { it.runId != runId }

    fun positionOf(q: List<Entry>, runId: String): Int =
        q.indexOfFirst { it.runId == runId } + 1 // 0 = nahi hai

    fun contains(q: List<Entry>, runId: String): Boolean =
        q.any { it.runId == runId }

    /** Stuck? (user ka wait ho to kabhi stuck nahi — wo park nahi hoga.) */
    fun isStuck(lastProgressMs: Long, nowMs: Long, waitingUser: Boolean): Boolean {
        if (waitingUser) return false
        if (lastProgressMs <= 0L) return false
        return nowMs - lastProgressMs >= STUCK_AFTER_MS
    }

    // ---------- user-facing text (simple Hinglish) ----------

    fun queuedText(name: String, position: Int): String =
        "📋 \"$name\" line me hai (#$position) — pehla kaam khatam hote hi shuru hoga."

    fun dequeuedText(name: String): String =
        "▶️ Line se \"$name\" shuru ho raha hai."

    fun stuckText(name: String): String =
        "⚠️ \"$name\" atka hua lag raha hai — line ka agla kaam shuru kar raha hun. " +
            "Atka kaam History se resume ho sakta hai."
}

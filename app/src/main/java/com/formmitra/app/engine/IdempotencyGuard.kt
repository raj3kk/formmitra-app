package com.formmitra.app.engine

import android.content.Context

/**
 * IdempotencyGuard — v36 refine point 8: ek hi kaam ke liye duplicate
 * agent runs KABHI nahi.
 *
 * Do level ka guard:
 *  1. runId — same runId dobara start nahi (crash-retry / double-tap /
 *     FCM + poll dono se trigger).
 *  2. goal+url fingerprint — same kaam 10 min ke andar dobara aaye to
 *     duplicate (user ne do baar "Kaam Shuru" dabaya, ya queue retry).
 *
 * Release: run terminal hote hi (done/failed/vetoed/cancelled) — taaki
 * wahi kaam dobara (nayi runId se) chal sake.
 *
 * Core logic pure hai (isDuplicate) — selftest me covered.
 */
object IdempotencyGuard {

    private const val PREFS = "formmitra_idempotency"
    private const val GOAL_WINDOW_MS = 10 * 60 * 1000L

    /** Process-level active runs (service restart par prefs backup). */
    private val active = mutableSetOf<String>()

    // v39: runId → goalKey mapping — release() par recent_goals se bhi
    // hatana hai (nahi to terminal run ke baad 10 min tak repeat block
    // rehta: "duplicate nahi banaya" ka root cause).
    private val runGoals = mutableMapOf<String, String>()

    /**
     * Pure duplicate check — testable.
     * @param activeRunIds abhi chal rahe runIds
     * @param recentGoals goalKey → last start epoch ms
     */
    fun isDuplicate(
        activeRunIds: Set<String>,
        recentGoals: Map<String, Long>,
        runId: String,
        goalKey: String,
        nowMs: Long
    ): Boolean {
        if (runId.isNotEmpty() && runId in activeRunIds) return true
        if (goalKey.isNotEmpty()) {
            val last = recentGoals[goalKey] ?: 0L
            if (nowMs - last < GOAL_WINDOW_MS) return true
        }
        return false
    }

    /** Goal fingerprint: normalized goal + domain (values nahi).
     *
     * v36 root-fix (selftest "goalKey normalized"): punctuation/case
     * normalize hota hai (LearnLogic.normalizeGoal jaisa) — "X!!" aur "X"
     * same fingerprint. Stop-words NAHI hatate — idempotency window chhota
     * hai aur conservative merge surakshit hai.
     */
    fun goalKey(goal: String, url: String): String {
        val g = goal.trim().lowercase()
            .replace(Regex("[^a-z0-9\\u0900-\\u097F ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
        val host = try {
            java.net.URL(url).host.lowercase()
        } catch (_: Exception) {
            url.lowercase().take(80)
        }
        return "$g@$host"
    }

    @Synchronized
    fun tryAcquire(ctx: Context, runId: String, goal: String, url: String): Boolean {
        val now = System.currentTimeMillis()
        val gk = goalKey(goal, url)
        val recent = readRecent(ctx)
        if (isDuplicate(active.toSet(), recent, runId, gk, now)) return false
        if (runId.isNotEmpty()) active.add(runId)
        // v39: release() ke liye mapping yaad rakho.
        if (runId.isNotEmpty() && gk.isNotEmpty()) runGoals[runId] = gk
        if (gk.isNotEmpty()) writeRecent(ctx, recent + (gk to now))
        return true
    }

    @Synchronized
    fun release(ctx: Context, runId: String) {
        try {
            active.remove(runId)
        } catch (_: Exception) { }
        // v39: run terminal → goal fingerprint bhi saaf karo taaki wahi
        // kaam nayi runId se dobara chal sake (10-min block nahi).
        try {
            val gk = runGoals.remove(runId)
            if (!gk.isNullOrEmpty()) {
                val recent = readRecent(ctx)
                if (recent.containsKey(gk)) {
                    writeRecent(ctx, recent - gk)
                }
            }
        } catch (_: Exception) { }
    }

    /** Sirf selftest/debug — active set ka size. */
    @Synchronized
    fun activeCount(): Int = active.size

    private fun readRecent(ctx: Context): Map<String, Long> {
        return try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val s = p.getString("recent_goals", "") ?: ""
            if (s.isEmpty()) return emptyMap()
            val now = System.currentTimeMillis()
            s.split(";").mapNotNull {
                val kv = it.split("=", limit = 2)
                if (kv.size != 2) null else {
                    val t = kv[1].toLongOrNull() ?: 0L
                    // Purane entries saaf (memory bloat nahi).
                    if (now - t < GOAL_WINDOW_MS) kv[0] to t else null
                }
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun writeRecent(ctx: Context, recent: Map<String, Long>) {
        try {
            val s = recent.entries.joinToString(";") { "${it.key}=${it.value}" }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("recent_goals", s.take(4000)).apply()
        } catch (_: Exception) { }
    }
}

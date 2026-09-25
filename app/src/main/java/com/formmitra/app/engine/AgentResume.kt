package com.formmitra.app.engine

import android.content.Context

/**
 * AgentResume — agent_run ka pending state (kill/reboot/offline ke baad
 * resume ke liye).
 *
 * AgentLoop agent_run shuru hote hi save karta hai, terminal state par clear.
 * G2 (Background Working Mode): run_id + category + steps_taken + summary
 * bhi save hote hain taaki wake par USI STEP se resume ho — shuru se nahi.
 * Server par task ka step-state pehle se hai (runs API) — WakeWorker usi ko
 * padhkar continue/restart decide karta hai.
 *
 * Boot par AUTO-RESUME NAHI hota — WorkingMode ON ho to WakeWorker app-open /
 * network-wake par resume karta hai; OFF ho to kuch nahi hota.
 */
object AgentResume {
    private const val PREFS = "fm_agent"
    private const val K_GOAL = "pending_goal"
    private const val K_URL = "pending_url"
    private const val K_TASK_ID = "pending_task_id"
    private const val K_RUN_ID = "pending_run_id"
    private const val K_CATEGORY = "pending_category"
    private const val K_STEPS = "pending_steps_taken"
    private const val K_SUMMARY = "pending_summary"

    data class PendingRun(
        val goal: String,
        val url: String,
        val taskId: String,
        val runId: String,
        val category: String,
        val stepsTaken: Int,
        val summary: String
    )

    fun save(
        ctx: Context,
        goal: String,
        url: String,
        taskId: String,
        runId: String = "",
        category: String = ""
    ) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(K_GOAL, goal)
                .putString(K_URL, url)
                .putString(K_TASK_ID, taskId)
                .putString(K_RUN_ID, runId)
                .putString(K_CATEGORY, category)
                .putInt(K_STEPS, 0)
                .putString(K_SUMMARY, "")
                .commit() // L2: sync — kill/crash par bhi resume state pakki
        } catch (_: Exception) {
        }
    }

    /** Har progress par: kahan tak pahunche (usi step se resume ke liye). */
    fun updateProgress(ctx: Context, stepsTaken: Int, summary: String = "") {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(K_STEPS, stepsTaken)
                .putString(K_SUMMARY, summary.take(300))
                .commit() // L2: sync — har step ki progress pakki save
        } catch (_: Exception) {
        }
    }

    /** PendingRun ya null (koi pending run nahi). */
    fun checkPending(ctx: Context): PendingRun? {
        return try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val goal = p.getString(K_GOAL, "") ?: ""
            if (goal.isEmpty()) return null
            PendingRun(
                goal = goal,
                url = p.getString(K_URL, "") ?: "",
                taskId = p.getString(K_TASK_ID, "") ?: "",
                runId = p.getString(K_RUN_ID, "") ?: "",
                category = p.getString(K_CATEGORY, "") ?: "",
                stepsTaken = p.getInt(K_STEPS, 0),
                summary = p.getString(K_SUMMARY, "") ?: ""
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(K_GOAL).remove(K_URL).remove(K_TASK_ID)
                .remove(K_RUN_ID).remove(K_CATEGORY)
                .remove(K_STEPS).remove(K_SUMMARY)
                .commit() // L2: sync — clear bhi pakka
        } catch (_: Exception) {
        }
    }
}

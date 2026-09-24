package com.formmitra.app.engine

import android.content.Context

/**
 * AgentResume — agent_run ka pending state (kill/reboot ke baad resume ke liye).
 *
 * AgentLoop agent_run shuru hote hi save karta hai, terminal state par clear.
 * Boot par AUTO-RESUME NAHI hota — user jab app khole tab UI agent
 * checkPending() se poochh ke user ki marzi se resume karega.
 */
object AgentResume {
    private const val PREFS = "fm_agent"
    private const val K_GOAL = "pending_goal"
    private const val K_URL = "pending_url"
    private const val K_TASK_ID = "pending_task_id"

    fun save(ctx: Context, goal: String, url: String, taskId: String) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(K_GOAL, goal)
                .putString(K_URL, url)
                .putString(K_TASK_ID, taskId)
                .apply()
        } catch (_: Exception) {
        }
    }

    /** Triple(goal, url, taskId) ya null (koi pending run nahi). */
    fun checkPending(ctx: Context): Triple<String, String, String>? {
        return try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val goal = p.getString(K_GOAL, "") ?: ""
            if (goal.isEmpty()) return null
            Triple(goal, p.getString(K_URL, "") ?: "", p.getString(K_TASK_ID, "") ?: "")
        } catch (_: Exception) {
            null
        }
    }

    fun clear(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(K_GOAL).remove(K_URL).remove(K_TASK_ID)
                .apply()
        } catch (_: Exception) {
        }
    }
}

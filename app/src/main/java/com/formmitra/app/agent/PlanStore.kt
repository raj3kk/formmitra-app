package com.formmitra.app.agent

import android.content.Context

/**
 * PlanStore — v36 refine point 1: pre-flight plan USER-VISIBLE.
 *
 * Kaam shuru hone se PEHLE bana plan ("ye link khulegi, ye ye steps
 * honge") sirf notification me nahi — chat me bhi dikhna chahiye, taaki
 * galat plan shuru me hi pakda jaye (misunderstanding check ka practical
 * roop: "Galat lage to turant batao").
 *
 * Flow: FormRunService.onPlan → save(runId, planText) → AgentChatView ka
 * poll loop takePlan(runId) karke ek baar chat bubble dikhata hai.
 * take = read + "dikha diya" mark (dobara nahi dikhega).
 */
object PlanStore {

    private const val PREFS = "formmitra_plan_store"

    fun save(ctx: Context, runId: String, planText: String) {
        if (runId.isEmpty() || planText.isEmpty()) return
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("plan_$runId", planText.take(1500))
                .putBoolean("shown_$runId", false)
                .putLong("at_$runId", System.currentTimeMillis())
                .apply()
        } catch (_: Exception) { }
    }

    /**
     * Plan lo aur "shown" mark karo. Pehli call par text milta hai,
     * uske baad null (dobara bubble nahi).
     */
    fun takePlan(ctx: Context, runId: String): String? {
        if (runId.isEmpty()) return null
        return try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getBoolean("shown_$runId", false)) return null
            val t = p.getString("plan_$runId", null) ?: return null
            // 30 min se purana plan — stale, mat dikhao.
            if (System.currentTimeMillis() - p.getLong("at_$runId", 0) > 30 * 60 * 1000L) {
                clear(ctx, runId)
                return null
            }
            p.edit().putBoolean("shown_$runId", true).apply()
            t
        } catch (_: Exception) { null }
    }

    fun clear(ctx: Context, runId: String) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("plan_$runId").remove("shown_$runId").remove("at_$runId")
                .apply()
        } catch (_: Exception) { }
    }
}

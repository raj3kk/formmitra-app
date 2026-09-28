package com.formmitra.app.engine

import android.content.Context

/**
 * OtpPark — v34 (Phase 2A, point 6: non-blocking OTP wait).
 *
 * Pehle: OTP gate par run ka thread UserPrompt.ask me 600s tak BLOCK
 * rehta tha — queue ka agla kaam atka rehta tha.
 *
 * Ab: OTP gate par run PARK hota hai (prompt utha + notification +
 * PendingPromptStore entry rehti hai), claim release, queue ka agla kaam
 * chalta hai. User ka jawab (manual dialog / SMS auto-fill) aate hi
 * onAnswered() parked run ko turant resume karta hai (WakeWorker wali
 * synthetic-task path se, usi step se).
 *
 * State SharedPreferences me — process kill par bhi parked runId milta hai
 * (AgentResume ke saath).
 */
object OtpPark {

    private const val PREFS = "formmitra_otp_park"
    private const val K_RUN = "parked_run_id"
    private const val K_SITE = "parked_site"
    private const val K_AT = "parked_at"
    private const val K_RETRY = "parked_retry"
    // v58: SAME-RUN — server run ID bhi park karo taaki resume par NAYA
    // server run na bane, purana hi continue ho.
    private const val K_AGENT_RUN = "parked_agent_run_id"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** @param retry galat-OTP par dobara maangne ki ginti (max 1). */
    fun park(ctx: Context, runId: String, site: String, retry: Int = 0,
             agentRunId: String = "") {
        try {
            prefs(ctx).edit()
                .putString(K_RUN, runId)
                .putString(K_SITE, site)
                .putLong(K_AT, System.currentTimeMillis())
                .putInt(K_RETRY, retry)
                .putString(K_AGENT_RUN, agentRunId)
                .apply()
        } catch (_: Exception) { }
    }

    fun unpark(ctx: Context) {
        try { prefs(ctx).edit().clear().apply() } catch (_: Exception) { }
    }

    fun parkedRunId(ctx: Context): String = try {
        prefs(ctx).getString(K_RUN, "") ?: ""
    } catch (_: Exception) { "" }

    fun isParked(ctx: Context, runId: String): Boolean =
        runId.isNotEmpty() && parkedRunId(ctx) == runId

    fun parkedSite(ctx: Context): String = try {
        prefs(ctx).getString(K_SITE, "") ?: ""
    } catch (_: Exception) { "" }

    fun parkedRetry(ctx: Context): Int = try {
        prefs(ctx).getInt(K_RETRY, 0)
    } catch (_: Exception) { 0 }

    /** v58: parked server run ID (resume par same run continue ho). */
    fun parkedAgentRunId(ctx: Context): String = try {
        prefs(ctx).getString(K_AGENT_RUN, "") ?: ""
    } catch (_: Exception) { "" }

    /**
     * User ka jawab aa gaya (manual dialog ya SMS auto-fill).
     * Parked run ho to turant resume — usi step se (AgentResume state).
     */
    fun onAnswered(ctx: Context, runId: String) {
        try {
            if (!isParked(ctx, runId)) return
            // Koi run pehle se chal raha ho to resume mat chhedo
            // (blocking-ask wala path jawab khud consume karega).
            if (com.formmitra.app.engine.FormRunService.activeTaskId != null) {
                return
            }
            val pending =
                com.formmitra.app.engine.AgentResume.checkPending(ctx) ?: return
            if (pending.runId != runId && pending.taskId != runId) return
            // v58: SAME-RUN — parked server run ID resume task me daalo
            // taaki AgentLoop naya server run na banaye, purana continue kare.
            val agentRunId = parkedAgentRunId(ctx)
            unpark(ctx)
            val task = com.formmitra.app.WakeWorker.buildResumeTask(
                pending, agentRunId
            )
            com.formmitra.app.engine.FormRunService.startWithTask(ctx, task)
        } catch (_: Exception) { }
    }
}

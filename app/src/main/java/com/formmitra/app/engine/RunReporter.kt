package com.formmitra.app.engine

import android.content.Context
import com.formmitra.app.agent.AgentApi

/**
 * RunReporter — agent_run ki server-side reporting (Phase 2).
 *
 * AgentLoop isi ko use karta hai; UI agent seedha AgentApi ke methods
 * (createRun/updateRun/uploadProof/listRuns) bhi call kar sakta hai.
 * Sab best-effort: server fail/offline ho to null/false, loop nahi rukta.
 *
 * Server contract:
 *  POST  /api/agent/runs        {goal, url, task_id} → {run_id}
 *  PATCH /api/agent/runs        {run_id, status, steps_taken, summary, error}
 *  POST  /api/agent/proof       {run_id, screenshot_b64} → {url}
 *  GET   /api/agent/runs        → {runs: [...]}
 */
object RunReporter {

    /** Naya run banao → run_id, ya null (fail → bina reporting chalao). */
    fun createRun(ctx: Context, goal: String, url: String, taskId: String): String? {
        return try {
            AgentApi.createRun(ctx, goal, url, taskId)
        } catch (_: Exception) {
            null
        }
    }

    /** Terminal state report karo. true = server ne maan liya. */
    fun updateRun(
        ctx: Context,
        runId: String,
        status: String,
        stepsTaken: Int,
        summary: String,
        error: String
    ): Boolean {
        if (runId.isEmpty()) return false
        return try {
            AgentApi.updateRun(ctx, runId, status, stepsTaken, summary, error)
        } catch (_: Exception) {
            false
        }
    }

    /** Submission proof (final screenshot) upload karo → proof URL ya null. */
    fun uploadProof(ctx: Context, runId: String, screenshotB64: String): String? {
        if (runId.isEmpty() || screenshotB64.isEmpty()) return null
        return try {
            AgentApi.uploadProof(ctx, runId, screenshotB64)
        } catch (_: Exception) {
            null
        }
    }
}

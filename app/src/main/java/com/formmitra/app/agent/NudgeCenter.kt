package com.formmitra.app.agent

import android.content.Context
import com.formmitra.app.engine.AgentResume
import com.formmitra.app.engine.FormRunService
import com.formmitra.app.engine.UserPrompt

/**
 * NudgeCenter — v24 N4 + N5 user nudges (bilingual, deep-link tap).
 *
 * N4: app background me gayi (MainActivity.onStop) + Working Mode OFF +
 *     pending kaam ho → "Kaam ruk gaya hai — app me jao / Working Mode
 *     on karo (Profile me)". Koi run abhi chal raha ho to nudge NAHI
 *     (kaam ruk nahi raha).
 * N5: beech me atka resume — AgentResume timestamps se detect (60 min tak
 *     koi progress nahi + koi run active nahi) → "Kaam atak gaya hai —
 *     app kholo ya Working Mode on karo". NotifPollWorker (15 min,
 *     background) + app-open par check hota hai.
 *
 * Dedupe: har nudge ek key par ek baar (naya stall/pending → naya key).
 * Sab fail-soft — notification na bane to bhi app nahi rukegi.
 */
object NudgeCenter {
    private const val PREFS = "fm_nudge"
    private const val K_LAST_NUDGE = "last_nudge_key"
    private const val K_LAST_STUCK = "last_stuck_key"
    /** N5: itne time tak koi progress nahi → atka mana. */
    private const val STUCK_AFTER_MS = 60 * 60 * 1000L

    /**
     * N4 — MainActivity.onStop se: app background me gayi.
     */
    fun onAppBackground(ctx: Context) {
        try {
            val appCtx = ctx.applicationContext
            if (WorkingMode.isEnabled(appCtx)) return
            // Koi run abhi chal raha ho to kaam ruk nahi raha — nudge nahi.
            if (FormRunService.activeTaskId != null) return
            val pending = AgentResume.checkPending(appCtx)
            val prompt = UserPrompt.pendingRequest()
            if (pending == null && prompt == null) return
            val key = "nudge_" +
                (pending?.runId?.ifEmpty { pending.goal.hashCode().toString() }
                    ?: "run") +
                "_" + (prompt?.runId ?: "noprompt")
            if (getLast(appCtx, K_LAST_NUDGE) == key) return
            setLast(appCtx, K_LAST_NUDGE, key)
            NotifCenter.notify(
                appCtx, NotifCenter.Cat.TASK,
                "Kaam ruk gaya hai ⏸️ (काम रुक गया है)",
                "App band hai aur Working Mode OFF hai — app me jao ya " +
                    "Profile me Working Mode on karo (चालू करो), taaki " +
                    "kaam aage badhe.",
                deepTab = "/profile",
                key = key
            )
        } catch (_: Exception) { }
    }

    /**
     * N5 — beech me atka resume. AgentResume timestamps se detect:
     * pending run hai + 60 min se koi progress nahi + koi run active nahi.
     */
    fun checkStuckResume(ctx: Context) {
        try {
            val appCtx = ctx.applicationContext
            val pending = AgentResume.checkPending(appCtx) ?: return
            val lastProgress = pending.lastProgressAt
            // Purana install (timestamp nahi) → pata nahi, false alert nahi.
            if (lastProgress <= 0) return
            if (FormRunService.activeTaskId == pending.runId) return
            if (System.currentTimeMillis() - lastProgress < STUCK_AFTER_MS) return
            val idPart = pending.runId.ifEmpty { pending.goal.hashCode().toString() }
            val key = "stuck_${idPart}_$lastProgress"
            if (getLast(appCtx, K_LAST_STUCK) == key) return
            setLast(appCtx, K_LAST_STUCK, key)
            val action = if (WorkingMode.isEnabled(appCtx)) "app kholo"
            else "app kholo ya Profile me Working Mode on karo (चालू करो)"
            NotifCenter.notify(
                appCtx, NotifCenter.Cat.TASK,
                "Kaam atak gaya hai ⚠️ (काम अटक गया है)",
                "“${pending.goal.take(80)}” aage nahi badh raha — $action.",
                deepTab = "/history",
                deepRunId = pending.runId,
                key = key
            )
        } catch (_: Exception) { }
    }

    private fun getLast(ctx: Context, k: String): String = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(k, "") ?: ""
    } catch (_: Exception) { "" }

    private fun setLast(ctx: Context, k: String, v: String) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(k, v).apply()
        } catch (_: Exception) { }
    }
}

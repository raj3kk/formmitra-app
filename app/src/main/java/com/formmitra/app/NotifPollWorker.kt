package com.formmitra.app

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.formmitra.app.agent.AgentApi
import com.formmitra.app.agent.NotifCenter
import com.formmitra.app.agent.NudgeCenter

/**
 * NotifPollWorker — v24 N2: kaam ke notifications ka polling fallback jo
 * BACKGROUND me bhi chalta hai (sirf foreground ke pollRunnable par
 * bharosa nahi).
 *
 * - FCM push (FmMessagingService) primary rahega; ye worker uska fallback
 *   hai — FCM na aaye / delayed ho tab bhi user ko pata chale.
 * - Sirf NOTIFICATIONS — koi task execute nahi karta. Isliye Working Mode
 *   OFF par bhi chalta rehta hai (Scheduler.cancelAll ise cancel nahi
 *   karta); OFF par background execution band rehta hai (existing).
 * - N5: har poll par stuck-resume check (AgentResume timestamps).
 * - Har 15 min, network-constrained, unique work "formmitra-notif-poll".
 * - Dedupe: (cat, key) — FormRunService ke terminal notifications ke
 *   SAME key scheme (runId) taaki doosri notification na bane, wahi
 *   update ho. Status-change par hi notify (pehli poll = baseline).
 *
 * Fail-soft: kuch bhi fail ho → Result.success (agli poll phir try karegi).
 */
class NotifPollWorker(appContext: Context, params: WorkerParameters) :
    Worker(appContext, params) {

    override fun doWork(): Result {
        return try {
            val ctx = applicationContext
            // N5: stuck-resume (local — network nahi chahiye)
            try {
                NudgeCenter.checkStuckResume(ctx)
            } catch (_: Exception) { }
            // N2: server runs poll (login ho to hi)
            try {
                pollRuns(ctx)
            } catch (e: Exception) {
                Log.i("NotifPollWorker", "pollRuns skip: ${e.message}")
            }
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }

    private fun pollRuns(ctx: Context) {
        val runs = AgentApi.listRuns(ctx) ?: return // 401/signed-out → skip
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ed = prefs.edit()
        var changed = false
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            val runId = r.optString("run_id").ifEmpty { r.optString("id") }
            if (runId.isEmpty()) continue
            val status = r.optString("status", "")
            if (status.isEmpty()) continue
            val last = prefs.getString(keyFor(runId), null)
            if (status == last) continue
            ed.putString(keyFor(runId), status)
            changed = true
            val name = r.optString("name", "kaam").ifEmpty { "kaam" }
            // Pehli poll = baseline. Lekin dhyaan-chahiye wale status
            // (needs_user/needs_admin) turant notify — user action abhi
            // chahiye, 15 min aur wait nahi.
            val baseline = last == null
            when (status) {
                "needs_user" -> notifyRun(
                    ctx, NotifCenter.Cat.DETAIL,
                    "Ek detail chahiye ✋ $name",
                    "Agent ko aapse ek detail chahiye — tap karke do, " +
                        "kaam aage badhega.",
                    runId, openPrompt = true
                )
                "needs_admin" -> if (!baseline) notifyRun(
                    ctx, NotifCenter.Cat.TASK,
                    "Dhyaan chahiye: $name",
                    "Captcha aaya hai — aapko dekhna hoga. (कैप्चा)",
                    runId
                )
                "vetoed" -> if (!baseline) notifyRun(
                    ctx, NotifCenter.Cat.TASK,
                    "Dhyaan chahiye: $name",
                    "Payment page mila — safety ke liye rok diya. (भुगतान)",
                    runId
                )
                "failed" -> if (!baseline) notifyRun(
                    ctx, NotifCenter.Cat.TASK,
                    "Dhyaan chahiye: $name",
                    "Kaam me dikkat aayi — tap karke dekho. (काम में दिक्कत)",
                    runId
                )
                "done" -> if (!baseline) notifyRun(
                    ctx, NotifCenter.Cat.TASK,
                    "Ho gaya ✅ $name",
                    "Form successfully bhar diya gaya. (हो गया)",
                    runId
                )
                // progress/running/claimed: spam nahi — skip.
            }
        }
        if (changed) ed.apply()
        // prefs bloat guard
        try {
            if (prefs.all.size > 300) prefs.edit().clear().apply()
        } catch (_: Exception) { }
    }

    /**
     * Key scheme FormRunService ke terminal notifications jaisa (runId)
     * taaki wahi notification update ho — duplicate na bane.
     */
    private fun notifyRun(
        ctx: Context,
        cat: NotifCenter.Cat,
        title: String,
        body: String,
        runId: String,
        openPrompt: Boolean = false
    ) {
        try {
            NotifCenter.notify(
                ctx, cat, title, body,
                deepTab = "/history",
                deepRunId = runId,
                openPromptRunId = if (openPrompt) runId else "",
                // FormRunService terminal key = runId (ya name) — wahi
                // notification update hogi, nayi nahi banegi.
                key = runId
            )
        } catch (_: Exception) { }
    }

    companion object {
        private const val PREFS = "fm_notif_poll"
        private fun keyFor(runId: String) = "st_$runId"
    }
}

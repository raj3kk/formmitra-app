package com.formmitra.app

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.formmitra.app.agent.WorkingMode
import com.formmitra.app.engine.AgentResume
import com.formmitra.app.engine.FormApi
import com.formmitra.app.engine.FormRunService
import org.json.JSONArray
import org.json.JSONObject

/**
 * WakeWorker — G2 (Background Working Mode) + J1 (FCM push) ka wake entry.
 *
 * Trigger: NetWake (offline→online), app-open, ya FCM push (FmMessagingService).
 * Sirf WorkingMode ON par kaam karta hai.
 *
 * Kaam (v41 order — cross-trigger root fix):
 *  1. FormApi.nextTask — fresh claimed task ho to WAHI chalao; saath me
 *     koi stale local pending ho to clear (naya kaam purane ko hijack nahi
 *     hone dega — e.g. scholarship ke saath zamin tracking nahi uthegi).
 *  2. Fresh task na ho → local pending run (AgentResume.checkPending) ho to
 *     USI STEP se resume: synthetic task JSON → FormRunService.startWithTask.
 *     AgentLoop ko startStep milta hai, shuru se nahi chalata.
 *  3. Duplicate-run guard: FormRunService.activeTaskId != null ho to kuch nahi.
 *
 * Polling fallback (I1/J1): ye worker FCM ke bina bhi chalta hai —
 * NetWake + app-open + 30-min FormTaskWorker chain me.
 */
class WakeWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!WorkingMode.isEnabled(ctx)) {
            Log.i("WakeWorker", "WorkingMode OFF — wake ignore")
            return Result.success()
        }
        return try {
            // Duplicate-run guard: koi run pehle se chal raha ho to mat chhedo.
            if (FormRunService.activeTaskId != null) {
                Log.i("WakeWorker", "run already active — skip")
                return Result.success()
            }
            // v41 ROOT FIX (cross-trigger): pehle FRESH task claim karo.
            // Pehle pending-first tha — purana parked run (doosri category ka)
            // naye kaam ko hijack kar leta tha (e.g. scholarship shuru karo →
            // purani zamin tracking background me uth jaati thi).
            // Ab: server par fresh task = user ne NAYA kaam shuru kiya →
            // purana pending stale hai → clear karke fresh chalao.
            // Fresh task nahi = crash/reboot case → pending resume (pehle jaisa).
            val freshTask = try {
                FormApi.nextTask(ctx)
            } catch (e: Exception) {
                Log.e("WakeWorker", "server ping/claim failed — retry", e)
                return Result.retry()
            }
            if (freshTask != null) {
                val stale = AgentResume.checkPending(ctx)
                if (stale != null) {
                    // v46 (A3 root fix): pending ko clear NAHI karna — ye
                    // OTP-parked ya needs_user wala deliberate resume ho sakta
                    // hai. Pehle clear ho jata tha → user ka OTP jawab aane par
                    // OtpPark.onAnswered ko pending nahi milta tha → parked run
                    // KABHI resume nahi hota tha (jawab silently drop).
                    // Ab: fresh task pehle chalega (v41 order barkarar), purana
                    // pending local line me lag jayega (data loss nahi).
                    // Sirf 24h+ purana aur parked-nahi pending hi stale hai.
                    val parkedId = try {
                        com.formmitra.app.engine.OtpPark.parkedRunId(ctx)
                    } catch (_: Exception) { "" }
                    val isParkedOtp = parkedId.isNotEmpty() &&
                        (stale.runId == parkedId || stale.taskId == parkedId)
                    val ageMs = System.currentTimeMillis() - stale.lastProgressAt
                    if (ageMs > 24 * 60 * 60 * 1000L && !isParkedOtp) {
                        Log.i(
                            "WakeWorker",
                            "fresh task aaya — 24h+ purana pending clear " +
                                "(goal=${stale.goal.take(40)})"
                        )
                        try { AgentResume.clear(ctx) } catch (_: Exception) { }
                    } else {
                        val pos = try {
                            com.formmitra.app.engine.FormRunService.enqueueTask(
                                ctx, buildResumeTask(stale)
                            )
                        } catch (_: Exception) { -1 }
                        Log.i(
                            "WakeWorker",
                            "fresh task aaya — pending resume line me #$pos " +
                                "(goal=${stale.goal.take(40)}, parkedOtp=$isParkedOtp)"
                        )
                    }
                }
                try {
                    FormRunService.startWithTask(ctx, freshTask)
                    Log.i("WakeWorker", "fresh claimed task handed off")
                } catch (e: Exception) {
                    Log.e("WakeWorker", "fresh handoff failed — retry", e)
                    return Result.retry()
                }
                return Result.success()
            }
            // 2. Koi fresh task nahi → local pending run ho to usi step se
            // resume (crash/reboot ke baad — G2).
            val pending = AgentResume.checkPending(ctx)
            if (pending != null) {
                Log.i(
                    "WakeWorker",
                    "resuming pending run at step ${pending.stepsTaken} (task=${pending.taskId})"
                )
                val task = buildResumeTask(pending)
                try {
                    FormRunService.startWithTask(ctx, task)
                } catch (e: Exception) {
                    Log.e("WakeWorker", "resume handoff failed — retry", e)
                    return Result.retry()
                }
                return Result.success()
            }
            // 3. Na fresh, na pending — normal idle.
            Result.success()
        } catch (e: Exception) {
            Log.e("WakeWorker", "wake failed — retry", e)
            Result.retry()
        }
    }

    companion object {
        /**
         * v34: parked-OTP resume ke liye synthetic task — WakeWorker wali
         * hi path (usi step se resume). OtpPark.onAnswered se reuse hota hai.
         */
        fun buildResumeTask(
            pending: com.formmitra.app.engine.AgentResume.PendingRun,
            agentRunId: String = ""
        ): org.json.JSONObject =
            org.json.JSONObject()
                .put("id", pending.taskId)
                .put("task_id", pending.taskId)
                .put("run_id", pending.runId.ifEmpty { pending.taskId })
                .put("name", pending.goal.take(80))
                .put("resumed", true)
                // v58: SAME-RUN — server run ID pass karo taaki AgentLoop
                // naya server run na banaye, purana hi continue kare.
                .put("agent_run_id", agentRunId)
                .put(
                    "steps",
                    org.json.JSONArray().put(
                        org.json.JSONObject()
                            .put("type", "agent_run")
                            .put("goal", pending.goal)
                            .put("url", pending.url)
                            .put("category", pending.category)
                            .put("start_step", pending.stepsTaken)
                            .put("resume_summary", pending.summary)
                            .put("agent_run_id", agentRunId)
                    )
                )

        /** Turant wake — network constraint ke saath (battery-friendly). */
        fun enqueue(ctx: Context) {
            try {
                val req = OneTimeWorkRequestBuilder<WakeWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                // L5: unique one-time work — 10 rapid taps = 1 hi wake,
                // wake storm nahi.
                WorkManager.getInstance(ctx.applicationContext)
                    .enqueueUniqueWork(
                        "fm-wake",
                        androidx.work.ExistingWorkPolicy.KEEP,
                        req
                    )
                Log.i("WakeWorker", "enqueued (unique)")
            } catch (t: Throwable) {
                Log.e("WakeWorker", "enqueue failed (non-fatal)", t)
            }
        }

        /**
         * v50: Pending wake cancel — delete / naya kaam par.
         */
        fun cancel(ctx: Context) {
            try {
                WorkManager.getInstance(ctx.applicationContext)
                    .cancelUniqueWork("fm-wake")
                Log.i("WakeWorker", "cancelled")
            } catch (t: Throwable) {
                Log.e("WakeWorker", "cancel failed (non-fatal)", t)
            }
        }
    }
}

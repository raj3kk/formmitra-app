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
 * Kaam:
 *  1. Server ping (lightweight): FormApi.nextTask ek hi call me ping +
 *     claim dono karta hai — alag ping call ki zaroorat nahi.
 *  2. Local pending run (AgentResume.checkPending) ho to USI STEP se resume:
 *     synthetic task JSON → FormRunService.startWithTask. AgentLoop ko
 *     startStep milta hai, shuru se nahi chalata.
 *  3. Pending na ho to FormApi.nextTask — naya claimed task mile to handoff.
 *  4. Duplicate-run guard: FormRunService.activeTaskId != null ho to kuch nahi.
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
            // 1. Pending local run → usi step se resume (G2).
            val pending = AgentResume.checkPending(ctx)
            if (pending != null) {
                Log.i(
                    "WakeWorker",
                    "resuming pending run at step ${pending.stepsTaken} (task=${pending.taskId})"
                )
                val task = JSONObject()
                    .put("id", pending.taskId)
                    .put("task_id", pending.taskId)
                    .put("run_id", pending.runId.ifEmpty { pending.taskId })
                    .put("name", pending.goal.take(80))
                    .put("resumed", true)
                    .put(
                        "steps",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "agent_run")
                                .put("goal", pending.goal)
                                .put("url", pending.url)
                                .put("category", pending.category)
                                .put("start_step", pending.stepsTaken)
                                .put("resume_summary", pending.summary)
                        )
                    )
                try {
                    FormRunService.startWithTask(ctx, task)
                } catch (e: Exception) {
                    Log.e("WakeWorker", "resume handoff failed — retry", e)
                    return Result.retry()
                }
                return Result.success()
            }
            // 2. Koi pending nahi → naya claimed task lao (server ping + claim).
            val task = try {
                FormApi.nextTask(ctx)
            } catch (e: Exception) {
                Log.e("WakeWorker", "server ping/claim failed — retry", e)
                return Result.retry()
            } ?: return Result.success() // koi task nahi — normal
            try {
                FormRunService.startWithTask(ctx, task)
                Log.i("WakeWorker", "claimed task handed off")
            } catch (e: Exception) {
                Log.e("WakeWorker", "claim handoff failed — retry", e)
                return Result.retry()
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("WakeWorker", "wake failed — retry", e)
            Result.retry()
        }
    }

    companion object {
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
                WorkManager.getInstance(ctx.applicationContext).enqueue(req)
                Log.i("WakeWorker", "enqueued")
            } catch (t: Throwable) {
                Log.e("WakeWorker", "enqueue failed (non-fatal)", t)
            }
        }
    }
}

package com.formmitra.app.engine

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.formmitra.app.agent.WorkingMode
import org.json.JSONObject

/**
 * RetryWorker — L1-UPGRADE ("retry with refill"): FormRunService me koi
 * task "failed" hua to 15 minute baad EK bounded auto-retry.
 *
 * - Task JSON wahi (fm_retry=1 flag ke saath) → FormRunService ko handoff.
 * - AgentLoop start me AgentResume ka pending state dekhta hai → USI STEP
 *   se resume; DetailStore ki saved details se refill (dobara nahi maangta).
 * - Sirf ek baar: fm_retry>=1 ho to dobara schedule nahi hota.
 * - WorkingMode OFF ho ya koi run active ho to skip (retry storm nahi).
 */
class RetryWorker(appContext: Context, params: WorkerParameters) :
    Worker(appContext, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!WorkingMode.isEnabled(ctx)) {
            Log.i("RetryWorker", "WorkingMode OFF — retry skip")
            return Result.success()
        }
        if (FormRunService.activeTaskId != null) {
            Log.i("RetryWorker", "run already active — retry skip")
            return Result.success()
        }
        return try {
            val taskJson = inputData.getString("task_json") ?: return Result.success()
            val task = try {
                JSONObject(taskJson)
            } catch (_: Exception) {
                return Result.success()
            }
            // Safety: flag na ho to bhi ek se zyada retry kabhi nahi
            if (task.optInt("fm_retry", 0) > 1) return Result.success()
            val name = inputData.getString("name") ?: task.optString("name", "form")
            Log.i("RetryWorker", "bounded retry: $name")
            try {
                FormRunService.startWithTask(ctx, task)
            } catch (e: Exception) {
                Log.e("RetryWorker", "handoff failed", e)
                return Result.retry()
            }
            Result.success()
        } catch (t: Throwable) {
            Log.e("RetryWorker", "retry failed", t)
            Result.success()
        }
    }
}

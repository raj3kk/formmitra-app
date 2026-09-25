package com.formmitra.app

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.formmitra.app.engine.FormApi
import com.formmitra.app.engine.FormRunService

// 30-min form-task poll: server se next claimed task lao; mile to
// foreground FormRunService ko execution handoff karo.
// Koi task nahi / 401 = success (koi notification nahi).
// Handoff fail = retry (C1: claimed task kabhi silently lost nahi hona chahiye).
class FormTaskWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        return try {
            // G2/J1 duplicate-run guard: WakeWorker ya pichla poll pehle se
            // koi run chala raha ho to naya claim mat karo.
            if (FormRunService.activeTaskId != null) {
                Log.i("FormTaskWorker", "run already active — skip")
                return Result.success()
            }
            val task = FormApi.nextTask(applicationContext) ?: return Result.success()
            val runId = task.optString("run_id").ifEmpty { task.optString("id") }
            // Service me handoff — service khud progress + terminal report karega.
            // NOTE: Android 12+ par background se startForegroundService() throw
            // kar sakta hai — neeche catch use pakadkar retry karega.
            FormRunService.startWithTask(applicationContext, task)
            Log.i("FormTaskWorker", "task handed off to FormRunService (run_id=$runId)")
            Result.success()
        } catch (e: Exception) {
            // nextTask = claim-and-return: task server par claimed ho chuka hai.
            // Release endpoint FormApi me nahi hai (sirf nextTask/report/captcha),
            // isliye retry — taaki claimed task silently lost na ho.
            Log.e("FormTaskWorker", "handoff failed — WorkManager retry karega", e)
            Result.retry()
        }
    }
}

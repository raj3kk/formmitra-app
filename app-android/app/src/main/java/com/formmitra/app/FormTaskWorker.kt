package com.formmitra.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.formmitra.app.engine.FormApi
import com.formmitra.app.engine.FormRunService

// 30-min form-task poll: server se next claimed task lao; mile to
// foreground FormRunService ko execution handoff karo.
// Koi task nahi / error / 401 = silently success (koi notification nahi).
class FormTaskWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        return try {
            val task = FormApi.nextTask(applicationContext) ?: return Result.success()
            // Service me handoff — service khud progress + terminal report karega.
            FormRunService.startWithTask(applicationContext, task)
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }
}

package com.formmitra.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object Scheduler {
    fun scheduleDigest(ctx: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val req = PeriodicWorkRequestBuilder<DigestWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "formmitra-digest", ExistingPeriodicWorkPolicy.KEEP, req
        )
    }

    fun scheduleFormTasks(ctx: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<FormTaskWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "formmitra-form-tasks", ExistingPeriodicWorkPolicy.KEEP, req
        )
    }

    /**
     * WorkingMode OFF: saare scheduled background workers cancel.
     * (Sirf is app ke WorkManager jobs — OS-level kuch nahi.)
     */
    fun cancelAll(ctx: Context) {
        try {
            WorkManager.getInstance(ctx).cancelAllWork()
        } catch (_: Exception) { }
    }

    /**
     * I3 (app-first): chat se task banne ke turant baad server-claim ke liye
     * turant ek one-time poll — 30-min periodic ka wait nahi. Server ka
     * "permission" nahi, sirf claim API; fail ho to periodic poll pakdega.
     */
    fun kickNow(ctx: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<FormTaskWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        } catch (_: Exception) { }
    }
}

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
     * v24 N2: notification polling fallback — har 15 min, background me bhi.
     * Sirf NOTIFICATIONS (koi execution nahi), isliye Working Mode OFF par
     * bhi schedule rehta hai — kaam ke updates (task/done/fail/needs_user)
     * OFF par bhi aate rahenge. FCM primary hai, ye uska fallback.
     * Idempotent (KEEP) — FmApp + BootReceiver dono se safe.
     */
    fun scheduleNotifPoll(ctx: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<NotifPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "formmitra-notif-poll", ExistingPeriodicWorkPolicy.KEEP, req
            )
        } catch (_: Exception) { }
    }

    /**
     * WorkingMode OFF: background AUTOMATION band — form-task poll + digest
     * cancel. v24 N2: notification poll ("formmitra-notif-poll") CANCEL NAHI
     * hota — wo sirf notifications hai, execution nahi; OFF par bhi kaam
     * ke updates aate rahenge. (Pehle cancelAllWork() sab udata tha —
     * targeted cancel hi sahi hai.)
     */
    fun cancelAll(ctx: Context) {
        try {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork("formmitra-form-tasks")
            wm.cancelUniqueWork("formmitra-digest")
        } catch (_: Exception) { }
    }

    /**
     * I3 (app-first): chat se task banne ke turant baad server-claim ke liye
     * turant ek one-time poll — 30-min periodic ka wait nahi. Server ka
     * "permission" nahi, sirf claim API; fail ho to periodic poll pakdega.
     *
     * v35: pehle saare exceptions SILENT nigal jata tha — kick fail ho to
     * session kabhi start nahi hota tha aur pata bhi nahi chalta tha
     * ("session band rehta hai" ka ek root cause). Ab Boolean + loud log.
     */
    fun kickNow(ctx: Context): Boolean {
        return try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<FormTaskWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
            true
        } catch (e: Exception) {
            android.util.Log.e("FmScheduler", "kickNow FAILED — 30-min periodic poll pakdega", e)
            false
        }
    }
}

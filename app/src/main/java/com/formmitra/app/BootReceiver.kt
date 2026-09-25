package com.formmitra.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            // FmApp.onCreate receiver se pehle chal chuka hai (WorkManager
            // init). Phir bhi guard: boot crash se bura kuch nahi.
            // G1: WorkingMode toggle ke hisaab se — ON ho to workers+NetWake,
            // OFF ho to schedule nahi (purani setting reboot par yaad rehti hai).
            try {
                com.formmitra.app.agent.WorkingMode.apply(context)
            } catch (t: Throwable) {
                Log.e("BootReceiver", "WorkingMode.apply failed (non-fatal)", t)
            }
            // Standalone (offline) tasks: reboot par dobara uthao — ye poori
            // tarah local hain, isliye auto-resume safe hai.
            try {
                StandaloneResumeWorker.resumeStandalone(context)
            } catch (e: Exception) {
                // H4: khaali catch me resume silently skip NAHI hoga — Log.e +
                // WorkManager one-time retry (2 min baad).
                Log.e("BootReceiver", "standalone resume failed — one-time retry scheduled", e)
                try {
                    val req = OneTimeWorkRequestBuilder<StandaloneResumeWorker>()
                        .setInitialDelay(2, TimeUnit.MINUTES)
                        .build()
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "formmitra-standalone-resume-retry",
                        ExistingWorkPolicy.REPLACE,
                        req
                    )
                } catch (e2: Exception) {
                    Log.e("BootReceiver", "retry schedule bhi fail hua", e2)
                }
            }
        }
    }
}

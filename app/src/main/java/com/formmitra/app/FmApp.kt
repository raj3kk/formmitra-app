package com.formmitra.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager

/**
 * FmApp — FormMitra ka Application class.
 *
 * KYUN CHAHIYE: manual build (tools/build-apk.sh) AAR manifests merge NAHI
 * karta, isliye final APK ke manifest me androidx.startup ka
 * InitializationProvider HAI HI NAHI. Bina uske WorkManager kabhi
 * auto-initialize nahi hota, aur MainActivity.onCreate me
 * Scheduler.scheduleDigest() → WorkManager.getInstance() →
 * IllegalStateException("WorkManager is not initialized properly") →
 * app khulte hi CRASH (v1–v14 tak yehi bug tha).
 *
 * FIX: Application.onCreate (har activity/receiver/service se PEHLE chalta
 * hai) me WorkManager explicitly initialize karo. Isse digest worker,
 * form-task worker, boot-resume — sab features pehle jaise kaam karte hain,
 * bas crash khatm.
 */
class FmApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        try {
            // Pehle se initialized ho (dobara call) to IllegalStateException
            // aayega — wo bhi pakad ke ignore, app nahi rukni chahiye.
            WorkManager.initialize(this, workManagerConfiguration)
            Log.i("FmApp", "WorkManager initialized")
        } catch (t: Throwable) {
            Log.e("FmApp", "WorkManager init failed (workers baad me retry karenge)", t)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}

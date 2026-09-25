package com.formmitra.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.formmitra.app.agent.FcmPush
import com.formmitra.app.agent.WorkingMode

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
        // G1: app khulne par Working Mode apply (reboot ke baad bhi yaad rehta hai).
        // ON ho to workers + NetWake lagte hain; OFF ho to kuch schedule nahi hota.
        try {
            WorkingMode.apply(this)
        } catch (t: Throwable) {
            Log.e("FmApp", "WorkingMode.apply failed (non-fatal)", t)
        }
        // G2: app-open wake — Working Mode ON ho aur koi pending run ho to
        // WakeWorker usi step se resume karega (duplicate-run guard andar hai).
        try {
            if (WorkingMode.isEnabled(this)) WakeWorker.enqueue(this)
        } catch (t: Throwable) {
            Log.e("FmApp", "WakeWorker enqueue failed (non-fatal)", t)
        }
        // J1: FCM init (best-effort). google-services.json me com.formmitra.app
        // client na ho to skip — polling fallback tab bhi zinda rehta hai.
        try {
            FcmPush.ensureInit(this)
        } catch (t: Throwable) {
            Log.e("FmApp", "FcmPush.ensureInit failed (non-fatal)", t)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}

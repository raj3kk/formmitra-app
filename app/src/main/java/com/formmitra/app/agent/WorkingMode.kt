package com.formmitra.app.agent

import android.content.Context
import android.util.Log
import com.formmitra.app.Scheduler

/**
 * WorkingMode — Profile tab ka "Working Mode" toggle (G1).
 *
 * ON: FormMitra background me bhi chalta rahe — foreground service +
 * WorkManager (battery-friendly intervals: form-tasks 30 min,
 * digest 6h, dono network-constrained). Network wake callback active.
 *
 * OFF: background automation band — workers cancel, wake callback hatao.
 * (Chalta hua run beech me nahi toda jata — wo poora hokar rukega;
 * naya background kaam shuru nahi hoga.)
 *
 * State SharedPreferences me persist — reboot ke baad bhi yaad rehta hai
 * (BootReceiver/FmApp isi ko padhkar apply karte hain).
 *
 * FmApp/WorkManager init (v15 fix) ko nahi chhoota — sirf work
 * schedule/cancel hota hai.
 */
object WorkingMode {
    private const val PREFS = "formmitra_working"
    private const val KEY_ON = "working_mode_on"

    fun isEnabled(ctx: Context): Boolean =
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ON, false)
        } catch (_: Exception) {
            false
        }

    /** Toggle persist + turant apply. */
    fun setEnabled(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ON, on).apply()
        } catch (_: Exception) { }
        apply(ctx)
    }

    /** Workers + network wake ko toggle ke hisaab se chalao/band karo. */
    fun apply(ctx: Context) {
        val appCtx = ctx.applicationContext
        try {
            if (isEnabled(appCtx)) {
                Scheduler.scheduleFormTasks(appCtx)
                Scheduler.scheduleDigest(appCtx)
                NetWake.register(appCtx)
                Log.i("WorkingMode", "ON — background automation active")
            } else {
                Scheduler.cancelAll(appCtx)
                NetWake.unregister(appCtx)
                Log.i("WorkingMode", "OFF — background automation band")
            }
        } catch (t: Throwable) {
            Log.e("WorkingMode", "apply failed (non-fatal)", t)
        }
    }
}

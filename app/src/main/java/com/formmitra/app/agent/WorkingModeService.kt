package com.formmitra.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.formmitra.app.MainActivity

/**
 * WorkingModeService — v24 N1 + v28 P13: WhopClip-style ALWAYS-ON
 * foreground presence + persistent notification ("user ke kill karne tak
 * rahe").
 *
 * v28 P13 semantics (badlaav):
 * - Service APP KHULNE PAR HI start hoti hai (FmApp.onCreate +
 *   WorkingMode.apply) — Working Mode toggle se INDEPENDENT.
 * - Working Mode toggle ab sirf AUTOMATION INTENSITY control karta hai
 *   (workers on/off); presence + saare promised notifications (N3 live
 *   status, N4 pending nudge, N5 stuck alert) OFF par bhi chalte hain.
 * - stop() ab sirf explicit user-kill path ke liye hai (toggle OFF par
 *   call NAHI hota).
 *
 * N0 VERIFY ka root cause (v24): WorkingMode ON par workers schedule hote
 * the, par koi foreground service kabhi start hi nahi hoti thi — isliye
 * user ko "notification bhi nahi aaya". Ye service us gap ko bharti hai.
 *
 * - startForegroundService (UI se = foreground, allowed). Notification
 *   ongoing (swipe se nahi hategi).
 * - BootReceiver (background) se start par Android 12+ FGS start throw
 *   kar sakta hai → fallback: wahi notification bina FGS ke (ongoing).
 *   User ke app kholte hi FmApp → service take-over.
 * - N3: FormRunService progress par updateLiveStatus() — persistent
 *   notification me LIVE dikhta hai kya chal raha hai + "working,
 *   don't close (बंद मत करो)". Kaam khatam → clearLiveStatus() wapas
 *   welcome text.
 * - Tap → MainActivity (default tab).
 *
 * Koi automation feature isse change nahi hota — sirf visibility.
 */
class WorkingModeService : Service() {

    companion object {
        private const val TAG = "WorkingModeService"
        const val NOTIF_ID = 4201
        private const val CHANNEL_ID = "fm_working_mode"

        @Volatile private var running = false
        // N3: live work status cache (service start se pehle aaye to bhi).
        @Volatile private var liveTitle: String? = null
        @Volatile private var liveText: String? = null

        /** v28 P13: presence start — app-open/boot par (toggle-independent). */
        fun start(ctx: Context) {
            try {
                val appCtx = ctx.applicationContext
                try {
                    appCtx.startForegroundService(
                        Intent(appCtx, WorkingModeService::class.java)
                    )
                } catch (t: Throwable) {
                    // Android 12+: background (BootReceiver) se FGS start
                    // allowed nahi → fallback: wahi notification, bina FGS.
                    Log.w(TAG, "FGS start fail — fallback ongoing notif", t)
                    postFallback(appCtx)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "start failed (non-fatal)", t)
            }
        }

        /**
         * v28 P13: sirf explicit user-kill path ke liye rakha hai.
         * Working Mode OFF par AB CALL NAHI HOTA (presence bani rehti hai).
         */
        fun stop(ctx: Context) {
            liveTitle = null
            liveText = null
            try {
                ctx.applicationContext.stopService(
                    Intent(ctx.applicationContext, WorkingModeService::class.java)
                )
            } catch (_: Exception) { }
            try {
                val nm = ctx.applicationContext
                    .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_ID)
            } catch (_: Exception) { }
        }

        /**
         * N3: koi kaam chal raha ho to persistent notification me LIVE
         * status. FormRunService ke onProgress se call hota hai.
         * Service na chal rahi ho (Working Mode OFF) → sirf cache, no-op.
         */
        fun updateLiveStatus(ctx: Context, title: String, text: String) {
            liveTitle = title
            liveText = text
            if (!running) return
            try {
                val appCtx = ctx.applicationContext
                val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
                nm.notify(NOTIF_ID, build(appCtx, title, text))
            } catch (_: Exception) { }
        }

        /** N3: kaam khatam — wapas default welcome text. */
        fun clearLiveStatus(ctx: Context) {
            liveTitle = null
            liveText = null
            if (!running) return
            try {
                val appCtx = ctx.applicationContext
                val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
                nm.notify(NOTIF_ID, build(appCtx, null, null))
            } catch (_: Exception) { }
        }

        private fun postFallback(ctx: Context) {
            try {
                ensureChannel(ctx)
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
                nm.notify(NOTIF_ID, build(ctx, liveTitle, liveText))
            } catch (_: Exception) { }
        }

        private fun ensureChannel(ctx: Context) {
            try {
                if (Build.VERSION.SDK_INT < 26) return
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
                nm.getNotificationChannel(CHANNEL_ID) ?:
                    nm.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            "FormMitra Working Mode",
                            NotificationManager.IMPORTANCE_LOW
                        )
                    )
            } catch (_: Exception) { }
        }

        private fun tapIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                ctx, 4201, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * v28 P13: user ka EXACT text, verbatim (kabhi paraphrase nahi):
         * "Welcome on FormMitra — kahin jaane ki zaroorat nahi, apna kaam
         * ghar baithe karein, 💯 surakshit"
         * ("background me chal raha hai" wali wording NAHI.)
         */
        private fun build(ctx: Context, title: String?, text: String?): Notification {
            ensureChannel(ctx)
            val t = title ?: "Welcome on FormMitra"
            val b = text ?: "kahin jaane ki zaroorat nahi, apna kaam " +
                "ghar baithe karein, 💯 surakshit"
            val nb = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(ctx, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
            }
            return nb.setContentTitle(t)
                .setContentText(b)
                .setStyle(
                    Notification.BigTextStyle().bigText(
                        "Welcome on FormMitra — kahin jaane ki zaroorat nahi, " +
                            "apna kaam ghar baithe karein, 💯 surakshit"
                    )
                )
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(tapIntent(ctx))
                .setOngoing(true)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, build(this, liveTitle, liveText))
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}

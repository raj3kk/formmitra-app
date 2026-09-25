package com.formmitra.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.formmitra.app.MainActivity
import com.formmitra.app.WakeWorker
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FmMessagingService — J1: FCM push receive.
 *
 * Server (sirf nazar rakhne wala) ye data-push bhej sakta hai:
 *   { "type": "wake" | "task" | "refresh" | "status",
 *     "title": "...", "body": "...", "task_id": "..." }
 *
 * - type = wake/task/refresh:
 *     Working Mode ON  → WakeWorker.enqueue → server ping + pending run ka
 *                        USI STEP se resume (ya naya claimed task).
 *     Working Mode OFF → automation start NAHI; sirf ek status notification
 *                        ("server se update aaya — Working Mode OFF hai").
 * - type = status (ya title/body ke saath koi bhi): hamesha notification.
 *
 * Manifest me MESSAGING_EVENT intent-filter ke saath declared hai.
 * Push na aaye / FCM configured na ho → polling fallback (FormTaskWorker
 * 30-min, NetWake, app-open WakeWorker) — feature kabhi dead nahi.
 */
class FmMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed — server ko register kar rahe")
        FcmPush.registerToken(applicationContext, token)
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        super.onMessageReceived(msg)
        try {
            val data = msg.data
            val type = (data["type"] ?: "").lowercase()
            val title = data["title"] ?: msg.notification?.title ?: "FormMitra"
            val body = data["body"] ?: msg.notification?.body ?: ""
            Log.i(TAG, "push aaya: type=$type")
            when (type) {
                "wake", "task", "refresh" -> {
                    if (WorkingMode.isEnabled(applicationContext)) {
                        Log.i(TAG, "Working Mode ON — wake + resume")
                        WakeWorker.enqueue(applicationContext)
                    } else {
                        Log.i(TAG, "Working Mode OFF — sirf status notification")
                        showStatus(
                            title.ifEmpty { "FormMitra" },
                            body.ifEmpty { "Server se update aaya — Working Mode OFF hai, automation start nahi hua." }
                        )
                    }
                }
                "status" -> showStatus(title, body.ifEmpty { "Server se update aaya hai." })
                else -> {
                    // Bina type ke notification-payload → seedha dikhao.
                    if (msg.notification != null || title.isNotEmpty()) {
                        showStatus(title, body)
                    } else {
                        Log.i(TAG, "unknown push type, ignore: $type")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onMessageReceived failed (non-fatal)", t)
        }
    }

    private fun showStatus(title: String, body: String) {
        try {
            val ctx = applicationContext
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "FormMitra Updates",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            val tap = PendingIntent.getActivity(
                ctx, 4300,
                Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val nb = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(ctx, CHANNEL_ID)
            } else {
                Notification.Builder(ctx)
            }
            nb.setContentTitle(title.take(60))
                .setContentText(body.take(200))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(tap)
                .setAutoCancel(true)
            nm.notify(NOTIF_ID, nb.build())
        } catch (t: Throwable) {
            Log.e(TAG, "status notification failed (non-fatal)", t)
        }
    }

    companion object {
        private const val TAG = "FmPush"
        private const val CHANNEL_ID = "formmitra_push"
        private const val NOTIF_ID = 4301
    }
}

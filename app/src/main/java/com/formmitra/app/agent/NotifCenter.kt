package com.formmitra.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.formmitra.app.MainActivity

/**
 * NotifCenter — K1: saari user notifications ka EK central hub.
 *
 * Do paths:
 *  1. FCM push (server bhejega) → FmMessagingService yahan route karta hai.
 *  2. Local fallback (app khud) — FCM na aaye tab bhi:
 *     - detail chahiye → UserPrompt listener (FmApp me registered)
 *     - approval (payment) → UserPrompt listener (kind=payment)
 *     - task complete/fail → FormRunService terminal report
 *     - document ready → AgentLoop proof upload / vault add
 *     - status/limit change → AgentChatView 429, server "status" push
 *
 * Categories (har ek ka apna Android 8+ channel + Profile me on/off):
 *  TASK     — task events (start/done/fail)              [fm_notif_tasks]
 *  DETAIL   — "ek detail chahiye — tap karke do" (HIGH)  [fm_notif_detail]
 *  APPROVAL — approval chahiye (payment, HIGH)           [fm_notif_approval]
 *  DOC      — document ready                             [fm_notif_docs]
 *  STATUS   — status/limit change (LOW)                  [fm_notif_status]
 *
 * Har notification:
 *  - tap → deep link: MainActivity khulti hai seedha sahi screen par
 *    (fm_deep_tab + fm_deep_run_id + fm_deep_prompt_run_id extras).
 *  - inbox me save hoti hai (NotifStore) — "🔔 tray".
 *  - app-icon badge update (BadgeHelper, fail-soft OEM paths).
 *  - category OFF ho to bilkul nahi dikhti (NotifSettings).
 *
 * Koi network call nahi — sirf local NotificationManager. Kabhi crash nahi.
 */
object NotifCenter {

    private const val TAG = "NotifCenter"

    /** Deep-link extras — MainActivity inhe padhkar sahi screen kholti hai. */
    const val EXTRA_TAB = "fm_deep_tab"
    const val EXTRA_RUN_ID = "fm_deep_run_id"
    const val EXTRA_PROMPT_RUN_ID = "fm_deep_prompt"

    enum class Cat(val channelId: String, val channelName: String, val importance: Int) {
        TASK("fm_notif_tasks", "FormMitra Tasks", NotificationManager.IMPORTANCE_DEFAULT),
        DETAIL("fm_notif_detail", "Detail chahiye", NotificationManager.IMPORTANCE_HIGH),
        APPROVAL("fm_notif_approval", "Approval chahiye", NotificationManager.IMPORTANCE_HIGH),
        DOC("fm_notif_docs", "Documents", NotificationManager.IMPORTANCE_DEFAULT),
        STATUS("fm_notif_status", "Status & limits", NotificationManager.IMPORTANCE_LOW);

        companion object {
            fun fromName(name: String): Cat? = try {
                valueOf(name.uppercase())
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Sab channels banao (Android 8+; neeche wale versions par no-op). */
    fun ensureChannels(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            for (c in Cat.values()) {
                try {
                    nm.getNotificationChannel(c.channelId)
                        ?: nm.createNotificationChannel(
                            NotificationChannel(c.channelId, c.channelName, c.importance)
                        )
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    /**
     * Notification dikhao.
     * @param cat category (channel + settings + inbox icon)
     * @param title chhota (60 chars tak)
     * @param body detail (240 chars tak)
     * @param deepTab tap par khulne wala tab ("/history" default)
     * @param deepRunId History me is run ki detail khule ("" = sirf tab)
     * @param openPromptRunId is run ka pending prompt khule ("" = nahi)
     * @param key dedupe/update key — same (cat,key) dobara aaye to wahi
     *            notification update hoti hai, nayi nahi banti.
     * @return notification id (0 = category OFF thi, kuch nahi dikhaya).
     */
    fun notify(
        ctx: Context,
        cat: Cat,
        title: String,
        body: String,
        deepTab: String = "/history",
        deepRunId: String = "",
        openPromptRunId: String = "",
        key: String = "",
        deepUrl: String = ""
    ): Int {
        try {
            if (!NotifSettings.isEnabled(ctx, cat)) {
                Log.i(TAG, "category ${cat.name} OFF — notification skip")
                return 0
            }
            val appCtx = ctx.applicationContext
            ensureChannels(appCtx)
            // v34 (Phase 2A, point 8): OTP kabhi notification preview me
            // poora nahi dikhega — central mask (sab notifications isi se guzarte hain).
            val safeTitle = try {
                com.formmitra.app.engine.GateLogic.maskOtp(title)
            } catch (_: Exception) { title }
            val safeBody = try {
                com.formmitra.app.engine.GateLogic.maskOtp(body)
            } catch (_: Exception) { body }
            val id = notifId(cat, if (key.isNotEmpty()) key else "$safeTitle|$safeBody")
            val tap = deepPendingIntent(appCtx, id, deepTab, deepRunId, openPromptRunId, deepUrl)
            val nb = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(appCtx, cat.channelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(appCtx)
            }
            nb.setContentTitle(safeTitle.take(60))
                .setContentText(safeBody.take(240))
                .setStyle(Notification.BigTextStyle().bigText(safeBody.take(400)))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(key.isNotEmpty())
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, nb.build())
            // Inbox + badge
            try {
                NotifStore.save(
                    appCtx, cat, title.take(60), body.take(400),
                    deepTab, deepRunId, openPromptRunId, key
                )
                updateBadge(appCtx)
            } catch (_: Exception) { }
            return id
        } catch (t: Throwable) {
            Log.e(TAG, "notify failed (non-fatal)", t)
            return 0
        }
    }

    /** Is (cat,key) ki notification hatao (detail mil gayi → "chahiye" wali gayab). */
    fun cancel(ctx: Context, cat: Cat, key: String) {
        try {
            val nm = ctx.applicationContext
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId(cat, key))
        } catch (_: Exception) { }
    }

    /** Deep-link PendingIntent → MainActivity seedha sahi screen par. */
    fun deepPendingIntent(
        ctx: Context,
        requestCode: Int,
        deepTab: String,
        deepRunId: String,
        openPromptRunId: String,
        deepUrl: String = ""
    ): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TAB, deepTab)
            if (deepRunId.isNotEmpty()) putExtra(EXTRA_RUN_ID, deepRunId)
            if (openPromptRunId.isNotEmpty()) putExtra(EXTRA_PROMPT_RUN_ID, openPromptRunId)
            if (deepUrl.isNotEmpty()) putExtra("deep_url", deepUrl)
        }
        return PendingIntent.getActivity(
            ctx, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Seedha MainActivity kholo (inbox tap se) — deep-link extras ke saath. */
    fun openDeepLink(ctx: Context, tab: String, runId: String, promptRunId: String) {
        try {
            val i = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_TAB, tab.ifEmpty { "/history" })
                if (runId.isNotEmpty()) putExtra(EXTRA_RUN_ID, runId)
                if (promptRunId.isNotEmpty()) putExtra(EXTRA_PROMPT_RUN_ID, promptRunId)
            }
            ctx.startActivity(i)
        } catch (t: Throwable) {
            Log.e(TAG, "openDeepLink failed (non-fatal)", t)
        }
    }

    // ---------- app-icon badge (K5) ----------
    //
    // Android me badge ka koi standard API nahi (OEM-specific). Teen bade
    // launcher paths try karte hain — sab fail-soft (na chale to badge nahi,
    // notification/inbox tab bhi kaam karte hain). Koi crash nahi.

    /** Unread inbox count ko launcher badge par lagao. */
    fun updateBadge(ctx: Context) {
        try {
            setBadge(ctx, NotifStore.unreadCount(ctx))
        } catch (_: Exception) { }
    }

    fun setBadge(ctx: Context, count: Int) {
        val appCtx = ctx.applicationContext
        val pkg = appCtx.packageName
        // 1. Samsung (sabse common): badge content provider
        try {
            val uri = Uri.parse("content://com.sec.badge/apps")
            val values = ContentValues().apply {
                put("package", pkg)
                put("class", "$pkg.MainActivity")
                put("badgecount", count)
            }
            appCtx.contentResolver.insert(uri, values)
        } catch (_: Exception) { }
        // 2. Sony
        try {
            appCtx.sendBroadcast(
                Intent("com.sonyericsson.home.action.UPDATE_BADGE").apply {
                    putExtra("com.sonyericsson.home.intent.extra.badge.ACTIVITY_NAME", "$pkg.MainActivity")
                    putExtra("com.sonyericsson.home.intent.extra.badge.PACKAGE_NAME", pkg)
                    putExtra("com.sonyericsson.home.intent.extra.badge.MESSAGE", "$count")
                    putExtra("com.sonyericsson.home.intent.extra.badge.SHOW_MESSAGE", count > 0)
                }
            )
        } catch (_: Exception) { }
        // 3. Huawei
        try {
            val uri = Uri.parse("content://com.huawei.android.launcher.settings/badge/")
            val bundle = android.os.Bundle().apply {
                putString("package", pkg)
                putString("class", "$pkg.MainActivity")
                putInt("badgenumber", count)
            }
            appCtx.contentResolver.call(uri, "change_badge", null, bundle)
        } catch (_: Exception) { }
    }

    // ---------- internal ----------

    private fun notifId(cat: Cat, key: String): Int {
        // Stable per (cat,key): dobara aane par wahi notification update ho.
        val h = (key.hashCode() and 0x7fffffff) % 100000
        return 50000 + cat.ordinal * 100000 + h
    }
}

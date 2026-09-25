package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NotifInboxView — K5: purani notifications ka tray/inbox.
 *
 * Khulta hai: History tab ke 🔔 button se, Profile → "📥 Notification Inbox" se.
 * Har entry: category icon + title + body + time. Tap → deep link
 * (wahi screen jahan notification le jati — History detail / prompt).
 * "✓ Sab padha" → unread zero + app-icon badge clear.
 */
object NotifInboxView {

    private val catIcon = mapOf(
        "TASK" to "📋",
        "DETAIL" to "✋",
        "APPROVAL" to "💰",
        "DOC" to "📄",
        "STATUS" to "📶"
    )

    /** Inbox dialog dikhao. onClosed: badge/count refresh ke liye callback. */
    fun show(ctx: Context, onClosed: (() -> Unit)? = null) {
        val act = ctx as? Activity ?: return
        act.runOnUiThread {
            try {
                val items = NotifStore.list(act)
                val layout = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(36, 16, 36, 8)
                }
                if (items.isEmpty()) {
                    layout.addView(TextView(act).apply {
                        text = "Abhi koi notification nahi aayi.\n" +
                            "Agent ka sawal, task complete/fail, approval — " +
                            "sab yahan dikhega."
                        textSize = 14f
                        setTextColor(Color.parseColor("#5F6368"))
                        setPadding(0, 24, 0, 24)
                        gravity = Gravity.CENTER
                    })
                }
                for (it in items) {
                    layout.addView(rowView(act, it))
                }
                val dlg = AlertDialog.Builder(act)
                    .setTitle("🔔 Notifications (${items.size})")
                    .setView(ScrollView(act).apply { addView(layout) })
                    .setPositiveButton("✓ Sab padha hua mark karo") { d, _ ->
                        NotifStore.markAllRead(act)
                        try { NotifCenter.setBadge(act, 0) } catch (_: Exception) { }
                        d.dismiss()
                        onClosed?.invoke()
                    }
                    .setNegativeButton("Band karo") { d, _ ->
                        d.dismiss()
                        onClosed?.invoke()
                    }
                    .create()
                dlg.show()
            } catch (_: Exception) { }
        }
    }

    private fun rowView(act: Activity, item: NotifStore.Item): LinearLayout {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
            background = with(UiKit) {
                act.tintCard(if (item.read) "#FFFFFF" else "#E8F5E9", "#E2E5EA")
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = with(UiKit) { act.dp(6) }
                setMargins(0, m, 0, m)
            }
            isClickable = true
            isFocusable = true
        }
        with(UiKit) { pressFeedback(row) }
        row.addView(TextView(act).apply {
            text = "${catIcon[item.cat] ?: "🔔"} ${item.title}" +
                (if (item.read) "" else "  • NAYA")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(
                if (item.read) Color.parseColor("#5F6368")
                else Color.parseColor("#202124")
            )
        })
        if (item.body.isNotEmpty()) {
            row.addView(TextView(act).apply {
                text = item.body
                textSize = 13f
                setTextColor(Color.parseColor("#5F6368"))
                maxLines = 3
            })
        }
        row.addView(TextView(act).apply {
            text = fmtTime(item.ts)
            textSize = 11f
            setTextColor(Color.parseColor("#9AA0A6"))
        })
        row.setOnClickListener {
            try {
                NotifStore.markRead(act, item.id)
                NotifCenter.updateBadge(act)
            } catch (_: Exception) { }
            NotifCenter.openDeepLink(act, item.tab, item.runId, item.promptRunId)
        }
        return row
    }

    private fun fmtTime(ts: Long): String = try {
        val now = System.currentTimeMillis()
        val diff = now - ts
        when {
            diff < 60_000 -> "abhi"
            diff < 3_600_000 -> "${diff / 60_000} min pehle"
            diff < 86_400_000 -> "${diff / 3_600_000} ghante pehle"
            else -> SimpleDateFormat("dd MMM, HH:mm", Locale("en", "IN")).format(Date(ts))
        }
    } catch (_: Exception) {
        ""
    }
}

package com.formmitra.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.formmitra.app.MainActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * FormRunService — foreground service jo ek claimed form-task ko
 * FormEngine se chalata hai aur server ko progress/terminal reports bhejta hai.
 *
 * Flow:
 *  1. FormTaskWorker task claim karke is service ko start karta hai.
 *  2. Service foreground notification ke saath task chalata hai:
 *     - pehla step {"type":"agent_run","goal","url"} ho to AgentLoop.runAgentTask
 *       (AI brain loop: screenshot+DOM → /api/agent/act → step execute → repeat)
 *     - nahi to FormEngine.runTask (v2 fixed steps)
 *  3. Progress: fixed tasks me RunPolicy cadence; agent mode me har AI step.
 *  4. Terminal report: done | failed | vetoed | needs_user (+ needs_admin) + notification.
 *
 * Notifications Hinglish me (user-facing copy):
 *  start: "Form bharna shuru: <name>"
 *  done:  "Ho gaya ✅ <name>"
 *  fail/veto/admin: "Dhyaan chahiye: <name> — <reason>"
 */
class FormRunService : Service() {

    companion object {
        const val CHANNEL_ID = "formmitra_forms"
        const val EXTRA_TASK_JSON = "task_json"
        private const val NOTIF_ID = 4101
        private const val DONE_NOTIF_ID = 4102

        fun startWithTask(ctx: Context, task: JSONObject) {
            val intent = Intent(ctx, FormRunService::class.java).apply {
                putExtra(EXTRA_TASK_JSON, task.toString())
            }
            ctx.startForegroundService(intent)
        }
    }

    private var runThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskJson = intent?.getStringExtra(EXTRA_TASK_JSON)
        if (taskJson.isNullOrEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val task = try { JSONObject(taskJson) } catch (_: Exception) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val name = task.optString("name", "form")
        ensureChannel()
        startForeground(NOTIF_ID, buildNotif("Form bhar raha hai: $name", "Kaam chal raha hai…"))
        notifySimple(NOTIF_ID + 10, "Form bharna shuru: $name", "FormMitra automation kaam kar raha hai")

        runThread = Thread({
            runTask(task, name)
            stopSelf(startId)
        }, "formmitra-run").also { it.start() }

        return START_NOT_STICKY
    }

    private fun runTask(task: JSONObject, name: String) {
        val runId = task.optString("run_id", "")
        val stepsJson = task.optJSONArray("steps") ?: JSONArray()
        val total = stepsJson.length()
        var lastReported = 0

        val engine = FormEngine(this)
        val firstStep = stepsJson.optJSONObject(0)
        val result = try {
            if (firstStep != null && firstStep.optString("type") == "agent_run") {
                // AI agent mode — AgentLoop har step khud decide karta hai
                val goal = firstStep.optString("goal", name).ifEmpty { name }
                val url = firstStep.optString("url", task.optString("target_url", ""))
                var offlineMode = false
                var standaloneMode = false
                AgentLoop.runAgentTask(
                    this, engine, goal, url, runId, 40,
                    onProgress = { aiStep ->
                        val mode = when {
                            standaloneMode -> "agent_standalone"
                            offlineMode -> "agent_offline"
                            else -> "agent"
                        }
                        val payload = JSONObject()
                            .put("status", "progress")
                            .put("current_step", aiStep)
                            .put("total_steps", 40)
                            .put("mode", mode)
                        FormApi.report(this, runId, payload)
                        val suffix = when {
                            standaloneMode -> " (standalone)"
                            offlineMode -> " (offline mode)"
                            else -> ""
                        }
                        updateOngoing("Form bhar raha hai: $name", "AI step $aiStep / 40$suffix")
                    },
                    onOfflineMode = { offlineMode = true },
                    onStandaloneMode = { standaloneMode = true }
                )
            } else {
                engine.runTask(task) { step1Based, _ ->
                    // progress cadence: RunPolicy (short: har step, long: har 3)
                    if (RunPolicy.shouldReportProgress(step1Based, total) && step1Based != lastReported) {
                        lastReported = step1Based
                        val payload = JSONObject()
                            .put("status", "progress")
                            .put("current_step", step1Based)
                            .put("total_steps", total)
                        FormApi.report(this, runId, payload)
                        updateOngoing("Form bhar raha hai: $name", "Step $step1Based / $total")
                    }
                }
            }
        } catch (t: Throwable) {
            FormEngine.RunResult("failed", "engine crash: ${t.message}", JSONArray())
        }

        // Terminal report — hamesha bhejo (best-effort)
        val terminal = JSONObject()
            .put("status", result.status)
            .put("current_step", total)
            .put("total_steps", total)
            .put("step_results", result.stepResults)
        if (result.status == "done") {
            terminal.put("summary", result.summary)
        } else {
            terminal.put("error", result.summary)
        }
        try {
            FormApi.report(this, runId, terminal)
        } catch (_: Exception) { }

        // User notification (Hinglish)
        when (result.status) {
            "done" -> notifySimple(
                DONE_NOTIF_ID, "Ho gaya ✅ $name",
                "Form successfully bhar diya gaya."
            )
            "vetoed" -> notifySimple(
                DONE_NOTIF_ID, "Dhyaan chahiye: $name",
                "Payment page mila — safety ke liye rok diya."
            )
            "needs_admin" -> notifySimple(
                DONE_NOTIF_ID, "Dhyaan chahiye: $name",
                "Captcha aaya hai — aapko dekhna hoga."
            )
            "needs_user" -> notifySimple(
                DONE_NOTIF_ID, "Dhyaan chahiye: $name",
                result.summary.take(120)
            )
            else -> notifySimple(
                DONE_NOTIF_ID, "Dhyaan chahiye: $name",
                result.summary.take(120)
            )
        }
    }

    // ---------------- notifications ----------------

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "FormMitra Form Tasks",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    private fun tapIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 4200, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotif(title: String, text: String): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(tapIntent())
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(tapIntent())
                .build()
        }
    }

    private fun updateOngoing(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(title, text))
    }

    private fun notifySimple(id: Int, title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val nb = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        nb.setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tapIntent())
            .setAutoCancel(true)
        nm.notify(id, nb.build())
    }

    override fun onDestroy() {
        try { runThread?.interrupt() } catch (_: Exception) { }
        super.onDestroy()
    }
}

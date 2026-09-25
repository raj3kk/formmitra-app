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
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.formmitra.app.MainActivity
import com.formmitra.app.agent.CategoryStore
import com.formmitra.app.agent.FlowAnnouncer
import com.formmitra.app.agent.NotifCenter
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

        /**
         * Duplicate-run guard (G2/J1): koi run chal raha ho to WakeWorker /
         * FormTaskWorker naya handoff na kare. Service start par set,
         * runTask khatam par clear (finally me).
         */
        @Volatile
        var activeTaskId: String? = null
            private set
        private val activeLock = Any()

        /**
         * L5: atomic run claim — do startWithTask ek saath aaye to sirf
         * pehla claim jeetega, doosra handoff ignore hoga (overwrite nahi).
         */
        private fun tryClaim(taskId: String): Boolean = synchronized(activeLock) {
            if (activeTaskId != null) return false
            activeTaskId = taskId
            true
        }

        private fun releaseClaim(taskId: String) = synchronized(activeLock) {
            if (activeTaskId == taskId) activeTaskId = null
        }

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
        // L4: flow milestone — TTS + notification
        try {
            FlowAnnouncer.say(this, "Form bharna shuru ho gaya: $name")
        } catch (_: Exception) { }

        // Duplicate-run guard (atomic): pehle se koi run active ho to
        // ye handoff ignore — overwrite/double-run nahi.
        val claimId = task.optString("run_id").ifEmpty { task.optString("id") }
        if (!tryClaim(claimId)) {
            try {
                android.util.Log.i(
                    "FormRunService",
                    "duplicate handoff ignored (active=$activeTaskId)"
                )
            } catch (_: Exception) { }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        runThread = Thread({
            try {
                runTask(task, name)
            } finally {
                releaseClaim(claimId)
            }
            stopSelf(startId)
        }, "formmitra-run").also { it.start() }

        return START_NOT_STICKY
    }

    private fun runTask(task: JSONObject, name: String) {
        val runId = task.optString("run_id", "")
        val stepsJson = task.optJSONArray("steps") ?: JSONArray()
        val total = stepsJson.length()
        var lastReported = 0
        // Standalone (offline) task: server bilkul nahi — Groq direct + local store
        val standalone = task.optBoolean("standalone", false) ||
            runId.startsWith("local-")

        val engine = FormEngine(this)
        val firstStep = stepsJson.optJSONObject(0)
        val result = try {
            if (firstStep != null && firstStep.optString("type") == "agent_run") {
                // AI agent mode — AgentLoop har step khud decide karta hai
                val goal = firstStep.optString("goal", name).ifEmpty { name }
                val url = firstStep.optString("url", task.optString("target_url", ""))
                // Category-wise full automation: step/task/device-local se
                // category nikaalo → AgentLoop ke har act() call me jayegi
                val category = firstStep.optString("category", "")
                    .ifEmpty { task.optString("category", "") }
                    .ifEmpty {
                        CategoryStore.takeForTask(
                            this,
                            task.optString("id"), runId, task.optString("task_id")
                        )
                    }
                var offlineMode = false
                var standaloneMode = false
                // G2 resume: WakeWorker synthetic task me start_step bhejta hai —
                // AgentLoop usi step se continue karega (shuru se nahi).
                val startStep = firstStep.optInt("start_step", 0).coerceAtLeast(0)
                AgentLoop.runAgentTask(
                    this, engine, goal, url, runId, 40,
                    onProgress = { aiStep ->
                        val mode = when {
                            standalone || standaloneMode -> "agent_standalone"
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
                            standalone || standaloneMode -> " (standalone)"
                            offlineMode -> " (offline mode)"
                            else -> ""
                        }
                        updateOngoing("Form bhar raha hai: $name", "AI step $aiStep / 40$suffix")
                    },
                    onOfflineMode = { offlineMode = true },
                    onStandaloneMode = { standaloneMode = true },
                    forceStandalone = standalone,
                    category = category,
                    startStep = startStep
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
        // Standalone task ka terminal status local store me (reboot-resume ke liye)
        if (standalone) {
            try {
                StandaloneStore.setStatus(this, runId, result.status)
            } catch (_: Exception) { }
        }

        // User notification (Hinglish) — K1: NotifCenter se (channel +
        // Profile on/off + inbox + badge). Tap → History + run detail.
        // L3a: technical summary kabhi user ko mat dikhao — UserText.friendly.
        val runKey = runId.ifEmpty { name }
        when (result.status) {
            "done" -> {
                notifyEvent(
                    NotifCenter.Cat.TASK, "Ho gaya ✅ $name",
                    "Form successfully bhar diya gaya.", runId, runKey
                )
                try {
                    FlowAnnouncer.say(this, "Kaam ho gaya: $name")
                } catch (_: Exception) { }
            }
            "vetoed" -> notifyEvent(
                NotifCenter.Cat.TASK, "Dhyaan chahiye: $name",
                "Payment page mila — safety ke liye rok diya.", runId, runKey
            )
            "needs_admin" -> notifyEvent(
                NotifCenter.Cat.TASK, "Dhyaan chahiye: $name",
                "Captcha aaya hai — aapko dekhna hoga.", runId, runKey
            )
            "needs_user" -> notifyEvent(
                NotifCenter.Cat.DETAIL, "Ek detail chahiye ✋ $name",
                UserText.friendly(result.summary.take(120))
                    .ifEmpty { "Agent ko aapse ek detail chahiye — tap karke do." },
                runId, runKey
            )
            else -> {
                notifyEvent(
                    NotifCenter.Cat.TASK, "Dhyaan chahiye: $name",
                    UserText.friendly(result.summary.take(200)), runId, runKey
                )
                try {
                    FlowAnnouncer.say(this, "Kaam me dikkat aayi: $name")
                } catch (_: Exception) { }
                // L1-UPGRADE: retry with refill — fail hua to EK baar bounded
                // auto-retry (15 min baad), saved details se refill hokar.
                // Vetoed/needs_user par kabhi nahi (wahan user ka action chahiye).
                scheduleOneRetry(task, name, result.status)
            }
        }
    }

    /**
     * L1-UPGRADE: "failed" par ek bounded auto-retry. DetailStore me saved
     * details se refill hokar wahi task dobara chalega (AgentResume ka
     * pending state bana rehta hai → same step se resume).
     * - Sirf status "failed" par (vetoed/needs_user/needs_admin/done nahi).
     * - Sirf ek baar (task JSON me fm_retry flag).
     * - Standalone tasks par nahi (server claim flow ka hissa nahi).
     */
    private fun scheduleOneRetry(task: JSONObject, name: String, status: String) {
        try {
            if (status != "failed") return
            if (task.optBoolean("standalone", false)) return
            if (task.optInt("fm_retry", 0) >= 1) return
            val runId = task.optString("run_id", "")
            if (runId.isEmpty()) return
            val retryTask = JSONObject(task.toString()).put("fm_retry", 1)
            val req = OneTimeWorkRequestBuilder<RetryWorker>()
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setInputData(
                    workDataOf(
                        "task_json" to retryTask.toString(),
                        "name" to name
                    )
                )
                .addTag("fm_retry_$runId")
                .build()
            WorkManager.getInstance(this).enqueue(req)
            try {
                NotifCenter.notify(
                    this, NotifCenter.Cat.STATUS,
                    "Ek baar phir try karega 🔁",
                    "$name — 15 minute me saved details se apne aap dobara chalega.",
                    deepTab = "/history", deepRunId = runId,
                    key = "retry_$runId"
                )
            } catch (_: Exception) { }
        } catch (_: Exception) { }
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
        // K1: start notice bhi NotifCenter se — channel + inbox + deep link.
        // (id param ab NotifCenter ke stable id me map hota hai.)
        try {
            NotifCenter.notify(
                this, NotifCenter.Cat.TASK, title, text,
                deepTab = "/history", key = "start-$title"
            )
        } catch (_: Exception) { }
    }

    /**
     * K1: terminal event notification — tap seedha History + is run ki
     * detail par le jata hai (deep link).
     */
    private fun notifyEvent(
        cat: NotifCenter.Cat,
        title: String,
        text: String,
        runId: String,
        key: String
    ) {
        try {
            NotifCenter.notify(
                this, cat, title, text,
                deepTab = "/history",
                deepRunId = runId,
                key = key
            )
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        try { runThread?.interrupt() } catch (_: Exception) { }
        super.onDestroy()
    }
}

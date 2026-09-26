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
import com.formmitra.app.agent.DetailStore
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
         * v36 (point 10): 1-active-session — [Band karo] action ka intent.
         * Active run ko turant rokta hai (slot free → naya kaam shuru ho
         * sakta hai). Sirf user ke apne active run par lagta hai.
         */
        const val ACTION_CANCEL_ACTIVE = "com.formmitra.app.engine.CANCEL_ACTIVE"

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

        // ---------- v36 (point 10): owner unlimited ----------
        //
        // Normal user = kul 1 active kaam (server + local claim dono enforce
        // karte hain). Owner/admin unlimited — doosra kaam REJECT nahi hota,
        // local line me lagta hai (pehla khatam hote hi pumpQueue apne aap
        // shuru karta hai). Flag persistent hai taaki background service bhi
        // jaan sake (MainActivity session-only rakhta hai).
        private const val OWNER_PREFS = "formmitra_owner"
        private const val OWNER_KEY = "is_owner"

        fun setOwnerDevice(ctx: Context, b: Boolean) {
            try {
                ctx.getSharedPreferences(OWNER_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(OWNER_KEY, b).apply()
            } catch (_: Exception) { }
        }

        fun isOwnerDevice(ctx: Context): Boolean = try {
            ctx.getSharedPreferences(OWNER_PREFS, Context.MODE_PRIVATE)
                .getBoolean(OWNER_KEY, false)
        } catch (_: Exception) { false }

        fun startWithTask(ctx: Context, task: JSONObject) {
            val intent = Intent(ctx, FormRunService::class.java).apply {
                putExtra(EXTRA_TASK_JSON, task.toString())
            }
            ctx.startForegroundService(intent)
        }

        /**
         * v36 (point 10): [Band karo] — active run band karne ki request.
         * Notification action se aata hai.
         */
        fun requestCancelActive(ctx: Context) {
            val intent = Intent(ctx, FormRunService::class.java).apply {
                action = ACTION_CANCEL_ACTIVE
            }
            ctx.startForegroundService(intent)
        }

        // ---------- POINT 28: parallel work queue (persistent) ----------

        private const val QPREFS = "formmitra_workqueue"
        private const val QKEY = "queue"

        private fun qPrefs(ctx: Context) =
            ctx.getSharedPreferences(QPREFS, Context.MODE_PRIVATE)

        /** Queue ka snapshot (persisted — reboot par bhi). */
        fun queueSnapshot(ctx: Context): List<WorkQueue.Entry> {
            val out = mutableListOf<WorkQueue.Entry>()
            try {
                val arr = JSONArray(qPrefs(ctx).getString(QKEY, null) ?: "[]")
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(
                        WorkQueue.Entry(
                            runId = o.optString("runId"),
                            name = o.optString("name"),
                            taskJson = o.optString("taskJson"),
                            enqueuedAt = o.optLong("enqueuedAt")
                        )
                    )
                }
            } catch (_: Exception) { }
            return out
        }

        private fun persistQueue(ctx: Context, q: List<WorkQueue.Entry>) {
            try {
                val arr = JSONArray()
                for (e in q) {
                    arr.put(
                        JSONObject()
                            .put("runId", e.runId)
                            .put("name", e.name)
                            .put("taskJson", e.taskJson)
                            .put("enqueuedAt", e.enqueuedAt)
                    )
                }
                qPrefs(ctx).edit().putString(QKEY, arr.toString()).apply()
            } catch (_: Exception) { }
        }

        fun queueSize(ctx: Context): Int = try {
            queueSnapshot(ctx).size
        } catch (_: Exception) { 0 }

        /**
         * Task line me lagao. @return line me position (1-based).
         */
        fun enqueueTask(ctx: Context, task: JSONObject): Int {
            val runId = task.optString("run_id").ifEmpty { task.optString("id") }
            val name = task.optString("name", "form")
            // v36 refine point 8: line me same runId pehle se ho to dobara
            // mat lagao (duplicate agent run guard — queue level).
            val existing = queueSnapshot(ctx)
            if (runId.isNotEmpty() && existing.any { it.runId == runId }) {
                return WorkQueue.positionOf(existing, runId)
            }
            val entry = WorkQueue.Entry(runId, name, task.toString(), System.currentTimeMillis())
            val q = WorkQueue.enqueue(existing, entry)
            persistQueue(ctx, q)
            return WorkQueue.positionOf(q, runId)
        }

        /** Agla task nikalo (pump ke liye). */
        private fun dequeueTask(ctx: Context): WorkQueue.Entry? {
            val (entry, rest) = WorkQueue.dequeue(queueSnapshot(ctx))
            if (entry != null) persistQueue(ctx, rest)
            return entry
        }

        /**
         * Line ka agla kaam shuru karo (claim free hona chahiye).
         * @return true agar koi kaam shuru hua.
         */
        private fun pumpQueue(ctx: Context): Boolean {
            val next = dequeueTask(ctx) ?: return false
            try {
                val task = JSONObject(next.taskJson)
                android.util.Log.i(
                    "FormRunService",
                    "queue pump: starting ${next.runId} (${next.name})"
                )
                startWithTask(ctx, task)
                return true
            } catch (t: Throwable) {
                android.util.Log.w("FormRunService", "queue pump failed", t)
                return false
            }
        }

        /** Chat watermark ke liye queue signature. */
        fun queueSignature(ctx: Context): String = try {
            queueSnapshot(ctx).joinToString("|") { it.runId }
        } catch (_: Exception) { "" }
    }

    private var runThread: Thread? = null

    /** POINT 28: aakhri progress ka time (stuck watchdog ke liye). */
    @Volatile
    private var lastProgressMs: Long = 0L

    /** POINT 28: stuck watchdog — har 60s. */
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                checkStuck()
            } catch (_: Exception) { }
            try {
                watchdogHandler.postDelayed(this, 60_000L)
            } catch (_: Exception) { }
        }
    }

    /**
     * POINT 28: active run atka ho (10 min bina progress, user ka wait
     * nahi) → park karke line ka agla kaam shuru karo. Atka run mara
     * nahi jata — uski history entry barkarar, History se resume hoga.
     */
    private fun checkStuck() {
        val taskId = activeTaskId ?: return
        val now = System.currentTimeMillis()
        val waiting = waitingUser(taskId)
        if (!WorkQueue.isStuck(lastProgressMs, now, waiting)) return
        val name = lastTaskName ?: "kaam"
        android.util.Log.i("FormRunService", "stuck detected: $taskId ($name)")
        try {
            notifySimple(
                NOTIF_ID + 30,
                "Atka kaam park kiya ⏸️",
                WorkQueue.stuckText(name)
            )
        } catch (_: Exception) { }
        // Run thread roko → finally me claim release + queue pump.
        try { runThread?.interrupt() } catch (_: Exception) { }
    }

    /**
     * v36 (point 10): [Band karo] — active run turant roko.
     * Run thread interrupt (stuck-watchdog wala pattern) → finally me
     * claim release + queue pump. Server run vetoed (best-effort) taaki
     * server-side slot bhi free ho.
     */
    private fun cancelActiveRun() {
        val taskId = activeTaskId
        try { runThread?.interrupt() } catch (_: Exception) { }
        try {
            Thread({
                try {
                    val ar = com.formmitra.app.agent.AgentApi.activeRun(this)
                    if (ar != null) {
                        com.formmitra.app.engine.RunReporter.updateRun(
                            this,
                            ar.optString("id", ""),
                            "vetoed",
                            0,
                            "",
                            "User ne band kiya"
                        )
                    }
                } catch (_: Exception) { }
            }, "fm-cancel-active").apply { isDaemon = true }.start()
        } catch (_: Exception) { }
        try {
            android.util.Log.i("FormRunService", "active run cancelled by user: $taskId")
        } catch (_: Exception) { }
        notifySimple(
            NOTIF_ID + 51,
            "Kaam band kiya ⏹️",
            "Ab naya kaam shuru kar sakte ho."
        )
    }

    /**
     * v36 (point 10): user ka FRESH start hai ya system resume/retry?
     * Resume (resumed=true ya start_step>0) kabhi block nahi hota —
     * wahi kaam aage badh raha hai.
     */
    private fun isUserFreshStart(task: JSONObject): Boolean {
        if (task.optBoolean("resumed", false)) return false
        val s0 = task.optJSONArray("steps")?.optJSONObject(0)
        if ((s0?.optInt("start_step", 0) ?: 0) > 0) return false
        return true
    }

    /**
     * v36 (point 10): doosra kaam shuru karne par — line me NAHI,
     * EXACT mana: "Pehla kaam poora karo ya band karo, phir naya shuru karo."
     * + [Chal raha kaam dekho] (app kholo) / [Band karo] (active run band,
     * slot free). Labels user-dictated — paraphrase NAHI.
     */
    private fun notifyOneActiveLimit(name: String) {
        try {
            ensureChannel()
            val nm =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val dekh = PendingIntent.getActivity(
                this, 9101,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val bandKaro = PendingIntent.getService(
                this, 9102,
                Intent(this, FormRunService::class.java).apply {
                    action = ACTION_CANCEL_ACTIVE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(this)
            }
                .setContentTitle("Ek kaam pehle se chal raha hai ⏳")
                .setContentText("Pehla kaam poora karo ya band karo, phir naya shuru karo.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(dekh)
                .setAutoCancel(true)
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                n.addAction(
                    android.app.Notification.Action.Builder(
                        null, "Chal raha kaam dekho", dekh
                    ).build()
                )
                n.addAction(
                    android.app.Notification.Action.Builder(
                        null, "Band karo", bandKaro
                    ).build()
                )
            }
            nm.notify(NOTIF_ID + 50, n.build())
        } catch (_: Exception) { }
        try {
            com.formmitra.app.agent.FlowAnnouncer.say(
                this, "Pehla kaam poora karo ya band karo, phir naya shuru karo."
            )
        } catch (_: Exception) { }
    }

    /** Kya ye run user ka wait kar raha hai? (prompt / detail batch) */
    private fun waitingUser(runId: String): Boolean {
        try {
            if (com.formmitra.app.engine.UserPrompt.isPending(runId)) return true
        } catch (_: Exception) { }
        try {
            val pending = com.formmitra.app.agent.DetailBatchStore
                .pending(this)
            if (pending.any { it.runId == runId && !it.answered }) return true
        } catch (_: Exception) { }
        return false
    }

    /** Stuck watchdog ke liye aakhri task ka naam. */
    @Volatile
    private var lastTaskName: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v36 (point 10): [Band karo] action — active run turant band karo,
        // slot free (phir naya kaam shuru ho sakta hai).
        if (intent?.action == ACTION_CANCEL_ACTIVE) {
            cancelActiveRun()
            stopSelf(startId)
            return START_NOT_STICKY
        }
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
        // v24 N3: live work status — "working, don't close (बंद मत करो)".
        // v35: startForeground kabhi silently na mare — throw hua to LOUD
        // notification (asli wajah ke saath) + stopSelf ("session band"
        // ka ek aur root cause: yahan ka crash bina khabar ke hota tha).
        try {
            startForeground(
                NOTIF_ID,
                buildNotif(
                    "Form bhar raha hai: $name",
                    "Kaam chal raha hai — band mat karo (don't close)"
                )
            )
        } catch (t: Throwable) {
            android.util.Log.e("FormRunService", "startForeground FAILED", t)
            try {
                notifySimple(
                    NOTIF_ID + 40,
                    "Kaam shuru nahi ho paya ⚠️ $name",
                    "Asli wajah: ${com.formmitra.app.engine.ErrorCatcher.shortCause(t)}"
                )
            } catch (_: Exception) { }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        notifySimple(NOTIF_ID + 10, "Form bharna shuru: $name", "FormMitra automation kaam kar raha hai")
        // L4: flow milestone — TTS + notification
        try {
            FlowAnnouncer.say(this, "Form bharna shuru ho gaya: $name")
        } catch (_: Exception) { }

        // POINT 28: doosra kaam pehle ko MAARTA nahi — line me lagta hai.
        // Pehla khatam hote hi agla apne aap shuru hoga. Har kaam ki
        // alag history entry barkarar (run_id alag-alag).
        // v36 (point 10): user ka FRESH start ab line me NAHI lagta —
        // user order: "Pehla kaam poora karo ya band karo, phir naya shuru
        // karo." Resume/retry (system) purane jaisa line me lagta rahega.
        val claimId = task.optString("run_id").ifEmpty { task.optString("id") }
        if (!tryClaim(claimId)) {
            if (isUserFreshStart(task)) {
                // v36 (point 10): owner/admin UNLIMITED — reject NAHI.
                // Doosra kaam local line me lagao; pehla khatam hote hi
                // pumpQueue apne aap shuru karega.
                if (isOwnerDevice(this)) {
                    val pos = try {
                        enqueueTask(this, task)
                    } catch (_: Exception) { queueSize(this) + 1 }
                    try {
                        android.util.Log.i(
                            "FormRunService",
                            "owner unlimited: fresh start queued at #$pos (active=$activeTaskId)"
                        )
                        notifySimple(
                            NOTIF_ID + 20,
                            "Line me lagaya 📋 $name",
                            "Ek kaam chal raha hai — khatam hote hi apne aap shuru hoga."
                        )
                    } catch (_: Exception) { }
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                try {
                    android.util.Log.i(
                        "FormRunService",
                        "1-active limit: fresh start rejected (active=$activeTaskId)"
                    )
                } catch (_: Exception) { }
                notifyOneActiveLimit(name)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            val pos = try {
                enqueueTask(this, task)
            } catch (_: Exception) { queueSize(this) + 1 }
            try {
                android.util.Log.i(
                    "FormRunService",
                    "run busy — queued at #$pos (run=$claimId, active=$activeTaskId)"
                )
                notifySimple(
                    NOTIF_ID + 20,
                    "Line me lagaya 📋 $name",
                    WorkQueue.queuedText(name, pos)
                )
                com.formmitra.app.agent.WorkingModeService.updateLiveStatus(
                    this,
                    "Form bhar raha hai (line me ${queueSize(this)})",
                    "Ek kaam chal raha hai — \"$name\" line me hai"
                )
            } catch (_: Exception) { }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        lastTaskName = name
        lastProgressMs = System.currentTimeMillis()
        // v24 N3: claim jeeta — persistent notification me live status.
        try {
            com.formmitra.app.agent.WorkingModeService.updateLiveStatus(
                this,
                "Form bhar raha hai: $name",
                "Shuru ho raha hai… — working, don't close (बंद मत करो)"
            )
        } catch (_: Exception) { }
        runThread = Thread({
            try {
                runTask(task, name)
            } catch (t: Throwable) {
                // CRASH-FREEDOM GATE (v36): run thread kabhi uncaught nahi
                // marega. runTask ke andar ke guards ke baad bhi jo bache
                // (pre-engine idempotency/category, post-engine notify),
                // wo yahan pakda jayega — ErrorCatcher me asli wajah ke
                // saath, app crash ke bina.
                try {
                    val rid = task.optString("run_id", "")
                    ErrorCatcher.report(
                        this, "Kaam chalate waqt (run thread)", t, name, rid
                    )
                } catch (_: Exception) { }
                try {
                    android.util.Log.e("FormRunService", "runTask uncaught (contained)", t)
                } catch (_: Exception) { }
            } finally {
                releaseClaim(claimId)
                // v34 (Phase 2A): parked-OTP race — jawab park→finish ke
                // beech aa gaya ho to turant resume (claim ab free hai).
                try {
                    val pid = OtpPark.parkedRunId(this)
                    if (pid.isNotEmpty() && UserPrompt.hasAnswer(pid)) {
                        OtpPark.onAnswered(this, pid)
                    }
                } catch (_: Exception) { }
                // POINT 28: line ka agla kaam shuru karo (ho to).
                try {
                    if (pumpQueue(this)) {
                        android.util.Log.i("FormRunService", "queue pumped after $claimId")
                    }
                } catch (_: Exception) { }
                // v24 N3: kaam khatam — persistent notification wapas
                // welcome text par (queue khaali ho to).
                try {
                    if (queueSize(this) == 0) {
                        com.formmitra.app.agent.WorkingModeService.clearLiveStatus(this)
                    }
                } catch (_: Exception) { }
            }
            stopSelf(startId)
        }, "formmitra-run").also { it.start() }
        // POINT 28: stuck watchdog shuru.
        try {
            watchdogHandler.removeCallbacks(watchdog)
            watchdogHandler.postDelayed(watchdog, 60_000L)
        } catch (_: Exception) { }

        return START_NOT_STICKY
    }

    private fun runTask(task: JSONObject, name: String) {
        val runId = task.optString("run_id", "")
        val stepsJson = task.optJSONArray("steps") ?: JSONArray()
        val total = stepsJson.length()
        // v36 refine point 8: IDEMPOTENCY — ek hi kaam ke liye duplicate
        // agent runs nahi. Same runId ya same goal+url (10 min window) dobara
        // aaye to skip (double-tap / FCM+poll double trigger / crash-retry).
        val firstStepIdem = stepsJson.optJSONObject(0)
        val idemGoal = firstStepIdem?.optString("goal", name).orEmpty().ifEmpty { name }
        val idemUrl = firstStepIdem?.optString("url", task.optString("target_url", "")).orEmpty()
        // Resume (WakeWorker parked-OTP/detail/gate resume) kabhi duplicate
        // nahi — wahi kaam aage badh raha hai, isliye guard skip.
        val isResumeTask = task.optBoolean("resumed", false) ||
            (firstStepIdem?.optInt("start_step", 0) ?: 0) > 0
        val idemAcquired = if (isResumeTask) true
        else IdempotencyGuard.tryAcquire(this, runId, idemGoal, idemUrl)
        if (!idemAcquired) {
            try {
                android.util.Log.w("FormRunService", "duplicate run skipped: $runId / $idemGoal")
            } catch (_: Exception) { }
            return
        }
        var lastReported = 0
        // POINT 17/21: is run ki category (handoff + summary ke liye).
        val firstStepForCat = stepsJson.optJSONObject(0)
        val runCategory = firstStepForCat?.optString("category", "").orEmpty()
            .ifEmpty { task.optString("category", "") }
            .ifEmpty {
                CategoryStore.takeForTask(
                    this,
                    task.optString("id"), runId, task.optString("task_id")
                )
            }
        // POINT 21: proof screenshots gino (summary card).
        var proofShots = 0
        // Standalone (offline) task: server bilkul nahi — Groq direct + local store
        val standalone = task.optBoolean("standalone", false) ||
            runId.startsWith("local-")

        val engine = FormEngine(this)
        // v38: shared live WebView — agent ka browsing "🖥️ Live" toggle me
        // live dikhega; har run apna WebView nahi banata.
        engine.useSharedWebView = true
        val firstStep = stepsJson.optJSONObject(0)
        val result = try {
            if (firstStep != null && firstStep.optString("type") == "agent_run") {
                // AI agent mode — AgentLoop har step khud decide karta hai
                val goal = firstStep.optString("goal", name).ifEmpty { name }
                val url = firstStep.optString("url", task.optString("target_url", ""))
                // Category-wise full automation: step/task/device-local se
                // category nikaalo → AgentLoop ke har act() call me jayegi.
                // (runCategory upar compute ho chuki — takeForTask dobara
                // consume na ho isliye yahan reuse.)
                val category = runCategory
                var offlineMode = false
                var standaloneMode = false
                // G2 resume: WakeWorker synthetic task me start_step bhejta hai —
                // AgentLoop usi step se continue karega (shuru se nahi).
                val startStep = firstStep.optInt("start_step", 0).coerceAtLeast(0)
                // v29 (P1-APP): step me aayi known details + asked keys —
                // AgentLoop ke har act() call me jayengi (dobara sawaal nahi).
                val knownDetails = LinkedHashMap<String, String>()
                try {
                    val kd = firstStep.optJSONObject("known_details")
                        ?: task.optJSONObject("known_details")
                    if (kd != null) {
                        val keys = kd.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = kd.optString(k, "").trim()
                            if (k.isNotEmpty() && v.isNotEmpty()) knownDetails[k] = v
                        }
                    }
                } catch (_: Exception) { }
                val askedAlready = ArrayList<String>()
                try {
                    val aa = firstStep.optJSONArray("asked_already")
                        ?: task.optJSONArray("asked_already")
                    if (aa != null) {
                        for (i in 0 until aa.length()) {
                            val k = aa.optString(i, "").trim()
                            if (k.isNotEmpty()) askedAlready.add(k)
                        }
                    }
                } catch (_: Exception) { }
                // CONTRACT SYNC (2026-09-26): plan ke details_needed /
                // blocking_details. Jo keys pehle se pata hain (DetailStore)
                // unhe knownDetails me dalo — brain dobara nahi poochhega
                // (zero-AI pattern, point 8). Missing blocking keys ka
                // pata loop ko details_needed step se chalega hi.
                try {
                    val planJson = firstStep.optJSONObject("plan")
                        ?: task.optJSONObject("plan")
                    if (planJson != null) {
                        val planMap = engine.jsonToMap(planJson)
                        val needed = PlanDetails.neededKeys(planMap)
                        if (needed.isNotEmpty()) {
                            val saved = try {
                                DetailStore.loadAll(this)
                            } catch (_: Exception) { emptyMap() }
                            val (_, missing) = PlanDetails.partition(
                                needed, knownDetails + saved
                            )
                            for (k in needed) {
                                if (k !in knownDetails) {
                                    val v = saved[k].orEmpty()
                                    if (v.isNotEmpty()) knownDetails[k] = v
                                }
                            }
                            if (missing.isNotEmpty()) {
                                android.util.Log.i(
                                    "FmPlan",
                                    "plan blocking/missing details: " +
                                        missing.joinToString(",")
                                )
                            }
                        }
                    }
                } catch (_: Exception) { }
                AgentLoop.runAgentTask(
                    this, engine, goal, url, runId, 40,
                    onProgress = { aiStep ->
                        // POINT 28: progress mili → stuck watchdog reset.
                        lastProgressMs = System.currentTimeMillis()
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
                    startStep = startStep,
                    knownDetails = knownDetails,
                    askedAlready = askedAlready,
                    // POINT 21: proof screenshots gino (summary card).
                    onProof = { proofShots++ },
                    // v36 SMART COORDINATION: pre-flight plan bana → user ko
                    // batao (ongoing notification me plan summary).
                    // v36 refine point 1: plan CHAT me bhi dikhe — PlanStore
                    // me save, AgentChatView ka poll ek baar bubble dikhayega
                    // ("Galat lage to turant batao" — misunderstanding check).
                    onPlan = { planTxt ->
                        try {
                            updateOngoing("Plan taiyar 📋 $name", planTxt.take(140))
                        } catch (_: Exception) { }
                        try {
                            com.formmitra.app.agent.PlanStore.save(
                                this@FormRunService, runId, planTxt
                            )
                        } catch (_: Exception) { }
                    }
                )
            } else {
                engine.runTask(task) { step1Based, _ ->
                    // progress cadence: RunPolicy (short: har step, long: har 3)
                    if (RunPolicy.shouldReportProgress(step1Based, total) && step1Based != lastReported) {
                        lastReported = step1Based
                        // POINT 28: progress mili → stuck watchdog reset.
                        lastProgressMs = System.currentTimeMillis()
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
            // v36: background failure → CENTRAL ErrorCatcher (asli wajah ke
            // saath). Sirf friendly text nahi — masked technical report bhi
            // persist hota hai taaki app khulne par dekha ja sake (v35 ka
            // catcher background me chhoota hua tha).
            try {
                ErrorCatcher.report(this, "Kaam chalate waqt", t, name, runId)
            } catch (_: Exception) { }
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
                // POINT 17: Apply poora → tracking handoff pending.
                try {
                    com.formmitra.app.agent.TrackHandoffStore.markDone(
                        this, runId, name, runCategory
                    )
                } catch (_: Exception) { }
                // POINT 21: end-of-work summary (chat card ke liye).
                try {
                    val doneTxt = UserText.friendly(result.summary.take(200))
                        .ifEmpty { "Kaam poora ho gaya." }
                    com.formmitra.app.agent.RunSummaryStore.save(
                        this,
                        com.formmitra.app.agent.RunSummaryStore.Summary(
                            runId = runId, workName = name, status = "done",
                            doneText = doneTxt,
                            pendingText = "Kuch nahi — sab ho gaya ✅",
                            nextAction = if (runCategory == "apply")
                                "Chaaho to iska status track karo — chat me card aayega 🔍"
                            else "Kuch nahi — kaam poora ho gaya.",
                            proofCount = proofShots,
                            at = System.currentTimeMillis()
                            // v36 user order (2026-09-26): token/model/cost ki
                            // jaankari user ko KAHIN nahi dikhegi — aiUsage
                            // field khaali rehta hai (hisaab admin panel par).
                        )
                    )
                } catch (_: Exception) { }
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
                // POINT 21: fail par bhi summary (kya hua / agla kadam).
                try {
                    com.formmitra.app.agent.RunSummaryStore.save(
                        this,
                        com.formmitra.app.agent.RunSummaryStore.Summary(
                            runId = runId, workName = name,
                            status = result.status,
                            doneText = "Poora nahi ho paya.",
                            pendingText = UserText.friendly(
                                result.summary.take(200)
                            ).ifEmpty { "Kuch steps baaki reh gaye." },
                            nextAction = "History se resume karo — wahi se aage badhega.",
                            proofCount = proofShots,
                            at = System.currentTimeMillis()
                            // v36 user order (2026-09-26): token/model/cost ki
                            // jaankari user ko KAHIN nahi dikhegi — aiUsage
                            // field khaali rehta hai (hisaab admin panel par).
                        )
                    )
                } catch (_: Exception) { }
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
        // v36 refine point 8: run khatm (service thread done) → idempotency
        // release. Resume tasks ne acquire kiya hi nahi tha (isResumeTask).
        if (!isResumeTask) {
            try { IdempotencyGuard.release(this, runId) } catch (_: Exception) { }
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
        // v24 N3: live status — dono notifications me "don't close".
        // POINT 28: line me kitne hain — status me dikhao.
        val qd = try { queueSize(this) } catch (_: Exception) { 0 }
        val qSuffix = if (qd > 0) " • 📋 line me $qd" else ""
        val live = "$text$qSuffix — band mat karo (don't close)"
        nm.notify(NOTIF_ID, buildNotif(title, live))
        try {
            com.formmitra.app.agent.WorkingModeService.updateLiveStatus(
                this, title, "$text$qSuffix — working, don't close (बंद मत करो)"
            )
        } catch (_: Exception) { }
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
        try { watchdogHandler.removeCallbacks(watchdog) } catch (_: Exception) { }
        try { runThread?.interrupt() } catch (_: Exception) { }
        super.onDestroy()
    }
}

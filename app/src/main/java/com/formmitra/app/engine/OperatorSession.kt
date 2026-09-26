package com.formmitra.app.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Operator session — fullscreen remote-control ke liye dedicated automation
 * WebView (FormEngine) + har ~3s screenshot loop.
 *
 * Loop har tick: WebView ka screenshot → /api/agent/proof upload
 * (AgentApi.uploadProof pattern reuse) → POST /api/agent/operator/state
 * {device_id, screenshot_url, page_url, desktop, ts}.
 *
 * Session stop par loop band + engine destroy + operator run terminal
 * report. Sab best-effort, koi uncaught nahi.
 *
 * NOTE: runTask wala engine alag hai — operator session usko chhedti NAHI
 * (agar koi form run chal raha ho to OperatorSession.start() false dega).
 */
object OperatorSession {

    private const val TAG = "OperatorSession"
    private const val LOOP_MS = 3000L

    @Volatile private var engine: FormEngine? = null
    @Volatile private var loopThread: Thread? = null
    @Volatile private var active = false
    @Volatile private var runId: String = ""
    @Volatile private var cmdCount = 0

    /** true = operator session abhi chal rahi hai. */
    val isActive: Boolean get() = active && engine != null

    /** Desktop UA mode abhi on hai ya nahi. */
    val isDesktop: Boolean get() = try { engine?.isDesktopMode == true } catch (_: Exception) { false }

    /** Current page URL (state POST ke liye). */
    fun pageUrl(): String = try { engine?.pageUrl() ?: "" } catch (_: Exception) { "" }

    /**
     * Session start: dedicated FormEngine + WebView, desktop flag apply,
     * FormMitra home par goto, operator run create, 3s state loop.
     * @return true = session start ho gayi.
     */
    fun start(ctx: Context, desktop: Boolean = false): Boolean {
        val appCtx = try { ctx.applicationContext } catch (_: Exception) { return false }
        if (isActive) return true
        // Koi form run chal raha ho to usko disturb mat karo.
        try {
            if (com.formmitra.app.engine.FormRunService.activeTaskId != null) {
                Log.i(TAG, "start skip — form run active hai")
                return false
            }
        } catch (_: Exception) { }
        return try {
            val eng = FormEngine(appCtx)
            eng.start()
            engine = eng
            try { eng.setDesktopMode(desktop) } catch (_: Exception) { }
            try {
                eng.opGoto(com.formmitra.app.BuildConfig.SITE_URL.trimEnd('/') + "/")
            } catch (_: Exception) { }
            try {
                runId = RunReporter.createRun(
                    appCtx, "Operator session", eng.pageUrl(), ""
                ) ?: ""
            } catch (_: Exception) {
                runId = ""
            }
            active = true
            cmdCount = 0
            startLoop(appCtx)
            Log.i(TAG, "started (desktop=$desktop, run=$runId)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "start failed (non-fatal)", t)
            try { engine?.stop() } catch (_: Exception) { }
            engine = null
            false
        }
    }

    /**
     * Session stop: loop band, engine destroy, operator run terminal.
     */
    fun stop(ctx: Context) {
        active = false
        try { loopThread?.interrupt() } catch (_: Exception) { }
        loopThread = null
        try {
            if (runId.isNotEmpty()) {
                RunReporter.updateRun(
                    ctx, runId, "done", cmdCount,
                    "Operator session band (commands: $cmdCount)", ""
                )
            }
        } catch (_: Exception) { }
        runId = ""
        try { engine?.stop() } catch (_: Exception) { }
        engine = null
        Log.i(TAG, "stopped")
    }

    /**
     * Operator command execute karo (OperatorCommandReceiver se aata hai).
     * @return result JSONObject (ok/error) — receiver server ko report karega.
     */
    fun execCommand(command: String, params: JSONObject): JSONObject {
        val eng = engine
        if (eng == null || !active) {
            return JSONObject()
                .put("command", command)
                .put("ok", false)
                .put("error", "operator session active nahi")
        }
        val res = try {
            if (command.trim().lowercase() == "captcha_request") {
                eng.opCaptchaHandoff(runId)
            } else {
                eng.execOperatorCommand(command, params)
            }
        } catch (t: Throwable) {
            JSONObject().put("command", command)
                .put("ok", false)
                .put("error", (t.message ?: "error").take(300))
        }
        try { cmdCount++ } catch (_: Exception) { }
        return res
    }

    /**
     * Turant ek state POST karo (command ke baad fresh screenshot chahiye
     * to receiver ise bula sakta hai). Best-effort.
     */
    fun pushStateNow(ctx: Context) {
        val eng = engine
        if (eng == null || !active) return
        try {
            postOneState(ctx, eng)
        } catch (_: Exception) { }
    }

    /**
     * Operator run par command result report karo (non-terminal "running"
     * update) — server/web console ko command ka natija dikhe.
     */
    fun reportCommandResult(ctx: Context, summary: String) {
        try {
            if (runId.isNotEmpty() && active) {
                RunReporter.updateRun(ctx, runId, "running", cmdCount, summary, "")
            }
        } catch (_: Exception) { }
    }

    // ---------------- internal ----------------

    private fun startLoop(appCtx: Context) {
        val t = Thread({
            try {
                while (active) {
                    try {
                        val eng = engine
                        if (eng != null && active) postOneState(appCtx, eng)
                    } catch (_: Exception) { }
                    try {
                        Thread.sleep(LOOP_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "state loop died (non-fatal)", t)
            }
        }, "OperatorSession-loop")
        t.isDaemon = true
        loopThread = t
        t.start()
    }

    private fun postOneState(appCtx: Context, eng: FormEngine) {
        val shot = try { eng.capturePngBase64() } catch (_: Exception) { "" }
        var shotUrl = ""
        try {
            if (shot.isNotEmpty() && runId.isNotEmpty()) {
                shotUrl = RunReporter.uploadProof(appCtx, runId, shot) ?: ""
            }
        } catch (_: Exception) { }
        val page = try { eng.pageUrl() } catch (_: Exception) { "" }
        val desk = try { eng.isDesktopMode } catch (_: Exception) { false }
        try {
            OperatorApi.postState(appCtx, shotUrl, page, desk, System.currentTimeMillis())
        } catch (_: Exception) { }
    }
}

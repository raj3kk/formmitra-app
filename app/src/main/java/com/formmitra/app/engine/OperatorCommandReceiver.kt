package com.formmitra.app.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Operator command receiver — FmRealtime ke "operator_command" message
 * type par aane wale commands ko automation WebView (OperatorSession ka
 * FormEngine) par execute karta hai.
 *
 * Flow: web console / in-app OperatorView → POST /api/agent/operator/command
 * → server realtime broadcast ("operator_command") → yahan execute →
 * result RunReporter (operator run) + turant state POST se server ko wapas.
 *
 * Session active nahi ho to on-demand start hoti hai (remote operator ka
 * command aana = kaam shuru karne ka signal).
 *
 * Koi bhi failure chup-chaap log + result me error — koi uncaught nahi.
 */
object OperatorCommandReceiver {

    private const val TAG = "OperatorCommandReceiver"

    /**
     * FmRealtime.handleEvent se aata hai (socket thread).
     * payload keys: command (String), params (Map/JSON), device_id (String?).
     */
    fun onCommand(ctx: Context, payload: Map<String, Any?>) {
        val appCtx = try { ctx.applicationContext } catch (_: Exception) { return }
        Thread({
            try {
                handleOnThread(appCtx, payload)
            } catch (t: Throwable) {
                Log.e(TAG, "onCommand failed (non-fatal)", t)
            }
        }, "OperatorCmd").apply { isDaemon = true }.start()
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleOnThread(appCtx: Context, payload: Map<String, Any?>) {
        val command = (payload["command"] as? String)?.trim() ?: ""
        if (command.isEmpty()) {
            Log.w(TAG, "command khaali — ignore")
            return
        }
        // Galat device ka command ho to ignore (safety).
        val targetDevice = payload["device_id"] as? String
        if (!targetDevice.isNullOrEmpty()) {
            val mine = try { FormApi.deviceId(appCtx) } catch (_: Exception) { "" }
            if (mine.isNotEmpty() && targetDevice != mine) {
                Log.i(TAG, "dusre device ka command — ignore")
                return
            }
        }
        val params: JSONObject = try {
            when (val p = payload["params"]) {
                is Map<*, *> -> JSONObject(p as Map<String, Any?>)
                is JSONObject -> p
                is String -> if (p.isBlank()) JSONObject() else JSONObject(p)
                else -> JSONObject()
            }
        } catch (_: Exception) {
            JSONObject()
        }

        // Session nahi hai to on-demand start (remote operator ka signal).
        if (!OperatorSession.isActive) {
            val wantDesktop = params.optBoolean("desktop", false) ||
                command.trim().lowercase() == "set_desktop" && params.optBoolean("enabled", false)
            val started = try { OperatorSession.start(appCtx, wantDesktop) } catch (_: Exception) { false }
            if (!started) {
                Log.w(TAG, "operator session start nahi hui (form run active?)")
                reportResult(appCtx, command, JSONObject()
                    .put("command", command)
                    .put("ok", false)
                    .put("error", "session start nahi hui — shayad form run chal raha hai"))
                return
            }
        }

        val result = try {
            OperatorSession.execCommand(command, params)
        } catch (t: Throwable) {
            JSONObject().put("command", command)
                .put("ok", false)
                .put("error", (t.message ?: "error").take(300))
        }
        // Har command ke baad fresh screenshot state — console turant dekhe.
        try { OperatorSession.pushStateNow(appCtx) } catch (_: Exception) { }
        reportResult(appCtx, command, result)
        Log.i(TAG, "command '$command' → ok=${result.optBoolean("ok", false)}")
    }

    /**
     * Result server ko wapas: operator run par non-terminal update
     * (status "running", summary me command + ok/error). Best-effort.
     */
    private fun reportResult(appCtx: Context, command: String, result: JSONObject) {
        try {
            val ok = result.optBoolean("ok", false)
            val err = result.optString("error", "")
            val summary = buildString {
                append("op:").append(command).append(" → ")
                append(if (ok) "ok" else "FAIL")
                if (err.isNotEmpty()) append(" (").append(err.take(150)).append(")")
                // useful detail fields (tapped/selected/typed_chars/desktop)
                for (k in listOf("tapped", "swiped", "selected", "typed_chars", "desktop", "nav", "has_image")) {
                    if (result.has(k)) append(" ").append(k).append("=").append(result.opt(k))
                }
            }.take(500)
            Log.i(TAG, "result: $summary")
            OperatorSession.reportCommandResult(appCtx, summary)
        } catch (_: Exception) { }
    }
}

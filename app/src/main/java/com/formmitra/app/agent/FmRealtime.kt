package com.formmitra.app.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.formmitra.app.BuildConfig
import com.formmitra.app.engine.FormApi
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * v29 P8 — FmRealtime: server↔app realtime coordination (foreground).
 *
 * - App FOREGROUND me → Supabase Realtime WebSocket par live updates:
 *   "task_status" → task UI refresh trigger (turant, polling wait nahi),
 *   "notification" → system notification + inbox (existing NotifCenter reuse).
 * - App background/killed → existing FCM + polling fallback (chheda NAHI).
 * - Single socket, battery-friendly (25s heartbeat, backoff reconnect).
 *
 * Lifecycle: app open (FmApp) / onResume (MainActivity, login ke baad) par
 * start(); logout par stop(). start() idempotent hai — login nahi to
 * chup-chaap skip (agla onResume phir try karega).
 */
object FmRealtime {
    private const val TAG = "FmRealtime"
    private const val CONFIG_PATH = "/api/app/realtime-config"

    private data class RtConfig(val url: String, val key: String, val channel: String)

    @Volatile private var socket: RealtimeSocket? = null
    @Volatile private var starting = false
    @Volatile private var netCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * UI layer ka listener — MainActivity set karti hai.
     * Socket thread par call hota hai; UI kaam main thread par karo.
     */
    @Volatile var onTaskEvent: ((event: String, payload: Map<String, Any?>) -> Unit)? = null

    val isRunning: Boolean get() = socket != null

    fun start(ctx: Context) {
        val appCtx = try { ctx.applicationContext } catch (_: Exception) { return }
        if (socket != null || starting) return
        // Login proxy: session cookie nahi = login nahi → skip.
        val cookie = try { AgentApi.sessionCookie() } catch (_: Exception) { null }
        if (cookie.isNullOrEmpty()) {
            Log.i(TAG, "start skip — login nahi (onResume par retry hoga)")
            return
        }
        starting = true
        Thread({
            try {
                startOnThread(appCtx)
            } catch (t: Throwable) {
                Log.e(TAG, "start failed (non-fatal)", t)
            } finally {
                starting = false
            }
        }, "FmRealtime-start").apply { isDaemon = true }.start()
    }

    fun stop() {
        try {
            val cb = netCallback
            netCallback = null
            if (cb != null) {
                // applicationContext nahi hai yahan — callback register karne
                // wala context yaad rakho
                lastAppCtx?.let { actx ->
                    val cm = actx.getSystemService(Context.CONNECTIVITY_SERVICE)
                        as? ConnectivityManager
                    try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        Log.i(TAG, "stopped")
    }

    @Volatile private var lastAppCtx: Context? = null

    // ---------------- internal ----------------

    private fun startOnThread(appCtx: Context) {
        if (socket != null) return
        lastAppCtx = appCtx
        val cfg = try { fetchConfig(appCtx) } catch (_: Exception) { null }
        if (cfg == null) {
            Log.i(TAG, "realtime config nahi mila (offline ya login nahi)")
            return
        }
        if (!RealtimeChannel.isValid(cfg.channel)) {
            Log.w(TAG, "realtime channel invalid — skip")
            return
        }
        val url = RealtimeSocket.buildUrl(cfg.url, cfg.key)
        val sock = RealtimeSocket(url, cfg.channel, object : RealtimeSocket.Listener {
            override fun onEvent(event: String, payload: Map<String, Any?>) {
                handleEvent(appCtx, event, payload)
            }
            override fun onConnected() { Log.i(TAG, "realtime connected") }
            override fun onDisconnected() { Log.i(TAG, "realtime disconnected") }
        })
        socket = sock
        sock.start()
        registerNetCallback(appCtx)
        Log.i(TAG, "started (channel=${cfg.channel.take(20)}…)")
    }

    /** GET /api/app/realtime-config — AgentApi jaisa session (cookie + device id). */
    private fun fetchConfig(ctx: Context): RtConfig? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(BuildConfig.SITE_URL.trimEnd('/') + CONFIG_PATH)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("X-Device-Id", FormApi.deviceId(ctx))
                setRequestProperty("Accept", "application/json")
                AgentApi.sessionCookie()?.let { setRequestProperty("Cookie", it) }
            }
            val code = conn.responseCode
            if (code != 200) {
                Log.i(TAG, "realtime-config HTTP $code")
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(text)
            val u = j.optString("url", "")
            val k = j.optString("key", "")
            val c = j.optString("channel", "")
            if (u.isEmpty() || k.isEmpty() || c.isEmpty()) return null
            RtConfig(u, k, c)
        } catch (e: Exception) {
            Log.i(TAG, "realtime-config fetch fail: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    private fun handleEvent(appCtx: Context, event: String, payload: Map<String, Any?>) {
        when (event) {
            "notification" -> {
                val title = payload["title"] as? String ?: "FormMitra"
                val body = payload["body"] as? String ?: "Naya update hai — app kholo."
                val id = (payload["id"] as? String) ?: ""
                try {
                    // Existing NotifCenter reuse — inbox + badge andar hi.
                    NotifCenter.notify(
                        appCtx, NotifCenter.Cat.TASK, title, body,
                        deepTab = "/history", key = id.ifEmpty { "$title|$body" }
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "notification show failed (non-fatal)", t)
                }
            }
            "task_status" -> {
                try {
                    onTaskEvent?.invoke(event, payload)
                } catch (t: Throwable) {
                    Log.e(TAG, "onTaskEvent failed (non-fatal)", t)
                }
            }
            "operator_command" -> {
                // Fullscreen operator console ke commands → automation
                // WebView par execute (OperatorSession ka FormEngine).
                try {
                    com.formmitra.app.engine.OperatorCommandReceiver.onCommand(appCtx, payload)
                } catch (t: Throwable) {
                    Log.e(TAG, "operator_command failed (non-fatal)", t)
                }
            }
        }
    }

    /**
     * Network change → turant reconnect (NetWake jaisa, par WorkingMode se
     * independent — realtime sirf foreground feature hai).
     */
    private fun registerNetCallback(appCtx: Context) {
        if (netCallback != null) return
        try {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return
            var wasOffline = !isOnline(cm)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (wasOffline) {
                        wasOffline = false
                        Log.i(TAG, "network wapas — realtime reconnect")
                        try { socket?.reconnectNow() } catch (_: Exception) { }
                    }
                }
                override fun onLost(network: Network) {
                    wasOffline = true
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (t: Throwable) {
            Log.e(TAG, "net callback register failed (non-fatal)", t)
        }
    }

    private fun isOnline(cm: ConnectivityManager): Boolean {
        return try {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }
}

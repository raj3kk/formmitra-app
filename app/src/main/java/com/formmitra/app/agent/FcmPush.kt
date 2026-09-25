package com.formmitra.app.agent

import android.content.Context
import android.util.Log
import com.formmitra.app.BuildConfig
import com.formmitra.app.engine.FormApi
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * FcmPush — J1 (FCM client, Firebase project clip-flow-685a5 reuse).
 *
 * - Firebase options code me HARDCODE NAHI — google-services.json se aate
 *   hain: build script (tools/build-apk.sh) json me `com.formmitra.app`
 *   client dhoondhkar res/values/fcm_values.xml generate karta hai
 *   (google_app_id, gcm_defaultSenderId, google_api_key, google_project_id).
 *   `FirebaseApp.initializeApp(ctx)` (no-arg) unhi resources se padhta hai.
 * - google-services.json me FormMitra client NA ho (user ne Firebase console
 *   me app abhi add nahi kiya) to initializeApp null deta hai → FCM skip,
 *   polling fallback (FormTaskWorker 30-min + NetWake + app-open WakeWorker)
 *   feature ko zinda rakhta hai. Build kabhi nahi toot-ta.
 * - Token milne/refresh par server ko register: POST /api/devices/token
 *   {token, device_id, platform}. (Endpoint server worker bana raha hai;
 *   404/fail = best-effort, polling fallback chalta rahega.)
 *
 * Koi secret commit nahi hota — google-services.json sirf build machine par
 * padha jata hai, repo me nahi jata.
 */
object FcmPush {
    private const val TAG = "FcmPush"
    private const val TIMEOUT_MS = 20_000

    @Volatile
    private var initAttempted = false

    /** Firebase configured + initialized hai ya nahi. */
    fun isConfigured(ctx: Context): Boolean {
        return try {
            FirebaseApp.getApps(ctx.applicationContext).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * FmApp.onCreate se (best-effort): Firebase init → token fetch → server
     * register. Ek hi baar attempt hota hai per process.
     */
    fun ensureInit(ctx: Context) {
        if (initAttempted) return
        initAttempted = true
        try {
            val appCtx = ctx.applicationContext
            val app = try {
                FirebaseApp.initializeApp(appCtx)
            } catch (t: Throwable) {
                Log.i(TAG, "Firebase init nahi hua (google-services config nahi?): ${t.message}")
                null
            }
            if (app == null) {
                Log.i(TAG, "FCM configured nahi — polling fallback active")
                return
            }
            Log.i(TAG, "Firebase initialized (project=${app.options.projectId})")
            try {
                FirebaseMessaging.getInstance().token
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val token = task.result ?: ""
                            if (token.isNotEmpty()) {
                                Log.i(TAG, "FCM token mila — server ko register kar rahe")
                                registerToken(appCtx, token)
                            }
                        } else {
                            Log.i(TAG, "FCM token fetch fail (Play Services?): polling fallback")
                        }
                    }
            } catch (t: Throwable) {
                Log.i(TAG, "FCM token fetch nahi hua (non-fatal): ${t.message}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ensureInit failed (non-fatal)", t)
        }
    }

    /**
     * Naya/refreshed token server ko bhejo: POST /api/devices/token
     * {token, device_id, platform}. Background thread par — kabhi UI block nahi.
     */
    fun registerToken(ctx: Context, token: String) {
        if (token.isEmpty()) return
        Thread({
            try {
                val body = JSONObject()
                    .put("token", token)
                    .put("device_id", FormApi.deviceId(ctx))
                    .put("platform", "android")
                    .put("app_version", BuildConfig.VERSION_NAME)
                val url = URL(BuildConfig.SITE_URL.trimEnd('/') + "/api/devices/token")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Device-Id", FormApi.deviceId(ctx))
                    doOutput = true
                }
                try {
                    conn.outputStream.use {
                        it.write(body.toString().toByteArray(Charsets.UTF_8))
                    }
                    val code = conn.responseCode
                    Log.i(TAG, "POST /api/devices/token → $code")
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                // Server endpoint abhi ban raha hai / network nahi — best-effort.
                // Polling fallback (FormTaskWorker) tab bhi kaam karta rahega.
                Log.i(TAG, "token register fail (best-effort, polling fallback): ${t.message}")
            }
        }, "fcm-token-register").start()
    }
}

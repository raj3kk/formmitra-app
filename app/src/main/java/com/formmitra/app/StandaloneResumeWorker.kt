package com.formmitra.app

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.formmitra.app.engine.FormRunService
import com.formmitra.app.engine.StandaloneStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Standalone (offline) task reboot-resume worker (H4).
 * BootReceiver BOOT_COMPLETED par resumeStandalone() karta hai; agar wahan
 * exception aaya to Log + ye one-time worker 2 min baad retry karta hai.
 * Poori tarah local — koi network/server call nahi.
 */
class StandaloneResumeWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        return try {
            resumeStandalone(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e("StandaloneResumeWorker", "standalone resume failed — retry", e)
            Result.retry()
        }
    }

    companion object {
        /** BootReceiver bhi yahi call karta hai — resume logic ek jagah. */
        fun resumeStandalone(context: Context) {
            val pending = StandaloneStore.pending(context)
            Log.i("StandaloneResumeWorker", "pending standalone tasks: ${pending.size}")
            for (t in pending) {
                val task = JSONObject()
                    .put("name", t.goal)
                    .put("target_url", t.url)
                    .put("run_id", t.id)
                    .put("standalone", true)
                    .put(
                        "steps",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "agent_run")
                                .put("goal", t.goal)
                                .put("url", t.url)
                        )
                    )
                FormRunService.startWithTask(context, task)
            }
        }
    }
}

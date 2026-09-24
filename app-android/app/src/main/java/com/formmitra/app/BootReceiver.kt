package com.formmitra.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Scheduler.scheduleDigest(context)
            Scheduler.scheduleFormTasks(context)
            // Standalone (offline) tasks: reboot par dobara uthao — ye poori
            // tarah local hain, isliye auto-resume safe hai.
            try {
                val pending = com.formmitra.app.engine.StandaloneStore.pending(context)
                for (t in pending) {
                    val task = org.json.JSONObject()
                        .put("name", t.goal)
                        .put("target_url", t.url)
                        .put("run_id", t.id)
                        .put("standalone", true)
                        .put(
                            "steps",
                            org.json.JSONArray().put(
                                org.json.JSONObject()
                                    .put("type", "agent_run")
                                    .put("goal", t.goal)
                                    .put("url", t.url)
                            )
                        )
                    com.formmitra.app.engine.FormRunService.startWithTask(context, task)
                }
            } catch (_: Exception) { }
        }
    }
}

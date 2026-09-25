package com.formmitra.app.agent

import android.content.Context

/**
 * CategoryStore — category → task threading (device-local).
 *
 * Category se bana task kaun si category ka hai, ye baat AgentLoop ke har
 * /api/agent/act call me "category" field bankar jaye — taaki server brain
 * har step par category-aware rahe. Server repo chhoone ki zaroorat nahi:
 * task banate waqt taskId→category yahan save hota hai, FormRunService run
 * shuru hone se pehle ise nikaal kar AgentLoop ko de deta hai.
 */
object CategoryStore {
    private const val PREFS = "formmitra_cat"

    fun saveForTask(ctx: Context, taskId: String, category: String) {
        if (taskId.isEmpty() || category.isEmpty()) return
        // L2: sync — task create ke turant baad kill ho to bhi category bache
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("task:$taskId", category).commit()
    }

    /** Pehli mili key se category nikaalo aur hatao (ek baar ka use). */
    fun takeForTask(ctx: Context, vararg keys: String): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (k in keys) {
            if (k.isEmpty()) continue
            val v = p.getString("task:$k", "")
            if (!v.isNullOrEmpty()) {
                p.edit().remove("task:$k").apply()
                return v
            }
        }
        return ""
    }
}

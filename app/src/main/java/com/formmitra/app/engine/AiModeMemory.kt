package com.formmitra.app.engine

import android.content.Context
import android.util.Log

/**
 * v52 AI MODE MEMORY — operator jo seekhega, yahan rahega.
 *
 * User order (2026-09-27):
 * - "Jo bhi seekhega memory me rahega, trained hoga, aage usse karega"
 * - "AI ki memory kabhi delete na ho (trained wala)"
 * - "Main kuch bhi bolu kabhi bhi — mere se confirmation lene ke baad
 *   delete ho. App ka agent ka koi bhi data jo kaam ka hai, mujhe batake
 *   remove karna, confirmation dunga tab."
 *
 * RULES:
 * 1. Ye memory KABHI auto-delete nahi hogi (refresh/reset se bhi nahi).
 * 2. User "delete karo" bole to bhi PEHLE confirmation mango.
 * 3. Sirf user ke explicit "haan delete karo" par delete ho.
 */
object AiModeMemory {

    private const val TAG = "AiModeMemory"
    private const val PREFS_BASE = "ai_mode_memory_v52"

    /**
     * v56 DATA ISOLATION (user order: "kisi aur ka data kisi aur user me
     * na jaye"): memory file user-scoped. Selected card ID se scope banta
     * hai — User A ka seekha hua User B ko kabhi nahi dikhega.
     * Card nahi selected to "shared" scope (pehle jaisa behavior).
     */
    private fun prefsName(ctx: Context): String {
        return try {
            val cardId = com.formmitra.app.agent.CardStore
                .selectedCardId(ctx).orEmpty()
            if (cardId.isNotEmpty()) {
                "${PREFS_BASE}_${cardId.hashCode().toString(16)}"
            } else {
                PREFS_BASE
            }
        } catch (_: Exception) { PREFS_BASE }
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(prefsName(ctx), Context.MODE_PRIVATE)

    /**
     * Seekha hua save karo.
     */
    fun learn(ctx: Context, problem: String, understanding: String) {
        try {
            val key = "learn_${problem.hashCode()}"
            prefs(ctx)
                .edit()
                .putString(key, understanding)
                .putLong("${key}_time", System.currentTimeMillis())
                .apply()
            Log.i(TAG, "Learned: ${problem.take(50)}")
        } catch (e: Exception) {
            Log.e(TAG, "learn fail", e)
        }
    }

    /**
     * Seekha hua lao (null = nahi seekha).
     */
    fun getLearned(ctx: Context, problem: String): String? {
        return try {
            val key = "learn_${problem.hashCode()}"
            prefs(ctx)
                .getString(key, null)
        } catch (_: Exception) { null }
    }

    /**
     * Kitna seekha hua hai?
     */
    fun count(ctx: Context): Int {
        return try {
            prefs(ctx)
                .all.keys.count { it.startsWith("learn_") && !it.endsWith("_time") }
        } catch (_: Exception) { 0 }
    }

    /**
     * DELETE — SIRF user confirmation ke baad call karo.
     * Bina confirmation kabhi mat bulana.
     */
    fun clearAllWithConfirmation(ctx: Context, userConfirmed: Boolean): Boolean {
        if (!userConfirmed) {
            Log.w(TAG, "Delete blocked — user confirmation nahi hai")
            return false
        }
        return try {
            prefs(ctx)
                .edit().clear().apply()
            Log.i(TAG, "Memory cleared (user confirmed)")
            true
        } catch (_: Exception) { false }
    }
}

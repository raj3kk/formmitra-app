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
     * v58 DATA ISOLATION — ROOT FIX (user order: "kisi aur ka data kisi aur
     * user me na jaye", "fail closed"):
     * - Card selected → SHA-256 scoped prefs (hashCode nahi — collision
     *   safe, strong namespace).
     * - Card NOT selected → NULL (koi shared global nahi). Learn no-op,
     *   recall null. Pehle global PREFS_BASE fallback tha — wo users ke
     *   beech data leak kar sakta tha. Ab fail-closed.
     */
    private fun prefsName(ctx: Context): String? {
        return try {
            val cardId = com.formmitra.app.agent.CardStore
                .selectedCardId(ctx).orEmpty()
            if (cardId.isNotEmpty()) {
                val digest = java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(cardId.toByteArray(Charsets.UTF_8))
                val hex = digest.joinToString("") { "%02x".format(it) }
                "${PREFS_BASE}_${hex.take(16)}"
            } else {
                // v58: fail closed — bina identity ke shared memory nahi
                null
            }
        } catch (_: Exception) { null }
    }

    private fun prefs(ctx: Context) =
        prefsName(ctx)?.let {
            ctx.getSharedPreferences(it, Context.MODE_PRIVATE)
        }

    /**
     * Seekha hua save karo.
     * v58: bina card identity ke NO-OP (fail closed) — shared global me
     * kabhi nahi likhta.
     */
    fun learn(ctx: Context, problem: String, understanding: String) {
        val p = prefs(ctx)
        if (p == null) {
            Log.w(TAG, "learn skip — koi card identity nahi (fail closed)")
            return
        }
        try {
            val key = "learn_${problem.hashCode()}"
            p.edit()
                .putString(key, understanding)
                .putLong("${key}_time", System.currentTimeMillis())
                .apply()
            Log.i(TAG, "Learned: ${problem.take(50)}")
        } catch (e: Exception) {
            Log.e(TAG, "learn fail", e)
        }
    }

    /**
     * Seekha hua lao (null = nahi seekha / koi identity nahi).
     */
    fun getLearned(ctx: Context, problem: String): String? {
        return try {
            val key = "learn_${problem.hashCode()}"
            prefs(ctx)?.getString(key, null)
        } catch (_: Exception) { null }
    }

    /**
     * Kitna seekha hua hai?
     */
    fun count(ctx: Context): Int {
        return try {
            prefs(ctx)?.all?.keys?.count {
                it.startsWith("learn_") && !it.endsWith("_time")
            } ?: 0
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
            val p = prefs(ctx) ?: return false
            p.edit().clear().apply()
            Log.i(TAG, "Memory cleared (user confirmed)")
            true
        } catch (_: Exception) { false }
    }
}

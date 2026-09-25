package com.formmitra.app.agent

import android.content.Context

/**
 * NotifSettings — K5: Profile tab me notification categories on/off.
 *
 * Har NotifCenter.Cat ka alag switch. Default sab ON.
 * NotifCenter.notify() dikhane se PEHLE ye check karta hai — OFF category
 * ki notification bilkul nahi aati (na shade me, na inbox me).
 *
 * Sirf local preference — server ko kuch nahi jata.
 */
object NotifSettings {
    private const val PREFS = "fm_notif_settings"

    /** UI labels (Profile switches ke liye) — order stable rakho. */
    fun categories(): List<Pair<NotifCenter.Cat, String>> = listOf(
        NotifCenter.Cat.TASK to "📋 Task events (shuru / ho gaya / fail)",
        NotifCenter.Cat.DETAIL to "✋ Detail chahiye (agent ka sawal)",
        NotifCenter.Cat.APPROVAL to "💰 Approval chahiye (payment)",
        NotifCenter.Cat.DOC to "📄 Document ready",
        NotifCenter.Cat.STATUS to "📶 Status / limit change"
    )

    fun isEnabled(ctx: Context, cat: NotifCenter.Cat): Boolean = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key(cat), true)
    } catch (_: Exception) {
        true
    }

    fun setEnabled(ctx: Context, cat: NotifCenter.Cat, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(key(cat), on).apply()
        } catch (_: Exception) { }
    }

    private fun key(cat: NotifCenter.Cat) = "cat_${cat.name.lowercase()}"
}

package com.formmitra.app.agent

import android.content.Context
import com.formmitra.app.engine.SmsOtpPolicy

/**
 * SmsOtpAutoRead — POINT 26: SMS OTP auto-read ka user choice state.
 *
 *  - opt_in (default ON — baseline me consent flow tha): user Profile me
 *    toggle se band kar sakta hai.
 *  - denied (persistent): consent dialog ek baar cancel → dobara kabhi
 *    auto consent prompt NAHI (NO-NAGGING rule). Toggle ON karne par reset.
 */
object SmsOtpAutoRead {

    private const val PREFS = "formmitra_sms_otp"
    private const val KEY_OPTIN = "opt_in"
    private const val KEY_DENIED = "denied"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOptedIn(ctx: Context): Boolean = try {
        prefs(ctx).getBoolean(KEY_OPTIN, true)
    } catch (_: Exception) { true }

    fun setOptedIn(ctx: Context, on: Boolean) {
        try {
            val e = prefs(ctx).edit().putBoolean(KEY_OPTIN, on)
            // Wapas ON → deny bhool jao (user ne khud chalu kiya).
            if (on) e.putBoolean(KEY_DENIED, SmsOtpPolicy.deniedAfterToggleOn())
            e.apply()
        } catch (_: Exception) { }
    }

    fun isDenied(ctx: Context): Boolean = try {
        prefs(ctx).getBoolean(KEY_DENIED, false)
    } catch (_: Exception) { false }

    /** Consent deny/cancel → persist (dobara prompt nahi). */
    fun recordDenied(ctx: Context) {
        try {
            prefs(ctx).edit()
                .putBoolean(KEY_DENIED, SmsOtpPolicy.nextDenied(isDenied(ctx), false))
                .apply()
        } catch (_: Exception) { }
    }

    /** Ab auto-read ki koshish karni chahiye? */
    fun shouldAttempt(ctx: Context): Boolean =
        SmsOtpPolicy.shouldAttempt(isOptedIn(ctx), isDenied(ctx))
}

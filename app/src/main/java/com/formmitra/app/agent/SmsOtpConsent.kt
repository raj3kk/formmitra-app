package com.formmitra.app.agent

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.google.android.gms.auth.api.phone.SmsRetriever
import java.lang.ref.WeakReference

/**
 * SmsOtpConsent — L1-UPGRADE (OTP maximum assistance), SMS User Consent API.
 *
 * Broad READ_SMS/RECEIVE_SMS permission NAHI chahiye (Play policy friendly).
 * Kaam:
 *  1. OTP dialog khulne par `startListening()` — Play Services 5 minute tak
 *     aane wale SMS par nazar rakhta hai.
 *  2. OTP wala SMS aate hi system consent dialog ke liye `consentIntent`
 *     milta hai. Dialog foreground me ho to turant launch — user EK tap
 *     me "ye wala message padho" allow karta hai.
 *  3. Result me poora SMS text milta hai → OTP nikaal kar field me
 *     auto-bharo. OTP value kabhi server/AI ko nahi jati.
 *
 * Background service (app band) me consent dialog nahi khul sakta — wahan
 * notification deep-link se app khulne par yehi flow chalta hai.
 */
object SmsOtpConsent {
    private const val TAG = "SmsOtpConsent"
    const val REQ_SMS_CONSENT = 9001

    private var receiver: BroadcastReceiver? = null
    private var receiverCtx: WeakReference<Context>? = null
    private var consentIntent: Intent? = null
    private var activityRef: WeakReference<Activity>? = null

    /** OTP dialog ne khola — SMS aate hi consent launch karne ke liye. */
    var onOtp: ((String) -> Unit)? = null
    /** Consent dialog available hua (button enable / auto-launch ke liye). */
    var onConsentReady: (() -> Unit)? = null
    /**
     * POINT 26 (no-nagging): user ne consent dialog cancel/deny kiya →
     * caller denied persist karega (dobara auto prompt nahi).
     */
    var onDenied: (() -> Unit)? = null

    fun startListening(ctx: Context) {
        if (receiver != null) return
        try {
            val client = SmsRetriever.getClient(ctx)
            client.startSmsUserConsent(null)
                .addOnSuccessListener {
                    Log.i(TAG, "user-consent listener started")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "user-consent start failed", e)
                }
            val br = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (SmsRetriever.SMS_RETRIEVED_ACTION != intent.action) return
                    val extras = intent.extras ?: return
                    val status = extras.get(SmsRetriever.EXTRA_STATUS)
                        as? com.google.android.gms.common.api.Status ?: return
                    if (status.statusCode ==
                        com.google.android.gms.common.api.CommonStatusCodes.SUCCESS
                    ) {
                        @Suppress("DEPRECATION")
                        consentIntent = if (Build.VERSION.SDK_INT >= 33) {
                            extras.getParcelable(
                                SmsRetriever.EXTRA_CONSENT_INTENT, Intent::class.java
                            )
                        } else {
                            extras.getParcelable(SmsRetriever.EXTRA_CONSENT_INTENT) as? Intent
                        }
                        Log.i(TAG, "consent intent received")
                        try { onConsentReady?.invoke() } catch (_: Exception) { }
                    }
                }
            }
            val filter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(br, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(br, filter)
            }
            receiver = br
            // L5: unregister WAHI context se hoga jisse register hua —
            // applicationContext mismatch par receiver leak hota.
            receiverCtx = WeakReference(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "startListening failed", t)
        }
    }

    fun setActivity(a: Activity?) {
        activityRef = if (a == null) null else WeakReference(a)
    }

    /** Consent dialog launch karo (OTP SMS aa chuka ho). @return true = khul gaya. */
    fun launchConsent(): Boolean {
        val i = consentIntent ?: return false
        val a = activityRef?.get() ?: return false
        return try {
            a.startActivityForResult(i, REQ_SMS_CONSENT)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "launchConsent failed", t)
            false
        }
    }

    fun hasConsent(): Boolean = consentIntent != null

    /**
     * MainActivity.onActivityResult se call karo.
     * @return true agar ye SMS-consent ka result tha (handle ho gaya).
     */
    fun handleActivityResult(reqCode: Int, resCode: Int, data: Intent?): Boolean {
        if (reqCode != REQ_SMS_CONSENT) return false
        try {
            if (resCode == Activity.RESULT_OK && data != null) {
                val msg = data.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)
                val otp = parseOtp(msg)
                if (otp != null) {
                    Log.i(TAG, "OTP parsed from consented SMS")
                    try { onOtp?.invoke(otp) } catch (_: Exception) { }
                }
            } else {
                // POINT 26: user ne consent cancel/deny kiya → no-nagging:
                // caller denied persist karega, manual flow chalega.
                Log.i(TAG, "SMS consent denied/cancelled by user")
                try { onDenied?.invoke() } catch (_: Exception) { }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "handleActivityResult failed", t)
        } finally {
            stop()
        }
        return true
    }

    /** 4–8 digit OTP nikalo; "otp/code/verification" ke paas wala prefer karo. */
    fun parseOtp(msg: String?): String? {
        if (msg.isNullOrBlank()) return null
        val lower = msg.lowercase()
        val keywordIdx = listOf("otp", "code", "verification", "verify", "passcode")
            .map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull()
        val re = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")
        val matches = re.findAll(msg).toList()
        if (matches.isEmpty()) return null
        if (keywordIdx == null) return matches.first().groupValues[1]
        // Keyword ke sabse nazdeek wala number
        return matches.minByOrNull {
            kotlin.math.abs(it.range.first - keywordIdx)
        }?.groupValues?.get(1)
    }

    fun stop() {
        consentIntent = null
        onOtp = null
        onConsentReady = null
        onDenied = null
        activityRef = null
        try {
            val br = receiver
            val rc = receiverCtx?.get()
            if (br != null && rc != null) rc.unregisterReceiver(br)
        } catch (_: Exception) { }
        receiver = null
        receiverCtx = null
    }
}

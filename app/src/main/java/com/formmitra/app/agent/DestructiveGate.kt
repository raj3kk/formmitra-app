package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.widget.TextView
import android.widget.Toast
import com.formmitra.app.engine.DestructivePolicy

/**
 * DestructiveGate — POINT 18: destructive actions ka non-waivable gate.
 *
 * Cancel/withdraw/delete/account-close: HAMESHA ye dialog — permanent
 * automation approval ise bypass NAHI kar sakta. Koi "hamesha allow"
 * checkbox nahi. Saaf samjhao kya hoga → "Haan karo / Mat karo".
 */
object DestructiveGate {

    /**
     * @param what kya hone wala hai (simple words, e.g. "Tracking band karna").
     * @param onConfirm user ne "Haan karo" dabaya.
     */
    fun confirm(act: Activity, what: String, onConfirm: () -> Unit) {
        try {
            if (act.isFinishing || act.isDestroyed) return
            AlertDialog.Builder(act)
                .setTitle(DestructivePolicy.title())
                .setMessage(DestructivePolicy.confirmText(what))
                .setPositiveButton(DestructivePolicy.acceptLabel()) { _, _ ->
                    try { onConfirm() } catch (_: Exception) { }
                }
                .setNegativeButton(DestructivePolicy.declineLabel()) { _, _ ->
                    try {
                        Toast.makeText(
                            act, DestructivePolicy.cancelledText(),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (_: Exception) { }
                }
                .setCancelable(false)
                .show()
        } catch (t: Throwable) {
            android.util.Log.e("FmDestructive", "gate failed", t)
        }
    }

    /** Text dekhkar faisla: gate chahiye ya nahi. */
    fun needsGate(text: String): Boolean = DestructivePolicy.isDestructive(text)
}

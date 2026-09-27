package com.formmitra.app.engine

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.Log

/**
 * v52 DELETE GUARD.
 *
 * User order (2026-09-27):
 * "Main kuch bhi bolu kabhi bhi — mere se confirmation lene ke baad
 * delete ho. App ka agent ka koi bhi data jo kaam ka hai, mujhe batake
 * remove karna, confirmation dunga tab."
 *
 * RULES:
 * 1. Agent ka kaam ka data delete karne se PEHLE hamesha user se
 *    confirmation lo (dialog).
 * 2. AI ki trained memory (AiModeMemory, AiHelper learnings) KABHI
 *    auto-delete nahi hogi — sirf explicit user "haan" par.
 * 3. FormMitra Card ka data KABHI delete nahi hoga (ye user ka hai).
 * 4. Confirmation ke bina delete = BLOCKED.
 */
object DeleteGuard {

    private const val TAG = "DeleteGuard"

    /**
     * Kya ye data delete kar sakte hain?
     * @return true = user ne confirm kiya
     */
    fun confirmDelete(
        act: Activity,
        title: String,
        what: String,
        onConfirmed: () -> Unit
    ) {
        if (act.isFinishing || act.isDestroyed) return
        AlertDialog.Builder(act)
            .setTitle("⚠️ $title")
            .setMessage(
                "$what\n\n" +
                "Ye hamesha ke liye delete ho jayega.\n\n" +
                "🛡️ Safe rahega:\n" +
                "• AI ki seekhi hui memory (trained)\n" +
                "• FormMitra Card ka data"
            )
            .setPositiveButton("Haan, delete karo") { _, _ ->
                Log.i(TAG, "User confirmed delete: $title")
                onConfirmed()
            }
            .setNegativeButton("Nahi, rehne do", null)
            .show()
    }

    /**
     * Bina confirmation delete ki koshish — hamesha BLOCK.
     * Ye method sirf isliye hai taaki kahin bhool se bina puche
     * delete na ho jaye.
     */
    fun blockUnconfirmedDelete(what: String): Boolean {
        Log.w(TAG, "BLOCKED unconfirmed delete: $what")
        return false
    }
}

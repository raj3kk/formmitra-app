package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.formmitra.app.engine.PinPolicy

/**
 * PinResetFlow — POINT 27: "PIN bhool gaye?" (chat/DETAILS se).
 *
 *  1. Step 1: account email dikhao → "OTP bhejo" →
 *     POST /api/cards/[id]/pin-reset/request (OTP email + device par).
 *  2. Step 2: 6-digit OTP dalo.
 *  3. Step 3: naya PIN (password-style input — kabhi plain nahi) + dobara →
 *     POST /api/cards/[id]/pin-reset/confirm {otp, new_pin}.
 *
 * PIN kabhi chat me nahi dikhta — sirf is dialog ke masked field me.
 * Server confirm endpoint pending ho to saaf message (404 → "jald aa raha").
 */
object PinResetFlow {

    private fun dp(act: Activity, v: Int): Int =
        (v * act.resources.displayMetrics.density).toInt()

    private fun toast(act: Activity, msg: String) {
        try {
            Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { }
    }

    /**
     * @param email account email (OTP yahin jayega) — khaali ho to bina
     *              email ke bhi flow chalega (device par OTP aata hai).
     * @param onDone safal reset par (cardId).
     */
    fun show(act: Activity, cardId: String, cardName: String, email: String, onDone: ((String) -> Unit)? = null) {
        try {
            stepEmail(act, cardId, cardName, email, onDone)
        } catch (t: Throwable) {
            android.util.Log.e("FmPinReset", "show failed", t)
            toast(act, "⚠️ PIN reset khulne me dikkat aayi")
        }
    }

    // ---------- step 1: email confirm + OTP bhejo ----------

    private fun stepEmail(
        act: Activity, cardId: String, cardName: String, email: String,
        onDone: ((String) -> Unit)?
    ) {
        val wrap = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(act, 24), dp(act, 8), dp(act, 24), dp(act, 8))
            addView(TextView(act).apply {
                text = "🪪 $cardName\n\n" +
                    "Naya PIN set karne ke liye pehle OTP verify hoga.\n" +
                    (if (email.isNotEmpty()) "OTP is email par jayega:\n📧 $email"
                     else "OTP tumhare phone par jayega.")
                textSize = 14f
                setTextColor(Color.parseColor("#202124"))
            })
        }
        val dlg = AlertDialog.Builder(act)
            .setTitle(PinPolicy.resetTitle())
            .setView(wrap)
            .setPositiveButton("📩 OTP bhejo", null)
            .setNegativeButton("Band karo", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                toast(act, "OTP bhej raha hun…")
                Thread({
                    val (code, err) = try {
                        AgentApi.requestCardPinOtp(act, cardId)
                    } catch (_: Exception) { -1 to "Network dikkat" }
                    act.runOnUiThread {
                        dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        if (err == null) {
                            dlg.dismiss()
                            toast(act, PinPolicy.otpSentText(email))
                            stepOtp(act, cardId, cardName, email, onDone)
                        } else {
                            val msg = if (code == 429) err
                            else "⚠️ $err"
                            toast(act, msg)
                        }
                    }
                }, "fm-pinreset-req").start()
            }
        }
        dlg.show()
    }

    // ---------- step 2: OTP dalo ----------

    private fun stepOtp(
        act: Activity, cardId: String, cardName: String, email: String,
        onDone: ((String) -> Unit)?
    ) {
        val et = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "6 ank ka OTP"
        }
        val wrap = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(act, 24), dp(act, 8), dp(act, 24), dp(act, 8))
            addView(TextView(act).apply {
                text = "📩 OTP dalo" +
                    (if (email.isNotEmpty()) " ($email)" else "") +
                    " — 10 minute me kaam karega."
                textSize = 14f
                setTextColor(Color.parseColor("#202124"))
                setPadding(0, 0, 0, dp(act, 8))
            })
            addView(et)
        }
        val dlg = AlertDialog.Builder(act)
            .setTitle(PinPolicy.resetTitle())
            .setView(wrap)
            .setPositiveButton("Aage ➤", null)
            .setNegativeButton("Band karo", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val otp = et.text.toString().trim()
                if (!PinPolicy.isValidOtp(otp)) {
                    et.error = "6 ank ka OTP likho"
                    return@setOnClickListener
                }
                dlg.dismiss()
                stepNewPin(act, cardId, cardName, otp, onDone)
            }
        }
        dlg.show()
    }

    // ---------- step 3: naya PIN (masked) ----------

    private fun stepNewPin(
        act: Activity, cardId: String, cardName: String, otp: String,
        onDone: ((String) -> Unit)?
    ) {
        val pinEt = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Naya PIN (4–8 ank)"
        }
        val pin2Et = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN dobara likho"
        }
        val wrap = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(act, 24), dp(act, 8), dp(act, 24), dp(act, 8))
            addView(TextView(act).apply {
                text = "🔑 Naya PIN set karo — 🪪 $cardName\n" +
                    "(PIN parde me rahega, kahin plain nahi dikhega)"
                textSize = 14f
                setTextColor(Color.parseColor("#202124"))
                setPadding(0, 0, 0, dp(act, 8))
            })
            addView(pinEt)
            addView(pin2Et)
        }
        val dlg = AlertDialog.Builder(act)
            .setTitle(PinPolicy.resetTitle())
            .setView(wrap)
            .setPositiveButton("✅ PIN set karo", null)
            .setNegativeButton("Band karo", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = pinEt.text.toString().trim()
                val pin2 = pin2Et.text.toString().trim()
                if (!PinPolicy.isValidPin(pin)) {
                    pinEt.error = PinPolicy.invalidPinText()
                    return@setOnClickListener
                }
                if (!PinPolicy.pinsMatch(pin, pin2)) {
                    pin2Et.error = PinPolicy.mismatchText()
                    return@setOnClickListener
                }
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                toast(act, "PIN set ho raha hai…")
                Thread({
                    val (code, err) = try {
                        AgentApi.confirmCardPinReset(act, cardId, otp, pin)
                    } catch (_: Exception) { -1 to "Network dikkat" }
                    act.runOnUiThread {
                        // PIN memory se turant saaf (variable scope khatm).
                        if (err == null) {
                            dlg.dismiss()
                            // Naya PIN → purani unlock state reset (naye
                            // PIN se dobara unlock hoga).
                            try { CardStore.lock(act, cardId) } catch (_: Exception) { }
                            toast(act, PinPolicy.resetDoneText())
                            try { onDone?.invoke(cardId) } catch (_: Exception) { }
                        } else {
                            dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            toast(act, "⚠️ $err")
                        }
                    }
                }, "fm-pinreset-confirm").start()
            }
        }
        dlg.show()
    }
}

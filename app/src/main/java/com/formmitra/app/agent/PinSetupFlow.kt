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
 * PinSetupFlow — POINT 27 EXTEND: pehli-baar PIN setup.
 *
 * Jis user ka koi Card hi nahi (PIN set karne ka mauka hi nahi mila),
 * uske liye chat entry par "Card banao + PIN set karo" ka flow:
 *   Card ka naam → naya PIN (masked) → dobara PIN → POST /api/cards.
 * Phir wahi PIN aage unlock me kaam karega.
 *
 * PIN kabhi plain me nahi — sirf masked input, chat me kabhi nahi.
 */
object PinSetupFlow {

    private fun dp(act: Activity, v: Int): Int =
        (v * act.resources.displayMetrics.density).toInt()

    private fun toast(act: Activity, msg: String) {
        try {
            Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { }
    }

    /**
     * Agar cards hain → onDone(null) (unlock flow sambhalega).
     * Koi card nahi → setup card dikhao; banne par onDone(cardId).
     */
    fun ensureCardOrSetup(act: Activity, onDone: (String?) -> Unit) {
        Thread({
            val cards = try {
                val res = AgentApi.cards(act)
                if (res.code in 200..299) {
                    val arr = res.json?.optJSONArray("cards")
                    val out = mutableListOf<org.json.JSONObject>()
                    if (arr != null) for (i in 0 until arr.length()) {
                        (arr.optJSONObject(i))?.let { out.add(it) }
                    }
                    out
                } else emptyList()
            } catch (_: Exception) { null }
            act.runOnUiThread {
                when {
                    cards == null -> {
                        // Network fail — unlock flow ko mauka do.
                        try { onDone(null) } catch (_: Exception) { }
                    }
                    cards.isEmpty() -> showSetupCard(act, onDone)
                    else -> try { onDone(null) } catch (_: Exception) { }
                }
            }
        }, "fm-pinsetup-check").start()
    }

    private fun showSetupCard(act: Activity, onDone: (String?) -> Unit) {
        val nameEt = EditText(act).apply {
            hint = "Card ka naam (jaise: Mera Card)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val pinEt = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN set karo (4–8 ank)"
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
                text = "Tumhara koi Card nahi hai.\n" +
                    "Card banao — iski details (naam, mobile, pata…) agent " +
                    "kaam me khud use karega, baar-baar nahi maangega.\n\n" +
                    "PIN parde me rahega — kahin plain nahi dikhega."
                textSize = 14f
                setTextColor(Color.parseColor("#202124"))
                setPadding(0, 0, 0, dp(act, 8))
            })
            addView(nameEt)
            addView(pinEt)
            addView(pin2Et)
        }
        val dlg = AlertDialog.Builder(act)
            .setTitle(PinPolicy.setupTitle())
            .setView(wrap)
            .setPositiveButton("✅ Card banao", null)
            .setNegativeButton("Baad me", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dlg.dismiss()
                try { onDone(null) } catch (_: Exception) { }
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameEt.text.toString().trim()
                val pin = pinEt.text.toString().trim()
                val pin2 = pin2Et.text.toString().trim()
                if (name.isEmpty()) {
                    nameEt.error = "Card ka naam likho"
                    return@setOnClickListener
                }
                if (!PinPolicy.isValidPin(pin)) {
                    pinEt.error = PinPolicy.invalidPinText()
                    return@setOnClickListener
                }
                if (!PinPolicy.pinsMatch(pin, pin2)) {
                    pin2Et.error = PinPolicy.mismatchText()
                    return@setOnClickListener
                }
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                toast(act, "Card ban raha hai…")
                Thread({
                    val res = try {
                        AgentApi.createCard(act, name, pin, "app")
                    } catch (_: Exception) { AgentApi.ApiResult(-1, null) }
                    act.runOnUiThread {
                        dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        val cardId = if (res.code in 200..299) {
                            res.json?.optJSONObject("card")?.optString("id", "")
                                ?: res.json?.optString("id", "")
                        } else ""
                        if (!cardId.isNullOrEmpty()) {
                            dlg.dismiss()
                            toast(act, PinPolicy.setupDoneText(name))
                            try { onDone(cardId) } catch (_: Exception) { }
                        } else {
                            val err = res.json?.optString("error", "").orEmpty()
                            toast(
                                act,
                                if (err == "card_limit") "⚠️ 4 Card ho gaye — naya nahi ban sakta"
                                else "⚠️ Card nahi bana — dobara try karo"
                            )
                        }
                    }
                }, "fm-pinsetup-create").start()
            }
        }
        // Cancel par bhi aage badho (user kaam to kar sake).
        dlg.setOnCancelListener {
            try { onDone(null) } catch (_: Exception) { }
        }
        dlg.show()
    }
}

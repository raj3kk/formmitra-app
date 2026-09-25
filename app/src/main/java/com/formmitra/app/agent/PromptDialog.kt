package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.formmitra.app.engine.PaymentFlow
import com.formmitra.app.engine.UserPrompt
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * PromptDialog — agent ke "user se chahiye" sawalon ka popup.
 * kinds: otp | input | choice | payment | document | login.
 *
 * - otp/input: fields bharo → agent aage badhega (loop wait kar raha hai).
 * - choice: options me se chuno (payment method choose karne ke liye bhi).
 * - payment: pehle approval (₹X pay karna hai?) → phir QR/UPI + countdown →
 *   "Payment ho gaya" → agent site par verify karega.
 * - document: vault ke docs me se chuno ya naya upload karo → agent page par
 *   upload karega. Sirf FILENAME loop ko milta hai, content kabhi nahi.
 * - login: username+password (mic NAHI — secret). Dono values OTP ki tarah
 *   KABHI server/AI/history me nahi jati — sirf page me locally bhari jati hain.
 * Har dialog me 🎤 mic (voice input) aur agent sawal ko bol ke bhi poochta hai
 * (secret fields par mic nahi hota).
 */
object PromptDialog {

    @Volatile private var showingFor: String = ""

    /** H5: dialog mic ke liye RECORD_AUDIO permission (chat wale flow jaisa). */
    const val REQ_PROMPT_VOICE_PERM = 1003
    private const val PREFS = "formmitra_prefs"
    private const val KEY_VOICE_ASKED = "prompt_voice_asked"
    private data class PendingVoice(val lang: String, val onText: (String) -> Unit)
    private var pendingVoice: PendingVoice? = null

    /** Kya is run ka dialog already khula hai? */
    fun isShowing(runId: String): Boolean = showingFor == runId

    fun dismiss() { showingFor = "" }

    fun show(activity: Activity, req: UserPrompt.Request) {
        if (showingFor == req.runId) return
        showingFor = req.runId
        activity.runOnUiThread {
            try {
                when (req.kind) {
                    "payment" -> showPayment(activity, req)
                    "choice" -> showChoice(activity, req)
                    "document" -> showDocument(activity, req)
                    "login" -> showLogin(activity, req)
                    "device_auth" -> showDeviceAuth(activity, req)
                    else -> showFields(activity, req) // otp | input
                }
                // Sawal bol ke bhi puchho (voice) — secret values kabhi nahi
                VoiceOutput.speak(activity, "${req.title}. ${req.message}".take(300))
            } catch (_: Exception) {
                showingFor = ""
            }
        }
    }

    // ---------- document picker ----------

    /** Vault docs me se chuno, ya naya upload karo. */
    const val REQ_DOC_PICK = 4101
    private var pendingDocPick: ((Uri?) -> Unit)? = null

    /** MainActivity.onActivityResult se forward karo. */
    fun onDocPickResult(requestCode: Int, data: Intent?) {
        if (requestCode != REQ_DOC_PICK) return
        val cb = pendingDocPick
        pendingDocPick = null
        val uri: Uri? = try { data?.data } catch (_: Exception) { null }
        try { cb?.invoke(uri) } catch (_: Exception) { }
    }

    private fun showDocument(activity: Activity, req: UserPrompt.Request) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val hint = if (req.docType.isNotEmpty()) "\nChahiye: ${req.docType}" else ""
        layout.addView(TextView(activity).apply {
            text = req.message + hint
            textSize = 15f
            setPadding(0, 0, 0, 16)
        })
        var dlg: AlertDialog? = null
        val docs = try { DocsStore.listDocs(activity) } catch (_: Exception) { emptyList() }
        if (docs.isEmpty()) {
            layout.addView(TextView(activity).apply {
                text = "Vault me koi document nahi hai — neeche se naya upload karo."
                textSize = 14f
                setPadding(0, 0, 0, 12)
            })
        }
        for (name in docs) {
            val b = Button(activity).apply {
                text = "📎 $name"
                textSize = 14f
                setOnClickListener {
                    // Sirf FILENAME jata hai — content kabhi nahi
                    answer(activity, req, mapOf("approved" to true, "doc" to name))
                    dlg?.dismiss()
                }
            }
            layout.addView(b)
        }
        val upBtn = Button(activity).apply {
            text = "📤 Naya document upload karo"
            textSize = 15f
            setOnClickListener {
                pendingDocPick = { uri ->
                    Thread({
                        val saved = if (uri != null) {
                            try { DocsStore.saveDoc(activity, uri) } catch (_: Exception) { null }
                        } else null
                        activity.runOnUiThread {
                            if (!saved.isNullOrEmpty()) {
                                toast(activity, "Save ho gaya: $saved")
                                answer(activity, req, mapOf("approved" to true, "doc" to saved))
                                try { dlg?.dismiss() } catch (_: Exception) { }
                            } else {
                                toast(activity, "Document save nahi hua — dobara try karo")
                            }
                        }
                    }, "fm-docsave").start()
                }
                try {
                    activity.startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        },
                        REQ_DOC_PICK
                    )
                } catch (_: Exception) {
                    pendingDocPick = null
                    toast(activity, "Picker nahi khula")
                }
            }
        }
        layout.addView(upBtn)
        dlg = AlertDialog.Builder(activity)
            .setTitle("📎 ${req.title}")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setCancelable(false)
            .setNegativeButton("❌ Cancel") { _, _ ->
                pendingDocPick = null
                UserPrompt.cancel(req.runId)
                showingFor = ""
            }
            .create()
        dlg.show()
    }

    // ---------- login (local-only secrets) ----------

    /**
     * Username+password dialog. Dono SENSITIVE — mic/voice NAHI, aur values
     * kahin record/transcribe nahi hoti. Loop inhe sirf page me locally
     * bharta hai (OTP pattern); server/AI/history me kabhi nahi jati.
     */
    private fun showLogin(activity: Activity, req: UserPrompt.Request) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        layout.addView(TextView(activity).apply {
            text = req.message + "\n\n🔒 Ye details sirf is page me bhari jayengi — " +
                "server/AI ko kabhi nahi bheji jayengi."
            textSize = 15f
            setPadding(0, 0, 0, 16)
        })
        val userEt = EditText(activity).apply {
            hint = "Username / Email / Mobile"
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passEt = EditText(activity).apply {
            hint = "Password"
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(TextView(activity).apply { text = "Username"; textSize = 14f })
        layout.addView(userEt)
        layout.addView(TextView(activity).apply {
            text = "Password"; textSize = 14f; setPadding(0, 16, 0, 0)
        })
        layout.addView(passEt)
        // L1-UPGRADE: save tick → agli baar is site par apne aap login
        // (sirf is phone me encrypted — server/AI ko kabhi nahi jata)
        val saveCb = CheckBox(activity).apply {
            text = "🔒 Is phone me save karo — agli baar apne aap login hoga"
            textSize = 14f
            isChecked = true
            setPadding(0, 20, 0, 0)
        }
        layout.addView(saveCb)
        val dlg = AlertDialog.Builder(activity)
            .setTitle("🔑 ${req.title}")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setCancelable(false)
            .setPositiveButton("✅ Login bhardo", null)
            .setNegativeButton("❌ Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val u = userEt.text.toString().trim()
                val p = passEt.text.toString()
                if (u.isEmpty() || p.isEmpty()) {
                    toast(activity, "Dono bharo")
                    return@setOnClickListener
                }
                // Values yahin rehti hain — answer me loop ko milti hain,
                // loop unhe sirf page me bharta hai (server/AI ko kabhi nahi).
                answer(
                    activity, req,
                    mapOf(
                        "approved" to true, "username" to u, "password" to p,
                        "save_login" to saveCb.isChecked
                    )
                )
                userEt.setText("")
                passEt.setText("")
                dlg.dismiss()
            }
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                userEt.setText("")
                passEt.setText("")
                UserPrompt.cancel(req.runId)
                showingFor = ""
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    /**
     * device_auth — user-gated #2 (biometric / device PIN): sirf user de
     * sakta hai. Maximum assistance:
     *  1. Pehle REAL system BiometricPrompt (framework API 29+) — user ek
     *     tap me fingerprint/face ya device PIN se authenticate kare.
     *  2. Device me biometric/PIN na ho ya fail ho → saaf manual dialog.
     * TTS announcement dono raaston me.
     */
    private fun showDeviceAuth(activity: Activity, req: UserPrompt.Request) {
        if (trySystemBiometric(activity, req)) return
        showManualDeviceAuth(activity, req)
    }

    /** Real system biometric prompt. @return true agar system prompt khul gaya. */
    @Suppress("DEPRECATION")
    private fun trySystemBiometric(activity: Activity, req: UserPrompt.Request): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        return try {
            val mgr = activity.getSystemService(
                android.hardware.biometrics.BiometricManager::class.java
            ) ?: return false
            val can = if (android.os.Build.VERSION.SDK_INT >= 30) {
                mgr.canAuthenticate(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                mgr.canAuthenticate()
            }
            if (can != android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) {
                return false
            }
            val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
                .setTitle(req.title.ifEmpty { "Pehchan verify karo" })
                .setSubtitle("Agent ka kaam aage badhane ke liye")
                .setDescription(
                    req.message.ifEmpty {
                        "Apne phone par fingerprint, face ya PIN se confirm karo."
                    }
                )
                .apply {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        setAllowedAuthenticators(
                            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        )
                    } else {
                        setDeviceCredentialAllowed(true)
                    }
                }
                .build()
            val cbs = object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?
                ) {
                    VoiceOutput.speak(activity, "Verify ho gaya — kaam aage badh raha hai.")
                    answer(activity, req, mapOf("approved" to true))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    // User ne cancel kiya ya lockout — prompt khatam
                    UserPrompt.cancel(req.runId)
                    showingFor = ""
                    VoiceOutput.stop()
                }

                override fun onAuthenticationFailed() {
                    // Galat fingerprint — system khud retry deta hai, kuch nahi
                }
            }
            prompt.authenticate(
                android.os.CancellationSignal(), activity.mainExecutor, cbs
            )
            VoiceOutput.speak(
                activity,
                "Ab aapko apne phone par fingerprint ya PIN dena hai. Baaki sab taiyar hai."
            )
            showingFor = req.runId
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Manual fallback — system biometric available na ho tab. */
    private fun showManualDeviceAuth(activity: Activity, req: UserPrompt.Request) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        layout.addView(TextView(activity).apply {
            text = "🔐"
            textSize = 44f
            gravity = Gravity.CENTER
        })
        layout.addView(TextView(activity).apply {
            text = req.message.ifEmpty {
                "Ab aapko apne phone par fingerprint ya PIN dena hai — baaki sab taiyar hai."
            }
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
        })
        var dlg: AlertDialog? = null
        val doneBtn = Button(activity).apply {
            text = "✅ Ho gaya"
            textSize = 16f
            setOnClickListener {
                answer(activity, req, mapOf("approved" to true))
                dlg?.dismiss()
            }
        }
        val cancelBtn = Button(activity).apply {
            text = "❌ Cancel"
            setOnClickListener {
                UserPrompt.cancel(req.runId)
                showingFor = ""
                dlg?.dismiss()
            }
        }
        layout.addView(doneBtn)
        layout.addView(cancelBtn)
        dlg = AlertDialog.Builder(activity)
            .setTitle("🔐 ${req.title}")
            .setView(layout)
            .setCancelable(false)
            .create()
        dlg.show()
        VoiceOutput.speak(
            activity,
            "Ab aapko apne phone par fingerprint ya PIN dena hai. Baaki sab taiyar hai."
        )
    }

    // ---------- fields (otp / input) ----------

    private fun showFields(activity: Activity, req: UserPrompt.Request) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        layout.addView(TextView(activity).apply {
            text = req.message
            textSize = 15f
            setPadding(0, 0, 0, 16)
        })
        val edits = mutableMapOf<String, EditText>()
        val fields = req.fields.ifEmpty {
            // otp kind: key "otp" rakho taaki loop ishe hamesha sensitive
            // maane (sirf page me locally bhare, server/AI ko kabhi na bheje)
            if (req.kind == "otp") listOf(UserPrompt.Field("otp", "OTP", "otp"))
            else listOf(UserPrompt.Field("value", "Likho", "text"))
        }
        for (f in fields) {
            layout.addView(TextView(activity).apply {
                text = f.label
                textSize = 14f
            })
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            // OTP/password: mic/voice NAHI — value kabhi record/transcribe nahi honi chahiye.
            val secret = f.type == "otp" || f.type == "password" ||
                f.key.lowercase().contains("otp") || f.key.lowercase().contains("password")
            val et = EditText(activity).apply {
                hint = f.label
                textSize = 16f
                // L1-UPGRADE: pata values pre-filled — sirf khaali bharega user
                req.prefill[f.key]?.let { if (it.isNotEmpty()) setText(it) }
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                inputType = when (f.type) {
                    "otp", "number", "phone" -> InputType.TYPE_CLASS_NUMBER
                    "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    "email" -> InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    else -> InputType.TYPE_CLASS_TEXT
                }
            }
            row.addView(et)
            if (!secret) {
                val mic = Button(activity).apply {
                    text = "🎤"
                    setOnClickListener { listenInto(activity, et) }
                }
                row.addView(mic)
            }
            layout.addView(row)
            edits[f.key] = et
        }
        // L1-UPGRADE (OTP maximum assistance): SMS User Consent — koi
        // permission nahi. Dialog khulne par listener start; OTP SMS aate
        // hi consent dialog auto-launch (ek tap), OTP field me auto-bharo.
        val isOtpPrompt = req.kind == "otp" ||
            fields.any { it.type == "otp" || it.key.lowercase().contains("otp") }
        var smsBtn: Button? = null
        var otpEdit: EditText? = null
        if (isOtpPrompt) {
            otpEdit = fields.firstOrNull {
                it.type == "otp" || it.key.lowercase().contains("otp")
            }?.let { edits[it.key] } ?: edits.values.firstOrNull()
            smsBtn = Button(activity).apply {
                text = "📩 SMS ka intezar hai…"
                textSize = 14f
                isEnabled = false
                setOnClickListener {
                    // Consent ready ho to turant launch
                    if (!SmsOtpConsent.launchConsent()) {
                        Toast.makeText(
                            activity,
                            "OTP wala SMS aate hi yahan se le lunga — thoda ruko",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            layout.addView(smsBtn)
            layout.addView(TextView(activity).apply {
                text = "OTP wala SMS aayega to ek tap me bhar jayega — type karne ki zaroorat nahi."
                textSize = 12f
                setTextColor(Color.parseColor("#80868B"))
                setPadding(0, 8, 0, 0)
            })
        }
        val dlg = AlertDialog.Builder(activity)
            .setTitle("🤖 ${req.title}")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setCancelable(false)
            .setPositiveButton("✅ Bhej do", null)
            .setNegativeButton("❌ Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val map = mutableMapOf<String, Any?>("approved" to true)
                edits.forEach { (k, et) -> map[k] = et.text.toString().trim() }
                if (isOtpPrompt) SmsOtpConsent.stop()
                answer(activity, req, map)
                dlg.dismiss()
            }
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                if (isOtpPrompt) SmsOtpConsent.stop()
                UserPrompt.cancel(req.runId)
                showingFor = ""
                dlg.dismiss()
            }
            if (isOtpPrompt) {
                // SMS consent flow start
                SmsOtpConsent.setActivity(activity)
                val et = otpEdit
                val btn = smsBtn
                SmsOtpConsent.onOtp = { otp ->
                    activity.runOnUiThread {
                        et?.setText(otp)
                        Toast.makeText(
                            activity, "✓ OTP SMS se bhar diya",
                            Toast.LENGTH_SHORT
                        ).show()
                        // L1: user ne consent me OTP dekh liya hai — ab
                        // auto-submit (user ne kuch badla to nahi hoga).
                        VoiceOutput.speak(activity, "OTP mil gaya, bhej raha hoon.")
                        val posBtn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                        posBtn?.postDelayed({
                            try {
                                if (dlg.isShowing &&
                                    et?.text?.toString() == otp
                                ) {
                                    posBtn.performClick()
                                }
                            } catch (_: Exception) { }
                        }, 1200)
                    }
                }
                SmsOtpConsent.onConsentReady = {
                    activity.runOnUiThread {
                        btn?.text = "📩 SMS se OTP lo"
                        btn?.isEnabled = true
                        // Dialog foreground me hai — consent turant launch
                        // karo taaki user ko ek hi tap karna pade
                        SmsOtpConsent.launchConsent()
                    }
                }
                SmsOtpConsent.startListening(activity)
                VoiceOutput.speak(
                    activity,
                    "Ab aapko sirf OTP dalna hai, baaki sab taiyar hai. " +
                        "SMS aate hi OTP apne aap bhar jayega."
                )
            }
        }
        dlg.show()
    }

    // ---------- choice ----------

    private fun showChoice(activity: Activity, req: UserPrompt.Request) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        layout.addView(TextView(activity).apply {
            text = req.message
            textSize = 15f
            setPadding(0, 0, 0, 16)
        })
        var dlg: AlertDialog? = null
        // Voice se choose: mic → bola hua text option se match
        val micRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        val micBtn = Button(activity).apply { text = "🎤 Bol ke chunno" }
        micRow.addView(micBtn)
        layout.addView(micRow)
        for (opt in req.options) {
            val b = Button(activity).apply {
                text = opt
                textSize = 15f
                setOnClickListener {
                    answer(activity, req, mapOf("approved" to true, "choice" to opt))
                    dlg?.dismiss()
                }
            }
            layout.addView(b)
        }
        micBtn.setOnClickListener {
            listenOnce(activity, "hi-IN") { spoken ->
                val hit = req.options.firstOrNull { o ->
                    spoken.contains(o, ignoreCase = true) ||
                        o.split(" ").any { w -> w.length > 3 && spoken.contains(w, ignoreCase = true) }
                }
                if (hit != null) {
                    toast(activity, "Chuna: $hit")
                    answer(activity, req, mapOf("approved" to true, "choice" to hit))
                    dlg?.dismiss()
                } else {
                    toast(activity, "Samajh nahi aaya — button dabao ya dobara bolo")
                    VoiceOutput.speak(activity, "Samajh nahi aaya. ${req.options.joinToString(", ")} me se bolo.")
                }
            }
        }
        dlg = AlertDialog.Builder(activity)
            .setTitle("🤖 ${req.title}")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setCancelable(false)
            .setNegativeButton("❌ Cancel") { _, _ ->
                UserPrompt.cancel(req.runId)
                showingFor = ""
            }
            .create()
        dlg.show()
    }

    // ---------- payment (2 stage) ----------

    private fun showPayment(activity: Activity, req: UserPrompt.Request) {
        val pay = req.payment
        val amount = pay?.amount?.ifEmpty { null } ?: "?"
        val merchant = pay?.merchant?.ifEmpty { null } ?: "site"
        // Stage 1: approval
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        layout.addView(TextView(activity).apply {
            text = "💰 ₹$amount"
            textSize = 30f
            setTextColor(Color.parseColor("#0E7C5B"))
            gravity = Gravity.CENTER
        })
        layout.addView(TextView(activity).apply {
            text = "$merchant ko pay karna hai.\n\n${req.message}\n\nAap khud apne UPI app se pay karoge — agent kabhi khud payment nahi karta."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
        })
        var dlg: AlertDialog? = null
        val yesBtn = Button(activity).apply {
            text = "✅ Haan, main pay karta hu"
            textSize = 16f
            setOnClickListener {
                dlg?.dismiss()
                showPaymentPay(activity, req, amount, merchant, pay?.upiId.orEmpty())
            }
        }
        val noBtn = Button(activity).apply {
            text = "❌ Nahi, rehne do"
            setOnClickListener {
                answer(activity, req, mapOf("approved" to false))
                dlg?.dismiss()
            }
        }
        layout.addView(yesBtn)
        layout.addView(noBtn)
        dlg = AlertDialog.Builder(activity)
            .setTitle("💰 Payment approval")
            .setView(layout)
            .setCancelable(false)
            .create()
        dlg.show()
        VoiceOutput.speak(
            activity,
            "Payment approval. $merchant ko $amount rupaye pay karna hai. Haan ya na bolo."
        )
    }

    /** Stage 2: method choice → QR / UPI intent / site-khud + countdown + "ho gaya". */
    private fun showPaymentPay(
        activity: Activity,
        req: UserPrompt.Request,
        amount: String,
        merchant: String,
        upiId: String
    ) {
        val timeoutSec = if (req.timeoutSec > 0) req.timeoutSec else 600L
        val hasUpi = upiId.isNotEmpty() && PaymentFlow.isValidUpiId(upiId)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 28, 40, 12)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        layout.addView(TextView(activity).apply {
            text = "₹$amount → $merchant"
            textSize = 20f
            gravity = Gravity.CENTER
        })
        val timerTv = TextView(activity).apply {
            text = "⏳ ${PaymentFlow.formatCountdown(timeoutSec)}"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B06000"))
            setPadding(0, 8, 0, 8)
        }
        layout.addView(timerTv)

        var method = if (hasUpi) "qr" else "site"

        // Method choice — user khud chune kaise pay karega
        val methodLabel = TextView(activity).apply {
            text = "Kaise pay karoge?"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 4)
        }
        layout.addView(methodLabel)
        val methodRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        layout.addView(methodRow)

        // Content boxes (choice ke hisaab se show/hide)
        val qrBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val upiBtn: Button
        if (hasUpi) {
            val uri = PaymentFlow.upiUri(upiId, merchant, amount)
            val bmp = qrBitmap(uri, 640)
            if (bmp != null) {
                qrBox.addView(ImageView(activity).apply {
                    setImageBitmap(bmp)
                    layoutParams = LinearLayout.LayoutParams(640, 640).apply {
                        gravity = Gravity.CENTER
                    }
                    setPadding(0, 8, 0, 8)
                })
            }
            qrBox.addView(TextView(activity).apply {
                text = "Apne UPI app (GPay/PhonePe/Paytm) se ye QR scan karo."
                textSize = 13f
                gravity = Gravity.CENTER
            })
            upiBtn = Button(activity).apply {
                text = "📱 UPI app kholo"
                setOnClickListener {
                    try {
                        activity.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    } catch (_: Exception) {
                        toast(activity, "UPI app nahi khula — QR scan karo")
                    }
                }
            }
        } else {
            upiBtn = Button(activity).apply { visibility = View.GONE }
        }
        val siteTv = TextView(activity).apply {
            text = "Site ka QR / payment page khud dekho aur apne UPI app se pay karo.\n" +
                "(UPI ID site se nahi mili, isliye QR nahi bana.)"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
            visibility = if (hasUpi) View.GONE else View.VISIBLE
        }

        fun renderMethod() {
            qrBox.visibility = if (method == "qr" && hasUpi) View.VISIBLE else View.GONE
            upiBtn.visibility = if (method == "upi_app" && hasUpi) View.VISIBLE else View.GONE
            siteTv.visibility = if (method == "site" || !hasUpi) View.VISIBLE else View.GONE
        }
        if (hasUpi) {
            val btnQr = Button(activity).apply { text = "📷 QR" }
            val btnUpi = Button(activity).apply { text = "📱 UPI app" }
            val btnSite = Button(activity).apply { text = "🌐 Site khud" }
            val all = listOf(btnQr to "qr", btnUpi to "upi_app", btnSite to "site")
            for ((b, m) in all) {
                b.setOnClickListener {
                    method = m
                    renderMethod()
                    for ((bb, mm) in all) bb.alpha = if (mm == m) 1f else 0.5f
                }
                methodRow.addView(b)
            }
            btnQr.alpha = 1f; btnUpi.alpha = 0.5f; btnSite.alpha = 0.5f
        } else {
            methodRow.visibility = View.GONE
            methodLabel.visibility = View.GONE
        }
        layout.addView(qrBox)
        layout.addView(upiBtn)
        layout.addView(siteTv)
        renderMethod()

        var dlg: AlertDialog? = null
        val handler = Handler(Looper.getMainLooper())
        val doneBtn = Button(activity).apply {
            text = "💰 Payment ho gaya"
            textSize = 16f
            setOnClickListener {
                handler.removeCallbacksAndMessages(null)
                answer(
                    activity, req,
                    mapOf("approved" to true, "payment_done" to true, "method" to method)
                )
                dlg?.dismiss()
            }
        }
        layout.addView(doneBtn)

        dlg = AlertDialog.Builder(activity)
            .setTitle("💰 Payment karo — time hai")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setCancelable(false)
            .setNegativeButton("❌ Cancel") { _, _ ->
                handler.removeCallbacksAndMessages(null)
                UserPrompt.cancel(req.runId)
                showingFor = ""
            }
            .create()
        dlg.show()
        val finalDlg = dlg
        val endAt = System.currentTimeMillis() + timeoutSec * 1000
        val tick = object : Runnable {
            override fun run() {
                val left = (endAt - System.currentTimeMillis()) / 1000
                if (left <= 0) {
                    answer(activity, req, mapOf("approved" to true, "payment_done" to false, "reason" to "timeout"))
                    try { finalDlg.dismiss() } catch (_: Exception) { }
                    return
                }
                timerTv.text = "⏳ ${PaymentFlow.formatCountdown(left)}"
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(tick)
        VoiceOutput.speak(
            activity,
            "Ab aapko sirf $amount rupaye pay karne hain $merchant ko — baaki sab taiyar hai. " +
                "UPI app kholo ya QR scan karo. Ho jaye to payment ho gaya dabao."
        )
    }

    // ---------- helpers ----------

    private fun answer(activity: Activity, req: UserPrompt.Request, map: Map<String, Any?>) {
        UserPrompt.answer(req.runId, PaymentFlow.answerJson(map))
        showingFor = ""
        VoiceOutput.stop()
    }

    private fun qrBitmap(text: String, size: Int): Bitmap? = try {
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size)
            for (y in 0 until size)
                bmp.setPixel(x, y, if (bits.get(x, y)) Color.BLACK else Color.WHITE)
        bmp
    } catch (_: Exception) {
        null
    }

    private fun toast(activity: Activity, msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    /** Ek baar suno → text wapas. Dialog ke mic ke liye. */
    private fun listenOnce(activity: Activity, lang: String, onText: (String) -> Unit) {
        // H5: chat wale mic jaisa permission flow (AgentChatView.onMicClick).
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val askedBefore = try {
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_VOICE_ASKED, false)
            } catch (_: Exception) { false }
            if (askedBefore &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            ) {
                // Permanently denied ("Don't ask again") — Settings ka rasta do.
                toast(activity, "Mic permission chahiye 🎤 — Settings me de do")
                try {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", activity.packageName, null)
                        )
                    )
                } catch (_: Exception) { }
                return
            }
            // Pehli baar (ya rationale ke saath) — system dialog maango; result
            // MainActivity.onRequestPermissionsResult se wapas aayega.
            pendingVoice = PendingVoice(lang, onText)
            try {
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_VOICE_ASKED, true).apply()
            } catch (_: Exception) { }
            activity.requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PROMPT_VOICE_PERM
            )
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            toast(activity, "Voice nahi mila")
            return
        }
        try {
            val sr = SpeechRecognizer.createSpeechRecognizer(activity)
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(r: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onError(e: Int) {
                    try { sr.destroy() } catch (_: Exception) { }
                    toast(activity, "Suna nahi gaya")
                }

                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    try { sr.destroy() } catch (_: Exception) { }
                    if (t.isNotEmpty()) onText(t)
                }
            })
            val ri = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            sr.startListening(ri)
            toast(activity, "🎤 Bolo…")
        } catch (_: Exception) {
            toast(activity, "Voice nahi mila")
        }
    }

    /**
     * MainActivity.onRequestPermissionsResult se forward hota hai
     * (chat ke onVoicePermissionResult jaisa). Granted → pending listen
     * resume karo; denied → Settings ka hint do.
     */
    fun onVoicePermissionResult(activity: Activity, granted: Boolean) {
        val pending = pendingVoice
        pendingVoice = null
        if (granted && pending != null) {
            listenOnce(activity, pending.lang, pending.onText)
        } else if (!granted) {
            toast(activity, "Mic permission chahiye 🎤 — Settings me de do")
        }
    }

    private fun listenInto(activity: Activity, target: EditText) {
        listenOnce(activity, "hi-IN") { t ->
            val cur = target.text.toString()
            target.setText(if (cur.isBlank()) t else "$cur $t")
            target.setSelection(target.text.length)
        }
    }
}

package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
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
 * kinds: otp | input | choice | payment.
 *
 * - otp/input: fields bharo → agent aage badhega (loop wait kar raha hai).
 * - choice: options me se chuno (payment method choose karne ke liye bhi).
 * - payment: pehle approval (₹X pay karna hai?) → phir QR/UPI + countdown →
 *   "Payment ho gaya" → agent site par verify karega.
 * Har dialog me 🎤 mic (voice input) aur agent sawal ko bol ke bhi poochta hai.
 */
object PromptDialog {

    @Volatile private var showingFor: String = ""

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
                    else -> showFields(activity, req) // otp | input
                }
                // Sawal bol ke bhi puchho (voice)
                VoiceOutput.speak(activity, "${req.title}. ${req.message}".take(300))
            } catch (_: Exception) {
                showingFor = ""
            }
        }
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
            listOf(UserPrompt.Field("value", "Likho", if (req.kind == "otp") "otp" else "text"))
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
                answer(activity, req, map)
                dlg.dismiss()
            }
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                UserPrompt.cancel(req.runId)
                showingFor = ""
                dlg.dismiss()
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
            "$amount rupaye ka payment karo. Time hai ${timeoutSec / 60} minute. Ho jaye to payment ho gaya dabao."
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

    private fun listenInto(activity: Activity, target: EditText) {
        listenOnce(activity, "hi-IN") { t ->
            val cur = target.text.toString()
            target.setText(if (cur.isBlank()) t else "$cur $t")
            target.setSelection(target.text.length)
        }
    }
}

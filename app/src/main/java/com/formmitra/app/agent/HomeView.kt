package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * HomeView — Home tab ka native view (v20):
 *  - services strip (Tracking/Jobs/Scholarships/Resume → WebView)
 *  - 🎯 Kaam chuno: 5 work-category cards (Task 5) — tap par pehle
 *    details popup (partial fill OK), phir agent chat usi category
 *    context me khulta hai (chat body me `category` jata hai)
 *  - 🔗 Quick links: Wallet/History/Document Vault/Profile tab cards
 *  - 🔴 live browser button + embedded Mitra chat (AgentChatView)
 *
 * v20 polish: tinted cards, appear animation, press feedback.
 */
class HomeView(
    context: Context,
    chatView: AgentChatView,
    private val onOpenService: (String) -> Unit,
    private val onOpenTab: (String) -> Unit,
    private val onStartCategory: (category: String, label: String, prefill: Map<String, String>) -> Unit,
    private val onShowMirror: () -> Unit
) : LinearLayout(context) {

    private val liveBtn: Button

    // v23 FIX: pehle `with(UiKit) { dp(v) }` tha — UiKit.dp ek Context
    // extension hai, UiKit receiver par apply nahi hota, isliye dp(v)
    // khud ko hi call karke infinite recursion → StackOverflowError
    // (v21/v22 launch crash). Ab seedha formula.
    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    /** Work-wise category: key = server ko bheja jane wala exact `category` value. */
    private data class WorkCat(
        val key: String,
        val label: String,
        val icon: String,
        val bg: String,
        val border: String,
        /** key, label, hint — sab optional (partial fill allowed) */
        val fields: List<Triple<String, String, String>>
    )

    private val workCats = listOf(
        WorkCat(
            "apply_track", "Apply Track", "📝", "#E8F0FE", "#8AB4F8",
            listOf(
                Triple("full_name", "Naam", "Apna naam"),
                Triple("phone", "Phone", "10-digit mobile"),
                Triple("state", "Rajya", ""),
                Triple("district", "Zila", ""),
                Triple("qualification", "Yogyata", "")
            )
        ),
        WorkCat(
            "zamin_track", "Zamin Track", "🌾", "#E6F4EA", "#81C995",
            listOf(
                Triple("full_name", "Naam", "Apna naam"),
                Triple("state", "Rajya", ""),
                Triple("district", "Zila", ""),
                Triple("village", "Gaon", ""),
                Triple("khata", "Khata no.", ""),
                Triple("khesra", "Khesra no.", ""),
                Triple("mauza", "Mauza", "")
            )
        ),
        WorkCat(
            "resume_create", "Resume Create", "📄", "#FEF7E0", "#F6C343",
            listOf(
                Triple("full_name", "Naam", "Apna naam"),
                Triple("phone", "Phone", "10-digit mobile"),
                Triple("email", "Email", ""),
                Triple("qualification", "Yogyata", ""),
                Triple("occupation", "Pesha", "")
            )
        ),
        WorkCat(
            "job_find", "Job Find", "💼", "#F3E8FD", "#C58AF9",
            listOf(
                Triple("full_name", "Naam", "Apna naam"),
                Triple("state", "Rajya", ""),
                Triple("district", "Zila", ""),
                Triple("qualification", "Yogyata", "")
            )
        ),
        WorkCat(
            "scholarship", "Scholarship", "🎓", "#FCE8E6", "#F28B82",
            listOf(
                Triple("full_name", "Naam", "Apna naam"),
                Triple("state", "Rajya", ""),
                Triple("district", "Zila", ""),
                Triple("qualification", "Yogyata", ""),
                Triple("category_caste", "Category/Jati", "SC/ST/OBC/General…")
            )
        )
    )

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#FAFBFC"))
        val pad = dp(12)

        // Services strip — soft buttons
        val strip = HorizontalScrollView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            )
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(pad, dp(8), pad, dp(4))
        }
        val services = listOf(
            "🌾 Tracking" to "/tracking",
            "💼 Jobs" to "/jobs",
            "🎓 Scholarships" to "/scholarships",
            "📄 Resume" to "/resume"
        )
        for ((label, path) in services) {
            val b = Button(context).apply {
                text = label
                textSize = 13f
                setTextColor(Color.parseColor("#1A73E8"))
                background = with(UiKit) { context.softBtnBg() }
                setOnClickListener { onOpenService(path) }
            }
            UiKit.pressFeedback(b)
            val lp = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(8), 0) }
            row.addView(b, lp)
        }
        strip.addView(row)
        addView(strip)

        // Live browser — sirf tab dikhao jab agent ka browser sach me live ho
        liveBtn = Button(context).apply {
            text = "🔴 Live browser dekho"
            textSize = 13f
            visibility = View.GONE
            setOnClickListener { onShowMirror() }
        }
        addView(
            liveBtn,
            LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(pad, 0, pad, dp(4)) }
        )

        // 🎯 Kaam chuno — 5 work-category cards (Task 5)
        addView(with(UiKit) { context.sectionTitle("🎯 Kaam chuno") })
        val grid = GridLayout(context).apply {
            columnCount = 2
            setPadding(pad, 0, pad, dp(2))
        }
        workCats.forEachIndexed { idx, cat ->
            val card = workCard(cat)
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = if (idx == workCats.lastIndex) {
                    // aakhri card poori chaudaai me
                    GridLayout.spec(GridLayout.UNDEFINED, 2)
                } else {
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
                setMargins(
                    dp(4), dp(4),
                    dp(4), dp(4)
                )
            }
            grid.addView(card, lp)
            UiKit.appear(card, delayMs = idx * 60L)
        }
        addView(grid)

        // 🔗 Quick links — tab navigation cards (purane 4, feature intact)
        addView(with(UiKit) { context.sectionTitle("🔗 Quick links") })
        val links = GridLayout(context).apply {
            columnCount = 2
            setPadding(pad, 0, pad, dp(6))
        }
        val quicks = listOf(
            Triple("💰", "Wallet", "/wallet"),
            Triple("🕘", "History", "/history"),
            Triple("📁", "Document Vault", "vault"),
            Triple("👤", "Profile", "/profile")
        )
        quicks.forEachIndexed { idx, (icon, label, target) ->
            val card = linkCard(icon, label) {
                if (target == "vault") onOpenTab("vault") else onOpenTab(target)
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(
                    dp(4), dp(4),
                    dp(4), dp(4)
                )
            }
            links.addView(card, lp)
            UiKit.appear(card, delayMs = 300 + idx * 60L)
        }
        addView(links)

        // Mitra chat — baki poori jagah
        chatView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, 0, 1f
        )
        addView(chatView)
    }

    /** Work-category card: icon + label, tinted, tap par details popup. */
    private fun workCard(cat: WorkCat): LinearLayout {
        val ctx = context
        return LinearLayout(ctx).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = with(UiKit) { ctx.tintCard(cat.bg, cat.border) }
            setPadding(dp(12), dp(14), dp(12), dp(14))
            addView(TextView(ctx).apply {
                text = cat.icon
                textSize = 30f
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = cat.label
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#202124"))
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            isClickable = true
            isFocusable = true
            setOnClickListener { showCategoryPopup(cat) }
            UiKit.pressFeedback(this)
        }
    }

    /** Quick-link card: icon + label, tap par tab khule. */
    private fun linkCard(icon: String, label: String, onTap: () -> Unit): LinearLayout {
        val ctx = context
        return LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = with(UiKit) { ctx.cardBg() }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(TextView(ctx).apply {
                text = icon
                textSize = 22f
                setPadding(0, 0, dp(8), 0)
            })
            addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#202124"))
            })
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
            UiKit.pressFeedback(this)
        }
    }

    /**
     * Task 5: category details popup — basic fields, sab optional.
     * Skip = khaali, Continue = bhare hue fields ke saath.
     * Uske baad agent chat usi category context me khulta hai.
     */
    private fun showCategoryPopup(cat: WorkCat) {
        val act = context as? Activity ?: return
        val layout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(with(UiKit) { act.dp(24) }, with(UiKit) { act.dp(8) }, with(UiKit) { act.dp(24) }, with(UiKit) { act.dp(8) })
        }
        layout.addView(TextView(act).apply {
            text = "Jo pata ho bhar do — khaali chhodna bhi chalega."
            textSize = 13f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, 0, 0, with(UiKit) { act.dp(8) })
        })
        val inputs = LinkedHashMap<String, EditText>()
        for ((key, label, hint) in cat.fields) {
            layout.addView(with(UiKit) { act.fieldLabel(label) })
            // B: har field me mic button — bolo to text LIVE field me likhe;
            // haath se type ka option bhi hamesha rehta hai.
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val et = with(UiKit) { act.formInput(hint) }
            et.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            val mic = Button(act).apply {
                text = "🎤"
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    with(UiKit) { act.dp(52) }, with(UiKit) { act.dp(52) }
                ).apply { leftMargin = with(UiKit) { act.dp(8) } }
                setOnClickListener { PopupVoice.toggle(act, et, this) }
            }
            row.addView(et)
            row.addView(mic)
            layout.addView(row)
            inputs[key] = et
        }
        val dlg = AlertDialog.Builder(act)
            .setTitle("${cat.icon} ${cat.label}")
            .setView(ScrollView(act).apply { addView(layout) })
            .setCancelable(true)
            .setPositiveButton("▶️ Continue", null)
            .setNeutralButton("⏭️ Skip", null)
            .create()
        dlg.setOnShowListener {
            val collect = {
                val m = LinkedHashMap<String, String>()
                inputs.forEach { (k, et) ->
                    val v = et.text.toString().trim()
                    if (v.isNotEmpty()) m[k] = v
                }
                m
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dlg.dismiss()
                onStartCategory(cat.key, cat.label, collect())
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dlg.dismiss()
                onStartCategory(cat.key, cat.label, emptyMap())
            }
        }
        dlg.setOnDismissListener { PopupVoice.stop() }
        dlg.show()
    }

    /** agent_mirror.png fresh (2 min) ho to live button dikhao. */
    fun refreshLiveButton() {
        try {
            val f = File(context.cacheDir, "agent_mirror.png")
            val fresh = f.exists() &&
                System.currentTimeMillis() - f.lastModified() < 120_000
            liveBtn.visibility = if (fresh) View.VISIBLE else View.GONE
        } catch (_: Exception) {
            liveBtn.visibility = View.GONE
        }
    }

    /** MainActivity.onRequestPermissionsResult se forward hota hai. */
    fun onPopupVoicePermissionResult(granted: Boolean) {
        PopupVoice.onPermissionResult(context as? Activity, granted)
    }

    companion object {
        /** MainActivity.onRequestPermissionsResult se forward hota hai. */
        const val REQ_POPUP_VOICE_PERM = 1003
    }

    /**
     * PopupVoice — category details popup ke HAR field me mic button (Task B).
     * Wahi STT fix jo chat me hai: hi-IN → en-IN fallback, partial results
     * se field me LIVE text, permission flow, error toasts. Haath se type
     * ka option hamesha rehta hai (mic sirf bharne me madad karta hai).
     * Ek waqt me ek hi field sunta hai; popup band ho to stop.
     */
    private object PopupVoice {
        private var recognizer: SpeechRecognizer? = null
        private var field: EditText? = null
        private var micBtn: Button? = null
        private var baseText = ""
        private var fallbackTried = false

        fun toggle(act: Activity, et: EditText, btn: Button) {
            if (recognizer != null && field === et) {
                stop()
                return
            }
            stop()
            if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                field = et
                micBtn = btn
                act.requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO), REQ_POPUP_VOICE_PERM
                )
                return
            }
            start(act, et, btn, "hi-IN")
        }

        fun onPermissionResult(act: Activity?, granted: Boolean) {
            val et = field
            val btn = micBtn
            if (!granted || act == null || et == null || btn == null) {
                if (!granted && act != null) {
                    Toast.makeText(
                        act, "🎤 Mic permission nahi mila — haath se likh do",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                stop()
                return
            }
            start(act, et, btn, "hi-IN")
        }

        fun stop() {
            try {
                recognizer?.stopListening()
                recognizer?.destroy()
            } catch (_: Exception) { }
            recognizer = null
            field = null
            try { micBtn?.text = "🎤" } catch (_: Exception) { }
            micBtn = null
            baseText = ""
            fallbackTried = false
        }

        private fun start(act: Activity, et: EditText, btn: Button, lang: String) {
            if (!SpeechRecognizer.isRecognitionAvailable(act)) {
                Toast.makeText(act, "🎤 Voice input uplabdh nahi", Toast.LENGTH_SHORT).show()
                return
            }
            stop()
            field = et
            micBtn = btn
            baseText = et.text.toString()
            fallbackTried = lang != "hi-IN"
            btn.text = "⏹"
            val sr = SpeechRecognizer.createSpeechRecognizer(act)
            recognizer = sr
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(e: Int, p: Bundle?) {}

                override fun onPartialResults(r: Bundle?) {
                    setLiveText(r)
                }

                override fun onResults(r: Bundle?) {
                    setLiveText(r)
                    stop()
                }

                override fun onError(e: Int) {
                    // Wahi fallback jo chat me hai: hi-IN fail → en-IN ek baar
                    if (!fallbackTried &&
                        (e == SpeechRecognizer.ERROR_NETWORK ||
                            e == SpeechRecognizer.ERROR_SERVER ||
                            e == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                            e == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                    ) {
                        fallbackTried = true
                        try { sr.stopListening() } catch (_: Exception) { }
                        start(act, et, btn, "en-IN")
                        return
                    }
                    val msg = when (e) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "🎤 Samajh nahi aaya — dobara bolo"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "🎤 Kuch sunai nahi diya"
                        else -> "🎤 Sun nahi paya — haath se likh do"
                    }
                    Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
                    stop()
                }

                private fun setLiveText(r: Bundle?) {
                    val list = r?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    val t = list?.firstOrNull()?.trim().orEmpty()
                    if (t.isEmpty()) return
                    val f = field ?: return
                    // LIVE: bola hua text field me dikhe
                    f.setText(if (baseText.isEmpty()) t else "$baseText $t")
                    f.setSelection(f.text.length)
                }
            })
            try {
                sr.startListening(intent)
            } catch (_: Exception) {
                Toast.makeText(act, "🎤 Mic shuru nahi hua", Toast.LENGTH_SHORT).show()
                stop()
            }
        }
    }
}

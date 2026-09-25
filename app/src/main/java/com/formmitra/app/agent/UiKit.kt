package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * UiKit (v20) — poore app ka shared decoration kit.
 *
 * Tasteful polish, clownish nahi:
 *  - soft rounded cards (halki border, halka shadow)
 *  - primary / soft buttons
 *  - fade+slide appear animation
 *  - button press feedback (halka scale)
 *  - section titles, field labels
 *
 * Koi feature logic yahan nahi — sirf look & feel.
 */
object UiKit {

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- backgrounds ----------

    /** Soft white card: halki border + 14dp radius. */
    fun Context.cardBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.WHITE)
        setStroke(dp(1), Color.parseColor("#E2E5EA"))
        cornerRadius = dp(14).toFloat()
    }

    /** Tinted card (category cards ke liye) — halka rang, rangli border. */
    fun Context.tintCard(bg: String, border: String): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(bg))
            setStroke(dp(1), Color.parseColor(border))
            cornerRadius = dp(14).toFloat()
        }

    /** Primary (hara) rounded button background. */
    fun Context.primaryBtnBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#0E7C5B"))
        cornerRadius = dp(12).toFloat()
    }

    /** Soft (halka neela) rounded button background. */
    fun Context.softBtnBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#E8F0FE"))
        setStroke(dp(1), Color.parseColor("#B9CFF5"))
        cornerRadius = dp(12).toFloat()
    }

    fun Context.chipBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#E8F0FE"))
        setStroke(dp(1), Color.parseColor("#1A73E8"))
        cornerRadius = dp(18).toFloat()
    }

    // ---------- views ----------

    fun Context.sectionTitle(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 15f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#202124"))
        setPadding(dp(2), dp(10), dp(2), dp(6))
    }

    fun Context.fieldLabel(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.parseColor("#80868B"))
        setPadding(0, dp(8), 0, dp(2))
    }

    fun Context.formInput(hint: String, prefill: String = ""): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(prefill)
            textSize = 16f
            setTextColor(Color.parseColor("#202124"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F8F9FA"))
                setStroke(dp(1), Color.parseColor("#DADCE0"))
                cornerRadius = dp(10).toFloat()
            }
        }

    /** Primary button banakar do (text + click). */
    fun Context.primaryButton(text: String, onClick: () -> Unit): android.widget.Button =
        android.widget.Button(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.WHITE)
            background = primaryBtnBg()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick() }
            pressFeedback(this)
        }

    // ---------- motion (subtle) ----------

    /** Fade + halka slide-up on appear. delayMs se stagger kar sakte ho. */
    fun appear(v: View, delayMs: Long = 0) {
        v.alpha = 0f
        v.translationY = 26f
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(260)
            .start()
    }

    /** Button press feedback — halka scale down/up. Click ko rokta nahi. */
    fun pressFeedback(v: View) {
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.96f).scaleY(0.96f)
                        .setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(140).start()
            }
            false
        }
        try { v.elevation = 2f } catch (_: Exception) { }
    }

    // ---------- popup field labels (category sheets ke liye) ----------

    private val EXTRA_LABELS = mapOf(
        "khata" to "Khata no.",
        "khesra" to "Khesra no.",
        "mauza" to "Mauza"
    )

    fun fieldLabelFor(key: String): String =
        EXTRA_LABELS[key] ?: DetailExtractor.label(key)

    // ---------- document type dialog (Task 3) ----------

    /**
     * "Kaun sa document hai?" — options + Other (custom text).
     * Sirf type string wapas karta hai; save caller karta hai.
     */
    fun askDocType(act: Activity, onPick: (String) -> Unit) {
        val types: Array<CharSequence> =
            Array(DocsStore.DOC_TYPES.size) { DocsStore.DOC_TYPES[it] }
        AlertDialog.Builder(act)
            .setTitle("Kaun sa document hai?")
            .setItems(types) { _, which ->
                val t = types[which].toString()
                if (t == "Other") askDocTypeCustom(act, onPick)
                else onPick(t)
            }
            .setNegativeButton("Rehne do", null)
            .show()
    }

    private fun askDocTypeCustom(act: Activity, onPick: (String) -> Unit) {
        val et = act.formInput("Document ka naam likho…")
        val wrap = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(24), act.dp(8), act.dp(24), act.dp(8))
            addView(et)
        }
        AlertDialog.Builder(act)
            .setTitle("Kaun sa document hai?")
            .setView(wrap)
            .setPositiveButton("OK") { d, _ ->
                val t = et.text.toString().trim()
                d.dismiss()
                onPick(if (t.isEmpty()) "Other" else t)
            }
            .setNegativeButton("Wapas", null)
            .show()
    }
}

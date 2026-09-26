package com.formmitra.app.agent

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * FmTheme — FormMitra ka rich + trusted design system.
 *
 * Philosophy: kala (black) kahin nahi. Gehara emerald + sona (gold) +
 * warm cream — premium, bharosemand feel (jaise bank/jewellery app).
 *
 * Palette:
 *  - Emerald deep  #0B5A41  (primary, headers, selected tab)
 *  - Emerald       #0E7C5B  (brand green, buttons)
 *  - Gold          #C9A227  (accent, highlights, premium touch)
 *  - Cream         #FAF6EE  (screen background)
 *  - Card white    #FFFFFF  (cards)
 *  - Ink           #2E2A24  (text — warm dark, black NAHI)
 *  - Ink soft      #6B6257  (secondary text)
 *  - Border warm   #E8DFC9  (card borders)
 */
object FmTheme {

    // ---------- colors ----------
    const val EMERALD_DEEP = "#0B5A41"
    const val EMERALD = "#0E7C5B"
    const val EMERALD_DARK = "#084A35"
    const val EMERALD_TINT = "#E6F4ED"
    const val GOLD = "#C9A227"
    const val GOLD_DARK = "#A8861C"
    const val GOLD_TINT = "#F7ECD0"
    const val CREAM = "#FAF6EE"
    const val CARD = "#FFFFFF"
    const val INK = "#2E2A24"
    const val INK_SOFT = "#6B6257"
    const val INK_FAINT = "#9A9184"
    const val BORDER = "#E8DFC9"
    const val SUCCESS = "#1B7A43"
    const val SUCCESS_TINT = "#E5F4EA"
    const val WARNING = "#B7791F"
    const val WARNING_TINT = "#FDF3DC"
    const val ERROR = "#C0392B"
    const val ERROR_TINT = "#FBE9E6"
    const val INFO = "#2471A3"
    const val INFO_TINT = "#E7F1F9"

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- backgrounds ----------

    /** Rich emerald→deep gradient (headers, selected tab pill). */
    fun Context.headerGradient(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            Color.parseColor(EMERALD),
            Color.parseColor(EMERALD_DEEP),
            Color.parseColor(EMERALD_DARK)
        )
    ).apply { cornerRadius = dp(0).toFloat() }

    /** Gold→deep gold gradient (premium accents, CTA highlights). */
    fun Context.goldGradient(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.parseColor("#DDBE4A"), Color.parseColor(GOLD))
    ).apply { cornerRadius = dp(12).toFloat() }

    /** Emerald gradient button. */
    fun Context.emeraldBtnBg(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Color.parseColor(EMERALD), Color.parseColor(EMERALD_DEEP))
    ).apply { cornerRadius = dp(14).toFloat() }

    /** Warm white card: cream border, soft radius, halka shadow via elevation. */
    fun Context.richCard(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(CARD))
        setStroke(dp(1), Color.parseColor(BORDER))
        cornerRadius = dp(16).toFloat()
    }

    /** Tinted card (status/info) — halka rang + rangli border. */
    fun Context.tintCard(bg: String, border: String): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(bg))
            setStroke(dp(1), Color.parseColor(border))
            cornerRadius = dp(16).toFloat()
        }

    /** Selected tab pill — emerald gradient + gold bottom edge. */
    fun Context.selectedTabBg(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Color.parseColor(EMERALD), Color.parseColor(EMERALD_DEEP))
    ).apply { cornerRadius = dp(20).toFloat() }

    /** Unselected tab pill — transparent, warm border. */
    fun Context.unselectedTabBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = dp(20).toFloat()
    }

    // ---------- text ----------

    /** Screen title (header me) — white on emerald. */
    fun TextView.titleOnDark(text: String, size: Float = 20f) {
        this.text = text
        textSize = size
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
    }

    /** Section heading — ink, bold. */
    fun TextView.sectionTitle(text: String, size: Float = 17f) {
        this.text = text
        textSize = size
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor(INK))
    }

    /** Body text — ink. */
    fun TextView.bodyText(text: String, size: Float = 14f) {
        this.text = text
        textSize = size
        setTextColor(Color.parseColor(INK))
    }

    /** Secondary text — soft ink. */
    fun TextView.hintText(text: String, size: Float = 13f) {
        this.text = text
        textSize = size
        setTextColor(Color.parseColor(INK_SOFT))
    }

    // ---------- buttons ----------

    /** Primary CTA button — emerald gradient, white text. */
    fun Button.primaryCta(text: String) {
        this.text = text
        textSize = 15f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = context.emeraldBtnBg()
        val p = context.dp(14)
        setPadding(p, context.dp(12), p, context.dp(12))
        try { elevation = context.dp(3).toFloat() } catch (_: Exception) { }
    }

    /** Gold premium button. */
    fun Button.goldCta(text: String) {
        this.text = text
        textSize = 15f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor(INK))
        background = context.goldGradient()
        val p = context.dp(14)
        setPadding(p, context.dp(12), p, context.dp(12))
        try { elevation = context.dp(3).toFloat() } catch (_: Exception) { }
    }

    /** Soft outline button — cream bg, emerald border+text. */
    fun Button.softOutline(text: String) {
        this.text = text
        textSize = 14f
        setTextColor(Color.parseColor(EMERALD_DEEP))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(CARD))
            setStroke(context.dp(1), Color.parseColor(EMERALD))
            cornerRadius = context.dp(14).toFloat()
        }
        val p = context.dp(12)
        setPadding(p, context.dp(10), p, context.dp(10))
    }

    // ---------- header builder ----------

    /**
     * Rich screen header: emerald gradient + gold bottom strip + title.
     * Har main tab ke upar lagao — ek jaisa premium look.
     */
    fun Context.richHeader(title: String, subtitle: String? = null): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = headerGradient()
            val p = dp(16)
            setPadding(p, dp(18), p, dp(14))
            try { elevation = dp(4).toFloat() } catch (_: Exception) { }
        }
        wrap.addView(TextView(this).apply {
            titleOnDark("✨ $title")
        })
        if (!subtitle.isNullOrEmpty()) {
            wrap.addView(TextView(this).apply {
                text = subtitle
                textSize = 13f
                setTextColor(Color.parseColor("#D6E9DF"))
                setPadding(0, dp(4), 0, 0)
            })
        }
        // Gold bottom strip — premium touch.
        wrap.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)
            ).apply { topMargin = dp(12) }
            setBackgroundColor(Color.parseColor(GOLD))
        })
        return wrap
    }

    /** Status badge — chhota pill (success/warning/error/info). */
    fun Context.statusBadge(text: String, kind: String): TextView {
        val (bg, fg) = when (kind) {
            "success" -> SUCCESS_TINT to SUCCESS
            "warning" -> WARNING_TINT to WARNING
            "error" -> ERROR_TINT to ERROR
            else -> INFO_TINT to INFO
        }
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(fg))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bg))
                cornerRadius = dp(10).toFloat()
            }
            val h = dp(6); val w = dp(10)
            setPadding(w, h, w, h)
        }
    }
}

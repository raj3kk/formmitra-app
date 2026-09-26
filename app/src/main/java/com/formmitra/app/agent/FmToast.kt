package com.formmitra.app.agent

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.formmitra.app.agent.FmTheme.dp

/**
 * FmToast — animated toast notifications (poore app me ek jaisa).
 *
 * - Upar se slide-in + fade, thahro, phir slide-out.
 * - Type ke hisaab se icon + rang: ✅ success, ⚠️ warning, ❌ error, ℹ️ info.
 * - Kala bilkul nahi — warm dark ink (#2E2A24) on tinted bg.
 *
 * Usage: FmToast.show(activity, "Kaam shuru ho gaya ✅", FmToast.SUCCESS)
 */
object FmToast {

    const val SUCCESS = "success"
    const val WARNING = "warning"
    const val ERROR = "error"
    const val INFO = "info"

    private var currentToast: View? = null

    /**
     * Animated toast dikhao. durationMs = kitni der ruke (default 2600ms).
     * Purana toast ho to use turant hatao (overlap nahi).
     */
    @JvmStatic
    fun show(act: Activity?, message: String, type: String = INFO, durationMs: Long = 2600) {
        if (act == null || act.isFinishing || act.isDestroyed) return
        act.runOnUiThread {
            try {
                dismissCurrent()
                val root = act.findViewById<ViewGroup>(android.R.id.content)
                    ?: return@runOnUiThread
                val (icon, bg, border, fg) = styleFor(type)
                val card = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(bg))
                        setStroke(act.dp(1), Color.parseColor(border))
                        cornerRadius = act.dp(16).toFloat()
                    }
                    val m = act.dp(14)
                    setPadding(m, act.dp(12), m, act.dp(12))
                    try { elevation = act.dp(8).toFloat() } catch (_: Exception) { }
                }
                card.addView(TextView(act).apply {
                    text = icon
                    textSize = 20f
                    setPadding(0, 0, act.dp(10), 0)
                })
                card.addView(TextView(act).apply {
                    text = message
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(fg))
                })
                val params = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = act.dp(56)
                    val side = act.dp(24)
                    leftMargin = side
                    rightMargin = side
                }
                // Container (FrameLayout) taaki toast sabke upar aaye.
                val holder = FrameLayout(act).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    isClickable = false
                    isFocusable = false
                    addView(card, params)
                }
                root.addView(holder)
                currentToast = holder

                // Slide-in + fade (upar se neeche).
                card.translationY = -act.dp(80).toFloat()
                card.alpha = 0f
                val slideIn = ObjectAnimator.ofFloat(card, "translationY", -act.dp(80).toFloat(), 0f).apply {
                    duration = 350
                    interpolator = DecelerateInterpolator()
                }
                val fadeIn = ObjectAnimator.ofFloat(card, "alpha", 0f, 1f).apply {
                    duration = 300
                }
                AnimatorSet().apply {
                    playTogether(slideIn, fadeIn)
                    start()
                }
                // Thahro, phir slide-out.
                card.postDelayed({
                    try {
                        val slideOut = ObjectAnimator.ofFloat(card, "translationY", 0f, -act.dp(60).toFloat()).apply {
                            duration = 300
                            interpolator = AccelerateInterpolator()
                        }
                        val fadeOut = ObjectAnimator.ofFloat(card, "alpha", 1f, 0f).apply {
                            duration = 280
                        }
                        AnimatorSet().apply {
                            playTogether(slideOut, fadeOut)
                            start()
                        }
                        card.postDelayed({
                            try { root.removeView(holder) } catch (_: Exception) { }
                            if (currentToast == holder) currentToast = null
                        }, 320)
                    } catch (_: Exception) {
                        try { root.removeView(holder) } catch (_: Exception) { }
                    }
                }, durationMs)
            } catch (_: Exception) {
                // Fallback: system toast (kabhi crash nahi).
                try {
                    android.widget.Toast.makeText(act, message, android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) { }
            }
        }
    }

    private fun dismissCurrent() {
        try {
            (currentToast?.parent as? ViewGroup)?.removeView(currentToast)
        } catch (_: Exception) { }
        currentToast = null
    }

    private fun styleFor(type: String): Array<String> = when (type) {
        SUCCESS -> arrayOf("✅", FmTheme.SUCCESS_TINT, FmTheme.SUCCESS, FmTheme.SUCCESS)
        WARNING -> arrayOf("⚠️", FmTheme.WARNING_TINT, FmTheme.WARNING, FmTheme.WARNING)
        ERROR -> arrayOf("❌", FmTheme.ERROR_TINT, FmTheme.ERROR, FmTheme.ERROR)
        else -> arrayOf("ℹ️", FmTheme.INFO_TINT, FmTheme.INFO, FmTheme.INFO)
    }

    // Convenience shortcuts
    @JvmStatic fun success(act: Activity?, msg: String) = show(act, msg, SUCCESS)
    @JvmStatic fun warning(act: Activity?, msg: String) = show(act, msg, WARNING)
    @JvmStatic fun error(act: Activity?, msg: String) = show(act, msg, ERROR)
    @JvmStatic fun info(act: Activity?, msg: String) = show(act, msg, INFO)
}

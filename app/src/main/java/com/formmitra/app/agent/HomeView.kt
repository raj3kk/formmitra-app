package com.formmitra.app.agent

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import java.io.File

/**
 * HomeView — Home tab ka native view: services strip + live-browser button
 * + embedded Mitra chat (AgentChatView).
 *
 * Agent chat ab alag "Agent" tab me nahi — Home me fixed hai (user demand).
 * Services (Tracking/Jobs/Scholarships/Resume) dabane par WebView me khulte
 * hain, nav me Home highlight rehta hai; Home dabao → wapas chat.
 */
class HomeView(
    context: Context,
    chatView: AgentChatView,
    private val onOpenService: (String) -> Unit,
    private val onShowMirror: () -> Unit
) : LinearLayout(context) {

    private val liveBtn: Button

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        val pad = dp(12)

        // Services strip
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
                setOnClickListener { onOpenService(path) }
            }
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

        // Mitra chat — baki poori jagah
        chatView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, 0, 1f
        )
        addView(chatView)
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

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}

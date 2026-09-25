package com.formmitra.app.agent

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * HomeView — Home tab ka native view (v24).
 *
 * v24 restructure:
 *  - HATA DIYA (B6/B7): top chips strip (Tracking/Jobs/Scholarships/Resume)
 *    aur Quick links section.
 *  - A4: upar ka content PROPER ScrollView me (weight 1) + neeche Mitra
 *    chat (weight 1) — chhoti screen par bhi sab dikhe, kuch kate nahi.
 *  - B8: category card tap → details-fill popup NAHI → card-first flow
 *    (CardFlow): card select (PIN unlock) ya naya card banao, tab kaam shuru.
 *  - D19: sab named sections — tap → open/fill/read.
 *
 * Sections:
 *  (1) 🎯 Kaam chuno (काम चुनें) — 5 work-category cards
 *  (2) 🔴 Live browser button (automation chal raha ho tabhi)
 *  (3) 💬 Mitra (मित्र) — embedded agent chat (neeche fixed)
 */
class HomeView(
    context: Context,
    chatView: AgentChatView,
    private val onStartCategory: (
        category: String, label: String, prefill: Map<String, String>,
        cardId: String, cardName: String, cardToken: String
    ) -> Unit,
    private val onShowMirror: () -> Unit,
    private val onOpenProfile: () -> Unit,
    /** "Through Agent" chuna — (catKey, catLabel, prefill). */
    private val onAgentCreateCard: (String, String, Map<String, String>) -> Unit
) : LinearLayout(context) {

    private val liveBtn: Button
    private val chat: AgentChatView = chatView

    // v23 FIX yaad rakho: UiKit.dp Context extension hai — with(UiKit)
    // me bare dp(v) mat bulao (self-recursion). Seedha formula.
    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    /** Work-wise category: key = server ko bheja jane wala exact `category` value. */
    private data class WorkCat(
        val key: String,
        val label: String,
        val icon: String,
        val bg: String,
        val border: String,
        val desc: String
    )

    private val workCats = listOf(
        WorkCat(
            "apply_track", "Apply Track", "📝", "#E8F0FE", "#8AB4F8",
            "Form bharna / status track karna"
        ),
        WorkCat(
            "zamin_track", "Zamin Track", "🌾", "#E6F4EA", "#81C995",
            "Khata/khesra zameen track karna"
        ),
        WorkCat(
            "resume_create", "Resume Create", "📄", "#FEF7E0", "#F6C343",
            "Resume banana"
        ),
        WorkCat(
            "job_find", "Job Find", "💼", "#F3E8FD", "#C58AF9",
            "Sarkari/naukri dhoondhna"
        ),
        WorkCat(
            "scholarship", "Scholarship", "🎓", "#FCE8E6", "#F28B82",
            "Scholarship dhoondhna/apply"
        )
    )

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#FAFBFC"))
        val pad = dp(12)

        // ---- upar: scrollable sections ----
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, dp(4), pad, pad)
        }

        // (1) 🎯 Kaam chuno — named section (tap → card-first flow)
        content.addView(with(UiKit) { context.sectionTitle("🎯 Kaam chuno (काम चुनें)") })
        content.addView(TextView(context).apply {
            text = "Pehle apna 🪪 Card chunna hoga — bina card ke kaam shuru nahi hoga."
            textSize = 12f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(dp(2), 0, dp(2), dp(4))
        })
        val grid = GridLayout(context).apply {
            columnCount = 2
        }
        workCats.forEachIndexed { idx, cat ->
            val card = workCard(cat)
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = if (idx == workCats.lastIndex) {
                    GridLayout.spec(GridLayout.UNDEFINED, 2)
                } else {
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(card, lp)
            UiKit.appear(card, delayMs = idx * 60L)
        }
        content.addView(grid)

        // (2) 🔴 Live browser — sirf tab dikhao jab agent ka browser live ho
        liveBtn = Button(context).apply {
            text = "🔴 Live browser dekho (लाइव देखें)"
            textSize = 13f
            visibility = View.GONE
            setOnClickListener { onShowMirror() }
        }
        UiKit.pressFeedback(liveBtn)
        content.addView(
            liveBtn,
            LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, dp(4)) }
        )

        // (3) 💬 Mitra section header — tap → chat par focus
        val mitraHead = LinearLayout(context).apply {
            orientation = VERTICAL
            background = with(UiKit) { context.cardBg() }
            setPadding(pad, dp(10), pad, dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { chat.focusInput() }
            UiKit.pressFeedback(this)
        }
        mitraHead.addView(TextView(context).apply {
            text = "💬 Mitra (मित्र) — neeche chat me likho ya 🎤 bolo"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
        })
        mitraHead.addView(TextView(context).apply {
            text = "Tap karo — likhne par focus hoga"
            textSize = 12f
            setTextColor(Color.parseColor("#80868B"))
        })
        content.addView(
            mitraHead,
            LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, dp(4)) }
        )

        scroll.addView(
            content,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        addView(scroll)

        // ---- neeche: Mitra chat (fixed, hamesha dikhe) ----
        chatView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, 0, 1f
        )
        addView(chatView)
    }

    /** Work-category card: icon + label + desc; tap → CARD-FIRST flow (B8). */
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
            addView(TextView(ctx).apply {
                text = cat.desc
                textSize = 11f
                setTextColor(Color.parseColor("#5F6368"))
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            })
            isClickable = true
            isFocusable = true
            // B8: details-fill popup HATA DIYA — seedha card-first flow.
            setOnClickListener { startCardFirst(cat) }
            UiKit.pressFeedback(this)
        }
    }

    /**
     * B8 + C14: card-first flow. CardFlow handle karta hai:
     * login → cards → selector/PIN ya create-redirect → onReady.
     */
    private fun startCardFirst(cat: WorkCat) {
        val act = context as? Activity ?: return
        CardFlow.startForCategory(
            act,
            cat.key,
            cat.label,
            onLoginNeeded = { onOpenProfile() },
            onAgentCreate = { prefill -> onAgentCreateCard(cat.key, cat.label, prefill) },
            onReady = { prefill, cardId, cardName, cardToken ->
                onStartCategory(
                    cat.key, cat.label, prefill, cardId, cardName, cardToken
                )
            }
        )
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
}

package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
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
 * HomeView — Home tab ka native view (v26).
 *
 * v24 restructure:
 *  - HATA DIYA (B6/B7): top chips strip (Tracking/Jobs/Scholarships/Resume)
 *    aur Quick links section.
 *  - A4: upar ka content PROPER ScrollView me (weight 1) — poori jagah lega.
 *  - B8: category card tap → details-fill popup NAHI → card-first flow
 *    (CardFlow): card select (PIN unlock) ya naya card banao, tab kaam shuru.
 *  - D19: sab named sections — tap → open/fill/read.
 *
 * v26 restructure (user order):
 *  - HATA DIYA: 💬 Mitra embedded chat (neeche fixed chat block + section header).
 *    Agent chat ab dedicated full-screen "💬 Agent" tab (/agent) me hai.
 *    Kaam par click → card-first flow ke baad Agent tab khulta hai.
 *
 * v28 (P1/P3): Home par SIRF 3 category cards — apply / track / resume.
 *  Purane 5 work-cats map hue: apply_track→apply, zamin_track→track(zameen),
 *  resume_create→resume, job_find→track(job), scholarship→track(scholarship).
 *  P2: apply me tracking ka koi zikr nahi. Track card par 8-type picker
 *  (P3) — type chuno → card-first flow → Track agent us type ke context me.
 *
 * Sections:
 *  (1) 🎯 Kaam chuno (काम चुनें) — 3 category cards
 *  (2) 🔴 Live browser button (automation chal raha ho tabhi)
 */
class HomeView(
    context: Context,
    private val onStartCategory: (
        category: String, label: String, prefill: Map<String, String>,
        cardId: String, cardName: String, cardToken: String,
        trackingType: String?
    ) -> Unit,
    private val onShowMirror: () -> Unit,
    private val onOpenProfile: () -> Unit,
    /** "Through Agent" chuna — (catKey, catLabel, prefill). */
    private val onAgentCreateCard: (String, String, Map<String, String>) -> Unit
) : LinearLayout(context) {

    private val liveBtn: Button

    // v23 FIX yaad rakho: UiKit.dp Context extension hai — with(UiKit)
    // me bare dp(v) mat bulao (self-recursion). Seedha formula.
    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    // v28 P12: fail-soft toast helper (HomeView me pehle koi toast nahi tha).
    private fun toast(msg: String) {
        try {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { }
    }

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
        // v28 (P1): SIRF 3 category cards — WorkCategories.ALL (ek jagah defined).
        val cats = WorkCategories.ALL
        cats.forEachIndexed { idx, cat ->
            val card = workCard(cat)
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = if (idx == cats.lastIndex && cats.size % 2 == 1) {
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

        scroll.addView(
            content,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        addView(scroll)
    }

    /** Work-category card: icon + label + desc; tap → CARD-FIRST flow (B8).
     * v28 (P1): WorkCategories.Cat; (P3) track card par pehle 8 track-types
     * ka picker — type chuno, phir card-first flow, phir Track agent us type
     * ke context me khulta hai. */
    private fun workCard(cat: WorkCategories.Cat): LinearLayout {
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
            setOnClickListener {
                if (cat.key == "track") showTrackTypePicker(cat)
                else startCardFirst(cat, null)
            }
            UiKit.pressFeedback(this)
        }
    }

    /** P3: Track ke andar 8 types — pehle type chuno, phir card-first flow. */
    private fun showTrackTypePicker(cat: WorkCategories.Cat) {
        // v28 P12: dialog creation fail-soft (dead activity → BadToken).
        try {
        val dlg = AlertDialog.Builder(context).create()
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        box.addView(TextView(context).apply {
            text = "🔍 Track (ट्रैकिंग) — kaunsa type?"
            textSize = 16f
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, 0, 0, dp(10))
        })
        for (tt in WorkCategories.TRACK_TYPES) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(TextView(context).apply {
                    text = tt.icon
                    textSize = 22f
                    setPadding(0, 0, dp(10), 0)
                })
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(TextView(context).apply {
                        text = tt.label
                        textSize = 14f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#202124"))
                    })
                    addView(TextView(context).apply {
                        text = tt.hint
                        textSize = 11f
                        setTextColor(Color.parseColor("#5F6368"))
                    })
                })
                isClickable = true
                isFocusable = true
                background = with(UiKit) { context.tintCard("#FFFFFF", "#DADCE0") }
                setOnClickListener {
                    dlg.dismiss()
                    startCardFirst(cat, tt.key)
                }
                UiKit.pressFeedback(this)
            }
            box.addView(row.apply {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, dp(4), 0, dp(4))
                layoutParams = lp
            })
        }
        box.addView(Button(context).apply {
            text = "← Peeche (वापस)"
            minimumWidth = 0
            setOnClickListener { dlg.dismiss() }
        })
        dlg.setView(box)
        dlg.show()
        } catch (t: Throwable) {
            android.util.Log.e("FmHome", "showTrackTypePicker failed", t)
            try {
                com.formmitra.app.engine.ErrorCatcher.show(
                    context, "Kaam khulne me", t,
                    sessionId = "home" + System.currentTimeMillis().toString(36)
                )
            } catch (_: Exception) {
                toast("⚠️ Kaam khulne me dikkat aayi — dobara try karo")
            }
        }
    }

    /**
     * B8 + C14: card-first flow. CardFlow handle karta hai:
     * login → cards → selector/PIN ya create-redirect → onReady.
     * v28 (P3): trackingType sirf track category ke saath thread hota hai —
     * onStartCategory → MainActivity.startCategoryWork → Agent tab ko
     * `tracking_type` context milta hai.
     */
    private fun startCardFirst(cat: WorkCategories.Cat, trackingType: String?) {
        // v28 P12: card tap par koi crash nahi — fail-soft toast.
        try {
        val act = context as? Activity ?: return
        CardFlow.startForCategory(
            act,
            cat.key,
            cat.label,
            onLoginNeeded = { onOpenProfile() },
            onAgentCreate = { prefill -> onAgentCreateCard(cat.key, cat.label, prefill) },
            onReady = { prefill, cardId, cardName, cardToken ->
                try {
                    onStartCategory(
                        cat.key, cat.label, prefill, cardId, cardName, cardToken,
                        trackingType
                    )
                } catch (t: Throwable) {
                    android.util.Log.e("FmHome", "onStartCategory failed", t)
                    try {
                        com.formmitra.app.engine.ErrorCatcher.show(
                            context, "Kaam khulne me", t,
                            workName = cat.label,
                            sessionId = "home" + System.currentTimeMillis().toString(36)
                        )
                    } catch (_: Exception) {
                        toast("⚠️ Kaam khulne me dikkat aayi — dobara try karo")
                    }
                }
            }
        )
        } catch (t: Throwable) {
            android.util.Log.e("FmHome", "startCardFirst failed", t)
            try {
                com.formmitra.app.engine.ErrorCatcher.show(
                    context, "Kaam khulne me", t,
                    workName = cat.label,
                    sessionId = "home" + System.currentTimeMillis().toString(36)
                )
            } catch (_: Exception) {
                toast("⚠️ Kaam khulne me dikkat aayi — dobara try karo")
            }
        }
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

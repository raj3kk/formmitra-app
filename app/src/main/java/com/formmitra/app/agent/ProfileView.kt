package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * ProfileView (v24) — Profile tab.
 *
 * v24: sirf 3 cheezein (user order):
 *  (1) 🪪 FormMitra Cards — list + Naya Card; tap → PIN unlock →
 *      CardDetailView (Details edit+save, Document Vault andar).
 *      Edit Profile + A-Z form HATA DIYE — card creation flow me MERGE.
 *      Standalone Document Vault/Details HATA DIYA — sab card ke andar.
 *  (2) 👤 Profile Detail (पंजीकरण विवरण) — registration wali: name, mobile,
 *      state. READ-ONLY, kisi kaam me use NAHI hoti (sirf pehchan).
 *  (3) 🔊 Talking Voice (बोलने वाली आवाज़) — English/Hindi × Male/Female
 *      (server jaisa).
 *  (4) ⚙️ Account (खाता) — operational cheezein jo hatayi nahi ja sakti:
 *      Working Mode, notifications, inbox, saved logins, admin, logout.
 *      (Profile DATA nahi — isliye alag section.)
 *
 *  Sab labels English (Hindi) bilingual (D17). Har delete par confirmation
 *  popup (D18). Sab kuch named sections me (D19).
 */
class ProfileView(
    context: Context,
    private val ownerEmail: String,
    private val onOwnerConfirmed: () -> Unit,
    private val onOpenAdmin: () -> Unit,
    private val onLogout: () -> Unit
) : LinearLayout(context) {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        try { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
        catch (_: Exception) { }
    }

    private val mainScroll = ScrollView(context)
    private val content = LinearLayout(context)

    // (1) cards
    private val cardsList = LinearLayout(context)
    private val cardsCount = TextView(context)
    private var cachedCards = mutableListOf<JSONObject>()
    private var owner = false

    // (2) profile detail
    private val profileText = TextView(context)

    // (3) voice
    private val voiceLabel = TextView(context)

    // (4) account
    private val adminBtn: Button
    private val inboxBtn: Button
    private val loginsList = LinearLayout(context)

    /** Card khula ho to uska view (back par wapas list). */
    private var cardDetailView: CardDetailView? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#FAFBFC"))
        content.orientation = VERTICAL
        val pad = dp(11)
        content.setPadding(pad, dp(6), pad, pad)

        // ============ (1) 🪪 FormMitra Cards ============
        content.addView(sectionTitle("🪪 FormMitra Cards (फॉर्ममित्र कार्ड)"))
        content.addView(cardsCount.apply {
            textSize = 10f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, 0, 0, dp(3))
        })
        val cardBtnRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }
        val newCardBtn = Button(context).apply {
            text = "➕ Naya Card (नया कार्ड)"
            textSize = 12f
            setOnClickListener { onNewCardClicked() }
        }
        UiKit.pressFeedback(newCardBtn)
        cardBtnRow.addView(newCardBtn.apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, dp(4), 0) }
        })
        val refreshBtn = Button(context).apply {
            text = "🔄"
            textSize = 12f
            setOnClickListener { loadCards() }
        }
        UiKit.pressFeedback(refreshBtn)
        cardBtnRow.addView(refreshBtn.apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), 0, 0, 0) }
        })
        content.addView(cardBtnRow)
        cardsList.orientation = VERTICAL
        content.addView(cardsList.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, 0) }
        })

        // ============ (2) 👤 Profile Detail (read-only) ============
        content.addView(sectionTitle("👤 Profile Detail (पंजीकरण विवरण)"))
        content.addView(TextView(context).apply {
            text = "Ye sirf registration ki pehchan hai — kisi kaam me use NAHI hoti. " +
                "Kaam ke liye hamesha 🪪 Card ka data use hota hai."
            textSize = 10f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, 0, 0, dp(3))
        })
        profileText.apply {
            textSize = 12f
            setTextColor(Color.parseColor("#202124"))
            background = with(UiKit) { context.cardBg() }
            setPadding(dp(9), dp(8), dp(9), dp(8))
        }
        content.addView(profileText)

        // ============ (3) 🔊 Talking Voice ============
        content.addView(sectionTitle("🔊 Talking Voice (बोलने वाली आवाज़)"))
        content.addView(TextView(context).apply {
            text = "Agent kis awaaz me bole (English/Hindi × Male/Female)."
            textSize = 10f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, 0, 0, dp(3))
        })
        voiceLabel.apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, 0, 0, dp(3))
        }
        content.addView(voiceLabel)
        val voiceBtn = Button(context).apply {
            text = "🎙️ Awaaz chuno (आवाज़ चुनें)"
            textSize = 12f
            setOnClickListener { showVoicePicker() }
        }
        UiKit.pressFeedback(voiceBtn)
        content.addView(voiceBtn)
        val voiceTestBtn = Button(context).apply {
            text = "🔊 Suno — awaaz parkho (परखें)"
            textSize = 11f
            setOnClickListener {
                VoiceOutput.init(context)
                VoiceOutput.speak(
                    context,
                    if (VoiceOutput.voiceLang(context) == "en")
                        "Hello! I am your Mitra. This is how I will sound."
                    else "Namaste! Main aapka Mitra hun. Aise bolunga main."
                )
            }
        }
        UiKit.pressFeedback(voiceTestBtn)
        content.addView(voiceTestBtn.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, 0) }
        })
        refreshVoiceLabel()

        // ============ (4) ⚙️ Account ============
        content.addView(sectionTitle("⚙️ Account (खाता)"))
        // Working Mode (automation stack — hataya nahi ja sakta)
        val wmRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = with(UiKit) { context.cardBg() }
            setPadding(dp(9), dp(6), dp(9), dp(6))
        }
        wmRow.addView(TextView(context).apply {
            text = "🤖 Working Mode (कार्य मोड)\nON: tez background automation • OFF: sirf notification"
            textSize = 11f
            setTextColor(Color.parseColor("#202124"))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        val wmSwitch = Switch(context).apply {
            isChecked = try { WorkingMode.isEnabled(context) } catch (_: Exception) { false }
            setOnCheckedChangeListener { _, on ->
                try {
                    WorkingMode.setEnabled(context, on)
                    toast(if (on) "Working Mode ON ✅" else "Working Mode OFF ⏸️")
                } catch (_: Exception) { }
            }
        }
        wmRow.addView(wmSwitch)
        content.addView(wmRow.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(4)) }
        })
        // Agent voice master toggle
        val voiceRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = with(UiKit) { context.cardBg() }
            setPadding(dp(9), dp(6), dp(9), dp(6))
        }
        voiceRow.addView(TextView(context).apply {
            text = "🔊 Agent ki awaaz (एजेंट की आवाज़)"
            textSize = 11f
            setTextColor(Color.parseColor("#202124"))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        val voiceSwitch = Switch(context).apply {
            isChecked = VoiceOutput.isEnabled(context)
            setOnCheckedChangeListener { _, on ->
                VoiceOutput.setEnabled(context, on)
                toast(if (on) "Awaaz ON 🔊" else "Awaaz OFF 🔇")
            }
        }
        voiceRow.addView(voiceSwitch)
        content.addView(voiceRow.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(4)) }
        })
        // Notifications
        val notifBtn = Button(context).apply {
            text = "🔔 Notifications (सूचनाएं)"
            textSize = 12f
            setOnClickListener { showNotifSettings() }
        }
        UiKit.pressFeedback(notifBtn)
        content.addView(notifBtn)
        // Inbox
        inboxBtn = Button(context).apply {
            text = "📥 Notification Inbox (इनबॉक्स)"
            textSize = 12f
            setOnClickListener {
                NotifInboxView.show(context) { refreshInboxBtn() }
            }
        }
        UiKit.pressFeedback(inboxBtn)
        content.addView(inboxBtn.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, 0) }
        })
        // Saved logins (automation)
        content.addView(TextView(context).apply {
            text = "🔑 Saved Logins (सहेजे लॉगिन) — automation ke liye"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, dp(8), 0, dp(3))
        })
        loginsList.orientation = VERTICAL
        content.addView(loginsList)
        // Admin (owner only)
        adminBtn = Button(context).apply {
            text = "🔐 Admin"
            textSize = 12f
            visibility = View.GONE
            setOnClickListener { onOpenAdmin() }
        }
        content.addView(adminBtn.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, 0) }
        })
        // Logout
        val logoutBtn = Button(context).apply {
            text = "🚪 Logout (लॉगआउट)"
            textSize = 12f
            setTextColor(Color.parseColor("#C5221F"))
            setOnClickListener { confirmLogout() }
        }
        UiKit.pressFeedback(logoutBtn)
        content.addView(logoutBtn.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, 0) }
        })

        mainScroll.addView(
            content,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            mainScroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    // ============ tab lifecycle ============

    fun onTabShown() {
        // Card detail khula ho to wahi rahe.
        if (cardDetailView != null) return
        loadCards()
        loadProfileDetail()
        refreshInboxBtn()
        refreshLogins()
        checkOwner()
        refreshVoiceLabel()
    }

    /** Logout ke baad cached state saaf. */
    fun onLoggedOut() {
        cachedCards.clear()
        CardStore.setSelectedCardId(context, null)
        post {
            renderCards()
            profileText.text = "⚠️ Login nahi hai — website me login karke dobara kholo."
            refreshLogins()
        }
    }

    fun setOwner(b: Boolean) {
        owner = b
        post { adminBtn.visibility = if (b) View.VISIBLE else View.GONE }
    }

    /** MainActivity.onActivityResult se forward (card doc picker). */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        try {
            cardDetailView?.handleActivityResult(requestCode, resultCode, data)
                ?: false
        } catch (_: Exception) { false }

    // ============ (1) cards ============

    private fun loadCards() {
        cardsCount.text = "La raha hun…"
        Thread({
            val res = try { AgentApi.cards(context) }
            catch (_: Exception) { AgentApi.ApiResult(-1, null) }
            post {
                when {
                    res.code == 401 -> {
                        cardsCount.text = "🔑 Login nahi hai — pehle login karo"
                        cachedCards.clear()
                        renderCards()
                    }
                    res.code == -1 -> {
                        cardsCount.text = "📡 Internet nahi — cards nahi dikhe"
                    }
                    res.code !in 200..299 -> {
                        cardsCount.text = "⚠️ Cards nahi mile — 🔄 dabakar dobara try karo"
                    }
                    else -> {
                        cachedCards = mutableListOf()
                        val arr = res.json?.optJSONArray("cards")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                arr.optJSONObject(i)?.let { cachedCards.add(it) }
                            }
                        }
                        renderCards()
                    }
                }
            }
        }, "fm-profile-cards").start()
    }

    private fun renderCards() {
        cardsList.removeAllViews()
        val n = cachedCards.size
        cardsCount.text = when {
            n == 0 -> "Koi card nahi — ➕ Naya Card se banao (max 4)"
            else -> "$n card${if (n > 1) "s" else ""} (max 4) — kholne ke liye tap karo 🔒"
        }
        if (n == 0) {
            cardsList.addView(TextView(context).apply {
                text = "🪪 Abhi koi card nahi hai.\nKaam shuru karne se pehle card banana zaroori hai."
                textSize = 11f
                setTextColor(Color.parseColor("#80868B"))
                setPadding(0, dp(3), 0, dp(3))
            })
            return
        }
        val sel = CardStore.selectedCardId(context)
        for (c in cachedCards) {
            val id = c.optString("id")
            val name = c.optString("name", "Card")
            val fid = c.optString("formmitra_id", "")
            val dk = c.optJSONArray("details_keys")?.length() ?: 0
            val dc = c.optInt("docs_count", 0)
            val isSel = id == sel
            cardsList.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = with(UiKit) { context.cardBg() }
                setPadding(dp(9), dp(8), dp(9), dp(8))
                isClickable = true
                isFocusable = true
                addView(TextView(context).apply {
                    text = "🪪"
                    textSize = 20f
                    setPadding(0, 0, dp(6), 0)
                })
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(context).apply {
                        text = name + (if (isSel) "  ✅ (chuna hua)" else "")
                        textSize = 13f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#202124"))
                    })
                    addView(TextView(context).apply {
                        text = "$fid • $dk details • $dc docs"
                        textSize = 10f
                        setTextColor(Color.parseColor("#80868B"))
                    })
                })
                addView(TextView(context).apply {
                    text = "🔒"
                    textSize = 15f
                })
                setOnClickListener { openCard(c) }
                UiKit.pressFeedback(this)
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(4)) }
            })
        }
    }

    private fun onNewCardClicked() {
        val act = context as? Activity ?: return
        if (cachedCards.size >= 4) {
            AlertDialog.Builder(act)
                .setTitle("4 Cards ho gaye")
                .setMessage("Ek user max 4 cards bana sakta hai. Naya chahiye to purana hatao.")
                .setPositiveButton("Samajh gaya", null)
                .show()
            return
        }
        CardFlow.showCreateChooser(
            act,
            emptyMap(),
            onAgentCreate = { prefill -> startAgentCreate(prefill) },
            onCreated = { _, id, name, _ ->
                CardStore.setSelectedCardId(context, id)
                loadCards()
            }
        )
    }

    /** Through Agent — chat kholo card_create mode me (caller: MainActivity). */
    var onAgentCreateRequest: ((prefill: Map<String, String>) -> Unit)? = null

    private fun startAgentCreate(prefill: Map<String, String>) {
        // Through-Agent flow ke dauraan bane naye card ko dhoondhne ke liye
        // current ids note karo.
        agentKnownIds = cachedCards.map { it.optString("id") }.toSet()
        onAgentCreateRequest?.invoke(prefill)
    }

    private var agentKnownIds: Set<String> = emptySet()

    /**
     * Agent se wapas aane par naya card aaya ho to pakdo (best-effort) —
     * phir PIN unlock → kholo.
     */
    fun checkAgentCreatedCard() {
        val act = context as? Activity ?: return
        if (agentKnownIds.isEmpty()) return
        CardFlow.checkNewCardAfterAgent(act, agentKnownIds) { id, name ->
            agentKnownIds = emptySet()
            toast("✅ Naya card mila: $name — PIN se kholo")
            loadCards()
        }
    }

    private fun openCard(card: JSONObject) {
        val act = context as? Activity ?: return
        CardFlow.askPinAndUnlock(
            act, card,
            onUnlocked = { _, id, name, token ->
                val fid = card.optString("formmitra_id", "")
                showCardDetail(id, name, fid, token)
            }
        )
    }

    private fun showCardDetail(
        cardId: String,
        cardName: String,
        formmitraId: String,
        token: String
    ) {
        val detail = CardDetailView(
            context, cardId, cardName, formmitraId, token,
            onBack = { closeCardDetail() },
            onCardDeleted = {
                closeCardDetail()
                loadCards()
            }
        )
        cardDetailView = detail
        removeView(mainScroll)
        addView(
            detail,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    private fun closeCardDetail() {
        cardDetailView?.let { removeView(it) }
        cardDetailView = null
        addView(
            mainScroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        loadCards()
    }

    // ============ (2) profile detail (read-only) ============

    private fun loadProfileDetail() {
        profileText.text = "La raha hun…"
        Thread({
            val p = try { AgentApi.profile(context) } catch (_: Exception) { null }
            post {
                if (p == null) {
                    profileText.text = "⚠️ Profile nahi mila — login karke dobara kholo."
                    return@post
                }
                val name = p.optString("name", p.optString("full_name", "")).trim()
                val mobile = p.optString("mobile", p.optString("phone", "")).trim()
                val state = p.optString("state", "").trim()
                val email = p.optString("email", "").trim()
                val sb = StringBuilder()
                if (name.isNotEmpty()) sb.append("👤 Naam (नाम): ").append(name).append("\n")
                if (mobile.isNotEmpty()) sb.append("📱 Mobile (मोबाइल): ").append(mobile).append("\n")
                if (state.isNotEmpty()) sb.append("📍 State (राज्य): ").append(state).append("\n")
                if (email.isNotEmpty()) sb.append("📧 Email: ").append(email).append("\n")
                if (sb.isEmpty()) {
                    sb.append("Profile khaali hai — website par login complete karo.")
                }
                profileText.text = sb.toString().trim()
                // owner check (fail-closed)
                if (!owner && email.isNotEmpty() &&
                    email.equals(ownerEmail, ignoreCase = true)
                ) {
                    onOwnerConfirmed()
                }
            }
        }, "fm-profile-detail").start()
    }

    private fun checkOwner() {
        // loadProfileDetail me email milne par onOwnerConfirmed call hota hai.
    }

    // ============ (3) talking voice ============

    private fun refreshVoiceLabel() {
        try {
            voiceLabel.text = "Abhi: " + VoiceOutput.voiceLabel(context)
        } catch (_: Exception) { }
    }

    private fun showVoicePicker() {
        val act = context as? Activity ?: return
        val opts = arrayOf(
            "Hindi (हिंदी) × Female (महिला)",
            "Hindi (हिंदी) × Male (पुरुष)",
            "English (अंग्रेज़ी) × Female (महिला)",
            "English (अंग्रेज़ी) × Male (पुरुष)"
        )
        val vals = arrayOf("hi|female", "hi|male", "en|female", "en|male")
        val cur = VoiceOutput.voiceLang(context) + "|" + VoiceOutput.voiceGender(context)
        var checked = vals.indexOf(cur).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(act)
            .setTitle("🔊 Talking Voice chuno (आवाज़ चुनें)")
            .setSingleChoiceItems(opts, checked) { _, w -> checked = w }
            .setPositiveButton("✅ Lagao") { d, _ ->
                d.dismiss()
                val parts = vals[checked].split("|")
                VoiceOutput.setVoicePref(context, parts[0], parts[1])
                VoiceOutput.init(context)
                refreshVoiceLabel()
                toast("✓ Awaaz set: ${opts[checked]}")
                // turant parakh
                VoiceOutput.speak(
                    context,
                    if (parts[0] == "en") "This is my new voice."
                    else "Ye meri nayi awaaz hai."
                )
            }
            .setNegativeButton("Radd karo", null)
            .show()
    }

    // ============ (4) account ============

    private fun showNotifSettings() {
        val act = context as? Activity ?: return
        val cats = try { NotifSettings.categories() } catch (_: Exception) { emptyList() }
        if (cats.isEmpty()) {
            toast("Notification settings uplabdh nahi")
            return
        }
        val layout = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        for ((cat, label) in cats) {
            val row = LinearLayout(act).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(act).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#202124"))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Switch(act).apply {
                isChecked = try { NotifSettings.isEnabled(act, cat) }
                catch (_: Exception) { true }
                setOnCheckedChangeListener { _, on ->
                    try { NotifSettings.setEnabled(act, cat, on) }
                    catch (_: Exception) { }
                }
            })
            layout.addView(row)
        }
        AlertDialog.Builder(act)
            .setTitle("🔔 Notifications (सूचनाएं)")
            .setView(ScrollView(act).apply { addView(layout) })
            .setPositiveButton("Ho gaya", null)
            .show()
    }

    private fun refreshInboxBtn() {
        try {
            val n = NotifStore.unreadCount(context)
            post {
                inboxBtn.text = if (n > 0) "📥 Notification Inbox (इनबॉक्स) — $n nayi"
                else "📥 Notification Inbox (इनबॉक्स)"
            }
        } catch (_: Exception) { }
    }

    private fun refreshLogins() {
        loginsList.removeAllViews()
        val domains = try { SiteCredentialStore.domains(context) }
        catch (_: Exception) { emptyList<String>() }
        if (domains.isEmpty()) {
            loginsList.addView(TextView(context).apply {
                text = "Koi saved login nahi (सहेजे लॉगिन नहीं)"
                textSize = 10f
                setTextColor(Color.parseColor("#80868B"))
            })
            return
        }
        for (d in domains) {
            loginsList.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = with(UiKit) { context.cardBg() }
                setPadding(dp(9), dp(6), dp(9), dp(6))
                addView(TextView(context).apply {
                    text = "🔑 $d"
                    textSize = 11f
                    setTextColor(Color.parseColor("#202124"))
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(Button(context).apply {
                    text = "🗑️"
                    textSize = 11f
                    setOnClickListener { confirmDeleteLogin(d) }
                })
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(4)) }
            })
        }
    }

    /** D18: saved login delete par confirmation. */
    private fun confirmDeleteLogin(domain: String) {
        val act = context as? Activity ?: return
        AlertDialog.Builder(act)
            .setTitle("🗑️ Login hatao? (हटाएं?)")
            .setMessage(
                "\"$domain\" ka saved login hat jayega.\n" +
                    "Agli baar automation par tumhe khud login karna padega."
            )
            .setPositiveButton("🗑️ Hatao (हटाएं)") { d, _ ->
                d.dismiss()
                try {
                    SiteCredentialStore.clear(context, domain)
                    toast("✓ Hata diya")
                } catch (_: Exception) {
                    toast("⚠️ Hataya nahi gaya")
                }
                refreshLogins()
            }
            .setNegativeButton("Rakho (रखें)", null)
            .show()
    }

    /** D18: logout par confirmation. */
    private fun confirmLogout() {
        val act = context as? Activity ?: return
        AlertDialog.Builder(act)
            .setTitle("🚪 Logout? (लॉगआउट?)")
            .setMessage("Website se logout ho jayega. Cards surakshit rahenge.")
            .setPositiveButton("🚪 Haan, logout") { d, _ ->
                d.dismiss()
                onLogout()
            }
            .setNegativeButton("Rehne do", null)
            .show()
    }

    // ============ helpers ============

    private fun sectionTitle(t: String): TextView =
        TextView(context).apply {
            text = t
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A73E8"))
            setPadding(0, dp(9), 0, dp(4))
        }
}

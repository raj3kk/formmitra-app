package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.formmitra.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * ProfileView (v19) — Profile tab ka NATIVE view (website WebView hata diya).
 *
 * Sections:
 *  (a) My Details card — GET /api/agent/profile se (AgentApi.profile).
 *  (b) Edit Profile button → form (naam, phone, email, DOB, pincode, address)
 *      → confirm dialog (verify-before-save spirit) → PUT /api/agent/profile.
 *  (c) Document Vault — DocsStore (encrypted, device-local): list, Add
 *      (file picker, koi bhi type), Remove (confirm ke saath). Har doc ke
 *      saath hint: "agent tasks me auto-available rahega".
 *      DOC-PRIVACY: filename sirf device par dikhta hai — server ko kabhi
 *      naam nahi bheja jata (PromptDialog/FormEngine bhi sirf local name
 *      istemal karte hain; upload me content jata hai, naam list nahi).
 *  (d) Admin entry — SIRF ownerEmail wale user ko (fail-closed).
 *  (e) Logout — session cookies clear.
 *
 * Wallet tab website WebView me hi rehta hai (wo theek hai).
 */
class ProfileView(
    context: Context,
    private val ownerEmail: String,
    private val onOwnerConfirmed: () -> Unit,
    private val onOpenAdmin: () -> Unit,
    private val onLogout: () -> Unit
) : LinearLayout(context) {

    companion object {
        /** MainActivity.onActivityResult se forward hota hai. */
        const val REQ_VAULT_PICK = 2001
    }

    private val scroll = ScrollView(context)
    private val content = LinearLayout(context)
    private val detailsText: TextView
    private val editBtn: Button
    private val vaultList: LinearLayout
    private val vaultSection = LinearLayout(context)
    private val adminBtn: Button
    private val logoutBtn: Button
    private lateinit var inboxBtn: Button
    private var cachedProfile = linkedMapOf<String, String>()
    private var owner = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        val pad = dp(14)
        content.orientation = VERTICAL
        content.setPadding(pad, pad, pad, pad * 2)

        // Header
        content.addView(TextView(context).apply {
            text = "👤 Profile"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, 0, 0, dp(8))
        })

        // (a) My Details card
        content.addView(sectionTitle("📋 My Details"))
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = cardBg()
            setPadding(pad, dp(10), pad, dp(10))
        }
        detailsText = TextView(context).apply {
            text = "Details la raha hun…"
            textSize = 15f
            setTextColor(Color.parseColor("#202124"))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        card.addView(detailsText)
        content.addView(card)
        editBtn = Button(context).apply {
            text = "✏️ Edit Profile"
            textSize = 14f
            setOnClickListener { showEditDialog() }
        }
        content.addView(
            editBtn,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, 0) }
        )
        // (b2) v20 Task 4: A-to-Z vault profile form (sab optional)
        val fullFormBtn = Button(context).apply {
            text = "📝 Poora Profile Form (A–Z)"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = with(UiKit) { context.primaryBtnBg() }
            setOnClickListener { showFullProfileForm() }
        }
        UiKit.pressFeedback(fullFormBtn)
        content.addView(
            fullFormBtn,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, 0) }
        )

        // (b3) G1: Working Mode toggle — background automation ON/OFF.
        // ON: WorkManager (form-tasks 30-min, digest 6h) + NetWake active;
        //     FCM push / network-wake / app-open par WakeWorker resume karega.
        // OFF: workers cancel, wake callback hatao (chalta run poora hoga).
        content.addView(sectionTitle("⚙️ Working Mode"))
        val wmCard = LinearLayout(context).apply {
            orientation = VERTICAL
            background = cardBg()
            setPadding(pad, dp(10), pad, dp(10))
        }
        val wmRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val wmLabel = TextView(context).apply {
            text = "🔄 Background Automation"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val wmSwitch = android.widget.Switch(context).apply {
            isChecked = WorkingMode.isEnabled(context)
        }
        wmRow.addView(wmLabel)
        wmRow.addView(wmSwitch)
        wmCard.addView(wmRow)
        val wmDesc = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#5F6368"))
            setPadding(0, dp(6), 0, 0)
        }
        fun wmDescText(on: Boolean) = if (on)
            "ON — app band hone par bhi kaam chalta rahega: push/network/app-open par " +
                "wake + usi step se resume. Battery-friendly intervals."
        else
            "OFF — background automation band. Naya background kaam shuru nahi hoga " +
                "(jo run chal raha hai wo poora hokar rukega)."
        wmDesc.text = wmDescText(wmSwitch.isChecked)
        wmCard.addView(wmDesc)
        wmSwitch.setOnCheckedChangeListener { _, on ->
            WorkingMode.setEnabled(context, on)
            wmDesc.text = wmDescText(on)
            android.widget.Toast.makeText(
                context,
                if (on) "Working Mode ON ✅" else "Working Mode OFF",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            // G6: ON karte hi battery-optimization guide (ek baar, force nahi).
            if (on) promptBatteryGuide()
        }
        content.addView(
            wmCard,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(4), 0, 0) }
        )

        // (b4) K5: Notification settings — kaunsi categories ON.
        // OFF category ki notification bilkul nahi aati (na shade, na inbox).
        content.addView(sectionTitle("🔔 Notifications"))
        val notifCard = LinearLayout(context).apply {
            orientation = VERTICAL
            background = cardBg()
            setPadding(pad, dp(10), pad, dp(10))
        }
        val notifSwitches = ArrayList<android.widget.Switch>()
        for ((cat, label) in NotifSettings.categories()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(Color.parseColor("#202124"))
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })
            val sw = android.widget.Switch(context).apply {
                isChecked = NotifSettings.isEnabled(context, cat)
                setOnCheckedChangeListener { _, on ->
                    NotifSettings.setEnabled(context, cat, on)
                    toast(if (on) "✓ $label ON" else "$label OFF")
                }
            }
            notifSwitches.add(sw)
            row.addView(sw)
            notifCard.addView(row)
        }
        // L4: agent ki awaaz (TTS) on/off — mute par bhi text announcements
        // (notifications/inbox) hamesha dikhte hain.
        run {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(context).apply {
                text = "🔊 Agent ki awaaz (voice)"
                textSize = 14f
                setTextColor(Color.parseColor("#202124"))
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })
            val sw = android.widget.Switch(context).apply {
                isChecked = VoiceOutput.isEnabled(context)
                setOnCheckedChangeListener { _, on ->
                    VoiceOutput.setEnabled(context, on)
                    toast(if (on) "✓ Agent ki awaaz ON" else "Agent ki awaaz OFF — text rahega")
                }
            }
            row.addView(sw)
            notifCard.addView(row)
        }
        content.addView(
            notifCard,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(4), 0, 0) }
        )
        // (b5) K5: Notification inbox — purani notifications ka tray.
        inboxBtn = Button(context).apply {
            text = "📥 Notification Inbox"
            textSize = 14f
            setOnClickListener {
                NotifInboxView.show(context) { refreshInboxBtn() }
            }
        }
        UiKit.pressFeedback(inboxBtn)
        content.addView(
            inboxBtn,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, 0) }
        )

        // (c) Document Vault
        vaultSection.orientation = VERTICAL
        vaultSection.addView(sectionTitle("📁 Document Vault"))
        vaultSection.addView(TextView(context).apply {
            text = "Vault ke docs agent tasks me auto-available rehte hain.\n" +
                "Filenames sirf is phone par rehte hain — server ko kabhi nahi bheje jate."
            textSize = 12f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, 0, 0, dp(6))
        })
        vaultList = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        vaultSection.addView(vaultList)
        vaultSection.addView(Button(context).apply {
            text = "➕ Add Document"
            textSize = 14f
            setOnClickListener { pickDoc() }
        })
        content.addView(vaultSection)

        // (c2) L1-UPGRADE: Saved logins — site credentials (device-encrypted).
        // Yahan se dekh/saaf kar sakte ho. Delete par confirm (L3c).
        val loginSection = LinearLayout(context).apply { orientation = VERTICAL }
        loginSection.addView(sectionTitle("🔑 Saved Logins"))
        loginSection.addView(TextView(context).apply {
            text = "In sites par agent apne aap login karta hai. " +
                "Credentials sirf is phone me encrypted hain."
            textSize = 12f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, 0, 0, dp(6))
        })
        val loginList = LinearLayout(context).apply { orientation = VERTICAL }
        loginSection.addView(loginList)
        fun refreshLogins() {
            loginList.removeAllViews()
            val domains = try { SiteCredentialStore.domains(context) }
            catch (_: Exception) { emptyList() }
            if (domains.isEmpty()) {
                loginList.addView(TextView(context).apply {
                    text = "Koi saved login nahi"
                    textSize = 13f
                    setTextColor(Color.parseColor("#80868B"))
                })
            }
            for (d in domains) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                row.addView(TextView(context).apply {
                    text = "🌐 $d"
                    textSize = 14f
                    setTextColor(Color.parseColor("#202124"))
                    layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(context).apply {
                    text = "🗑️"
                    textSize = 13f
                    setOnClickListener {
                        val act = context as? Activity ?: return@setOnClickListener
                        AlertDialog.Builder(act)
                            .setTitle("Login hatao?")
                            .setMessage("$d ka saved login hata diya jayega — " +
                                "agli baar manually dena hoga.")
                            .setPositiveButton("🗑️ Hatao") { dd, _ ->
                                try { SiteCredentialStore.clear(context, d) }
                                catch (_: Exception) { }
                                toast("Login hata diya: $d")
                                refreshLogins()
                                dd.dismiss()
                            }
                            .setNegativeButton("Rehne do", null)
                            .show()
                    }
                })
                loginList.addView(row)
            }
        }
        refreshLogins()
        content.addView(
            loginSection,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(12), 0, 0) }
        )

        // (d) Admin entry — owner ko hi dikhega
        adminBtn = Button(context).apply {
            text = "🔧 Admin Panel"
            textSize = 14f
            visibility = View.GONE
            setOnClickListener { onOpenAdmin() }
        }
        content.addView(
            adminBtn,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(16), 0, 0) }
        )

        // (e) Logout
        logoutBtn = Button(context).apply {
            text = "🚪 Logout"
            textSize = 14f
            setOnClickListener { confirmLogout() }
        }
        content.addView(
            logoutBtn,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, 0) }
        )

        scroll.addView(
            content,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            scroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    /** Tab khulne par refresh: details + vault + owner check. */
    fun onTabShown() {
        loadDetails()
        refreshVault()
        checkOwnerHttp()
        refreshInboxBtn()
    }

    /** Inbox button par unread count (badge jaisa). */
    private fun refreshInboxBtn() {
        try {
            val n = NotifStore.unreadCount(context)
            post {
                inboxBtn.text =
                    if (n > 0) "📥 Notification Inbox ($n nayi)" else "📥 Notification Inbox"
            }
        } catch (_: Exception) { }
    }

    /** Home ke "Document Vault" card se — seedha vault section par scroll. */
    fun jumpToVault() {
        post {
            try {
                scroll.smoothScrollTo(0, vaultSection.top)
            } catch (_: Exception) { }
        }
    }

    /** Owner confirm hua (MainActivity ya apne HTTP check se). */
    fun setOwner(b: Boolean) {
        owner = b
        post { adminBtn.visibility = if (b) View.VISIBLE else View.GONE }
    }

    /** Logout ke baad cached state saaf. */
    fun onLoggedOut() {
        cachedProfile.clear()
        post {
            detailsText.text = "⚠️ Login nahi hai — website me login karke dobara kholo."
            refreshVault()
        }
    }

    /** MainActivity.onActivityResult se forward. */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_VAULT_PICK) return
        if (resultCode != Activity.RESULT_OK) return
        val uri = try { data?.data } catch (_: Exception) { null } ?: return
        Thread({
            val saved = try { DocsStore.saveDoc(context, uri) }
            catch (_: Exception) { null }
            post {
                if (!saved.isNullOrEmpty()) {
                    toast("✓ Document save ho gaya")
                    // K1: document ready — vault me doc jod diya (DOC channel).
                    try {
                        NotifCenter.notify(
                            context, NotifCenter.Cat.DOC,
                            "📄 Document vault me jod diya",
                            "Agent tasks me ye document auto-available rahega.",
                            deepTab = "/profile"
                        )
                    } catch (_: Exception) { }
                    // v20 Task 3: "Kaun sa document hai?" — type device-local
                    // save hota hai, server ko kabhi nahi jata (doc-privacy).
                    val act = context as? Activity
                    if (act != null) {
                        UiKit.askDocType(act) { type ->
                            DocsStore.setDocType(context, saved, type)
                            toast("🏷️ $type ke roop me save hua")
                            refreshVault()
                        }
                    } else {
                        refreshVault()
                    }
                } else {
                    toast("⚠️ Save nahi hua — dobara try karo")
                }
            }
        }, "fm-vaultsave").start()
    }

    // ---------- (a) My Details ----------

    private fun loadDetails() {
        detailsText.text = "Details la raha hun…"
        Thread({
            val profile = try { AgentApi.profile(context) }
            catch (_: Exception) { null }
            post { renderDetails(profile) }
        }, "fm-profileload").start()
    }

    private fun renderDetails(p: org.json.JSONObject?) {
        cachedProfile.clear()
        if (p == null) {
            detailsText.text =
                "⚠️ Login nahi hai ya server se dikkat — website me login karke dobara kholo."
            return
        }
        val sb = StringBuilder()
        for (k in DetailExtractor.orderedKeys()) {
            val v = p.optString(k, "").trim()
            if (v.isNotEmpty() && v != "null") {
                cachedProfile[k] = v
                sb.append(DetailExtractor.label(k)).append(": ").append(v).append("\n")
            }
        }
        // unknown extra fields bhi dikhao
        val it = p.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (cachedProfile.containsKey(k) || k == "confirmed") continue
            val v = p.optString(k, "").trim()
            if (v.isNotEmpty() && v != "null") {
                cachedProfile[k] = v
                sb.append(DetailExtractor.label(k)).append(": ").append(v).append("\n")
            }
        }
        detailsText.text = if (sb.isEmpty())
            "Koi details nahi — Edit Profile se jodo."
        else sb.toString().trim()
    }

    // ---------- (b) Edit Profile ----------

    private fun showEditDialog() {
        val act = context as? Activity ?: return
        val layout = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(48, 16, 48, 8)
        }
        val edits = LinkedHashMap<String, EditText>()
        for (k in DetailExtractor.orderedKeys()) {
            layout.addView(TextView(act).apply {
                text = DetailExtractor.label(k)
                textSize = 13f
                setTextColor(Color.parseColor("#80868B"))
                setPadding(0, dp(6), 0, 0)
            })
            val et = EditText(act).apply {
                setText(cachedProfile[k] ?: "")
                textSize = 16f
                setTextColor(Color.parseColor("#202124"))
            }
            layout.addView(et)
            edits[k] = et
        }
        AlertDialog.Builder(act)
            .setTitle("✏️ Edit Profile")
            .setView(ScrollView(act).apply { addView(layout) })
            .setPositiveButton("Save") { dlg, _ ->
                val vals = LinkedHashMap<String, String>()
                edits.forEach { (k, et) ->
                    val v = et.text.toString().trim()
                    if (v.isNotEmpty()) vals[k] = v
                }
                dlg.dismiss()
                showSaveConfirm(act, vals)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Verify-before-save spirit: save se PEHLE values dikhao, Proceed par
     * hi PUT /api/agent/profile jaye (confirmed:true).
     */
    private fun showSaveConfirm(act: Activity, vals: Map<String, String>) {
        if (vals.isEmpty()) {
            toast("Kuch likha hi nahi — save cancel")
            return
        }
        val sb = StringBuilder()
        for ((k, v) in vals) {
            sb.append(DetailExtractor.label(k)).append(": ").append(v).append("\n")
        }
        AlertDialog.Builder(act)
            .setTitle("Confirm karo")
            .setMessage(
                "Ye details save hongi:\n\n${sb.toString().trim()}\n\n" +
                    "Sahi hain to Proceed dabao."
            )
            .setPositiveButton("✅ Sahi hai — save karo") { dlg, _ ->
                dlg.dismiss()
                Thread({
                    val ok = try {
                        AgentApi.saveProfile(context, vals)
                    } catch (_: Exception) { false }
                    post {
                        toast(
                            if (ok) "✓ Details save ho gayi"
                            else "⚠️ Save me dikkat — baad me try karo"
                        )
                        if (ok) loadDetails()
                    }
                }, "fm-profilesave").start()
            }
            .setNegativeButton("❌ Mat karo", null)
            .setCancelable(false)
            .show()
    }

    // ---------- (b2) v20 Task 4: A-to-Z profile form ----------

    /**
     * Poora profile form — EXACT server field keys (spelling mat badalna):
     * full_name, father_name, mother_name, dob, gender, phone, email,
     * village, post, district, state, pincode, address, qualification,
     * occupation, category_caste, id_numbers.
     * Sab OPTIONAL — partial save hota hai. PUT /api/agent/profile par
     * `confirmed` field BHEJA HI NAHI jata (absent = server save karta hai).
     * Yehi details agent document bharne me use karega.
     */
    private fun showFullProfileForm() {
        val act = context as? Activity ?: return
        with(UiKit) {
            val layout = LinearLayout(act).apply {
                orientation = VERTICAL
                setPadding(act.dp(16), act.dp(8), act.dp(16), act.dp(8))
            }
            layout.addView(TextView(act).apply {
                text = "Sab optional hai — jo pata ho bhar do. " +
                    "Yehi details agent document bharne me use karega."
                textSize = 13f
                setTextColor(Color.parseColor("#80868B"))
                setPadding(0, 0, 0, act.dp(6))
            })
            val sections = listOf(
                "👤 Personal" to listOf(
                    "full_name", "father_name", "mother_name", "dob",
                    "gender", "phone", "email"
                ),
                "🏠 Address" to listOf(
                    "village", "post", "district", "state", "pincode", "address"
                ),
                "📚 Other" to listOf(
                    "qualification", "occupation", "category_caste", "id_numbers"
                )
            )
            val edits = LinkedHashMap<String, EditText>()
            for ((secTitle, keys) in sections) {
                layout.addView(act.sectionTitle(secTitle).apply {
                    setPadding(0, act.dp(10), 0, act.dp(2))
                })
                for (k in keys) {
                    layout.addView(act.fieldLabel(DetailExtractor.label(k)))
                    val et = act.formInput(hintFor(k), cachedProfile[k] ?: "")
                    layout.addView(et)
                    edits[k] = et
                }
            }
            val dlg = AlertDialog.Builder(act)
                .setTitle("📝 Poora Profile Form")
                .setView(ScrollView(act).apply { addView(layout) })
                .setPositiveButton("💾 Save karo", null)
                .setNegativeButton("Band karo", null)
                .create()
            dlg.setOnShowListener {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val vals = LinkedHashMap<String, String>()
                    edits.forEach { (k, et) ->
                        val v = et.text.toString().trim()
                        if (v.isNotEmpty()) vals[k] = v
                    }
                    if (vals.isEmpty()) {
                        toast("Kuch bhara hi nahi — save cancel")
                        return@setOnClickListener
                    }
                    dlg.dismiss()
                    saveFullProfileWithRetry(vals)
                }
            }
            dlg.show()
        }
    }

    /**
     * L2: profile save — fail ho to user ko pata chale + retry mile.
     * Vals dialog me hi rehte hain, dobara bharna nahi padta.
     */
    private fun saveFullProfileWithRetry(vals: Map<String, String>) {
        val act = context as? Activity ?: return
        toast("Save ho raha hai…")
        Thread({
            val ok = try {
                AgentApi.saveProfileForm(context, vals)
            } catch (_: Exception) { false }
            post {
                if (ok) {
                    toast("✓ Poora profile save ho gaya")
                    loadDetails()
                } else {
                    AlertDialog.Builder(act)
                        .setTitle("⚠️ Save nahi hua")
                        .setMessage(
                            "Internet ya server me dikkat hai. " +
                                "Tumhari bhari hui details surakshit hain — " +
                                "dobara try karo."
                        )
                        .setPositiveButton("🔁 Dobara try karo") { d, _ ->
                            d.dismiss()
                            saveFullProfileWithRetry(vals)
                        }
                        .setNegativeButton("Band karo", null)
                        .show()
                }
            }
        }, "fm-fullformsave").start()
    }

    private fun hintFor(key: String): String = when (key) {
        "full_name" -> "Apna poora naam"
        "father_name" -> "Pita ka naam"
        "mother_name" -> "Mata ka naam"
        "dob" -> "DD/MM/YYYY"
        "gender" -> "Male / Female / Other"
        "phone" -> "10-digit mobile"
        "email" -> "Email address"
        "village" -> "Gaon"
        "post" -> "Post office"
        "district" -> "Zila"
        "state" -> "Rajya"
        "pincode" -> "6-digit pincode"
        "address" -> "Poora pata"
        "qualification" -> "Padhai (10th/12th/Graduate…)"
        "occupation" -> "Kaam (kisan/mazdoor…)"
        "category_caste" -> "SC / ST / OBC / General"
        "id_numbers" -> "Aadhaar/PAN no. (zaroorat par)"
        else -> ""
    }

    // ---------- (c) Document Vault ----------

    private fun refreshVault() {
        post {
            vaultList.removeAllViews()
            val docs = try { DocsStore.listDocs(context) }
            catch (_: Exception) { emptyList<String>() }
            if (docs.isEmpty()) {
                vaultList.addView(TextView(context).apply {
                    text = "Koi document nahi — Add Document se jodo."
                    textSize = 13f
                    setTextColor(Color.parseColor("#80868B"))
                    setPadding(0, dp(4), 0, dp(4))
                })
                return@post
            }
            for (name in docs) {
                val row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = cardBg()
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
                // v20 Task 3: doc type badge (device-local metadata)
                val dtype = try { DocsStore.getDocType(context, name) }
                catch (_: Exception) { "" }
                val tv = TextView(context).apply {
                    text = "📄 $name" +
                        (if (dtype.isNotEmpty()) "\n🏷️ $dtype" else "") +
                        "\nagent tasks me auto-available rahega"
                    textSize = 13f
                    setTextColor(Color.parseColor("#202124"))
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(tv)
                val del = Button(context).apply {
                    text = "🗑️"
                    textSize = 16f
                    setOnClickListener { confirmRemove(name) }
                }
                row.addView(del)
                vaultList.addView(
                    row,
                    LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, dp(6)) }
                )
                UiKit.appear(row)
            }
        }
    }

    private fun confirmRemove(name: String) {
        val act = context as? Activity ?: return
        AlertDialog.Builder(act)
            .setTitle("Document hatao?")
            .setMessage(
                "\"$name\" vault se hat jayega.\n" +
                    "Agent tasks me ye file phir use nahi hogi."
            )
            .setPositiveButton("🗑️ Hatao") { dlg, _ ->
                dlg.dismiss()
                val ok = try { DocsStore.deleteDoc(context, name) }
                catch (_: Exception) { false }
                toast(if (ok) "✓ Hata diya" else "⚠️ Hataya nahi gaya")
                if (ok) refreshVault()
            }
            .setNegativeButton("Rakho", null)
            .show()
    }

    private fun pickDoc() {
        val act = context as? Activity ?: return
        try {
            act.startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*" // koi bhi type
                },
                REQ_VAULT_PICK
            )
        } catch (_: Exception) {
            toast("Picker nahi khula")
        }
    }

    // ---------- (d) Owner check ----------

    /**
     * Website ka /profile page session cookie ke saath lao — text me
     * ownerEmail dikhe to owner (fail-closed: na dikhe to admin nahi).
     * MainActivity ka purana WebView wala checkOwner bhi rakha hai
     * (defense-in-depth); ye native tab ka apna check hai.
     */
    private fun checkOwnerHttp() {
        if (owner) return
        Thread({
            try {
                val url = URL(BuildConfig.SITE_URL.trimEnd('/') + "/profile")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 20000
                }
                AgentApi.sessionCookie()?.let {
                    conn.setRequestProperty("Cookie", it)
                }
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader()
                        .use { r -> r.readText() }
                    if (text.contains(ownerEmail)) {
                        post { onOwnerConfirmed() }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) { }
        }, "fm-ownercheck").start()
    }

    // ---------- (e) Logout ----------

    private fun confirmLogout() {
        val act = context as? Activity ?: return
        AlertDialog.Builder(act)
            .setTitle("Logout?")
            .setMessage("Website session khatm ho jayega. Phone ka vault data (details/docs) safe rahega.")
            .setPositiveButton("🚪 Logout") { dlg, _ ->
                dlg.dismiss()
                onLogout()
            }
            .setNegativeButton("Raho", null)
            .show()
    }

    /**
     * G6: Battery-optimization guide — Working Mode ON par ek baar.
     * Force nahi karte: samjhate hain + Settings kholne ka button dete hain.
     * (Doze/battery-optimization background workers ko rok sakti hai.)
     */
    private fun promptBatteryGuide() {
        val act = context as? Activity ?: return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm != null && pm.isIgnoringBatteryOptimizations(context.packageName)) return
        } catch (_: Exception) { }
        try {
            val prefs = context.getSharedPreferences("formmitra_working", Context.MODE_PRIVATE)
            if (prefs.getBoolean("battery_guide_shown", false)) return
            prefs.edit().putBoolean("battery_guide_shown", true).apply()
        } catch (_: Exception) { }
        AlertDialog.Builder(act)
            .setTitle("🔋 Background ke liye ek setting")
            .setMessage(
                "Phone ki battery-optimization FormMitra ke background kaam ko rok sakti hai.\n\n" +
                    "\"Settings kholo\" dabao → \"Allow\" / \"Don't optimize\" chuno — " +
                    "uske baad Working Mode poori tarah kaam karega.\n\n" +
                    "(Ye zaroori nahi — bina iske bhi app khulne par kaam resume hoga.)"
            )
            .setPositiveButton("⚙️ Settings kholo") { dlg, _ ->
                dlg.dismiss()
                openBatterySettings()
            }
            .setNegativeButton("Baad me", null)
            .show()
    }

    private fun openBatterySettings() {
        try {
            val pkg = context.packageName
            val intents = listOf(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$pkg")
                ),
                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
            for (i in intents) {
                try {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                    return
                } catch (_: Exception) { }
            }
            toast("Settings → Battery → FormMitra → Don't optimize")
        } catch (_: Exception) {
            toast("Settings → Battery → FormMitra → Don't optimize")
        }
    }

    // ---------- helpers ----------

    private fun sectionTitle(t: String): TextView =
        TextView(context).apply {
            text = t
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, dp(10), 0, dp(6))
        }

    private fun cardBg(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor("#F8F9FA"))
            setStroke(dp(1), Color.parseColor("#DADCE0"))
            cornerRadius = dp(10).toFloat()
        }

    private fun toast(msg: String) {
        try {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT)
                .show()
        } catch (_: Exception) { }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}

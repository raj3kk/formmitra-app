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
                toast(
                    if (!saved.isNullOrEmpty()) "✓ Document save ho gaya"
                    else "⚠️ Save nahi hua — dobara try karo"
                )
                if (!saved.isNullOrEmpty()) refreshVault()
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
                val tv = TextView(context).apply {
                    text = "📄 $name\nagent tasks me auto-available rahega"
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

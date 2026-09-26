package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.formmitra.app.engine.CardUnlockPolicy
import org.json.JSONObject

/**
 * CardFlow (v24) — card-first workflow ka app-side orchestrator.
 *
 *  - Koi kaam (category) shuru karne se pehle card MANDATORY (C14):
 *    startForCategory → login? → cards? → 0 cards to CREATE par redirect →
 *    ≥1 to selector → PIN unlock (30-min token) → pending details flush →
 *    prefill ke saath onReady.
 *  - Card banao — 2 tareeke (C13): Manual (form: validate → toke/samjhaye →
 *    summary → Proceed → POST) ya Through Agent (voice Q&A — server agent
 *    ek-ek karke poochhta hai; app chat kholta hai).
 *  - #4: user ne details di par card nahi — pehle create-card redirect,
 *    PHIR details tag ke saath usi card me save. PendingDetails kabhi
 *    khota nahi (prefs me persist).
 *  - #2 (agent sahi se baat kare): card_create mode ka intro message saaf
 *    Hinglish me; manual form me galat field par tok + samjhaye + phir se
 *    bharwaye (dialog band nahi hota).
 */
object CardFlow {

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun toast(ctx: Context, msg: String) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { }
    }

    // ============ entry: category card tap (C14 card-first) ============

    /**
     * @param onLoginNeeded login nahi hai → caller profile/login khole
     * @param onAgentCreate "Through Agent" chuna → caller agent chat khole
     *                      (card_create mode)
     * @param onReady card unlock ho gaya → prefill + card info ke saath
     *                kaam shuru karo
     */
    fun startForCategory(
        act: Activity,
        catKey: String,
        catLabel: String,
        onLoginNeeded: () -> Unit,
        onAgentCreate: (prefill: Map<String, String>) -> Unit,
        onReady: (prefill: Map<String, String>, cardId: String, cardName: String, cardToken: String) -> Unit
    ) {
        toast(act, "Card la raha hun…")
        Thread({
            val res = try { AgentApi.cards(act) }
            catch (_: Exception) { AgentApi.ApiResult(-1, null) }
            act.runOnUiThread {
                // v28 P12: UI block me koi crash nahi — fail-soft toast.
                try {
                when {
                    res.code == 401 -> {
                        toast(act, "🔑 Pehle login karo — tabhi Card banega")
                        onLoginNeeded()
                    }
                    res.code == -1 -> toast(act, "📡 Internet nahi — Card nahi khula")
                    res.code !in 200..299 -> toast(act, "⚠️ Card list nahi mili — dobara try karo")
                    else -> {
                        val arr = res.json?.optJSONArray("cards")
                        val cards = mutableListOf<JSONObject>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                arr.optJSONObject(i)?.let { cards.add(it) }
                            }
                        }
                        if (cards.isEmpty()) {
                            // #4: card nahi hai → create-card par REDIRECT
                            toast(act, "Pehle apna FormMitra Card banao 🪪")
                            showCreateChooser(
                                act, emptyMap(),
                                onCreated = { prefill, id, name, token ->
                                    onReady(prefill, id, name, token)
                                }
                            )
                        } else {
                            showCardSelector(
                                act, cards, catLabel, onAgentCreate,
                                onPick = { card ->
                                    askPinAndUnlock(
                                        act, card,
                                        onUnlocked = { prefill, id, name, token ->
                                            // Existing card chuna + pending details
                                            // hain → poochhkar save karo (#4).
                                            flushPendingWithAsk(
                                                act, id, token,
                                                auto = false
                                            )
                                            onReady(prefill, id, name, token)
                                        },
                                        onCreateNew = {
                                            showCreateChooser(
                                                act, emptyMap(),
                                                onCreated = { prefill, id, name, token ->
                                                    onReady(prefill, id, name, token)
                                                }
                                            )
                                        }
                                    )
                                },
                                onCreateNew = {
                                    showCreateChooser(
                                        act, emptyMap(),
                                        onCreated = { prefill, id, name, token ->
                                            onReady(prefill, id, name, token)
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
                } catch (t: Throwable) {
                    android.util.Log.e("FmCardFlow", "startForCategory UI failed", t)
                    toast(act, "⚠️ Card khulne me dikkat aayi — dobara try karo")
                }
            }
        }, "fm-cardflow-list").start()
    }

    // ============ card selector ============

    private fun showCardSelector(
        act: Activity,
        cards: List<JSONObject>,
        catLabel: String,
        onAgentCreate: (Map<String, String>) -> Unit,
        onPick: (JSONObject) -> Unit,
        onCreateNew: () -> Unit
    ) {
        val lastSel = CardStore.selectedCardId(act)
        val items = cards.map { c ->
            val name = c.optString("name", "Card")
            val fid = c.optString("formmitra_id", "")
            val dk = c.optJSONArray("details_keys")?.length() ?: 0
            val dc = c.optInt("docs_count", 0)
            "$name — $fid ($dk details, $dc docs)"
        }.toTypedArray()
        var checked = cards.indexOfFirst { it.optString("id") == lastSel }
            .takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(act)
            .setTitle("🪪 Card chuno (कार्ड चुनें) — $catLabel")
            .setSingleChoiceItems(items, checked) { _, which -> checked = which }
            .setPositiveButton("✅ Is card se aage badho") { d, _ ->
                d.dismiss()
                val card = cards.getOrNull(checked) ?: return@setPositiveButton
                CardStore.setSelectedCardId(act, card.optString("id"))
                onPick(card)
            }
            .setNeutralButton("➕ Naya card") { d, _ ->
                d.dismiss()
                onCreateNew()
            }
            .setNegativeButton("Radd karo", null)
            .show()
    }

    // ============ PIN unlock ============

    /** PIN dialog — kahin se bhi card kholne ke liye (list/detail dono). */
    fun askPinAndUnlock(
        act: Activity,
        card: JSONObject,
        onUnlocked: (prefill: Map<String, String>, cardId: String, cardName: String, cardToken: String) -> Unit,
        onCreateNew: (() -> Unit)? = null
    ) {
        val cardId = card.optString("id")
        val cardName = card.optString("name", "Card")
        val fid = card.optString("formmitra_id", "")
        // Token abhi valid ho to PIN dobara mat maango (30-min window).
        CardStore.token(cardId)?.let { tok ->
            fetchDetailAndProceed(act, cardId, cardName, tok, onUnlocked)
            return
        }
        // POINT 24 (revised): 5 galat PIN / 15 min → temporary lock.
        val blockedUntil = CardStore.pinBlockedUntilMs(act, cardId)
        if (blockedUntil > System.currentTimeMillis()) {
            AlertDialog.Builder(act)
                .setTitle("🔒 Card Lock Hai")
                .setMessage(CardUnlockPolicy.pinBlockedText())
                .setPositiveButton("Samajh gaya", null)
                .show()
            return
        }
        val et = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "4–8 ank ka PIN"
        }
        val wrap = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(act, 24), dp(act, 8), dp(act, 24), dp(act, 8))
            addView(TextView(act).apply {
                text = "🪪 $cardName ($fid)\nCard PIN dalo — details/documents khulenge."
                textSize = 14f
                setTextColor(Color.parseColor("#202124"))
                setPadding(0, 0, 0, dp(act, 8))
            })
            addView(et)
        }
        val dlg = AlertDialog.Builder(act)
            .setTitle("🔒 Card Unlock (कार्ड खोलें)")
            .setView(wrap)
            .setPositiveButton("🔓 Kholo", null)
            // POINT 27: PIN bhool gaye? → reset flow (OTP email par).
            .setNeutralButton("PIN bhool gaye?", null)
            .setNegativeButton("Band karo", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dlg.dismiss()
                toast(act, "Email la raha hun…")
                Thread({
                    val email = try {
                        AgentApi.profile(act)?.optString("email", "").orEmpty()
                    } catch (_: Exception) { "" }
                    act.runOnUiThread {
                        try {
                            PinResetFlow.show(act, cardId, cardName, email)
                        } catch (t: Throwable) {
                            android.util.Log.e("FmCardFlow", "pin reset failed", t)
                        }
                    }
                }, "fm-pinreset-email").start()
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = et.text.toString().trim()
                if (!pin.matches(Regex("\\d{4,8}"))) {
                    et.error = "4 se 8 ank ka PIN likho"
                    return@setOnClickListener
                }
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                toast(act, "Unlock ho raha hai…")
                Thread({
                    val res = try { AgentApi.unlockCard(act, cardId, pin) }
                    catch (_: Exception) { AgentApi.ApiResult(-1, null) }
                    act.runOnUiThread {
                        dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        when {
                            res.code == 200 && res.json?.optBoolean("ok") == true -> {
                                val token = res.json.optString("card_token", "")
                                val exp = res.json.optString("expires_at", "")
                                if (token.isEmpty()) {
                                    toast(act, "⚠️ Token nahi mila — dobara try karo")
                                    return@runOnUiThread
                                }
                                // POINT 24 (revised): safal unlock → persistent
                                // unlock ON + token encrypted persist.
                                CardStore.recordPinAttempt(act, cardId, true)
                                CardStore.setUnlocked(act, cardId)
                                CardStore.putToken(act, cardId, token, exp)
                                dlg.dismiss()
                                fetchDetailAndProceed(
                                    act, cardId, cardName, token, onUnlocked
                                )
                            }
                            res.code == 403 -> {
                                // POINT 24: galat PIN gino — 5 par temporary lock.
                                CardStore.recordPinAttempt(act, cardId, false)
                                val b = CardStore.pinBlockedUntilMs(act, cardId)
                                et.error = if (b > System.currentTimeMillis()) {
                                    "🔒 " + CardUnlockPolicy.pinBlockedText()
                                } else {
                                    "❌ Galat PIN — dobara dalo"
                                }
                            }
                            res.code == -1 ->
                                toast(act, "📡 Internet nahi — dobara try karo")
                            else -> toast(act, "⚠️ Unlock nahi hua — dobara try karo")
                        }
                    }
                }, "fm-card-unlock").start()
            }
        }
        dlg.show()
    }

    private fun fetchDetailAndProceed(
        act: Activity,
        cardId: String,
        cardName: String,
        token: String,
        onUnlocked: (Map<String, String>, String, String, String) -> Unit
    ) {
        Thread({
            val res = try { AgentApi.cardDetail(act, cardId, token) }
            catch (_: Exception) { AgentApi.ApiResult(-1, null) }
            val prefill = LinkedHashMap<String, String>()
            if (res.code in 200..299) {
                // v30 ROOT FIX: cardDetail NESTED {card:{details}} bhejta
                // hai — top-level optJSONObject("details") hamesha null
                // deta tha → prefill hamesha khaali. CardJson.detailsOf se.
                val det = CardJson.detailsOf(res.json)
                if (det != null) {
                    val keys = det.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val o = det.optJSONObject(k)
                        val v = o?.optString("value", "")
                            ?: det.optString(k, "")
                        if (!v.isNullOrEmpty() && v != "null") prefill[k] = v
                    }
                }
            }
            act.runOnUiThread { onUnlocked(prefill, cardId, cardName, token) }
        }, "fm-card-detail").start()
    }

    // ============ create: seedha manual form (v41) ============

    /**
     * v41 (user order): "agent choose" option hata diya — Naya Card ab
     * hamesha seedha manual form se banta hai, koi chooser dialog nahi.
     * (Manual form me "Radd karo" hai — cancel ka rasta khula hai.)
     *
     * @param prefillDetails pehle se di hui basic details (card me jayengi)
     * @param onCreated (prefill, cardId, cardName, cardToken)
     */
    fun showCreateChooser(
        act: Activity,
        prefillDetails: Map<String, String>,
        onCreated: (Map<String, String>, String, String, String) -> Unit
    ) {
        showManualForm(act, prefillDetails, onCreated)
    }

    // ============ Manual form (C13: Edit Profile + A-Z MERGED) ============

    private fun showManualForm(
        act: Activity,
        prefill: Map<String, String>,
        onCreated: (Map<String, String>, String, String, String) -> Unit
    ) {
        val ctx = act
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        }
        fun secTitle(t: String) = TextView(ctx).apply {
            text = t
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
        }
        fun fieldLabel(t: String) = TextView(ctx).apply {
            text = t
            textSize = 13f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 2))
        }
        fun input(hint: String, pre: String, numeric: Boolean = false): EditText =
            EditText(ctx).apply {
                this.hint = hint
                setText(pre)
                textSize = 16f
                setTextColor(Color.parseColor("#202124"))
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_VARIATION_PASSWORD
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#F8F9FA"))
                    setStroke(dp(ctx, 1), Color.parseColor("#DADCE0"))
                    cornerRadius = dp(ctx, 10).toFloat()
                }
            }

        layout.addView(secTitle("🪪 Card ki pehchan (पहचान)"))
        layout.addView(fieldLabel("Card ka naam (नाम) *"))
        val nameEt = input("jaise: Mera Card / Papa ka Card", "")
        layout.addView(nameEt)
        layout.addView(fieldLabel("Card PIN (पिन) — 4 se 8 ank *"))
        val pinEt = input("PIN likho", "", numeric = true)
        layout.addView(pinEt)
        layout.addView(fieldLabel("PIN dobara (पुष्टि) *"))
        val pin2Et = input("PIN phir se likho", "", numeric = true)
        layout.addView(pin2Et)

        // Basic details (A-Z merge — sab optional)
        layout.addView(secTitle("📝 Basic Details (बुनियादी विवरण) — optional"))
        layout.addView(TextView(ctx).apply {
            text = "Jo pata ho bhar do — baaki kaam ke dauraan agent le lega " +
                "aur card me save karega."
            textSize = 13f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, 0, 0, dp(ctx, 4))
        })
        val fieldKeys = listOf(
            "full_name", "phone", "dob", "gender",
            "village", "post", "district", "state", "pincode", "address_line",
            "qualification", "occupation", "category_caste", "email", "id_numbers"
        )
        val edits = LinkedHashMap<String, EditText>()
        val errViews = LinkedHashMap<String, TextView>()
        for (k in fieldKeys) {
            layout.addView(fieldLabel(DetailExtractor.label(k)))
            val et = input(hintFor(k), prefill[k] ?: "")
            layout.addView(et)
            edits[k] = et
            val err = TextView(ctx).apply {
                textSize = 12f
                setTextColor(Color.parseColor("#C5221F"))
                visibility = View.GONE
            }
            layout.addView(err)
            errViews[k] = err
        }

        val dlg = AlertDialog.Builder(act)
            .setTitle("🪪 Naya Card banao (Manual)")
            .setView(ScrollView(ctx).apply { addView(layout) })
            .setPositiveButton("Aage ▶ (सारांश देखो)", null)
            .setNegativeButton("Radd karo", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // #2: validate — galat ho to TOKO + SAMJHAO, phir se bharwao
                // (dialog band nahi hota).
                var firstBad: EditText? = null
                val vals = LinkedHashMap<String, String>()
                for ((k, et) in edits) {
                    val raw = et.text.toString().trim()
                    val err = validateField(k, raw)
                    val ev = errViews[k]!!
                    if (err != null && raw.isNotEmpty()) {
                        ev.text = "❌ $err"
                        ev.visibility = View.VISIBLE
                        if (firstBad == null) firstBad = et
                    } else {
                        ev.visibility = View.GONE
                        if (raw.isNotEmpty()) {
                            vals[k] = normalizeField(k, raw)
                        }
                    }
                }
                val name = nameEt.text.toString().trim()
                val pin = pinEt.text.toString().trim()
                val pin2 = pin2Et.text.toString().trim()
                var ok = true
                if (name.isEmpty()) {
                    nameEt.error = "Card ka naam likho (jaise: Mera Card)"
                    ok = false
                }
                if (!pin.matches(Regex("\\d{4,8}"))) {
                    pinEt.error = "4 se 8 ank ka PIN likho"
                    ok = false
                } else if (pin != pin2) {
                    pin2Et.error = "Dono PIN same hone chahiye — dobara milao"
                    ok = false
                }
                if (!ok || firstBad != null) {
                    if (firstBad != null) {
                        toast(ctx, "❌ Kuch fields galat hain — laal me samjhaya hai, theek karke phir Aage dabao")
                        firstBad.requestFocus()
                    }
                    return@setOnClickListener
                }
                dlg.dismiss()
                showCreateSummary(act, name, pin, vals, onCreated)
            }
        }
        dlg.show()
    }

    /**
     * Field validation — CardValidation (pure-Kotlin, self-tested) ko
     * delegate. Galat ho to Hinglish me TOKO + SAMJHAO kahan se sahi
     * bharna hai. @return error string ya null (sahi).
     */
    fun validateField(key: String, raw: String): String? =
        CardValidation.validateField(key, raw)

    private fun normalizeField(key: String, raw: String): String =
        CardValidation.normalizeField(key, raw)

    private fun hintFor(key: String): String = when (key) {
        "full_name" -> "Apna poora naam"
        "phone" -> "10-digit mobile"
        "dob" -> "DD/MM/YYYY"
        "gender" -> "Male / Female / Other"
        "village" -> "Gaon"
        "post" -> "Post office"
        "district" -> "Zila"
        "state" -> "Rajya"
        "pincode" -> "6-digit pincode"
        "address_line" -> "Poora pata"
        "qualification" -> "Padhai (10th/12th/Graduate…)"
        "occupation" -> "Kaam (kisan/mazdoor…)"
        "category_caste" -> "SC / ST / OBC / General"
        "email" -> "Email address"
        "id_numbers" -> "Aadhaar/PAN no. (zaroorat par)"
        else -> ""
    }

    /**
     * A1-style draft flow card creation par: server-validated summary
     * dikhao → user Proceed → POST confirmed. Yahan summary client-side
     * validated values ka hai (card POST me draft mode nahi hai contract me).
     */
    private fun showCreateSummary(
        act: Activity,
        name: String,
        pin: String,
        vals: Map<String, String>,
        onCreated: (Map<String, String>, String, String, String) -> Unit
    ) {
        val sb = StringBuilder("🪪 $name\n\n")
        if (vals.isEmpty()) sb.append("(koi detail nahi — baad me jod sakte ho)\n")
        for ((k, v) in vals) {
            sb.append("• ").append(DetailExtractor.label(k)).append(": ").append(v).append("\n")
        }
        sb.append("\nSahi hain to Card banao dabao.")
        AlertDialog.Builder(act)
            .setTitle("Confirm karo (पुष्टि करें)")
            .setMessage(sb.toString().trim())
            .setPositiveButton("✅ Sahi hai — Card banao (कार्ड बनाएं)") { d, _ ->
                d.dismiss()
                createCardNow(act, name, pin, vals, onCreated)
            }
            .setNegativeButton("❌ Theek karo", null)
            .setCancelable(false)
            .show()
    }

    private fun createCardNow(
        act: Activity,
        name: String,
        pin: String,
        vals: Map<String, String>,
        onCreated: (Map<String, String>, String, String, String) -> Unit
    ) {
        toast(act, "Card ban raha hai…")
        Thread({
            val res = try { AgentApi.createCard(act, name, pin, "manual", vals) }
            catch (_: Exception) { AgentApi.ApiResult(-1, null) }
            act.runOnUiThread {
                when {
                    res.code == 201 -> {
                        val card = res.json?.optJSONObject("card")
                        val id = card?.optString("id", "").orEmpty()
                        val cardName = card?.optString("name", name).orEmpty()
                        val fid = card?.optString("formmitra_id", "").orEmpty()
                        if (id.isEmpty()) {
                            toast(act, "⚠️ Card bana par id nahi mili — dobara try karo")
                            return@runOnUiThread
                        }
                        toast(act, "✅ Card ban gaya! $fid")
                        // Abhi PIN set kiya hai — turant unlock (dobara PIN
                        // mat maango), phir pending details flush.
                        Thread({
                            val u = try { AgentApi.unlockCard(act, id, pin) }
                            catch (_: Exception) { AgentApi.ApiResult(-1, null) }
                            val token = u.json?.optString("card_token", "").orEmpty()
                            act.runOnUiThread {
                                if (u.code == 200 && token.isNotEmpty()) {
                                    // POINT 24 (revised): naya card turant
                                    // persistent unlock + token persist.
                                    CardStore.recordPinAttempt(act, id, true)
                                    CardStore.setUnlocked(act, id)
                                    CardStore.putToken(
                                        act, id, token,
                                        u.json?.optString("expires_at", "")
                                    )
                                    CardStore.setSelectedCardId(act, id)
                                    // #4: pending details isi naye card me
                                    // tag ke saath AUTO-save (khoyi nahi).
                                    flushPendingDetails(act, id, token)
                                    onCreated(vals, id, cardName, token)
                                } else {
                                    // Unlock fail — phir bhi card bana hai;
                                    // user baad me PIN se kholega.
                                    CardStore.setSelectedCardId(act, id)
                                    toast(act, "Card bana — PIN se kholkar kaam shuru karo")
                                }
                            }
                        }, "fm-card-autounlock").start()
                    }
                    res.code == 400 &&
                        res.json?.optString("error", "") == "card_limit" ->
                        AlertDialog.Builder(act)
                            .setTitle("4 Cards ho gaye")
                            .setMessage(
                                "Ek user max 4 cards bana sakta hai. " +
                                    "Naya card chahiye to purana hatao (Profile → Cards)."
                            )
                            .setPositiveButton("Samajh gaya", null)
                            .show()
                    res.code == 401 -> toast(act, "🔑 Pehle login karo")
                    res.code == -1 -> toast(act, "📡 Internet nahi — Card nahi bana")
                    else -> {
                        val err = res.json?.optString("error", "").orEmpty()
                        toast(act, "⚠️ Card nahi bana${if (err.isNotEmpty()) ": $err" else " — dobara try karo"}")
                    }
                }
            }
        }, "fm-card-create").start()
    }

    // ============ #4 pending details flush ============

    /**
     * Pending details → card me PATCH (tag ke saath). auto=true: bina
     * poochhe (naya card abhi bana hai); auto=false: pehle poochho.
     */
    fun flushPendingDetails(act: Activity, cardId: String, token: String) {
        val pending = CardStore.pendingAll(act)
        if (pending.isEmpty()) return
        Thread({
            // v29 P2 (verify-after-write): PATCH ke baad re-read se confirm —
            // tabhi pending clear (pehle 2xx par turant clear ho jata tha).
            val toSave = LinkedHashMap<String, String>()
            for ((k, vt) in pending) toSave[k] = vt.first
            val res = CardSaveVerifier.saveAndVerify(
                patch = { d ->
                    try { AgentApi.patchCard(act, cardId, token, d).code }
                    catch (_: Exception) { -1 }
                },
                reread = {
                    try { AgentApi.cardDetail(act, cardId, token).json }
                    catch (_: Exception) { null }
                },
                prebuilt = JSONObject().also { details ->
                    for ((k, vt) in pending) {
                        details.put(
                            k,
                            JSONObject().put("value", vt.first).put("tag", vt.second)
                        )
                    }
                },
                toVerify = toSave
            )
            act.runOnUiThread {
                when (res) {
                    is CardSaveVerifier.Result.Verified -> {
                        CardStore.pendingClear(act)
                        val tags = pending.values.map { it.second }.toSet()
                            .filter { it.isNotEmpty() }.joinToString(", ")
                        toast(
                            act,
                            "✓ ${pending.size} details card me save ho gayi" +
                                (if (tags.isNotEmpty()) " (tag: $tags)" else "")
                        )
                    }
                    else -> {
                        // Fail/mismatch hua to pending REHTI HAI — khoyegi
                        // nahi, agli baar phir try hogi.
                        toast(
                            act,
                            "❌ Details save verify nahi hui — " +
                                "${CardSaveVerifier.loudReason(res)} — " +
                                "surakshit hain, agli baar try hogi"
                        )
                    }
                }
            }
        }, "fm-pending-flush").start()
    }

    private fun flushPendingWithAsk(act: Activity, cardId: String, token: String, auto: Boolean) {
        val n = CardStore.pendingCount(act)
        if (n == 0) return
        if (auto) {
            flushPendingDetails(act, cardId, token)
            return
        }
        AlertDialog.Builder(act)
            .setTitle("📝 Ruki hui details ($n)")
            .setMessage(
                "Tumne pehle $n details di thi jab koi card nahi tha — " +
                    "wo surakshit rakhi hain. Is card me tag ke saath save karun?"
            )
            .setPositiveButton("✅ Haan, save karo") { d, _ ->
                d.dismiss()
                flushPendingDetails(act, cardId, token)
            }
            .setNegativeButton("Rehne do", null)
            .show()
    }

    /**
     * #4 offer — chat me details aayi par koi active card nahi.
     * Ek session me ek baar poochho; "Baad me" par details PendingDetails
     * me safe rehti hain (khoyengi nahi).
     */
    private var offerShownSession = false

    fun offerCreateCardForDetails(
        act: Activity,
        n: Int,
        onAgentCreate: (Map<String, String>) -> Unit,
        onCreated: (Map<String, String>, String, String, String) -> Unit
    ) {
        if (offerShownSession) return
        offerShownSession = true
        AlertDialog.Builder(act)
            .setTitle("🪪 Card banao — details surakshit rahen")
            .setMessage(
                "Tumne $n details di hain — wo surakshit save kar li hain, " +
                    "khoyengi nahi. Inhe card me rakhne ke liye apna " +
                    "FormMitra Card banao (basic details se, 1 minute)."
            )
            .setPositiveButton("🪪 Card banao") { d, _ ->
                d.dismiss()
                showCreateChooser(act, emptyMap(), onCreated)
            }
            .setNegativeButton("Baad me (बाद में)", null)
            .show()
    }

    /** Through Agent flow complete hua ho to naya card dhoondho (best-effort). */
    fun checkNewCardAfterAgent(
        act: Activity,
        knownIds: Set<String>,
        onFound: (cardId: String, cardName: String) -> Unit
    ) {
        Thread({
            val res = try { AgentApi.cards(act) }
            catch (_: Exception) { AgentApi.ApiResult(-1, null) }
            if (res.code !in 200..299) return@Thread
            val arr = res.json?.optJSONArray("cards") ?: return@Thread
            var found: JSONObject? = null
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (c.optString("id") !in knownIds) { found = c; break }
            }
            val f = found ?: return@Thread
            act.runOnUiThread {
                onFound(f.optString("id"), f.optString("name", "Card"))
            }
        }, "fm-card-checknew").start()
    }

    fun knownCardIds(act: Activity): Set<String> {
        val out = HashSet<String>()
        try {
            val res = AgentApi.cards(act)
            val arr = res.json?.optJSONArray("cards") ?: return out
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("id")?.let { out.add(it) }
            }
        } catch (_: Exception) { }
        return out
    }
}

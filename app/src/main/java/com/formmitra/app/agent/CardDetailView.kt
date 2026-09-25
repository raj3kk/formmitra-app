package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * CardDetailView (v24) — PIN unlock ke baad card ka andaruni screen.
 *
 *  - Header: card naam + FormMitra ID + [🔄 Refresh] [🗑️ Card hatao] [← Wapas]
 *  - 📝 Details (विवरण) section: har field row — label (bilingual) + value +
 *    tag chip; TAP → edit dialog (value + tag) → 💾 Save (सहेजें) → PATCH
 *    /api/cards/[id]; 🗑️ per-field delete (D18: pehle confirmation popup).
 *    "➕ Detail jodo (विवरण जोड़ें)" — key + value + tag → PATCH merge.
 *  - 📁 Document Vault (दस्तावेज़): "➕ Add Document (दस्तावेज़ जोड़ें)"
 *    button VAULT SCREEN KE ANDAR UPAR (C11); list; har doc par 🗑️
 *    (confirm ke saath → DELETE {confirm:true}).
 */
class CardDetailView(
    context: Context,
    private val cardId: String,
    private val cardName: String,
    private val formmitraId: String,
    private val cardToken: String,
    private val onBack: () -> Unit,
    private val onCardDeleted: () -> Unit
) : LinearLayout(context) {

    companion object {
        /** MainActivity.onActivityResult se forward hota hai. */
        const val REQ_CARD_DOC_PICK = 2002
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        try { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
        catch (_: Exception) { }
    }

    private val scroll = ScrollView(context)
    private val content = LinearLayout(context)
    private val detailsList = LinearLayout(context)
    private val customList = LinearLayout(context)
    private val docsList = LinearLayout(context)
    private val detailsCount = TextView(context)
    private var detailsData = JSONObject()

    /** v28 P9/P10: form state — staged edits, Save par ek PATCH. */
    // v29 P2+P4: knownKeys = TagRegistry canonical 25 (pehle
    // DetailExtractor.orderedKeys() + khata/khesra/mauza — jo usme PEHLE
    // SE the → form me 3 fields DO-DO baar dikhte the; ab fixed).
    private val knownKeys: List<String> = TagRegistry.orderedKeys()
    private val fieldEdits = LinkedHashMap<String, EditText>()
    private val customEdits = LinkedHashMap<String, EditText>()
    private val knownOriginal = LinkedHashMap<String, String>()
    private val knownTags = LinkedHashMap<String, String>()
    private val customOriginal = LinkedHashMap<String, String>()
    private val customTags = LinkedHashMap<String, String>()
    private val customDeleted = mutableSetOf<String>()

    /**
     * v29 P2: per-field save-state indicators — teen states visually alag:
     * saved ✓ (hara), pending/unsaved ● (narangi), khaali (grey).
     */
    private val knownStatus = LinkedHashMap<String, TextView>()
    private val customStatus = LinkedHashMap<String, TextView>()

    /** v29 P2: Save ka inline result — LOUD Hinglish (toast ke saath). */
    private lateinit var saveStatus: TextView
    private lateinit var saveBtn: Button
    private var saving = false

    /** v29 P2: load fail par inline banner (silent khaali form nahi). */
    private lateinit var loadError: TextView

    /** Upload hone wali file ka pending tag (picker → tag dialog → upload). */
    private var pendingUploadUri: Uri? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        val pad = dp(14)
        content.orientation = VERTICAL
        content.setPadding(pad, pad, pad, pad * 2)

        // Header
        val headRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backBtn = Button(context).apply {
            text = "←"
            textSize = 16f
            setOnClickListener { onBack() }
        }
        headRow.addView(backBtn)
        headRow.addView(TextView(context).apply {
            text = "🪪 $cardName"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(8), 0, dp(8), 0) }
        })
        content.addView(headRow)
        content.addView(TextView(context).apply {
            text = "FormMitra ID: $formmitraId"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0E7C5B"))
            setPadding(0, dp(2), 0, dp(2))
        })
        val btnRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnRow.addView(Button(context).apply {
            text = "🔄 Refresh (ताज़ा करें)"
            textSize = 13f
            setOnClickListener { load() }
        }.apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, dp(6), 0) }
        })
        btnRow.addView(Button(context).apply {
            text = "🗑️ Card hatao (हटाएं)"
            textSize = 13f
            setTextColor(Color.parseColor("#C5221F"))
            setOnClickListener { confirmDeleteCard() }
        }.apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(6), 0, 0, 0) }
        })
        content.addView(btnRow)

        // ---- 📝 Details section (v28 P9: EK simple form — saare known
        // fields, bhare ya khaali, ek-ek EditText; neeche EK Save button) ----
        content.addView(sectionTitle("📝 Details (विवरण)"))
        content.addView(detailsCount.apply {
            textSize = 12f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, 0, 0, dp(4))
        })
        // v29 P2: load fail par LOUD inline banner (silent khaali form nahi).
        loadError = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#C5221F"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
        }
        content.addView(loadError)
        detailsList.orientation = VERTICAL
        content.addView(detailsList)

        // ---- 🏷️ Extra Details (v28 P10: custom Tag + Value rows) ----
        content.addView(sectionTitle("🏷️ Extra Details (अतिरिक्त विवरण)"))
        content.addView(TextView(context).apply {
            text = "Apni marzi ke tag — jaise Aadhar number, PAN. Chat me di hui nayi details yahan khud save hoti hain."
            textSize = 12f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, 0, 0, dp(4))
        })
        customList.orientation = VERTICAL
        content.addView(customList)
        val addMoreBtn = Button(context).apply {
            text = "＋ Add more (और जोड़ें)"
            textSize = 14f
            setOnClickListener { showAddCustomDialog() }
        }
        UiKit.pressFeedback(addMoreBtn)
        content.addView(addMoreBtn.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, 0) }
        })

        // ---- 💾 EK Save button (v28 P9) — saare badlav ek PATCH me ----
        // v29 P2: neeche inline saveStatus — Save ka natija LOUD Hinglish
        // me (toast ke SAATH); fail par retry = yehi Save button dobara.
        saveBtn = Button(context).apply {
            text = "💾 Save (सहेजें)"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setOnClickListener { saveAllDetails() }
        }
        UiKit.pressFeedback(saveBtn)
        content.addView(saveBtn.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10), 0, 0) }
        })
        saveStatus = TextView(context).apply {
            textSize = 13f
            setPadding(dp(4), dp(6), dp(4), 0)
            visibility = View.GONE
        }
        content.addView(saveStatus)

        // ---- 📁 Document Vault (Add button UPAR — C11) ----
        content.addView(sectionTitle("📁 Document Vault (दस्तावेज़)"))
        val addDocBtn = Button(context).apply {
            text = "➕ Add Document (दस्तावेज़ जोड़ें)"
            textSize = 14f
            setOnClickListener { pickDoc() }
        }
        UiKit.pressFeedback(addDocBtn)
        content.addView(addDocBtn.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(6)) }
        })
        content.addView(TextView(context).apply {
            text = "Vault ke documents agent ke kaam me tag ke saath use honge."
            textSize = 12f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, 0, 0, dp(6))
        })
        docsList.orientation = VERTICAL
        content.addView(docsList)

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
        load()
    }

    // ============ load ============

    fun load() {
        detailsCount.text = "La raha hun…"
        loadError.visibility = View.GONE
        Thread({
            val det = try { AgentApi.cardDetail(context, cardId, cardToken) }
            catch (_: Exception) { AgentApi.ApiResult(-1, null) }
            val docs = try { AgentApi.cardDocs(context, cardId, cardToken) }
            catch (_: Exception) { AgentApi.ApiResult(-1, null) }
            post {
                if (det.code == 401 || det.code == 403) {
                    toast("🔒 Session khatm — PIN se dobara kholo")
                    onBack()
                    return@post
                }
                // v29 P2 ROOT FIX: load fail par pehle CHUPCHAAP khaali form
                // render ho jata tha — user ko lagta data hi gayab hai.
                // Ab LOUD banner: details dikhengi hi nahi jab tak load na ho.
                if (det.code !in 200..299) {
                    val why = if (det.code == -1) "internet nahi lag raha"
                    else "server me dikkat"
                    loadError.text =
                        "❌ Details LOAD NAHI HUI — $why.\n" +
                            "🔄 Refresh dabao — purani details gayab NAHI hui hain."
                    loadError.visibility = View.VISIBLE
                    detailsCount.text = "Load nahi hui"
                    return@post
                }
                renderDetails(det.json?.optJSONObject("details"))
                renderDocs(docs)
            }
        }, "fm-carddetail-load").start()
    }

    // ============ Details (v28 P9/P10: ek simple form + custom rows) ============

    private fun storedValue(key: String): String {
        val o = detailsData.optJSONObject(key)
        return o?.optString("value", "") ?: detailsData.optString(key, "")
    }

    private fun storedTag(key: String): String {
        val o = detailsData.optJSONObject(key)
        return o?.optString("tag", "").orEmpty()
    }

    /**
     * P9: EK scrollable simple form — saare KNOWN fields (bhare ya khaali),
     * har field ka EditText. P10: uske neeche custom Tag+Value rows
     * (unknown keys — chat se auto-save hue ya "＋ Add more" se jude).
     * Badlav staged rehte hain — 💾 Save par EK PATCH jata hai.
     *
     * v29 P2+P4: aane wali keys normalize hoti hain — legacy "address" →
     * "address_line", purane display labels ("Aadhar No. (आधार नं.)") →
     * canonical. Collision par exact canonical key jeetegi. Har field par
     * save-state indicator (✓/●/khaali).
     */
    private fun renderDetails(det: JSONObject?) {
        detailsList.removeAllViews()
        customList.removeAllViews()
        fieldEdits.clear()
        customEdits.clear()
        knownStatus.clear()
        customStatus.clear()
        knownOriginal.clear()
        knownTags.clear()
        customOriginal.clear()
        customTags.clear()
        customDeleted.clear()
        detailsData = det ?: JSONObject()

        // v29 P4: keys normalize karo.
        val knownSet = knownKeys.toSet()
        val knownVals = LinkedHashMap<String, String>()
        val knownTagMap = LinkedHashMap<String, String>()
        val customVals = LinkedHashMap<String, String>()
        val customTagMap = LinkedHashMap<String, String>()
        val rawKeys = mutableListOf<String>()
        val ki = detailsData.keys()
        while (ki.hasNext()) rawKeys.add(ki.next())
        // Pass 1: exact canonical keys.
        for (k in rawKeys) {
            if (k in knownSet) {
                knownVals[k] = storedValue(k).let { if (it == "null") "" else it }
                knownTagMap[k] = storedTag(k)
            }
        }
        // Pass 2: baaki keys normalize karke.
        for (k in rawKeys) {
            if (k in knownSet) continue
            val nk = TagRegistry.normalizeTag(k)
            if (nk.isEmpty()) continue
            val v = storedValue(k).let { if (it == "null") "" else it }
            if (v.isEmpty()) continue
            val t = storedTag(k)
            if (nk in knownSet) {
                if (!knownVals.containsKey(nk)) {
                    knownVals[nk] = v
                    knownTagMap[nk] = t
                }
            } else {
                if (!customVals.containsKey(nk)) {
                    customVals[nk] = v
                    customTagMap[nk] = t
                }
            }
        }

        // Known fields — sab, khaali ho to bhi.
        var filled = 0
        for (k in knownKeys) {
            val v = knownVals[k].orEmpty()
            val tag = knownTagMap[k].orEmpty()
            knownOriginal[k] = v
            knownTags[k] = tag
            if (v.isNotEmpty()) filled++
            detailsList.addView(knownFieldRow(k, v, tag))
        }
        detailsCount.text =
            "$filled/${knownKeys.size} fields bhare hue — badlo, phir neeche 💾 Save dabao"

        // Custom rows — unknown keys (P10/P11), normalized.
        val customKeys = customVals.keys.sorted()
        for (k in customKeys) {
            customOriginal[k] = customVals.getValue(k)
            customTags[k] = customTagMap[k].orEmpty()
            customList.addView(customRow(k, customVals.getValue(k)))
        }
        if (customKeys.isEmpty()) {
            customList.addView(TextView(context).apply {
                text = "Koi extra detail nahi — \"＋ Add more\" se jodo."
                textSize = 12f
                setTextColor(Color.parseColor("#80868B"))
                setPadding(0, dp(2), 0, dp(2))
            })
        }
    }

    /**
     * P9: known field ki ek form row — label + EditText (khaali ho to bhi)
     * + save-state indicator (v29 P2).
     */
    private fun knownFieldRow(key: String, value: String, tag: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(TextView(context).apply {
                text = TagRegistry.labelOf(key) +
                    (if (tag.isNotEmpty()) "  🏷️ $tag" else "")
                textSize = 12f
                setTextColor(Color.parseColor("#80868B"))
            })
            val et = EditText(context).apply {
                setText(value)
                hint = TagRegistry.labelOf(key)
                textSize = 15f
                setTextColor(Color.parseColor("#202124"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            fieldEdits[key] = et
            addView(et)
            // v29 P2: har field par saved ✓ indicator.
            val st = TextView(context).apply {
                textSize = 11f
                setPadding(dp(8), dp(2), dp(8), 0)
            }
            knownStatus[key] = st
            addView(st)
            // Badlav par turant "pending" state (Save dabane tak).
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, a: Int, b: Int, c: Int
                ) { }
                override fun onTextChanged(
                    s: CharSequence?, a: Int, b: Int, c: Int
                ) { }
                override fun afterTextChanged(s: android.text.Editable?) {
                    try { updateFieldStatus(key) } catch (_: Exception) { }
                }
            })
            updateFieldStatus(key)
        }
    }

    /**
     * v29 P2: teen states visually alag —
     *  saved (✓ hara-bhara) / pending-unsaved (● narangi) / khaali (grey).
     */
    private fun updateFieldStatus(key: String) {
        val st = knownStatus[key] ?: return
        val et = fieldEdits[key] ?: return
        val cur = et.text.toString().trim()
        val orig = knownOriginal[key].orEmpty()
        when {
            cur != orig -> {
                st.text = "● badla — Save dabao"
                st.setTextColor(Color.parseColor("#E8710A"))
            }
            cur.isNotEmpty() -> {
                st.text = "✓ save hua"
                st.setTextColor(Color.parseColor("#0E7C5B"))
            }
            else -> {
                st.text = "khaali"
                st.setTextColor(Color.parseColor("#80868B"))
            }
        }
    }

    /** v29 P2: custom row ka status — known jaisa hi. */
    private fun updateCustomStatus(key: String) {
        val st = customStatus[key] ?: return
        val et = customEdits[key] ?: return
        val cur = et.text.toString().trim()
        val orig = customOriginal[key].orEmpty()
        when {
            key in customDeleted -> {
                st.text = "🗑️ hatega — Save dabao"
                st.setTextColor(Color.parseColor("#C5221F"))
            }
            cur != orig -> {
                st.text = "● badla — Save dabao"
                st.setTextColor(Color.parseColor("#E8710A"))
            }
            cur.isNotEmpty() -> {
                st.text = "✓ save hua"
                st.setTextColor(Color.parseColor("#0E7C5B"))
            }
            else -> {
                st.text = "khaali"
                st.setTextColor(Color.parseColor("#80868B"))
            }
        }
    }

    /**
     * P10: custom Tag+Value row — label, value EditText, per-row delete +
     * save-state indicator (v29 P2). `label` normalized key hai (render
     * ya add-dialog se).
     */
    private fun customRow(label: String, value: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val mid = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                // v29 P2+P4: bilingual label (canonical → contract label,
                // baaki → readable).
                text = "🏷️ ${TagRegistry.labelOf(label)}"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#5F6368"))
            })
            addView(EditText(context).apply {
                setText(value)
                textSize = 15f
                setTextColor(Color.parseColor("#202124"))
                customEdits[label] = this
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?, a: Int, b: Int, c: Int
                    ) { }
                    override fun onTextChanged(
                        s: CharSequence?, a: Int, b: Int, c: Int
                    ) { }
                    override fun afterTextChanged(s: android.text.Editable?) {
                        try { updateCustomStatus(label) } catch (_: Exception) { }
                    }
                })
            })
            addView(TextView(context).apply {
                textSize = 11f
                customStatus[label] = this
            })
        }
        row.addView(mid)
        row.addView(Button(context).apply {
            text = "🗑️"
            textSize = 14f
            minimumWidth = 0
            setOnClickListener { confirmDeleteCustom(label, row) }
        })
        row.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(6)) }
        updateCustomStatus(label)
        return row
    }

    /** P10: "＋ Add more" — Tag (label) + Value dialog. */
    private fun showAddCustomDialog() {
        val act = context as? Activity ?: return
        val layout = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        layout.addView(TextView(act).apply {
            text = "Tag (टैग) — jaise: Aadhar number"
            textSize = 13f
            setTextColor(Color.parseColor("#80868B"))
        })
        val tagEt = EditText(act).apply {
            hint = "Tag likho"
            textSize = 16f
            setTextColor(Color.parseColor("#202124"))
        }
        layout.addView(tagEt)
        layout.addView(TextView(act).apply {
            text = "Value (मूल्य)"
            textSize = 13f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, dp(8), 0, 0)
        })
        val valEt = EditText(act).apply {
            hint = "Value likho"
            textSize = 16f
            setTextColor(Color.parseColor("#202124"))
        }
        layout.addView(valEt)
        val errTv = TextView(act).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#C5221F"))
            visibility = View.GONE
        }
        layout.addView(errTv)
        val dlg = AlertDialog.Builder(act)
            .setTitle("＋ Add more (और जोड़ें)")
            .setView(ScrollView(act).apply { addView(layout) })
            .setPositiveButton("Jodo (जोड़ें)", null)
            .setNegativeButton("Radd karo", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val tagRaw = tagEt.text.toString().trim()
                val v = valEt.text.toString().trim()
                // v29 P2+P4: tag canonical key par lao — "Aadhar number"
                // → aadhar_no; unknown → sanitized fallback.
                val tag = TagRegistry.normalizeTag(tagRaw)
                when {
                    tagRaw.isEmpty() -> {
                        errTv.text = "❌ Tag khaali hai"
                        errTv.visibility = View.VISIBLE
                    }
                    tag.isEmpty() -> {
                        errTv.text = "❌ Tag samajh nahi aaya"
                        errTv.visibility = View.VISIBLE
                    }
                    v.isEmpty() -> {
                        errTv.text = "❌ Value khaali hai"
                        errTv.visibility = View.VISIBLE
                    }
                    customEdits.containsKey(tag) || knownKeys.contains(tag) -> {
                        errTv.text = "❌ Ye tag pehle se hai"
                        errTv.visibility = View.VISIBLE
                    }
                    else -> {
                        dlg.dismiss()
                        // Pehle se "khaali" wala placeholder hatao.
                        if (customEdits.isEmpty() && customOriginal.isEmpty()) {
                            customList.removeAllViews()
                        }
                        customList.addView(customRow(tag, v))
                        toast("✓ Judega — neeche 💾 Save dabao")
                    }
                }
            }
        }
        dlg.show()
    }

    /** P10: per-row confirmed delete (har delete par confirmation popup). */
    private fun confirmDeleteCustom(label: String, row: View) {
        val act = context as? Activity ?: return
        AlertDialog.Builder(act)
            .setTitle("🗑️ Extra detail hatao?")
            .setMessage(
                "\"${TagRegistry.labelOf(label)}\" is card se hat jayega.\n" +
                    "Ye wapas nahi aayega!"
            )
            .setPositiveButton("🗑️ Hatao (हटाएं)") { d, _ ->
                d.dismiss()
                customList.removeView(row)
                customEdits.remove(label)
                customStatus.remove(label)
                // Server par maujood tha → Save par null jayega (delete).
                if (customOriginal.containsKey(label)) {
                    customDeleted.add(label)
                }
                toast("✓ Hatega — neeche 💾 Save dabao")
            }
            .setNegativeButton("Rakho (रखें)", null)
            .show()
    }

    /**
     * P9: EK Save button — saare staged badlav ek PATCH me.
     *  - known field badla → {value, tag}; khaali kiya (pehle bhara tha) → null.
     *  - custom badla/naya → {value, tag}; delete confirm hua → null.
     *  - validate fail → ruko, error dikhao (galat save nahi).
     *
     * v29 P2 ROOT FIX: fail par pehle CHUPCHAAP "⚠️ Save nahi hua — dobara
     * try karo" toast tha — user samjhta hi nahi tha KIYA galat hua. Ab:
     * inline LOUD red error (kaaran Hinglish me) + retry = yehi Save
     * button dobara dabana. Failed PATCH par fields ke values baney
     * rehte hain (reload nahi) — kuch khoyega nahi.
     */
    private fun saveAllDetails() {
        // Double-tap guard — do PATCH ek saath nahi.
        if (saving) return
        val details = JSONObject()
        // Known fields
        for ((k, et) in fieldEdits) {
            val nv = et.text.toString().trim()
            val ov = knownOriginal[k].orEmpty()
            if (nv == ov) continue
            if (nv.isNotEmpty()) {
                val err = CardFlow.validateField(k, nv)
                if (err != null) {
                    toast("❌ ${TagRegistry.labelOf(k)}: $err")
                    saveStatus.text = "❌ ${TagRegistry.labelOf(k)}: $err"
                    saveStatus.setTextColor(Color.parseColor("#C5221F"))
                    saveStatus.visibility = View.VISIBLE
                    et.requestFocus()
                    return
                }
                details.put(
                    k,
                    JSONObject().put("value", nv).put("tag", knownTags[k].orEmpty())
                )
            } else if (ov.isNotEmpty()) {
                details.put(k, JSONObject.NULL)
            }
        }
        // Custom rows
        for ((label, et) in customEdits) {
            if (label in customDeleted) continue
            val nv = et.text.toString().trim()
            val ov = customOriginal[label]
            if (ov != null && nv == ov) continue
            if (nv.isEmpty()) {
                if (ov != null) details.put(label, JSONObject.NULL)
                continue
            }
            details.put(
                label,
                JSONObject().put("value", nv).put("tag", customTags[label].orEmpty())
            )
        }
        for (label in customDeleted) {
            details.put(label, JSONObject.NULL)
        }
        if (details.length() == 0) {
            toast("Koi badlav nahi")
            return
        }
        saving = true
        saveBtn.isEnabled = false
        saveBtn.text = "⏳ Save ho raha hai…"
        saveStatus.text = "⏳ Save ho raha hai…"
        saveStatus.setTextColor(Color.parseColor("#80868B"))
        saveStatus.visibility = View.VISIBLE
        val payload = details
        // v29 P2 (verify-after-write): likhi/hatayi keys alag — re-read se
        // milan hoga (payload me per-key tags + delete-NULL hain).
        val toVerify = LinkedHashMap<String, String>()
        val toVerifyDeleted = mutableSetOf<String>()
        val pit = payload.keys()
        while (pit.hasNext()) {
            val k = pit.next()
            val o = payload.optJSONObject(k)
            if (o != null) toVerify[k] = o.optString("value", "")
            else toVerifyDeleted.add(k)
        }
        Thread({
            val res = CardSaveVerifier.saveAndVerify(
                patch = { d ->
                    try { AgentApi.patchCard(context, cardId, cardToken, d).code }
                    catch (_: Exception) { -1 }
                },
                reread = {
                    try { AgentApi.cardDetail(context, cardId, cardToken).json }
                    catch (_: Exception) { null }
                },
                prebuilt = payload,
                toVerify = toVerify,
                toVerifyDeleted = toVerifyDeleted
            )
            post {
                saving = false
                saveBtn.isEnabled = true
                saveBtn.text = "💾 Save (सहेजें)"
                when (res) {
                    is CardSaveVerifier.Result.Verified -> {
                        // v29 P2: re-read me confirm — TABHI "✓ save ho gaya".
                        saveStatus.text = "✓ Sab save ho gaya"
                        saveStatus.setTextColor(Color.parseColor("#0E7C5B"))
                        saveStatus.visibility = View.VISIBLE
                        toast("✓ Save ho gaya (सहेजा गया)")
                        load()
                    }
                    is CardSaveVerifier.Result.PatchFailed -> {
                        if (res.code == 401 || res.code == 403) {
                            // Session khatm — retry ka matlab nahi, dobara kholo.
                            saveStatus.text = "🔒 Session khatm — PIN se dobara kholo"
                            saveStatus.setTextColor(Color.parseColor("#C5221F"))
                            saveStatus.visibility = View.VISIBLE
                            toast("🔒 Session khatm — PIN se dobara kholo")
                            onBack()
                        } else {
                            // v29 P2: kaaran saaf-saaf — retry = Save dobara
                            // dabana. Fields ki values bani rehti hain.
                            val why = CardSaveVerifier.loudReason(res)
                            val msg = "❌ SAVE NAHI HUA — $why. Aapki typing " +
                                "gayab nahi hui — 💾 Save dobara dabao."
                            saveStatus.text = msg
                            saveStatus.setTextColor(Color.parseColor("#C5221F"))
                            saveStatus.visibility = View.VISIBLE
                            toast("❌ Save nahi hua — $why — dobara try karo")
                        }
                    }
                    is CardSaveVerifier.Result.Mismatch -> {
                        // PATCH 2xx par re-read mismatch — LOUD + retry.
                        val why = CardSaveVerifier.loudReason(res)
                        val msg = "❌ SAVE VERIFY NAHI HUA — $why. " +
                            "💾 Save dobara dabao."
                        saveStatus.text = msg
                        saveStatus.setTextColor(Color.parseColor("#C5221F"))
                        saveStatus.visibility = View.VISIBLE
                        toast("❌ Save verify nahi hua — dobara try karo")
                    }
                }
            }
        }, "fm-card-saveall").start()
    }

    private fun confirmDeleteCard() {
        val act = context as? Activity ?: return
        AlertDialog.Builder(act)
            .setTitle("🗑️ Card hatao? (कार्ड हटाएं?)")
            .setMessage(
                "🪪 \"$cardName\" ($formmitraId) HAMESHA ke liye hat jayega —\n" +
                    "saari details + documents bhi.\nYe wapas nahi aayega!"
            )
            .setPositiveButton("🗑️ Haan, hatao") { d, _ ->
                d.dismiss()
                deleteCardNow()
            }
            .setNegativeButton("Rehne do", null)
            .show()
    }

    private fun deleteCardNow() {
        toast("Card hat raha hai…")
        Thread({
            val res = try { AgentApi.deleteCard(context, cardId) }
            catch (_: Exception) { AgentApi.ApiResult(-1, null) }
            post {
                if (res.code in 200..299) {
                    CardStore.clearToken(cardId)
                    if (CardStore.selectedCardId(context) == cardId) {
                        CardStore.setSelectedCardId(context, null)
                    }
                    toast("✓ Card hata diya")
                    onCardDeleted()
                } else {
                    toast("⚠️ Card hataya nahi gaya — dobara try karo")
                }
            }
        }, "fm-card-delete").start()
    }

    // ============ Documents ============

    private fun renderDocs(res: AgentApi.ApiResult) {
        docsList.removeAllViews()
        if (res.code == -1) {
            docsList.addView(TextView(context).apply {
                text = "📡 Internet nahi — documents nahi dikhe"
                textSize = 13f
                setTextColor(Color.parseColor("#80868B"))
            })
            return
        }
        val arr = res.json?.optJSONArray("documents")
        if (arr == null || arr.length() == 0) {
            docsList.addView(TextView(context).apply {
                text = "Koi document nahi — upar Add Document (दस्तावेज़ जोड़ें) se jodo."
                textSize = 13f
                setTextColor(Color.parseColor("#80868B"))
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val docId = d.optString("id", "")
            val name = d.optString("name", "document")
            val tag = d.optString("tag", "")
            docsList.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg()
                setPadding(dp(10), dp(8), dp(10), dp(8))
                addView(TextView(context).apply {
                    text = "📄 $name" +
                        (if (tag.isNotEmpty()) "\n🏷️ $tag" else "")
                    textSize = 13f
                    setTextColor(Color.parseColor("#202124"))
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(Button(context).apply {
                    text = "🗑️"
                    textSize = 14f
                    setOnClickListener { confirmDeleteDoc(docId, name) }
                })
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(6)) }
            })
        }
    }

    private fun pickDoc() {
        val act = context as? Activity ?: return
        pendingUploadUri = null
        try {
            act.startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                REQ_CARD_DOC_PICK
            )
        } catch (_: Exception) {
            toast("Picker nahi khula")
        }
    }

    /** MainActivity.onActivityResult se forward hota hai. */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_CARD_DOC_PICK) return false
        if (resultCode != Activity.RESULT_OK) return true
        val uri = try { data?.data } catch (_: Exception) { null } ?: return true
        pendingUploadUri = uri
        val act = context as? Activity ?: return true
        // Tag poochho, phir upload (C14: documents vault me tag ke saath).
        val tagEt = EditText(act).apply {
            hint = "tag — jaise zamin, job, scholarship (optional)"
            textSize = 16f
        }
        val wrap = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(tagEt)
        }
        AlertDialog.Builder(act)
            .setTitle("🏷️ Document ka tag")
            .setMessage("Ye document kaun se kaam ka hai? Tag se agent sahi file uthayega.")
            .setView(wrap)
            .setPositiveButton("⬆️ Upload karo") { d, _ ->
                d.dismiss()
                uploadDoc(tagEt.text.toString().trim())
            }
            .setNegativeButton("Radd karo", null)
            .show()
        return true
    }

    private fun uploadDoc(tag: String) {
        val uri = pendingUploadUri ?: return
        val act = context as? Activity ?: return
        toast("Upload ho raha hai…")
        Thread({
            try {
                val cr = act.contentResolver
                val mime = try { cr.getType(uri) ?: "" } catch (_: Exception) { "" }
                var name = "document"
                try {
                    cr.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex("_display_name")
                        if (c.moveToFirst() && idx >= 0) {
                            c.getString(idx)?.let { if (it.isNotEmpty()) name = it }
                        }
                    }
                } catch (_: Exception) { }
                val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    act.runOnUiThread { toast("⚠️ File padhi nahi gayi") }
                    return@Thread
                }
                if (bytes.size > 10 * 1024 * 1024) {
                    act.runOnUiThread { toast("⚠️ File bahut badi hai (10MB max)") }
                    return@Thread
                }
                val res = AgentApi.uploadCardDoc(
                    act, cardId, cardToken, name, mime, bytes, tag
                )
                act.runOnUiThread {
                    when {
                        res.code in 200..299 -> {
                            toast("✓ Document jud gaya 🏷️ ${if (tag.isNotEmpty()) tag else "bina tag"}")
                            load()
                        }
                        res.code == 401 || res.code == 403 ->
                            toast("🔒 Session khatm — PIN se dobara kholo")
                        res.code == -1 -> toast("📡 Internet nahi — upload nahi hua")
                        else -> toast("⚠️ Upload nahi hua — dobara try karo")
                    }
                }
            } catch (_: Exception) {
                act.runOnUiThread { toast("⚠️ Upload me dikkat — dobara try karo") }
            }
        }, "fm-card-docupload").start()
    }

    /** D18: document delete par pehle confirmation. */
    private fun confirmDeleteDoc(docId: String, name: String) {
        val act = context as? Activity ?: return
        AlertDialog.Builder(act)
            .setTitle("🗑️ Document hatao? (हटाएं?)")
            .setMessage("\"$name\" card se hat jayega.\nAgent aage se is file ko use nahi karega.")
            .setPositiveButton("🗑️ Hatao (हटाएं)") { d, _ ->
                d.dismiss()
                deleteDocNow(docId)
            }
            .setNegativeButton("Rakho (रखें)", null)
            .show()
    }

    private fun deleteDocNow(docId: String) {
        Thread({
            val res = try { AgentApi.deleteCardDoc(context, cardId, docId, cardToken) }
            catch (_: Exception) { AgentApi.ApiResult(-1, null) }
            post {
                if (res.code in 200..299) {
                    toast("✓ Document hata diya")
                    load()
                } else {
                    toast("⚠️ Hataya nahi gaya — dobara try karo")
                }
            }
        }, "fm-card-deldoc").start()
    }

    // ============ helpers ============

    private fun sectionTitle(t: String): TextView =
        TextView(context).apply {
            text = t
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, dp(12), 0, dp(6))
        }

    private fun cardBg(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor("#F8F9FA"))
            setStroke(dp(1), Color.parseColor("#DADCE0"))
            cornerRadius = dp(10).toFloat()
        }
}

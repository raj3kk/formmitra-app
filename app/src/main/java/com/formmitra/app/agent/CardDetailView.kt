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
    private val docsList = LinearLayout(context)
    private val detailsCount = TextView(context)
    private var detailsData = JSONObject()

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

        // ---- 📝 Details section ----
        content.addView(sectionTitle("📝 Details (विवरण)"))
        content.addView(detailsCount.apply {
            textSize = 12f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, 0, 0, dp(4))
        })
        detailsList.orientation = VERTICAL
        content.addView(detailsList)
        val addDetailBtn = Button(context).apply {
            text = "➕ Detail jodo (विवरण जोड़ें)"
            textSize = 14f
            setOnClickListener { showAddDetailDialog() }
        }
        UiKit.pressFeedback(addDetailBtn)
        content.addView(addDetailBtn.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, 0) }
        })

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
                renderDetails(det.json?.optJSONObject("details"))
                renderDocs(docs)
            }
        }, "fm-carddetail-load").start()
    }

    // ============ Details ============

    private fun renderDetails(det: JSONObject?) {
        detailsList.removeAllViews()
        detailsData = det ?: JSONObject()
        val keys = mutableListOf<String>()
        val it = detailsData.keys()
        while (it.hasNext()) keys.add(it.next())
        detailsCount.text = if (keys.isEmpty())
            "Koi detail nahi — neeche se jodo. Kaam ke dauraan di hui details yahan tag ke saath save hongi."
        else "${keys.size} details — tap karke edit karo"
        if (keys.isEmpty()) {
            detailsList.addView(TextView(context).apply {
                text = "📝 Abhi khaali hai"
                textSize = 13f
                setTextColor(Color.parseColor("#80868B"))
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }
        for (k in keys.sorted()) {
            val o = detailsData.optJSONObject(k)
            val v = o?.optString("value", "") ?: detailsData.optString(k, "")
            val tag = o?.optString("tag", "").orEmpty()
            if (v.isEmpty() || v == "null") continue
            detailsList.addView(detailRow(k, v, tag))
        }
    }

    private fun detailRow(key: String, value: String, tag: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            isFocusable = true
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = DetailExtractor.label(key) +
                        (if (tag.isNotEmpty()) "  🏷️ $tag" else "")
                    textSize = 12f
                    setTextColor(Color.parseColor("#80868B"))
                })
                addView(TextView(context).apply {
                    text = value
                    textSize = 15f
                    setTextColor(Color.parseColor("#202124"))
                })
            })
            addView(Button(context).apply {
                text = "✏️"
                textSize = 14f
                setOnClickListener { showEditDetailDialog(key, value, tag) }
            })
            addView(Button(context).apply {
                text = "🗑️"
                textSize = 14f
                setOnClickListener { confirmDeleteDetail(key) }
            })
            setOnClickListener { showEditDetailDialog(key, value, tag) }
            UiKit.pressFeedback(this)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(6)) }
        }
    }

    /** #3: Details editable + Save — card ke andar (PATCH /api/cards/[id]). */
    private fun showEditDetailDialog(key: String, value: String, tag: String) {
        val act = context as? Activity ?: return
        val layout = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        layout.addView(TextView(act).apply {
            text = "${DetailExtractor.label(key)} (मूल्य)"
            textSize = 13f
            setTextColor(Color.parseColor("#80868B"))
        })
        val valEt = EditText(act).apply {
            setText(value)
            textSize = 16f
            setTextColor(Color.parseColor("#202124"))
        }
        layout.addView(valEt)
        layout.addView(TextView(act).apply {
            text = "Tag (टैग) — jaise: zamin, job, scholarship"
            textSize = 13f
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, dp(8), 0, 0)
        })
        val tagEt = EditText(act).apply {
            setText(tag)
            hint = "tag (optional)"
            textSize = 16f
            setTextColor(Color.parseColor("#202124"))
        }
        layout.addView(tagEt)
        val errTv = TextView(act).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#C5221F"))
            visibility = View.GONE
        }
        layout.addView(errTv)
        val dlg = AlertDialog.Builder(act)
            .setTitle("✏️ Edit (संपादित करें) — ${DetailExtractor.label(key)}")
            .setView(ScrollView(act).apply { addView(layout) })
            .setPositiveButton("💾 Save (सहेजें)", null)
            .setNegativeButton("❌ Radd karo (रद्द करें)", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nv = valEt.text.toString().trim()
                val nt = tagEt.text.toString().trim()
                // #2: validate — galat ho to toko + samjhao, dialog khula rahe
                val err = CardFlow.validateField(key, nv)
                if (err != null && nv.isNotEmpty()) {
                    errTv.text = "❌ $err"
                    errTv.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                if (nv.isEmpty()) {
                    errTv.text = "❌ Khaali hai — kuch likho ya 🗑️ se hatao"
                    errTv.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                dlg.dismiss()
                patchOneField(key, nv, nt)
            }
        }
        dlg.show()
    }

    private fun patchOneField(key: String, value: String, tag: String) {
        toast("Save ho raha hai…")
        Thread({
            val details = JSONObject()
                .put(key, JSONObject().put("value", value).put("tag", tag))
            val res = try { AgentApi.patchCard(context, cardId, cardToken, details) }
            catch (_: Exception) { AgentApi.ApiResult(-1, null) }
            post {
                if (res.code in 200..299) {
                    toast("✓ Save ho gaya (सहेजा गया)")
                    load()
                } else if (res.code == 401 || res.code == 403) {
                    toast("🔒 Session khatm — PIN se dobara kholo")
                    onBack()
                } else {
                    toast("⚠️ Save nahi hua — dobara try karo")
                }
            }
        }, "fm-card-patch").start()
    }

    private fun showAddDetailDialog() {
        val act = context as? Activity ?: return
        val keys = DetailExtractor.orderedKeys() + listOf("khata", "khesra", "mauza")
        // pehle se maujood keys hatao
        val avail = keys.filter { k ->
            try { !detailsData.has(k) } catch (_: Exception) { true }
        }
        if (avail.isEmpty()) {
            toast("Sab fields pehle se hain — maujooda ko tap karke edit karo")
            return
        }
        val availLabels = avail.map { DetailExtractor.label(it) }.toTypedArray()
        var checked = 0
        val dlg = AlertDialog.Builder(act)
            .setTitle("➕ Detail jodo (विवरण जोड़ें)")
            .setSingleChoiceItems(availLabels, 0) { _, w -> checked = w }
            .setPositiveButton("💾 Save (सहेजें)", null)
            .setNegativeButton("Radd karo", null)
            .create()
        dlg.setOnShowListener {
            // Pehle field chuno, phir value/tag alag dialog me.
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dlg.dismiss()
                val key = avail[checked]
                askValueAndTag(act, key) { v, t -> patchOneField(key, v, t) }
            }
        }
        dlg.show()
    }

    private fun askValueAndTag(
        act: Activity,
        key: String,
        onSave: (value: String, tag: String) -> Unit
    ) {
        val layout = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        layout.addView(TextView(act).apply {
            text = DetailExtractor.label(key)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
        })
        val valEt = EditText(act).apply {
            hint = "Value likho"
            textSize = 16f
            setTextColor(Color.parseColor("#202124"))
        }
        layout.addView(valEt)
        val tagEt = EditText(act).apply {
            hint = "tag (optional) — jaise zamin, job"
            textSize = 16f
            setTextColor(Color.parseColor("#202124"))
        }
        layout.addView(tagEt)
        val errTv = TextView(act).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#C5221F"))
            visibility = View.GONE
        }
        layout.addView(errTv)
        val d2 = AlertDialog.Builder(act)
            .setTitle("Value likho")
            .setView(layout)
            .setPositiveButton("💾 Save (सहेजें)", null)
            .setNegativeButton("Wapas", null)
            .create()
        d2.setOnShowListener {
            d2.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = valEt.text.toString().trim()
                val t = tagEt.text.toString().trim()
                val err = CardFlow.validateField(key, v)
                if (err != null && v.isNotEmpty()) {
                    errTv.text = "❌ $err"
                    errTv.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                if (v.isEmpty()) {
                    errTv.text = "❌ Value khaali hai"
                    errTv.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                d2.dismiss()
                onSave(v, t)
            }
        }
        d2.show()
    }

    /** D18: har delete par pehle confirmation popup. */
    private fun confirmDeleteDetail(key: String) {
        val act = context as? Activity ?: return
        AlertDialog.Builder(act)
            .setTitle("🗑️ Detail hatao? (हटाएं?)")
            .setMessage(
                "\"${DetailExtractor.label(key)}\" is card se hat jayega.\n" +
                    "Agent aage se is detail ko form me nahi bharega."
            )
            .setPositiveButton("🗑️ Hatao (हटाएं)") { d, _ ->
                d.dismiss()
                deleteDetail(key)
            }
            .setNegativeButton("Rakho (रखें)", null)
            .show()
    }

    private fun deleteDetail(key: String) {
        // Contract me field-delete ka alag endpoint nahi — PATCH me null
        // bhejkar hatane ki koshish; server merge me null ko delete mane.
        // (Server worker se confirm karna hai — conformance note.)
        toast("Hata raha hun…")
        Thread({
            val details = JSONObject().put(key, JSONObject.NULL)
            val res = try { AgentApi.patchCard(context, cardId, cardToken, details) }
            catch (_: Exception) { AgentApi.ApiResult(-1, null) }
            post {
                if (res.code in 200..299) {
                    toast("✓ Hata diya")
                    load()
                } else {
                    toast("⚠️ Hataya nahi gaya — dobara try karo")
                }
            }
        }, "fm-card-deldetail").start()
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

package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.formmitra.app.engine.FormApi
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TrainerRecorderActivity — v44 (ADMIN ONLY).
 *
 * Admin website operate karke dikhata hai; har page-navigation auto-capture
 * hota hai, admin "📝 Note jodo" se har step par explanation likhta hai.
 * Steps server par /api/app/trainer/sessions/[id]/steps me jate hain.
 * "⏹️ Band karo" par guide banane (finalize) ka option.
 *
 * Launch: MainActivity shouldOverrideUrlLoading "formmitra://trainer/record"
 * ko pakadkar is activity ko kholta hai (sirf isOwner par).
 */
class TrainerRecorderActivity : Activity() {

    private var sessionId: String = ""
    private var webView: WebView? = null
    private var stepCountView: TextView? = null
    private var infoView: TextView? = null

    private val pendingSteps = JSONArray()
    private var totalSteps = 0
    private var lastAutoUrl = ""
    private var lastAutoAt = 0L
    private var flushing = false

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra("session_id") ?: ""
        if (sessionId.isEmpty()) {
            toast("Session nahi mili")
            finish()
            return
        }

        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FAF6EE"))
        }

        // Top bar: REC + info + steps
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B5A41"))
            setPadding(pad, pad, pad, pad)
        }
        val recRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recRow.addView(TextView(this).apply {
            text = "⏺️ REC"
            setTextColor(Color.parseColor("#FF6B6B"))
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
        })
        stepCountView = TextView(this).apply {
            text = "0 steps"
            setTextColor(Color.WHITE)
            textSize = 14f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.END
            layoutParams = lp
        }
        // spacer
        recRow.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        recRow.addView(stepCountView)
        topBar.addView(recRow)
        infoView = TextView(this).apply {
            text = "Session load ho rahi…"
            setTextColor(Color.parseColor("#FFE9A8"))
            textSize = 13f
        }
        topBar.addView(infoView)
        root.addView(topBar)

        // WebView
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // Desktop mode (automation jaisa)
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    autoCapture(url, view.title)
                }
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest
                ): Boolean = false
            }
        }
        root.addView(webView)

        // Bottom bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0B5A41"))
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER
        }
        val noteBtn = Button(this).apply {
            text = "📝 Note jodo"
            setOnClickListener { askNote() }
        }
        val stopBtn = Button(this).apply {
            text = "⏹️ Band karo"
            setOnClickListener { stopRecording() }
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(6, 0, 6, 0)
        noteBtn.layoutParams = lp
        stopBtn.layoutParams = lp
        bottomBar.addView(noteBtn)
        bottomBar.addView(stopBtn)
        root.addView(bottomBar)

        setContentView(root)

        // Session detail lao (background thread)
        Thread {
            val s = FormApi.trainerSession(this, sessionId)
            runOnUiThread {
                if (s == null) {
                    toast("Session load nahi hui — net check karo")
                    finish()
                    return@runOnUiThread
                }
                val tags = s.optJSONObject("tags")
                val what = tags?.optString("what_work") ?: ""
                val url = s.optString("url")
                totalSteps = s.optJSONArray("steps")?.length() ?: 0
                updateStepCount()
                infoView?.text = "🎓 $what\n🌐 $url"
                if (url.isNotEmpty()) webView?.loadUrl(url)
                else toast("Session me URL nahi hai")
            }
        }.start()
    }

    /** Har page-load auto-capture (dedupe: same URL 3s me dobara nahi). */
    private fun autoCapture(url: String, title: String?) {
        val now = System.currentTimeMillis()
        if (url == lastAutoUrl && now - lastAutoAt < 3000) return
        lastAutoUrl = url
        lastAutoAt = now
        addStep("khola", url, title, null)
    }

    /** Admin explanation wala note dialog. */
    private fun askNote() {
        val input = EditText(this).apply {
            hint = "Ye step kya tha / kyun kiya — short me likho"
        }
        AlertDialog.Builder(this)
            .setTitle("📝 Step note")
            .setView(input)
            .setPositiveButton("Jodo") { _, _ ->
                val note = input.text.toString().trim()
                val wv = webView
                addStep("note", wv?.url ?: "", wv?.title, note.ifEmpty { null })
                toast("Note jud gaya")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addStep(action: String, url: String, title: String?, note: String?) {
        val o = JSONObject()
            .put("action", action)
            .put("url", url.take(1000))
            .put("title", (title ?: "").take(200))
            .put("at", dateFmt.format(Date()))
        if (!note.isNullOrEmpty()) o.put("note", note.take(500))
        pendingSteps.put(o)
        totalSteps++
        runOnUiThread { updateStepCount() }
        // Har 5 steps par flush (net bachao + data suraksha)
        if (pendingSteps.length() >= 5) flushSteps(null)
    }

    private fun updateStepCount() {
        stepCountView?.text = "$totalSteps steps"
    }

    private fun flushSteps(done: (() -> Unit)?) {
        if (flushing || pendingSteps.length() == 0) {
            done?.invoke()
            return
        }
        flushing = true
        val batch = JSONArray()
        while (pendingSteps.length() > 0) batch.put(pendingSteps.remove(0))
        Thread {
            val ok = FormApi.trainerPostSteps(this, sessionId, batch)
            runOnUiThread {
                flushing = false
                if (!ok) {
                    // Wapas queue me daalo — data na khoye (order banaye rakho)
                    val restored = JSONArray()
                    for (i in 0 until batch.length()) restored.put(batch.get(i))
                    for (i in 0 until pendingSteps.length()) restored.put(pendingSteps.get(i))
                    while (pendingSteps.length() > 0) pendingSteps.remove(0)
                    for (i in 0 until restored.length()) pendingSteps.put(restored.get(i))
                    toast("Steps bhejne me dikkat — dobara try hoga")
                }
                done?.invoke()
            }
        }.start()
    }

    private fun stopRecording() {
        flushSteps {
            AlertDialog.Builder(this)
                .setTitle("⏹️ Recording band karein?")
                .setMessage("$totalSteps steps capture hue. Ab guide banani hai?")
                .setPositiveButton("✅ Guide banao") { _, _ -> finalize() }
                .setNegativeButton("Sirf band karo") { _, _ -> finish() }
                .show()
        }
    }

    private fun finalize() {
        toast("Guide ban rahi…")
        Thread {
            val ok = FormApi.trainerFinalize(this, sessionId)
            runOnUiThread {
                if (ok) toast("✅ Guide ban gayi — agent ab trained hai")
                else toast("Guide nahi bani — Admin panel se dobara try karo")
                finish()
            }
        }.start()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        // App background me jaye to steps flush (data na khoye)
        flushSteps(null)
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}

package com.formmitra.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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
 * TrainerRecorderActivity — v45 (ADMIN ONLY).
 *
 * Admin website operate karke dikhata hai; recorder AUTOMATICALLY capture
 * karta hai:
 *  - page navigation ("goto")
 *  - taps ("tap": selector + element text/label + href)
 *  - typing ("type": field label + value; PASSWORD KABHI NAHI — masked)
 *  - selections ("select": dropdown me chuna option)
 *  - scrolling ("scroll": direction)
 *  - har step par: CSS selector, element context (tag/text/label),
 *    DOM summary, auto-generated Hinglish explanation
 * Admin "📝 Note jodo" se har step par apni explanation bhi likh sakta hai.
 *
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
    private var jsBridge: TrainerJsBridge? = null

    private val pendingSteps = JSONArray()
    private val stepLock = Any()
    private var totalSteps = 0
    @Volatile private var lastAutoUrl = ""
    private var lastAutoAt = 0L
    private var flushing = false

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    /**
     * Page me inject hone wala JS — user interactions pakad ke
     * window.FmTrainer.onAction(JSON) par bhejta hai.
     * (Kotlin raw string: '$' use nahi kiya taaki interpolation na ho.)
     */
    private val TRAINER_JS = """
(function(){
if(window.__fmTrainerHooked) return; window.__fmTrainerHooked=true;
function txt(el,n){ try{ return (el.innerText||el.textContent||'').replace(/\s+/g,' ').trim().slice(0,n||80);}catch(e){ return ''; } }
function cssPath(el){
  var parts=[]; var n=el;
  while(n && n.nodeType===1 && parts.length<4){
    var tag=n.tagName.toLowerCase(); var s=tag;
    if(n.id && /^[A-Za-z][\w\-\:\.]*$/.test(n.id)){ parts.unshift(tag+'#'+n.id); break; }
    var sib=n, i=1;
    while((sib=sib.previousElementSibling)!=null){ if(sib.tagName===n.tagName) i++; }
    s+=':nth-of-type('+i+')';
    parts.unshift(s); n=n.parentElement;
  }
  return parts.join(' > ');
}
function labelOf(el){
  try{
    var al=el.getAttribute('aria-label'); if(al) return al.trim().slice(0,80);
    if(el.id){ var lb=document.querySelector('label[for="'+el.id+'"]'); if(lb) return txt(lb,80); }
    var p=el.closest('label'); if(p) return txt(p,80);
    if(el.placeholder) return el.placeholder.trim().slice(0,80);
    if(el.name) return el.name.trim().slice(0,80);
    if(el.alt) return el.alt.trim().slice(0,80);
    if(el.title) return el.title.trim().slice(0,80);
  }catch(e){}
  return '';
}
function domSum(){
  var h=[]; try{
    var hs=document.querySelectorAll('h1,h2,h3');
    for(var i=0;i<hs.length&&h.length<4;i++){ var t=txt(hs[i],60); if(t) h.push(t); }
  }catch(e){}
  return { t: document.title||'', f: document.forms.length,
    i: document.querySelectorAll('input,textarea,select').length,
    b: document.querySelectorAll('button,[role="button"],input[type="submit"],input[type="button"]').length,
    l: document.querySelectorAll('a[href]').length, h: h };
}
function post(o){
  try{
    o.url=location.href; o.title=document.title||'';
    o.dom=domSum();
    window.FmTrainer.onAction(JSON.stringify(o));
  }catch(e){}
}
function interactive(el){
  if(!el || el.nodeType!==1) return null;
  return el.closest('a,button,input,select,textarea,[role="button"],[onclick],summary');
}
document.addEventListener('click', function(e){
  var el=interactive(e.target); if(!el) return;
  var tag=el.tagName.toLowerCase();
  if(tag==='input'){ var tp0=(el.type||'').toLowerCase(); if(tp0==='checkbox'||tp0==='radio') return; }
  post({ action:'tap', selector:cssPath(el), tag:tag,
    text: txt(el,80), label: labelOf(el) || txt(el,80),
    href: (tag==='a'&&el.href)?String(el.href).slice(0,300):'' });
}, true);
document.addEventListener('change', function(e){
  var el=e.target; if(!el || el.nodeType!==1) return;
  var tag=el.tagName.toLowerCase();
  if(tag==='select'){
    var opt=el.options[el.selectedIndex];
    post({ action:'select', selector:cssPath(el), tag:tag,
      label:labelOf(el), value: opt?txt(opt,80):'' });
  } else if(tag==='input'||tag==='textarea'){
    var tp=(el.type||'text').toLowerCase();
    if(tp==='password'){
      post({ action:'type', selector:cssPath(el), tag:tag, inputType:tp,
        label:labelOf(el), masked:true });
    } else if(tp==='checkbox'||tp==='radio'){
      post({ action:'tap', selector:cssPath(el), tag:tag, inputType:tp,
        label:labelOf(el), text:(el.checked?'chuna':'hataya') });
    } else if(tp!=='submit'&&tp!=='button'&&tp!=='hidden'&&tp!=='file'){
      post({ action:'type', selector:cssPath(el), tag:tag, inputType:tp,
        label:labelOf(el), value:String(el.value||'').slice(0,120) });
    }
  }
}, true);
var lastY=(window.scrollY||0), scrollT=null;
window.addEventListener('scroll', function(){
  if(scrollT) clearTimeout(scrollT);
  scrollT=setTimeout(function(){
    var y=(window.scrollY||0); var d=y-lastY; lastY=y;
    if(Math.abs(d)>200) post({ action:'scroll', direction:d>0?'down':'up', y:Math.round(y) });
  }, 900);
}, {passive:true});
})();
""".trimIndent()

    /** JS → Kotlin bridge (JavaBridge thread par chalta hai — UI thread NAHI). */
    inner class TrainerJsBridge {
        @JavascriptInterface
        fun onAction(json: String) {
            try {
                handleRecordedAction(JSONObject(json))
            } catch (_: Exception) { }
        }
    }

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
        jsBridge = TrainerJsBridge()
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
            addJavascriptInterface(jsBridge!!, "FmTrainer")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    autoCapture(url, view.title)
                    // Recorder hooks har page par (iframe me bhi — apna window)
                    try {
                        view.evaluateJavascript(TRAINER_JS, null)
                    } catch (_: Exception) { }
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
        // v45: goto (replayable action) — pehle wala Hindi action naam pattern me drop hota tha.
        val extra = JSONObject()
            .put("auto_explanation", "Page khola: ${(title ?: "").take(80)}".take(300))
        addStep("goto", url, title, null, extra)
    }

    /** JS bridge se aaya user action → step banao. (Kisi bhi thread se.) */
    private fun handleRecordedAction(o: JSONObject) {
        val action = o.optString("action", "")
        if (action != "tap" && action != "type" && action != "select" && action != "scroll") return
        val url = o.optString("url", "").ifEmpty { lastAutoUrl }
        if (url.isEmpty()) return
        val title = o.optString("title", "")
        val extra = JSONObject()
            .put("selector", o.optString("selector", "").take(300))
            .put("element_tag", o.optString("tag", "").take(20))
            .put("element_text", o.optString("text", "").take(200))
            .put("element_label", o.optString("label", "").take(200))
            .put("auto_explanation", autoExplain(action, o).take(300))
        val masked = o.optBoolean("masked", false)
        val v = o.optString("value", "")
        // Password KABHI record nahi — masked ho to value khaali.
        if (!masked && v.isNotEmpty()) extra.put("value", v.take(120))
        val href = o.optString("href", "")
        val note = if (href.isNotEmpty()) "link: ${href.take(300)}" else null
        val ds = domSummaryText(o.optJSONObject("dom"))
        if (ds.isNotEmpty()) extra.put("dom_summary", ds)
        addStep(action, url, title, note, extra)
    }

    /** Action-specific 1-line Hinglish explanation (auto). */
    private fun autoExplain(action: String, o: JSONObject): String {
        val label = o.optString("label", "").ifEmpty { o.optString("text", "") }
        val tag = o.optString("tag", "")
        return when (action) {
            "tap" -> {
                val what = label.ifEmpty { tag.ifEmpty { "element" } }
                "‘$what’ par tap kiya"
            }
            "type" -> {
                val what = label.ifEmpty { "field" }
                if (o.optBoolean("masked", false)) "‘$what’ me password bhara (value record nahi hui)"
                else "‘$what’ me ‘${o.optString("value", "")}’ likha"
            }
            "select" -> "‘${label.ifEmpty { "dropdown" }}’ me ‘${o.optString("value", "")}’ chuna"
            "scroll" -> if (o.optString("direction", "") == "up") "Upar scroll kiya" else "Neeche scroll kiya"
            else -> action
        }
    }

    /** JS ke dom object se compact readable summary. */
    private fun domSummaryText(dom: JSONObject?): String {
        if (dom == null) return ""
        val sb = StringBuilder()
        val t = dom.optString("t", "")
        if (t.isNotEmpty()) sb.append(t.take(120)).append(" | ")
        sb.append("forms:").append(dom.optInt("f", 0))
            .append(" inputs:").append(dom.optInt("i", 0))
            .append(" buttons:").append(dom.optInt("b", 0))
            .append(" links:").append(dom.optInt("l", 0))
        val h = dom.optJSONArray("h")
        if (h != null && h.length() > 0) {
            sb.append(" | headings: ")
            for (i in 0 until h.length()) {
                if (i > 0) sb.append("; ")
                sb.append(h.optString(i, ""))
            }
        }
        return sb.toString().take(2000)
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

    /** Step queue me dalo — thread-safe (JS bridge background thread se bhi aata hai). */
    private fun addStep(
        action: String,
        url: String,
        title: String?,
        note: String?,
        extra: JSONObject? = null
    ) {
        val o = JSONObject()
            .put("action", action)
            .put("url", url.take(1000))
            .put("title", (title ?: "").take(200))
        if (!note.isNullOrEmpty()) o.put("note", note.take(500))
        if (extra != null) {
            val keys = extra.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                o.put(k, extra.get(k))
            }
        }
        var needFlush = false
        synchronized(stepLock) {
            // dateFmt thread-unsafe hai — lock ke andar format karo.
            o.put("at", dateFmt.format(Date()))
            pendingSteps.put(o)
            totalSteps++
            if (pendingSteps.length() >= 5 && !flushing) needFlush = true
        }
        runOnUiThread { updateStepCount() }
        // Har 5 steps par flush (net bachao + data suraksha)
        if (needFlush) flushSteps(null)
    }

    private fun updateStepCount() {
        stepCountView?.text = "$totalSteps steps"
    }

    /** Steps server bhejo — kisi bhi thread se safe. */
    private fun flushSteps(done: (() -> Unit)?) {
        val batch: JSONArray? = synchronized(stepLock) {
            if (flushing || pendingSteps.length() == 0) {
                null
            } else {
                flushing = true
                JSONArray().also { b ->
                    while (pendingSteps.length() > 0) b.put(pendingSteps.remove(0))
                }
            }
        }
        if (batch == null) {
            done?.invoke()
            return
        }
        Thread {
            val ok = FormApi.trainerPostSteps(this, sessionId, batch)
            synchronized(stepLock) {
                flushing = false
                if (!ok) {
                    // Wapas queue me daalo — data na khoye (order banaye rakho)
                    val restored = JSONArray()
                    for (i in 0 until batch.length()) restored.put(batch.get(i))
                    for (i in 0 until pendingSteps.length()) restored.put(pendingSteps.get(i))
                    while (pendingSteps.length() > 0) pendingSteps.remove(0)
                    for (i in 0 until restored.length()) pendingSteps.put(restored.get(i))
                }
            }
            runOnUiThread {
                if (!ok) toast("Steps bhejne me dikkat — dobara try hoga")
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
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onPause() {
        super.onPause()
        // App background me jaye to steps flush (data na khoye)
        flushSteps(null)
    }

    override fun onDestroy() {
        try {
            webView?.removeJavascriptInterface("FmTrainer")
        } catch (_: Exception) { }
        webView?.destroy()
        webView = null
        jsBridge = null
        super.onDestroy()
    }
}

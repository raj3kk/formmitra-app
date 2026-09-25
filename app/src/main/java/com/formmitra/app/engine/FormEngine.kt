package com.formmitra.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * FormEngine — FormMitra form-filling automation.
 *
 * Dedicated HandlerThread + hidden WebView, MainActivity ke browsing
 * WebView se COMPLETELY alag (automation user ko kabhi disturb nahi karta).
 *
 * Safety (hard rules):
 *  - Payment/purchase-looking content = HARD VETO → status "vetoed".
 *    Check hota hai: task name + target_url (run se pehle), har step ka
 *    text blob, aur har step se pehle live page ka URL + title + body text.
 *  - CAPTCHA: AI analyze karta hai (type + position), engine khud solve
 *    karta hai — max 3 attempts, uske baad run "needs_user" pe rukta hai
 *    (user khud karke "dobara chalao" dabata hai). Kabhi bypass/fake nahi.
 *
 * Step vocabulary (server contract):
 *  goto, fill, select, toggle, press, click, wait_for_element,
 *  wait_for_text, wait_for_navigation, screenshot, captcha_detect,
 *  captcha_solve, back, forward, upload.
 */
class FormEngine(private val appContext: Context) {

    class VetoException(msg: String) : Exception(msg)
    class NeedsAdminException(msg: String) : Exception(msg)

    data class RunResult(
        val status: String,          // done | failed | vetoed | needs_admin
        val summary: String,
        val stepResults: JSONArray = JSONArray()
    )

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var webView: WebView? = null

    /**
     * Assisted payment verify hone ke baad AgentLoop ise true karta hai.
     * Uske baad: live-page veto + goto-URL veto suppress (success/receipt
     * page par "payment" text hota hai — dobara prompt nahi chahiye).
     * Step-blob veto (AI ka payment action: card bharna, "Pay Now" dabana)
     * KABHI suppress nahi hota — doosri payment hamesha vetoed rahegi.
     */
    @Volatile var paymentVerifiedOnce: Boolean = false

    /** Engine thread start + hidden WebView. Blocking; caller background pe ho. */
    fun start() {
        if (thread != null) return
        val t = HandlerThread("formmitra-engine").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        val latch = CountDownLatch(1)
        handler!!.post {
            try {
                val wv = WebView(appContext)
                with(wv.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                }
                // session cookies MainActivity ke WebView se shared hain
                // (CookieManager process-global hai)
                wv.webViewClient = WebViewClient()
                // file upload: <input type=file> click par system picker NAHI —
                // upload step ka pending file auto-supply hota hai (deterministic)
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        view: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        return handleFileChooser(filePathCallback)
                    }
                }
                wv.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        1080, android.view.View.MeasureSpec.EXACTLY
                    ),
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        1920, android.view.View.MeasureSpec.EXACTLY
                    )
                )
                wv.layout(0, 0, 1080, 1920)
                webView = wv
            } catch (_: Exception) {
            }
            latch.countDown()
        }
        latch.await(30, TimeUnit.SECONDS)
        if (webView == null) throw Exception("FormEngine WebView create failed")
    }

    fun stop() {
        try {
            val h = handler
            if (h != null) {
                val latch = CountDownLatch(1)
                h.post {
                    try { webView?.destroy() } catch (_: Exception) { }
                    latch.countDown()
                }
                latch.await(5, TimeUnit.SECONDS)
            }
        } catch (_: Exception) { }
        try { thread?.quitSafely() } catch (_: Exception) { }
        thread = null
        handler = null
        webView = null
    }

    // ---------------- AI agent loop (Phase 2) ----------------
    //
    // AgentLoop inhi public methods ko use karta hai. Caller ko pehle start()
    // karna hoga (background thread pe — blocking calls hain).

    /**
     * AI agent loop ke liye single step chalao.
     * runTask jaisi safety: step blob veto + live page scan + per-step timeout.
     * Step JSON shape: {type, url, selector{mode,value}, text, option, state,
     *                   key, timeout_s, seconds, label} (StepParser contract).
     */
    @Throws(Exception::class)
    fun runAgentStep(stepJson: JSONObject): JSONObject {
        // "upload" StepParser ke bahar handle hota hai (doc/path params
        // StepSpec me nahi hain) — raw JSON se seedha.
        if (stepJson.optString("type", "").trim() == "upload") {
            return runUploadStep(stepJson)
        }
        val s = StepParser.parse(jsonToMap(stepJson))
        // Step-blob veto: AI ka payment action KABHI allow nahi — koi bypass
        // nahi (v14: paymentVerifiedOnce wala goto-skip hataya; action-level
        // veto hamesha chalta hai).
        VetoCheck.find(StepParser.vetoBlob(s))?.let {
            throw VetoException("agent step ('${s.type}') me payment keyword '$it'")
        }
        // Live-page veto: payment verify ke baad suppress (receipt page par
        // "payment successful" text hota hai).
        if (!paymentVerifiedOnce) checkLivePageVeto()
        return runStepWithTimeout(s)
    }

    /** upload step: apna timeout path (StepSpec ke bahar). */
    @Throws(Exception::class)
    private fun runUploadStep(raw: JSONObject): JSONObject {
        // Veto locally-selected doc naam par bhi (payment keyword scan)
        val vetoName = raw.optString("doc", "").trim()
            .ifEmpty { selectedDoc?.trim() ?: "" }
        VetoCheck.find(
            "$vetoName ${raw.optString("path", "")}"
        )?.let {
            throw VetoException("upload step me payment keyword '$it'")
        }
        if (!paymentVerifiedOnce) checkLivePageVeto()
        val exec = Executors.newSingleThreadExecutor()
        return try {
            val fut = exec.submit<JSONObject> { executeUpload(raw) }
            try {
                fut.get(90, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                fut.cancel(true)
                throw Exception("step 'upload' timeout (90s)")
            }
        } finally {
            exec.shutdownNow()
        }
    }

    /** AI agent loop ke liye DOM snapshot: fields + buttons + page text + url/title. */
    fun domSnapshot(): JSONObject {
        return unwrapJsObject(evalJsSync(SNAPSHOT_JS))
    }

    /** AI agent loop ke liye CAPTCHA detect (public wrapper). */
    fun detectCaptcha(): JSONObject = captchaDetect()

    /**
     * DOM snapshot JS — fields: tag/type/label/placeholder/aria/id/name/rect
     * (max 60); buttons: text + rect (max 30); page text excerpt 1500 chars.
     * Same-origin iframes cover hote hain (cross-origin try/catch me skip).
     */
    private val SNAPSHOT_JS = """(function(){
      function rect(el){
        try{
          var r=el.getBoundingClientRect();
          return {x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)};
        }catch(e){ return null; }
      }
      function labelText(el){
        try{
          var id=el.getAttribute('id');
          if(id){
            var lb=document.querySelector('label[for="'+id+'"]');
            if(lb && lb.innerText) return lb.innerText.trim().slice(0,80);
          }
        }catch(e){}
        try{
          var p=el.closest('label');
          if(p && p.innerText) return p.innerText.trim().slice(0,80);
        }catch(e){}
        return '';
      }
      function eachDoc(fn){
        var docs=[document];
        try{
          Array.from(document.querySelectorAll('iframe')).forEach(function(f){
            try{ if(f.contentDocument) docs.push(f.contentDocument); }catch(e){}
          });
        }catch(e){}
        docs.forEach(fn);
      }
      var fields=[];
      eachDoc(function(doc){
        var els;
        try{ els=doc.querySelectorAll('input,select,textarea'); }catch(e){ return; }
        Array.from(els).forEach(function(el){
          if(fields.length>=60) return;
          var t='';
          try{ t=el.getAttribute('type')||''; }catch(e){}
          if(t==='hidden'||t==='submit'||t==='button'||t==='image') return;
          var tag='', ph='', ar='', id='', nm='';
          try{ tag=(el.tagName||'').toLowerCase(); }catch(e){}
          try{ ph=(el.getAttribute('placeholder')||'').slice(0,80); }catch(e){}
          try{ ar=(el.getAttribute('aria-label')||'').slice(0,80); }catch(e){}
          try{ id=(el.getAttribute('id')||'').slice(0,60); }catch(e){}
          try{ nm=(el.getAttribute('name')||'').slice(0,60); }catch(e){}
          var val='';
          try{ val = (t==='password') ? '' : ((el.value||'')+'').slice(0,120); }catch(e){}
          fields.push({tag:tag,type:t,label:labelText(el),placeholder:ph,aria:ar,id:id,name:nm,rect:rect(el),value:val});
        });
      });
      var buttons=[];
      eachDoc(function(doc){
        var els;
        try{ els=doc.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]'); }catch(e){ return; }
        Array.from(els).forEach(function(el){
          if(buttons.length>=30) return;
          var txt='';
          try{ txt=((el.innerText||el.getAttribute('value')||'')+'').trim().slice(0,60); }catch(e){}
          if(!txt) return;
          buttons.push({text:txt,rect:rect(el)});
        });
      });
      var bodyText='';
      try{ bodyText=(document.body?document.body.innerText:'').replace(/\s+/g,' ').slice(0,1500); }catch(e){}
      var href='', ttl='';
      try{ href=location.href||''; ttl=document.title||''; }catch(e){}
      return JSON.stringify({url:href,title:ttl,fields:fields,buttons:buttons,page_text:bodyText});
    })()"""

    /**
     * Task chalao (blocking). onProgress(step1Based, total) har report
     * cadence pe call hota hai — caller server ko progress POST karta hai.
     */
    fun runTask(task: JSONObject, onProgress: (Int, Int) -> Unit): RunResult {
        val name = task.optString("name", "form")
        val targetUrl = task.optString("target_url", "")
        val runId = task.optString("run_id", "")
        val stepsJson = task.optJSONArray("steps") ?: JSONArray()

        // Pre-run veto: task name + target URL
        val preBlob = "$name $targetUrl"
        VetoCheck.find(preBlob)?.let {
            return RunResult("vetoed", "PAYMENT VETO (start): '$it' mila — '$name'", JSONArray())
        }

        val steps = ArrayList<StepSpec>()
        for (i in 0 until stepsJson.length()) {
            val raw = stepsJson.optJSONObject(i) ?: continue
            steps.add(StepParser.parse(jsonToMap(raw)))
        }

        start()
        activeRunId = runId
        val results = JSONArray()
        return try {
            if (targetUrl.isNotEmpty()) {
                checkPageVetoTarget(targetUrl)
                navigate(targetUrl)
                checkLivePageVeto()
            }
            for (i in steps.indices) {
                val s = steps[i]
                // har step se pehle: step blob + live page scan
                VetoCheck.find(StepParser.vetoBlob(s))?.let {
                    throw VetoException("step ${i + 1} ('${s.type}') me payment keyword '$it'")
                }
                checkLivePageVeto()
                val detail = runStepWithTimeout(s)
                results.put(
                    JSONObject()
                        .put("index", i + 1)
                        .put("type", s.type)
                        .put("ok", true)
                        .put("detail", detail)
                )
                if (RunPolicy.shouldReportProgress(i + 1, steps.size)) {
                    onProgress(i + 1, steps.size)
                }
            }
            RunResult("done", "form complete: ${steps.size} steps ('$name')", results)
        } catch (e: VetoException) {
            RunResult("vetoed", "PAYMENT VETO: ${e.message}", results)
        } catch (e: NeedsAdminException) {
            RunResult("needs_admin", "CAPTCHA: ${e.message}", results)
        } catch (e: Exception) {
            RunResult("failed", "step error: ${e.message}", results)
        } finally {
            activeRunId = ""
            // session cookies persist karo (login bana rahe)
            try { CookieManager.getInstance().flush() } catch (_: Exception) { }
            stop()
        }
    }

    // ---------------- veto ----------------

    private fun checkPageVetoTarget(url: String) {
        VetoCheck.find(url)?.let {
            throw VetoException("target_url me payment keyword '$it'")
        }
    }

    /** Live page ka URL + title + body text scan — har step se pehle. */
    private fun checkLivePageVeto() {
        val raw = evalJsSync(
            """(function(){
              try{
                return JSON.stringify({
                  href: location.href||'',
                  title: document.title||'',
                  text: (document.body?document.body.innerText:'').slice(0,4000)
                });
              }catch(e){ return JSON.stringify({href:'',title:'',text:''}); }
            })()"""
        )
        val obj = unwrapJsObject(raw)
        val blob = obj.optString("href", "") + " " +
            obj.optString("title", "") + " " +
            obj.optString("text", "")
        VetoCheck.find(blob)?.let {
            throw VetoException("live page pe payment keyword '$it' — page rok diya")
        }
    }

    // ---------------- step dispatch (per-step timeout ke saath) ----------------

    private fun runStepWithTimeout(s: StepSpec): JSONObject {
        val exec = Executors.newSingleThreadExecutor()
        return try {
            val fut = exec.submit<JSONObject> { executeStep(s) }
            try {
                fut.get(s.timeoutS, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                fut.cancel(true)
                throw Exception("step '${s.type}' timeout (${s.timeoutS}s)")
            }
        } finally {
            exec.shutdownNow()
        }
    }

    private fun executeStep(s: StepSpec): JSONObject {
        return when (s.type) {
            "goto" -> {
                checkPageVetoTarget(s.url)
                navigate(s.url)
                checkLivePageVeto()
                JSONObject().put("url", s.url)
            }
            "fill" -> fillField(s)
            "select" -> selectOption(s)
            "toggle" -> toggleCheck(s)
            "press" -> pressKey(s)
            "click" -> clickEl(s)
            "wait_for_element" -> {
                waitForElement(s); JSONObject().put("waited_for", "element")
            }
            "wait_for_text" -> {
                waitForText(s); JSONObject().put("waited_for", "text")
            }
            "wait_for_navigation" -> {
                waitForNavigation(s); JSONObject().put("waited_for", "navigation")
            }
            "screenshot" -> {
                val b64 = capturePngBase64()
                JSONObject().put("label", s.label).put("bytes", b64.length)
                    .put("has_image", b64.isNotEmpty())
            }
            "captcha_detect" -> captchaDetect()
            "captcha_solve" -> captchaSolve(s)
            "back" -> { goBack(); JSONObject().put("nav", "back") }
            "forward" -> { goForward(); JSONObject().put("nav", "forward") }
            "scroll" -> scrollPage(s)
            else -> throw Exception("unsupported step: '${s.type}'")
        }
    }

    // ---------------- engine-thread primitives ----------------

    private fun evalJsSync(js: String, timeoutMs: Long = 30_000): String {
        val latch = CountDownLatch(1)
        var out = "null"
        handler!!.post {
            try {
                webView!!.evaluateJavascript(js) { v ->
                    out = v ?: "null"
                    latch.countDown()
                }
            } catch (_: Exception) { latch.countDown() }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out
    }

    private fun unwrapJsString(raw: String): String {
        return try {
            val v = org.json.JSONTokener(raw.trim()).nextValue()
            if (v is String) v else raw
        } catch (_: Exception) {
            raw
        }
    }

    /**
     * Current page ka URL (L1-UPGRADE: login auto-fill ke liye domain
     * nikaalne me kaam aata hai).
     */
    fun pageUrl(): String = try {
        unwrapJsString(evalJsSync("location.href", 10_000))
    } catch (_: Exception) { "" }

    private fun unwrapJsObject(raw: String): JSONObject {
        return try {
            JSONObject(unwrapJsString(raw))
        } catch (_: Exception) {
            JSONObject()
        }
    }

    /** JSONObject → Map (recursive). AgentLoop bhi istemal karta hai. */
    fun jsonToMap(o: JSONObject): Map<String, Any?> {
        val m = HashMap<String, Any?>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.opt(k)
            m[k] = when (v) {
                is JSONObject -> jsonToMap(v)
                is JSONArray -> v
                JSONObject.NULL -> null
                else -> v
            }
        }
        return m
    }

    // ---------------- element finder ----------------
    //
    // Selector modes: css, id, name, aria, text, label, placeholder, hint.
    // Traversal: same-origin iframes (cross-origin try/catch me SKIP —
    // contentDocument padhne pe SecurityError aata hai) + shadow DOM
    // piercing (shadowRoot mile to recurse).

    private fun finderJs(mode: String, value: String): String {
        val m = if (mode in setOf(
                "css", "id", "name", "aria", "text", "label", "placeholder", "hint"
            )
        ) mode else "css"
        val q = JSONObject.quote(value)
        return """(function(){
          var mode='$m', val=$q, needle=val.toLowerCase().trim();
          var docs=[document];
          try{
            Array.from(document.querySelectorAll('iframe')).forEach(function(f){
              try{ if(f.contentDocument) docs.push(f.contentDocument); }catch(e){}
            });
          }catch(e){}
          function* roots(doc){
            yield doc;
            var all;
            try{ all=doc.querySelectorAll('*'); }catch(e){ return; }
            for(var i=0;i<all.length;i++){
              var sr=all[i].shadowRoot;
              if(sr) yield* roots(sr);
            }
          }
          function findInRoots(fn){
            for(var d=0;d<docs.length;d++){
              var gen=roots(docs[d]); var it=gen.next();
              while(!it.done){ var el=fn(it.value); if(el) return el; it=gen.next(); }
            }
            return null;
          }
          function txt(t){ return (t||'').toLowerCase(); }
          function byLabel(root, nd){
            var labels;
            try{ labels=root.querySelectorAll('label'); }catch(x){ return null; }
            for(var i=0;i<labels.length;i++){
              var lb=labels[i];
              if(txt(lb.textContent).indexOf(nd)<0) continue;
              var forId=lb.getAttribute('for');
              if(forId){
                var t=null;
                try{ t=(root.getElementById?root.getElementById(forId):null)||document.getElementById(forId); }catch(x){}
                if(t) return t;
              }
              var inner=null;
              try{ inner=lb.querySelector('input,select,textarea'); }catch(x){}
              if(inner) return inner;
            }
            return null;
          }
          var el=null;
          if(mode==='css'){
            el=findInRoots(function(root){ try{ return root.querySelector(val); }catch(e){ return null; } });
          } else if(mode==='id'){
            el=findInRoots(function(root){
              var t=null;
              try{ t=root.getElementById?root.getElementById(val):null; }catch(x){}
              if(t) return t;
              try{ t=root.querySelector('[id="'+val+'"],[id*="'+val+'"]'); }catch(x){}
              return t;
            });
          } else if(mode==='name'){
            el=findInRoots(function(root){
              try{ return root.querySelector('[name="'+val+'"]'); }catch(e){ return null; }
            });
          } else if(mode==='aria'){
            el=findInRoots(function(root){
              var all;
              try{ all=root.querySelectorAll('[aria-label]'); }catch(x){ return null; }
              for(var i=0;i<all.length;i++){ if((all[i].getAttribute('aria-label')||'')===val) return all[i]; }
              for(var i=0;i<all.length;i++){ if(txt(all[i].getAttribute('aria-label')).indexOf(needle)>=0) return all[i]; }
              return null;
            });
          } else if(mode==='label'){
            el=findInRoots(function(root){ return byLabel(root,needle); });
          } else if(mode==='placeholder'){
            el=findInRoots(function(root){
              var all;
              try{ all=root.querySelectorAll('input,textarea'); }catch(x){ return null; }
              for(var i=0;i<all.length;i++){ if(txt(all[i].getAttribute('placeholder')).indexOf(needle)>=0) return all[i]; }
              return null;
            });
          } else if(mode==='hint'){
            el=findInRoots(function(root){
              var all;
              try{ all=root.querySelectorAll('input,textarea,select'); }catch(x){ return null; }
              for(var i=0;i<all.length;i++){ if(txt(all[i].getAttribute('aria-label')).indexOf(needle)>=0) return all[i]; }
              for(var i=0;i<all.length;i++){ if(txt(all[i].getAttribute('placeholder')).indexOf(needle)>=0) return all[i]; }
              return null;
            });
            if(!el) el=findInRoots(function(root){ return byLabel(root,needle); });
          } else {
            el=findInRoots(function(root){
              var all;
              try{ all=root.querySelectorAll('a,button,input,[role="button"],[aria-label],select,textarea,div[onclick],span[onclick]'); }catch(x){ return null; }
              for(var i=0;i<all.length;i++){ if((all[i].textContent||'').trim().toLowerCase()===needle) return all[i]; }
              for(var i=0;i<all.length;i++){ if(txt((all[i].textContent||'').trim()).indexOf(needle)>=0) return all[i]; }
              return null;
            });
          }
          return el;
        })()"""
    }

    // ---------------- navigation / primitives ----------------

    private fun navigate(url: String) {
        val latch = CountDownLatch(1)
        val wv = webView!!
        handler!!.post {
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    latch.countDown()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) latch.countDown()
                }
            }
            wv.loadUrl(url)
        }
        latch.await(45, TimeUnit.SECONDS)
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (evalJsSync("(function(){return document.readyState})()").trim('"') == "complete") break
            Thread.sleep(500)
        }
        Thread.sleep(1200)
    }

    /**
     * fill — keyboard-event-faithful typing + read-back verify + retries.
     * OTP boxes / autocomplete frameworks per-char keydown/keypress/keyup
     * sunte hain; native value setter + input/change events framework
     * bindings ko trigger karte hain.
     *
     * Result me verified:true/false — type ke BAAD JS se actual value wapas
     * padhi jaati hai; mismatch par retry (FILL_MAX_ATTEMPTS tak). Aakhiri
     * mismatch par throw NAHI — verified=false return hota hai taaki caller
     * (AgentLoop/LocalFallback) khud faisla le sake.
     */
    private fun fillField(s: StepSpec): JSONObject {
        val q = JSONObject.quote(s.text)
        val js = """(function(){
          var el=${finderJs(s.selectorMode, s.selectorValue)};
          if(!el) return JSON.stringify({status:'NOT_FOUND',value:''});
          try{ el.scrollIntoView({block:'center'}); }catch(e){}
          try{ el.focus(); }catch(e){}
          try{
            var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype :
                        el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
            var setter = Object.getOwnPropertyDescriptor(proto,'value').set
                     || Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value').set;
            if(setter) setter.call(el, $q); else el.value=$q;
          }catch(e){ try{ el.value=$q; }catch(x){} }
          try{
            var str=$q;
            for(var i=0;i<Math.min(str.length,200);i++){
              var ch=str.charAt(i);
              el.dispatchEvent(new KeyboardEvent('keydown',{key:ch,bubbles:true,cancelable:true}));
              el.dispatchEvent(new KeyboardEvent('keypress',{key:ch,bubbles:true,cancelable:true}));
              el.dispatchEvent(new KeyboardEvent('keyup',{key:ch,bubbles:true,cancelable:true}));
            }
          }catch(e){}
          el.dispatchEvent(new Event('input',{bubbles:true}));
          el.dispatchEvent(new Event('change',{bubbles:true}));
          var v='';
          try{ v=el.value||''; }catch(e){}
          return JSON.stringify({status:'FILLED',value:v});
        })()"""
        var lastActual = ""
        var attempts = 0
        var verified = false
        for (attempt in 1..RunPolicy.FILL_MAX_ATTEMPTS) {
            attempts = attempt
            val obj = unwrapJsObject(evalJsSync(js))
            if (obj.optString("status", "") != "FILLED") {
                throw Exception("fill: element nahi mila (${s.selectorMode}:${s.selectorValue})")
            }
            lastActual = obj.optString("value", "")
            if (RunPolicy.fillVerified(s.text, lastActual)) {
                verified = true
                break
            }
            Thread.sleep(600)
        }
        return JSONObject()
            .put("filled", verified)
            .put("verified", verified)
            .put("value", lastActual)
            .put("expected", s.text)
            .put("attempts", attempts)
    }

    /** select — native <select> (text/value match) ya custom div-dropdown. */
    private fun selectOption(s: StepSpec): JSONObject {
        val q = JSONObject.quote(s.option)
        val js1 = """(function(){
          var el=${finderJs(s.selectorMode, s.selectorValue)};
          if(!el) return JSON.stringify({status:'NOT_FOUND'});
          if((el.tagName||'').toLowerCase()==='select'){
            var nd=$q.toLowerCase(), pick=null, i;
            for(i=0;i<el.options.length;i++){
              var t=(el.options[i].text||'').toLowerCase(), v=(el.options[i].value||'').toLowerCase();
              if(t===nd||v===nd){ pick=el.options[i]; break; }
            }
            if(!pick){
              for(i=0;i<el.options.length;i++){
                if((el.options[i].text||'').toLowerCase().indexOf(nd)>=0){ pick=el.options[i]; break; }
              }
            }
            if(!pick) return JSON.stringify({status:'NO_OPTION'});
            el.value=pick.value;
            el.dispatchEvent(new Event('input',{bubbles:true}));
            el.dispatchEvent(new Event('change',{bubbles:true}));
            return JSON.stringify({status:'SELECTED',text:pick.text});
          }
          try{ el.scrollIntoView({block:'center'}); }catch(e){}
          el.click();
          return JSON.stringify({status:'OPENED'});
        })()"""
        when (unwrapJsObject(evalJsSync(js1)).optString("status", "")) {
            "SELECTED" -> return JSONObject().put("selected", s.option)
            "NOT_FOUND" -> throw Exception("select: element nahi mila (${s.selectorMode}:${s.selectorValue})")
            "NO_OPTION" -> throw Exception("select: option nahi mila: '${s.option}'")
        }
        // custom dropdown: option text pe click
        Thread.sleep(900)
        val js2 = """(function(){
          var opt=${finderJs("text", s.option)};
          if(!opt) return 'NO_OPTION';
          try{ opt.scrollIntoView({block:'center'}); }catch(e){}
          opt.click();
          return 'CLICKED_OPTION';
        })()"""
        if (evalJsSync(js2).trim('"') != "CLICKED_OPTION") {
            throw Exception("select: custom dropdown me option nahi mila: '${s.option}'")
        }
        Thread.sleep(800)
        return JSONObject().put("selected", s.option).put("custom_dropdown", true)
    }

    /** toggle — checkbox/radio: target state ke liye zaroorat ho tabhi click. */
    private fun toggleCheck(s: StepSpec): JSONObject {
        val js = """(function(){
          var el=${finderJs(s.selectorMode, s.selectorValue)};
          if(!el) return JSON.stringify({status:'NOT_FOUND'});
          var before=!!el.checked;
          var want = '${s.state}'==='on' ? true : ('${s.state}'==='off' ? false : !before);
          if(before!==want){
            try{ el.scrollIntoView({block:'center'}); }catch(e){}
            el.click();
          }
          return JSON.stringify({status:'TOGGLED',before:before,after:!!el.checked});
        })()"""
        val r = unwrapJsObject(evalJsSync(js))
        if (r.optString("status", "") != "TOGGLED") {
            throw Exception("toggle: element nahi mila (${s.selectorMode}:${s.selectorValue})")
        }
        Thread.sleep(500)
        return JSONObject()
            .put("before", r.optBoolean("before", false))
            .put("after", r.optBoolean("after", false))
            .put("requested", s.state)
    }

    /** press — keyboard events element pe (ya focused element/document pe). */
    private fun pressKey(s: StepSpec): JSONObject {
        val k = s.key.trim().lowercase()
        val pair = when (k) {
            "enter" -> "Enter" to "Enter"
            "tab" -> "Tab" to "Tab"
            "escape", "esc" -> "Escape" to "Escape"
            "space" -> " " to "Space"
            "backspace" -> "Backspace" to "Backspace"
            "arrowup" -> "ArrowUp" to "ArrowUp"
            "arrowdown" -> "ArrowDown" to "ArrowDown"
            "arrowleft" -> "ArrowLeft" to "ArrowLeft"
            "arrowright" -> "ArrowRight" to "ArrowRight"
            else -> s.key to s.key
        }
        val qk = JSONObject.quote(pair.first)
        val qc = JSONObject.quote(pair.second)
        val target = if (s.selectorValue.isNotEmpty())
            "var el=${finderJs(s.selectorMode, s.selectorValue)};"
        else
            "var el=document.activeElement||document.body;"
        val js = """(function(){
          $target
          if(!el) return 'NOT_FOUND';
          var kn=$qk, cd=$qc;
          ['keydown','keypress','keyup'].forEach(function(t){
            try{ el.dispatchEvent(new KeyboardEvent(t,{key:kn,code:cd,bubbles:true,cancelable:true})); }catch(e){}
          });
          try{ document.dispatchEvent(new KeyboardEvent('keydown',{key:kn,code:cd,bubbles:true,cancelable:true})); }catch(e){}
          return 'PRESSED';
        })()"""
        if (evalJsSync(js).trim('"') != "PRESSED") throw Exception("press: target nahi mila")
        Thread.sleep(700)
        return JSONObject().put("key", s.key)
    }

    private fun clickEl(s: StepSpec): JSONObject {
        val js = """(function(){
          var el=${finderJs(s.selectorMode, s.selectorValue)};
          if(!el) return 'NOT_FOUND';
          try{ el.scrollIntoView({block:'center'}); }catch(e){}
          el.click();
          return 'CLICKED';
        })()"""
        if (evalJsSync(js).trim('"') != "CLICKED") {
            throw Exception("click: element nahi mila (${s.selectorMode}:${s.selectorValue})")
        }
        Thread.sleep(1500)
        return JSONObject().put("clicked", true)
    }

    private fun waitForElement(s: StepSpec) {
        val js = """(function(){
          var el=${finderJs(s.selectorMode, s.selectorValue)};
          if(!el) return 'false';
          try{
            var r=el.getBoundingClientRect();
            return JSON.stringify(r.width>0&&r.height>0);
          }catch(e){ return 'true'; }
        })()"""
        val deadline = SystemClock.elapsedRealtime() + s.timeoutS * 1000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (evalJsSync(js).trim('"') == "true") return
            Thread.sleep(500)
        }
        throw Exception("wait_for_element: timeout ${s.timeoutS}s (${s.selectorMode}:${s.selectorValue})")
    }

    private fun waitForText(s: StepSpec) {
        val q = JSONObject.quote(s.text)
        val js = "JSON.stringify((document.body?document.body.innerText:'').indexOf($q)>=0)"
        val deadline = SystemClock.elapsedRealtime() + s.timeoutS * 1000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (evalJsSync(js).trim('"') == "true") return
            Thread.sleep(500)
        }
        throw Exception("wait_for_text: timeout ${s.timeoutS}s ('${s.text.take(60)}')")
    }

    private fun waitForNavigation(s: StepSpec) {
        val start = evalJsSync("JSON.stringify(location.href)")
        val deadline = SystemClock.elapsedRealtime() + s.timeoutS * 1000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (evalJsSync("JSON.stringify(location.href)") != start) {
                Thread.sleep(1200)
                return
            }
            Thread.sleep(500)
        }
        throw Exception("wait_for_navigation: timeout ${s.timeoutS}s (URL nahi badla)")
    }

    // ---------------- history nav ----------------

    /** back — WebView history back + settle wait. */
    private fun goBack() {
        val latch = CountDownLatch(1)
        handler!!.post {
            try {
                if (webView!!.canGoBack()) webView!!.goBack()
            } catch (_: Exception) { }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        Thread.sleep(1500)
    }

    /** scroll — L1: selector ho to element center me lao, nahi to page
     *  ek screen neeche scroll karo (lazy-load / lambe form ke liye). */
    private fun scrollPage(s: StepSpec): JSONObject {
        val js = if (s.selectorValue.isNotBlank()) {
            """(function(){
              var el=${finderJs(s.selectorMode.ifBlank { "css" }, s.selectorValue)};
              if(!el) return 'NOT_FOUND';
              try{ el.scrollIntoView({block:'center'}); }catch(e){}
              return 'SCROLLED';
            })()"""
        } else {
            """(function(){
              try{ window.scrollBy(0, Math.floor(window.innerHeight*0.8)); }catch(e){}
              return 'SCROLLED';
            })()"""
        }
        if (evalJsSync(js).trim('"') != "SCROLLED") {
            throw Exception("scroll: element nahi mila (${s.selectorMode}:${s.selectorValue})")
        }
        Thread.sleep(800)
        return JSONObject().put("scrolled", true)
    }

    /** forward — WebView history forward + settle wait. */
    private fun goForward() {
        val latch = CountDownLatch(1)
        handler!!.post {
            try {
                if (webView!!.canGoForward()) webView!!.goForward()
            } catch (_: Exception) { }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        Thread.sleep(1500)
    }

    // ---------------- screenshot ----------------

    /**
     * base64 PNG, downscaled, ~400KB cap.
     * 540px → 400px → 320px widths try karo; phir bhi zyada ho to JPEG 80.
     */
    fun capturePngBase64(): String {
        for (w in intArrayOf(540, 400, 320)) {
            val b64 = renderAtWidth(w, Bitmap.CompressFormat.PNG, 100)
            if (b64.isNotEmpty() && b64.length * 3 / 4 <= 400 * 1024) return b64
            if (b64.isNotEmpty() && w == 320) {
                val jpg = renderAtWidth(320, Bitmap.CompressFormat.JPEG, 80)
                return if (jpg.isNotEmpty()) jpg else b64
            }
            if (b64.isNotEmpty()) return b64 // smallest PNG attempt
        }
        return ""
    }

    private fun renderAtWidth(
        w: Int, fmt: Bitmap.CompressFormat, quality: Int
    ): String {
        val b = renderBitmap(w) ?: return ""
        return try {
            val out = ByteArrayOutputStream()
            b.compress(fmt, quality, out)
            b.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    /** WebView ko bitmap me render karo (screenshot + mirror dono isi se). */
    private fun renderBitmap(w: Int): Bitmap? {
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        handler!!.post {
            try {
                val wv = webView!!
                val h = (960 * wv.height / wv.width.coerceAtLeast(1)).coerceAtMost(1200)
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(b)
                canvas.scale(
                    w.toFloat() / wv.width.coerceAtLeast(1),
                    h.toFloat() / wv.height.coerceAtLeast(1)
                )
                wv.draw(canvas)
                bmp = b
            } catch (_: Exception) { }
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)
        return bmp
    }

    /**
     * Mirror screenshot — UI agent ke live view ke liye
     * File(cacheDir, "agent_mirror.png"). Best-effort, kabhi throw nahi.
     */
    fun writeMirrorPng(ctx: Context) {
        try {
            val bmp = renderBitmap(540) ?: return
            try {
                java.io.File(ctx.cacheDir, "agent_mirror.png").outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } finally {
                try { bmp.recycle() } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    // ---------------- captcha ----------------

    /**
     * Coordinate tap — page CSS pixels (getBoundingClientRect) ko view
     * pixels me badal ke WebView pe synthetic touch bhejo. Cross-origin
     * iframe (reCAPTCHA checkbox) me JS click kaam nahi karta, isliye ye.
     * UI thread pe dispatch hota hai; true = events bhej diye.
     */
    fun tapAt(xCss: Float, yCss: Float): Boolean {
        val h = handler ?: return false
        val latch = CountDownLatch(1)
        var sent = false
        h.post {
            try {
                val wv = webView ?: return@post
                val scale = wv.scale
                val vx = xCss * scale
                val vy = yCss * scale
                val now = android.os.SystemClock.uptimeMillis()
                val down = android.view.MotionEvent.obtain(
                    now, now,
                    android.view.MotionEvent.ACTION_DOWN, vx, vy, 0
                )
                val up = android.view.MotionEvent.obtain(
                    now, now + 80,
                    android.view.MotionEvent.ACTION_UP, vx, vy, 0
                )
                wv.dispatchTouchEvent(down)
                wv.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
                sent = true
            } catch (_: Exception) { }
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return sent
    }

    /**
     * v14 CAPTCHA protocol: AI ke 0-1000 normalized coords → CSS px tap.
     * Screenshot viewport ka linear scale hai, isliye mapping linear hai.
     */
    fun tapNormalized(x1000: Double, y1000: Double): Boolean {
        val wv = webView ?: return false
        val scale = try { wv.scale } catch (_: Exception) { 0f }
        if (scale <= 0f) return false
        val w = try { wv.width } catch (_: Exception) { 0 }
        val h = try { wv.height } catch (_: Exception) { 0 }
        if (w <= 0 || h <= 0) return false
        val xCss = (x1000.coerceIn(0.0, 1000.0) / 1000.0 * w / scale).toFloat()
        val yCss = (y1000.coerceIn(0.0, 1000.0) / 1000.0 * h / scale).toFloat()
        return tapAt(xCss, yCss)
    }

    /**
     * v14 CAPTCHA protocol: puzzle_slide ke liye normalized swipe
     * (down → moves → up).
     */
    fun swipeNormalized(x1: Double, y1: Double, x2: Double, y2: Double): Boolean {
        val h = handler ?: return false
        val latch = CountDownLatch(1)
        var sent = false
        h.post {
            try {
                val wv = webView ?: return@post
                val scale = wv.scale
                if (scale <= 0f) return@post
                fun cx(x: Double) = (x.coerceIn(0.0, 1000.0) / 1000.0 * wv.width / scale).toFloat()
                fun cy(y: Double) = (y.coerceIn(0.0, 1000.0) / 1000.0 * wv.height / scale).toFloat()
                val now = android.os.SystemClock.uptimeMillis()
                val down = android.view.MotionEvent.obtain(
                    now, now, android.view.MotionEvent.ACTION_DOWN, cx(x1), cy(y1), 0
                )
                wv.dispatchTouchEvent(down)
                // 10 interpolated moves (~300ms) — slider pakad ke kheenchna
                val steps = 10
                for (i in 1..steps) {
                    val t = i.toFloat() / steps
                    val mx = cx(x1) + (cx(x2) - cx(x1)) * t
                    val my = cy(y1) + (cy(y2) - cy(y1)) * t
                    val mv = android.view.MotionEvent.obtain(
                        now, now + i * 30L, android.view.MotionEvent.ACTION_MOVE, mx, my, 0
                    )
                    wv.dispatchTouchEvent(mv)
                    mv.recycle()
                }
                val up = android.view.MotionEvent.obtain(
                    now, now + 350, android.view.MotionEvent.ACTION_UP, cx(x2), cy(y2), 0
                )
                wv.dispatchTouchEvent(up)
                down.recycle(); up.recycle()
                sent = true
            } catch (_: Exception) { }
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return sent
    }

    /**
     * v14 CAPTCHA protocol: height-cap ke BINA screenshot — y-coords ka
     * linear mapping sahi rahe (1200px cap aspect bigaad deta hai).
     */
    fun captureCaptchaPngBase64(): String {
        for (w in intArrayOf(540, 400, 320)) {
            val b64 = renderAtWidthUncapped(w)
            if (b64.isNotEmpty() && b64.length * 3 / 4 <= 400 * 1024) return b64
            if (b64.isNotEmpty()) return b64
        }
        return ""
    }

    private fun renderAtWidthUncapped(w: Int): String {
        val b = renderBitmapUncapped(w) ?: return ""
        return try {
            val out = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.PNG, 100, out)
            b.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    private fun renderBitmapUncapped(w: Int): Bitmap? {
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        handler!!.post {
            try {
                val wv = webView!!
                val h = 960 * wv.height / wv.width.coerceAtLeast(1)
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(b)
                canvas.scale(
                    w.toFloat() / wv.width.coerceAtLeast(1),
                    h.toFloat() / wv.height.coerceAtLeast(1)
                )
                wv.draw(canvas)
                bmp = b
            } catch (_: Exception) { }
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)
        return bmp
    }

    /**
     * captcha_detect — recaptcha / hcaptcha / turnstile iframes, class/id me
     * "captcha" wale elements, src me "captcha" wali images scan karo.
     */
    private fun captchaDetect(): JSONObject {
        val js = """(function(){
          var out=[];
          function rect(el){
            try{
              var r=el.getBoundingClientRect();
              return {x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)};
            }catch(e){ return null; }
          }
          function cls(e){
            try{ var c=e.getAttribute('class')||''; return (typeof c==='string')?c:''; }catch(x){ return ''; }
          }
          function push(kind, el, snippet){
            if(out.length>=10) return;
            out.push({kind:kind, rect:rect(el), html_snippet:(snippet||'').slice(0,300)});
          }
          var docs=[document];
          try{
            Array.from(document.querySelectorAll('iframe')).forEach(function(f){
              try{ if(f.contentDocument) docs.push(f.contentDocument); }catch(e){}
            });
          }catch(e){}
          docs.forEach(function(doc){
            var iframes;
            try{ iframes=doc.querySelectorAll('iframe'); }catch(e){ return; }
            Array.from(iframes).forEach(function(f){
              var s=(f.src||'').toLowerCase(), kind=null;
              if(s.indexOf('recaptcha')>=0) kind='recaptcha';
              else if(s.indexOf('hcaptcha')>=0) kind='hcaptcha';
              else if(s.indexOf('turnstile')>=0||s.indexOf('challenges.cloudflare')>=0) kind='turnstile';
              if(kind) push(kind, f, f.outerHTML);
            });
            var all;
            try{ all=doc.querySelectorAll('*'); }catch(e){ return; }
            Array.from(all).forEach(function(e){
              var c=(cls(e)+' '+(e.id||'')).toLowerCase();
              if(c.indexOf('captcha')<0) return;
              if(e.tagName==='IFRAME') return;
              var kind='captcha_element';
              if(c.indexOf('hcaptcha')>=0) kind='hcaptcha';
              else if(c.indexOf('g-recaptcha')>=0||c.indexOf('recaptcha')>=0) kind='recaptcha';
              else if(e.tagName==='IMG') kind='image_captcha';
              push(kind, e, e.outerHTML);
            });
            var imgs;
            try{ imgs=doc.querySelectorAll('img'); }catch(e){ return; }
            Array.from(imgs).forEach(function(im){
              if((im.src||'').toLowerCase().indexOf('captcha')>=0) push('image_captcha', im, im.outerHTML);
              else if((im.alt||'').toLowerCase().indexOf('captcha')>=0) push('image_captcha', im, im.outerHTML);
            });
            // v14: data-sitekey wale elements (invisible recaptcha/turnstile)
            var sk;
            try{ sk=doc.querySelectorAll('[data-sitekey]'); }catch(e){ return; }
            Array.from(sk).forEach(function(e){
              var c=(cls(e)+' '+(e.id||'')).toLowerCase();
              var kind='recaptcha';
              if(c.indexOf('hcaptcha')>=0) kind='hcaptcha';
              else if(c.indexOf('turnstile')>=0||c.indexOf('cloudflare')>=0) kind='turnstile';
              push(kind, e, e.outerHTML);
            });
            // v14: aria-label me captcha (accessible widgets)
            var al;
            try{ al=doc.querySelectorAll('[aria-label]'); }catch(e){ return; }
            Array.from(al).forEach(function(e){
              try{
                var a=(e.getAttribute('aria-label')||'').toLowerCase();
                if(a.indexOf('captcha')>=0) push('captcha_element', e, e.outerHTML);
              }catch(x){}
            });
          });
          return JSON.stringify({found: out.length>0, widgets: out});
        })()"""
        return unwrapJsObject(evalJsSync(js))
    }

    /**
     * captcha_solve — KABHI fake solve nahi. Screenshot server ko POST karo;
     * solver wired nahi hai to run "needs_admin" pe rukta hai (user ki
     * choice=solve preference ka handoff path).
     */
    private fun captchaSolve(s: StepSpec): JSONObject {
        val det = captchaDetect()
        val widgets = det.optJSONArray("widgets") ?: JSONArray()
        val kind = widgets.optJSONObject(0)?.optString("kind", "unknown") ?: "unknown"
        val shot = capturePngBase64()
        val pageUrl = unwrapJsString(evalJsSync("JSON.stringify(location.href)"))
        val note = "captcha_solve: kind=$kind, page=$pageUrl — solver wired nahi, admin solve karein"
        // Server handoff (best-effort; fail bhi ho to needs_admin hi hai)
        try {
            FormApi.captcha(appContext, currentRunId(), shot, note)
        } catch (_: Exception) { }
        throw NeedsAdminException(
            "captcha mila ($kind) — screenshot server ko bhej diya, admin solve karein"
        )
    }

    // ---------------- upload ----------------
    //
    // Deterministic file upload: upload step aane par file input click hota
    // hai → onShowFileChooser fire → pending file auto-supply (koi system
    // picker nahi khulta). File docs dir (File(filesDir,"docs")) se ya
    // absolute path se aati hai.

    /** upload step ka pending file + chooser-signal. */
    private var pendingUploadFile: java.io.File? = null
    private var pendingUploadLatch: CountDownLatch? = null

    /**
     * User-prompt se chuna hua document (vault filename) — LOCAL-ONLY.
     * Ye kabhi network request me nahi jata: upload step me {"type":"upload"}
     * (bina doc naam) aaye to engine isi file ko use karta hai.
     */
    private var selectedDoc: String? = null

    /** Document prompt ka chuna hua filename set karo (device-local). */
    fun setSelectedDoc(doc: String?) {
        selectedDoc = doc?.trim()?.ifEmpty { null }
    }

    /** Locally-selected document ki file (proactive re-prompt check ke liye). */
    fun selectedDocFile(): java.io.File? {
        val d = selectedDoc ?: return null
        return docFile(d)
    }

    private fun handleFileChooser(cb: ValueCallback<Array<Uri>>?): Boolean {
        val callback = cb ?: return false
        val file = pendingUploadFile
        val latch = pendingUploadLatch
        pendingUploadFile = null
        pendingUploadLatch = null
        try {
            if (file != null && file.exists()) {
                // same-process WebView — file:// URI seedha padh leta hai
                callback.onReceiveValue(arrayOf(Uri.fromFile(file)))
            } else {
                callback.onReceiveValue(null)
            }
        } catch (_: Exception) {
            try { callback.onReceiveValue(null) } catch (_: Exception) { }
        }
        try { latch?.countDown() } catch (_: Exception) { }
        return true
    }

    /** upload step ka file resolve karo — docs dir ya absolute path. */
    private fun resolveUploadFile(doc: String, path: String): java.io.File {
        if (path.isNotEmpty()) {
            val f = java.io.File(path)
            if (!f.isFile || !f.canRead()) throw Exception("upload: file nahi mili: $path")
            return f
        }
        if (doc.isEmpty()) throw Exception("upload: 'doc' ya 'path' chahiye")
        val docsDir = com.formmitra.app.agent.DocsStore.docsDir(appContext)
        val f = java.io.File(docsDir, doc)
        // path traversal guard — docs dir ke bahar nahi
        val canonBase = try { docsDir.canonicalPath } catch (_: Exception) { docsDir.absolutePath }
        val canonFile = try { f.canonicalPath } catch (_: Exception) { "" }
        if (!canonFile.startsWith(canonBase + java.io.File.separator)) {
            throw Exception("upload: galat path '$doc'")
        }
        if (!f.isFile || !f.canRead()) throw Exception("upload: docs me file nahi mili: $doc")
        return f
    }

    /**
     * Vault ka document file ke roop me do (upload ke liye).
     * null = nahi mili / path traversal / unreadable.
     */
    fun docFile(doc: String): java.io.File? = try {
        resolveUploadFile(doc, "")
    } catch (_: Exception) {
        null
    }

    /**
     * Image >400KB ho to compress karke <400KB lao (verify ke saath).
     * Non-image badi file → exception (chup-chaap badi upload nahi).
     */
    private fun maybeCompressImage(src: java.io.File): java.io.File {
        val maxBytes = 400L * 1024L
        if (src.length() <= maxBytes) return src
        val name = src.name.lowercase()
        val isImg = name.endsWith(".jpg") || name.endsWith(".jpeg") ||
            name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".bmp")
        if (!isImg) {
            throw Exception(
                "upload: file 400KB se badi hai (${src.length() / 1024}KB) aur image nahi"
            )
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw Exception("upload: image decode nahi hui (name hidden)")
        }
        var sample = 1
        val dim = maxOf(bounds.outWidth, bounds.outHeight)
        while (dim / sample > 1600) sample *= 2
        val out = java.io.File(appContext.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
        var quality = 85
        while (true) {
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(src.absolutePath, o2)
                ?: throw Exception("upload: image decode nahi hui (name hidden)")
            try {
                java.io.FileOutputStream(out).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos)
                }
            } finally {
                try { bmp.recycle() } catch (_: Exception) { }
            }
            if (out.length() <= maxBytes) break
            if (quality > 50) quality -= 15 else sample *= 2
            if (sample > 16 || quality < 40) break
        }
        if (out.length() > maxBytes) {
            try { out.delete() } catch (_: Exception) { }
            throw Exception("upload: compress ke baad bhi 400KB se badi hai")
        }
        return out
    }

    /** file input dhoondh ke click karo (chooser fire hoga). */
    private fun clickFileInput(selMode: String, selVal: String): Boolean {
        val js = if (selVal.isNotEmpty()) {
            """(function(){
              var el=${finderJs(selMode.ifEmpty { "css" }, selVal)};
              if(!el) return 'NOT_FOUND';
              try{ el.scrollIntoView({block:'center'}); }catch(e){}
              el.click();
              return 'CLICKED';
            })()"""
        } else {
            """(function(){
              var el=null;
              var docs=[document];
              try{
                Array.from(document.querySelectorAll('iframe')).forEach(function(f){
                  try{ if(f.contentDocument) docs.push(f.contentDocument); }catch(e){}
                });
              }catch(e){}
              for(var d=0; d<docs.length && !el; d++){
                try{ el=docs[d].querySelector('input[type=file]'); }catch(e){}
              }
              if(!el) return 'NOT_FOUND';
              try{ el.scrollIntoView({block:'center'}); }catch(e){}
              el.click();
              return 'CLICKED';
            })()"""
        }
        return unwrapJsString(evalJsSync(js)) == "CLICKED"
    }

    /** upload step execute — file resolve → decrypt → compress → input click → auto-supply. */
    private fun executeUpload(raw: JSONObject): JSONObject {
        // doc naam step me ho to wahi; nahi to user-prompt ka locally-selected
        // document (selectedDoc) — filename kabhi server se aata nahi, na jata hai.
        val docName = raw.optString("doc", "").trim()
            .ifEmpty { selectedDoc?.trim() ?: "" }
        val file = resolveUploadFile(
            docName,
            raw.optString("path", "").trim()
        )
        // docs dir ki file CryptoVault-encrypted ho sakti hai (UI agent
        // save karte waqt encrypt karta hai) → cache me decrypt karke temp
        // banao; purani plaintext file ho to fallback (as-is).
        val uploadSrc = maybeDecryptDoc(file)
        // maybeCompressImage naya temp banata hai jab compress hua (HIGH-2:
        // ye plaintext copy cache me reh jati thi) — use ke turant baad
        // finally me delete karo.
        var compressedTmp: java.io.File? = null
        try {
            val final = maybeCompressImage(uploadSrc)
            if (final != uploadSrc) compressedTmp = final
            val sel = raw.optJSONObject("selector")
            val selMode = sel?.optString("mode", "") ?: ""
            val selVal = sel?.optString("value", "") ?: ""
            pendingUploadFile = final
            val latch = CountDownLatch(1)
            pendingUploadLatch = latch
            try {
                if (!clickFileInput(selMode, selVal)) {
                    throw Exception("upload: file input nahi mila")
                }
                if (!latch.await(30, TimeUnit.SECONDS)) {
                    throw Exception("upload: file chooser timeout (30s)")
                }
            } finally {
                pendingUploadFile = null
                pendingUploadLatch = null
            }
            Thread.sleep(1000)
            // v14: upload ke baad page par confirmation text dikha? (verify)
            val confirms = pageConfirmsUpload()
            // v14: filename kabhi result/history/server payload me nahi
            return JSONObject()
                .put("uploaded", true)
                .put("file", "(name hidden)")
                .put("bytes", final.length())
                .put("page_confirms", confirms)
        } finally {
            // decrypt ka temp saaf karo (original chhedo mat)
            if (uploadSrc != file) {
                try { uploadSrc.delete() } catch (_: Exception) { }
            }
            // v14: purani plaintext docs file → successful read ke baad
            // encrypted me migrate (best-effort; docs-dir-bahar chhoote).
            if (uploadSrc == file) {
                try {
                    com.formmitra.app.agent.DocsStore
                        .migratePlaintextToEncrypted(appContext, file)
                } catch (_: Exception) { }
            }
            // HIGH-2: compress ki plaintext copy bhi saaf karo. Upload ho
            // chuka hai — chooser ko file upar Thread.sleep(1000) se pehle
            // mil chuki hai; originals (file/uploadSrc) chhedo mat.
            val ct = compressedTmp
            if (ct != null && ct != uploadSrc && ct != file) {
                try { ct.delete() } catch (_: Exception) { }
            }
        }
    }

    /**
     * v14: upload ke baad page par confirmation text dikha? (best-effort
     * verify — site apna file input accept kar chuka hai ya nahi).
     */
    private fun pageConfirmsUpload(): Boolean {
        return try {
            val txt = unwrapJsString(
                evalJsSync(
                    "(function(){try{return document.body.innerText||''}catch(e){return ''}})()",
                    10_000
                )
            ).lowercase()
            listOf(
                "upload", "success", "attached", "added", "uploaded",
                "file", "document", "submit"
            ).any { txt.contains(it) }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * docs dir ki encrypted file → cache me decrypted temp. Plaintext purani
     * file ya docs-dir-bahar (absolute path) → as-is wapas.
     */
    private fun maybeDecryptDoc(file: java.io.File): java.io.File {
        val docsDir = com.formmitra.app.agent.DocsStore.docsDir(appContext)
        val canonBase = try { docsDir.canonicalPath } catch (_: Exception) { docsDir.absolutePath }
        val canonFile = try { file.canonicalPath } catch (_: Exception) { "" }
        if (!canonFile.startsWith(canonBase + java.io.File.separator)) return file
        return try {
            val plain = CryptoVault.decryptFile(appContext, file)
            val tmp = java.io.File(
                appContext.cacheDir,
                "dec_${System.currentTimeMillis()}_${file.name}"
            )
            tmp.outputStream().use { it.write(plain) }
            tmp
        } catch (_: Exception) {
            file // purani plaintext file — fallback
        }
    }

    /**
     * LocalFallback ke liye: submit/next/continue jaisa button dhoondh ke
     * click karo. Click se PEHLE live-page payment veto — payment/checkout
     * page par submit kabhi nahi dabta (hard veto, har mode me).
     * @return {clicked, text}. Nahi mila to Exception.
     */
    fun clickSubmitButton(): JSONObject {
        checkLivePageVeto()
        val js = """(function(){
          var pats=[/submit/i,/^next$/i,/continue/i,/आगे/,/जमा करें/,/भेजें/,/save/i];
          var docs=[document];
          try{
            Array.from(document.querySelectorAll('iframe')).forEach(function(f){
              try{ if(f.contentDocument) docs.push(f.contentDocument); }catch(e){}
            });
          }catch(e){}
          var pick=null, pickTxt='';
          docs.forEach(function(doc){
            var els;
            try{ els=doc.querySelectorAll('button,a,input[type=submit],input[type=button],[role=button]'); }catch(e){ return; }
            Array.from(els).forEach(function(el){
              if(pick) return;
              var txt='';
              try{ txt=((el.innerText||el.getAttribute('value')||'')+'').trim(); }catch(e){}
              if(!txt) return;
              for(var p=0;p<pats.length;p++){
                if(pats[p].test(txt)){ pick=el; pickTxt=txt.slice(0,60); break; }
              }
            });
          });
          if(!pick) return JSON.stringify({status:'NOT_FOUND'});
          try{ pick.scrollIntoView({block:'center'}); }catch(e){}
          pick.click();
          return JSON.stringify({status:'CLICKED',text:pickTxt});
        })()"""
        val r = unwrapJsObject(evalJsSync(js))
        if (r.optString("status", "") != "CLICKED") {
            throw Exception("submit button nahi mila")
        }
        Thread.sleep(1500)
        return JSONObject().put("clicked", true).put("text", r.optString("text", ""))
    }

    /** captchaSolve ko run_id chahiye — runTask isko set karta hai. */
    private var activeRunId: String = ""

    private fun currentRunId(): String = activeRunId
}

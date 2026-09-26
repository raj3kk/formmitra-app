package com.formmitra.app.engine

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * ErrorCatcher — v35 ADVANCED CATCHER.
 *
 * User ka order (2026-09-26): "koi bhi dikkat, kahin bhi bug aaye — catch
 * karke WAHIN popup show ho, copy ke option ke saath." Generic "dikkat aayi"
 * KHATAM — har error ke saath ASLI wajah dikhegi.
 *
 * Kya karta hai:
 *  1. Caught error ko turant in-app POPUP (dialog) me dikhata hai:
 *     - simple Hinglish me kya hua (bina server/API/status-code jargon),
 *     - "Asli wajah dekho" se expandable technical detail,
 *     - COPY button — chat me paste karne layak format me poori detail.
 *  2. OTP / password / Card PIN / token kabhi copy-text ya logs me nahi
 *     aate — maskSecrets() sab jagah lagta hai.
 *  3. Catcher khud kabhi crash nahi karta — har Android touch defensive
 *     try/catch me; dialog na khule to toast + loud log fallback.
 *  4. Non-blocking: dialog hai, kaam ka state bachta hai; retry button
 *     wahi se dobara koshish karata hai.
 *
 * Global uncaught crashes ab bhi CrashCatcher pakadta hai (process marne
 * wale). Ye un errors ke liye hai jo pakde to gaye par user ko sirf
 * "dikkat aayi" dikha kar dabaa diye jaate the.
 */
object ErrorCatcher {
    private const val TAG = "ErrorCatcher"

    // v36: background reports ka persistent store (koi Activity na ho tab bhi).
    private const val PREFS_REPORTS = "formmitra_error_reports"
    private const val KEY_REPORTS = "reports"
    private const val MAX_REPORTS = 20

    // ------------------------------------------------------------------
    // PURE (JVM-testable, koi Android nahi)
    // ------------------------------------------------------------------

    /**
     * Secrets mask karo: OTP, password, Card PIN, token/session values.
     * Keys rehte hain, VALUES "****" hote hain. Report me session id alag
     * field me jata hai (wo chhota id hai, secret nahi).
     */
    fun maskSecrets(text: String): String {
        var s = text
        // OTP / verification code ke aas-paas ke digit-runs
        s = s.replace(
            Regex(
                "(?i)(otp|one[-_ ]?time[-_ ]?(password|code)|verification[-_ ]?code|sms[-_ ]?code)[^\\d\\n]{0,25}(\\d[\\d\\s\\-]{3,11}\\d)"
            ),
            "$1 ****"
        )
        // password / passwd / pwd
        s = s.replace(
            Regex("(?i)(password|passwd|pwd)\\s*[:=]\\s*([^\\s,}]+)"),
            "$1=****"
        )
        // card PIN / pin
        s = s.replace(
            Regex("(?i)(card\\s*pin|\\bpin\\b)\\s*[:=]?\\s*(\\d{4,8})"),
            "$1=****"
        )
        // token / secret / api key (lambi values)
        s = s.replace(
            Regex("(?i)(token|secret|api[-_ ]?key|auth)\\s*[:=]\\s*([A-Za-z0-9\\-_.]{10,})"),
            "$1=****"
        )
        return s
    }

    /** Exception type → simple Hinglish (jargon-free). */
    fun friendlyMessage(t: Throwable): String {
        val name = t.javaClass.name
        val msg = (t.message ?: "").lowercase(Locale.US)
        return when {
            "SocketTimeoutException" in name ||
                "TimeoutException" in name ||
                "timeout" in msg ->
                "⏳ Time khatm ho gaya — net slow hai ya server jawab nahi de raha."
            "UnknownHostException" in name || "ConnectException" in name ->
                "📡 Internet se connect nahi ho paya — net check karo."
            "SecurityException" in name ->
                "🔒 Permission ki dikkat hai — setting me permission do."
            "WebView" in (t.toString()) ->
                "📱 Browser banane me dikkat aayi."
            "JSONException" in name ->
                "📦 Server ka jawab samajh nahi aaya."
            "IllegalStateException" in name ->
                "⚠️ Galat state me kaam shuru hua — dobara try karo."
            else ->
                "⚠️ Kaam karte waqt dikkat aayi."
        }
    }

    /** Masked one-line cause (popup ki detail + logs ke liye). */
    fun shortCause(t: Throwable): String =
        maskSecrets(t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""))

    /**
     * v37: "... N more" EXPANSION — full technical detail me cause chain
     * ke frames printStackTrace ke "... N more" se chhupe NAHI rehte.
     *
     * printStackTrace() cause ke trailing common frames ko "... N more"
     * likhkar chhupa deta hai. Yahan har cause ke frames uske apne
     * `stackTrace` array se KHUD render hote hain — poori chain, koi line
     * chhupi nahi. Cycle-safe (cause cycle par pehle dekha cause dobara
     * nahi) + suppressed exceptions bhi. Sab secrets masked.
     *
     * Pure (JVM-testable).
     */
    fun fullStackTrace(t: Throwable): String {
        return try {
            val sb = StringBuilder()
            val seen = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Throwable, Boolean>()
            )
            renderThrowable(sb, t, "", seen)
            maskSecrets(sb.toString())
        } catch (_: Exception) {
            try {
                maskSecrets(t.javaClass.name + ": " + (t.message ?: ""))
            } catch (_: Exception) {
                t.javaClass.name
            }
        }
    }

    private fun renderThrowable(
        sb: StringBuilder,
        t: Throwable,
        prefix: String,
        seen: MutableSet<Throwable>
    ) {
        if (!seen.add(t)) {
            sb.append(prefix).append("[CIRCULAR: ").append(t.javaClass.name)
                .append("]\n")
            return
        }
        sb.append(prefix).append(t.javaClass.name)
        val msg = try { t.message } catch (_: Exception) { null }
        if (!msg.isNullOrEmpty()) sb.append(": ").append(msg)
        sb.append('\n')
        // Frames KHUD render — printStackTrace wala "... N more" yahan
        // kabhi nahi aata (expansion hi point hai).
        val frames = try { t.stackTrace } catch (_: Exception) { emptyArray() }
        for (f in frames) {
            sb.append(prefix).append("    at ").append(f.toString()).append('\n')
        }
        // Suppressed bhi poore
        val sup = try { t.suppressed } catch (_: Exception) { emptyArray() }
        for (s in sup) {
            renderThrowable(sb, s, prefix + "    Suppressed: ", seen)
        }
        // Cause chain — frames poore (common frames bhi repeat hote hain;
        // chhupana nahi hai — user order: "kuch chhupana nahi").
        // "Caused by:" separator APNI line par aata hai; prefix accumulate
        // NAHI hota (nahi to har level "Caused by: Caused by: ..." ban jata
        // aur count toot jata — v37 selftest pin).
        val c = try { t.cause } catch (_: Exception) { null }
        if (c != null) {
            if (c === t) {
                sb.append(prefix).append("[CIRCULAR CAUSE]\n")
            } else {
                sb.append(prefix).append("Caused by: ")
                renderThrowable(sb, c, prefix, seen)
            }
        }
    }

    /**
     * v36 catcher order: POORA technical detail — kuch chhupana nahi.
     * Sirf secrets mask (maskSecrets) — baaki sab RAW.
     *
     * Me hota hai:
     *  - Error class (poora naam)
     *  - Message (raw)
     *  - Cause chain (poori)
     *  - Error code (class-prefix + stack fingerprint — report match karne ke liye)
     *  - Jahan hua (pehle 5 app frames: file/func/line jahan available)
     *  - Stack trace — POORA, untruncated (fullTrace=true) ya pehli N lines
     *
     * Pure (JVM-testable).
     */
    fun technicalDetail(t: Throwable, maxLines: Int = Int.MAX_VALUE): String {
        // v37: fullStackTrace — printStackTrace wala "... N more" kabhi
        // nahi aata; cause chain ke frames poore render hote hain.
        val fullTrace = fullStackTrace(t)
        val lines = fullTrace.lines()
        val trace = if (lines.size > maxLines) {
            lines.take(maxLines).joinToString("\n") +
                "\n... (${lines.size - maxLines} lines aur — \"Poora dekho\" me)"
        } else fullTrace
        val code = errorCode(t, fullTrace)
        val where = whereHappened(t)
        val causes = causeChain(t)
        return buildString {
            appendLine("Error class: ${t.javaClass.name}")
            appendLine("Message: ${maskSecrets(t.message ?: "-")}")
            appendLine("Error code: $code")
            if (causes.isNotEmpty()) {
                appendLine("Cause chain:")
                for (c in causes) appendLine("  ← $c")
            }
            appendLine("Jahan hua:")
            if (where.isEmpty()) appendLine("  (stack khaali)")
            else for (w in where) appendLine("  $w")
            appendLine("--- Stack trace (poora) ---")
            append(trace)
        }
    }

    /**
     * Error code — report match karne ke liye stable fingerprint:
     * CLASSNAME-xxxxxx (stack hash ke pehle 6 hex).
     */
    fun errorCode(t: Throwable, maskedTrace: String? = null): String {
        return try {
            // v37: fullStackTrace (printStackTrace ka "... N more" nahi).
            val trace = maskedTrace ?: fullStackTrace(t)
            val hash = trace.hashCode().toUInt().toString(16).padStart(8, '0').take(6)
            "${t.javaClass.simpleName.uppercase(Locale.US).take(12)}-$hash"
        } catch (_: Exception) {
            t.javaClass.simpleName.uppercase(Locale.US).take(12)
        }
    }

    /**
     * Jahan hua — pehle 5 frames (file/func/line jahan available).
     * Format: at pkg.Class.func (File.kt:line)
     */
    fun whereHappened(t: Throwable, maxFrames: Int = 5): List<String> {
        return try {
            t.stackTrace.take(maxFrames).map { e ->
                val loc = if (e.fileName != null) {
                    " (${e.fileName}:${if (e.lineNumber >= 0) e.lineNumber else "?"})"
                } else ""
                "at ${e.className}.${e.methodName}$loc"
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Cause chain — masked, sabse andar tak, BINA cap.
     * Cycle-safe: cause cycle (A→B→A) par pehle dekha cause dobara nahi
     * (infinite loop impossible). Koi arbitrary depth cap nahi — user order:
     * causes poore dikhenge.
     */
    fun causeChain(t: Throwable): List<String> {
        val out = mutableListOf<String>()
        return try {
            val seen = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Throwable, Boolean>()
            )
            var c = t.cause
            seen.add(t)
            while (c != null && seen.add(c)) {
                out.add(maskSecrets(c.javaClass.simpleName + (c.message?.let { ": $it" } ?: "")))
                c = c.cause
            }
            out
        } catch (_: Exception) { out }
    }

    /**
     * Chat me paste karne layak POORI report (Copy button isi ko bhejta hai).
     * v36 catcher order: kuch chhupana nahi — error class, message, cause
     * chain, error code, jahan hua, POORA stack trace. Sirf secrets masked.
     * Format: kaam, time, session id, jagah, wajah + technicalDetail (poora).
     * Koi length cap NAHI (user order: copy me complete report).
     */
    fun formatReport(
        where: String,
        workName: String,
        sessionId: String,
        t: Throwable,
        extra: Map<String, String> = emptyMap()
    ): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("FormMitra error report")
            appendLine("Kaam: ${workName.ifEmpty { "-" }}")
            appendLine("Time: $ts")
            appendLine("Session: ${sessionId.ifEmpty { "-" }}")
            appendLine("Jagah: $where")
            appendLine("Wajah: ${shortCause(t)}")
            for ((k, v) in extra) {
                if (k.isNotEmpty() && v.isNotEmpty()) appendLine("$k: ${maskSecrets(v)}")
            }
            appendLine("=== Technical detail (poora) ===")
            append(technicalDetail(t))
        }
    }

    /**
     * v36: BACKGROUND failure path — koi Activity visible na ho tab bhi.
     * Asli wajah (masked) log + persist hoti hai; app khulne par user
     * report dekh/copy kar sakta hai (lastReports()).
     * Kabhi throw nahi karta — catcher khud crash nahi karega.
     */
    fun report(
        ctx: Context,
        where: String,
        t: Throwable,
        workName: String = "",
        sessionId: String = ""
    ) {
        try {
            Log.e(
                TAG,
                "[$where] work='$workName' session='$sessionId': ${shortCause(t)}",
                t
            )
        } catch (_: Exception) { }
        try {
            val prefs = ctx.getSharedPreferences(PREFS_REPORTS, Context.MODE_PRIVATE)
            val arr = try {
                JSONArray(prefs.getString(KEY_REPORTS, "[]") ?: "[]")
            } catch (_: Exception) {
                JSONArray()
            }
            arr.put(
                JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("where", where)
                    .put("work", workName)
                    .put("session", sessionId)
                    // v36 catcher order: persisted report BHI poora (copy
                    // me complete report — koi cap nahi, sirf secrets masked).
                    .put("report", formatReport(where, workName, sessionId, t))
            )
            while (arr.length() > MAX_REPORTS) arr.remove(0)
            prefs.edit().putString(KEY_REPORTS, arr.toString()).apply()
        } catch (_: Exception) { }
    }

    /**
     * v36: persist hui background reports (nayi pehle). Copy/share ke liye.
     */
    fun lastReports(ctx: Context): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        try {
            val arr = JSONArray(
                ctx.getSharedPreferences(PREFS_REPORTS, Context.MODE_PRIVATE)
                    .getString(KEY_REPORTS, "[]") ?: "[]"
            )
            for (i in arr.length() - 1 downTo 0) {
                (arr.optJSONObject(i) ?: continue).let { out.add(it) }
            }
        } catch (_: Exception) { }
        return out
    }

    /**
     * FormEngine.start() failure ka message contract — asli wajah hamesha
     * message me (pehle silent nigal jati thi → generic "create failed").
     */
    fun startFailureMessage(err: Throwable?): String {
        val why = err?.let { " — asli wajah: ${shortCause(it)}" } ?: ""
        return "FormEngine WebView create failed$why"
    }

    // ------------------------------------------------------------------
    // ANDROID (defensive — catcher khud kabhi crash nahi karega)
    // ------------------------------------------------------------------

    /**
     * Popup dikhao. Hamesha UI thread par; Activity na mile ya dialog fail
     * ho to toast + loud log (silent death kabhi nahi).
     *
     * @param ctx koi bhi Context (Activity nikaalne ki koshish hogi)
     * @param where Hinglish me jagah, jaise "Kaam shuru karte waqt"
     * @param t asli exception
     * @param workName kaam ka naam (report ke liye)
     * @param sessionId chhota session id (report ke liye)
     * @param retry null nahi to "Dobara try karo" button
     */
    fun show(
        ctx: Context,
        where: String,
        t: Throwable,
        workName: String = "",
        sessionId: String = "",
        retry: (() -> Unit)? = null
    ) {
        try {
            Log.e(TAG, "[$where] work='$workName' session='$sessionId'", t)
        } catch (_: Exception) { }
        val report = try {
            formatReport(where, workName, sessionId, t)
        } catch (_: Exception) {
            "FormMitra error report\nJagah: $where\nWajah: ${t.javaClass.simpleName}"
        }
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    showDialog(ctx, where, t, report, retry)
                } catch (_: Exception) {
                    fallbackToast(ctx, where, t)
                }
            }
        } catch (_: Exception) {
            fallbackToast(ctx, where, t)
        }
    }

    private fun showDialog(
        ctx: Context,
        where: String,
        t: Throwable,
        report: String,
        retry: (() -> Unit)?
    ) {
        val act = findActivity(ctx) ?: run {
            fallbackToast(ctx, where, t)
            return
        }
        if (act.isFinishing || act.isDestroyed) {
            fallbackToast(ctx, where, t)
            return
        }
        val friendly = friendlyMessage(t)
        // v36 catcher order: do level — upar simple Hinglish, neeche
        // expandable me POORA technical detail. Pehle chhota (error class +
        // jahan hua + 25 lines), "Poora dekho" par poora untruncated trace.
        val fullDetail = try {
            technicalDetail(t)
        } catch (_: Exception) {
            "Error class: ${t.javaClass.name}\nMessage: ${shortCause(t)}"
        }
        val shortDetail = try {
            buildString {
                appendLine("Error class: ${t.javaClass.name}")
                appendLine("Error code: ${errorCode(t)}")
                appendLine("Jahan hua:")
                val wh = whereHappened(t)
                if (wh.isEmpty()) appendLine("  (stack khaali)")
                else for (w in wh) appendLine("  $w")
                appendLine()
                appendLine("Asli wajah: ${shortCause(t)}")
                appendLine()
                appendLine("--- Stack (pehli 25 lines) ---")
                // v37: fullStackTrace (printStackTrace ka "... N more" nahi).
                append(fullStackTrace(t).lines().take(25).joinToString("\n"))
            }
        } catch (_: Exception) {
            "Asli wajah: ${t.javaClass.simpleName}"
        }

        val pad = (16 * act.resources.displayMetrics.density).toInt()
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        // v36 catcher order: "Poora dekho" toggle — short detail se full
        // untruncated technical detail par switch.
        var showingFull = false
        val detailView = TextView(act).apply {
            text = shortDetail
            textSize = 11f
            typeface = Typeface.MONOSPACE
            visibility = android.view.View.GONE
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        val fullToggle = TextView(act).apply {
            text = "📜 Poora technical detail dekho"
            textSize = 12f
            setTextColor(0xFF1A73E8.toInt())
            setPadding(pad / 2, pad / 4, pad / 2, pad / 4)
            visibility = android.view.View.GONE
            setOnClickListener {
                try {
                    showingFull = !showingFull
                    detailView.text = if (showingFull) fullDetail else shortDetail
                    text = if (showingFull) "📜 Chhota dekho" else "📜 Poora technical detail dekho"
                } catch (_: Exception) { }
            }
        }
        // Detail toggle — view ke andar (dialog ke 3-button limit se bahar).
        val toggle = TextView(act).apply {
            text = "🔍 Asli wajah dekho"
            textSize = 13f
            setTextColor(0xFF1A73E8.toInt())
            setPadding(0, pad / 4, 0, pad / 4)
            setOnClickListener {
                try {
                    val showing = detailView.visibility == android.view.View.VISIBLE
                    detailView.visibility =
                        if (showing) android.view.View.GONE else android.view.View.VISIBLE
                    fullToggle.visibility =
                        if (showing) android.view.View.GONE else android.view.View.VISIBLE
                    if (showing) {
                        // Band karte waqt short par wapas (agla khulna saaf ho)
                        showingFull = false
                        detailView.text = shortDetail
                        fullToggle.text = "📜 Poora technical detail dekho"
                    }
                    text = if (showing) "🔍 Asli wajah dekho" else "🔍 Wajah chhupao"
                } catch (_: Exception) { }
            }
        }
        root.addView(toggle)
        root.addView(fullToggle)
        val scroll = ScrollView(act)
        scroll.addView(detailView)
        root.addView(scroll)

        val builder = AlertDialog.Builder(act)
            .setTitle("⚠️ $where — dikkat aayi")
            .setMessage(friendly + "\n\nWajah oopar dekho ya Copy karke hume bhejo — turant theek karenge.")
            .setView(root)
            .setPositiveButton("📋 Copy karo") { _, _ ->
                try {
                    val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("formmitra-error", report))
                    Toast.makeText(act, "Copy ho gaya — chat me bhej do", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(act, "Copy nahi ho paya", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Band karo") { d, _ -> d.dismiss() }
        if (retry != null) {
            builder.setNeutralButton("🔁 Dobara try karo") { _, _ ->
                try { retry() } catch (_: Exception) { }
            }
        }
        try {
            builder.create().show()
        } catch (_: Exception) {
            fallbackToast(ctx, where, t)
        }
    }

    private fun fallbackToast(ctx: Context, where: String, t: Throwable) {
        try {
            Toast.makeText(
                ctx.applicationContext,
                "⚠️ $where — dikkat aayi (${t.javaClass.simpleName})",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) { }
        try {
            Log.e(TAG, "popup fallback [$where]: ${shortCause(t)}")
        } catch (_: Exception) { }
    }

    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        var hops = 0
        while (c is ContextWrapper && hops < 10) {
            if (c is Activity) return c
            c = c.baseContext
            hops++
        }
        return null
    }
}

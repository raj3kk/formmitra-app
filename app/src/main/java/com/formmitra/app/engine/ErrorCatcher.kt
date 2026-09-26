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
     * Chat me paste karne layak poori report. Format:
     * kaam, time, session id, jagah, wajah + masked stack (60 lines tak).
     */
    fun formatReport(
        where: String,
        workName: String,
        sessionId: String,
        t: Throwable,
        extra: Map<String, String> = emptyMap()
    ): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val maskedTrace = maskSecrets(sw.toString()).lines().take(60).joinToString("\n")
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
            appendLine("---")
            append(maskedTrace)
        }.take(48_000)
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
        val detail = try {
            "Asli wajah:\n${shortCause(t)}\n\n" +
                maskSecrets(
                    StringWriter().also {
                        t.printStackTrace(PrintWriter(it))
                    }.toString()
                ).lines().take(25).joinToString("\n")
        } catch (_: Exception) {
            "Asli wajah: ${t.javaClass.simpleName}"
        }

        val pad = (16 * act.resources.displayMetrics.density).toInt()
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val detailView = TextView(act).apply {
            text = detail
            textSize = 11f
            typeface = Typeface.MONOSPACE
            visibility = android.view.View.GONE
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setBackgroundColor(0xFFF5F5F5.toInt())
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
                    text = if (showing) "🔍 Asli wajah dekho" else "🔍 Wajah chhupao"
                } catch (_: Exception) { }
            }
        }
        root.addView(toggle)
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

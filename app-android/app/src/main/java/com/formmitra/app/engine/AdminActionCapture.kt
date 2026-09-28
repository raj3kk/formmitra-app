package com.formmitra.app.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * v58: ADMIN ACTION CAPTURE — admin jo kare, agent usse SEEKHE.
 *
 * Jab admin kisi browser ko control karta hai (AdminTakeover driving),
 * uski har navigation (page change) yahan log hoti hai — JSONL file me:
 *   {"ts":..., "browser":"work"|"help", "action":"navigate",
 *    "url":"...", "title":"..."}
 *
 * Trainer baad me is file ko padhkar admin ke patterns seekh sakta hai
 * (kaunsi site par kaise navigate kiya, kis order me).
 *
 * Design notes:
 * - Sirf navigation log hota hai (URL + title) — passwords/OTP kabhi nahi.
 *   URL me bhi agar "password|otp|token|secret" jaisa param dikhe to
 *   us param ki value "***" se mask hoti hai.
 * - File app ke private filesDir me — sirf app padh sakta hai.
 * - Best-effort: log fail ho to automation kabhi nahi rukti.
 */
object AdminActionCapture {
    private const val TAG = "FmAdminCapture"
    private const val FILE_NAME = "admin_actions.jsonl"
    private const val MAX_LINES = 2000

    fun log(
        ctx: Context,
        browser: String,
        action: String,
        url: String?,
        detail: String? = null
    ) {
        try {
            val safeUrl = maskSensitive(url ?: "")
            val obj = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("browser", browser)
                put("action", action)
                put("url", safeUrl)
                if (detail != null) put("detail", detail.take(200))
            }
            val f = File(ctx.filesDir, FILE_NAME)
            // File bahut badi ho to purani lines hatao (ring buffer jaisa)
            try {
                if (f.exists() && f.length() > 500_000) {
                    val lines = f.readLines()
                    val keep = lines.takeLast(MAX_LINES / 2)
                    f.writeText(keep.joinToString("\n") + "\n")
                }
            } catch (_: Exception) { }
            f.appendText(obj.toString() + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "capture log fail: ${(e.message ?: "").take(80)}")
        }
    }

    /**
     * URL me sensitive query params mask karo.
     * e.g. ?otp=123456 → ?otp=***
     */
    fun maskSensitive(url: String): String {
        return try {
            var out = url
            val sensitive = listOf(
                "password", "passwd", "pwd", "otp", "token", "secret",
                "pin", "cvv", "card", "api_key", "apikey", "auth"
            )
            for (key in sensitive) {
                // ?key=value ya &key=value → ?key=***
                out = out.replace(
                    Regex("([?&]$key=)[^&]*", RegexOption.IGNORE_CASE),
                    "$1***"
                )
            }
            out.take(500)
        } catch (_: Exception) {
            url.take(500)
        }
    }

    /** Trainer ke liye: saari captured actions padho (nayi sabse aakhir me). */
    fun readAll(ctx: Context): List<JSONObject> {
        return try {
            val f = File(ctx.filesDir, FILE_NAME)
            if (!f.exists()) return emptyList()
            f.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull {
                    try { JSONObject(it) } catch (_: Exception) { null }
                }
        } catch (_: Exception) { emptyList() }
    }

    /** Kitni actions capture hui hain. */
    fun count(ctx: Context): Int {
        return try {
            val f = File(ctx.filesDir, FILE_NAME)
            if (!f.exists()) 0 else f.readLines().count { it.isNotBlank() }
        } catch (_: Exception) { 0 }
    }
}

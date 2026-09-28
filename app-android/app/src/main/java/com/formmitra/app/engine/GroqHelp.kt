package com.formmitra.app.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GroqHelp — v55.
 *
 * User order: "Jo ai integrate h groq uska v help le jb jaruri ho
 * jha p lena chaiye"
 *
 * Jab operator atka ho aur samajh chahiye, to AI Mode browser ke
 * ALAWA Groq se bhi seedha puchho. Groq fast hai (direct API —
 * browser kholne ka wait nahi), isliye help chain me iska
 * order hai:
 *
 *   1. AiModeMemory (seekha hua — free, instant)
 *   2. GroqHelp (key hai to — fast, smart)
 *   3. AI Mode browser (key nahi hai to — screenshot ke saath)
 *
 * Privacy: situation me passwords/OTP kabhi nahi hote (sirf goal,
 * URL, action description) — isliye Groq ko bhejna safe hai.
 * Key CryptoVault me encrypted rehti hai (Standalone).
 */
object GroqHelp {

    private const val TAG = "GroqHelp"
    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "qwen/qwen3-32b"
    private const val TIMEOUT_MS = 45_000

    private const val SYSTEM = """You are helping a browser automation agent that is STUCK on a web page. The agent tells you its goal, the page URL, what it was trying to do, and what went wrong.

Reply in Romanized Hinglish, short and step-by-step. Tell the agent:
1. Page par likely kya dikh raha hai / kahan atka hai
2. Goal poora karne ke liye AGLA STEP kya hona chahiye
3. Kaunsa button/link/field use kare — exact visible text batao
4. Agar page galat lag raha hai to sahi page ka URL guess karo

Rules:
- Sirf actionable guidance do, lamba explanation nahi.
- Kabhi password/OTP/personal data mat maango, mat bhejo.
- Agar samajh na aaye to seedha bolo "samajh nahi aaya" — jhootha step mat batao."""

    /**
     * Stuck situation par Groq se help maango.
     *
     * @param situation full smart situation (goal/URL/problem/tries)
     * @param pageText page ka text summary (optional — context ke liye)
     * @return samajh (null = key nahi hai / call fail)
     */
    fun askForHelp(
        ctx: Context,
        situation: AiHelpSystem.HelpSituation,
        pageText: String = ""
    ): String? {
        // Key nahi hai → chupchaap skip (AI Mode browser fallback hai)
        val key = try {
            Standalone.getKey(ctx)?.ifEmpty { null }
        } catch (_: Exception) { null } ?: run {
            Log.i(TAG, "Groq key nahi hai — skip")
            return null
        }

        return try {
            val userPrompt = buildHelpPrompt(situation, pageText)
            val body = JSONObject()
                .put("model", MODEL)
                .put("temperature", 0.3)
                .put("max_tokens", 600)
                .put(
                    "messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", SYSTEM))
                        .put(JSONObject().put("role", "user").put("content", userPrompt))
                )
            val respText = postGroq(key, body) ?: return null
            // Response se text nikalo
            val resp = try { JSONObject(respText) } catch (_: Exception) { return null }
            val text = try {
                resp.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content", "")
                    .trim()
            } catch (_: Exception) { "" }
            if (text.length >= 50) {
                Log.i(TAG, "Groq se samajh mila (${text.length} chars)")
                text
            } else {
                Log.w(TAG, "Groq jawab chhota/khaali")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "GroqHelp fail: ${(e.message ?: "").take(80)}")
            null
        }
    }

    /**
     * Help prompt — situation se full detail.
     * (Screenshot nahi — Groq text API hai; situation text me poori hai)
     */
    private fun buildHelpPrompt(
        s: AiHelpSystem.HelpSituation,
        pageText: String
    ): String {
        val sb = StringBuilder()
        sb.append("Mera goal hai: ${s.goal}\n")
        if (s.currentUrl.isNotEmpty()) {
            sb.append("Main is link par hun: ${s.currentUrl}\n")
        }
        if (s.pageTitle.isNotEmpty()) {
            sb.append("Page title: ${s.pageTitle}\n")
        }
        if (s.attemptedAction.isNotEmpty()) {
            sb.append("Main ye karne ki koshish kar raha tha: ${s.attemptedAction}\n")
        }
        sb.append("Problem: ${s.problem}\n")
        if (s.failCount > 0) {
            sb.append("Ye ${s.failCount} baar fail ho chuka hai.\n")
        }
        if (s.recentAttempts.isNotEmpty()) {
            sb.append("Pehle ye try kar chuka hun:\n")
            s.recentAttempts.take(5).forEach { sb.append("- $it\n") }
        }
        // v56: "kya kr diya h" — Groq ko bhi pata ho jo ho gaya.
        if (s.completedSoFar.isNotEmpty()) {
            sb.append("Ab tak YE HO CHUKA HAI (dobara karne ko mat kaho):\n")
            s.completedSoFar.take(8).forEach { sb.append("- $it\n") }
        }
        if (pageText.isNotEmpty()) {
            sb.append("\nPage par ye text dikh raha hai:\n")
            // v58: SECRET REDACTION — password/OTP/token AI ko kabhi nahi
            sb.append(SecretRedactor.redact(pageText.take(1500)))
            sb.append("\n")
        }
        sb.append("\n")
        sb.append(SecretRedactor.screenshotWarning())
        sb.append("\nAb batao — iske aage kya karun? Step by step.")
        return sb.toString()
    }

    /** Groq POST. 2xx → response text; 401 → null (key galat); baaki → null. */
    private fun postGroq(apiKey: String, body: JSONObject): String? {
        val conn = (URL(GROQ_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            doOutput = true
        }
        return try {
            conn.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            if (code == 401) {
                Log.w(TAG, "Groq 401 — key galat/expire")
                return null
            }
            if (code == 429) {
                Log.w(TAG, "Groq 429 — rate limit")
                return null
            }
            if (code !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }.ifEmpty { null }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}

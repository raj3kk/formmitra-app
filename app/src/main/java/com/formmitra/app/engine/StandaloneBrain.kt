package com.formmitra.app.engine

import android.content.Context
import com.formmitra.app.agent.AgentApi
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * StandaloneBrain — no-server AI brain (Phase 4 standalone mode).
 *
 * Jab FormMitra server unreachable ho (network fail / HTTP 5xx) AUR user ne
 * apni Groq API key save ki ho, to AgentLoop isse seedha Groq
 * (qwen/qwen3.8-27b) ko call karta hai — server ke /api/agent/act jaisa
 * hi decision, phone par.
 *
 * System prompt = server ke ACT_SYSTEM (lib/agent/act.ts) ka port —
 * text-only (screenshot nahi bhejte, DOM snapshot hi kaafi).
 *
 * decide() contract:
 *   - step JSONObject (server ke {step} jaisa) → AgentLoop normal flow me le
 *   - null → call fail (network / 429 / 5xx / bad key) → AgentLoop
 *     LocalFallback pe jayega
 *   - parse fail → {"action":"needs_user",...} step (kabhi crash nahi)
 *   - payment veto → {"action":"vetoed",...} step (pre-check, AI se pehle)
 *
 * Safety: har step AgentLoop me validateAgentStep + runAgentStep ke veto
 * se guzarta hai — standalone me bhi payment/checkout kabhi execute nahi.
 */
object StandaloneBrain {

    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "qwen/qwen3.8-27b"
    private const val TIMEOUT_MS = 60_000

    // Server ACT_SYSTEM ka port (text-only standalone ke liye).
    private const val SYSTEM = """You are the Browser Operator brain for FormMitra, an Indian form-filling assistant running STANDALONE on the user's phone (no FormMitra server). The app shows you a web page (DOM snapshot, text-only — no screenshot) and the user's goal. Decide the SINGLE next action.

Return STRICT JSON only, no other text, exactly this shape:
{"action": "fill|select|toggle|press|click|goto|wait_for_text|wait_for_element|wait_for_navigation|upload|done|needs_user",
 "selector": {"mode": "css|id|name|label|placeholder|text|aria|hint", "value": "..."},
 "value": "text to type (fill only)",
 "option": "option text (select only)",
 "url": "https url (goto only)",
 "key": "Enter (press only)",
 "text": "text to wait for (wait_for_text only)",
 "doc": "file name in docs folder (upload only)",
 "path": "absolute file path (upload only)",
 "confidence": 0.0-1.0,
 "reason": "one short line why this step",
 "user_prompt": "Hinglish question for user (needs_user only)",
 "result_summary": "Hinglish summary (done only)"}

Rules:
- ONE step at a time. Look at history — do NOT repeat a step that already failed twice.
- Prefer selector mode "label" or "placeholder" with the visible field name; use "text" for buttons.
- "fill": value MUST come from the user's saved profile or the goal — NEVER invent personal data (name, phone, OTP, passwords). If the needed value is not in profile/goal, use needs_user.
- NEVER do any payment/checkout/purchase action. If the page asks for payment, use needs_user with user_prompt explaining it.
- If the goal is clearly complete, use "done" with result_summary in Hinglish.
- If blocked (login wall you cannot pass, CAPTCHA you cannot describe, unclear page), use needs_user.
- confidence: your certainty 0-1. Below 0.55 the app will ask the user instead.
- Reply in the JSON only. reason/user_prompt/result_summary in Romanized Hinglish."""

    /**
     * Agla step decide karo. @param req wahi reqBody jo AgentLoop server ko
     * bhejta hai (goal, url, page_title, dom_snapshot, history, stuck_count).
     */
    fun decide(ctx: Context, req: JSONObject): JSONObject? {
        val goal = req.optString("goal", "")
        val url = req.optString("url", "")
        val pageText = req.optJSONObject("dom_snapshot")?.optString("page_text", "") ?: ""

        // Pre-veto: AI se pehle hi payment pakdo (server jaisa)
        VetoCheck.find("$goal $url $pageText")?.let { kw ->
            return JSONObject()
                .put("action", "vetoed")
                .put("blocked_reason", "PAYMENT VETO (standalone): '$kw' mila")
                .put("confidence", 1.0)
        }

        val key = Standalone.getKey(ctx)?.ifEmpty { null } ?: return null
        val profile = minimizeProfile(fetchVaultProfile(ctx), req)
        val userText = buildUserText(req, profile)

        val body = JSONObject()
            .put("model", MODEL)
            .put("temperature", 0.2)
            .put("max_tokens", 800)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", userText))
            )

        val respText = try {
            postGroq(key, body)
        } catch (e: GroqAuthException) {
            // v14: 401 = key galat/expire — explicit needs_user, koi
            // deterministic fallback nahi (galat key se andha kaam nahi).
            return JSONObject()
                .put("action", "needs_user")
                .put(
                    "user_prompt",
                    "Groq API key galat ya expire ho gayi hai — ⚙️ settings me nayi key dalen, phir task dobara chalayein"
                )
                .put("confidence", 1.0)
        } catch (_: Exception) {
            return null
        } ?: return null

        val content = try {
            JSONObject(respText)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "") ?: ""
        } catch (_: Exception) {
            ""
        }
        val step = extractJson(content)
            ?: return JSONObject()
                .put("action", "needs_user")
                .put("user_prompt", "AI se sahi jawab nahi mila — aap dekh lein")
                .put("confidence", 1.0)

        // v14: AI ke jawab par payment keyword scan (action/value/url/text/
        // reason/selector/user_prompt) — server-side veto ka standalone mirror.
        val aiBlob = listOf(
            step.optString("action", ""),
            step.optString("value", ""),
            step.optString("url", ""),
            step.optString("text", ""),
            step.optString("reason", ""),
            step.optString("result_summary", ""),
            step.optString("user_prompt", ""),
            step.optJSONObject("selector")?.optString("value", "") ?: ""
        ).joinToString(" ")
        VetoCheck.find(aiBlob)?.let { kw ->
            return JSONObject()
                .put("action", "vetoed")
                .put("blocked_reason", "PAYMENT VETO (standalone AI output): '$kw' mila")
                .put("confidence", 1.0)
        }

        // confidence < 0.55 → needs_user (server jaisa)
        val conf = step.optDouble("confidence", 1.0)
        if (conf < 0.55) {
            return JSONObject()
                .put("action", "needs_user")
                .put(
                    "user_prompt",
                    "AI sure nahi hai (confidence ${"%.2f".format(conf)}) — aap dekh lein"
                )
                .put("confidence", 1.0)
        }
        return step
    }

    /** 401 (invalid/expired key) — deterministic fallback nahi, explicit handoff. */
    private class GroqAuthException : Exception()

    /** Groq POST. 2xx → response text; 401 → GroqAuthException; baaki → null. */
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
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 401) throw GroqAuthException()
            if (code !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }.ifEmpty { null }
        } catch (e: GroqAuthException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * v14: profile minimization — Groq ko sirf wahi fields bhejo jo is page /
     * goal ke liye relevant hain (visible DOM + goal text se match).
     */
    private fun minimizeProfile(
        profile: Map<String, String>,
        req: JSONObject
    ): Map<String, String> {
        if (profile.isEmpty()) return profile
        val snap = req.optJSONObject("dom_snapshot") ?: JSONObject()
        val labelBlob = StringBuilder()
        val fields = snap.optJSONArray("fields") ?: JSONArray()
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: continue
            labelBlob.append(f.optString("label", "")).append(" ")
                .append(f.optString("placeholder", "")).append(" ")
                .append(f.optString("aria", "")).append(" ")
                .append(f.optString("name", "")).append(" ")
        }
        val blob = (req.optString("goal", "") + " " +
            snap.optString("page_text", "") + " " + labelBlob.toString()).lowercase()
        // key → us field ko dhoondhne wale hints
        val hints = mapOf(
            "name" to listOf("name", "naam", "full name"),
            "email" to listOf("email", "e-mail", "mail"),
            "phone" to listOf("phone", "mobile", "tel", "number"),
            "dob" to listOf("dob", "birth", "janm", "date of birth"),
            "gender" to listOf("gender", "ling"),
            "address" to listOf("address", "pata", "street"),
            "city" to listOf("city", "shahar"),
            "state" to listOf("state", "rajya"),
            "pincode" to listOf("pin", "pincode", "postal", "zip"),
            "country" to listOf("country", "desh"),
            "aadhaar" to listOf("aadhaar", "aadhar"),
            "pan" to listOf("pan"),
            "occupation" to listOf("occupation", "job", "profession", "kaam"),
            "education" to listOf("education", "qualification", "degree", "padhai")
        )
        return profile.filter { (k, _) ->
            val key = k.lowercase()
            if (blob.contains(key)) return@filter true
            val hs = hints.entries.firstOrNull { key.contains(it.key) }?.value
                ?: return@filter blob.contains(key.take(4))
            hs.any { blob.contains(it) }
        }
    }

    /** ```json fence hata ke pehla {...} nikalo. */
    private fun extractJson(content: String): JSONObject? {
        var t = content.trim()
        if (t.isEmpty()) return null
        // code fence hatao
        if (t.startsWith("```")) {
            t = t.removePrefix("```")
            if (t.startsWith("json", ignoreCase = true)) t = t.removePrefix("json").removePrefix("JSON")
            val end = t.lastIndexOf("```")
            if (end > 0) t = t.substring(0, end)
            t = t.trim()
        }
        return try {
            JSONObject(t)
        } catch (_: Exception) {
            // pehle { se aakhri } tak ka tukda try karo
            try {
                val s = t.indexOf('{')
                val e = t.lastIndexOf('}')
                if (s >= 0 && e > s) JSONObject(t.substring(s, e + 1)) else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun buildUserText(req: JSONObject, profile: Map<String, String>): String {
        val goal = req.optString("goal", "")
        val url = req.optString("url", "")
        val title = req.optString("page_title", "")
        val snap = req.optJSONObject("dom_snapshot") ?: JSONObject()
        val snapStr = JSONObject()
            .put("fields", snap.optJSONArray("fields") ?: JSONArray())
            .put("buttons", snap.optJSONArray("buttons") ?: JSONArray())
            .put("page_text", snap.optString("page_text", "").take(2000))
            .toString()
            .take(6000)
        val hist = req.optJSONArray("history") ?: JSONArray()
        val histLines = ArrayList<String>()
        for (i in 0 until hist.length()) {
            val h = hist.optJSONObject(i) ?: continue
            histLines.add(
                "- ${h.optString("action")} [${h.optString("selector")}] → " +
                    "${h.optString("result")}: ${h.optString("detail", "").take(120)}"
            )
        }
        val historyBlock = if (histLines.isEmpty()) "Pichhle actions: (koi nahi — pehla step)"
        else "Pichhle actions (repeat mat karo jo fail hue):\n" + histLines.takeLast(10).joinToString("\n")
        val profileBlock = if (profile.isEmpty()) {
            "User ka saved profile khaali hai — personal details chahiye to needs_user use karo."
        } else {
            "User ka saved profile (isi se fields bharo, invent mat karo):\n" +
                profile.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
        val stuck = req.optInt("stuck_count", 0)
        return "Goal: $goal\n" +
            "Page: $url — \"$title\"\n\n" +
            "$profileBlock\n\n" +
            "$historyBlock\n\n" +
            "Stuck count: $stuck (2+ ho to naya tareeka socho, repeat nahi)\n\n" +
            "Note: screenshot available nahi hai (standalone text-only mode) — DOM snapshot se kaam chalao.\n\n" +
            "DOM snapshot:\n$snapStr"
    }

    /** Vault profile lao — server → encrypted local cache → khaali. */
    private fun fetchVaultProfile(ctx: Context): Map<String, String> =
        VaultProfileCache.fetch(ctx)
}

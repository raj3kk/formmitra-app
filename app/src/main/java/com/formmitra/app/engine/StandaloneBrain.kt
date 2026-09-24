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
        val profile = fetchVaultProfile(ctx)
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

    /** Groq POST. 2xx → response text; kuch aur → null (caller fallback). */
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
            if (code !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }.ifEmpty { null }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
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

    /** Vault profile (server wala; fail ho to khaali map). */
    private fun fetchVaultProfile(ctx: Context): Map<String, String> {
        return try {
            val p = AgentApi.profile(ctx) ?: return emptyMap()
            val out = HashMap<String, String>()
            val keys = p.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = p.optString(k, "").trim()
                if (v.isNotEmpty()) out[k] = v
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

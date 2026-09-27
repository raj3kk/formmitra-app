package com.formmitra.app.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * v51 GUIDE HELPER + ASK-BEFORE-VISIT.
 *
 * User demand:
 * - "Agent operator link par visit NAHI karega — sirf bolega. Jo user
 *   bolega karne ke liye, usko puchega."
 * - "Browser par ye apply — user jo bolega karne ke liye usko puchega:
 *   'user jo bolega karne ke liye' — kaise kare full step by step batao:
 *   kahan jaise, kahan par kya option milega, kis par click kare, kahan
 *   kya submit kare, login ya register bhi karna hoga, kya process hai,
 *   kya kya lagega, link ke saath, kya detail ya document upload karna
 *   hoga, kaise complete hoga, payment bhi karega — kitna, kaise, kya
 *   process hai"
 *
 * Do features:
 * 1. AskBeforeVisit — link kholne se PEHLE user se puchho
 * 2. StepGuide — "X kaise kare" par full guide banao (AI se)
 */
object GuideHelper {

    private const val TAG = "GuideHelper"

    // v52: buildAskBeforeVisitRequest REMOVED (user order 2026-09-27) —
    // agent automated rahega, link kholne se pehle nahi puchega.

    /**
     * 2. STEP-BY-STEP GUIDE
     *
     * "X kaise kare" — AI se full guide banao:
     * - Kahan jana hai (link)
     * - Kya option milega, kis par click karna hai
     * - Kahan kya submit karna hai
     * - Login/register karna hoga ya nahi
     * - Kya documents lagenge
     * - Payment kitna, kaise
     *
     * @param ctx context
     * @param topic "PMS scholarship apply kaise kare"
     * @return Guide (Hinglish, links ke saath) ya null
     */
    fun generateGuide(
        ctx: Context,
        topic: String
    ): String? {
        return try {
            val prompt = """
                User puchh raha hai: "$topic"
                
                Full step-by-step guide do (Hinglish me, simple words).
                Har guide me YE SAB hona chahiye:
                
                1. 🔗 LINK: Kahan jana hai (official website ka link)
                2. 📝 STEPS: Step-by-step kya karna hai
                   - Kahan par kya option milega
                   - Kis par click karna hai
                   - Kahan kya bharna/submit karna hai
                3. 🔑 LOGIN: Login ya register karna hoga? Kaise?
                4. 📄 DOCUMENTS: Kya documents lagenge? Kaise upload karne hain?
                5. 💰 PAYMENT: Kitna lagega? Kaise pay karna hai? Kya process hai?
                6. ⏱️ TIME: Kitna time lagega? Kab tak hoga?
                7. ⚠️ DHYAN: Kya galtiyan nahi karni hain?
                
                Format: WhatsApp-style, emoji ke saath, short lines.
                Links alag line par do.
            """.trimIndent()
            callGuideAi(ctx, prompt)
        } catch (e: Exception) {
            Log.e(TAG, "guide fail", e)
            null
        }
    }

    private fun callGuideAi(ctx: Context, prompt: String): String? {
        return try {
            val apiKey = getGeminiKey(ctx) ?: return null
            val url = "https://generativelanguage.googleapis.com/v1beta/" +
                "models/gemini-3-flash-preview:generateContent?key=$apiKey"
            val payload = JSONObject()
                .put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", prompt)
                    ))
                ))
                .put("generationConfig", JSONObject()
                    .put("temperature", 0.4)
                    .put("maxOutputTokens", 2000)
                )
            val conn = java.net.URL(url).openConnection()
                as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            val resp = JSONObject(body)
            resp.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
        } catch (e: Exception) {
            Log.e(TAG, "guide AI fail", e)
            null
        }
    }

    private fun getGeminiKey(ctx: Context): String? {
        // BuildConfig ya prefs se — server key yahan nahi hoti.
        // Guide server se bhi ban sakta hai (/api/agent/vision-help jaisa).
        // Abhi ke liye: server endpoint use karo.
        return null
    }

    /**
     * Server se guide banao (API key server par hai).
     */
    fun generateGuideViaServer(ctx: Context, topic: String): String? {
        return try {
            val siteUrl = com.formmitra.app.BuildConfig.SITE_URL.trimEnd('/')
            val url = "$siteUrl/api/agent/guide"
            val payload = JSONObject().put("topic", topic).put("lang", "hinglish")
            val conn = java.net.URL(url).openConnection()
                as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            JSONObject(body).optString("guide", null)?.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "server guide fail", e)
            null
        }
    }
}

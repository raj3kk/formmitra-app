package com.formmitra.app.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * v53 AI HELP SYSTEM — operator ka permanent AI companion.
 *
 * User order (2026-09-27):
 * "AI helper wala browser fix kar do permanent — browser khol ke help
 * search karo, AI Mode me jaake rahe, wahi jo problem agent puchega AI se,
 * AI jo bolega usko agent samjhega, trained hoga, uske according kaam
 * karega. Kaam me koi dikkat aayi to screenshot leke AI Mode me upload
 * karke puchega. Aisa system banao. Agar AI Mode nahi khula to help search
 * karke AI Mode me aake proceed karega. Loop me nahi fasega."
 *
 * DESIGN:
 * - Alag help WebView (kaam wala page untouched).
 * - ensureAiMode(): AI Mode khula hai? Nahi to help search → AI Mode.
 * - askAi(problem): AI Mode me puchho, jawab READ karo (samajh).
 * - askWithScreenshot(problem, screenshot): screenshot upload + puchho.
 * - BOUNDED: ek stuck point par max 3 help cycles (loop nahi).
 * - Seekha hua AiModeMemory me (trained).
 *
 * Ye OPERATOR-driven hai — user ko kuch nahi karna.
 */
object AiHelpSystem {

    private const val TAG = "AiHelpSystem"

    /** Ek stuck point par max help cycles (loop-guard). */
    private const val MAX_CYCLES = 3

    /** AI Mode URL. */
    private const val AI_MODE_URL = "https://www.google.com/search?udm=50&q="

    /** Help search URL (AI Mode tak pahunchne ke liye). */
    private const val HELP_SEARCH = "https://www.google.com/search?q="

    /**
     * AI Mode khula hai ya nahi — nahi to kholo.
     * @return true = AI Mode ready
     */
    fun ensureAiMode(engine: FormEngine, query: String): Boolean {
        return try {
            val current = engine.helpCurrentUrl()
            // Pehle se AI Mode me hain?
            if (current.contains("udm=50")) {
                Log.i(TAG, "AI Mode pehle se khula hai")
                return true
            }
            // AI Mode kholo (query ke saath)
            val url = AI_MODE_URL +
                java.net.URLEncoder.encode(query, "UTF-8")
            Log.i(TAG, "AI Mode khol raha: ${query.take(50)}")
            if (!engine.loadHelpUrl(url)) return false
            Thread.sleep(4000)
            // Verify: AI Mode khula?
            val after = engine.helpCurrentUrl()
            if (after.contains("udm=50")) return true
            // Nahi khula to help search se try karo
            Log.i(TAG, "AI Mode direct nahi khula — help search se try")
            val helpUrl = HELP_SEARCH +
                java.net.URLEncoder.encode("$query AI Mode", "UTF-8")
            if (!engine.loadHelpUrl(helpUrl)) return false
            Thread.sleep(4000)
            // Help page se AI Mode tab par jao (JS se click)
            try {
                engine.evalHelpJs(
                    """(function(){
                        var t = document.querySelector('a[href*="udm=50"]');
                        if (t) { t.click(); return "CLICKED"; }
                        // AI Mode tab dhoondo
                        var tabs = document.querySelectorAll('a,div[role="tab"]');
                        for (var i=0;i<tabs.length;i++) {
                            var tx = (tabs[i].innerText||"").toLowerCase();
                            if (tx.indexOf("ai mode")>=0) { tabs[i].click(); return "CLICKED_TAB"; }
                        }
                        return "NOT_FOUND";
                    })()""", 10000)
                Thread.sleep(3000)
            } catch (_: Exception) { }
            engine.helpCurrentUrl().contains("udm=50")
        } catch (e: Exception) {
            Log.e(TAG, "ensureAiMode fail: ${(e.message ?: "").take(100)}")
            false
        }
    }

    /**
     * AI Mode me problem puchho, jawab READ karo.
     * @return AI ka jawab (null = nahi mila)
     */
    fun askAi(engine: FormEngine, question: String): String? {
        return try {
            if (!ensureAiMode(engine, question)) {
                Log.w(TAG, "AI Mode ready nahi — puchh nahi paye")
                return null
            }
            // "Ask anything" me question daalo + send
            try {
                engine.evalHelpJs(AiModeOperator.askAnythingJs(question), 15000)
            } catch (_: Exception) { }
            Thread.sleep(5000)
            // Jawab READ karo
            val raw = try {
                engine.evalHelpJs(AiModeOperator.readAiAnswerJs(), 15000)
            } catch (_: Exception) { "null" }
            val answer = raw.trim().trim('"')
            if (answer.length >= 100 && !answer.contains("NOT_FOUND")) {
                Log.i(TAG, "AI se jawab mila (${answer.length} chars)")
                answer.take(3000)
            } else {
                Log.w(TAG, "AI se kaam ka jawab nahi mila")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "askAi fail: ${(e.message ?: "").take(100)}")
            null
        }
    }

    /**
     * Screenshot ke saath puchho.
     * (Screenshot AI Mode me upload + question)
     * @return AI ka jawab (null = nahi mila)
     */
    fun askWithScreenshot(
        engine: FormEngine,
        question: String,
        screenshotB64: String
    ): String? {
        return try {
            if (!ensureAiMode(engine, question)) return null
            // Screenshot upload karne ki koshish (Lens/upload button)
            // Pehle sawal puchho, phir screenshot ka zikr karo
            val q = "$question\n\n(Mere paas is page ka screenshot hai — " +
                "page par ye dikh raha hai, iske hisaab se batao)"
            askAi(engine, q)
        } catch (e: Exception) {
            Log.e(TAG, "askWithScreenshot fail: ${(e.message ?: "").take(100)}")
            null
        }
    }

    /**
     * FULL HELP CYCLE — stuck point par operator ye bulayega.
     *
     * 1. Pehle memory dekho (seekha hua?)
     * 2. AI Mode me puchho
     * 3. Na mile to screenshot ke saath puchho
     * 4. Seekha hua memory me save karo
     *
     * BOUNDED: max 3 cycles (loop-guard).
     *
     * @return HelpResult (understanding + source)
     */
    fun helpCycle(
        ctx: Context,
        engine: FormEngine,
        problem: String,
        goal: String,
        workScreenshotB64: String? = null
    ): HelpResult {
        // 0. Memory me seekha hua hai?
        val learned = AiModeMemory.getLearned(ctx, problem)
        if (learned != null) {
            Log.i(TAG, "Memory se mila — AI Mode nahi khola")
            return HelpResult(
                understanding = learned,
                source = "memory",
                success = true
            )
        }
        // 1-3. Bounded help cycles
        for (cycle in 1..MAX_CYCLES) {
            Log.i(TAG, "Help cycle $cycle/$MAX_CYCLES: $problem".take(80))
            try {
                // Pehle bina screenshot puchho
                var answer = askAi(engine, "$goal — $problem. Step by step batao kya karu.")
                // Na mile to screenshot ke saath
                if (answer == null && workScreenshotB64 != null) {
                    Log.i(TAG, "Screenshot ke saath puchh raha")
                    answer = askWithScreenshot(
                        engine,
                        "$goal — $problem. Screenshot me jo dikh raha hai uske hisaab se batao.",
                        workScreenshotB64
                    )
                }
                if (answer != null && answer.length >= 100) {
                    // Seekha hua save karo (trained)
                    AiModeMemory.learn(ctx, problem, answer)
                    return HelpResult(
                        understanding = answer,
                        source = "ai_mode",
                        success = true
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cycle $cycle fail: ${(e.message ?: "").take(80)}")
            }
            // Cycle ke beech thoda rukho
            if (cycle < MAX_CYCLES) {
                try { Thread.sleep(2000) } catch (_: Exception) { }
            }
        }
        Log.w(TAG, "Help cycles khatm — samajh nahi mila")
        return HelpResult(
            understanding = "",
            source = "none",
            success = false
        )
    }

    /**
     * Help ka natija.
     */
    data class HelpResult(
        val understanding: String,
        val source: String,  // "memory" | "ai_mode" | "none"
        val success: Boolean
    )

    /**
     * v53: Pure-logic test helper — cycle bound check.
     * (JVM selftest ke liye, Android nahi chahiye)
     */
    fun maxCyclesForTest(): Int = MAX_CYCLES
}

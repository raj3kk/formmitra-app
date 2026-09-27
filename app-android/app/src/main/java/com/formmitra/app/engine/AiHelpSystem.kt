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
     * (Screenshot AI Mode me UPLOAD + question)
     * v53: asli file upload — pendingUploadFile arm karke AI Mode ke
     * Lens/image button ko click karta hai.
     * @return AI ka jawab (null = nahi mila)
     */
    fun askWithScreenshot(
        engine: FormEngine,
        question: String,
        screenshotB64: String
    ): String? {
        return try {
            if (!ensureAiMode(engine, question)) return null
            // 1. Screenshot AI Mode me UPLOAD karo (asli file upload)
            val uploaded = try {
                engine.uploadWorkScreenshotToAiMode(screenshotB64)
            } catch (_: Exception) { false }
            Log.i(TAG, "Screenshot upload: $uploaded")
            Thread.sleep(3000)
            // 2. Sawal puchho (screenshot ke context ke saath)
            val q = if (uploaded)
                "$question\n\n(Upar jo screenshot upload kiya hai usme jo " +
                "dikh raha hai, uske hisaab se step-by-step batao kya karu)"
            else
                "$question\n\n(Mere paas is page ka screenshot hai — " +
                "page par ye dikh raha hai, iske hisaab se batao)"
            askAi(engine, q)
        } catch (e: Exception) {
            Log.e(TAG, "askWithScreenshot fail: ${(e.message ?: "").take(100)}")
            null
        }
    }

    /**
     * SMART HELP SITUATION — agent khud decide karega kya puchna hai.
     * User order: "agent ko pta ho kha se screenshot kr k kya upload kr k
     * puchna h and kya puchna kya situation h kya age krna"
     *
     * Har field AI ko full context deta hai:
     * - goal: user ka asli kaam kya hai
     * - problem: kya dikkat aayi
     * - currentUrl: KAUNSE link par atka (link ke saath)
     * - pageTitle: page ka naam
     * - attemptedAction: kya karne ki koshish thi (tap/fill/etc)
     * - failCount: kitni baar fail hua
     * - recentAttempts: pehle kya-kya try kiya (dobara wahi na puche)
     */
    data class HelpSituation(
        val goal: String,
        val problem: String,
        val currentUrl: String = "",
        val pageTitle: String = "",
        val attemptedAction: String = "",
        val failCount: Int = 0,
        val recentAttempts: List<String> = emptyList()
    )

    /**
     * SMART QUESTION BUILDER — situation se full-detail sawal banata hai.
     * Agent ko khud pata: kya puchna hai, kya situation hai, kya aage karna hai.
     */
    fun buildSmartQuestion(s: HelpSituation, withScreenshot: Boolean): String {
        val sb = StringBuilder()
        sb.append("Mera goal hai: ${s.goal}\n")
        if (s.currentUrl.isNotEmpty()) {
            sb.append("Main is website/link par hun: ${s.currentUrl}\n")
        }
        if (s.pageTitle.isNotEmpty()) {
            sb.append("Page ka title: ${s.pageTitle}\n")
        }
        if (s.attemptedAction.isNotEmpty()) {
            sb.append("Main ye karne ki koshish kar raha tha: ${s.attemptedAction}\n")
        }
        sb.append("Problem: ${s.problem}\n")
        if (s.failCount > 0) {
            sb.append("Ye ${s.failCount} baar fail ho chuka hai.\n")
        }
        if (s.recentAttempts.isNotEmpty()) {
            sb.append("Pehle ye try kar chuka hun (dobara mat batao):\n")
            s.recentAttempts.take(5).forEach { sb.append("- $it\n") }
        }
        if (withScreenshot) {
            sb.append("\nUpar jo screenshot upload kiya hai, wo KAAM WALE " +
                "browser ka hai — wahi page jahan main atka hun.\n")
        }
        sb.append("\nScreenshot/page dekh ke batao:\n")
        sb.append("1. Page par abhi kya dikh raha hai? (kahan atka hun)\n")
        sb.append("2. Mera goal poora karne ke liye AGLA STEP kya hona chahiye?\n")
        sb.append("3. Kaunsa button/link/field use karun — exact text batao\n")
        sb.append("4. Agar ye page galat hai to sahi page ka URL kya hai?\n")
        sb.append("Step by step, short me batao.")
        return sb.toString()
    }

    /**
     * FULL HELP CYCLE — stuck point par operator ye bulayega.
     *
     * 1. Pehle memory dekho (seekha hua?)
     * 2. AI Mode me puchho (SMART question — full detail)
     * 3. Na mile to screenshot ke saath puchho (ASLI upload)
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
        // Purana signature — HelpSituation me convert karo
        return helpCycle(
            ctx, engine,
            HelpSituation(goal = goal, problem = problem),
            workScreenshotB64
        )
    }

    /**
     * SMART HELP CYCLE — HelpSituation ke saath.
     * Agent khud full detail bhejta hai: link + screenshot + situation.
     *
     * v55 order (user: "groq uska v help le jb jaruri ho"):
     *   1. Memory (seekha hua — free, instant)
     *   2. Groq (key hai to — fast direct API)
     *   3. AI Mode browser (screenshot upload ke saath)
     */
    fun helpCycle(
        ctx: Context,
        engine: FormEngine,
        situation: HelpSituation,
        workScreenshotB64: String? = null
    ): HelpResult {
        // 0. Memory me seekha hua hai?
        val memKey = "${situation.goal} — ${situation.problem}".take(200)
        val learned = AiModeMemory.getLearned(ctx, memKey)
        if (learned != null) {
            Log.i(TAG, "Memory se mila — AI nahi puchha")
            return HelpResult(
                understanding = learned,
                source = "memory",
                success = true
            )
        }
        // 1. GROQ — key hai to seedha puchho (fast, browser ka wait nahi)
        // (User order: "groq uska v help le jb jaruri ho jha p lena chaiye")
        // Jab agent atka ho aur samajh chahiye = jaruri jagah.
        try {
            // Page ka text context ke liye (screenshot Groq ko nahi jata —
            // text API hai; situation + page text kaafi hai)
            val pageText = try {
                engine.evalJs(
                    "document.body ? document.body.innerText.slice(0,1500) : ''",
                    8000
                )
            } catch (_: Exception) { "" }
            val groqAnswer = GroqHelp.askForHelp(ctx, situation, pageText)
            if (groqAnswer != null && groqAnswer.length >= 50) {
                Log.i(TAG, "Groq se samajh mila — memory me save kar raha")
                AiModeMemory.learn(ctx, memKey, groqAnswer)
                return HelpResult(
                    understanding = groqAnswer,
                    source = "groq",
                    success = true
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq help fail: ${(e.message ?: "").take(80)}")
        }
        // 2-4. AI Mode browser — bounded help cycles (screenshot ke saath)
        for (cycle in 1..MAX_CYCLES) {
            Log.i(TAG, "Help cycle $cycle/$MAX_CYCLES: ${situation.problem}".take(80))
            try {
                // SMART question — full detail (link + situation)
                val smartQ = buildSmartQuestion(situation, withScreenshot = false)
                var answer = askAi(engine, smartQ)
                // Na mile to screenshot ke saath (ASLI upload)
                if (answer == null && workScreenshotB64 != null) {
                    Log.i(TAG, "Screenshot ke saath puchh raha")
                    val smartQShot = buildSmartQuestion(situation, withScreenshot = true)
                    answer = askWithScreenshot(
                        engine,
                        smartQShot,
                        workScreenshotB64
                    )
                }
                if (answer != null && answer.length >= 100) {
                    // Seekha hua save karo (trained)
                    AiModeMemory.learn(ctx, memKey, answer)
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

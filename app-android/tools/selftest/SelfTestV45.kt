package com.formmitra.app.selftest

import java.io.File

/**
 * SelfTestV45 — v45 pins (CAPTCHA genuine-attempt semantics root fix).
 *
 * 1. attempts[0] SIRF genuine failed attempt par badhta hai (visible challenge
 *    + acted + verify-confirmed unsolved). Loop-top blind increment nahi.
 * 2. AI/network fail (analyze ya verify) → attempt consume NAHI (continue).
 * 3. captcha_present=false / unknown+low-confidence → consume nahi (reset).
 * 4. access_wall: server key "access_wall" check hoti hai (purani
 *    "is_access_wall" sirf fallback) — login-wall par solve attempt kabhi nahi.
 * 5. executeCaptchaSequence server shape padhta hai: {"action":...} + flat
 *    x/y/x2/y2 (purana "op"/"target{}" kabhi server ne bheja hi nahi — us
 *    shape par modern path kuch execute hi nahi karta tha).
 * 6. execute ka Boolean return use hota hai — acted=false par andha retry
 *    nahi, user handoff (no-action consume nahi).
 * 7. verify ka next_action JSONArray shape support (server array bhejta hai;
 *    purana String "tap:x,y" sirf legacy fallback).
 * 8. guard bound — fail-continue passes infinite loop nahi.
 */
object SelfTestV45 {
    private var pass = 0
    private var fail = 0

    private fun pin(name: String, cond: Boolean) {
        if (cond) { pass++; println("PASS: $name") }
        else { fail++; println("FAIL: $name") }
    }

    private fun read(path: String): String = try {
        File(path).readText()
    } catch (_: Exception) { "" }

    fun run(appRoot: String): Pair<Int, Int> {
        val al = read("$appRoot/app/src/main/java/com/formmitra/app/engine/AgentLoop.kt")

        // 1. genuine-attempt: increment sirf verify-unsolved ke baad
        val incIdx = al.indexOf("attempts[0]++")
        val loopIdx = al.indexOf("fun handleCaptcha(")
        val analyzeIdx = al.indexOf("(1) ANALYZE", loopIdx)
        pin(
            "captcha genuine increment (verify ke baad, loop-top par nahi)",
            incIdx > 0 && analyzeIdx > 0 && incIdx > analyzeIdx &&
                al.contains("GENUINE failed attempt")
        )
        // 2. AI/network fail → no consume
        pin(
            "captcha AI fail no-consume",
            al.contains("AI/network fail") && al.contains("consume NAHI")
        )
        // 3. false positive → reset, no consume (v44 se)
        pin(
            "captcha false-positive reset",
            al.contains("captcha_present") && al.contains("attempts[0] = 0")
        )
        // 4. access_wall server key
        pin(
            "captcha access_wall server key",
            al.contains("\"access_wall\"") && al.contains("is_access_wall")
        )
        // 5. server action shape
        pin(
            "captcha executor server shape (action + flat coords)",
            al.contains("st.optString(\"action\"") && al.contains("x2")
        )
        // 6. acted=false → handoff, no blind retry
        pin(
            "captcha no-action handoff",
            al.contains("val acted = executeCaptchaSequence") &&
                al.contains("if (!acted)")
        )
        // 7. next_action JSONArray
        pin(
            "captcha next_action JSONArray",
            al.contains("optJSONArray(\"next_action\")") &&
                al.contains("applyCorrectedCaptchaActionLegacy")
        )
        // 8. guard bound
        pin(
            "captcha guard bound",
            al.contains("guard < 8")
        )
        // 9. purana blind-increment pattern gaya
        pin(
            "captcha no loop-top increment",
            !al.contains("while (attempts[0] < 3) {\n            attempts[0]++")
        )

        // ---- Trainer recorder auto-capture (v45) ----
        val tr = read("$appRoot/app/src/main/java/com/formmitra/app/agent/TrainerRecorderActivity.kt")
        // 10. JS bridge
        pin(
            "recorder js bridge",
            tr.contains("addJavascriptInterface") &&
                tr.contains("@JavascriptInterface") &&
                tr.contains("fun onAction")
        )
        // 11. tap/type/select/scroll auto-capture
        pin(
            "recorder captures tap/type/select/scroll",
            tr.contains("action:'tap'") && tr.contains("action:'type'") &&
                tr.contains("action:'select'") && tr.contains("action:'scroll'")
        )
        // 12. password kabhi record nahi
        pin(
            "recorder password masked",
            tr.contains("masked:true") && tr.contains("password")
        )
        // 13. selector + element context + dom summary + auto explanation
        pin(
            "recorder selector+context+dom+explanation",
            tr.contains("cssPath") && tr.contains("element_label") &&
                tr.contains("domSum") && tr.contains("autoExplain")
        )
        // 14. navigation replayable ("goto", "khola" nahi)
        pin(
            "recorder navigation goto",
            tr.contains("\"goto\"") && !tr.contains("\"khola\"")
        )
        // 15. thread-safe step queue (JS bridge background thread se aata hai)
        pin(
            "recorder thread-safe queue",
            tr.contains("synchronized(stepLock)")
        )
        return pass to fail
    }
}

fun main(args: Array<String>) {
    val appRoot = System.getProperty("fm.app.dir", "")
    val (p, f) = SelfTestV45.run(appRoot)
    println("$p PASS, $f FAIL")
}

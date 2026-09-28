package com.formmitra.app.selftest

import java.io.File

/**
 * v55 selftest:
 * 1. GroqHelp exists — stuck par Groq se help (key check + prompt + POST).
 * 2. AiHelpSystem helpCycle me Groq order hai (memory → groq → AI Mode).
 * 3. HelpSituation + buildSmartQuestion — full detail (link/screenshot/situation).
 * 4. AgentLoop me smart auto-refresh (2 baar fail par opReload).
 * 5. GroqHelp privacy — password/OTP nahi maangta/bhejta.
 */
fun main() {
    var failures = 0
    fun check(name: String, cond: Boolean) {
        if (cond) println("PASS: $name")
        else { println("FAIL: $name"); failures++ }
    }

    val srcDir = System.getProperty("fm.src.dir")
        ?: "/home/hatch/workspace/formmitra-app/app-android/app/src/main/java/com/formmitra/app"
    val groqHelpSrc = try {
        File("$srcDir/engine/GroqHelp.kt").readText()
    } catch (_: Exception) { "" }
    val aiHelpSrc = try {
        File("$srcDir/engine/AiHelpSystem.kt").readText()
    } catch (_: Exception) { "" }
    val loopSrc = try {
        File("$srcDir/engine/AgentLoop.kt").readText()
    } catch (_: Exception) { "" }

    // ============ 1. GroqHelp exists ============
    check(
        "GroqHelp.kt maujood hai",
        groqHelpSrc.isNotEmpty() && groqHelpSrc.contains("object GroqHelp")
    )
    check(
        "GroqHelp me key check hai (bina key chupchaap skip)",
        groqHelpSrc.contains("Standalone.getKey") &&
            groqHelpSrc.contains("return null")
    )
    check(
        "GroqHelp me help prompt builder hai",
        groqHelpSrc.contains("buildHelpPrompt")
    )
    check(
        "GroqHelp Groq API ko POST karta hai",
        groqHelpSrc.contains("api.groq.com/openai/v1/chat/completions")
    )
    check(
        "GroqHelp 401/429 handle karta hai",
        groqHelpSrc.contains("401") && groqHelpSrc.contains("429")
    )

    // ============ 2. helpCycle me Groq order ============
    check(
        "helpCycle me Groq call hai",
        aiHelpSrc.contains("GroqHelp.askForHelp")
    )
    // Order: memory → groq → AI Mode (groq, memory ke baad, AI Mode se pehle)
    val memIdx = aiHelpSrc.indexOf("AiModeMemory.getLearned")
    val groqIdx = aiHelpSrc.indexOf("GroqHelp.askForHelp")
    val aiModeIdx = aiHelpSrc.indexOf("1..MAX_CYCLES")
    check(
        "Help order: memory → groq → AI Mode",
        memIdx >= 0 && groqIdx > memIdx && aiModeIdx > groqIdx
    )
    check(
        "Groq ka jawab memory me save hota hai (trained)",
        aiHelpSrc.contains("AiModeMemory.learn(ctx, memKey, groqAnswer)")
    )
    check(
        "HelpResult me groq source hai",
        aiHelpSrc.contains("\"groq\"")
    )

    // ============ 3. HelpSituation + smart question ============
    check(
        "HelpSituation data class hai",
        aiHelpSrc.contains("data class HelpSituation")
    )
    check(
        "HelpSituation me link (currentUrl) hai",
        aiHelpSrc.contains("currentUrl")
    )
    check(
        "HelpSituation me failCount + recentAttempts hain",
        aiHelpSrc.contains("failCount") && aiHelpSrc.contains("recentAttempts")
    )
    check(
        "buildSmartQuestion full detail banata hai",
        aiHelpSrc.contains("fun buildSmartQuestion") &&
            aiHelpSrc.contains("AGLA STEP")
    )
    check(
        "AgentLoop HelpSituation bhejta hai (URL + title + tries)",
        loopSrc.contains("HelpSituation(") &&
            loopSrc.contains("currentUrl = currentUrl")
    )

    // ============ 4. Smart auto-refresh ============
    check(
        "2 baar fail par auto-refresh (opReload)",
        loopSrc.contains("stepFails == 2") && loopSrc.contains("engine.opReload()")
    )

    // ============ 5. GroqHelp privacy ============
    check(
        "GroqHelp password/OTP nahi maangta",
        groqHelpSrc.contains("password/OTP") || groqHelpSrc.contains("Kabhi password")
    )
    check(
        "GroqHelp key kabhi log nahi karta",
        !groqHelpSrc.contains("Log.i(TAG, key") &&
            !groqHelpSrc.contains("Log.d(TAG, key")
    )

    // ============ 6. v62 stopRequested idle-block fix ============
    val runServiceSrc = File(srcDir, "engine/FormRunService.kt").readText()
    check(
        "stopService idle par stopRequested nahi lagata (permanent block fix)",
        runServiceSrc.contains("if (activeTaskId != null)") &&
            runServiceSrc.contains("stopRequested = true")
    )

    println("$failures FAILURES")
}

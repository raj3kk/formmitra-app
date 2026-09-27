package com.formmitra.app.selftest

import com.formmitra.app.agent.LearnLogic
import java.io.File

/**
 * v53 selftest:
 * 1. AiHelpSystem MAX_CYCLES bounded hai (loop-guard) — source check.
 * 2. LearnLogic STEP_SMART_RETRY — koi give-up nahi.
 */
fun main() {
    var failures = 0
    fun check(name: String, cond: Boolean) {
        if (cond) println("PASS: $name")
        else { println("FAIL: $name"); failures++ }
    }

    // ============ 1. AiHelpSystem bounded (source check) ============
    // (AiHelpSystem FormEngine par depend karta hai — isliye source text check,
    // jaise SelfTestV46 karta hai)
    val srcDir = System.getProperty("fm.src.dir")
        ?: "/home/hatch/workspace/formmitra-app/app-android/app/src/main/java/com/formmitra/app"
    val aiHelpSrc = try {
        File("$srcDir/engine/AiHelpSystem.kt").readText()
    } catch (_: Exception) { "" }
    // MAX_CYCLES = 3 (ya kam)
    val maxCyclesMatch = Regex(
        """private const val MAX_CYCLES\s*=\s*(\d+)"""
    ).find(aiHelpSrc)
    val maxCycles = maxCyclesMatch?.groupValues?.get(1)?.toIntOrNull() ?: 99
    check(
        "AiHelpSystem MAX_CYCLES bounded (=$maxCycles, <=3)",
        maxCycles in 1..3
    )
    // Loop-guard: for loop MAX_CYCLES tak hi
    check(
        "AiHelpSystem me bounded loop hai",
        aiHelpSrc.contains("1..MAX_CYCLES")
    )
    // ensureAiMode fallback hai (AI Mode nahi khula to help search)
    check(
        "AiHelpSystem me AI Mode fallback (help search) hai",
        aiHelpSrc.contains("HELP_SEARCH") || aiHelpSrc.contains("help search")
    )
    // Memory se seekha hua pehle dekhta hai
    check(
        "AiHelpSystem pehle memory dekhta hai",
        aiHelpSrc.contains("AiModeMemory.getLearned")
    )
    // Seekha hua save karta hai (trained)
    check(
        "AiHelpSystem seekha hua save karta hai",
        aiHelpSrc.contains("AiModeMemory.learn")
    )

    // ============ 2. No give-up ============
    check(
        "step 7 fail → smart retry (user ka '7 baar fail' order)",
        LearnLogic.stepEscalation(7) == LearnLogic.STEP_SMART_RETRY
    )
    check(
        "step 50 fail → smart retry",
        LearnLogic.stepEscalation(50) == LearnLogic.STEP_SMART_RETRY
    )
    check(
        "STEP_SMART_RETRY defined (=2)",
        LearnLogic.STEP_SMART_RETRY == 2
    )

    println("$failures FAILURES")
}

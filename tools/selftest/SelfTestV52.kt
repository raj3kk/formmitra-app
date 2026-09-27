package com.formmitra.app.selftest

import com.formmitra.app.agent.LearnLogic

/**
 * v52 selftest — user orders 2026-09-27:
 * 1. Ask-before-visit REMOVED (automated)
 * 2. AI Mode strategy operator-driven
 * 3. "7 baar fail ab nahi hoga" give-up REMOVED
 * 4. Delete confirmation protection
 */
object SelfTestV52 {
    @JvmStatic
    fun main(args: Array<String>) {
        var failures = 0
        fun check(name: String, cond: Boolean) {
            if (cond) println("PASS $name") else { println("FAIL $name"); failures++ }
        }
        // 1. stepEscalation: 3+ fails = SMART RETRY (give-up nahi)
        check("esc_3_smart_retry",
            LearnLogic.stepEscalation(3) == LearnLogic.STEP_SMART_RETRY)
        check("esc_7_smart_retry",
            LearnLogic.stepEscalation(7) == LearnLogic.STEP_SMART_RETRY)
        check("esc_100_smart_retry",
            LearnLogic.stepEscalation(100) == LearnLogic.STEP_SMART_RETRY)
        check("esc_2_ai_help",
            LearnLogic.stepEscalation(2) == LearnLogic.STEP_AI_HELP)
        check("esc_1_ok",
            LearnLogic.stepEscalation(1) == LearnLogic.STEP_OK)
        // 2. stepEscalation sirf OK/AI_HELP/SMART_RETRY return karta hai —
        // fail-count par user gate (give-up) kabhi nahi.
        var badLevel = false
        for (i in 1..20) {
            val lvl = LearnLogic.stepEscalation(i)
            if (lvl != LearnLogic.STEP_OK &&
                lvl != LearnLogic.STEP_AI_HELP &&
                lvl != LearnLogic.STEP_SMART_RETRY) {
                badLevel = true
            }
        }
        check("no_user_gate_on_fail_count", !badLevel)
        // 3. Constants maujood
        check("smart_retry_const", LearnLogic.STEP_SMART_RETRY == 2)
        println("$failures FAILURES")
    }
}

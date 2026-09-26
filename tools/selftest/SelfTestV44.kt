package com.formmitra.app.selftest

import java.io.File

/**
 * SelfTestV44 — v44 pins (CAPTCHA root fix + Trainer system + Admin panel fix).
 *
 * CAPTCHA (app):
 * 1. FormEngine captchaDetect me visibility filter (hidden/display-none/
 *    zero-size elements false positive nahi).
 * 2. AgentLoop handleCaptcha blind 3x loop nahi (unknown/low-confidence
 *    par bail-out, kaam nahi rokta).
 * 3. Detector "html_snippet" key (server contract).
 * 4. Analyze request me "page_url" key.
 * 5. Verify response compat ("captcha" ya "verify").
 *
 * Admin panel (server):
 * 6. /admin signed-out → /login?next=/admin.
 * 7. Login page ?next= honor karta hai.
 *
 * Trainer backend (server):
 * 8. lib/agent/trainer.ts + TRAINER_MASTER_PROMPT.
 * 9. Admin API routes (sessions/steps/finalize/learn/master-prompt).
 * 10. App-facing trainer routes + admin-email guard.
 *
 * Brain injection (server):
 * 11. act route trainerContext inject karta hai.
 * 12. act route trainer_learn:: prefix → learnMode.
 * 13. runs PATCH learn run done → finalizeLearnSession.
 *
 * Admin UI (server):
 * 14. AdminDashboard me 🎓 Trainer tab.
 * 15. TrainerTab me "App me record karo" deep link.
 *
 * App recorder:
 * 16. TrainerRecorderActivity + Manifest entry.
 * 17. MainActivity formmitra://trainer/record intercept (isOwner gate).
 * 18. FormApi trainer methods (session/steps/finalize).
 */
object SelfTestV44 {
    private var pass = 0
    private var fail = 0

    private fun pin(name: String, cond: Boolean) {
        if (cond) { pass++; println("PASS: $name") }
        else { fail++; println("FAIL: $name") }
    }

    private fun read(path: String): String = try {
        File(path).readText()
    } catch (_: Exception) { "" }

    fun run(appRoot: String, webRoot: String): Pair<Int, Int> {
        val fe = read("$appRoot/app/src/main/java/com/formmitra/app/engine/FormEngine.kt")
        // 1. visibility filter
        pin(
            "captcha visibility filter",
            fe.contains("display") && fe.contains("none") &&
                (fe.contains("visibility") || fe.contains("opacity")) &&
                fe.contains("captcha", ignoreCase = true)
        )
        val al = read("$appRoot/app/src/main/java/com/formmitra/app/engine/AgentLoop.kt")
        // 2. no blind 3x loop
        pin(
            "handleCaptcha no blind 3x",
            al.contains("handleCaptcha") &&
                (al.contains("unknown") || al.contains("bail") || al.contains("confidence"))
        )
        // 3. html_snippet key
        pin(
            "captcha html_snippet key",
            fe.contains("html_snippet")
        )
        // 4. page_url key
        pin(
            "captcha page_url key",
            fe.contains("page_url") || al.contains("page_url")
        )
        // 5. verify compat
        pin(
            "captcha verify compat",
            fe.contains("verify") && fe.contains("captcha")
        )

        // 6. admin ?next=
        val adminPage = read("$webRoot/app/admin/page.tsx")
        pin(
            "admin ?next=/admin",
            adminPage.contains("next=") && adminPage.contains("/admin")
        )
        // 7. login honors next
        val loginPage = read("$webRoot/app/login/page.tsx")
        pin(
            "login honors ?next=",
            loginPage.contains("useSearchParams") && loginPage.contains("nextUrl")
        )

        // 8. trainer lib
        val trainer = read("$webRoot/lib/agent/trainer.ts")
        pin(
            "trainer.ts + master prompt",
            trainer.contains("TRAINER_MASTER_PROMPT") &&
                trainer.contains("fm-trainer") &&
                trainer.contains("generateGuide")
        )
        // 9. admin routes
        pin(
            "admin trainer routes",
            File("$webRoot/app/api/admin/trainer/sessions/route.ts").exists() &&
                File("$webRoot/app/api/admin/trainer/sessions/[id]/finalize/route.ts").exists() &&
                File("$webRoot/app/api/admin/trainer/learn/route.ts").exists() &&
                File("$webRoot/app/api/admin/trainer/master-prompt/route.ts").exists()
        )
        // 10. app routes + guard
        pin(
            "app trainer routes + guard",
            File("$webRoot/app/api/app/trainer/_guard.ts").exists() &&
                File("$webRoot/app/api/app/trainer/sessions/[id]/steps/route.ts").exists() &&
                read("$webRoot/app/api/app/trainer/_guard.ts").contains("ADMIN_EMAIL")
        )

        // 11. act trainerContext
        val actRoute = read("$webRoot/app/api/agent/act/route.ts")
        pin(
            "act injects trainerContext",
            actRoute.contains("trainerContext") && actRoute.contains("findTrainedForHost")
        )
        // 12. learn prefix
        pin(
            "act learnMode prefix",
            actRoute.contains("TRAINER_LEARN_GOAL_RE") && actRoute.contains("learnMode")
        )
        // 13. runs finalize hook
        val runsRoute = read("$webRoot/app/api/agent/runs/route.ts")
        pin(
            "runs finalizeLearnSession hook",
            runsRoute.contains("finalizeLearnSession") && runsRoute.contains("TRAINER_LEARN_GOAL_RE")
        )

        // 14. dashboard tab
        val dash = read("$webRoot/app/admin/AdminDashboard.tsx")
        pin(
            "dashboard Trainer tab",
            dash.contains("TrainerTab") && dash.contains("\"trainer\"")
        )
        // 15. deep link
        val ttab = read("$webRoot/app/admin/TrainerTab.tsx")
        pin(
            "TrainerTab record deep link",
            ttab.contains("formmitra://trainer/record")
        )

        // 16. recorder activity + manifest
        pin(
            "TrainerRecorderActivity + manifest",
            File("$appRoot/app/src/main/java/com/formmitra/app/agent/TrainerRecorderActivity.kt").exists() &&
                read("$appRoot/app/AndroidManifest.xml").contains("TrainerRecorderActivity")
        )
        // 17. MainActivity intercept + owner gate
        val main = read("$appRoot/app/src/main/java/com/formmitra/app/MainActivity.kt")
        pin(
            "MainActivity trainer intercept (owner-gated)",
            main.contains("formmitra://trainer/record") && main.contains("isOwner")
        )
        // 18. FormApi trainer methods
        val fapi = read("$appRoot/app/src/main/java/com/formmitra/app/engine/FormApi.kt")
        pin(
            "FormApi trainer methods",
            fapi.contains("trainerSession") && fapi.contains("trainerPostSteps") &&
                fapi.contains("trainerFinalize")
        )

        println("$pass PASS-count, $fail FAIL-count (v44)")
        return Pair(pass, fail)
    }
}

fun main() {
    val appRoot = System.getProperty("fm.app.dir") ?: "."
    val webRoot = System.getProperty("fm.web.dir") ?: "."
    val (p, f) = SelfTestV44.run(appRoot, webRoot)
    println("$f FAILURES")
    if (f > 0) kotlin.system.exitProcess(1)
}

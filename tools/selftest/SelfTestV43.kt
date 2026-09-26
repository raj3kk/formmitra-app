package com.formmitra.app.selftest

import java.io.File

/**
 * SelfTestV43 — v43 pins (cross-trigger root fix + Work tab merge).
 *
 * 1. Server: updateAgentRun cascade me "in_progress" shamil
 *    (runs.ts — delete ke baad task zinda rehne ka root cause).
 * 2. Server: GET /api/app/form-tasks cancelled exclude karta hai.
 * 3. Server: /next me new-wins logic (freshQueued + stale cancel).
 * 4. Server: [id] PATCH me "cancelled" support.
 * 5. App: FormEngine desktop mode default ON.
 * 6. App: WorkTabView exists (Agent+History merge).
 * 7. App: MainActivity me "📋 Kaam" tab, Agent/History nav se hate.
 * 8. App: AgentApi.deleteTask + cancelTask methods.
 * 9. App: AgentChatView.openWork + startFreshWork + onBackToWork.
 * 10. App: PendingNewTask + addSwitchWorkCard (naya kaam purana rok ke).
 * 11. App UI: FmTheme design system (emerald+gold, kala nahi) + richHeader.
 * 12. App UI: FmToast animated toasts (success/warning/error/info).
 * 13. App UI: rich tab bar pills + hide-on-scroll-down.
 * 14. App UI: scroll listeners (Home/Work/Profile/Chat/WebView).
 * 15. App UI: rich headers sab screens par.
 */
object SelfTestV43 {
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
        // 1. Server cascade fix
        val runsTs = read("$webRoot/lib/agent/runs.ts")
        pin(
            "server cascade me in_progress",
            runsTs.contains("\"in_progress\"") &&
                runsTs.contains(".in(\"status\", [\"queued\", \"in_progress\"")
        )
        // 2. GET list cancelled exclude
        val listRoute = read("$webRoot/app/api/app/form-tasks/route.ts")
        pin(
            "GET form-tasks cancelled exclude",
            listRoute.contains(".neq(\"status\", \"cancelled\")")
        )
        // 3. /next new-wins
        val nextRoute = read("$webRoot/app/api/app/form-tasks/next/route.ts")
        pin(
            "/next new-wins (freshQueued)",
            nextRoute.contains("freshQueued") && nextRoute.contains("hasFresh")
        )
        pin(
            "/next stale in_progress cancel",
            nextRoute.contains("superseded") || nextRoute.contains("staleId")
        )
        pin(
            "/next newest queued first",
            nextRoute.contains(".order(\"created_at\", { ascending: false })")
        )
        // 4. [id] PATCH cancelled
        val idRoute = read("$webRoot/app/api/app/form-tasks/[id]/route.ts")
        pin(
            "[id] PATCH cancelled support",
            idRoute.contains("body.status === \"cancelled\"")
        )
        // 5. Desktop default ON
        val engine = read("$appRoot/app/src/main/java/com/formmitra/app/engine/FormEngine.kt")
        pin(
            "desktop mode default ON",
            engine.contains("setDesktopMode(!task.optBoolean(\"no_desktop\", false))")
        )
        // 6. WorkTabView exists
        val workTab = read("$appRoot/app/src/main/java/com/formmitra/app/agent/WorkTabView.kt")
        pin("WorkTabView exists", workTab.contains("class WorkTabView"))
        pin(
            "WorkTabView me chat/cancel/delete",
            workTab.contains("onOpenWorkChat") &&
                workTab.contains("confirmCancelWork") &&
                workTab.contains("confirmDeleteWork")
        )
        pin(
            "WorkTabView me agent guide",
            workTab.contains("showAgentGuide")
        )
        // 7. MainActivity tabs
        val main = read("$appRoot/app/src/main/java/com/formmitra/app/MainActivity.kt")
        pin(
            "Kaam tab joda",
            main.contains("Triple(\"📋\", \"Kaam\", \"/work\")")
        )
        pin(
            "Agent/History nav se hate",
            !main.contains("\"💬 Agent\" to \"/agent\"") &&
                !main.contains("\"History\" to \"/history\"")
        )
        pin(
            "showAgentChatForWork",
            main.contains("fun showAgentChatForWork")
        )
        // 8. AgentApi methods
        val api = read("$appRoot/app/src/main/java/com/formmitra/app/agent/AgentApi.kt")
        pin(
            "AgentApi.deleteTask",
            api.contains("fun deleteTask(")
        )
        pin(
            "AgentApi.cancelTask",
            api.contains("fun cancelTask(")
        )
        // 9. AgentChatView methods
        val chat = read("$appRoot/app/src/main/java/com/formmitra/app/agent/AgentChatView.kt")
        pin(
            "openWork + startFreshWork",
            chat.contains("fun openWork(") && chat.contains("fun startFreshWork(")
        )
        pin("onBackToWork", chat.contains("onBackToWork"))
        pin(
            "← Kaam back button",
            chat.contains("\"← Kaam\"")
        )
        // 10. Switch-work card
        pin(
            "PendingNewTask + addSwitchWorkCard",
            chat.contains("PendingNewTask") && chat.contains("fun addSwitchWorkCard")
        )
        // 11. v43 UI: FmTheme design system (rich, no black)
        val theme = read("$appRoot/app/src/main/java/com/formmitra/app/agent/FmTheme.kt")
        pin(
            "FmTheme exists + no black",
            theme.contains("object FmTheme") &&
                theme.contains("EMERALD_DEEP") &&
                theme.contains("GOLD") &&
                !theme.contains("\"#000000\"") &&
                !theme.contains("Color.BLACK")
        )
        pin(
            "FmTheme richHeader + statusBadge",
            theme.contains("fun Context.richHeader(") &&
                theme.contains("fun Context.statusBadge(")
        )
        // 12. v43 UI: FmToast animated toasts
        val fmToast = read("$appRoot/app/src/main/java/com/formmitra/app/agent/FmToast.kt")
        pin(
            "FmToast animated (slide+fade)",
            fmToast.contains("object FmToast") &&
                fmToast.contains("ObjectAnimator") &&
                fmToast.contains("SUCCESS") &&
                fmToast.contains("WARNING") &&
                fmToast.contains("ERROR")
        )
        // 13. v43 UI: rich tab bar (icon+label pills, emerald selected)
        val mainAct = read("$appRoot/app/src/main/java/com/formmitra/app/MainActivity.kt")
        pin(
            "rich tab bar pills",
            mainAct.contains("Triple(\"🏠\", \"Home\"") &&
                mainAct.contains("selectedTabBg()") &&
                mainAct.contains("unselectedTabBg()") &&
                mainAct.contains("fun onContentScrolled(")
        )
        pin(
            "tab bar hide on scroll down",
            mainAct.contains("fun onContentScrolled(") &&
                mainAct.contains("navHidden") &&
                mainAct.contains("translationY(bar.height")
        )
        // 14. v43 UI: scroll listeners in views
        val home = read("$appRoot/app/src/main/java/com/formmitra/app/agent/HomeView.kt")
        val work = read("$appRoot/app/src/main/java/com/formmitra/app/agent/WorkTabView.kt")
        val profile = read("$appRoot/app/src/main/java/com/formmitra/app/agent/ProfileView.kt")
        pin(
            "scroll→tab-hide in Home/Work/Profile/Chat/WebView",
            home.contains("onContentScrolled(") &&
                work.contains("onContentScrolled(") &&
                profile.contains("onContentScrolled(") &&
                chat.contains("onContentScrolled(") &&
                mainAct.contains("setOnScrollChangeListener")
        )
        // 15. v43 UI: rich headers on screens
        pin(
            "rich headers (Home/Work/Profile/Chat)",
            home.contains("richHeader(") &&
                work.contains("richHeader(") &&
                profile.contains("richHeader(") &&
                chat.contains("headerGradient()")
        )
        println("-> selftest_v43 PASS: $pass FAIL: $fail")
        return pass to fail
    }
}

fun main() {
    val appRoot = System.getenv("FM_APP")
        ?: "/home/hatch/workspace/formmitra-app/app-android"
    val webRoot = System.getenv("FM_WEB")
        ?: "/home/hatch/workspace/repos/formmitra"
    SelfTestV43.run(appRoot, webRoot)
}

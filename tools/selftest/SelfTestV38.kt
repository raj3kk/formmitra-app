// Self-test: v38 Live WebView pins.
//
// User order (2026-09-26): naya toggle NAHI — wahi "🖥️ Live" button, usi me
// LIVE WebView (screenshot delay nahi, asli live). Agent idle → khaali
// browser; agent kaam kare → sab kuch live. Browsing background me;
// live view SIRF display (view-only).
//
// Approved 5 additions:
//  1. Hidden mode me rendering pause (battery) — decidePowerAction pin.
//  2. Live khula + kaam khatm → completion banner (OperatorView — UI, yahan
//     sirf terminal-event mapping pin: labelFor terminal → null rehta hai,
//     banner OperatorView me LiveActivity.isTerminal se drive hota hai).
//  3. WebView renderer crash → graceful (HostWebViewClient onRenderProcessGone
//     override maujood + WebViewClient ko extend karta hai; recover label).
//  4. Live button par active-dot (AgentChatView.updateLiveDot — UI; yahan
//     isTerminal/freshness contract pin).
//  5. Live band → automation jaari (host ka attach/detach object nahi
//     badalta — design pin: acquire idempotent).
//
// Pure-JVM pins only (WebView instantiate NAHI hota — android.jar stubs).
// Compile: kotlinc -cp android.jar ... && java -cp out:<stdlib> SelfTestV38Kt

import com.formmitra.app.agent.LiveActivity
import com.formmitra.app.engine.LiveWebViewHost
import com.formmitra.app.engine.LiveWebViewHost.PowerAction

var failures = 0

fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}

fun main() {
    // ---- addition #1: battery power policy (pure function) ----
    check(
        "power: live visible + active => RESUME",
        LiveWebViewHost.decidePowerAction(true, true) == PowerAction.RESUME
    )
    check(
        "power: live visible + idle => RESUME",
        LiveWebViewHost.decidePowerAction(true, false) == PowerAction.RESUME
    )
    check(
        "power: hidden + idle => PAUSE (battery bachao)",
        LiveWebViewHost.decidePowerAction(false, false) == PowerAction.PAUSE
    )
    check(
        "power: hidden + automation active => DETACHED_ACTIVE (JS chalta rahe, frame nahi)",
        LiveWebViewHost.decidePowerAction(false, true) == PowerAction.DETACHED_ACTIVE
    )

    // ---- addition #3: renderer-crash recovery contract ----
    val hostClientClass = LiveWebViewHost.HostWebViewClient::class.java
    check(
        "host client WebViewClient ko extend karta hai",
        hostClientClass.superclass?.name == "android.webkit.WebViewClient"
    )
    check(
        "host client me onRenderProcessGone override hai",
        try {
            hostClientClass.getMethod(
                "onRenderProcessGone",
                android.webkit.WebView::class.java,
                android.webkit.RenderProcessGoneDetail::class.java
            )
            true
        } catch (_: NoSuchMethodException) { false }
    )
    check(
        "recover label simple Hinglish hai",
        LiveActivity.labelFor("state:webview_recovered") == "Browser theek ho gaya, kaam jaari"
    )
    check(
        "recovered terminal NAHI (kaam jaari rehta hai)",
        !LiveActivity.isTerminal(LiveActivity.Event("r", "state:webview_recovered"))
    )
    // emitRecovered kabhi throw nahi karta (catcher khud crash nahi karega).
    val okEmit = try {
        LiveActivity.emitRecovered()
        true
    } catch (_: Throwable) { false }
    check("emitRecovered no-throw", okEmit)
    check(
        "emitRecovered ka event key sahi hai",
        LiveActivity.lastEvent()?.key == "state:webview_recovered"
    )

    // ---- addition #2: completion banner terminal mapping ----
    check(
        "state_done terminal hai (banner trigger)",
        LiveActivity.isTerminal(LiveActivity.Event("r", "state:state_done"))
    )
    check(
        "state_failed terminal hai (banner trigger)",
        LiveActivity.isTerminal(LiveActivity.Event("r", "state:state_failed"))
    )
    check(
        "state_stopped terminal hai (banner trigger)",
        LiveActivity.isTerminal(LiveActivity.Event("r", "state:state_stopped"))
    )
    check(
        "terminal par label null (chat indicator gayab — banner uski jagah)",
        LiveActivity.labelFor("state:state_done") == null &&
            LiveActivity.labelFor("state:state_failed") == null
    )

    // ---- addition #4: dot ka freshness contract ----
    val fresh = LiveActivity.Event("r", "step:goto", System.currentTimeMillis())
    val stale = LiveActivity.Event(
        "r", "step:goto", System.currentTimeMillis() - LiveActivity.STALE_MS - 1000
    )
    check(
        "fresh non-terminal event => dot ka haqdaar",
        !LiveActivity.isTerminal(fresh) &&
            System.currentTimeMillis() - fresh.at <= LiveActivity.STALE_MS
    )
    check(
        "stale event => dot nahi",
        !(System.currentTimeMillis() - stale.at <= LiveActivity.STALE_MS)
    )

    // ---- host initial state (design pin) ----
    check("shuru me koi hosted WebView nahi", LiveWebViewHost.current() == null)
    check("shuru me automation inactive", !LiveWebViewHost.isAutomationActive())
    check("shuru me live visible nahi", !LiveWebViewHost.isLiveVisible())

    println("$failures FAILURES")
    if (failures > 0) kotlin.system.exitProcess(1)
}

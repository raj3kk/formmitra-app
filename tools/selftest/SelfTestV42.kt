// SelfTestV42 — v42 pins: indexed element tap (Hindi pages par sahi tap),
// visibleSummary button-text bug fix.
import java.io.File

var failures42 = 0
fun check42(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures42++ }
}

fun main() {
    val base = System.getenv("FM_APP")
        ?: "/home/hatch/workspace/formmitra-app/app-android"
    val engine = File("$base/app/src/main/java/com/formmitra/app/engine/FormEngine.kt").readText()
    val loop = File("$base/app/src/main/java/com/formmitra/app/engine/AgentLoop.kt").readText()

    // 1. Snapshot me unified indexed elements array
    check42(
        "snapshot me elements[] with idx",
        engine.contains("var elements=[];") &&
            engine.contains("elements.push({idx:")
    )
    // 2. elements snapshot me pehle (truncation se bachne ke liye)
    check42(
        "elements snapshot order me pehle",
        engine.contains("elements:elements,fields:fields")
    )
    // 3. domSnapshot elements cache karta hai
    check42(
        "domSnapshot lastElements cache",
        engine.contains("private var lastElements") &&
            engine.contains("lastElements = els")
    )
    // 4. clickEl me index-mode
    check42(
        "clickEl index-mode tap",
        engine.contains("s.selectorMode == \"index\"") &&
            engine.contains("lastElements.optJSONObject(idx)")
    )
    // 5. visibleSummary button "text" padhta hai (bug fix)
    check42(
        "visibleSummary button text (not label)",
        loop.contains("b.optString(\"text\", \"\")") &&
            !loop.contains("b.optString(\"label\", \"\")")
    )

    // 6. Server: SELECTOR_MODES me "index"
    val webBase = System.getenv("FM_WEB") ?: "/home/hatch/workspace/repos/formmitra"
    val act = File("$webBase/lib/agent/act.ts").readText()
    check42(
        "server SELECTOR_MODES me index",
        act.contains("\"index\"")
    )
    // 7. Server: prompt me index-mode instructions
    val prompts = File("$webBase/lib/agent/prompts.ts").readText()
    check42(
        "prompt me index-mode + Hindi guidance",
        prompts.contains("INDEX-MODE") && prompts.contains("transliteration")
    )

    // 8. v42b: notification tap → OperatorView (live view), MainActivity nahi
    val svc = File("$base/app/src/main/java/com/formmitra/app/engine/FormRunService.kt").readText()
    check42(
        "notification tapIntent → OperatorView",
        svc.contains("fun tapIntent()") &&
            svc.contains("com.formmitra.app.agent.OperatorView::class.java") &&
            !svc.contains("Intent(this, MainActivity::class.java).apply")
    )
    // 9. v42b: "Chal raha kaam dekho" action → OperatorView
    check42(
        "dekho action → OperatorView",
        svc.contains("9101") && svc.contains("OperatorView::class.java")
    )
    // 10. v42b: Purane Kaam sorted (chal raha pehle)
    val chatView = File("$base/app/src/main/java/com/formmitra/app/agent/AgentChatView.kt").readText()
    check42(
        "past work sorted: running first",
        chatView.contains("thenByDescending") && chatView.contains("rank(it)")
    )
    // 11. v42b: WebView popup support (ServicePlus)
    check42(
        "WebChromeClient onCreateWindow + JS dialogs",
        engine.contains("onCreateWindow") &&
            engine.contains("setSupportMultipleWindows(true)") &&
            engine.contains("onJsAlert") &&
            engine.contains("onJsConfirm")
    )
    // 12. v42c: Purane Kaam me bulk delete (sab select → ek baar me delete)
    check42(
        "bulk delete: select-all + delete button",
        chatView.contains("Sab select karo") &&
            chatView.contains("Delete karo (") &&
            chatView.contains("fm-bulk-delete")
    )
    // 13. v42c: bulk delete me chal raha kaam excluded
    check42(
        "bulk delete: running excluded",
        chatView.contains("val deletable = tasks.filter { !isRunning(it) }")
    )

    println("$failures42 FAILURES")
    if (failures42 > 0) kotlin.system.exitProcess(1)
}

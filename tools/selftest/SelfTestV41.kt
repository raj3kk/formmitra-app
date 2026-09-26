// SelfTestV41 — v41 pins: history isolation (track-type namespace), user
// delete (chat + work), agent-choose hatao, category→kaam restructure.
// Source-grep pins (UI files Android par compile hote hain — yahan sirf
// structure verify hota hai).
import java.io.File

var failures41 = 0
fun check41(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures41++ }
}

fun main() {
    val base = System.getenv("FM_APP")
        ?: "/home/hatch/workspace/formmitra-app/app-android"
    val chat = File("$base/app/src/main/java/com/formmitra/app/agent/AgentChatView.kt").readText()
    val card = File("$base/app/src/main/java/com/formmitra/app/agent/CardFlow.kt").readText()

    // 1. histKey me trackingType — chat_history_<cat>_<type>
    check41(
        "histKey me trackingType param",
        chat.contains("fun histKey(cat: String, type: String?)")
    )
    check41(
        "history key format me type",
        chat.contains("chat_history_\${cat}_\${type ?: \"main\"}")
    )
    // 2. work memory namespace: track_<type>
    check41(
        "workKeyFor track-type namespace",
        chat.contains("fun workKeyFor(cat: String?, type: String?)") &&
            chat.contains("\"track_\${type ?: \"main\"}\"")
    )
    // 3. trackingType persist/restore
    check41(
        "rememberTrackingType exists + startCategoryChat me call",
        chat.contains("fun rememberTrackingType(") &&
            chat.contains("rememberTrackingType(trackingType)")
    )
    check41(
        "restoreLastCategory type restore karta hai",
        chat.contains("getString(\"last_tracking_type\"")
    )
    // 4. user delete: chat
    check41(
        "confirmClearChat + clearCurrentChat exist",
        chat.contains("fun confirmClearChat()") && chat.contains("fun clearCurrentChat()")
    )
    // 5. user delete: work (server cancelled + local pending saaf)
    check41(
        "confirmDeleteTask exists + cancelled report",
        chat.contains("fun confirmDeleteTask(") &&
            chat.contains("\"cancelled\", 0")
    )
    check41(
        "delete par active kaam ka guard",
        chat.contains("Pehle kaam band karo")
    )
    // 6. agent-choose hatao: chooser seedha manual form
    check41(
        "showCreateChooser seedha manual form (koi Agent-se button nahi)",
        card.contains("fun showCreateChooser(") &&
            card.contains("showManualForm(act, prefillDetails, onCreated)") &&
            !card.contains("Agent se (एजेंट से)")
    )
    // 7. category → kaam: Purane Kaam pehle category chunta hai
    check41(
        "showPastWork category→kaam flow",
        chat.contains("fun pickCategoryThenPastWork()") &&
            chat.contains("fun loadPastWork(cat: String)")
    )
    // 8. delete button header me
    check41(
        "header me chat-saaf button",
        chat.contains("confirmClearChat()")
    )
    // 9. v41 cross-trigger root fix: WakeWorker me fresh task PEHLE,
    // stale pending clear.
    val wake = File("$base/app/src/main/java/com/formmitra/app/WakeWorker.kt").readText()
    val freshIdx = wake.indexOf("val freshTask = try {")
    val pendingIdx = wake.indexOf("val pending = AgentResume.checkPending(ctx)")
    check41(
        "WakeWorker: fresh task claim pending-check se PEHLE",
        freshIdx >= 0 && pendingIdx >= 0 && freshIdx < pendingIdx
    )
    check41(
        "WakeWorker: fresh task par stale pending clear",
        wake.contains("fresh task aaya — stale pending clear") &&
            wake.contains("AgentResume.clear(ctx)")
    )

    println("$failures41 FAILURES")
    if (failures41 > 0) kotlin.system.exitProcess(1)
}

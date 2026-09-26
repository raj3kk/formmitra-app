package com.formmitra.app.agent

import java.util.concurrent.CopyOnWriteArrayList

/**
 * v36 — LIVE ACTIVITY INDICATOR (user order 2026-09-26):
 *
 * "User ko chat me dikhe jab agent kaam karega — kya kar raha hai,
 *  neeche show ho: visit website, finding website, filling form —
 *  sara kuch. Likha hua MESSAGE nahi, sirf jaise 'thinking' me
 *  thinking show hota hai par message nahi aata — waise hi."
 *
 * Design:
 *  - Ye CHAT MESSAGE NAHI HAI. Transient status line hai (typing/
 *    thinking indicator jaisi) — messageList me ADD NAHI hota, history
 *    me SAVE NAHI hota, RunSummaryStore me bhi nahi jata.
 *  - AgentLoop (app-local engine) har step/gate/state par emit karta hai;
 *    AgentChatView visible ho tab listener se indicator dikhata/chhupata hai.
 *  - Server-step fallback: existing 30s pollTaskStatus — active run dikhe
 *    aur koi fresh LiveActivity event na ho to generic label.
 *  - Naya heavy infra NAHI — in-process bus + existing polling/FCM.
 *
 * Thread-safe: engine background thread se emit, UI listener khud
 * main-thread par post kare.
 */
object LiveActivity {

    /** Transient event — koi bhi persist nahi hota. */
    data class Event(
        val runId: String,
        /** "step:<type>" | "gate:<kind>" | "state:<state>" */
        val key: String,
        val at: Long = System.currentTimeMillis()
    )

    const val STATE_STARTED = "state_started"
    const val STATE_DONE = "state_done"
    const val STATE_FAILED = "state_failed"
    const val STATE_STOPPED = "state_stopped"
    /** v38: WebView renderer crash ke baad host ne recover kiya. */
    const val STATE_RECOVERED = "webview_recovered"

    /** Terminal keys — in par indicator GAYAB (spec point 4). */
    val TERMINAL_KEYS = setOf("state:state_done", "state:state_failed", "state:state_stopped")

    /** Bina fresh event ke itne ms baad indicator stale maan kar chhupao. */
    const val STALE_MS = 90_000L

    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()
    @Volatile private var last: Event? = null

    fun addListener(l: (Event) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Event) -> Unit) { listeners.remove(l) }

    fun lastEvent(): Event? = last

    private fun publish(e: Event) {
        last = e
        for (l in listeners) {
            try { l(e) } catch (_: Exception) { }
        }
    }

    /** AgentLoop: har step execute hone se PEHLE. [stepType] = server action. */
    @JvmStatic
    fun emitStep(runId: String, stepType: String) {
        val t = stepType.trim().ifEmpty { "work" }
        publish(Event(runId.ifEmpty { "run" }, "step:$t"))
    }

    /** Gate khulne par — gate-specific label (spec point 4). */
    @JvmStatic
    fun emitGate(runId: String, gateKind: String) {
        val k = gateKind.trim().ifEmpty { "wait" }
        publish(Event(runId.ifEmpty { "run" }, "gate:$k"))
    }

    /** Run state: state_started | state_done | state_failed | state_stopped. */
    @JvmStatic
    fun emitState(runId: String, state: String) {
        publish(Event(runId.ifEmpty { "run" }, "state:$state"))
    }

    /** v38: renderer crash ke baad browser theek — chat status line par. */
    @JvmStatic
    fun emitRecovered() {
        publish(Event("run", "state:$STATE_RECOVERED"))
    }

    fun isTerminal(e: Event): Boolean = e.key in TERMINAL_KEYS

    /**
     * Fixed label map — har step-type ka EK label, SIMPLE Hinglish,
     * koi technical jargon nahi (spec point 3).
     *
     * Returns null = indicator CHHUPAO (terminal state / internal noise).
     */
    @JvmStatic
    fun labelFor(key: String): String? {
        val k = key.trim()
        val bare = k.substringAfter(":", k)
        // Terminal state — gayab.
        if (k in TERMINAL_KEYS) return null
        // Gate labels — gate-specific (spec point 4).
        if (k.startsWith("gate:")) {
            return when (bare) {
                "otp" -> "OTP ka intezaar hai"
                "payment" -> "Payment approval ka intezaar hai"
                "choice", "option_choice" -> "Tumhare jawab ka intezaar hai"
                "detail", "details", "input", "fields", "field" -> "Detail ka intezaar hai"
                "login" -> "Login ka intezaar hai"
                "document", "doc" -> "Document ka intezaar hai"
                "destructive", "destructive_confirm" -> "Confirm ka intezaar hai"
                "device_auth", "auth" -> "Verification ka intezaar hai"
                "stuck", "needs_user", "attention" -> "Rukawat aayi — tumse poochh raha hai"
                else -> "Tumhare jawab ka intezaar hai"
            }
        }
        if (k.startsWith("state:")) {
            return when (bare) {
                STATE_STARTED -> "Kaam shuru ho raha hai"
                // v38: renderer crash recovery — terminal NAHI, kaam jaari.
                STATE_RECOVERED -> "Browser theek ho gaya, kaam jaari"
                else -> null
            }
        }
        // Step labels.
        return when (bare) {
            "goto", "set_desktop" -> "Website khol raha hai"
            "research" -> "Website dhoondh raha hai"
            "fill" -> "Form bhar raha hai"
            "select", "toggle" -> "Option chun raha hai"
            "click", "press" -> "Button daba raha hai"
            "upload" -> "Document laga raha hai"
            "scroll" -> "Page dekh raha hai"
            "screenshot" -> "Jaanch raha hai"
            "captcha_detect" -> "Captcha dekh raha hai"
            "captcha_solve", "captcha" -> "Captcha solve kar raha hai"
            "verify_submit" -> "Submit se pehle jaanch raha hai"
            "wait_for_text", "wait_for_element", "wait_for_navigation", "wait" -> "Intezaar kar raha hai"
            "back" -> "Peeche ja raha hai"
            "forward" -> "Aage badh raha hai"
            "preflight_plan", "preflight" -> "Plan bana raha hai"
            "pattern_replay" -> "Seekha hua kaam dohra raha hai"
            "ai_diagnosis", "ai_single_step", "ai" -> "Soch raha hai"
            "precheck" -> "Taiyaari kar raha hai"
            "site_memory", "fieldmap", "correct_field" -> "Details taiyaar kar raha hai"
            // Internal log-only noise — indicator me flash mat karo.
            "ladder", "ai_usage", "category" -> null
            else -> "Kaam kar raha hai"
        }
    }
}

package com.formmitra.app.engine

/**
 * UserPrompt — AgentLoop (background thread) aur UI ke beech blocking handoff.
 *
 * Flow: loop ko OTP/input/choice/payment/document/login chahiye →
 * [UserPrompt.ask] (blocking, timeout ke saath) → AgentChatView/MainActivity
 * poll karke dialog dikhata hai → user bharta hai → [UserPrompt.answer] →
 * loop aage badhta hai.
 *
 * kinds: otp | input | choice | payment | document | login
 * - document: user vault se doc chunta hai ya naya upload karta hai.
 *   Answer: {approved, doc: "<filename>"} — sirf FILENAME jata hai,
 *   content kabhi nahi (engine upload se pehle locally decrypt karta hai).
 * - login: username+password dialog (mic NAHI). Dono values sensitive —
 *   OTP/password ki tarah KABHI server/AI/userProvided/history me nahi jati,
 *   sirf page me locally bhari jati hain, run ke baad bhool jao.
 *
 * Timeout/cancel pe ask null deta hai → loop needs_user pe terminal finish.
 * Server ka status "running" hi rehta hai; prompt device-local hai.
 */
object UserPrompt {

    data class Request(
        val runId: String,
        val kind: String,          // otp | input | choice | payment | document | login | device_auth
        val title: String,
        val message: String,
        val fields: List<Field> = emptyList(),
        val options: List<String> = emptyList(),
        val payment: Payment? = null,
        val docType: String = "",  // kind='document' — kaun sa doc chahiye (hint)
        val timeoutSec: Long = 600,
        // L1-UPGRADE: pehle se pata values dialog me pre-filled dikhao
        // (DetailStore auto-resolve se). Khali wahi user bharega.
        val prefill: Map<String, String> = emptyMap()
    )

    data class Field(val key: String, val label: String, val type: String)
    // type: otp | text | email | phone | number

    data class Payment(
        val amount: String,        // "499.00"
        val merchant: String,
        val upiId: String          // "" = pata nahi, choice se puchna
    )

    private val lock = Object()

    @Volatile private var pending: Request? = null
    @Volatile private var answerJson: String? = null
    @Volatile private var answeredRun: String = ""

    // ---------- K4: detail-request loop hooks ----------
    //
    // FmApp.onCreate me register hote hain:
    //  - raised: PendingPromptStore me entry + "ek detail chahiye" notification
    //  - resolved: entry hatao + notification cancel
    // Listeners loop ko BLOCK nahi karte (try/catch, koi network nahi).

    private val raisedListeners = java.util.concurrent.CopyOnWriteArrayList<(Request) -> Unit>()
    private val resolvedListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    /** Sawal uthne par bulao (FmApp se register). */
    fun onRaised(l: (Request) -> Unit) {
        raisedListeners.add(l)
    }

    /** Jawab milne / cancel hone par bulao (runId ke saath). */
    fun onResolved(l: (String) -> Unit) {
        resolvedListeners.add(l)
    }

    private fun fireRaised(req: Request) {
        for (l in raisedListeners) {
            try {
                l(req)
            } catch (_: Exception) { }
        }
    }

    private fun fireResolved(runId: String) {
        if (runId.isEmpty()) return
        for (l in resolvedListeners) {
            try {
                l(runId)
            } catch (_: Exception) { }
        }
    }

    /**
     * Background thread se call karo. User ka jawab (JSON string) ya null
     * (timeout / cancel / kisi aur run ka prompt aa gaya).
     */
    fun ask(req: Request): String? {
        synchronized(lock) {
            pending = req
            answerJson = null
            answeredRun = ""
            (lock as Object).notifyAll()
        }
        // K4: sawal utha — listeners (notification + pending-store). Loop
        // block nahi hota; listeners best-effort hain.
        try {
            fireRaised(req)
        } catch (_: Exception) { }
        val deadline = System.currentTimeMillis() + req.timeoutSec * 1000
        synchronized(lock) {
            while (true) {
                if (answeredRun == req.runId && answerJson != null) {
                    val a = answerJson
                    pending = null
                    answerJson = null
                    return a
                }
                // Kisi aur run ka naya prompt — ye wala cancel
                val cur = pending
                if (cur == null || cur.runId != req.runId) return null
                val waitMs = deadline - System.currentTimeMillis()
                if (waitMs <= 0) {
                    pending = null
                    return null
                }
                try {
                    (lock as Object).wait(waitMs.coerceAtMost(5000))
                } catch (_: InterruptedException) {
                    pending = null
                    return null
                }
            }
        }
    }

    /** UI thread se: user ne jawab diya. */
    fun answer(runId: String, answer: String) {
        synchronized(lock) {
            val cur = pending
            if (cur != null && cur.runId == runId) {
                answerJson = answer
                answeredRun = runId
                (lock as Object).notifyAll()
            }
        }
        // K4: jawab mil gaya — pending entry + "chahiye" notification hatao.
        try {
            fireResolved(runId)
        } catch (_: Exception) { }
    }

    /** UI thread se: dialog band / run cancel. */
    fun cancel(runId: String) {
        synchronized(lock) {
            if (pending?.runId == runId) {
                pending = null
                (lock as Object).notifyAll()
            }
        }
        // K4: cancel par bhi entry/notification saaf (loop wahi rukega).
        try {
            fireResolved(runId)
        } catch (_: Exception) { }
    }

    /**
     * L1-UPGRADE (OTP maximum assistance): SMS se aaya OTP pending
     * OTP prompt me apne aap bharo. true = koi OTP prompt pending tha
     * aur usme OTP daal diya (dialog apne aap aage badhega).
     *
     * Answer key "otp" hai → AgentLoop ishe hamesha sensitive maanta hai
     * (sirf page me locally bharta hai, server/AI ko kabhi nahi bhejta).
     */
    fun autoFillOtp(ctx: android.content.Context, otp: String): Boolean {
        val req = synchronized(lock) { pending } ?: return false
        if (req.kind != "otp") return false
        if (!otp.matches(Regex("\\d{4,8}"))) return false
        val ans = try {
            org.json.JSONObject()
                .put("approved", true)
                .put("otp", otp)
                .toString()
        } catch (_: Exception) { return false }
        answer(req.runId, ans)
        return true
    }

    /** UI polling: is waqt koi prompt khula hai? (koi bhi run) */
    fun pendingRequest(): Request? = pending

    /** Kya is run ka prompt khula hai? */
    fun isPending(runId: String): Boolean = pending?.runId == runId
}

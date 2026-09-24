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
        val kind: String,          // otp | input | choice | payment | document | login
        val title: String,
        val message: String,
        val fields: List<Field> = emptyList(),
        val options: List<String> = emptyList(),
        val payment: Payment? = null,
        val docType: String = "",  // kind='document' — kaun sa doc chahiye (hint)
        val timeoutSec: Long = 600
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
    }

    /** UI thread se: dialog band / run cancel. */
    fun cancel(runId: String) {
        synchronized(lock) {
            if (pending?.runId == runId) {
                pending = null
                (lock as Object).notifyAll()
            }
        }
    }

    /** UI polling: is waqt koi prompt khula hai? (koi bhi run) */
    fun pendingRequest(): Request? = pending

    /** Kya is run ka prompt khula hai? */
    fun isPending(runId: String): Boolean = pending?.runId == runId
}

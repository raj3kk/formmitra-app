package com.formmitra.app.engine

/**
 * GateLogic — POINT 15 (NON-BLOCKING INTERVENTION GATES) ka pure-Kotlin hissa.
 * ZERO Android imports — JVM self-test me seedha compile hota hai.
 *
 * Model:
 *  - Gate aaye (otp / login / payment / option_choice / document) to loop
 *    us step ko PARK karta hai (GateStore — persisted, crash-safe), user ko
 *    masked notification bhejta hai, aur run ko "gated" state me chhod kar
 *    worker thread free kar deta hai (dusre queued kaam chalte rehte hain).
 *  - Gated step WAIT karta hai (deadline + countdown). User jawab de
 *    (chat gate-card ya dialog) to run USI step se resume hota hai.
 *  - Timeout + no-response → step "pending" (EXPIRED), History ke Pending
 *    tab se resume (point 11 se juda).
 *  - Har gate decision ka audit: screenshot + timestamp (GateStore me).
 *
 * HARD SAFETY (non-waivable): operator KABHI khud payment initiate nahi
 * karta. Payment hamesha USER khud karta hai (apne UPI app me); operator
 * sirf site par VERIFY karta hai (page text me success keywords). Is file
 * me ya kahin bhi aisa koi code path nahi hai jo user-tap ke bina koi
 * UPI/payment intent launch kare — ekmatra payment-intent call-site
 * PromptDialog ke "UPI app kholo" button ka onClick hai (explicit user tap).
 */
object GateLogic {

    // ---------- kinds ----------

    const val OTP = "otp"
    const val LOGIN = "login"
    const val PAYMENT = "payment"
    /** Server "choice"/"option" bhi bhej sakta hai — sab option_choice. */
    const val OPTION = "option_choice"
    const val DOCUMENT = "document"
    const val DEVICE_AUTH = "device_auth"

    /** Server ke bheje kind ko normalize karo. */
    fun normKind(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "choice", "option", "options", "select_option" -> OPTION
        else -> raw?.trim()?.lowercase().orEmpty()
    }

    /** Kaun se gates non-blocking park hote hain (loop thread exit, resume-safe). */
    val PARKED = setOf(OTP, LOGIN, PAYMENT, OPTION, DOCUMENT)

    /**
     * device_auth (fingerprint/PIN) park NAHI hota — user ko saamne hona hi
     * chahiye, isliye wo foreground blocking rehta hai (chhota timeout).
     */
    fun isParked(kind: String?): Boolean = normKind(kind) in PARKED

    /**
     * Foreground wait (sec): user app me dekh raha ho to gate turant
     * resolve ho aur loop bina exit ke aage badhe. Iske baad bhi jawab na
     * aaye to loop exit karke gate ko parked chhod deta hai (worker free).
     */
    const val FOREGROUND_WAIT_SEC = 120L

    // ---------- decisions (audit me) ----------

    const val D_OTP_FILLED = "otp_filled"
    const val D_LOGIN_FILLED = "login_filled"
    const val D_PAID_CLAIMED = "paid_claimed"
    const val D_PAID_VERIFIED = "paid_verified"
    const val D_NOT_PAID = "not_paid"
    const val D_OPTION_CHOSEN = "option_chosen"
    const val D_DOC_CHOSEN = "doc_chosen"
    const val D_DECLINED = "declined"
    const val D_EXPIRED = "expired"

    /** Answer JSON (UserPrompt.answer ke liye) — values me secret ho sakta
     *  hai (otp/password): ye JSON sirf in-memory rehta hai, persist NAHI hota. */
    fun gateAnswerJson(kind: String, decision: String, values: Map<String, String>): String {
        val sb = StringBuilder("{")
        var first = true
        fun put(k: String, v: Any?) {
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(k).append("\":")
            when (v) {
                null -> sb.append("null")
                is Boolean -> sb.append(v)
                is Number -> sb.append(v)
                else -> sb.append("\"").append(
                    v.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                ).append("\"")
            }
        }
        put("approved", decision != D_DECLINED && decision != D_EXPIRED && decision != D_NOT_PAID)
        put("gate_decision", decision)
        when (normKind(kind)) {
            PAYMENT -> {
                put("payment_done", decision == D_PAID_CLAIMED || decision == D_PAID_VERIFIED)
                if (decision == D_NOT_PAID) put("reason", "user_not_paid")
                values["method"]?.let { put("method", it) }
            }
            OPTION -> values["choice"]?.let { put("choice", it) }
            DOCUMENT -> values["doc"]?.let { put("doc", it) }
            else -> for ((k, v) in values) put(k, v) // otp / login: field values
        }
        sb.append("}")
        return sb.toString()
    }

    /** Answer JSON se decision nikalo (loop apply karte waqt). */
    fun decisionOf(kind: String, ans: Map<String, Any?>): String {
        if (ans["approved"] != true) {
            val r = (ans["reason"] as? String).orEmpty()
            return if (r == "user_not_paid") D_NOT_PAID else D_DECLINED
        }
        if ((ans["gate_decision"] as? String)?.isNotEmpty() == true) {
            return ans["gate_decision"] as String
        }
        return when (normKind(kind)) {
            OTP -> D_OTP_FILLED
            LOGIN -> D_LOGIN_FILLED
            PAYMENT -> if (ans["payment_done"] == true) D_PAID_CLAIMED else D_NOT_PAID
            OPTION -> D_OPTION_CHOSEN
            DOCUMENT -> D_DOC_CHOSEN
            else -> D_OPTION_CHOSEN
        }
    }

    // ---------- masking (req 10: tray notification me sensitive NAHI) ----------
    //
    // Chat popup (app ke andar, user ka apna session) me poora text dikhta
    // hai — "OTP gaya hai 98XXXXXX12 par" jaisa server ne bheja. Tray me
    // sirf masked: "OTP gaya hai 98••••••12 par".

    /** 10-digit mobile (6-9 se shuru): 98••••••12 */
    fun maskPhone(text: String): String =
        text.replace(Regex("(?<!\\d)([6-9]\\d)\\d{6}(\\d{2})(?!\\d)"), "$1••••••$2")

    /** email: ra••@gmail.com (local part ke pehle 2 + domain) */
    fun maskEmail(text: String): String =
        text.replace(
            Regex("([A-Za-z0-9._%+-]{2})[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})"),
            "$1••@$2"
        )

    /**
     * OTP jaisa code — SIRF "otp/code" shabd ke aas-paas wale 4-8 digit.
     * Akele numbers (amount waghera) ko HAATH NAHI lagata — user ko exact
     * amount dikhna chahiye (req 4).
     */
    fun maskOtp(text: String): String =
        text.replace(
            Regex("(?i)\\b(otp|code|कोड)\\b([^\\d]{0,20}?)(\\d{4,8})"),
            "$1$2••••"
        )

    /**
     * Tray/notif text ke liye — phone + email + OTP mask.
     * ORDER (root fix): pehle PHONE, phir OTP, phir email — warna maskOtp
     * "OTP gaya hai 9876543210 par" wale 10-digit phone number ko OTP code
     * samajh ke kha jata hai (galat mask: "••••10" bajay "98••••••10").
     */
    fun maskSensitive(text: String): String =
        maskEmail(maskOtp(maskPhone(text)))

    // ---------- deadline / countdown ----------

    /** Gate ki deadline (ms epoch). timeoutSec<=0 → default 600. */
    fun deadlineMs(nowMs: Long, timeoutSec: Long): Long {
        val t = if (timeoutSec > 0) timeoutSec else 600L
        return nowMs + t * 1000
    }

    fun isExpired(nowMs: Long, deadlineMs: Long): Boolean = nowMs >= deadlineMs

    fun secLeft(nowMs: Long, deadlineMs: Long): Long =
        ((deadlineMs - nowMs) / 1000).coerceAtLeast(0)

    /** 600s → "10:00" (PaymentFlow.formatCountdown jaisa, yahan bhi pure). */
    fun formatCountdown(secLeft: Long): String {
        val s = secLeft.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ---------- user-facing text (simple Hinglish, jargon nahi) ----------

    fun gateTitle(kind: String): String = when (normKind(kind)) {
        OTP -> "🔐 OTP chahiye"
        LOGIN -> "🔑 Login chahiye"
        PAYMENT -> "💰 Payment"
        OPTION -> "🤔 Chunna hai"
        DOCUMENT -> "📄 Document chahiye"
        DEVICE_AUTH -> "👆 Aapki pehchaan chahiye"
        else -> "✋ Aapki madad chahiye"
    }

    /** Parked gate par loop-exit ke waqt run summary me jaane wala note. */
    fun parkedNote(kind: String): String = when (normKind(kind)) {
        OTP -> "[gate] OTP chahiye — app me OTP bhardo, kaam wahin se aage badhega"
        LOGIN -> "[gate] Login chahiye — app me login details do, kaam wahin se aage badhega"
        PAYMENT -> "[gate] Payment pending — app me payment karo, verify hote hi kaam aage badhega"
        OPTION -> "[gate] Aapka chunav chahiye — app me option chuno, kaam aage badhega"
        DOCUMENT -> "[gate] Document chahiye — app me vault se chuno, kaam aage badhega"
        else -> "[gate] Aapki madad chahiye — app kholo"
    }

    /** Voice announcement (FlowAnnouncer) — chhota, saaf. */
    fun gateVoice(kind: String): String = when (normKind(kind)) {
        OTP -> "OTP chahiye — app khol ke bhar do."
        LOGIN -> "Login chahiye — app khol ke details do."
        PAYMENT -> "Payment karna hai — app kholo."
        OPTION -> "Ek option chunna hai — app kholo."
        DOCUMENT -> "Document chahiye — app kholo."
        else -> "Aapki madad chahiye — app kholo."
    }

    // ---------- audit (req 9: har gate decision → screenshot + timestamp) ----------

    /** "14:32" jaisa time (device timezone). */
    fun hhmm(atMs: Long): String = try {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(atMs))
    } catch (_: Exception) {
        ""
    }

    /** Audit line — HistoryView "🛡 Gate decisions" me + chat bubble me. */
    fun auditText(kind: String, decision: String, atMs: Long, hasShot: Boolean): String {
        val what = when (normKind(kind)) {
            OTP -> "OTP"
            LOGIN -> "Login"
            PAYMENT -> "Payment"
            OPTION -> "Option"
            DOCUMENT -> "Document"
            else -> "Gate"
        }
        val did = when (decision) {
            D_OTP_FILLED -> "bhar diya"
            D_LOGIN_FILLED -> "bhar diya"
            D_PAID_CLAIMED -> "ho gaya (user ne kiya)"
            D_PAID_VERIFIED -> "verify ho gaya ✅"
            D_NOT_PAID -> "nahi kiya"
            D_OPTION_CHOSEN -> "chun liya"
            D_DOC_CHOSEN -> "de diya"
            D_DECLINED -> "mana kar diya"
            D_EXPIRED -> "time khatm ⏳"
            else -> decision
        }
        val shot = if (hasShot) " 📸" else ""
        val t = hhmm(atMs)
        return "• $what $did" + (if (t.isNotEmpty()) " • $t" else "") + shot
    }

    /** Gate card (chat) ke liye chhota hint — kind ke hisaab se. */
    fun cardHint(kind: String): String = when (normKind(kind)) {
        OTP -> "SMS/WhatsApp me aaya OTP yahan bharo — agent site par daal ke verify karega."
        LOGIN -> "Sirf jo manga hai wahi do — agent wahi bharega, kuch extra khud se nahi."
        PAYMENT -> "Agent kabhi khud payment nahi karta — aap khud apne UPI app se pay karo, phir \"Payment ho gaya\" dabao. Agent site par verify karega."
        OPTION -> "Jo sahi lage wo chuno — agent wahi option site par lagayega."
        DOCUMENT -> "Vault se document chuno ya naya upload karo."
        else -> ""
    }
}

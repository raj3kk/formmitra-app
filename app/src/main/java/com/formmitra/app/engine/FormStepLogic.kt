package com.formmitra.app.engine

/**
 * FormStepLogic — pure Kotlin, ZERO Android imports.
 *
 * Ye file JVM pe kotlinc se seedha compile hoti hai taaki self-test
 * (veto matcher, step parsing, retry/progress policy) real phone ke
 * bina verify ho sake. FormEngine isi ko use karta hai — logic yahan
 * single source of truth hai.
 */
object VetoCheck {
    /** workflows.ts PAYMENT_KEYWORDS ki exact copy (order bhi same) + Hindi shabd. */
    val KEYWORDS = listOf(
        "checkout",
        "payment",
        "razorpay",
        "stripe",
        "upi",
        "pay now",
        "paynow",
        "buy now",
        "buynow",
        "place order",
        "placeorder",
        "add to cart",
        "addtocart",
        "purchase",
        "billing",
        "card number",
        "cardnumber",
        "cvv",
        // Hindi payment shabd
        "भुगतान",
        "पेमेंट",
        "खरीदें",
        "ऑर्डर करें",
        "भुगतान करें",
        "कार्ट में डालें",
        "अभी खरीदें",
        "कीमत चुकाएं"
    )

    /**
     * text me pehla matching payment keyword, ya null.
     * workflows.ts browserPaymentVeto jaisa: lowercase match + compact
     * (space-hataya) form bhi check hota hai.
     */
    fun find(text: String?): String? {
        val blob = (text ?: "").lowercase()
        if (blob.isEmpty()) return null
        for (kw in KEYWORDS) {
            val compact = kw.replace("\\s+".toRegex(), "")
            if (blob.contains(kw) || (compact != kw && blob.contains(compact))) {
                return kw
            }
        }
        return null
    }
}

/** Ek step ka parsed form — Map-based taaki org.json ke bina test ho sake. */
data class StepSpec(
    val type: String,
    val url: String = "",
    val selectorMode: String = "",
    val selectorValue: String = "",
    val text: String = "",
    val option: String = "",
    val state: String = "",
    val key: String = "",
    val timeoutS: Long = 60,
    val seconds: Long = 2,
    val label: String = ""
)

object StepParser {
    /** Server contract ke saare supported step types. */
    val TYPES = setOf(
        "goto", "fill", "select", "toggle", "press", "click",
        "wait_for_element", "wait_for_text", "wait_for_navigation",
        "screenshot", "captcha_detect", "captcha_solve", "back", "forward",
        // v24 C15: explicit final-submit (click se pehle AI verification).
        "verify_submit",
        "upload", "scroll"
    )

    @Suppress("UNCHECKED_CAST")
    fun parse(raw: Map<String, Any?>): StepSpec {
        val type = (raw["type"] as? String)?.trim() ?: ""
        if (type !in TYPES) {
            throw IllegalArgumentException(
                "unknown step type: '$type' — supported: ${TYPES.sorted().joinToString(", ")}"
            )
        }
        val sel = raw["selector"] as? Map<String, Any?>
        return StepSpec(
            type = type,
            url = raw["url"] as? String ?: "",
            selectorMode = sel?.get("mode") as? String ?: "",
            selectorValue = sel?.get("value") as? String ?: "",
            text = raw["text"] as? String ?: "",
            option = raw["option"] as? String ?: "",
            state = (raw["state"] as? String ?: "flip").trim().lowercase()
                .let { if (it in setOf("on", "off", "flip")) it else "flip" },
            key = raw["key"] as? String ?: "Enter",
            timeoutS = (raw["timeout_s"] as? Number)?.toLong()?.coerceIn(5, 300) ?: 60,
            seconds = (raw["seconds"] as? Number)?.toLong()?.coerceIn(1, 120) ?: 2,
            label = raw["label"] as? String ?: ""
        )
    }

    /** Step ke saare free-text fields ek blob me — veto scan ke liye. */
    fun vetoBlob(s: StepSpec): String =
        listOf(s.url, s.text, s.option, s.key, s.label, s.selectorValue)
            .joinToString(" ")
}

/** Retry / progress-reporting policy — pure, testable. */
object RunPolicy {
    const val FILL_MAX_ATTEMPTS = 3

    /** Short tasks: har step pe report; long tasks: har 3 steps pe. */
    fun progressEvery(totalSteps: Int): Int = if (totalSteps <= 10) 1 else 3

    fun shouldReportProgress(stepIndex1Based: Int, totalSteps: Int): Boolean =
        stepIndex1Based % progressEvery(totalSteps) == 0

    /** attempt 1-based; true = ek aur try allowed. */
    fun fillMayRetry(attempt: Int): Boolean = attempt < FILL_MAX_ATTEMPTS

    /** read-back verify: actual == expected? */
    fun fillVerified(expected: String, actual: String): Boolean = expected == actual
}

package com.formmitra.app.engine

/**
 * PaymentFlow — pure Kotlin payment-helper logic (no Android).
 * Device kabhi khud payment nahi karta (hard veto); user apne UPI app me
 * pay karta hai. Ye sirf payload/validation/verification helpers hain.
 */
object PaymentFlow {

    /** UPI collect/QR payload: upi://pay?pa=...&pn=...&am=...&cu=INR */
    fun upiUri(upiId: String, name: String, amount: String, note: String = "FormMitra"): String {
        val am = amount.filter { it.isDigit() || it == '.' }
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return "upi://pay?pa=${enc(upiId.trim())}&pn=${enc(name.trim().ifEmpty { upiId })}" +
            (if (am.isNotEmpty()) "&am=${enc(am)}" else "") +
            "&cu=INR&tn=${enc(note.take(40))}"
    }

    /** UPI ID valid? (name@bank / number@upi pattern) */
    fun isValidUpiId(id: String): Boolean {
        val t = id.trim()
        if (t.length < 4 || t.length > 60) return false
        if (!t.contains("@")) return false
        return t.matches(Regex("[a-zA-Z0-9._\\-]{2,}@[a-zA-Z]{2,}"))
    }

    /** Amount valid? positive number, max 2 decimals. */
    fun isValidAmount(amount: String): Boolean {
        val am = amount.trim().replace(",", "")
        if (!am.matches(Regex("\\d+(\\.\\d{1,2})?"))) return false
        return (am.toDoubleOrNull() ?: 0.0) > 0.0
    }

    /**
     * Page text me se payable amount nikalo. "total", "payable", "amount",
     * "pay ₹X" patterns prefer karo; fallback: sabse badi ₹-amount.
     * @return "1234.50" jaisa plain number ya null.
     */
    fun parseAmount(pageText: String): String? {
        val t = pageText.replace(",", "")
        val labeled = Regex(
            "(?i)(total|payable|amount payable|grand total|net payable|pay)\\s*:?\\s*₹?\\s*(\\d+(?:\\.\\d{1,2})?)"
        ).findAll(t).map { it.groupValues[2] }.toList()
        if (labeled.isNotEmpty()) {
            return labeled.mapNotNull { it.toDoubleOrNull() }.maxOrNull()
                ?.let { "%.2f".format(it) }
        }
        val all = Regex("₹\\s*(\\d+(?:\\.\\d{1,2})?)").findAll(t)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        return all.maxOrNull()?.let { "%.2f".format(it) }
    }

    /** Payment-success page keywords (verification ke liye). */
    val SUCCESS_KEYWORDS = listOf(
        "payment successful", "payment success", "transaction successful",
        "paid successfully", "payment completed", "order placed",
        "booking confirmed", "successfully paid", "txn successful",
        "भुगतान सफल", "सफलतापूर्वक"
    )

    /** Page text me success keywords hain? */
    fun isSuccessText(pageText: String): Boolean {
        val low = pageText.lowercase()
        return SUCCESS_KEYWORDS.any { low.contains(it) }
    }

    /** Countdown text: 600s → "10:00". */
    fun formatCountdown(secLeft: Long): String {
        val s = secLeft.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Prompt answer JSON banao. */
    fun answerJson(map: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        map.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(",")
            sb.append("\"").append(k).append("\":")
            when (v) {
                null -> sb.append("null")
                is Boolean -> sb.append(v)
                is Number -> sb.append(v)
                else -> sb.append("\"").append(v.toString()
                    .replace("\\", "\\\\").replace("\"", "\\\"")).append("\"")
            }
        }
        return sb.append("}").toString()
    }
}

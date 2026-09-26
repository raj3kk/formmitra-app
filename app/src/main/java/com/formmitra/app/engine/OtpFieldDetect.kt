package com.formmitra.app.engine

/**
 * OtpFieldDetect — v34 (Phase 2A): OTP field / multi-box / verify-button /
 * result-classification ka pure-Kotlin hissa. ZERO Android imports —
 * JVM self-test me seedha chalta hai.
 *
 * Root fixes vs purana findOtpField:
 *  1. Sirf "otp" shabd nahi — "verification code", "one-time passcode",
 *     "enter code" jaise labels bhi score hote hain (weighted).
 *  2. Multi-box OTP (4-8 alag single-char inputs — bahut common UI):
 *     group detect karke har box me ek digit bhara jata hai.
 *  3. Bharne ke BAAD dabane wala verify/submit button dhoondhna
 *     (Verify/Submit/Continue/Confirm + Hindi).
 *  4. Submit ke baad result classify: SUCCESS / FAILURE / UNKNOWN
 *     (success + failure markers, EN + Hindi) — fail par 1 retry, phir
 *     user ko saaf message.
 */
object OtpFieldDetect {

    /** DOM snapshot ke field ka plain roop (adapter AgentLoop me). */
    data class Field(
        val tag: String,
        val type: String,
        val label: String,
        val placeholder: String,
        val aria: String,
        val name: String,
        val id: String,
        val maxLen: Int = -1
    )

    enum class Result { SUCCESS, FAILURE, UNKNOWN }

    private val EXCLUDED_TYPES = setOf(
        "hidden", "submit", "button", "checkbox", "radio", "file", "image"
    )

    /** label/placeholder/aria/name/id blob par weighted score. */
    fun scoreField(f: Field): Int {
        if (f.tag != "input" && f.tag != "textarea") return 0
        if (f.type.lowercase() in EXCLUDED_TYPES) return 0
        val blob = (f.label + " " + f.placeholder + " " + f.aria + " " +
            f.name + " " + f.id).lowercase()
        var s = 0
        if ("otp" in blob) s += 10
        if ("one-time" in blob || "one time" in blob) s += 8
        if ("verification code" in blob) s += 8
        else if ("verification" in blob) s += 6
        if ("passcode" in blob) s += 6
        // akela "code": postal/pin-code se takra sakta hai — halka score
        if (s == 0 && "code" in blob) s += 2
        if ("verify" in blob) s += 4
        if ("ओटीपी" in blob || "सत्यापन" in blob) s += 6
        if (f.type.lowercase() == "tel" || f.type.lowercase() == "number") s += 2
        return s
    }

    /** Sabse achha single OTP field (score>0), ya null. */
    fun bestField(fields: List<Field>): Field? =
        fields.map { it to scoreField(it) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

    /**
     * Multi-box OTP: lagatar 4-8 single-char inputs (maxlength=1) ka group.
     * DOM order me wapas — har box me ek digit jayega.
     */
    fun boxGroup(fields: List<Field>): List<Field>? {
        val singles = fields.filter { f ->
            (f.tag == "input") &&
                f.type.lowercase() !in EXCLUDED_TYPES &&
                f.maxLen == 1
        }
        if (singles.size < 4) return null
        // Lagatar index wala sabse bada group (4-8)
        var best: List<Field> = emptyList()
        var cur = ArrayList<Field>()
        var prevIdx = -2
        val idxOf = fields.withIndex().associate { (i, f) -> f to i }
        for (f in singles) {
            val i = idxOf[f] ?: continue
            if (i == prevIdx + 1) cur.add(f)
            else {
                if (cur.size in 4..8 && cur.size > best.size) best = cur.toList()
                cur = ArrayList<Field>().also { it.add(f) }
            }
            prevIdx = i
        }
        if (cur.size in 4..8 && cur.size > best.size) best = cur.toList()
        return best.ifEmpty { null }
    }

    private val BUTTON_WORDS = listOf(
        "verify", "submit", "continue", "confirm", "proceed",
        "sign in", "login", "log in", "सत्यापित", "जारी रखें",
        "पुष्टि", "आगे बढ़ें", "ओटीपी सत्यापित"
    )

    /** Verify/submit button ka text dhoondho (click ke liye). */
    fun submitButtonText(buttons: List<String>): String? {
        var best: String? = null
        var bestScore = 0
        for (b in buttons) {
            val t = b.trim().lowercase()
            if (t.isEmpty()) continue
            var s = 0
            for (w in BUTTON_WORDS) {
                if (t == w) { s += 10; break }
                if (t.startsWith(w)) { s += 6; break }
                if (w in t) { s += 3; break }
            }
            if (s > bestScore) { bestScore = s; best = b.trim() }
        }
        return best
    }

    private val SUCCESS_WORDS = listOf(
        "verified", "successfully", "success", "welcome", "dashboard",
        "otp verified", "phone verified", "email verified",
        "सफल", "सत्यापित हो गया", "हो गया"
    )
    private val FAILURE_WORDS = listOf(
        "invalid", "incorrect", "wrong otp", "wrong code", "expired",
        "try again", "does not match", "did not match", "failed",
        "गलत", "अमान्य", "पुनः प्रयास", "समाप्त"
    )

    /**
     * Submit ke baad page classify karo.
     * Failure markers pehle (safe) — mixed text par FAILURE.
     */
    fun classifyResult(
        beforeUrl: String,
        afterUrl: String,
        beforeText: String,
        afterText: String
    ): Result {
        val at = afterText.lowercase()
        if (FAILURE_WORDS.any { it in at }) return Result.FAILURE
        if (SUCCESS_WORDS.any { it in at }) return Result.SUCCESS
        // URL badla + OTP wali baat gayi + koi failure nahi = success
        if (afterUrl.isNotEmpty() && afterUrl != beforeUrl) {
            val bt = beforeText.lowercase()
            val otpGone = ("otp" in bt || "verification" in bt) &&
                ("otp" !in at && "verification" !in at)
            if (otpGone) return Result.SUCCESS
        }
        return Result.UNKNOWN
    }
}

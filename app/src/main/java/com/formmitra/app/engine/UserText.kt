package com.formmitra.app.engine

/**
 * UserText — technical engine/server errors ko user ke liye simple
 * Hinglish me badlo. Logs me technical detail rehti hai; screen aur
 * notification par SIRF friendly text jata hai.
 *
 * (L3a — koi "code 500", "exception", "engine crash" user ko nahi dikhega.)
 */
object UserText {

    /** Technical summary → user-friendly Hinglish. Never returns blank. */
    fun friendly(raw: String?): String {
        val s = (raw ?: "").trim()
        if (s.isEmpty()) return "Kaam me dikkat aayi — dobara try karo."
        val l = s.lowercase()
        return when {
            "payment veto" in l ->
                "Payment wala page mila — safety ke liye agent ne khud payment nahi kiya."
            "http 429" in l || "too many requests" in l || "rate limit" in l ->
                "Thoda slow ho gaya hai (limit) — kuch der me apne aap theek hoga."
            "engine crash" in l ->
                "Agent me andaruni dikkat aayi — dobara try karo."
            "browser start" in l && "nahi hua" in l ->
                "Browser khul nahi paya — dobara try karo."
            "step error" in l ->
                "Ek step me dikkat aayi — agent ne wahi se dobara koshish ki."
            "captcha" in l && ("fail" in l || "3" in l) ->
                "Captcha 3 baar try karke bhi clear nahi hua — aapko dekhna hoga."
            "timeout" in l ->
                "Waqt khatam ho gaya jawab ka intezar karte karte."
            "network" in l || "connection" in l || "socket" in l ->
                "Internet me dikkat hai — connection theek hote hi try karo."
            "code 4" in l || "code 5" in l || "http " in l ->
                "Server se baat nahi ho payi — thodi der me dobara try karo."
            "exception" in l || "nullpointer" in l ->
                "Andaruni dikkat aayi — dobara try karo."
            s.length > 160 -> s.take(157) + "…"
            else -> s
        }
    }
}

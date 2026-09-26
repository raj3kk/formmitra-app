import com.formmitra.app.engine.CardUnlockPolicy

// POINT 24 (revised): persistent card unlock — pure policy tests.
var pass = 0
var fail = 0
fun check(name: String, cond: Boolean) {
    if (cond) { pass++; println("PASS: $name") }
    else { fail++; println("FAIL: $name") }
}

fun main() {
    val now = 1_700_000_000_000L

    // ---------- attempt guard ----------
    // Pehli galat koshish → count 1
    var s = CardUnlockPolicy.recordAttempt(null, now, false)
    check("attempt_first", s == CardUnlockPolicy.AttemptState(1, now))
    // Sahi PIN → reset
    check("attempt_ok_resets", CardUnlockPolicy.recordAttempt(s, now + 1000, true) == null)
    // 5 galat → block
    var st: CardUnlockPolicy.AttemptState? = null
    for (i in 1..5) st = CardUnlockPolicy.recordAttempt(st, now + i * 1000, false)
    check("attempt_5_count", st?.count == 5)
    val blocked = CardUnlockPolicy.blockedUntilMs(st, now + 6000)
    check("blocked_after_5", blocked == now + 1000 + CardUnlockPolicy.ATTEMPT_WINDOW_MS)
    // 4 galat → block nahi
    var st4: CardUnlockPolicy.AttemptState? = null
    for (i in 1..4) st4 = CardUnlockPolicy.recordAttempt(st4, now + i, false)
    check("no_block_after_4", CardUnlockPolicy.blockedUntilMs(st4, now + 100) == 0L)
    // Window purani → block nahi, nayi window
    val old = CardUnlockPolicy.AttemptState(5, now - CardUnlockPolicy.ATTEMPT_WINDOW_MS - 1)
    check("window_expired_no_block", CardUnlockPolicy.blockedUntilMs(old, now) == 0L)
    val renewed = CardUnlockPolicy.recordAttempt(old, now, false)
    check("window_expired_restarts", renewed == CardUnlockPolicy.AttemptState(1, now))
    // Block khatm hone ke baad → 0
    check(
        "block_lifts",
        CardUnlockPolicy.blockedUntilMs(st, now + 1000 + CardUnlockPolicy.ATTEMPT_WINDOW_MS + 1) == 0L
    )
    // Parse/format round-trip
    val raw = CardUnlockPolicy.formatAttemptState(CardUnlockPolicy.AttemptState(3, 123L))
    check("attempt_serde", CardUnlockPolicy.parseAttemptState(raw) == CardUnlockPolicy.AttemptState(3, 123L))
    check("attempt_parse_null", CardUnlockPolicy.parseAttemptState(null) == null)
    check("attempt_parse_bad", CardUnlockPolicy.parseAttemptState("xx") == null)

    // ---------- token freshness ----------
    check("token_fresh", CardUnlockPolicy.isTokenFresh(now + 120_000L, now))
    check("token_skew", !CardUnlockPolicy.isTokenFresh(now + 59_000L, now))
    check("token_expired", !CardUnlockPolicy.isTokenFresh(now - 1000L, now))
    check("token_zero", !CardUnlockPolicy.isTokenFresh(0L, now))

    // ---------- user-facing text (Hinglish, saaf) ----------
    check("note_once", "background" in CardUnlockPolicy.unlockNoteText())
    check("blocked_text", "15 minute" in CardUnlockPolicy.pinBlockedText())
    check("lock_text", "PIN" in CardUnlockPolicy.lockDoneText("Mera Card"))
    check("entry_text", "PIN" in CardUnlockPolicy.entryPinText("Mera Card"))

    println("UNLOCK_DONE pass=$pass fail=$fail")
}

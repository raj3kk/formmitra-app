package com.formmitra.app.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * VoiceOutput — agent ki awaaz (Phase 5 voice ka bolne wala hissa; sunne wala
 * SpeechRecognizer pehle se AgentChatView me hai).
 * Hindi (hi-IN) prefer, na mile to default locale. Toggle prefs me persist.
 */
object VoiceOutput {
    private const val PREFS = "formmitra_prefs"
    private const val KEY = "voice_speak_enabled"

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var appCtx: Context? = null
    // Pehla speak() init se pehle aaye to utterance lost na ho — init
    // complete hote hi bol do.
    @Volatile private var pendingUtterance: String? = null

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
        if (!on) stop()
    }

    /** Lazy init — pehle speak() pe ya Agent tab khulne pe call karo. */
    fun init(ctx: Context) {
        if (tts != null) return
        appCtx = ctx.applicationContext
        try {
            tts = TextToSpeech(appCtx) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    val t = tts ?: return@TextToSpeech
                    val hi = Locale("hi", "IN")
                    val r = t.setLanguage(hi)
                    if (r == TextToSpeech.LANG_MISSING_DATA ||
                        r == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        t.language = Locale.getDefault()
                    }
                    t.setSpeechRate(0.95f)
                    // Pehli utterance jo init ka wait kar rahi thi
                    val p = pendingUtterance
                    pendingUtterance = null
                    if (!p.isNullOrEmpty()) {
                        try {
                            t.speak(p.take(400), TextToSpeech.QUEUE_FLUSH, null, "fm_reply")
                        } catch (_: Exception) { }
                    }
                }
            }
        } catch (_: Exception) { }
    }

    /** Agent ka jawab bolo (chhota rakho — 400 chars cap). */
    fun speak(ctx: Context, text: String) {
        if (!isEnabled(ctx)) return
        val t = tts
        if (t == null) {
            // Engine abhi init ho raha hai — utterance queue karo, ready
            // hote hi bolega (pehla jawab lost nahi hoga).
            pendingUtterance = text.take(400)
            init(ctx)
            return
        }
        if (!ready) {
            pendingUtterance = text.take(400)
            return
        }
        try {
            val short = text.take(400)
            t.speak(short, TextToSpeech.QUEUE_FLUSH, null, "fm_reply")
        } catch (_: Exception) { }
    }

    fun stop() {
        try { tts?.stop() } catch (_: Exception) { }
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Exception) { }
        tts = null
        ready = false
    }
}

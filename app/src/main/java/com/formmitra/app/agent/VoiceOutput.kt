package com.formmitra.app.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.ArrayDeque
import java.util.Locale

/**
 * VoiceOutput — agent ki awaaz (TTS).
 *
 * A3 ROOT CAUSE (pehla utterance der se bolta tha):
 *  (1) init LAZY tha — pehla speak() tab hota tha jab Home tab khulta tha
 *      ya pehla reply aata tha; TextToSpeech ka constructor system TTS
 *      service se bind hota hai jisme 1–3 second lagte hain — is beech ka
 *      pehla jawab atak jata tha.
 *  (2) sirf EK pendingUtterance tha — init ke dauraan aane wale beech ke
 *      utterances overwrite hokar KHO jate the.
 *
 * FIX:
 *  - FmApp.onCreate se warmup() — app khulte hi background me engine
 *    ready (pehle reply tak aksar ready hota hai).
 *  - FIFO queue (cap 5) — init se pehle aaye saare utterances surakshit,
 *    ready hote hi क्रम se bolte hain (pehla FLUSH, baaki ADD).
 *  - speak() ready state me QUEUE_FLUSH — naya jawab purane ko kaat-ta hai
 *    (sahi behavior: latest reply sunai de).
 *
 * Talking Voice (C16): English/Hindi × Male/Female — prefs me, engine
 * ready par tts.voices me se best match chunta hai (locale + naam me
 * gender hint); na mile to setLanguage fallback.
 *
 * Har agent voice par repeat + mute: AgentChatView har assistant bubble
 * ke neeche 🔁 (dobara suno) + 🔇/🔊 buttons deta hai — ye speak/setEnabled
 * unhi se chalta hai.
 */
object VoiceOutput {
    private const val PREFS = "formmitra_prefs"
    private const val KEY = "voice_speak_enabled"
    private const val KEY_LANG = "voice_lang" // "hi" | "en"
    private const val KEY_GENDER = "voice_gender" // "male" | "female"

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var initializing = false
    private var appCtx: Context? = null
    private val queue = ArrayDeque<String>()
    private val qLock = Any()
    private const val QUEUE_CAP = 5

    // ---------- prefs ----------

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
        if (!on) stop()
    }

    /** "hi" | "en" */
    fun voiceLang(ctx: Context): String = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "hi") ?: "hi"
    } catch (_: Exception) { "hi" }

    /** "male" | "female" */
    fun voiceGender(ctx: Context): String = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GENDER, "female") ?: "female"
    } catch (_: Exception) { "female" }

    fun setVoicePref(ctx: Context, lang: String, gender: String) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LANG, lang)
                .putString(KEY_GENDER, gender)
                .apply()
        } catch (_: Exception) { }
        // Engine ready ho to turant nayi awaaz lagao.
        try {
            val t = tts
            if (t != null && ready) applyVoice(t)
        } catch (_: Exception) { }
    }

    fun voiceLabel(ctx: Context): String {
        val lang = if (voiceLang(ctx) == "en") "English (अंग्रेज़ी)" else "Hindi (हिंदी)"
        val gen = if (voiceGender(ctx) == "male") "Male (पुरुष)" else "Female (महिला)"
        return "$lang × $gen"
    }

    // ---------- init ----------

    /**
     * Early warm-init — FmApp.onCreate se bulao (background thread par).
     * Pehle reply aane tak engine aksar ready hota hai.
     */
    fun warmup(ctx: Context) {
        Thread({
            try { init(ctx.applicationContext) } catch (_: Exception) { }
        }, "fm-tts-warmup").start()
    }

    /** Lazy init — pehle speak() pe ya Agent tab khulne pe call karo. */
    fun init(ctx: Context) {
        if (tts != null || initializing) return
        synchronized(this) {
            if (tts != null || initializing) return
            initializing = true
        }
        appCtx = ctx.applicationContext
        try {
            tts = TextToSpeech(appCtx) { status ->
                ready = status == TextToSpeech.SUCCESS
                initializing = false
                if (ready) {
                    val t = tts ?: return@TextToSpeech
                    try { applyVoice(t) } catch (_: Exception) { }
                    t.setSpeechRate(0.95f)
                    // Queue drain: pehla FLUSH, baaki ADD (kram se).
                    val items: List<String> = synchronized(qLock) {
                        val l = queue.toList()
                        queue.clear()
                        l
                    }
                    items.forEachIndexed { idx, text ->
                        try {
                            val mode = if (idx == 0) TextToSpeech.QUEUE_FLUSH
                            else TextToSpeech.QUEUE_ADD
                            t.speak(text.take(400), mode, null, "fm_reply_$idx")
                        } catch (_: Exception) { }
                    }
                }
            }
        } catch (_: Exception) {
            initializing = false
        }
    }

    /**
     * Talking Voice apply: prefs (lang × gender) ke hisaab se tts.voices
     * me best match. Gender ka pata voice ke naam se (e.g. "female"/"male"
     * hint); quality/network voices ko prefer nahi — jo mile wahi.
     */
    private fun applyVoice(t: TextToSpeech) {
        val ctx = appCtx ?: return
        val lang = voiceLang(ctx)
        val wantFemale = voiceGender(ctx) != "male"
        val locale = if (lang == "en") Locale("en", "IN") else Locale("hi", "IN")
        var picked: Voice? = null
        try {
            val voices = t.voices?.toList() ?: emptyList()
            // 1. same language + gender hint
            picked = voices.firstOrNull { v ->
                v.locale.language == locale.language &&
                    genderMatches(v.name, wantFemale)
            }
            // 2. same language (koi bhi gender)
            if (picked == null) {
                picked = voices.firstOrNull { v ->
                    v.locale.language == locale.language
                }
            }
            // 3. default locale ki awaaz
            if (picked == null) {
                picked = voices.firstOrNull { v ->
                    v.locale.language == Locale.getDefault().language
                }
            }
        } catch (_: Exception) { }
        try {
            if (picked != null) {
                t.voice = picked
            } else {
                val r = t.setLanguage(locale)
                if (r == TextToSpeech.LANG_MISSING_DATA ||
                    r == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    t.language = Locale.getDefault()
                }
            }
        } catch (_: Exception) {
            try { t.language = locale } catch (_: Exception) { }
        }
    }

    private fun genderMatches(voiceName: String, wantFemale: Boolean): Boolean {
        val n = voiceName.lowercase()
        return if (wantFemale) {
            n.contains("female") || n.contains("fem") ||
                n.contains("f1") || n.contains("woman")
        } else {
            (n.contains("male") && !n.contains("female")) ||
                n.contains("m1") || n.contains("man") || n.contains("masc")
        }
    }

    // ---------- speak ----------

    /** Agent ka jawab bolo (chhota rakho — 400 chars cap). */
    fun speak(ctx: Context, text: String) {
        if (!isEnabled(ctx)) return
        val short = text.take(400)
        if (short.isEmpty()) return
        val t = tts
        if (t == null || !ready) {
            // Engine abhi init ho raha hai — FIFO queue me rakho (khoye nahi).
            synchronized(qLock) {
                if (queue.size >= QUEUE_CAP) queue.removeFirst()
                queue.addLast(short)
            }
            init(ctx)
            return
        }
        try {
            t.speak(short, TextToSpeech.QUEUE_FLUSH, null, "fm_reply")
        } catch (_: Exception) { }
    }

    /** 🔁 Repeat — aakhri/yeh text dobara sunao (bubble ka repeat button). */
    fun repeat(ctx: Context, text: String) = speak(ctx, text)

    fun stop() {
        try { tts?.stop() } catch (_: Exception) { }
        synchronized(qLock) { queue.clear() }
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Exception) { }
        tts = null
        ready = false
        initializing = false
    }
}

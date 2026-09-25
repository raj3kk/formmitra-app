package com.formmitra.app.agent

import android.content.Context

/**
 * FlowAnnouncer — L4: har flow milestone par CENTRAL announcement:
 * chhota TTS (agar voice ON ho) + NotifCenter text (hamesha dikhta hai,
 * mute par bhi). Secrets kabhi announce nahi hote.
 */
object FlowAnnouncer {

    /**
     * Bolo + dikhao. [alsoNotify]=true to NotifCenter me bhi entry.
     */
    fun say(
        ctx: Context,
        text: String,
        alsoNotify: Boolean = false,
        cat: NotifCenter.Cat = NotifCenter.Cat.STATUS,
        title: String = "FormMitra",
        deepTab: String = "/agent"
    ) {
        val short = text.take(200)
        try {
            VoiceOutput.speak(ctx.applicationContext, short)
        } catch (_: Exception) { }
        if (alsoNotify) {
            try {
                NotifCenter.notify(
                    ctx.applicationContext, cat, title, short, deepTab = deepTab
                )
            } catch (_: Exception) { }
        }
    }
}

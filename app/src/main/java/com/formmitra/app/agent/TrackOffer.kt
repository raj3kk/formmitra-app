package com.formmitra.app.agent

import android.content.Context
import com.formmitra.app.engine.TrackOfferPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * TrackOffer — POINT 25 (TRACKING → ACTION OFFER), app-side receiver.
 *
 * Server (tracking/action-offers) FCM data {kind:"action_offer",...} aur
 * realtime event "action_offer" bhejta hai. Yahan:
 *  - offer_id se dedupe (dobara same offer card nahi),
 *  - unseen offers persist (app restart/chat band par bhi),
 *  - notification (tap → /agent tab),
 *  - AgentChatView me card (onRaised listeners).
 *
 * Koi auto-run NAHI — user "Haan, shuru karo" dabaye tabhi
 * AgentApi.startActionRun → POST /api/agent/runs {goal, url}.
 */
object TrackOffer {

    data class Offer(
        val offerId: String,
        val kind: String,
        val title: String,
        val detail: String,
        val question: String,
        val goal: String,
        val url: String
    )

    private const val PREFS = "formmitra_track_offers"
    private const val KEY_SEEN = "seen_ids"
    private const val KEY_UNSEEN = "unseen"

    private val listeners = mutableListOf<(Offer) -> Unit>()

    fun addListener(l: (Offer) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Offer) -> Unit) { listeners.remove(l) }

    /** FCM data map ya realtime payload se Offer banao (null = invalid). */
    fun fromMap(m: Map<String, Any?>): Offer? = try {
        val offerId = (m["offer_id"] as? String).orEmpty()
        val kind = (m["offer_kind"] as? String
            ?: m["kind"] as? String).orEmpty()
        val title = (m["title"] as? String).orEmpty()
            .ifEmpty { TrackOfferPolicy.defaultTitle(kind) }
        val detail = (m["detail"] as? String).orEmpty()
        val question = (m["question"] as? String).orEmpty()
            .ifEmpty { TrackOfferPolicy.defaultQuestion(kind) }
        val goal = ((m["goal"] as? String)
            ?: (m["suggested_goal"] as? String)).orEmpty()
        val url = ((m["url"] as? String)
            ?: (m["suggested_url"] as? String)).orEmpty()
        if (!TrackOfferPolicy.isValid(offerId, question, goal)) null
        else Offer(offerId, kind, title, detail, question, goal, url)
    } catch (_: Exception) { null }

    /**
     * Offer receive karo: dedupe + persist + notify + chat listeners.
     * @return true agar naya offer tha (dikhaya gaya).
     */
    fun receive(ctx: Context, offer: Offer): Boolean {
        try {
            if (isSeen(ctx, offer.offerId)) return false
            markSeen(ctx, offer.offerId)
            addUnseen(ctx, offer)
            // Notification (tap → /agent tab).
            NotifCenter.notify(
                ctx, NotifCenter.Cat.TASK,
                "FormMitra — ${offer.title}",
                TrackOfferPolicy.notifBody(offer.detail, offer.question),
                deepTab = "/agent",
                key = "offer-${offer.offerId}"
            )
            listeners.toList().forEach { l ->
                try { l(offer) } catch (_: Exception) { }
            }
            return true
        } catch (_: Exception) { return false }
    }

    // ---------- persistence ----------

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun seenSet(ctx: Context): MutableSet<String> = try {
        prefs(ctx).getStringSet(KEY_SEEN, emptySet())!!.toMutableSet()
    } catch (_: Exception) { mutableSetOf() }

    private fun isSeen(ctx: Context, offerId: String): Boolean =
        seenSet(ctx).contains(offerId)

    private fun markSeen(ctx: Context, offerId: String) {
        try {
            val s = seenSet(ctx)
            s.add(offerId)
            // Cap: 200 se zyada purane ids mat rakho.
            val trimmed = s.toList().takeLast(200).toSet()
            prefs(ctx).edit().putStringSet(KEY_SEEN, trimmed).apply()
        } catch (_: Exception) { }
    }

    private fun addUnseen(ctx: Context, offer: Offer) {
        try {
            val arr = try {
                JSONArray(prefs(ctx).getString(KEY_UNSEEN, null) ?: "[]")
            } catch (_: Exception) { JSONArray() }
            arr.put(
                JSONObject()
                    .put("offer_id", offer.offerId)
                    .put("kind", offer.kind)
                    .put("title", offer.title)
                    .put("detail", offer.detail)
                    .put("question", offer.question)
                    .put("goal", offer.goal)
                    .put("url", offer.url)
            )
            prefs(ctx).edit().putString(KEY_UNSEEN, arr.toString()).apply()
        } catch (_: Exception) { }
    }

    /** Abhi tak chat me na dikhe offers (AgentChatView entry par). */
    fun takeUnseen(ctx: Context): List<Offer> {
        val out = mutableListOf<Offer>()
        try {
            val p = prefs(ctx)
            val arr = JSONArray(p.getString(KEY_UNSEEN, null) ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val offer = fromMap(
                    mapOf(
                        "offer_id" to o.optString("offer_id"),
                        "kind" to o.optString("kind"),
                        "title" to o.optString("title"),
                        "detail" to o.optString("detail"),
                        "question" to o.optString("question"),
                        "suggested_goal" to o.optString("goal"),
                        "suggested_url" to o.optString("url")
                    )
                )
                offer?.let { out.add(it) }
            }
            // Le liye → unseen saaf (card dikh chuka).
            p.edit().remove(KEY_UNSEEN).apply()
        } catch (_: Exception) { }
        return out
    }

    /** Offer dismiss (Rehne do) — unseen se hatao (seen rehta hai). */
    fun dismissUnseen(ctx: Context, offerId: String) {
        try {
            val p = prefs(ctx)
            val arr = JSONArray(p.getString(KEY_UNSEEN, null) ?: "[]")
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("offer_id") != offerId) kept.put(o)
            }
            p.edit().putString(KEY_UNSEEN, kept.toString()).apply()
        } catch (_: Exception) { }
    }
}

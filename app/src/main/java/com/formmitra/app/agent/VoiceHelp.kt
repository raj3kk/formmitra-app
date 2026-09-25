package com.formmitra.app.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.formmitra.app.engine.UserPrompt
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * VoiceHelp — "voice help mode": agent jahan ATKE (stuck report / blocker /
 * needs_user), wahan TTS se BOLKAR sunao: kya karna tha, kahan atka, kaunsi
 * details hain — aur /api/agent/act stuck-recovery se AI ka next-step lao,
 * TTS se user ko sunao + screen par saaf status dikhao.
 *
 * - Sirf stuck par trigger hota hai; normal flow me KABHI nahi.
 * - TTS user-mutable hai: chat ka speaker toggle (🔊/🔇) band ho to sirf
 *   screen text dikhta hai, awaaz nahi (VoiceOutput.speak khud no-op hai).
 * - Har (runId, event) ek session me ek hi baar announce hota hai (dedupe).
 */
object VoiceHelp {
    private val announced = Collections.synchronizedSet(mutableSetOf<String>())
    private val ui = Handler(Looper.getMainLooper())

    /**
     * Stuck / blocker / needs_user event.
     * [onStatus] hamesha UI thread par call hota hai (banner/status text).
     */
    fun announceStuck(
        ctx: Context,
        runId: String,
        status: String,
        goal: String,
        reason: String,
        onStatus: (String) -> Unit
    ) {
        val key = "stuck:$runId:$status"
        if (!announced.add(key)) return
        val appCtx = ctx.applicationContext
        val g = goal.ifEmpty { "ye kaam" }.take(140)
        val r = reason.ifEmpty { "wajah saaf nahi hai" }.take(200)
        val first = "Agent atak gaya hai. Kaam tha: $g. Wajah: $r."
        // Turant sunao (fast feedback), phir AI se next-step lao.
        VoiceOutput.speak(appCtx, first)
        ui.post { onStatus("🎙️ $first\nAI se aage ka rasta poochha ja raha hai…") }
        Thread {
            val guidance = fetchGuidance(appCtx, runId, g, r)
            val msg = if (guidance.isNotEmpty()) "Aage ye karo: $guidance"
            else "Screen par sandesh padho aur Retry dabao — agent wahi se aage badhega."
            VoiceOutput.speak(appCtx, msg)
            ui.post { onStatus("🎙️ $first\n➡️ $msg") }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Blocking user prompt khula (OTP / input / choice / payment / document /
     * login) — loop ruka hai, user ka action chahiye. Ek baar sunao.
     */
    fun announcePrompt(ctx: Context, req: UserPrompt.Request) {
        val key = "prompt:${req.runId}:${req.kind}"
        if (!announced.add(key)) return
        val what = when (req.kind) {
            "otp" -> "OTP chahiye"
            "payment" -> "payment ki anumati chahiye"
            "login" -> "site login chahiye"
            "document" -> "document chahiye"
            "choice" -> "aapka chunav chahiye"
            else -> "aapki madad chahiye"
        }
        VoiceOutput.speak(
            ctx.applicationContext,
            "Agent ko $what. ${req.title}. ${req.message.take(220)}"
        )
    }

    /**
     * /api/agent/act stuck-recovery: stuck context bhejo, AI ka next-step lao.
     * Sirf stuck events par call hota hai (quota bachao).
     */
    private fun fetchGuidance(ctx: Context, runId: String, goal: String, reason: String): String {
        return try {
            val hist = JSONArray().put(
                JSONObject()
                    .put("action", "stuck_report")
                    .put("result", "stuck")
                    .put("note", reason)
            )
            val body = JSONObject()
                .put("goal", goal)
                .put("url", "")
                .put("stuck_count", 4)
                .put("run_id", runId)
                .put("history", hist)
                .put(
                    "page_analysis",
                    JSONObject().put("stuck_report", "stuck_count=4; $reason")
                )
                .put("user_provided", JSONObject())
            val res = AgentApi.act(ctx, body)
            if (res.code != 200 || res.json == null) return ""
            // F2: server stuck par step me `voice_summary` bhejta hai (user ko
            // sunane layak) — use sabse pehle prefer karo.
            val rootVoice = res.json.optString("voice_summary", "").trim()
            if (rootVoice.isNotEmpty()) return rootVoice.take(300)
            val step = res.json.optJSONObject("step") ?: return ""
            val stepVoice = step.optString("voice_summary", "").trim()
            if (stepVoice.isNotEmpty()) return stepVoice.take(300)
            val up = step.opt("user_prompt")
            val upText = when (up) {
                is String -> up
                is JSONObject -> up.optString("message").ifEmpty { up.optString("text") }
                else -> ""
            }
            upText.ifEmpty {
                step.optString("blocked_reason").ifEmpty {
                    step.optString("reason").ifEmpty { step.optString("result_summary") }
                }
            }.trim().take(300)
        } catch (_: Exception) {
            ""
        }
    }
}

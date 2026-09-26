package com.formmitra.app.agent

import android.content.Context
import android.os.Build
import android.util.Log
import com.formmitra.app.WakeWorker
import com.formmitra.app.engine.TrackOfferPolicy
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FmMessagingService — J1: FCM push receive (+ K1: notification deep links).
 *
 * Server (sirf nazar rakhne wala) ye data-push bhej sakta hai:
 *   { "type": "wake" | "task" | "refresh" | "status" | "detail" |
 *             "approval" | "task_done" | "task_fail" | "doc_ready",
 *     "title": "...", "body": "...",
 *     "task_id": "...", "run_id": "...", "deep_tab": "/history" }
 *
 * - wake/task/refresh: Working Mode ON → WakeWorker.enqueue (resume);
 *   OFF → sirf status notification (automation start NAHI).
 * - detail: agent ko user se detail chahiye → DETAIL channel (HIGH),
 *   tap → History + prompt khule.
 * - approval: approval chahiye (payment) → APPROVAL channel (HIGH).
 * - task_done / task_fail: TASK channel, tap → History + run detail.
 * - doc_ready: DOC channel, tap → History + run detail.
 * - status: STATUS channel (limit/status change).
 *
 * Sab notifications NotifCenter se — channels + Profile on/off +
 * inbox + badge + deep link ek jagah. Category OFF ho to push bhi
 * nahi dikhega (user ki setting).
 *
 * Manifest me MESSAGING_EVENT intent-filter ke saath declared hai.
 * Push na aaye / FCM configured na ho → local fallback (FormTaskWorker
 * 30-min, NetWake, app-open WakeWorker, UserPrompt listeners) — feature
 * kabhi dead nahi.
 */
class FmMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed — server ko register kar rahe")
        FcmPush.registerToken(applicationContext, token)
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        super.onMessageReceived(msg)
        try {
            val data = msg.data
            // POINT 25: server action-offer push me "kind":"action_offer"
            // bhejta hai (type nahi) — dono dekho.
            val type = (data["type"] ?: data["kind"] ?: "").lowercase()
            val title = data["title"] ?: msg.notification?.title ?: "FormMitra"
            val body = data["body"] ?: msg.notification?.body ?: ""
            val runId = data["run_id"] ?: data["task_id"] ?: ""
            val deepTab = (data["deep_tab"] ?: "/history").ifEmpty { "/history" }
            Log.i(TAG, "push aaya: type=$type run=$runId")
            when (type) {
                "wake", "task", "refresh" -> {
                    if (WorkingMode.isEnabled(applicationContext)) {
                        Log.i(TAG, "Working Mode ON — wake + resume")
                        WakeWorker.enqueue(applicationContext)
                    } else {
                        Log.i(TAG, "Working Mode OFF — sirf status notification")
                        NotifCenter.notify(
                            applicationContext, NotifCenter.Cat.STATUS,
                            title.ifEmpty { "FormMitra" },
                            body.ifEmpty {
                                "Naya update aaya — Working Mode OFF hai, " +
                                    "automation start nahi hua."
                            },
                            deepTab = "/profile"
                        )
                    }
                }
                // K1: agent ko user se detail chahiye (server-side trigger).
                // Tap → History khulti hai + prompt entry (Pending tab me
                // dikhegi jab tak detail na mile — K4 loop).
                "detail" -> {
                    // L5: remote prompt persist — deep link khulne par prompt
                    // dialog/resume ke paas data ho (khaali History nahi).
                    if (runId.isNotEmpty()) {
                        try {
                            PendingPromptStore.raiseRemote(
                                applicationContext, runId, "detail",
                                title.ifEmpty { "Ek detail chahiye ✋" },
                                body.ifEmpty { "Tap karke detail do — agent aage badhega." }
                            )
                        } catch (_: Exception) { }
                    }
                    NotifCenter.notify(
                        applicationContext, NotifCenter.Cat.DETAIL,
                        title.ifEmpty { "Ek detail chahiye ✋" },
                        body.ifEmpty { "Tap karke detail do — agent aage badhega." },
                        deepTab = deepTab,
                        deepRunId = runId,
                        openPromptRunId = runId,
                        key = runId.ifEmpty { "detail-$title" }
                    )
                }
                // K1: approval chahiye (payment / sensitive action).
                "approval" -> {
                    // L5: remote prompt persist (detail jaisa).
                    if (runId.isNotEmpty()) {
                        try {
                            PendingPromptStore.raiseRemote(
                                applicationContext, runId, "approval",
                                title.ifEmpty { "Approval chahiye 💰" },
                                body.ifEmpty { "Tap karke approve karo." }
                            )
                        } catch (_: Exception) { }
                    }
                    NotifCenter.notify(
                        applicationContext, NotifCenter.Cat.APPROVAL,
                        title.ifEmpty { "Approval chahiye 💰" },
                        body.ifEmpty { "Tap karke approve karo." },
                        deepTab = deepTab,
                        deepRunId = runId,
                        openPromptRunId = runId,
                        key = runId.ifEmpty { "approval-$title" }
                    )
                }
                "task_done" -> NotifCenter.notify(
                    applicationContext, NotifCenter.Cat.TASK,
                    title.ifEmpty { "Ho gaya ✅" },
                    body.ifEmpty { "Kaam poora ho gaya." },
                    deepTab = deepTab,
                    deepRunId = runId,
                    key = runId.ifEmpty { "done-$title" }
                )
                "task_fail" -> NotifCenter.notify(
                    applicationContext, NotifCenter.Cat.TASK,
                    title.ifEmpty { "Dhyaan chahiye ⚠️" },
                    body.ifEmpty { "Kaam me dikkat aayi — detail dekho." },
                    deepTab = deepTab,
                    deepRunId = runId,
                    key = runId.ifEmpty { "fail-$title" }
                )
                "doc_ready" -> NotifCenter.notify(
                    applicationContext, NotifCenter.Cat.DOC,
                    title.ifEmpty { "📄 Document ready" },
                    body.ifEmpty { "Document taiyaar hai — dekho." },
                    deepTab = deepTab,
                    deepRunId = runId,
                    key = runId.ifEmpty { "doc-$title" }
                )
                "status" -> NotifCenter.notify(
                    applicationContext, NotifCenter.Cat.STATUS,
                    title.ifEmpty { "FormMitra" },
                    body.ifEmpty { "Naya update aaya hai." },
                    deepTab = deepTab,
                    deepRunId = runId
                )
                // POINT 25: tracking → action offer. Offer card + notification
                // (tap → /agent tab). Koi auto-run NAHI.
                // FCM data me sirf ids hoti hain; poora text notification
                // title/body me hota hai (realtime path me full payload).
                "action_offer" -> {
                    try {
                        val pushTitle = title.removePrefix("FormMitra — ")
                            .removePrefix("FormMitra - ").ifEmpty {
                                TrackOfferPolicy.defaultTitle(
                                    data["offer_kind"] ?: ""
                                )
                            }
                        val offer = TrackOffer.fromMap(
                            mapOf(
                                "offer_id" to data["offer_id"],
                                "offer_kind" to data["offer_kind"],
                                "title" to pushTitle,
                                "question" to body,
                                "goal" to data["goal"],
                                "url" to data["url"]
                            )
                        )
                        if (offer != null) {
                            TrackOffer.receive(applicationContext, offer)
                        } else {
                            Log.w(TAG, "action_offer invalid, ignore")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "action_offer handle failed", t)
                    }
                }
                else -> {
                    // Bina type ke notification-payload → seedha dikhao.
                    if (msg.notification != null || title.isNotEmpty()) {
                        NotifCenter.notify(
                            applicationContext, NotifCenter.Cat.STATUS,
                            title, body, deepTab = deepTab, deepRunId = runId
                        )
                    } else {
                        Log.i(TAG, "unknown push type, ignore: $type")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onMessageReceived failed (non-fatal)", t)
        }
    }

    companion object {
        private const val TAG = "FmPush"
    }
}

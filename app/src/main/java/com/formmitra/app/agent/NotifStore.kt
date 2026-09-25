package com.formmitra.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * NotifStore — K5: purani notifications ka tray/inbox (device-local).
 *
 * NotifCenter.notify() har dikhayi gayi notification ko yahan save karta hai.
 * History tab ke 🔔 button aur Profile → Notifications se khulta hai.
 * Tap → deep link (wahi screen jahan notification le jati).
 *
 * Sirf title/body/time/link — koi secret ya document content kabhi nahi.
 * Cap 50 entries (purani auto-hat ti hain). Unread count = app-icon badge.
 */
object NotifStore {
    private const val PREFS = "fm_notif_inbox"
    private const val KEY = "items"
    private const val MAX = 50

    data class Item(
        val id: Long,
        val ts: Long,
        val cat: String,
        val title: String,
        val body: String,
        val tab: String,
        val runId: String,
        val promptRunId: String,
        val read: Boolean,
        val key: String = ""
    )

    /**
     * Nayi notification save karo (sabse upar). Cap 50.
     * L5: event-key dedupe — same (cat,key) dobara aaye to purani entry
     * UPDATE hoti hai, duplicate nahi (e.g. baar-baar "detail chahiye").
     */
    fun save(
        ctx: Context,
        cat: NotifCenter.Cat,
        title: String,
        body: String,
        tab: String,
        runId: String,
        promptRunId: String,
        key: String = ""
    ) {
        try {
            val items = loadRaw(ctx).toMutableList()
            if (key.isNotEmpty()) {
                val idx = items.indexOfFirst { it.cat == cat.name && it.key == key }
                if (idx >= 0) {
                    val updated = items[idx].copy(
                        title = title, body = body,
                        ts = System.currentTimeMillis(),
                        tab = tab, runId = runId, promptRunId = promptRunId,
                        read = false
                    )
                    items.removeAt(idx)
                    items.add(0, updated)
                    persist(ctx, items.take(MAX))
                    return
                }
            }
            items.add(
                0, Item(
                    id = System.currentTimeMillis(),
                    ts = System.currentTimeMillis(),
                    cat = cat.name,
                    title = title, body = body,
                    tab = tab, runId = runId, promptRunId = promptRunId,
                    read = false,
                    key = key
                )
            )
            persist(ctx, items.take(MAX))
        } catch (_: Exception) { }
    }

    /** Nayi se purani — inbox list. */
    fun list(ctx: Context): List<Item> = try {
        loadRaw(ctx)
    } catch (_: Exception) {
        emptyList()
    }

    fun unreadCount(ctx: Context): Int = try {
        loadRaw(ctx).count { !it.read }
    } catch (_: Exception) {
        0
    }

    fun markAllRead(ctx: Context) {
        try {
            persist(ctx, loadRaw(ctx).map { it.copy(read = true) })
        } catch (_: Exception) { }
    }

    fun markRead(ctx: Context, id: Long) {
        try {
            persist(ctx, loadRaw(ctx).map { if (it.id == id) it.copy(read = true) else it })
        } catch (_: Exception) { }
    }

    fun clear(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply()
        } catch (_: Exception) { }
    }

    // ---------- internal ----------

    private fun loadRaw(ctx: Context): List<Item> {
        val raw = try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<Item>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Item(
                        id = o.optLong("id", 0),
                        ts = o.optLong("ts", 0),
                        cat = o.optString("cat", "TASK"),
                        title = o.optString("title", ""),
                        body = o.optString("body", ""),
                        tab = o.optString("tab", "/history"),
                        runId = o.optString("runId", ""),
                        promptRunId = o.optString("promptRunId", ""),
                        read = o.optBoolean("read", true),
                        key = o.optString("key", "")
                    )
                )
            }
        } catch (_: Exception) { }
        return out
    }

    private fun persist(ctx: Context, items: List<Item>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("ts", it.ts)
                    .put("cat", it.cat)
                    .put("title", it.title)
                    .put("body", it.body)
                    .put("tab", it.tab)
                    .put("runId", it.runId)
                    .put("promptRunId", it.promptRunId)
                    .put("read", it.read)
                    .put("key", it.key)
            )
        }
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply()
        } catch (_: Exception) { }
    }
}

package com.formmitra.app.engine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * StandaloneStore — no-server (offline) tasks ka local store.
 *
 * Server unreachable ho aur user ki Groq key saved ho, to AgentChatView task
 * yahan banata hai; FormRunService ise AgentLoop (forceStandalone) se chalata
 * hai. BootReceiver reboot par pending tasks dobara uthata hai.
 * Koi extra dependency nahi — plain SQLiteOpenHelper.
 */
object StandaloneStore {

    data class Task(val id: String, val goal: String, val url: String, val status: String)

    private const val DB = "fm_standalone.db"
    private const val VER = 1
    private const val TABLE_SQL =
        "CREATE TABLE IF NOT EXISTS tasks (" +
            "id TEXT PRIMARY KEY, goal TEXT, url TEXT, status TEXT, " +
            "created_at INTEGER, updated_at INTEGER)"

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, DB, null, VER) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(TABLE_SQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL(TABLE_SQL)
        }
    }

    private fun open(ctx: Context, writable: Boolean): SQLiteDatabase {
        val h = Helper(ctx.applicationContext)
        return if (writable) h.writableDatabase else h.readableDatabase
    }

    /** Naya offline task banao → local id ("local-<ts>"). */
    fun create(ctx: Context, goal: String, url: String): String {
        val id = "local-${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        try {
            val d = open(ctx, true)
            try {
                val cv = ContentValues().apply {
                    put("id", id)
                    put("goal", goal)
                    put("url", url)
                    put("status", "running")
                    put("created_at", now)
                    put("updated_at", now)
                }
                d.insertWithOnConflict("tasks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            } finally {
                d.close()
            }
        } catch (_: Exception) { }
        return id
    }

    /** Abhi bhi 'running' tasks (reboot-resume ke liye). */
    fun pending(ctx: Context): List<Task> {
        val out = ArrayList<Task>()
        try {
            val d = open(ctx, false)
            try {
                d.rawQuery(
                    "SELECT id, goal, url, status FROM tasks WHERE status='running' ORDER BY created_at",
                    null
                )?.use { c ->
                    while (c.moveToNext()) {
                        out.add(
                            Task(
                                c.getString(0) ?: "",
                                c.getString(1) ?: "",
                                c.getString(2) ?: "",
                                c.getString(3) ?: ""
                            )
                        )
                    }
                }
            } finally {
                d.close()
            }
        } catch (_: Exception) { }
        return out.filter { it.id.isNotEmpty() }
    }

    /** Terminal status save karo (done/failed/vetoed/needs_user). */
    fun setStatus(ctx: Context, id: String, status: String) {
        if (id.isEmpty()) return
        try {
            val d = open(ctx, true)
            try {
                val cv = ContentValues().apply {
                    put("status", status)
                    put("updated_at", System.currentTimeMillis())
                }
                d.update("tasks", cv, "id=?", arrayOf(id))
            } finally {
                d.close()
            }
        } catch (_: Exception) { }
    }
}

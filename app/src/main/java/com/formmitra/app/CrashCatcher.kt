package com.formmitra.app

import android.content.Context
import android.content.Intent
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashCatcher — v22.
 *
 * KYUN: v21 kuch phones par "khulte hi band" ho raha tha aur wajah phone ke
 * bahar se dikh nahi rahi thi. Ye catcher har uncaught exception ko pakad kar:
 *  1. poora stack trace `/files/last_crash.txt` me likhta hai,
 *  2. CrashReportActivity kholta hai taaki user ko exact wajah dikhe
 *     (copy karke bhej sake).
 *
 * Install FmApp.onCreate ki PEHLI line me hota hai — isse pehle crash ho to
 * system default handler chalega (wahan kuch nahi kar sakte).
 */
object CrashCatcher {
    private const val FILE = "last_crash.txt"
    private const val TAG = "CrashCatcher"

    fun install(ctx: Context) {
        val appCtx = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            try {
                val ts = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.US
                ).format(Date())
                val sw = StringWriter()
                err.printStackTrace(PrintWriter(sw))
                val report = buildString {
                    append("[$ts] thread=${thread.name}\n")
                    append(err.toString()).append("\n")
                    append(sw.toString())
                }
                try {
                    File(appCtx.filesDir, FILE).writeText(report)
                } catch (_: Exception) { }
                android.util.Log.e(TAG, "Crash pakda:\n$report")
            } catch (_: Exception) { }

            // Crash screen khud crash hui ho to loop se bacho.
            val inCrashScreen = try {
                err.stackTrace.any {
                    it.className.contains("CrashReportActivity")
                }
            } catch (_: Exception) { false }
            if (!inCrashScreen) {
                try {
                    val i = Intent(appCtx, CrashReportActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                        )
                    }
                    appCtx.startActivity(i)
                } catch (_: Exception) { }
            }
            try {
                prev?.uncaughtException(thread, err)
            } catch (_: Exception) { }
            Process.killProcess(Process.myPid())
        }
    }

    fun readReport(ctx: Context): String = try {
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) f.readText() else ""
    } catch (_: Exception) { "" }

    fun clear(ctx: Context) {
        try {
            File(ctx.filesDir, FILE).delete()
        } catch (_: Exception) { }
    }
}

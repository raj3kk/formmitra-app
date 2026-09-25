package com.formmitra.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * CrashReportActivity — v22.
 *
 * App crash hone par CrashCatcher ise kholta hai. User ko simple Hinglish me
 * batata hai kya hua, aur poori technical report copy karne ka button deta hai
 * taaki wo hume bhej sake (exact root cause ke liye).
 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashCatcher.readReport(this)
        val summary = if (report.isEmpty()) {
            "Report nahi mili."
        } else {
            // Pehli 8 lines — exception + top frames (padhne layak hissa).
            report.lines().take(8).joinToString("\n")
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF8F0"))
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "😟 App me dikkat aayi"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#7A1F1F"))
        })
        root.addView(TextView(this).apply {
            text = "Ghabrao mat — aapka data surakshit hai. " +
                "Neeche wajah likhi hai; ise copy karke hume bhej do, " +
                "hum turant theek karenge."
            textSize = 14f
            setTextColor(Color.parseColor("#4E342E"))
            setPadding(0, pad / 2, 0, pad / 2)
        })
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        scroll.addView(TextView(this).apply {
            text = summary
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#202124"))
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        })
        root.addView(scroll)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
        }
        btnRow.addView(Button(this).apply {
            text = "📋 Copy karo"
            setOnClickListener {
                try {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", report))
                    Toast.makeText(
                        this@CrashReportActivity,
                        "Copy ho gaya — hume bhej do",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Exception) { }
            }
        })
        btnRow.addView(Button(this).apply {
            text = "Band karo"
            setOnClickListener { finish() }
        })
        root.addView(btnRow)
        setContentView(root)
    }
}

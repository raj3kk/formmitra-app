package com.formmitra.app.engine

import android.util.Log

/**
 * v58: ADMIN TAKEOVER — admin vs agent conflict ka root solution.
 *
 * Problem: Admin kisi browser me kuch check karne jata hai, agent usi
 * waqt usi browser me apna kaam karta hai → dono ki actions mix →
 * agent confuse/fail/atak (misunderstanding).
 *
 * Solution:
 * 1. Admin jis browser ko CHHUta hai (touch), wo browser turant
 *    "admin ke control me" mark ho jata hai.
 * 2. Agent har browser action se PEHLE check karta hai —
 *    agar admin control me hai to agent RUKTA hai (ladta nahi),
 *    chhote intervals me recheck karta hai.
 * 3. Admin 30 second tak kuch na chhue to control AUTO-RELEASE —
 *    agent wapas kaam shuru kar deta hai.
 * 4. Admin chahe to "Agent ko wapas do" button se turant release.
 * 5. Dono taraf status dikhta hai — koi confusion nahi.
 *
 * Ye object thread-safe hai (agent background thread se, UI main
 * thread se access hota hai).
 */
object AdminTakeover {
    private const val TAG = "FmAdminTakeover"

    /** Admin 30s tak na chhue to control auto-release. */
    const val IDLE_RELEASE_MS = 30_000L

    @Volatile private var drivingWork = false
    @Volatile private var drivingHelp = false
    @Volatile private var lastTouchWork = 0L
    @Volatile private var lastTouchHelp = 0L

    /** Listener — LiveTabView status UI update ke liye. */
    @Volatile var onChange: (() -> Unit)? = null

    /**
     * Admin ne browser ko chhua (touch DOWN).
     * @param work true = work browser, false = AI helper browser
     */
    @Synchronized
    fun onAdminTouch(work: Boolean) {
        val now = System.currentTimeMillis()
        if (work) {
            if (!drivingWork) {
                Log.i(TAG, "Admin ne WORK browser sambhala — agent rukega")
            }
            drivingWork = true
            lastTouchWork = now
        } else {
            if (!drivingHelp) {
                Log.i(TAG, "Admin ne HELP browser sambhala — AI help rukegi")
            }
            drivingHelp = true
            lastTouchHelp = now
        }
        notifyChange()
    }

    /**
     * Kya admin abhi is browser ko chala raha hai?
     * Idle timeout par auto-release bhi yahin hota hai.
     */
    @Synchronized
    fun isDriving(work: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (work) {
            if (drivingWork && now - lastTouchWork > IDLE_RELEASE_MS) {
                drivingWork = false
                Log.i(TAG, "Work browser auto-release (30s idle) — agent wapas")
                notifyChange()
            }
            return drivingWork
        } else {
            if (drivingHelp && now - lastTouchHelp > IDLE_RELEASE_MS) {
                drivingHelp = false
                Log.i(TAG, "Help browser auto-release (30s idle) — AI help wapas")
                notifyChange()
            }
            return drivingHelp
        }
    }

    /** Admin ne "Agent ko wapas do" dabaya — turant release. */
    @Synchronized
    fun release(work: Boolean) {
        if (work) {
            if (drivingWork) Log.i(TAG, "Admin ne work browser release kiya")
            drivingWork = false
        } else {
            if (drivingHelp) Log.i(TAG, "Admin ne help browser release kiya")
            drivingHelp = false
        }
        notifyChange()
    }

    @Synchronized
    fun releaseAll() {
        drivingWork = false
        drivingHelp = false
        notifyChange()
    }

    /**
     * AGENT KA WAIT POINT — har browser action se pehle call karo.
     * Agar admin control me hai to 2s ke chunks me rukta hai jab tak
     * admin release na kare (30s idle auto-release ya manual button).
     * Kabhi infinite nahi — har chunk ke baad recheck.
     */
    fun waitIfDriving(work: Boolean, tag: String) {
        var waited = 0
        while (isDriving(work)) {
            if (waited == 0) {
                Log.i(TAG, "[$tag] Admin control me hai — agent ruk gaya, " +
                    "release ka intezaar")
            }
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            waited += 2
            // Har 30s par ek reminder log (spam nahi)
            if (waited % 30 == 0) {
                Log.i(TAG, "[$tag] Abhi bhi admin control me (${waited}s) — " +
                    "intezaar jaari")
            }
        }
        if (waited > 0) {
            Log.i(TAG, "[$tag] Admin ne release kiya (${waited}s ruka) — " +
                "agent wapas kaam par")
        }
    }

    private fun notifyChange() {
        try {
            onChange?.invoke()
        } catch (_: Exception) { }
    }
}

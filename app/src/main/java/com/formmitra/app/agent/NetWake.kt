package com.formmitra.app.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.formmitra.app.WakeWorker

/**
 * NetWake — network change listener (G2/G6).
 *
 * offline→online transition pakdo → WakeWorker enqueue (server ping +
 * pending run ka usi step se resume). Sirf WorkingMode ON par.
 *
 * - Ek hi callback registered rehta hai (double-register guard).
 * - Pehla onAvailable (register ke waqt ki current state) wake NAHI
 *   karta — sirf asli offline→online transition par.
 * - Koi nayi permission nahi (ACCESS_NETWORK_STATE pehle se hai).
 */
object NetWake {
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var wasOffline = false

    fun register(ctx: Context) {
        if (callback != null) return
        val appCtx = ctx.applicationContext
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        wasOffline = !isOnline(appCtx)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Register ke turant baad wala onAvailable = current state,
                // transition nahi. wasOffline tabhi true hoga jab register
                // ke waqt offline the — wahi asli transition hai.
                if (wasOffline) {
                    wasOffline = false
                    Log.i("NetWake", "offline→online — wake")
                    onWake(appCtx)
                }
            }

            override fun onLost(network: Network) {
                wasOffline = true
                Log.i("NetWake", "network lost — offline flag")
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
            Log.i("NetWake", "registered (wasOffline=$wasOffline)")
        } catch (t: Throwable) {
            Log.e("NetWake", "register failed (non-fatal)", t)
        }
    }

    fun unregister(ctx: Context) {
        val cb = callback ?: return
        callback = null
        try {
            val cm = ctx.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) { }
        Log.i("NetWake", "unregistered")
    }

    fun isOnline(ctx: Context): Boolean {
        return try {
            val cm = ctx.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(
                android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun onWake(ctx: Context) {
        if (!WorkingMode.isEnabled(ctx)) return
        try {
            WakeWorker.enqueue(ctx)
        } catch (t: Throwable) {
            Log.e("NetWake", "wake enqueue failed (non-fatal)", t)
        }
    }
}

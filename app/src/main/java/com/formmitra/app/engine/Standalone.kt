package com.formmitra.app.engine

import android.content.Context

/**
 * Standalone — no-server mode (Phase 4).
 *
 * User apni Groq API key app settings me save karta hai (CryptoVault me
 * encrypted — key kabhi plaintext me nahi rehti). Server unreachable ho
 * (network fail / HTTP 5xx) to AgentLoop StandaloneBrain se seedha Groq
 * ko call karta hai — bina FormMitra server ke form bharta hai.
 *
 * UI agent ka contract:
 *   Standalone.isConfigured(ctx) → key saved hai?
 *   Standalone.saveKey(ctx, key) / getKey(ctx) / clearKey(ctx)
 * Local task IDs: "local-<timestamp>" (AgentLoop khud banata hai jab
 * runId khaali ho).
 */
object Standalone {

    const val KEY_API = "groq_api_key"

    fun isConfigured(ctx: Context): Boolean =
        CryptoVault.hasSecure(ctx, KEY_API)

    fun saveKey(ctx: Context, key: String) {
        val k = key.trim()
        require(k.isNotEmpty()) { "API key khaali hai" }
        CryptoVault.putSecure(ctx, KEY_API, k)
    }

    fun getKey(ctx: Context): String? =
        CryptoVault.getSecure(ctx, KEY_API)

    fun clearKey(ctx: Context) =
        CryptoVault.clearSecure(ctx, KEY_API)
}

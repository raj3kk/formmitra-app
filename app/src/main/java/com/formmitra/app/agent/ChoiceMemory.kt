package com.formmitra.app.agent

import android.content.Context
import org.json.JSONObject

/**
 * ChoiceMemory — L1-UPGRADE: "choice" prompt ab har baar manual nahi.
 * User ne ek domain+title par jo option chuna, wahi agli baar apne
 * aap lagta hai (notification me bataya jata hai). User Profile se
 * yaad kiye gaye choices saaf kar sakta hai (future scope note).
 *
 * Sirf non-sensitive choices yaad hote hain — payment/OTP/login
 * kabhi nahi.
 */
object ChoiceMemory {
    private const val PREFS = "formmitra_choice_memory"
    private const val KEY = "choices_v1"

    fun scope(domain: String, title: String): String =
        "${domain.trim().lowercase()}|${title.trim().take(80)}"

    fun get(ctx: Context, scope: String): String? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return try { JSONObject(raw).optString(scope, null)?.ifEmpty { null } }
        catch (_: Exception) { null }
    }

    fun save(ctx: Context, scope: String, choice: String) {
        if (scope.isBlank() || choice.isBlank()) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val obj = try {
            JSONObject(prefs.getString(KEY, null) ?: "{}")
        } catch (_: Exception) { JSONObject() }
        obj.put(scope, choice.take(200))
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}

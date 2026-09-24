package com.formmitra.app.engine

import android.content.Context
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * CryptoVault — device-side encryption (Phase 5: vault encryption).
 *
 * Android Keystore (alias "fm_vault_v1", AES-256-GCM, randomized IV) —
 * key kabhi app process se bahar nahi nikalti, koi nayi dependency nahi
 * (androidx.security nahi laya gaya — manual Keystore + Cipher hi kaafi).
 *
 *   putSecure/getSecure : chhote secrets (API keys) → SharedPreferences
 *                         "fm_secure" me Base64(IV + ciphertext)
 *   encryptFile/decryptFile : docs jaise files → file ke first 12 bytes IV,
 *                             baaki ciphertext (GCM tag andar hi)
 *
 * Har crypto failure EXCEPTION deta hai — caller decide kare (fallback ya
 * needs_user). getSecure galat/corrupt value par null deta hai (crash nahi).
 */
object CryptoVault {

    private const val ALIAS = "fm_vault_v1"
    private const val PREFS = "fm_secure"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    private fun encryptBytes(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv.size == IV_LEN) { "unexpected IV size ${iv.size}" }
        val ct = cipher.doFinal(plain)
        return iv + ct
    }

    private fun decryptBytes(blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "blob too short" }
        val iv = blob.copyOfRange(0, IV_LEN)
        val ct = blob.copyOfRange(IV_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        return cipher.doFinal(ct)
    }

    /** Secret save karo (API key jaise). Overwrite allowed. */
    fun putSecure(ctx: Context, key: String, value: String) {
        val blob = encryptBytes(value.toByteArray(Charsets.UTF_8))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, Base64.encodeToString(blob, Base64.NO_WRAP))
            .apply()
    }

    /** Secret padho. Missing/corrupt → null (kabhi throw nahi). */
    fun getSecure(ctx: Context, key: String): String? {
        val b64 = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            String(decryptBytes(blob), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Non-empty secret saved hai? */
    fun hasSecure(ctx: Context, key: String): Boolean =
        getSecure(ctx, key)?.isNotEmpty() == true

    /** Secret hatao. */
    fun clearSecure(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key).apply()
    }

    /** Plain bytes → encrypted file (IV file ke first 12 bytes). */
    fun encryptFile(ctx: Context, plain: ByteArray, dest: File) {
        dest.parentFile?.mkdirs()
        val blob = encryptBytes(plain)
        dest.outputStream().use { it.write(blob) }
    }

    /**
     * Encrypted file → plain bytes. Galat/plaintext file par EXCEPTION —
     * caller (FormEngine) purani plaintext files ke liye fallback rakhta hai.
     */
    fun decryptFile(ctx: Context, src: File): ByteArray {
        val blob = src.inputStream().use { it.readBytes() }
        return decryptBytes(blob)
    }
}

/**
 * VaultProfileCache — user ke vault profile (naam, phone, address...) ka
 * encrypted local cache. Server reachable ho to fresh profile laake cache
 * update hota hai; server unreachable ho to cache se kaam chalta hai —
 * isliye standalone/offline mode sach me server ke bina kaam karta hai.
 * Cache CryptoVault me AES-256-GCM se encrypted hai.
 */
object VaultProfileCache {
    private const val KEY = "vault_profile_cache_v1"

    /** Profile cache karo (encrypted). Khaali profile cache nahi hoti. */
    fun save(ctx: Context, profile: Map<String, String>) {
        if (profile.isEmpty()) return
        try {
            val obj = JSONObject()
            for ((k, v) in profile) obj.put(k, v)
            CryptoVault.putSecure(ctx, KEY, obj.toString())
        } catch (_: Exception) { }
    }

    /** Cached profile, ya null (koi cache nahi ya corrupt). */
    fun load(ctx: Context): Map<String, String>? {
        val raw = CryptoVault.getSecure(ctx, KEY) ?: return null
        return try {
            val obj = JSONObject(raw)
            val out = HashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "").trim()
                if (v.isNotEmpty()) out[k] = v
            }
            out.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Vault profile lao: pehle server (fresh), fail ho to encrypted cache.
     * Dono fail → khaali map. Duplicate fetchVaultProfile ka single source.
     */
    fun fetch(ctx: Context): Map<String, String> {
        val fresh = try {
            val p = com.formmitra.app.agent.AgentApi.profile(ctx)
            if (p == null) null else {
                val out = HashMap<String, String>()
                val keys = p.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = p.optString(k, "").trim()
                    if (v.isNotEmpty()) out[k] = v
                }
                out
            }
        } catch (_: Exception) {
            null
        }
        if (fresh != null && fresh.isNotEmpty()) {
            save(ctx, fresh)
            return fresh
        }
        return load(ctx) ?: emptyMap()
    }
}

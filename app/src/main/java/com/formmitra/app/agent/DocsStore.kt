package com.formmitra.app.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.formmitra.app.engine.CryptoVault
import com.formmitra.app.engine.DocCompressPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject

/**
 * DocsStore — user ke documents (photo/PDF) filesDir/docs/ me ENCRYPTED save rakhta hai.
 * Android Keystore (AES-256-GCM) se encrypt hota hai; engine upload se pehle decrypt karta hai.
 * Purani plaintext files bhi engine me chalti rahengi (backward compatible).
 */
object DocsStore {

    fun docsDir(ctx: Context): File =
        File(ctx.filesDir, "docs").apply { if (!exists()) mkdirs() }

    /**
     * Content URI → filesDir/docs/ me copy karo.
     * POINT 16: badi photo ho to DocCompressPolicy ladder se 400KB tak
     * compress (Bitmap, OOM-safe) — upload tez, data bachat. Note batata
     * hai kya hua (picker result me dikhao).
     * @return (saved file ka naam, user note) — fail par (null, reason).
     */
    fun saveDocWithNote(ctx: Context, uri: Uri): Pair<String?, String> {
        return try {
            val cr = ctx.contentResolver
            var name: String? = null
            cr.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) name = c.getString(idx)
            }
            var base = (name?.trim().takeIf { !it.isNullOrEmpty() }
                ?: "doc_${System.currentTimeMillis()}")
            // Unsafe chars hatao
            base = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (base.length > 80) base = base.take(80)
            val dir = docsDir(ctx)
            var dest = File(dir, base)
            if (dest.exists()) {
                // clash → timestamp jodo
                val dot = base.lastIndexOf('.')
                val stem = if (dot > 0) base.substring(0, dot) else base
                val ext = if (dot > 0) base.substring(dot) else ""
                dest = File(dir, "${stem}_${System.currentTimeMillis()}$ext")
            }
            cr.openInputStream(uri)?.use { ins ->
                dest.outputStream().use { outs -> ins.copyTo(outs) }
            } ?: return null to "File khul nahi payi"
            // POINT 16: actual bitmap compression (policy-driven).
            var note = ""
            try {
                val bytes = dest.readBytes()
                val kind = DocCompressPolicy.detectKind(bytes)
                when (val d = DocCompressPolicy.decide(bytes.size.toLong(), kind)) {
                    is DocCompressPolicy.Decision.Compress -> {
                        val compressed = compressLadder(bytes, d.steps)
                        if (compressed != null) {
                            dest.writeBytes(compressed)
                            note = DocCompressPolicy.compressedNote()
                        } else {
                            note = d.giveUpNote
                        }
                    }
                    is DocCompressPolicy.Decision.Keep -> {
                        note = d.note
                    }
                }
            } catch (_: OutOfMemoryError) {
                note = "Photo bahut badi thi — original hi rakhi hai."
            } catch (_: Exception) {
                // compress fail → original rakho, note khali
            }
            // Vault encryption (fail-closed): file ko Keystore AES-256-GCM se
            // encrypt karo. Fail ho to plaintext file DELETE karo aur null
            // wapas do — aadhi-encrypted ya plaintext vault me kabhi nahi.
            // (Purani plaintext files ka read-fallback engine me ab bhi hai.)
            try {
                val plain = dest.readBytes()
                CryptoVault.encryptFile(ctx, plain, dest)
            } catch (_: Exception) {
                try { dest.delete() } catch (_: Exception) { }
                return null to "Encrypt nahi ho paya"
            }
            // Engine baad me bhi padh sake — permission persist karo
            try {
                cr.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            dest.name to note
        } catch (_: Exception) {
            null to "File save nahi hui — dobara try karo"
        }
    }

    /**
     * Content URI → filesDir/docs/ me copy karo.
     * @return saved file ka naam, ya null (fail).
     */
    fun saveDoc(ctx: Context, uri: Uri): String? =
        saveDocWithNote(ctx, uri).first

    /**
     * POINT 16: DocCompressPolicy ladder ko Bitmap par chalao.
     * Pehla step jo 400KB ke andar aaye wahi lo. OOM-safe (inSampleSize +
     * OutOfMemoryError catch). @return compressed JPEG bytes ya null.
     */
    private fun compressLadder(
        bytes: ByteArray,
        steps: List<DocCompressPolicy.Step>
    ): ByteArray? {
        val bound = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bound)
        } catch (_: Exception) { return null }
        val w = bound.outWidth
        val h = bound.outHeight
        if (w <= 0 || h <= 0) return null
        for (s in steps) {
            var bmp: Bitmap? = null
            var scaled: Bitmap? = null
            try {
                val targetW = (w * s.scale).toInt().coerceAtLeast(1)
                val targetH = (h * s.scale).toInt().coerceAtLeast(1)
                // Power-of-2 sample jo target se bada rahe.
                var sample = 1
                while (w / (sample * 2) >= targetW &&
                    h / (sample * 2) >= targetH
                ) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                }
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?: continue
                scaled = if (s.scale < 1.0 &&
                    (bmp.width != targetW || bmp.height != targetH)
                ) {
                    Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                } else {
                    bmp
                }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, s.quality, out)
                val result = out.toByteArray()
                if (result.size <= DocCompressPolicy.MAX_BYTES) return result
            } catch (_: OutOfMemoryError) {
                // Agla (chhota) step try karo.
            } catch (_: Exception) {
                // Agla step try karo.
            } finally {
                try {
                    if (scaled != null && scaled !== bmp) scaled.recycle()
                    bmp?.recycle()
                } catch (_: Exception) { }
            }
        }
        return null
    }

    /** Save ki hui files ke naam (sorted). */
    fun listDocs(ctx: Context): List<String> {
        return try {
            docsDir(ctx).listFiles()
                ?.filter { it.isFile && it.canRead() }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Vault se file hatao — sirf docs dir ke ANDAR ki file (canonical-path
     * guard; traversal bahar nahi). Bina confirm ke mat bulao.
     * @return true agar file delete hui.
     */
    fun deleteDoc(ctx: Context, name: String): Boolean {
        return try {
            if (name.isEmpty()) return false
            val dir = docsDir(ctx)
            val f = File(dir, name)
            val canonBase = try { dir.canonicalPath }
            catch (_: Exception) { dir.absolutePath }
            val canonFile = try { f.canonicalPath }
            catch (_: Exception) { return false }
            if (!canonFile.startsWith(canonBase + File.separator)) return false
            val gone = f.isFile && f.delete()
            if (gone) clearDocType(ctx, name) // type metadata bhi saaf
            return gone
        } catch (_: Exception) {
            false
        }
    }

    /**
     * v14: purani plaintext file → successful read ke baad encrypted me
     * migrate karo (best-effort). docs dir ke bahar ki files ko chhedo mat.
     */
    fun migratePlaintextToEncrypted(ctx: Context, file: File) {
        try {
            val dir = docsDir(ctx)
            val canonBase = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
            val canonFile = try { file.canonicalPath } catch (_: Exception) { return }
            if (!canonFile.startsWith(canonBase + File.separator)) return
            try {
                CryptoVault.decryptFile(ctx, file)
                return // pehle se encrypted — kuch nahi karna
            } catch (_: Exception) { /* plaintext → migrate karo */ }
            val plain = file.readBytes()
            CryptoVault.encryptFile(ctx, plain, file)
        } catch (_: Exception) { }
    }

    // ---------- document type metadata (v20, Task 3) ----------
    //
    // "Kaun sa document hai?" — har doc ke saath uska type device-local
    // save hota hai (filesDir/docs_meta.json). DOC-PRIVACY: ye metadata
    // kabhi server ko nahi jata — filename ki tarah sirf is phone par.

    /** Type options — Task 3 ke exact options ("Other" = custom text). */
    val DOC_TYPES = listOf(
        "Aadhaar",
        "PAN",
        "Voter ID",
        "Driving License",
        "Passport",
        "Marksheet",
        "Caste Certificate",
        "Income Certificate",
        "Domicile",
        "Photo",
        "Other"
    )

    private fun metaFile(ctx: Context): File = File(ctx.filesDir, "docs_meta.json")

    private fun readMeta(ctx: Context): JSONObject = try {
        val f = metaFile(ctx)
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    /** Doc ka saved type, ya "" (pata nahi). */
    fun getDocType(ctx: Context, name: String): String =
        try { readMeta(ctx).optString(name, "") } catch (_: Exception) { "" }

    /** Doc ka type save karo (device-local only). */
    fun setDocType(ctx: Context, name: String, type: String) {
        try {
            val m = readMeta(ctx)
            m.put(name, type)
            metaFile(ctx).writeText(m.toString())
        } catch (_: Exception) { }
    }

    /** Doc ka type metadata hatao. */
    fun clearDocType(ctx: Context, name: String) {
        try {
            val m = readMeta(ctx)
            m.remove(name)
            metaFile(ctx).writeText(m.toString())
        } catch (_: Exception) { }
    }
}

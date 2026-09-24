package com.formmitra.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * DocsStore — user ke documents (photo/PDF) filesDir/docs/ me save rakhta hai.
 * Engine ka upload step yahin se file uthata hai.
 */
object DocsStore {

    fun docsDir(ctx: Context): File =
        File(ctx.filesDir, "docs").apply { if (!exists()) mkdirs() }

    /**
     * Content URI → filesDir/docs/ me copy karo.
     * @return saved file ka naam, ya null (fail).
     */
    fun saveDoc(ctx: Context, uri: Uri): String? {
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
            } ?: return null
            // Engine baad me bhi padh sake — permission persist karo
            try {
                cr.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            dest.name
        } catch (_: Exception) {
            null
        }
    }

    /** Save ki hui files ke naam (sorted). */
    fun listDocs(ctx: Context): List<String> {
        return try {
            docsDir(ctx).listFiles()?.map { it.name }?.sorted() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

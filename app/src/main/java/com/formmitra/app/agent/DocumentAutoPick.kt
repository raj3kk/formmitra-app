package com.formmitra.app.agent

import android.content.Context

/**
 * DocumentAutoPick — L1-UPGRADE: document ab "manual choice" nahi.
 * Vault me jo document hai, wahi apne aap attach hota hai:
 *  - docType hint mile aur us type ka EK doc ho → wahi
 *  - hint na ho aur vault me SIRF ek doc ho → wahi
 *  - multiple/ambiguous → null (user se puchho — fallback)
 */
object DocumentAutoPick {
    fun pick(ctx: Context, docTypeHint: String): String? {
        val docs = try { DocsStore.listDocs(ctx) } catch (_: Exception) { return null }
        if (docs.isEmpty()) return null
        val hint = docTypeHint.trim().lowercase()
        if (hint.isNotEmpty()) {
            val match = docs.filter {
                try { DocsStore.getDocType(ctx, it) } catch (_: Exception) { "" }
                    .trim().lowercase() == hint
            }
            if (match.size == 1) return match[0]
            return null
        }
        return if (docs.size == 1) docs[0] else null
    }
}

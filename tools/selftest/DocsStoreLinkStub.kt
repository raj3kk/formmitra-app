package com.formmitra.app.agent

import android.content.Context
import java.io.File

/**
 * SELFTEST-ONLY link stub — APK build me KABHI nahi jata
 * (tools/build-apk.sh sirf app/src/main compile karta hai).
 *
 * SettingsStore.storageUsedBytes real DocsStore.docsDir ko reference karta
 * hai; uska poora dep-tree (CryptoVault → AgentApi → AgentLoopLogic…)
 * selftest compile me uthana zaroori nahi kyunki test kiye jaane wale
 * functions (sync mapping, formatBytes, deleteFilesUnder) pure hain —
 * Context kabhi touch nahi hota. Ye stub sirf linker ko khush karta hai.
 * Signature real DocsStore.docsDir jaisi hi rakho.
 */
object DocsStore {
    fun docsDir(ctx: Context): File =
        File(ctx.filesDir, "docs").apply { if (!exists()) mkdirs() }
}

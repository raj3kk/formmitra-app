import com.formmitra.app.agent.SettingsStore
import org.json.JSONObject
import java.io.File

// Self-test: POINT 29 settings — SettingsStore ke pure functions.
//  - key contract pin (server sync mapping ka aadhaar)
//  - serverPatchBody: local key/value → /api/settings PATCH body tukda
//  - mergePendingToBody: pending queue → combined PATCH body
//    (sirf SYNCED_KEYS; local-only keys ignore)
//  - localPairsFromServer: server GET {settings} → local pairs
//    (unknown fields / galat quality ignore)
//  - formatBytes boundaries
//  - deleteFilesUnder: real FS par (sirf contents, root bachta hai)
// Compile: kotlinc -cp <android.jar>:<json-20231013.jar>
//   <src>/agent/SettingsStore.kt tools/selftest/DocsStoreLinkStub.kt
//   tools/selftest/SelfTestSettings.kt -d out
// (DocsStoreLinkStub: selftest-only link stub — real DocsStore ka dep-tree
//  nahi uthate; APK build me kabhi nahi jata.)
// Runtime: java -cp out:<json-20231013.jar>:<stdlib> SelfTestSettingsKt
// (android.jar runtime par NAHI — sirf pure functions chalte hain.)

var failures = 0

fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}

fun main() {
    // ============ key contract pin ============
    check("K_CAPTCHA_AUTO pinned", SettingsStore.K_CAPTCHA_AUTO == "captcha_auto")
    check("K_LIVE_QUALITY pinned", SettingsStore.K_LIVE_QUALITY == "live_quality")
    check("K_LIVE_WIFI_ONLY pinned", SettingsStore.K_LIVE_WIFI_ONLY == "live_wifi_only")
    check("SYNCED_KEYS has 3", SettingsStore.SYNCED_KEYS.size == 3)
    check(
        "SYNCED_KEYS exact",
        SettingsStore.SYNCED_KEYS == setOf("captcha_auto", "live_quality", "live_wifi_only")
    )

    // ============ serverPatchBody ============
    var b = SettingsStore.serverPatchBody(SettingsStore.K_CAPTCHA_AUTO, "1")
    check(
        "patch captcha on",
        b.optJSONObject("automation")?.optBoolean("captcha_auto_solve") == true
    )
    b = SettingsStore.serverPatchBody(SettingsStore.K_CAPTCHA_AUTO, "0")
    check(
        "patch captcha off",
        b.optJSONObject("automation")?.optBoolean("captcha_auto_solve") == false
    )
    b = SettingsStore.serverPatchBody(SettingsStore.K_LIVE_QUALITY, "saver")
    check(
        "patch quality saver",
        b.optJSONObject("live_view")?.optString("quality") == "saver"
    )
    b = SettingsStore.serverPatchBody(SettingsStore.K_LIVE_QUALITY, "full")
    check(
        "patch quality full",
        b.optJSONObject("live_view")?.optString("quality") == "full"
    )
    b = SettingsStore.serverPatchBody(SettingsStore.K_LIVE_WIFI_ONLY, "0")
    check(
        "patch wifi off",
        b.optJSONObject("live_view")?.optBoolean("wifi_only_full") == false
    )
    b = SettingsStore.serverPatchBody("sms_otp_autoread", "1")
    check("patch unknown key empty", b.length() == 0)
    b = SettingsStore.serverPatchBody("", "1")
    check("patch empty key empty", b.length() == 0)

    // ============ mergePendingToBody ============
    var m = SettingsStore.mergePendingToBody(
        mapOf(
            SettingsStore.K_CAPTCHA_AUTO to "0",
            SettingsStore.K_LIVE_QUALITY to "full",
            SettingsStore.K_LIVE_WIFI_ONLY to "1",
            SettingsStore.K_SMS_OTP to "1" // local-only → ignore hona chahiye
        )
    )
    check(
        "merge captcha",
        m.optJSONObject("automation")?.optBoolean("captcha_auto_solve") == false
    )
    check(
        "merge quality",
        m.optJSONObject("live_view")?.optString("quality") == "full"
    )
    check(
        "merge wifi",
        m.optJSONObject("live_view")?.optBoolean("wifi_only_full") == true
    )
    check("merge only 2 top keys", m.length() == 2)
    m = SettingsStore.mergePendingToBody(emptyMap())
    check("merge empty → empty", m.length() == 0)
    m = SettingsStore.mergePendingToBody(mapOf(SettingsStore.K_SMS_OTP to "1"))
    check("merge local-only → empty", m.length() == 0)
    m = SettingsStore.mergePendingToBody(mapOf(SettingsStore.K_CAPTCHA_AUTO to "1"))
    check("merge single key", m.length() == 1 && m.has("automation"))

    // ============ localPairsFromServer ============
    val full = JSONObject(
        "{\"notifications\":{\"tracking_change\":true,\"gate\":true,\"work_done\":true}," +
            "\"live_view\":{\"wifi_only_full\":false,\"quality\":\"saver\"}," +
            "\"automation\":{\"captcha_auto_solve\":false}," +
            "\"updated_at\":\"2026-09-26T00:00:00Z\"}"
    )
    var p = SettingsStore.localPairsFromServer(full)
    check("pairs captcha off", p[SettingsStore.K_CAPTCHA_AUTO] == "0")
    check("pairs quality saver", p[SettingsStore.K_LIVE_QUALITY] == "saver")
    check("pairs wifi off", p[SettingsStore.K_LIVE_WIFI_ONLY] == "0")
    check("pairs size 3", p.size == 3)

    p = SettingsStore.localPairsFromServer(
        JSONObject("{\"automation\":{\"captcha_auto_solve\":true}}")
    )
    check("pairs partial captcha", p[SettingsStore.K_CAPTCHA_AUTO] == "1")
    check("pairs partial size 1", p.size == 1)

    p = SettingsStore.localPairsFromServer(
        JSONObject("{\"live_view\":{\"quality\":\"ultra\",\"wifi_only_full\":true}}")
    )
    check("pairs bad quality ignored", !p.containsKey(SettingsStore.K_LIVE_QUALITY))
    check("pairs wifi kept", p[SettingsStore.K_LIVE_WIFI_ONLY] == "1")

    p = SettingsStore.localPairsFromServer(JSONObject("{}"))
    check("pairs empty", p.isEmpty())

    p = SettingsStore.localPairsFromServer(
        JSONObject("{\"foo\":1,\"automation\":{\"captcha_auto_solve\":true,\"zzz\":2}}")
    )
    check("pairs ignores unknowns", p.size == 1 && p[SettingsStore.K_CAPTCHA_AUTO] == "1")

    // ============ formatBytes ============
    check("fmt 0", SettingsStore.formatBytes(0) == "0 B")
    check("fmt 512", SettingsStore.formatBytes(512) == "512 B")
    check("fmt 1023", SettingsStore.formatBytes(1023) == "1023 B")
    check("fmt 1024", SettingsStore.formatBytes(1024) == "1 KB")
    check("fmt 1536", SettingsStore.formatBytes(1536) == "1 KB")
    check("fmt 1MB", SettingsStore.formatBytes(1024 * 1024) == "1.0 MB")
    check("fmt 1.5MB", SettingsStore.formatBytes(1572864) == "1.5 MB")

    // ============ deleteFilesUnder (real FS) ============
    val root = File("/tmp/fm-selftest-cache")
    if (root.exists()) root.deleteRecursively()
    root.mkdirs()
    File(root, "a.bin").writeBytes(ByteArray(100))
    val sub = File(root, "sub")
    sub.mkdirs()
    File(sub, "b.bin").writeBytes(ByteArray(250))
    val deep = File(sub, "deep")
    deep.mkdirs()
    File(deep, "c.bin").writeBytes(ByteArray(50))
    File(root, "empty").mkdirs()
    val freed = SettingsStore.deleteFilesUnder(root)
    check("delete freed 400", freed == 400L)
    check("delete root survives", root.isDirectory)
    check("delete root empty", (root.listFiles()?.size ?: -1) == 0)
    val freed2 = SettingsStore.deleteFilesUnder(root)
    check("delete empty → 0", freed2 == 0L)
    check(
        "delete missing → 0",
        SettingsStore.deleteFilesUnder(File("/tmp/fm-selftest-nope-xyz")) == 0L
    )
    root.delete()

    println("----")
    if (failures == 0) println("ALL SETTINGS TESTS PASSED")
    else println("$failures FAILURES")
}

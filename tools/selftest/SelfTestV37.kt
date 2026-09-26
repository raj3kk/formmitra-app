import java.io.File
import java.util.zip.ZipFile

// v37 PART 2 app-logic pins (Global Playbook sanitizer/selection/cache,
// resume-skip, one-tap repeat, memory wiring, "... N more" expansion,
// CardJson nested parsing, StateInference).
import com.formmitra.app.agent.GlobalPlaybook
import com.formmitra.app.agent.RepeatRun
import com.formmitra.app.agent.CardJson
import com.formmitra.app.engine.RunMemory
import com.formmitra.app.engine.StateInference
import com.formmitra.app.engine.MemoryWiring
import com.formmitra.app.engine.ErrorCatcher
import org.json.JSONArray
import org.json.JSONObject

// Self-test: v37 crash-fix pins (WorkManager/ArchTaskExecutor NoClassDefFoundError).
//
// v36 REAL-PHONE crash: "Kaam shuru karte waqt"
//   NoClassDefFoundError: Landroidx/arch/core/executor/ArchTaskExecutor
//   (Scheduler.kickNow -> WorkManager.enqueue -> LiveData.postValue -> ArchTaskExecutor)
// Root cause: core-runtime AAR d8 inputs me nahi tha (artifacts.txt me entry
// hi nahi thi; deps/ me sirf ek corrupt 554-byte HTML "core-runtime-2.2.0.jar"
// pada tha). d8 dangling references par fail NAHI karta — isliye JVM selftest
// par dikha nahi, phone par crash hua.
//
// Ye pins har cheez ko pakadte hain:
//  1. artifacts.txt ki HAR entry ka file maujood hai.
//  2. HAR .aar/.jar valid zip hai (corrupt HTML file dobara nahi).
//  3. coord version == file version (stale label dobara nahi —
//     play-services-basement 16.0.1 label / 18.1.0 file jaisa).
//  4. core-runtime entry maujood + uske classes.jar me ArchTaskExecutor.class.
//  5. transitive version floors (Gradle resolution ke barabar).
//  6. dex-content check (AGAR pichle build ka dex maujood ho): ArchTaskExecutor
//     + EnumEntriesKt DEFINITION. Dex na ho to SKIP (authoritative gate
//     build-apk.sh step 4b me hai — build fail karta hai).
//
// Compile: kotlinc tools/selftest/SelfTestV37.kt -d out
//   && java -cp out:<stdlib> SelfTestV37Kt
// Env override: FM_DEPS_DIR (default ~/workspace/phone-agent/tools/deps),
//   FM_DEX_DIR (default ~/workspace/formmitra-app/app-android/tools/apk-build/dex),
//   FM_DEXDUMP (default ~/workspace/phone-agent/tools/android-sdk/build-tools/34.0.0/dexdump)

var failures = 0
var skips = 0

fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}

fun skip(name: String, reason: String) {
    println("SKIP: $name ($reason)"); skips++
}

fun verTuple(v: String): List<Int> =
    v.split(Regex("[.\\-]")).map { it.toIntOrNull() ?: 0 }

fun verAtLeast(have: String, need: String): Boolean {
    val h = verTuple(have); val n = verTuple(need)
    for (i in 0 until maxOf(h.size, n.size)) {
        val a = h.getOrElse(i) { 0 }; val b = n.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return true
}

fun main() {
    // NOTE: user.home galat ho sakta hai (root shell me /root) — HOME env pehle.
    val home = System.getenv("HOME") ?: System.getProperty("user.home")
    val depsDir = File(System.getenv("FM_DEPS_DIR") ?: "$home/workspace/phone-agent/tools/deps")
    val artFile = File(depsDir, "artifacts.txt")
    check("artifacts.txt maujood", artFile.isFile)

    data class Entry(val coord: String, val group: String, val artifact: String,
                     val version: String, val file: File, val isJarCoord: Boolean)

    val entries = mutableListOf<Entry>()
    artFile.forEachLine { raw ->
        val line = raw.trim()
        if (line.isEmpty() || "=" !in line) return@forEachLine
        val coord = line.substringBefore("=")
        val path = line.substringAfter("=")
        val parts = coord.split(":")
        if (parts[0] == "jars") {
            // coord: jars:<artifact>-<version> — version strip karo taaki
            // find("kotlinx-coroutines-play-services") kaam kare
            val art = parts[1].replaceFirst(Regex("-\\d[\\d.]*$"), "")
            entries.add(Entry(coord, "jars", art, "", File(path), true))
        } else if (parts.size == 3) {
            entries.add(Entry(coord, parts[0], parts[1], parts[2], File(path), false))
        }
    }
    check("artifacts.txt me entries hain", entries.isNotEmpty())

    // 1+2+3: har entry ka file maujood + valid zip + version label sahi
    var zipOk = 0
    for (e in entries) {
        if (!e.file.isFile) {
            println("FAIL: missing file: ${e.coord} -> ${e.file}")
            failures++
            continue
        }
        val name = e.file.name
        val validZip = try {
            ZipFile(e.file).use { z -> z.entries().hasMoreElements() }
            true
        } catch (t: Throwable) { false }
        if (!validZip) {
            println("FAIL: corrupt zip (HTML?): ${e.coord} -> $name")
            failures++
            continue
        }
        zipOk++
        if (!e.isJarCoord) {
            // coord version == file version (stale label pakdo)
            val expectA = "${e.artifact}-${e.version}.aar"
            val expectJ = "${e.artifact}-${e.version}.jar"
            if (name != expectA && name != expectJ) {
                println("FAIL: stale version label: coord ${e.coord} -> file $name")
                failures++
            }
        }
    }
    check("sab artifact files valid zip (${entries.size} entries)", zipOk == entries.size)

    fun find(artifact: String): Entry? = entries.firstOrNull { it.artifact == artifact }

    // 4. THE crash-fix pin: core-runtime entry + ArchTaskExecutor class
    val coreRuntime = find("core-runtime")
    check("core-runtime artifacts.txt me hai", coreRuntime != null)
    var archTaskExecutorFound = false
    if (coreRuntime != null) {
        check("core-runtime version >= 2.2.0", verAtLeast(coreRuntime.version, "2.2.0"))
        try {
            ZipFile(coreRuntime.file).use { aar ->
                val classesJar = aar.entries().asSequence()
                    .firstOrNull { it.name == "classes.jar" }
                check("core-runtime AAR me classes.jar", classesJar != null)
                if (classesJar != null) {
                    val tmp = File.createTempFile("corert", ".jar")
                    try {
                        aar.getInputStream(classesJar).use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                        ZipFile(tmp).use { cj ->
                            archTaskExecutorFound =
                                cj.getEntry("androidx/arch/core/executor/ArchTaskExecutor.class") != null
                        }
                    } finally { tmp.delete() }
                }
            }
        } catch (t: Throwable) {
            println("FAIL: core-runtime AAR padhne me dikkat: ${t.message}"); failures++
        }
        check("ArchTaskExecutor.class core-runtime me maujood", archTaskExecutorFound)
    }

    // Corrupt HTML jar dobara nahi (v36 wali galti ka pin)
    check(
        "corrupt core-runtime-2.2.0.jar (HTML) deps me nahi",
        !File(depsDir, "core-runtime-2.2.0.jar").exists()
    )

    // 5. transitive version floors (Gradle max-resolution ke barabar)
    val floors = mapOf(
        "core-common" to "2.2.0",                    // core-runtime 2.2.0 -> [2.2.0]
        "lifecycle-livedata-core" to "2.5.1",         // livedata 2.5.1 -> 2.5.1
        "startup-runtime" to "1.1.1",                // work-runtime 2.9.0 -> 1.1.1
        "firebase-installations-interop" to "17.1.1",// installations 17.2.0 -> 17.1.1
        "annotation" to "1.6.0",                     // webkit 1.10.0 -> 1.6.0
        "annotation-experimental" to "1.3.0"
    )
    for ((art, need) in floors) {
        val e = find(art)
        check("$art entry maujood", e != null)
        if (e != null) check("$art >= $need (have ${e.version})", verAtLeast(e.version, need))
    }
    // naye transitive deps maujood (round 1: dex-audit POM scan; round 2: dex
    // referenced-vs-available audit — har ek ke liye referencer classes mile)
    for (art in listOf("transport-api", "firebase-encoders-json", "firebase-iid-interop",
        "firebase-common-ktx", "kotlinx-coroutines-play-services",
        "collection", "fragment", "legacy-support-core-utils", "versionedparcelable",
        "lifecycle-viewmodel", "viewpager", "customview")) {
        check("$art artifacts.txt me hai", find(art) != null)
    }

    // 6. dex-content check — sirf tab jab pichla build dex maujood ho
    val dexDir = File(System.getenv("FM_DEX_DIR")
        ?: "$home/workspace/formmitra-app/app-android/tools/apk-build/dex")
    val dexes = dexDir.listFiles { f -> f.name.startsWith("classes") && f.name.endsWith(".dex") }
        ?.toList() ?: emptyList()
    val dexdump = File(System.getenv("FM_DEXDUMP")
        ?: "$home/workspace/phone-agent/tools/android-sdk/build-tools/34.0.0/dexdump")
    if (dexes.isEmpty() || !dexdump.canExecute()) {
        skip("dex ArchTaskExecutor DEFINITION", "pichle build ka dex nahi (build-apk.sh 4b gate authoritative hai)")
        skip("dex EnumEntriesKt DEFINITION", "pichle build ka dex nahi (build-apk.sh 4b gate authoritative hai)")
    } else {
        // dexdump: "Class descriptor  : 'L...;'" — descriptor ke baad DO space
        fun dexHasDef(desc: String): Boolean {
            val pb = ProcessBuilder(dexdump.absolutePath, "-d", *dexes.map { it.absolutePath }.toTypedArray())
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val needle = "Class descriptor  : '$desc'"
            var found = false
            proc.inputStream.bufferedReader().useLines { lines ->
                for (l in lines) if (l.contains(needle)) { found = true; break }
            }
            proc.waitFor()
            return found
        }
        check("dex me ArchTaskExecutor DEFINITION", dexHasDef("Landroidx/arch/core/executor/ArchTaskExecutor;"))
        check("dex me EnumEntriesKt DEFINITION", dexHasDef("Lkotlin/enums/EnumEntriesKt;"))
    }

    println("v37 crash-fix pins: failures=$failures skips=$skips")
    if (failures > 0) kotlin.system.exitProcess(1)

    // ================= v37 PART 2: behavioral pins =================
    // ============ Global Playbook sanitizer ============
    fun cleanSteps(): JSONArray = JSONArray()
        .put(JSONObject().put("type", "goto")
            .put("selector", JSONObject().put("mode", "url")
                .put("value", "https://serviceonline.bihar.gov.in/"))
            .put("value_src", ""))
        .put(JSONObject().put("type", "fill")
            .put("selector", JSONObject().put("mode", "css").put("value", "#name"))
            .put("value_src", "full_name"))
        .put(JSONObject().put("type", "click")
            .put("selector", JSONObject().put("mode", "css").put("value", "#submit"))
            .put("value_src", ""))
    check("sanitizer: clean steps pass", GlobalPlaybook.Logic.sanitize(cleanSteps()).ok)
    val phoneSteps = cleanSteps()
    phoneSteps.getJSONObject(1).put("text", "9876543210")
    check("sanitizer: 10-digit number blocked", !GlobalPlaybook.Logic.sanitize(phoneSteps).ok)
    val emailSteps = cleanSteps()
    emailSteps.getJSONObject(1).put("value", "ram@example.com")
    check("sanitizer: email blocked", !GlobalPlaybook.Logic.sanitize(emailSteps).ok)
    for (vk in listOf("otp", "password", "card_pin", "cvv")) {
        val s = cleanSteps()
        s.getJSONObject(1).put("value_src", vk)
        check("sanitizer: valueKey '$vk' blocked", !GlobalPlaybook.Logic.sanitize(s).ok)
    }
    check("sanitizer: reasons listed", GlobalPlaybook.Logic.sanitize(phoneSteps).reasons.isNotEmpty())
    check("sanitizer: masked preview", GlobalPlaybook.Logic.sanitize(phoneSteps).preview.contains("[BLOCKED]"))

    // ============ selection (pure) ============
    check("select: higher success-rate jeetta", GlobalPlaybook.Logic.betterOf(0.9, 0.5, 0.7, 0.9) == "a")
    check("select: tie → higher confidence", GlobalPlaybook.Logic.betterOf(0.8, 0.9, 0.8, 0.4) == "a")
    check("select: tie conf → b", GlobalPlaybook.Logic.betterOf(0.8, 0.3, 0.8, 0.9) == "b")
    fun gp(conf: Double, status: String, steps: Int = 2): GlobalPlaybook.PlaybookPattern =
        GlobalPlaybook.PlaybookPattern("id1", "task", "site", "st", "dt",
            JSONArray().apply { for (i in 0 until steps) put(JSONObject().put("type", "click")) },
            emptyList(), conf, 5, "hash1", status)
    check("eligible: confidence > 0.5", GlobalPlaybook.Logic.eligibleServer(gp(0.8, "")))
    check("eligible: status live", GlobalPlaybook.Logic.eligibleServer(gp(0.1, "live")))
    check("eligible: status trial", GlobalPlaybook.Logic.eligibleServer(gp(0.0, "trial")))
    check("eligible: low conf + no status → nahi", !GlobalPlaybook.Logic.eligibleServer(gp(0.3, "draft")))
    check("eligible: 0 steps → nahi", !GlobalPlaybook.Logic.eligibleServer(gp(0.9, "live", 0)))
    check("eligible: null → nahi", !GlobalPlaybook.Logic.eligibleServer(null))
    check("selection: local eligible → LOCAL",
        GlobalPlaybook.Logic.select(true, 1.0, gp(0.9, "live")) == GlobalPlaybook.Logic.Selection.LOCAL)
    check("selection: no local + eligible server → GLOBAL",
        GlobalPlaybook.Logic.select(false, 0.0, gp(0.9, "live")) == GlobalPlaybook.Logic.Selection.GLOBAL)
    check("selection: kuch nahi → NONE (AI path)",
        GlobalPlaybook.Logic.select(false, 0.0, gp(0.2, "draft")) == GlobalPlaybook.Logic.Selection.NONE)

    // ============ cache key isolation ============
    val ck1 = GlobalPlaybook.Logic.cacheKey("Caste Certificate", "serviceonline.bihar.gov.in", "Bihar", "Purnea")
    val ck2 = GlobalPlaybook.Logic.cacheKey("caste certificate!!", "ServiceOnline.Bihar.Gov.In", "bihar", "purnea")
    check("cache key normalized", ck1 == ck2)
    val ck3 = GlobalPlaybook.Logic.cacheKey("Caste Certificate", "serviceonline.bihar.gov.in", "Bihar", "Katihar")
    check("cache key district isolation", ck1 != ck3)
    val ck4 = GlobalPlaybook.Logic.cacheKey("Caste Certificate", "serviceonline.bihar.gov.in", "UP", "Purnea")
    check("cache key state isolation", ck1 != ck4)
    val ob = GlobalPlaybook.Logic.outcomeBody("pid-1", true)
    check("outcome body shape",
        ob.optJSONObject("outcome")?.optString("pattern_id") == "pid-1" &&
            ob.optJSONObject("outcome")?.optBoolean("success") == true)

    // ============ resume-skip (RunMemory pure) ============
    val mem = JSONObject()
        .put("completed_steps", JSONArray().put(1).put(2).put(3))
        .put("gates", JSONArray().put(JSONObject().put("kind", "otp")))
    check("resume: completed steps", RunMemory.completedSteps(mem) == setOf(1, 2, 3))
    check("resume: resumeFrom = 3", RunMemory.resumeFrom(mem) == 3)
    check("resume: empty → 0", RunMemory.resumeFrom(JSONObject()) == 0)
    check("resume: null → 0", RunMemory.resumeFrom(null) == 0)
    val mem2 = JSONObject().put("steps", JSONArray()
        .put(JSONObject().put("n", 1).put("status", "done"))
        .put(JSONObject().put("n", 2).put("status", "failed")))
    check("resume: steps[] done shape", RunMemory.completedSteps(mem2) == setOf(1))
    check("resume: summary non-empty", RunMemory.summary(mem).contains("3 steps"))
    check("resume: summary empty on null", RunMemory.summary(null).isEmpty())
    val umem = JSONObject().put("facts", JSONObject().put("full_name", "Ram").put("state", "Bihar"))
    val facts = RunMemory.factsOf(umem)
    check("userFacts: facts parsed", facts["full_name"] == "Ram" && facts["state"] == "Bihar")
    check("userFacts: flat fallback", RunMemory.factsOf(JSONObject().put("city", "Patna"))["city"] == "Patna")

    // ============ one-tap repeat (RepeatRun pure) ============
    val entry = JSONObject()
        .put("goal", "Caste certificate apply")
        .put("url", "https://serviceonline.bihar.gov.in/")
        .put("category", "apply_track")
    val rp = RepeatRun.decide(entry, mapOf("full_name" to "Ram"), true, false)
    check("repeat: plan banta", rp != null)
    check("repeat: pattern ho to planning skip", rp?.skipPlanning == true)
    check("repeat: goal/url same",
        rp?.goal == "Caste certificate apply" && rp?.url == "https://serviceonline.bihar.gov.in/")
    check("repeat: known details reuse", rp?.knownDetails?.get("full_name") == "Ram")
    check("repeat: no pattern → planning hogi",
        RepeatRun.decide(entry, emptyMap(), false, false)?.skipPlanning == false)
    val payEntry = JSONObject(entry.toString()).put("payment", JSONObject().put("status", "paid"))
    val rpp = RepeatRun.decide(payEntry, emptyMap(), true, false)
    check("repeat: payment → gate", rpp?.needsGate == true && !rpp?.gateReason.isNullOrEmpty())
    check("repeat: no payment → no gate", rp?.needsGate == false)
    check("repeat: missing goal → null",
        RepeatRun.decide(JSONObject().put("url", "https://x.com/"), emptyMap(), true, false) == null)
    check("repeat: missing url → null",
        RepeatRun.decide(JSONObject().put("goal", "x"), emptyMap(), true, false) == null)
    check("repeat: sameWork", RepeatRun.sameWork(entry, JSONObject(entry.toString())))
    check("repeat: sameWork false on diff",
        !RepeatRun.sameWork(entry, JSONObject().put("goal", "dusra").put("url", "https://x.com/")))

    // ============ memory wiring (declaration + source pin) ============
    val sites = MemoryWiring.sites()
    check("memory: 6 call-sites", sites.size == 6)
    check("memory: har site read+write", MemoryWiring.allReadWrite())
    check("memory: sites named",
        sites.map { it.name }.toSet() == setOf("run_start", "preflight", "act_loop", "detail_gate", "payment_gate", "run_finish"))
    run {
        val appDir = System.getProperty("fm.app.dir", ".")
        fun codeOnly(src: String): String {
            var s = src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            return s.lines().filter { !it.trimStart().startsWith("//") }.joinToString("\n")
        }
        val loopSrc = codeOnly(
            File("$appDir/app/src/main/java/com/formmitra/app/engine/AgentLoop.kt").readText())
        check("memory: run-start GET wired", loopSrc.contains("RunMemory.getRun(ctx, effectiveRunId)"))
        check("memory: userFacts wired", loopSrc.contains("RunMemory.userFacts(ctx)"))
        check("memory: resume-skip wired", loopSrc.contains("memoryResumeSteps = RunMemory.resumeFrom(mem)"))
        check("memory: act me summary", loopSrc.contains("\"memory_summary\""))
        check("memory: state inference wired", loopSrc.contains("StateInference.fromSources(detailMap)"))
        check("memory: card nested parse", loopSrc.contains("CardJson.detailsOf(res.json)"))
        val hvSrc = codeOnly(
            File("$appDir/app/src/main/java/com/formmitra/app/agent/HistoryView.kt").readText())
        check("repeat: button wired", hvSrc.contains("Phir se karo") && hvSrc.contains("repeatRun(r)"))
        check("repeat: idempotency guard", hvSrc.contains("IdempotencyGuard.tryAcquire"))
    }

    // ============ "... N more" EXPANSION ============
    fun deep2(n: Int): Nothing {
        if (n <= 0) throw RuntimeException("test boom otp=123456")
        try { deep2(n - 1) } catch (t: Throwable) { throw RuntimeException("wrap $n", t) }
    }
    val boom2 = try { deep2(40); RuntimeException("unreachable") } catch (t: Throwable) { t }
    val fst = ErrorCatcher.fullStackTrace(boom2)
    check("catcher: no '... N more'", !Regex("\\.\\.\\. \\d+ more").containsMatchIn(fst))
    check("catcher: all 40 causes rendered", Regex("Caused by:").findAll(fst).count() == 40)
    check("catcher: innermost wrap present", fst.contains("wrap 1"))
    check("catcher: secrets masked in expansion", !fst.contains("123456"))
    check("catcher: technicalDetail no '... N more'",
        !Regex("\\.\\.\\. \\d+ more").containsMatchIn(ErrorCatcher.technicalDetail(boom2)))

    // ============ CardJson nested parsing ============
    val nested = JSONObject().put("card", JSONObject().put("details",
        JSONObject().put("full_name", JSONObject().put("value", "Ram").put("tag", "user"))))
    check("cardjson: nested details",
        CardJson.detailsOf(nested)?.optJSONObject("full_name")?.optString("value") == "Ram")
    val flat = JSONObject().put("details", JSONObject().put("a", "b"))
    check("cardjson: flat fallback", CardJson.detailsOf(flat)?.optString("a") == "b")
    check("cardjson: null on missing", CardJson.detailsOf(JSONObject()) == null)
    check("cardjson: null input → null", CardJson.detailsOf(null) == null)

    // ============ StateInference ============
    val detObj = JSONObject()
        .put("state", JSONObject().put("value", "Bihar").put("tag", "user"))
        .put("district", "Purnea")
        .put("name", JSONObject().put("value", "Ram"))
    val flatDet = StateInference.flattenDetails(detObj)
    check("stateinfer: flatten nested", flatDet["state"] == "Bihar" && flatDet["name"] == "Ram")
    check("stateinfer: flatten raw", flatDet["district"] == "Purnea")
    val (st2, dt2) = StateInference.fromSources(flatDet)
    check("stateinfer: fromSources", st2 == "Bihar" && dt2 == "Purnea")
    check("stateinfer: priority first-source",
        StateInference.fromSources(mapOf("state" to "UP"), mapOf("state" to "Bihar")).first == "UP")
    check("stateinfer: missing → empty", StateInference.fromSources(mapOf("x" to "y")) == ("" to ""))
    check("stateinfer: inferDistrict", StateInference.inferDistrict(detObj) == "Purnea")

    println("v37 part2: failures=$failures skips=$skips")
    if (failures > 0) kotlin.system.exitProcess(1)
}

package com.formmitra.app.engine

import android.content.Context
import com.formmitra.app.agent.AgentApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * RunMemory — v37 "AI Mind" ka memory client.
 *
 * Server endpoints (dusra worker bana raha hai — contract):
 *  - GET   /api/agent/run-memory?run_id= → working memory JSON
 *  - PATCH /api/agent/run-memory {run_id, append:{completed_steps|gates|
 *          ai_decisions|evidence|failures|...}}
 *  - GET   /api/agent/user-memory → private memory (sirf apna)
 *  - PATCH /api/agent/user-memory {facts:{...}}
 *
 * Run start par GET (yaad: kya ho chuka) — beech me ruka run resume ho to
 * completed steps skip hote hain, wahin se continue. Har AI/agent
 * call-site par relevant memory padhi jati hai (summary) AUR outcome
 * wapas likha jata hai (PATCH append).
 *
 * Sab network calls background thread pe; caller handle kare.
 * Pure functions (completedSteps / resumeFrom / summary / factsOf)
 * selftest me covered.
 */
object RunMemory {

    // ---------------- network (AgentApi auth pattern) ----------------

    /** GET /api/agent/run-memory?run_id= → working memory JSONObject ya null. */
    fun getRun(ctx: Context, runId: String): JSONObject? {
        if (runId.isEmpty()) return null
        return try {
            val res = AgentApi.runMemoryGet(ctx, runId)
            if (res.code !in 200..299) return null
            // Contract: {memory: {...}} ya seedha {...}
            res.json?.optJSONObject("memory") ?: res.json
        } catch (_: Exception) {
            null
        }
    }

    /**
     * PATCH /api/agent/run-memory {run_id, append} — BEST-EFFORT.
     * @return true = server ne accept kiya (2xx).
     */
    fun appendRun(ctx: Context, runId: String, append: JSONObject): Boolean {
        if (runId.isEmpty() || append.length() == 0) return false
        return try {
            val body = JSONObject().put("run_id", runId).put("append", append)
            AgentApi.runMemoryPatch(ctx, body).code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /**
     * GET /api/agent/user-memory → is user ke facts (private, sirf apna).
     * Values user ki apni hain — prefill/skip-sawal ke liye; KABHI log nahi.
     */
    fun userFacts(ctx: Context): Map<String, String> {
        return try {
            val res = AgentApi.userMemoryGet(ctx)
            if (res.code !in 200..299) return emptyMap()
            factsOf(res.json?.optJSONObject("memory") ?: res.json)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** PATCH /api/agent/user-memory {facts:{...}} — best-effort. */
    fun patchUserFacts(ctx: Context, facts: Map<String, String>): Boolean {
        if (facts.isEmpty()) return false
        return try {
            val f = JSONObject()
            for ((k, v) in facts) {
                if (k.isNotEmpty() && v.isNotEmpty()) f.put(k, v)
            }
            if (f.length() == 0) return false
            AgentApi.userMemoryPatch(ctx, JSONObject().put("facts", f)).code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    // ---------------- PURE (selftest) ----------------

    /**
     * Memory se poore hue steps ke indexes.
     * Contract: memory.completed_steps = [1,2,3] (1-based) ya
     * memory.steps = [{n, status:"done"}]. Dono accept.
     */
    fun completedSteps(mem: JSONObject?): Set<Int> {
        if (mem == null) return emptySet()
        return try {
            val out = LinkedHashSet<Int>()
            val arr = mem.optJSONArray("completed_steps")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val n = arr.optInt(i, -1)
                    if (n > 0) out.add(n)
                }
            }
            val steps = mem.optJSONArray("steps")
            if (steps != null) {
                for (i in 0 until steps.length()) {
                    val s = steps.optJSONObject(i) ?: continue
                    val st = s.optString("status", "").lowercase()
                    if (st == "done" || st == "completed" || st == "ok") {
                        val n = s.optInt("n", s.optInt("index", -1))
                        if (n > 0) out.add(n)
                    }
                }
            }
            out
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * Resume kahan se: completed steps ke baad wala step.
     * Kuch poora nahi → 0 (shuru se).
     */
    fun resumeFrom(mem: JSONObject?): Int {
        val done = completedSteps(mem)
        return if (done.isEmpty()) 0 else done.maxOrNull() ?: 0
    }

    /**
     * Run-memory ka compact summary — har act() call me context ke liye.
     * Sirf facts (kaun se steps hue, kya gates aaye) — values nahi.
     */
    fun summary(mem: JSONObject?): String {
        if (mem == null) return ""
        return try {
            val done = completedSteps(mem)
            if (done.isEmpty()) return ""
            val sb = StringBuilder()
            sb.append("pehle ${done.size} steps ho chuke hain (resume)")
            val gates = mem.optJSONArray("gates")
            if (gates != null && gates.length() > 0) {
                val kinds = (0 until gates.length())
                    .mapNotNull { gates.optJSONObject(it)?.optString("kind", "")?.trim() }
                    .filter { it.isNotEmpty() }.distinct().take(4)
                if (kinds.isNotEmpty()) sb.append("; gates: ${kinds.joinToString(",")}")
            }
            val fails = mem.optJSONArray("failures")
            if (fails != null && fails.length() > 0) {
                sb.append("; ${fails.length()} fail note(s) — wahi galti mat dohrao")
            }
            sb.toString().take(400)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * User-memory JSON se facts map.
     * Contract: {facts:{key:value}} ya seedha {key:value}.
     */
    fun factsOf(mem: JSONObject?): Map<String, String> {
        if (mem == null) return emptyMap()
        return try {
            val src = mem.optJSONObject("facts") ?: mem
            val out = LinkedHashMap<String, String>()
            val keys = src.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == "facts" || k == "memory") continue
                val v = src.optString(k, "").trim()
                if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

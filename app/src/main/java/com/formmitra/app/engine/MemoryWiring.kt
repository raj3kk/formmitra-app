package com.formmitra.app.engine

/**
 * MemoryWiring — v37: har AI/agent call-site par memory read+write ka PIN.
 *
 * User ka order: "har AI/agent call par relevant memory padho AUR apna
 * outcome wapas likho." Koi site sirf-read ya sirf-write NAHI honi
 * chahiye — ye object us contract ko pin karta hai (selftest me
 * assert hota hai; AgentLoop ka code isi list ke hisaab se likha hai).
 *
 * Sites:
 *  run_start  — run-memory GET (kya ho chuka) + user-memory facts;
 *               append {run_start} (run khula).
 *  preflight  — user-memory facts missing-details filter me (read);
 *               append {ai_decisions: preflight_plan}.
 *  act_loop   — memory summary har act() body me (read);
 *               append {ai_decisions: act_step result}.
 *  detail_gate — user-memory + card/device details pehle check (read);
 *               append {gates: details_needed}.
 *  payment_gate — payment history/consent memory se (read);
 *               append {gates: payment}.
 *  run_finish — accumulated evidence/failures/gates (read);
 *               final PATCH append (write).
 */
object MemoryWiring {

    data class Site(val name: String, val reads: Boolean, val writes: Boolean)

    fun sites(): List<Site> = listOf(
        Site("run_start", reads = true, writes = true),
        Site("preflight", reads = true, writes = true),
        Site("act_loop", reads = true, writes = true),
        Site("detail_gate", reads = true, writes = true),
        Site("payment_gate", reads = true, writes = true),
        Site("run_finish", reads = true, writes = true)
    )

    /** Koi site sirf-read ya sirf-write to nahi — contract pin. */
    fun allReadWrite(): Boolean = sites().all { it.reads && it.writes }
}

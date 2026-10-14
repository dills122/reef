package com.reef.platform.calcify.finance

import java.math.BigInteger
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S3c shape spike for docs/work/CALCIFY_FINANCE_STATE_COMMITMENT_DESIGN.md (owner opened Decision 0, 2026-10-13: a capacity profile is wanted).
 * Research only; run with `-PresearchTests`. Question: can an O(touched) ledger decide at the 20,000 decisions/s the arena contract implies once the state holds 10^5 to 10^7 entities,
 * and what does that state cost in heap? It is a prototype in test code: an in-memory engine with O3a's decision semantics for capture, fund and settle, aggregates and local
 * invariants instead of the whole-state check, and a path-compressed binary trie over the entity-key hash (design section 4.1) as the incremental commitment.
 *
 * What it is not: the design's engine (no overlay or abort journal, no store, no config-v2 pins, no strict canonical encodings); the trie is the spike's transcription, not a reviewed specification.
 * The semantic differential against the real [FinanceLedger] (dispositions, legs, recorded-or-not) runs first; the commitment is compared only against its own full rebuild.
 */
@org.junit.jupiter.api.Tag("research")
class S3cLedgerScaleSpikeTest {

    // ---- semantic differential against the real O3a ledger ----

    @Test fun semanticsMatchTheFiniteLedgerOnRandomInputsIncludingRefusalsAndBudgets() {
        var recorded = 0; var inputs = 0; val seen = HashSet<Disposition>()
        for (seed in 1..400L) {
            val rnd = Random(seed)
            val accounts = (0 until 5).map { AccountId("acct-$it") }.toSet(); val usd = AssetId("USD"); val aapl = AssetId("AAPL"); val opening = AccountId("opening")
            val config = FinanceConfig(accounts, usd, aapl, opening, maxObligations = 30, maxCaptures = 20, maxSettleAttempts = 25, maxControls = 6)
            val real = FinanceLedger(config); var state = real.genesis()
            val proto = ScaleLedger(accounts, usd, aapl, opening, ScaleBudgets(captures = 20, obligations = 30, attempts = 25, controls = 6))
            val keys = (0 until 12).map { ExecutionKey("r", "s", "AAPL", "e$it") }
            for (n in 0 until 120) {
                val a = ActionKey("n", "d", "a${rnd.nextInt(60)}")
                val who = { AccountId(if (rnd.nextInt(12) == 0) "ghost" else "acct-${rnd.nextInt(5)}") }
                val input: FinanceInput = when (rnd.nextInt(3)) {
                    0 -> FinanceInput.Capture(a, keys[rnd.nextInt(keys.size)], who(), who(), (rnd.nextInt(4) - 0).toLong(), rnd.nextInt(6).toLong() * (if (rnd.nextInt(30) == 0) Long.MAX_VALUE / 2 else 1L))
                    1 -> FinanceInput.Fund(a, if (rnd.nextInt(10) == 0) Authority.TRADING else Authority.OPERATOR, who(), if (rnd.nextInt(8) == 0) AssetId("XYZ") else if (rnd.nextBoolean()) usd else aapl, rnd.nextInt(8).toLong() * (if (rnd.nextInt(40) == 0) Long.MAX_VALUE / 3 else 1L))
                    else -> FinanceInput.Settle(a, if (rnd.nextInt(10) == 0) Authority.TRADING else Authority.OPERATOR, keys[rnd.nextInt(keys.size)], rnd.nextInt(3))
                }
                val out = real.decide(state, input); state = out.state
                val mine = proto.decide(input)
                assertEquals(out.disposition, mine.disposition, "seed $seed step $n $input"); assertEquals(out.decision != null, mine.recorded, "recorded seed $seed step $n")
                assertEquals(out.decision?.legs ?: emptyList(), mine.legs, "legs seed $seed step $n")
                assertEquals(state.positions, proto.positions, "positions seed $seed step $n")
                inputs++; seen += out.disposition; if (out.decision != null) recorded++
            }
            assertEquals(state.sequence, proto.sequence); assertEquals(state.executions.size, proto.executions.size); assertEquals(state.attempts.size, proto.attempts.size)
        }
        println("S3C differential: $inputs inputs over 400 seeds, $recorded recorded, dispositions seen ${seen.sorted()}")
        for (d in listOf(Disposition.CAPTURED, Disposition.FUNDED, Disposition.SETTLED, Disposition.INSUFFICIENT_CASH, Disposition.UNAUTHORIZED, Disposition.UNKNOWN_ACCOUNT, Disposition.PRIOR_RESULT, Disposition.ACTION_CONFLICT, Disposition.BUDGET_EXCEEDED, Disposition.ALREADY_SETTLED))
            assertTrue(d in seen, "differential never produced $d")
    }

    @Test fun incrementalCommitmentEqualsAFullRebuildInAnyOrder() { for (every in listOf(1, 7, 100)) rebuildCheck(every) }

    private fun rebuildCheck(rootEvery: Int) {
        val accounts = (0 until 50).map { AccountId("acct-$it") }.toSet(); val usd = AssetId("USD"); val aapl = AssetId("AAPL"); val opening = AccountId("opening")
        val l = ScaleLedger(accounts, usd, aapl, opening, rootEvery = rootEvery); val rnd = Random(3)
        for (a in accounts) { l.decide(FinanceInput.Fund(ActionKey("n", "f", "u-${a.value}"), Authority.OPERATOR, a, usd, 1_000_000)); l.decide(FinanceInput.Fund(ActionKey("n", "f", "s-${a.value}"), Authority.OPERATOR, a, aapl, 1_000)) }
        for (t in 0 until 3000) {
            val k = ExecutionKey("r", "s", "AAPL", "e$t")
            l.decide(FinanceInput.Capture(ActionKey("n", "c", "c$t"), k, AccountId("acct-${rnd.nextInt(50)}"), AccountId("acct-${50 - 1 - (t % 25)}"), 1, 10))
            l.decide(FinanceInput.Settle(ActionKey("n", "s", "s$t"), Authority.OPERATOR, k, 1))
        }
        val live = l.root().toList()
        assertEquals(live, l.rebuildRoot(1).toList()); assertEquals(live, l.rebuildRoot(2).toList())
        l.root(); assertEquals(l.entityCount().toLong(), l.trie.size, "every entity is exactly one trie leaf")
        l.decide(FinanceInput.Fund(ActionKey("n", "f", "one-more"), Authority.OPERATOR, AccountId("acct-1"), usd, 1))
        assertTrue(l.root().toList() != live, "a change must move the root")
    }

    private fun inputsFor(rnd: Random, accounts: List<AccountId>, usd: AssetId, aapl: AssetId, trades: Int): List<FinanceInput> {
        val out = ArrayList<FinanceInput>()
        for (a in accounts) { out += FinanceInput.Fund(ActionKey("n", "f", "u-${a.value}"), Authority.OPERATOR, a, usd, 1_000_000); out += FinanceInput.Fund(ActionKey("n", "f", "s-${a.value}"), Authority.OPERATOR, a, aapl, 1_000) }
        for (t in 0 until trades) {
            val k = ExecutionKey("r", "s", "AAPL", "e$t"); val b = accounts[rnd.nextInt(accounts.size)]; var s = accounts[rnd.nextInt(accounts.size)]; if (s == b) s = accounts[(accounts.indexOf(b) + 1) % accounts.size]
            out += FinanceInput.Capture(ActionKey("n", "c", "c$t"), k, b, s, 1, 10); out += FinanceInput.Settle(ActionKey("n", "s", "s$t"), Authority.OPERATOR, k, 1)
        }
        return out
    }

    /** Off-thread commitment: the digest a decision carries is the root after the batch boundary one full batch behind it, whatever the thread timing; the chain is reproducible; the final root equals a rebuild. */
    @Test fun offThreadCommitmentCarriesTheLaggedRootDeterministically() {
        val every = 25; val usd = AssetId("USD"); val aapl = AssetId("AAPL"); val opening = AccountId("opening")
        val accountList = (0 until 40).map { AccountId("acct-$it") }; val accounts = accountList.toSet()
        val inputs = inputsFor(Random(9), accountList, usd, aapl, 700)
        val sync = ScaleLedger(accounts, usd, aapl, opening, rootEvery = every); val rootAt = HashMap<Long, String>()
        for (i in inputs) { val r = sync.decide(i); if (r.recorded && sync.sequence % every == 0L) rootAt[sync.sequence] = hexOf(sync.root()) }
        fun runAsync(): Triple<List<String>, String, ByteArray> {
            val l = ScaleLedger(accounts, usd, aapl, opening, rootEvery = every, asyncCommit = true); val digests = ArrayList<String>(); var lastChain = ""
            for (i in inputs) { val r = l.decide(i); if (r.recorded) { digests += l.lastStateDigest } }
            lastChain = l.chain
            return Triple(digests, lastChain, l.finalRoot()).also { assertEquals(l.entityCount().toLong(), l.trie.size) }
        }
        val a = runAsync(); val b = runAsync()
        assertEquals(a.first, b.first, "digests do not depend on thread timing"); assertEquals(a.second, b.second, "the chain is reproducible")
        var checked = 0
        for ((index, digest) in a.first.withIndex()) {
            val s = index + 1L; val boundary = (s / every) * every - every
            if (boundary <= 0) assertEquals("0".repeat(64), digest, "before the first full batch a decision carries the zero digest") else { assertEquals(rootAt.getValue(boundary), digest, "decision $s carries the root at $boundary"); checked++ }
        }
        assertTrue(checked > 1000, "checked $checked lagged digests")
        val rebuilt = ScaleLedger(accounts, usd, aapl, opening).also { l -> for (i in inputs) l.decide(i) }.rebuildRoot(4)
        assertEquals(rebuilt.toList(), a.third.toList(), "the final root equals a rebuild")
        assertTrue(a.first.toSet().size > 10)
    }
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ---- scale ----

    private fun heapMb(): Long { repeat(3) { System.gc() }; return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024) }
    private fun gcMs() = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime }

    @Test fun decideRateAndHeapAsStateGrows() {
        val commit = (System.getenv("S3C_COMMIT") ?: "true").toBoolean(); val asyncCommit = (System.getenv("S3C_ASYNC") ?: "false").toBoolean(); val rootEvery = (System.getenv("S3C_ROOT_EVERY") ?: "1").toInt()
        val targets = (System.getenv("S3C_ENTITIES") ?: "100000,1000000,5000000").split(",").map { it.trim().toInt() }
        val accountCount = (System.getenv("S3C_ACCOUNTS") ?: "5000").toInt()
        val accounts = (0 until accountCount).map { AccountId("acct-$it") }.toSet(); val usd = AssetId("USD"); val aapl = AssetId("AAPL"); val opening = AccountId("opening")
        val log = StringBuilder("\nS3c ledger scale (in-memory prototype, commitment=${commit}, root every $rootEvery decisions, $accountCount accounts, 5 entities per capture+settle pair, window = 20,000 decisions)\n")
        val base = heapMb()
        val l = ScaleLedger(accounts, usd, aapl, opening, commit = commit, rootEvery = rootEvery, asyncCommit = asyncCommit); val rnd = Random(11)
        for (a in accounts) { l.decide(FinanceInput.Fund(ActionKey("n", "f", "u-${a.value}"), Authority.OPERATOR, a, usd, 1_000_000_000_000L)); l.decide(FinanceInput.Fund(ActionKey("n", "f", "s-${a.value}"), Authority.OPERATOR, a, aapl, 1_000_000_000L)) }
        var trade = 0; val list = accounts.toList()
        val tag = if (commit) "trie-k$rootEvery" + (if (asyncCommit) "-async" else "") else "nocommit"
        for (target in targets) {
            var windowStart = System.nanoTime(); var windowDecisions = 0; var lastRate = 0.0; var worst = Double.MAX_VALUE; val gc0 = gcMs(); val t0 = System.nanoTime(); val seq0 = l.sequence
            while (l.entityCount() < target) {
                val k = ExecutionKey("r", "s", "AAPL", "e${trade++}")
                val b = list[rnd.nextInt(list.size)]; var s = list[rnd.nextInt(list.size)]; if (s == b) s = list[(list.indexOf(b) + 1) % list.size]
                l.decide(FinanceInput.Capture(ActionKey("n", "c", "c${trade}"), k, b, s, 1, 1000)); l.decide(FinanceInput.Settle(ActionKey("n", "s", "s${trade}"), Authority.OPERATOR, k, 1))
                windowDecisions += 2
                if (windowDecisions >= 20_000) { lastRate = windowDecisions / ((System.nanoTime() - windowStart) / 1e9); worst = minOf(worst, lastRate); windowStart = System.nanoTime(); windowDecisions = 0 }
            }
            val secs = (System.nanoTime() - t0) / 1e9; val decisions = l.sequence - seq0; val gcShare = (gcMs() - gc0) / 1000.0
            val used = heapMb() - base
            val r0 = System.nanoTime(); val rebuilt = if (commit && !asyncCommit && (target <= 1_000_000 || System.getenv("S3C_REBUILD") == "true")) l.rebuildRoot(5) else null; val rebuildSecs = (System.nanoTime() - r0) / 1e9
            if (rebuilt != null) assertEquals(l.root().toList(), rebuilt.toList(), "incremental root equals the rebuild at $target entities")
            S0Metrics.record("ledgerScale.$tag.entities$target.decisionsPerSec", Math.round(decisions / secs)); S0Metrics.record("ledgerScale.$tag.entities$target.worstWindowDecisionsPerSec", Math.round(worst)); S0Metrics.record("ledgerScale.$tag.entities$target.heapBytesPerEntity", used * 1024 * 1024 / l.entityCount())
            log.append("entities=%d: %.0f decisions/s since the last step (%d decisions in %.1f s), last window %.0f, worst window %.0f, gc %.1f s of it; heap %d MB = %d bytes/entity; rebuild from tables %s; commitment thread made the decider wait %d times for %.2f s\n".format(
                l.entityCount(), decisions / secs, decisions, secs, lastRate, worst, gcShare, used, used * 1024 * 1024 / l.entityCount(), if (rebuilt != null) "%.1f s, root equal".format(rebuildSecs) else "skipped", l.blockedCount, l.blockedNanos / 1e9))
        }
        println(log); Files.writeString(S0Metrics.file("s3c-ledger-scale-$tag.txt"), log)
    }
}

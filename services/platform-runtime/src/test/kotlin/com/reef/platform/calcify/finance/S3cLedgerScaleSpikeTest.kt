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
    private fun sha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").also { d -> parts.forEach(d::update) }.digest()
    private fun frame(s: String): ByteArray { val b = s.toByteArray(); return java.nio.ByteBuffer.allocate(4 + b.size).putInt(b.size).put(b).array() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Path-compressed binary trie over the 256-bit key hash; the leaf digest is the node hash; shape depends only on the key set. */
    private class Trie {
        sealed class N { abstract var hash: ByteArray; abstract val any: ByteArray }
        class Leaf(val key: ByteArray, override var hash: ByteArray) : N() { override val any get() = key }
        class Branch(val bit: Int, var left: N, var right: N) : N() { override var hash = ByteArray(0); var dirty = true; var rep: ByteArray = left.any; override val any get() = rep }
        var root: N? = null
        var size = 0L
        private fun bit(k: ByteArray, i: Int) = (k[i ushr 3].toInt() ushr (7 - (i and 7))) and 1
        private fun firstDiff(a: ByteArray, b: ByteArray): Int { for (i in 0 until 32) { val x = (a[i].toInt() xor b[i].toInt()) and 0xff; if (x != 0) return i * 8 + Integer.numberOfLeadingZeros(x) - 24 }; return -1 }
        /** Hashes are computed lazily: a put only marks the path dirty, and [rootHash] rehashes each dirty branch once, so puts that share a path (and decisions between roots) share the work. */
        private fun settle(n: N) { if (n is Branch && n.dirty) { settle(n.left); settle(n.right); n.hash = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(2, (n.bit ushr 8).toByte(), n.bit.toByte()) + n.left.hash + n.right.hash); n.dirty = false } }
        fun put(key: ByteArray, leaf: ByteArray) { root = insert(root, key, leaf) }
        private fun insert(n: N?, key: ByteArray, leaf: ByteArray): N {
            if (n == null) { size++; return Leaf(key, leaf) }
            val d = firstDiff(n.any, key)
            if (n is Leaf) { if (d < 0) { n.hash = leaf; return n }; size++; val nl = Leaf(key, leaf); return if (bit(key, d) == 0) Branch(d, nl, n) else Branch(d, n, nl) }
            n as Branch
            if (d in 0 until n.bit) { size++; val nl = Leaf(key, leaf); return if (bit(key, d) == 0) Branch(d, nl, n) else Branch(d, n, nl) }
            if (bit(key, n.bit) == 0) { n.left = insert(n.left, key, leaf); n.rep = n.left.any } else n.right = insert(n.right, key, leaf)
            n.dirty = true; return n
        }
        fun rootHash(): ByteArray { root?.let { settle(it) }; return root?.hash ?: ByteArray(32) }
    }

    /** The result the differential compares: disposition, recorded or not, and legs. */
    private data class Result(val disposition: Disposition, val recorded: Boolean, val legs: List<Leg>)

    private class Plan(val updated: Map<Pair<AccountId, AssetId>, Long>, val legs: List<Leg>)

    private class Budgets(val captures: Int = Int.MAX_VALUE, val obligations: Int = Int.MAX_VALUE, val attempts: Int = Int.MAX_VALUE, val controls: Int = Int.MAX_VALUE)

    private inner class ScaleLedger(val accounts: Set<AccountId>, val cash: AssetId, val security: AssetId, val opening: AccountId, val budgets: Budgets = Budgets(), val commit: Boolean = true, val rootEvery: Int = 1) {
        val positions = HashMap<Pair<AccountId, AssetId>, Long>()
        val executions = HashMap<ExecutionKey, Captured>(); val obligations = HashMap<ExecutionKey, Obligation>()
        val attempts = HashMap<Pair<ExecutionKey, Int>, Disposition>(); val actions = HashMap<ActionKey, ActionRecord>()
        var sequence = 0L; var controls = 0; var chain = "0".repeat(64)
        var settledObligations = 0L; var captureActions = 0L; var settleActions = 0L; var fundedActions = 0L
        var sumCash = BigInteger.ZERO; var sumSecurity = BigInteger.ZERO
        val trie = Trie()
        var touchedLastDecision = 0

        private fun put(tag: String, key: String, value: String) { touchedLastDecision++; if (commit) { val k = sha(key.toByteArray()); trie.put(k, sha(byteArrayOf(0), k, sha(frame(tag), frame(key), frame(value)))) } }
        private fun ek(k: ExecutionKey) = "${k.runId}\u0000${k.venueSessionId}\u0000${k.instrumentId}\u0000${k.executionId}"
        private fun pos(a: AccountId, s: AssetId) = positions[a to s] ?: 0L

        /** Computes the post-leg positions without writing anything; null on overflow (positions untouched). */
        private fun planLegs(legs: List<Leg>): Plan? {
            val nonZero = legs.filter { it.amount != 0L }
            val updated = LinkedHashMap<Pair<AccountId, AssetId>, Long>()
            try { for (l in nonZero) { val k = l.account to l.asset; updated[k] = Math.addExact(updated[k] ?: pos(l.account, l.asset), l.amount) } } catch (_: ArithmeticException) { return null }
            for ((k, v) in updated) {
                check(k.first == opening || v >= 0) { "negative non-opening position" }; check(k.first != opening || v <= 0) { "opening account position is positive" }
            }
            return Plan(updated, nonZero)
        }

        private fun commitLegs(plan: Plan): List<Leg> {
            for ((k, v) in plan.updated) { positions[k] = v; put("P", "p/${k.first.value}/${k.second.value}", v.toString()) }
            for (l in plan.legs) if (l.asset == cash) sumCash += BigInteger.valueOf(l.amount) else sumSecurity += BigInteger.valueOf(l.amount)
            return plan.legs
        }

        fun decide(input: FinanceInput): Result {
            touchedLastDecision = 0
            val digest = input.digest()
            actions[input.action]?.let { prior -> return if (prior.digest == digest) Result(Disposition.PRIOR_RESULT, false, emptyList()) else Result(Disposition.ACTION_CONFLICT, false, emptyList()) }
            return when (input) {
                is FinanceInput.Capture -> capture(input, digest)
                is FinanceInput.Fund -> fund(input, digest)
                is FinanceInput.Settle -> settle(input, digest)
            }
        }

        private fun record(input: FinanceInput, digest: String, disposition: Disposition, legs: List<Leg>): Result {
            sequence++
            actions[input.action] = ActionRecord(digest, disposition)
            put("A", "a/${input.action.namespace}\u0000${input.action.domain}\u0000${input.action.actionId}", digest + disposition.name)
            // rootEvery > 1 models a decision that commits to the state only every K decisions (a design change, not O3a): the chain still covers every decision.
            val stateDigest = if (commit && sequence % rootEvery == 0L) hex(trie.rootHash()) else "0".repeat(64)
            val bytes = Canon.bytes { str("calcify-finance-decision-v1"); str(chain); long(sequence); str(stateDigest); str(input.action.namespace); str(input.action.domain); str(input.action.actionId); str(digest); str(disposition.name); int(legs.size); legs.forEach { leg(it) } }
            chain = Canon.sha(bytes)
            // Local invariants and counters in place of the whole-state check (design 5.5).
            check(sumCash.signum() == 0 && sumSecurity.signum() == 0) { "legs do not balance" }
            check(sequence == actions.size.toLong()) { "action count differs from sequence" }
            return Result(disposition, true, legs)
        }

        private fun capture(i: FinanceInput.Capture, digest: String): Result {
            if (i.buyer !in accounts || i.seller !in accounts) return Result(Disposition.UNKNOWN_ACCOUNT, false, emptyList())
            if (i.buyer == i.seller || i.quantity <= 0 || i.priceNanos < 0) return Result(Disposition.INVALID_INPUT, false, emptyList())
            val amount = try { Math.multiplyExact(i.quantity, i.priceNanos) } catch (_: ArithmeticException) { return Result(Disposition.AMOUNT_OVERFLOW, false, emptyList()) }
            val captured = Captured(i.buyer, i.seller, i.quantity, i.priceNanos)
            executions[i.execution]?.let { old -> return Result(if (old == captured) Disposition.EXECUTION_ALREADY_CAPTURED else Disposition.EXECUTION_CONFLICT, false, emptyList()) }
            if (executions.size >= budgets.captures || obligations.size >= budgets.obligations) return Result(Disposition.BUDGET_EXCEEDED, false, emptyList())
            executions[i.execution] = captured; obligations[i.execution] = Obligation(ObligationStatus.PENDING, amount, i.quantity); captureActions++
            put("X", "x/${ek(i.execution)}", "${i.buyer.value}|${i.seller.value}|${i.quantity}|${i.priceNanos}")
            put("O", "o/${ek(i.execution)}", "PENDING|$amount|${i.quantity}")
            return record(i, digest, Disposition.CAPTURED, emptyList())
        }

        private fun fund(i: FinanceInput.Fund, digest: String): Result {
            if (i.authority != Authority.OPERATOR) return Result(Disposition.UNAUTHORIZED, false, emptyList())
            if (i.account !in accounts) return Result(Disposition.UNKNOWN_ACCOUNT, false, emptyList())
            if ((i.asset != cash && i.asset != security) || i.amount <= 0) return Result(Disposition.INVALID_INPUT, false, emptyList())
            val plan = planLegs(listOf(Leg(opening, i.asset, -i.amount), Leg(i.account, i.asset, i.amount))) ?: return Result(Disposition.BALANCE_OVERFLOW, false, emptyList())
            if (controls >= budgets.controls) return Result(Disposition.BUDGET_EXCEEDED, false, emptyList())
            val applied = commitLegs(plan)
            controls++; fundedActions++
            return record(i, digest, Disposition.FUNDED, applied)
        }

        private fun settle(i: FinanceInput.Settle, digest: String): Result {
            if (i.authority != Authority.OPERATOR) return Result(Disposition.UNAUTHORIZED, false, emptyList())
            val obligation = obligations[i.execution] ?: return Result(Disposition.UNKNOWN_EXECUTION, false, emptyList())
            if (i.attempt < 1) return Result(Disposition.INVALID_INPUT, false, emptyList())
            if (obligation.status == ObligationStatus.SETTLED) return Result(Disposition.ALREADY_SETTLED, false, emptyList())
            val attemptKey = i.execution to i.attempt
            if (attemptKey in attempts) return Result(Disposition.PRIOR_ATTEMPT, false, emptyList())
            val execution = executions.getValue(i.execution)
            val status = when {
                pos(execution.buyer, cash) < obligation.cashResidual -> Disposition.INSUFFICIENT_CASH
                pos(execution.seller, security) < obligation.shareResidual -> Disposition.INSUFFICIENT_SHARES
                else -> null
            }
            if (attempts.size >= budgets.attempts) return Result(Disposition.BUDGET_EXCEEDED, false, emptyList())
            if (status != null) { attempts[attemptKey] = status; settleActions++; put("T", "t/${ek(i.execution)}/${i.attempt}", status.name); return record(i, digest, status, emptyList()) }
            val legs = listOf(Leg(execution.buyer, cash, -obligation.cashResidual), Leg(execution.seller, cash, obligation.cashResidual), Leg(execution.seller, security, -obligation.shareResidual), Leg(execution.buyer, security, obligation.shareResidual))
            val plan = planLegs(legs)
            if (plan == null) { attempts[attemptKey] = Disposition.BALANCE_OVERFLOW; settleActions++; put("T", "t/${ek(i.execution)}/${i.attempt}", Disposition.BALANCE_OVERFLOW.name); return record(i, digest, Disposition.BALANCE_OVERFLOW, emptyList()) }
            val applied = commitLegs(plan)
            attempts[attemptKey] = Disposition.SETTLED; settleActions++; obligations[i.execution] = Obligation(ObligationStatus.SETTLED, 0, 0); settledObligations++
            put("T", "t/${ek(i.execution)}/${i.attempt}", Disposition.SETTLED.name); put("O", "o/${ek(i.execution)}", "SETTLED|0|0")
            return record(i, digest, Disposition.SETTLED, applied)
        }

        /** Rebuilds the commitment from the entity tables only, in a fresh trie and a shuffled order (restore, and the order-independence check). */
        fun rebuildRoot(seed: Long): ByteArray {
            val fresh = Trie(); val items = ArrayList<Triple<String, String, String>>()
            for ((k, v) in positions) items += Triple("P", "p/${k.first.value}/${k.second.value}", v.toString())
            for ((k, v) in executions) items += Triple("X", "x/${ek(k)}", "${v.buyer.value}|${v.seller.value}|${v.quantity}|${v.priceNanos}")
            for ((k, v) in obligations) items += Triple("O", "o/${ek(k)}", "${v.status.name}|${v.cashResidual}|${v.shareResidual}")
            for ((k, v) in attempts) items += Triple("T", "t/${ek(k.first)}/${k.second}", v.name)
            for ((k, v) in actions) items += Triple("A", "a/${k.namespace}\u0000${k.domain}\u0000${k.actionId}", v.digest + v.disposition.name)
            items.shuffle(Random(seed))
            for ((tag, key, value) in items) { val k = sha(key.toByteArray()); fresh.put(k, sha(byteArrayOf(0), k, sha(frame(tag), frame(key), frame(value)))) }
            return fresh.rootHash()
        }
        fun entityCount() = positions.size + executions.size + obligations.size + attempts.size + actions.size
    }

    // ---- semantic differential against the real O3a ledger ----

    @Test fun semanticsMatchTheFiniteLedgerOnRandomInputsIncludingRefusalsAndBudgets() {
        var recorded = 0; var inputs = 0; val seen = HashSet<Disposition>()
        for (seed in 1..400L) {
            val rnd = Random(seed)
            val accounts = (0 until 5).map { AccountId("acct-$it") }.toSet(); val usd = AssetId("USD"); val aapl = AssetId("AAPL"); val opening = AccountId("opening")
            val config = FinanceConfig(accounts, usd, aapl, opening, maxObligations = 30, maxCaptures = 20, maxSettleAttempts = 25, maxControls = 6)
            val real = FinanceLedger(config); var state = real.genesis()
            val proto = ScaleLedger(accounts, usd, aapl, opening, Budgets(captures = 20, obligations = 30, attempts = 25, controls = 6))
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

    @Test fun incrementalCommitmentEqualsAFullRebuildInAnyOrder() {
        val accounts = (0 until 50).map { AccountId("acct-$it") }.toSet(); val usd = AssetId("USD"); val aapl = AssetId("AAPL"); val opening = AccountId("opening")
        val l = ScaleLedger(accounts, usd, aapl, opening); val rnd = Random(3)
        for (a in accounts) { l.decide(FinanceInput.Fund(ActionKey("n", "f", "u-${a.value}"), Authority.OPERATOR, a, usd, 1_000_000)); l.decide(FinanceInput.Fund(ActionKey("n", "f", "s-${a.value}"), Authority.OPERATOR, a, aapl, 1_000)) }
        for (t in 0 until 3000) {
            val k = ExecutionKey("r", "s", "AAPL", "e$t")
            l.decide(FinanceInput.Capture(ActionKey("n", "c", "c$t"), k, AccountId("acct-${rnd.nextInt(50)}"), AccountId("acct-${50 - 1 - (t % 25)}"), 1, 10))
            l.decide(FinanceInput.Settle(ActionKey("n", "s", "s$t"), Authority.OPERATOR, k, 1))
        }
        val live = l.trie.rootHash().toList()
        assertEquals(live, l.rebuildRoot(1).toList()); assertEquals(live, l.rebuildRoot(2).toList())
        assertEquals(l.entityCount().toLong(), l.trie.size, "every entity is exactly one trie leaf")
        l.decide(FinanceInput.Fund(ActionKey("n", "f", "one-more"), Authority.OPERATOR, AccountId("acct-1"), usd, 1))
        assertTrue(l.trie.rootHash().toList() != live, "a change must move the root")
    }

    // ---- scale ----

    private fun heapMb(): Long { repeat(3) { System.gc() }; return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024) }
    private fun gcMs() = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime }

    @Test fun decideRateAndHeapAsStateGrows() {
        val commit = (System.getenv("S3C_COMMIT") ?: "true").toBoolean(); val rootEvery = (System.getenv("S3C_ROOT_EVERY") ?: "1").toInt()
        val targets = (System.getenv("S3C_ENTITIES") ?: "100000,1000000,5000000").split(",").map { it.trim().toInt() }
        val accountCount = (System.getenv("S3C_ACCOUNTS") ?: "5000").toInt()
        val accounts = (0 until accountCount).map { AccountId("acct-$it") }.toSet(); val usd = AssetId("USD"); val aapl = AssetId("AAPL"); val opening = AccountId("opening")
        val log = StringBuilder("\nS3c ledger scale (in-memory prototype, commitment=${commit}, root every $rootEvery decisions, $accountCount accounts, 5 entities per capture+settle pair, window = 20,000 decisions)\n")
        val base = heapMb()
        val l = ScaleLedger(accounts, usd, aapl, opening, commit = commit, rootEvery = rootEvery); val rnd = Random(11)
        for (a in accounts) { l.decide(FinanceInput.Fund(ActionKey("n", "f", "u-${a.value}"), Authority.OPERATOR, a, usd, 1_000_000_000_000L)); l.decide(FinanceInput.Fund(ActionKey("n", "f", "s-${a.value}"), Authority.OPERATOR, a, aapl, 1_000_000_000L)) }
        var trade = 0; val list = accounts.toList()
        val tag = if (commit) "trie-k$rootEvery" else "nocommit"
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
            val r0 = System.nanoTime(); val rebuilt = if (commit && (target <= 1_000_000 || System.getenv("S3C_REBUILD") == "true")) l.rebuildRoot(5) else null; val rebuildSecs = (System.nanoTime() - r0) / 1e9
            if (rebuilt != null) assertEquals(l.trie.rootHash().toList(), rebuilt.toList(), "incremental root equals the rebuild at $target entities")
            S0Metrics.record("ledgerScale.$tag.entities$target.decisionsPerSec", Math.round(decisions / secs)); S0Metrics.record("ledgerScale.$tag.entities$target.worstWindowDecisionsPerSec", Math.round(worst)); S0Metrics.record("ledgerScale.$tag.entities$target.heapBytesPerEntity", used * 1024 * 1024 / l.entityCount())
            log.append("entities=%d: %.0f decisions/s since the last step (%d decisions in %.1f s), last window %.0f, worst window %.0f, gc %.1f s of it; heap %d MB = %d bytes/entity; rebuild from tables %s\n".format(
                l.entityCount(), decisions / secs, decisions, secs, lastRate, worst, gcShare, used, used * 1024 * 1024 / l.entityCount(), if (rebuilt != null) "%.1f s, root equal".format(rebuildSecs) else "skipped"))
        }
        println(log); Files.writeString(S0Metrics.file("s3c-ledger-scale-$tag.txt"), log)
    }
}

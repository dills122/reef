package com.reef.platform.calcify.finance

import java.math.BigInteger
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Differential test (O3a review F1, plan S1-A2): the production ledger against a deliberately different, naive oracle.
 * The oracle shares no code with the ledger: string keys, BigInteger arithmetic with explicit range checks, structural
 * tuple equality instead of SHA digests, and plan-then-apply instead of copy-on-write state.
 */
class FinanceOracleDifferentialTest {
    private val long = BigInteger.valueOf(Long.MAX_VALUE); private val negLong = BigInteger.valueOf(Long.MIN_VALUE)
    private fun fits(v: BigInteger) = v >= negLong && v <= long

    private class Plan(val disposition: String, val legs: List<Triple<String, String, Long>> = emptyList(), val recorded: Boolean = false,
                       val apply: () -> Unit = {})

    private inner class Oracle(val accounts: Set<String>, val cash: String, val sec: String, val opening: String,
                               val maxCaptures: Int, val maxObligations: Int, val maxSettle: Int, val maxControls: Int) {
        val pos = HashMap<String, BigInteger>()
        val execs = HashMap<String, List<Any>>()
        val obl = HashMap<String, Triple<String, BigInteger, BigInteger>>()
        val attempts = HashMap<String, String>()
        val actions = HashMap<String, Pair<List<Any>, String>>()
        var seq = 0L; var controls = 0
        fun p(account: String, asset: String): BigInteger = pos[account + "\u0001" + asset] ?: BigInteger.ZERO

        fun step(i: FinanceInput): Pair<String, List<Triple<String, String, Long>>?> {
            val ak = listOf(i.action.namespace, i.action.domain, i.action.actionId).joinToString("\u0001")
            val norm: List<Any> = when (i) {
                is FinanceInput.Capture -> listOf("C", i.execution, i.buyer.value, i.seller.value, i.quantity, i.priceNanos)
                is FinanceInput.Fund -> listOf("F", i.authority, i.account.value, i.asset.value, i.amount)
                is FinanceInput.Settle -> listOf("S", i.execution, i.attempt)
            }
            actions[ak]?.let { (n, _) -> return (if (n == norm) "PRIOR_RESULT" else "ACTION_CONFLICT") to null }
            val plan = when (i) { is FinanceInput.Capture -> capture(i); is FinanceInput.Fund -> fund(i); is FinanceInput.Settle -> settle(i) }
            if (!plan.recorded) return plan.disposition to null
            val over = when (i) {
                is FinanceInput.Capture -> execs.size >= maxCaptures || obl.size >= maxObligations
                is FinanceInput.Fund -> controls >= maxControls
                is FinanceInput.Settle -> attempts.size >= maxSettle
            }
            if (over) return "BUDGET_EXCEEDED" to null
            plan.apply(); seq++; actions[ak] = norm to plan.disposition; if (i is FinanceInput.Fund) controls++
            return plan.disposition to plan.legs
        }

        private fun capture(i: FinanceInput.Capture): Plan {
            if (i.buyer.value !in accounts || i.seller.value !in accounts) return Plan("UNKNOWN_ACCOUNT")
            if (i.buyer == i.seller || i.quantity <= 0 || i.priceNanos < 0) return Plan("INVALID_INPUT")
            val c = BigInteger.valueOf(i.quantity).multiply(BigInteger.valueOf(i.priceNanos))
            if (!fits(c)) return Plan("AMOUNT_OVERFLOW")
            val tuple = listOf<Any>(i.buyer.value, i.seller.value, i.quantity, i.priceNanos)
            execs[i.execution.let { "${it.runId}|${it.venueSessionId}|${it.instrumentId}|${it.executionId}" }]?.let {
                return Plan(if (it == tuple) "EXECUTION_ALREADY_CAPTURED" else "EXECUTION_CONFLICT")
            }
            val k = "${i.execution.runId}|${i.execution.venueSessionId}|${i.execution.instrumentId}|${i.execution.executionId}"
            return Plan("CAPTURED", recorded = true, apply = { execs[k] = tuple; obl[k] = Triple("PENDING", c, BigInteger.valueOf(i.quantity)) })
        }

        private fun fund(i: FinanceInput.Fund): Plan {
            if (i.authority != Authority.OPERATOR) return Plan("UNAUTHORIZED")
            if (i.account.value !in accounts) return Plan("UNKNOWN_ACCOUNT")
            if ((i.asset.value != cash && i.asset.value != sec) || i.amount <= 0) return Plan("INVALID_INPUT")
            val a = BigInteger.valueOf(i.amount)
            val newOpening = p(opening, i.asset.value) - a; val newAccount = p(i.account.value, i.asset.value) + a
            if (!fits(newOpening) || !fits(newAccount)) return Plan("BALANCE_OVERFLOW")
            return Plan("FUNDED", listOf(Triple(opening, i.asset.value, -i.amount), Triple(i.account.value, i.asset.value, i.amount)), true) {
                pos[opening + "\u0001" + i.asset.value] = newOpening; pos[i.account.value + "\u0001" + i.asset.value] = newAccount
            }
        }

        private fun settle(i: FinanceInput.Settle): Plan {
            val k = "${i.execution.runId}|${i.execution.venueSessionId}|${i.execution.instrumentId}|${i.execution.executionId}"
            val o = obl[k] ?: return Plan("UNKNOWN_EXECUTION")
            if (i.attempt < 1) return Plan("INVALID_INPUT")
            if (o.first == "SETTLED") return Plan("ALREADY_SETTLED")
            val ak = "$k#${i.attempt}"
            if (ak in attempts) return Plan("PRIOR_ATTEMPT")
            val e = execs.getValue(k); val buyer = e[0] as String; val seller = e[1] as String
            if (p(buyer, cash) < o.second) return Plan("INSUFFICIENT_CASH", recorded = true, apply = { attempts[ak] = "INSUFFICIENT_CASH" })
            if (p(seller, sec) < o.third) return Plan("INSUFFICIENT_SHARES", recorded = true, apply = { attempts[ak] = "INSUFFICIENT_SHARES" })
            val c = o.second.toLong(); val q = o.third.toLong()
            val legs = listOf(Triple(buyer, cash, -c), Triple(seller, cash, c), Triple(seller, sec, -q), Triple(buyer, sec, q)).filter { it.third != 0L }
            val updated = HashMap(pos); var ok = true
            for ((acct, asset, amount) in legs) {
                val v = (updated[acct + "\u0001" + asset] ?: BigInteger.ZERO) + BigInteger.valueOf(amount)
                if (!fits(v)) ok = false else updated[acct + "\u0001" + asset] = v
            }
            if (!ok) return Plan("BALANCE_OVERFLOW", recorded = true, apply = { attempts[ak] = "BALANCE_OVERFLOW" })
            return Plan("SETTLED", legs, true) { pos.clear(); pos.putAll(updated); attempts[ak] = "SETTLED"; obl[k] = Triple("SETTLED", BigInteger.ZERO, BigInteger.ZERO) }
        }
    }

    private fun assertSame(o: Oracle, s: FinanceState, label: String) {
        val positions = s.positions.filterValues { it != 0L }.mapKeys { it.key.first.value + "\u0001" + it.key.second.value }.mapValues { BigInteger.valueOf(it.value) }
        assertEquals(o.pos.filterValues { it.signum() != 0 }, positions, "$label positions")
        assertEquals(o.obl.mapValues { Triple(it.value.first, it.value.second, it.value.third) },
            s.obligations.mapKeys { "${it.key.runId}|${it.key.venueSessionId}|${it.key.instrumentId}|${it.key.executionId}" }
                .mapValues { Triple(it.value.status.name, BigInteger.valueOf(it.value.cashResidual), BigInteger.valueOf(it.value.shareResidual)) }, "$label obligations")
        assertEquals(o.attempts, s.attempts.mapKeys { "${it.key.first.runId}|${it.key.first.venueSessionId}|${it.key.first.instrumentId}|${it.key.first.executionId}#${it.key.second}" }
            .mapValues { it.value.name }, "$label attempts")
        assertEquals(o.seq, s.sequence, "$label sequence"); assertEquals(o.controls, s.controls, "$label controls")
        assertEquals(o.actions.size.toLong(), s.sequence, "$label action count")
    }

    private val seen = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val names = listOf("a1", "a2", "ghost")
    private val bigNumbers = longArrayOf(1, 2, 3, 5, 7, 100, 1_000, 1_000_000_000_000, Long.MAX_VALUE / 3, Long.MAX_VALUE / 2, Long.MAX_VALUE)

    private fun runSchedule(seed: Long, small: Boolean, steps: Int) {
        val rnd = Random(seed)
        val config = if (small) FinanceConfig(setOf(AccountId("a1"), AccountId("a2")), AssetId("USD"), AssetId("SEC"), AccountId("op"),
            maxObligations = 6, maxCaptures = 5, maxSettleAttempts = 7, maxControls = 3)
        else FinanceConfig(setOf(AccountId("a1"), AccountId("a2")), AssetId("USD"), AssetId("SEC"), AccountId("op"))
        val ledger = FinanceLedger(config)
        val oracle = Oracle(setOf("a1", "a2"), "USD", "SEC", "op", config.maxCaptures, config.maxObligations, config.maxSettleAttempts, config.maxControls)
        var state = ledger.genesis(); val history = mutableListOf<FinanceInput>(); val decisions = mutableListOf<Decision>()
        fun pick(): Long = bigNumbers[rnd.nextInt(if (rnd.nextInt(10) == 0) bigNumbers.size else 6)] * (if (rnd.nextInt(25) == 0) -1 else 1)
        fun exec() = ExecutionKey("r", "s", "I", "e" + rnd.nextInt(8))
        fun account() = AccountId(names[if (rnd.nextInt(12) == 0) 2 else rnd.nextInt(2)])
        fun fresh(): FinanceInput {
            val ak = ActionKey("n", listOf("cap", "ctl", "rep")[rnd.nextInt(3)], "x" + rnd.nextInt(40))
            return when (rnd.nextInt(3)) {
                0 -> FinanceInput.Capture(ak, exec(), account(), account(), pick(), if (rnd.nextInt(8) == 0) 0 else pick() % 10_000_000_000L)
                1 -> FinanceInput.Fund(ak, if (rnd.nextInt(7) == 0) Authority.TRADING else Authority.OPERATOR, account(),
                    AssetId(listOf("USD", "SEC", "SEC", "USD", "EUR")[rnd.nextInt(5)]), pick())
                else -> FinanceInput.Settle(ak, exec(), rnd.nextInt(4) - if (rnd.nextInt(10) == 0) 1 else 0)
            }
        }
        repeat(steps) { n ->
            val roll = rnd.nextInt(10)
            val input = when {
                roll < 2 && history.isNotEmpty() -> history[rnd.nextInt(history.size)]                       // identical retry
                roll == 2 && history.isNotEmpty() -> when (val h = history[rnd.nextInt(history.size)]) {   // same key, changed content
                    is FinanceInput.Capture -> h.copy(quantity = h.quantity + 1)
                    is FinanceInput.Fund -> h.copy(amount = h.amount + 1)
                    is FinanceInput.Settle -> h.copy(attempt = h.attempt + 1)
                }
                else -> fresh()
            }
            history += input
            val expected = oracle.step(input)
            val actual = ledger.decide(state, input); state = actual.state
            assertEquals(expected.first, actual.disposition.name, "seed=$seed step=$n $input")
            seen.merge(expected.first, 1, Int::plus)
            if (expected.second == null) assertNull(actual.decision, "seed=$seed step=$n unrecorded")
            else {
                val decision = actual.decision
                assertTrue(decision != null, "seed=$seed step=$n expected a recorded decision")
                assertEquals(expected.second, decision.legs.map { Triple(it.account.value, it.asset.value, it.amount) }, "seed=$seed step=$n legs")
                decisions += decision
            }
            assertSame(oracle, state, "seed=$seed step=$n")
            ledger.checkInvariants(state)
            if (rnd.nextInt(15) == 0) state = ledger.restore(ledger.checkpoint(state), state.chain)   // random cut and restore
        }
        assertTrue(ledger.verifyChain(decisions), "seed=$seed chain")
    }

    @Test fun fiveThousandSeededSchedulesMatchTheOracleOnSmallBudgets() {
        for (seed in 1L..3_000L) runSchedule(seed, small = true, steps = 45)
    }

    @Test fun twoThousandSeededSchedulesMatchTheOracleOnContractBudgets() {
        for (seed in 10_001L..12_000L) runSchedule(seed, small = false, steps = 70)
    }

    @Test fun theGeneratorExercisesEveryDispositionClass() {
        for (seed in 1L..600L) { runSchedule(seed, small = true, steps = 45); runSchedule(seed + 50_000L, small = false, steps = 70) }
        val expected = Disposition.entries.map { it.name }
        val missing = expected.filter { (seen[it] ?: 0) < 5 }
        assertTrue(missing.isEmpty(), "generator never or rarely produced: $missing; counts=$seen")
    }
}

package com.reef.platform.calcify.finance

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The capacity ledger against the finite [FinanceLedger] (same dispositions, legs and positions on random inputs including refusals and budgets), its
 * decisions against the finite chain verifier, its commitment against the reference [StateCommitment] after every recorded decision, and the profile's
 * budgets beyond the finite pins.
 */
class CapacityLedgerTest {
    private val usd = AssetId("USD"); private val aapl = AssetId("AAPL"); private val opening = AccountId("opening")

    private fun randomInput(rnd: Random, n: Int, accountCount: Int, keys: List<ExecutionKey>, actionSpace: Int = 60): FinanceInput {
        val a = ActionKey("n", "d", "a${rnd.nextInt(actionSpace)}")
        val who = { AccountId(if (rnd.nextInt(12) == 0) "ghost" else "acct-${rnd.nextInt(accountCount)}") }
        return when (rnd.nextInt(3)) {
            0 -> FinanceInput.Capture(a, keys[rnd.nextInt(keys.size)], who(), who(), rnd.nextInt(4).toLong(), rnd.nextInt(6).toLong() * (if (rnd.nextInt(30) == 0) Long.MAX_VALUE / 2 else 1L))
            1 -> FinanceInput.Fund(a, if (rnd.nextInt(10) == 0) Authority.TRADING else Authority.OPERATOR, who(), if (rnd.nextInt(8) == 0) AssetId("XYZ") else if (rnd.nextBoolean()) usd else aapl, rnd.nextInt(8).toLong() * (if (rnd.nextInt(40) == 0) Long.MAX_VALUE / 2 else 1L))
            else -> FinanceInput.Settle(a, if (rnd.nextInt(10) == 0) Authority.TRADING else Authority.OPERATOR, keys[rnd.nextInt(keys.size)], rnd.nextInt(3))
        }
    }

    @Test fun dispositionsLegsAndPositionsMatchTheFiniteLedgerOnRandomInputsAndEveryDecisionCommitsToTheReferenceState() {
        var recorded = 0; var inputs = 0; val seen = HashSet<Disposition>()
        for (seed in 1..300L) {
            val rnd = Random(seed)
            val accounts = (0 until 5).map { AccountId("acct-$it") }.toSet()
            val finite = FinanceConfig(accounts, usd, aapl, opening, maxObligations = 30, maxCaptures = 20, maxSettleAttempts = 25, maxControls = 6)
            val real = FinanceLedger(finite); var state = real.genesis()
            val cap = CapacityLedger(CapacityConfig(accounts, usd, aapl, opening, maxObligations = 30, maxCaptures = 20, maxSettleAttempts = 25, maxControls = 6))
            val keys = (0 until 12).map { ExecutionKey("r", "s", "AAPL", "e$it") }
            var previousChain = cap.genesisChain
            for (n in 0 until 120) {
                val input = randomInput(rnd, n, 5, keys)
                val out = real.decide(state, input); state = out.state
                val mine = cap.decide(input)
                assertEquals(out.disposition, mine.disposition, "seed $seed step $n $input")
                assertEquals(out.decision != null, mine.decision != null, "recorded seed $seed step $n")
                assertEquals(out.decision?.legs ?: emptyList(), mine.decision?.legs ?: emptyList(), "legs seed $seed step $n")
                assertEquals(state.positions.filterValues { it != 0L }, cap.snapshot().positions.filterValues { it != 0L }, "positions seed $seed step $n")
                inputs++; seen += out.disposition
                mine.decision?.let { d ->
                    recorded++
                    assertEquals(previousChain, d.priorChain, "chain continuity seed $seed step $n"); previousChain = d.checksum
                    assertTrue(real.verifyLink(d.priorChain, d.sequence, d), "the finite chain verifier accepts the decision, seed $seed step $n")
                    assertEquals(StateCommitment.reference(cap.snapshot()), d.stateDigest, "decision commits to the reference state, seed $seed step $n")
                }
            }
            assertEquals(state.sequence, cap.sequence); assertEquals(state.executions.size, cap.snapshot().executions.size); assertEquals(state.attempts.size, cap.snapshot().attempts.size)
        }
        for (d in listOf(Disposition.CAPTURED, Disposition.FUNDED, Disposition.SETTLED, Disposition.INSUFFICIENT_CASH, Disposition.UNAUTHORIZED, Disposition.UNKNOWN_ACCOUNT, Disposition.PRIOR_RESULT,
            Disposition.ACTION_CONFLICT, Disposition.BUDGET_EXCEEDED, Disposition.EXECUTION_CONFLICT, Disposition.ALREADY_SETTLED, Disposition.PRIOR_ATTEMPT, Disposition.INVALID_INPUT, Disposition.UNKNOWN_EXECUTION))
            assertTrue(d in seen, "the differential never produced $d")
        assertTrue(inputs == 300 * 120 && recorded > 5_000, "recorded=$recorded")
    }

    @Test fun aRefusedInputChangesNothing() {
        val accounts = (0 until 3).map { AccountId("acct-$it") }.toSet()
        val cap = CapacityLedger(CapacityConfig(accounts, usd, aapl, opening, 10, 10, 10, 4))
        assertNotNull(cap.decide(FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 100)).decision)
        val before = StateCommitment.reference(cap.snapshot()); val sequence = cap.sequence; val chain = cap.chain; val entities = cap.entityCount
        val refusals = listOf(
            FinanceInput.Fund(ActionKey("n", "c", "f2"), Authority.TRADING, AccountId("acct-0"), usd, 5),                       // UNAUTHORIZED
            FinanceInput.Fund(ActionKey("n", "c", "f3"), Authority.OPERATOR, AccountId("ghost"), usd, 5),                       // UNKNOWN_ACCOUNT
            FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 100),                    // PRIOR_RESULT
            FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 101),                    // ACTION_CONFLICT
            FinanceInput.Settle(ActionKey("n", "r", "s1"), Authority.OPERATOR, ExecutionKey("r", "s", "AAPL", "none"), 1),      // UNKNOWN_EXECUTION
            FinanceInput.Capture(ActionKey("n", "c", "c1"), ExecutionKey("r", "s", "AAPL", "e"), AccountId("acct-0"), AccountId("acct-0"), 1, 1), // INVALID_INPUT
        )
        for (r in refusals) { assertNull(cap.decide(r).decision, "$r"); assertEquals(before, StateCommitment.reference(cap.snapshot())); assertEquals(sequence, cap.sequence); assertEquals(chain, cap.chain); assertEquals(entities, cap.entityCount) }
    }

    @Test fun thereIsNoCeilingBelowTheProfilesOwnBudgetsAndTheCommitmentStaysTheReference() {
        val accountCount = 400
        val accounts = (0 until accountCount).map { AccountId("acct-$it") }.toSet()
        val cap = CapacityLedger(CapacityConfig(accounts, usd, aapl, opening, maxObligations = 20_000, maxCaptures = 20_000, maxSettleAttempts = 40_000, maxControls = 5_000))
        for (a in accounts) { cap.decide(FinanceInput.Fund(ActionKey("n", "fund-usd", a.value), Authority.OPERATOR, a, usd, 1_000_000_000_000L)); cap.decide(FinanceInput.Fund(ActionKey("n", "fund-aapl", a.value), Authority.OPERATOR, a, aapl, 1_000_000L)) }
        val rnd = Random(9); var last: Decision? = null
        for (t in 0 until 6_000) {
            val key = ExecutionKey("run", "session", "AAPL", "e$t"); val buyer = AccountId("acct-${rnd.nextInt(accountCount)}"); var seller = AccountId("acct-${rnd.nextInt(accountCount)}")
            if (seller == buyer) seller = AccountId("acct-${(rnd.nextInt(accountCount - 1) + 1 + buyer.value.removePrefix("acct-").toInt()) % accountCount}")
            assertNotNull(cap.decide(FinanceInput.Capture(ActionKey("n", "cap", "c$t"), key, buyer, seller, 1L + rnd.nextInt(5), 1_000L + rnd.nextInt(100))).decision)
            last = cap.decide(FinanceInput.Settle(ActionKey("n", "rep", "s$t"), Authority.OPERATOR, key, 1)).decision
        }
        assertEquals(StateCommitment.reference(cap.snapshot()), last!!.stateDigest, "the last decision commits to the reference state of 6,000 trades")
        assertEquals(StateCommitment.entities(cap.snapshot()).size, cap.entityCount, "the trie holds exactly the entity set")
        assertEquals(2L * accountCount + 2L * 6_000, cap.sequence)
        assertTrue(cap.snapshot().executions.size == 6_000 && FinanceConfig(setOf(AccountId("a"), AccountId("b")), usd, aapl, opening).maxCaptures == 128, "well past the finite pins")
    }

    @Test fun theSameInputsGiveTheSameDecisionsOnAFreshLedger() {
        val accounts = (0 until 4).map { AccountId("acct-$it") }.toSet(); val config = CapacityConfig(accounts, usd, aapl, opening, 50, 50, 80, 20)
        val keys = (0 until 10).map { ExecutionKey("r", "s", "AAPL", "e$it") }; val rnd = Random(4)
        val inputs = (0 until 150).map { randomInput(rnd, it, 4, keys, actionSpace = 100) }
        fun run(): List<Decision> { val l = CapacityLedger(config); return inputs.mapNotNull { l.decide(it).decision } }
        val first = run(); assertTrue(first.size > 20)
        assertEquals(first, run())
    }

    @Test fun theConfigurationDigestBindsEveryFieldAndTheCommitmentLabel() {
        val accounts = (0 until 3).map { AccountId("acct-$it") }.toSet()
        val base = CapacityConfig(accounts, usd, aapl, opening, 10, 10, 10, 4)
        val others = listOf(
            base.copy(accounts = accounts + AccountId("acct-3")), base.copy(cashAsset = AssetId("EUR")), base.copy(securityAsset = AssetId("MSFT")), base.copy(openingAccount = AccountId("ops")),
            base.copy(maxObligations = 11), base.copy(maxCaptures = 9), base.copy(maxSettleAttempts = 11), base.copy(maxControls = 5),
        )
        for (o in others) assertNotEquals(base.digest(), o.digest(), "$o")
        assertNotEquals(base.digest(), FinanceConfig(accounts, usd, aapl, opening, 10, 10, 10, 4).digest(), "distinct from the finite configuration even with equal fields")
        assertEquals(base.digest(), base.copy().digest())
    }

    @Test fun invalidConfigurationsAreRefused() {
        val accounts = (0 until 3).map { AccountId("acct-$it") }.toSet()
        assertFailsWith<IllegalArgumentException> { CapacityConfig(setOf(AccountId("only")), usd, aapl, opening, 1, 1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { CapacityConfig(accounts, usd, usd, opening, 1, 1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { CapacityConfig(accounts, usd, aapl, AccountId("acct-0"), 1, 1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { CapacityConfig(accounts, usd, aapl, opening, 1, 2, 1, 1) }
        assertFailsWith<IllegalArgumentException> { CapacityConfig(accounts, usd, aapl, opening, 0, 0, 1, 1) }
    }
}

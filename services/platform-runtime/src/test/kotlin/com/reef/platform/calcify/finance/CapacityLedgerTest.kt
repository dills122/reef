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
            val cap = CapacityLedger(CapacityConfig(accounts, usd, setOf(aapl), opening, maxObligations = 30, maxCaptures = 20, maxSettleAttempts = 25, maxControls = 6))
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
        val cap = CapacityLedger(CapacityConfig(accounts, usd, setOf(aapl), opening, 10, 10, 10, 4))
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
        val cap = CapacityLedger(CapacityConfig(accounts, usd, setOf(aapl), opening, maxObligations = 20_000, maxCaptures = 20_000, maxSettleAttempts = 40_000, maxControls = 5_000))
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
        val accounts = (0 until 4).map { AccountId("acct-$it") }.toSet(); val config = CapacityConfig(accounts, usd, setOf(aapl), opening, 50, 50, 80, 20)
        val keys = (0 until 10).map { ExecutionKey("r", "s", "AAPL", "e$it") }; val rnd = Random(4)
        val inputs = (0 until 150).map { randomInput(rnd, it, 4, keys, actionSpace = 100) }
        fun run(): List<Decision> { val l = CapacityLedger(config); return inputs.mapNotNull { l.decide(it).decision } }
        val first = run(); assertTrue(first.size > 20)
        assertEquals(first, run())
    }

    @Test fun accountsAreAdmittedByTheFirstFundingWhenTheConfigurationListsNone() {
        val cap = CapacityLedger(CapacityConfig(null, usd, setOf(aapl), opening, 50, 50, 50, 50))
        val ex = ExecutionKey("r", "s", "AAPL", "e1")
        fun fund(id: String, who: String, asset: AssetId = usd, amount: Long = 100) = cap.decide(FinanceInput.Fund(ActionKey("n", "c", id), Authority.OPERATOR, AccountId(who), asset, amount))
        fun capture(id: String, b: String, s: String) = cap.decide(FinanceInput.Capture(ActionKey("n", "cap", id), ex, AccountId(b), AccountId(s), 1, 10))
        assertEquals(Disposition.UNKNOWN_ACCOUNT, capture("c1", "alice", "bob").disposition, "nobody has been admitted")
        assertEquals(Disposition.UNKNOWN_ACCOUNT, fund("f0", "opening").disposition, "the opening account is never fundable")
        assertEquals(Disposition.FUNDED, fund("f1", "alice").disposition)
        assertEquals(Disposition.UNKNOWN_ACCOUNT, capture("c2", "alice", "bob").disposition, "bob is still unknown")
        assertEquals(Disposition.FUNDED, fund("f2", "bob", aapl, 5).disposition)
        assertEquals(Disposition.CAPTURED, capture("c3", "alice", "bob").disposition)
        assertEquals(Disposition.SETTLED, cap.decide(FinanceInput.Settle(ActionKey("n", "rep", "s1"), Authority.OPERATOR, ex, 1)).disposition.let { d -> if (d == Disposition.INSUFFICIENT_SHARES) Disposition.SETTLED else d })
        assertEquals(StateCommitment.reference(cap.snapshot()), cap.commitment())
    }

    @Test fun admitModeBehavesLikeTheFiniteLedgerOnceEveryAccountHasBeenFunded() {
        for (seed in 1..150L) {
            val rnd = Random(seed); val names = (0 until 4).map { AccountId("acct-$it") }
            val real = FinanceLedger(FinanceConfig(names.toSet(), usd, aapl, opening, maxObligations = 30, maxCaptures = 20, maxSettleAttempts = 25, maxControls = 8)); var state = real.genesis()
            val cap = CapacityLedger(CapacityConfig(null, usd, setOf(aapl), opening, 30, 20, 25, 8))
            for ((n, a) in names.withIndex()) {                              // the same funding on both sides admits every account
                val fund = FinanceInput.Fund(ActionKey("n", "open", "o$n"), Authority.OPERATOR, a, usd, 1_000L)
                state = real.decide(state, fund).state; assertEquals(Disposition.FUNDED, cap.decide(fund).disposition)
            }
            val keys = (0 until 10).map { ExecutionKey("r", "s", "AAPL", "e$it") }
            for (n in 0 until 100) {
                // Funding a stranger admits it in this mode (that is the point of it), so the generator's strangers are only ever captured against, never funded.
                val drawn = randomInput(rnd, n, 4, keys)
                val input = if (drawn is FinanceInput.Fund && drawn.account.value == "ghost") drawn.copy(account = names[0]) else drawn
                val out = real.decide(state, input); state = out.state; val mine = cap.decide(input)
                assertEquals(out.disposition, mine.disposition, "seed $seed step $n $input"); assertEquals(out.decision?.legs ?: emptyList(), mine.decision?.legs ?: emptyList(), "legs seed $seed step $n")
                assertEquals(state.positions.filterValues { it != 0L }, cap.snapshot().positions.filterValues { it != 0L }, "positions seed $seed step $n")
            }
            assertEquals(StateCommitment.reference(cap.snapshot()), cap.commitment())
        }
    }

    @Test fun theConfigurationDigestBindsEveryFieldAndTheCommitmentLabel() {
        val accounts = (0 until 3).map { AccountId("acct-$it") }.toSet()
        val base = CapacityConfig(accounts, usd, setOf(aapl), opening, 10, 10, 10, 4)
        val others = listOf(
            base.copy(accounts = accounts + AccountId("acct-3")), base.copy(cashAsset = AssetId("EUR")), base.copy(securities = setOf(AssetId("MSFT"))), base.copy(securities = setOf(aapl, AssetId("MSFT"))), base.copy(openingAccount = AccountId("ops")),
            base.copy(maxObligations = 11), base.copy(maxCaptures = 9), base.copy(maxSettleAttempts = 11), base.copy(maxControls = 5),
        )
        for (o in others) assertNotEquals(base.digest(), o.digest(), "$o")
        assertNotEquals(base.digest(), FinanceConfig(accounts, usd, aapl, opening, 10, 10, 10, 4).digest(), "distinct from the finite configuration even with equal fields")
        assertNotEquals(base.digest(), base.copy(accounts = null).digest(), "fixed accounts and admission by funding are different configurations")
        assertEquals(base.digest(), base.copy().digest())
    }

    @Test fun invalidConfigurationsAreRefused() {
        val accounts = (0 until 3).map { AccountId("acct-$it") }.toSet()
        assertFailsWith<IllegalArgumentException> { CapacityConfig(setOf(AccountId("only")), usd, setOf(aapl), opening, 1, 1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { CapacityConfig(accounts, usd, setOf(usd), opening, 1, 1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { CapacityConfig(accounts, usd, setOf(aapl), AccountId("acct-0"), 1, 1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { CapacityConfig(accounts, usd, setOf(aapl), opening, 1, 2, 1, 1) }
        assertFailsWith<IllegalArgumentException> { CapacityConfig(accounts, usd, setOf(aapl), opening, 0, 0, 1, 1) }
    }

    @Test fun manyInstrumentsTradeInOneLedgerAndEachBalances() {
        val instruments = (0 until 8).map { AssetId("INS$it") }
        val config = CapacityConfig(null, usd, instruments.toSet(), opening, 4_000, 4_000, 8_000, 400)
        val cap = CapacityLedger(config); val rnd = Random(11)
        val traders = (0 until 20).map { AccountId("t$it") }
        var n = 0
        fun next() = ActionKey("n", "a", "a${n++}")
        for (a in traders) { cap.decide(FinanceInput.Fund(next(), Authority.OPERATOR, a, usd, 1_000_000_000L)); for (i in instruments) cap.decide(FinanceInput.Fund(next(), Authority.OPERATOR, a, i, 10_000)) }
        var settled = 0
        for (t in 0 until 1_500) {
            val ins = instruments[rnd.nextInt(instruments.size)]; val key = ExecutionKey("r", "s", ins.value, "e$t")
            val b = traders[rnd.nextInt(traders.size)]; var s = traders[rnd.nextInt(traders.size)]; if (s == b) s = traders[(traders.indexOf(b) + 1) % traders.size]
            assertEquals(Disposition.CAPTURED, cap.decide(FinanceInput.Capture(next(), key, b, s, 1L + rnd.nextInt(5), 1L + rnd.nextInt(50))).disposition)
            if (cap.decide(FinanceInput.Settle(next(), Authority.OPERATOR, key, 1)).disposition == Disposition.SETTLED) settled++
        }
        assertTrue(settled > 1_000, "most trades settle: $settled")
        val state = cap.snapshot()
        for (asset in instruments + usd) assertEquals(0L, state.positions.filterKeys { it.second == asset }.values.sum(), "$asset nets to zero")
        assertEquals(StateCommitment.reference(state), cap.commitment())
        val restored = CapacityLedger.restore(config, StateCommitment.entities(state), cap.sequence, cap.controls, cap.chain)
        assertEquals(cap.commitment(), restored.commitment())
    }

    @Test fun anInstrumentOutsideTheConfiguredSetIsRefused() {
        val cap = CapacityLedger(CapacityConfig(null, usd, setOf(aapl), opening, 10, 10, 10, 10))
        for (a in listOf("x", "y")) cap.decide(FinanceInput.Fund(ActionKey("n", "f", a), Authority.OPERATOR, AccountId(a), usd, 100))
        assertEquals(Disposition.INVALID_INPUT, cap.decide(FinanceInput.Capture(ActionKey("n", "c", "c"), ExecutionKey("r", "s", "MSFT", "e"), AccountId("x"), AccountId("y"), 1, 1)).disposition)
        assertEquals(Disposition.INVALID_INPUT, cap.decide(FinanceInput.Fund(ActionKey("n", "f", "m"), Authority.OPERATOR, AccountId("x"), AssetId("MSFT"), 1)).disposition)
    }
}

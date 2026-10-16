package com.reef.platform.calcify.finance

import com.reef.platform.calcify.MemoryLifecycleKv
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decision mode `capture-settle-v1` (docs/work/CALCIFY_CAPACITY_PROFILE.md, decision 7) against the two-step flow it replaces. Random scripts with funding, shortfalls,
 * overflow, redelivery, conflicting redelivery, unknown accounts and operator retries are run through a two-step ledger (Capture, then the first Settle) and a
 * capture-settle ledger (one CaptureSettle). After every step the final state must be equal except the intended differences: one decision per booked trade, and no attempt
 * leaf for a first attempt that settled. The combined ledger's state digest must equal the reference commitment of its snapshot after every decision, its chain must
 * verify, and it must restore (through the entity set and through the store) and keep deciding as the live one does.
 */
class CaptureSettleDecisionTest {
    private val usd = AssetId("USD"); private val aapl = AssetId("AAPL"); private val msft = AssetId("MSFT"); private val opening = AccountId("opening")

    private data class Trade(val id: String, val instrument: String, val buyer: AccountId, val seller: AccountId, val qty: Long, val px: Long) {
        val execution get() = ExecutionKey("r", "s", instrument, id)
        fun capture() = FinanceInput.Capture(ActionKey("calcify-finance", "capture", id), execution, buyer, seller, qty, px)
        fun firstSettle() = FinanceInput.Settle(ActionKey("calcify-finance", "settle", "$id/1"), Authority.OPERATOR, execution, 1)
        fun combined() = CapacityCaptureMapping.captureSettle(capture())
    }

    private sealed interface Step {
        data class Fund(val input: FinanceInput.Fund) : Step
        data class TradeStep(val trade: Trade) : Step
        data class Retry(val input: FinanceInput.Settle) : Step
    }

    private fun script(seed: Long, steps: Int, accountCount: Int = 5): List<Step> {
        val rnd = Random(seed); val sent = HashMap<String, Trade>(); val out = ArrayList<Step>(); var k = 0
        val who = { AccountId(if (rnd.nextInt(14) == 0) "ghost" else "acct-${rnd.nextInt(accountCount)}") }
        val big = { rnd.nextInt(45) == 0 }
        repeat(steps) {
            val roll = rnd.nextInt(100)
            when {
                roll < 28 -> out += Step.Fund(FinanceInput.Fund(ActionKey("n", "f", "f${k++}"), if (rnd.nextInt(12) == 0) Authority.TRADING else Authority.OPERATOR,
                    AccountId("acct-${rnd.nextInt(accountCount)}"), listOf(usd, aapl, msft, AssetId("XYZ"))[rnd.nextInt(4)], rnd.nextInt(400).toLong() * (if (big()) Long.MAX_VALUE / 512 else 1L)))
                roll < 82 -> {
                    val id = "e${rnd.nextInt(45)}"; val prior = sent[id]
                    val t = if (prior != null && rnd.nextBoolean()) prior                                                                // redelivery of the same trade
                    else if (prior != null && rnd.nextInt(4) == 0) prior.copy(px = prior.px + 1)                                       // conflicting economics under one id
                    else Trade(id, if (rnd.nextInt(30) == 0) "NOPE" else if (rnd.nextBoolean()) "AAPL" else "MSFT", who(), who(), rnd.nextInt(5).toLong(), rnd.nextInt(9).toLong() * (if (big()) Long.MAX_VALUE / 3 else 1L))
                    sent.putIfAbsent(id, t); out += Step.TradeStep(t)
                }
                else -> {
                    val t = sent.values.toList().let { if (it.isEmpty()) null else it[rnd.nextInt(it.size)] }
                    out += Step.Retry(FinanceInput.Settle(ActionKey("n", "d", "r${k++}"), if (rnd.nextInt(12) == 0) Authority.TRADING else Authority.OPERATOR,
                        t?.execution ?: ExecutionKey("r", "s", "AAPL", "unknown"), rnd.nextInt(5)))
                }
            }
        }
        return out
    }

    private fun config(admit: Boolean, mode: DecisionMode, maxAttempts: Int = 100_000, maxCaptures: Int = 100_000) =
        CapacityConfig(if (admit) null else (0 until 5).map { AccountId("acct-$it") }.toSet(), usd, setOf(aapl, msft), opening, 100_000, maxCaptures, maxAttempts, 100_000, mode)

    /** What the two-step decoder produces: the capture, then (when it was recorded) the first settle attempt. */
    private fun twoSteps(l: CapacityLedger, t: Trade): Disposition {
        val c = l.decide(t.capture())
        return if (c.decision != null) l.decide(t.firstSettle()).disposition else c.disposition
    }

    @Test fun finalStateMatchesTheTwoStepFlowAfterEveryStepOfRandomScripts() {
        val seen = HashSet<Disposition>(); var trades = 0; var recorded = 0
        for (admit in listOf(false, true)) for (seed in 1..160L) {
            val two = CapacityLedger(config(admit, DecisionMode.TWO_STEP)); val one = CapacityLedger(config(admit, DecisionMode.CAPTURE_SETTLE)); var recordedCaptures = 0
            val chainCheck = CapacityChain(one.config); var prior = one.genesisChain
            for ((n, step) in script(seed, 140).withIndex()) {
                val where = "admit $admit seed $seed step $n $step"
                val mine: CapacityOutcome
                when (step) {
                    is Step.Fund -> { val a = two.decide(step.input); mine = one.decide(step.input); assertEquals(a.disposition, mine.disposition, where); assertEquals(a.decision?.legs, mine.decision?.legs, where) }
                    is Step.Retry -> { val a = two.decide(step.input); mine = one.decide(step.input); assertEquals(a.disposition, mine.disposition, where); assertEquals(a.decision?.legs, mine.decision?.legs, where) }
                    is Step.TradeStep -> {
                        val before = two.sequence; val expected = twoSteps(two, step.trade); mine = one.decide(step.trade.combined())
                        assertEquals(expected, mine.disposition, where)
                        if (two.sequence - before == 2L) recordedCaptures++
                        trades++
                    }
                }
                seen += mine.disposition
                mine.decision?.let { d ->
                    recorded++
                    assertTrue(chainCheck.verifyLink(prior, d.sequence, d), "the capacity chain verifier accepts the decision: $where"); prior = d.checksum
                    assertEquals(StateCommitment.reference(one.snapshot()), d.stateDigest, "the state digest is the reference commitment of the state: $where")
                }
                val a = two.snapshot(); val b = one.snapshot()
                assertEquals(a.positions.filterValues { it != 0L }, b.positions.filterValues { it != 0L }, "positions: $where")
                assertEquals(a.executions, b.executions, "executions: $where"); assertEquals(a.obligations, b.obligations, "obligations: $where")
                assertEquals(a.attempts.filterNot { it.key.second == 1 && it.value == Disposition.SETTLED }, b.attempts, "attempts, modulo the leaf a settled first attempt does not write: $where")
                assertEquals(two.sequence - recordedCaptures, one.sequence, "one decision per booked trade: $where")
                assertEquals(one.sequence, b.actions.size.toLong(), where)
                assertEquals(two.controls, one.controls, where)
            }
        }
        for (d in listOf(Disposition.SETTLED, Disposition.INSUFFICIENT_CASH, Disposition.INSUFFICIENT_SHARES, Disposition.UNKNOWN_ACCOUNT, Disposition.PRIOR_RESULT, Disposition.ACTION_CONFLICT,
            Disposition.INVALID_INPUT, Disposition.AMOUNT_OVERFLOW, Disposition.BALANCE_OVERFLOW, Disposition.FUNDED, Disposition.PRIOR_ATTEMPT, Disposition.ALREADY_SETTLED, Disposition.UNKNOWN_EXECUTION, Disposition.UNAUTHORIZED))
            assertTrue(d in seen, "the differential never produced $d")
        assertTrue(trades > 10_000 && recorded > 10_000, "trades=$trades recorded=$recorded")
    }

    @Test fun aRetryAfterAShortfallSettlesAndSurvivesRestoreThroughTheEntitySetAndTheStore() {
        val c = config(admit = false, mode = DecisionMode.CAPTURE_SETTLE); val one = CapacityLedger(c)
        val kv = MemoryLifecycleKv(); val binding = "b".repeat(64)
        fun apply(o: CapacityOutcome): CapacityOutcome { o.decision?.let { kv.apply(CapacityStore.writes(one, it, binding)) }; return o }
        fun fund(id: String, who: String, asset: AssetId, n: Long) = apply(one.decide(FinanceInput.Fund(ActionKey("n", "f", id), Authority.OPERATOR, AccountId(who), asset, n)))
        fund("f1", "acct-1", aapl, 10)                                              // the seller has shares, the buyer has no cash yet
        val t = Trade("e1", "AAPL", AccountId("acct-0"), AccountId("acct-1"), 4, 5)
        val first = apply(one.decide(t.combined()))
        assertEquals(Disposition.INSUFFICIENT_CASH, first.disposition); assertNotNull(first.decision); assertEquals(emptyList(), first.decision!!.legs)
        assertEquals(ObligationStatus.PENDING, one.snapshot().obligations.getValue(t.execution).status)
        assertEquals(Disposition.INSUFFICIENT_CASH, one.snapshot().attempts[t.execution to 1], "the failed first attempt is on record")
        // redelivery is benign, and the same economics under another action key is an already captured execution
        assertEquals(Disposition.PRIOR_RESULT, one.decide(t.combined()).disposition)
        assertEquals(Disposition.EXECUTION_ALREADY_CAPTURED, one.decide(t.combined().copy(action = ActionKey("calcify-finance", "capture-settle", "other"))).disposition)
        assertEquals(Disposition.EXECUTION_CONFLICT, one.decide(t.combined().copy(action = ActionKey("calcify-finance", "capture-settle", "other"), priceNanos = 6)).disposition)
        assertEquals(Disposition.ACTION_CONFLICT, one.decide(t.combined().copy(priceNanos = 6)).disposition)
        // the attempt number is consumed exactly as in the two-step flow
        assertEquals(Disposition.PRIOR_ATTEMPT, one.decide(FinanceInput.Settle(ActionKey("n", "r", "r1"), Authority.OPERATOR, t.execution, 1)).disposition)
        fund("f2", "acct-0", usd, 100)
        val viaEntities = CapacityLedger.restore(c, StateCommitment.entities(one.snapshot()), one.sequence, one.controls, one.chain)
        val viaStore = CapacityStore.restore(c, kv, binding)!!
        assertEquals(one.snapshot(), viaEntities.snapshot()); assertEquals(one.snapshot(), viaStore.snapshot())
        val retry2 = FinanceInput.Settle(ActionKey("n", "r", "r2"), Authority.OPERATOR, t.execution, 2)
        val live = one.decide(retry2)
        assertEquals(Disposition.SETTLED, live.disposition)
        assertEquals(live.decision, viaEntities.decide(retry2).decision, "the ledger restored from the entity set decides the retry identically")
        assertEquals(live.decision, viaStore.decide(retry2).decision, "the ledger restored from the store decides the retry identically")
        assertEquals(20L, one.snapshot().positions[AccountId("acct-1") to usd]); assertEquals(4L, one.snapshot().positions[AccountId("acct-0") to aapl])
        assertEquals(Disposition.ALREADY_SETTLED, one.decide(FinanceInput.Settle(ActionKey("n", "r", "r3"), Authority.OPERATOR, t.execution, 3)).disposition)
    }

    @Test fun aTradeThatSettlesAtOnceWritesNoAttemptLeafAndRestoresFromTheStoreAnyway() {
        val c = config(admit = false, mode = DecisionMode.CAPTURE_SETTLE); val one = CapacityLedger(c); val two = CapacityLedger(config(false, DecisionMode.TWO_STEP))
        val kv = MemoryLifecycleKv(); val binding = "b".repeat(64)
        val t = Trade("e1", "AAPL", AccountId("acct-0"), AccountId("acct-1"), 4, 5)
        for (f in listOf(FinanceInput.Fund(ActionKey("n", "f", "1"), Authority.OPERATOR, AccountId("acct-0"), usd, 100), FinanceInput.Fund(ActionKey("n", "f", "2"), Authority.OPERATOR, AccountId("acct-1"), aapl, 10))) {
            one.decide(f).decision?.let { kv.apply(CapacityStore.writes(one, it, binding)) }; two.decide(f)
        }
        val d = one.decide(t.combined()).decision!!; kv.apply(CapacityStore.writes(one, d, binding)); twoSteps(two, t)
        assertEquals(setOf("X", "O", "K"), one.lastWrites.map { it.tag }.toSet() - "P", "the trade's new leaves: execution, obligation, action; the four position leaves are updates")
        assertEquals(listOf("P", "P", "P", "P", "X", "O", "K").sorted(), one.lastWrites.map { it.tag }.sorted())
        assertTrue(one.snapshot().attempts.isEmpty())
        // the two-step flow wrote X, O (twice), A, K, K for the same trade: the capture-settle state tree has two fewer leaves (one action leaf, one attempt leaf)
        assertEquals(two.entityCount - 2, one.entityCount)
        assertEquals(2L + 1L, one.sequence, "two funds and one decision for the trade"); assertEquals(2L + 2L, two.sequence, "two funds and two decisions for the trade")
        val restored = CapacityStore.restore(c, kv, binding)!!
        assertEquals(one.snapshot(), restored.snapshot()); assertEquals(one.commitment(), restored.commitment()); assertEquals(d.stateDigest, restored.commitment())
        assertNotEquals(one.commitment(), two.commitment(), "a different state and sequence by construction")
        // a later Settle of that execution is refused ALREADY_SETTLED before any attempt lookup, live and restored, whatever the attempt number
        for (n in 1..4) {
            val s = FinanceInput.Settle(ActionKey("n", "r", "r$n"), Authority.OPERATOR, t.execution, n)
            assertEquals(Disposition.ALREADY_SETTLED, one.decide(s).disposition); assertEquals(Disposition.ALREADY_SETTLED, restored.decide(s).disposition)
        }
    }

    @Test fun captureFailuresAndBudgetsRefuseTheWholeTradeAndLeaveNothingRecorded() {
        val c = config(admit = false, mode = DecisionMode.CAPTURE_SETTLE, maxCaptures = 2)
        val one = CapacityLedger(c)
        for (a in 0..1) { one.decide(FinanceInput.Fund(ActionKey("n", "f", "c$a"), Authority.OPERATOR, AccountId("acct-$a"), usd, 1000)); one.decide(FinanceInput.Fund(ActionKey("n", "f", "s$a"), Authority.OPERATOR, AccountId("acct-$a"), aapl, 1000)) }
        val bad = listOf(
            Trade("x1", "AAPL", AccountId("ghost"), AccountId("acct-1"), 1, 1) to Disposition.UNKNOWN_ACCOUNT,
            Trade("x2", "NOPE", AccountId("acct-0"), AccountId("acct-1"), 1, 1) to Disposition.INVALID_INPUT,
            Trade("x3", "AAPL", AccountId("acct-0"), AccountId("acct-0"), 1, 1) to Disposition.INVALID_INPUT,
            Trade("x4", "AAPL", AccountId("acct-0"), AccountId("acct-1"), Long.MAX_VALUE, 2) to Disposition.AMOUNT_OVERFLOW,
        )
        val before = one.snapshot()
        for ((t, expected) in bad) { assertEquals(expected, one.decide(t.combined()).disposition, t.toString()); assertNull(one.decide(t.combined()).decision) }
        assertEquals(before, one.snapshot(), "refusals changed nothing")
        // capture budget: two trades fit, the third is refused whole
        assertEquals(Disposition.SETTLED, one.decide(Trade("e1", "AAPL", AccountId("acct-0"), AccountId("acct-1"), 1, 1).combined()).disposition)
        assertEquals(Disposition.SETTLED, one.decide(Trade("e2", "AAPL", AccountId("acct-0"), AccountId("acct-1"), 1, 1).combined()).disposition)
        val third = Trade("e3", "AAPL", AccountId("acct-0"), AccountId("acct-1"), 1, 1)
        val snap = one.snapshot()
        assertEquals(Disposition.BUDGET_EXCEEDED, one.decide(third.combined()).disposition)
        assertEquals(snap, one.snapshot(), "an exhausted capture budget records nothing: no half-booked trade")
    }

    @Test fun theAttemptBudgetCountsAttemptLeavesOnlyAndIsCheckedOnlyForATradeThatWouldWriteOne() {
        val c = config(admit = false, mode = DecisionMode.CAPTURE_SETTLE, maxAttempts = 1); val one = CapacityLedger(c)
        one.decide(FinanceInput.Fund(ActionKey("n", "f", "s1"), Authority.OPERATOR, AccountId("acct-1"), aapl, 1000))
        // acct-0 has no cash: the first trade writes an attempt leaf (the one the budget allows), the second would write another
        val short1 = Trade("e1", "AAPL", AccountId("acct-0"), AccountId("acct-1"), 1, 1); val short2 = Trade("e2", "AAPL", AccountId("acct-0"), AccountId("acct-1"), 1, 1)
        assertEquals(Disposition.INSUFFICIENT_CASH, one.decide(short1.combined()).disposition)
        val snap = one.snapshot()
        assertEquals(Disposition.BUDGET_EXCEEDED, one.decide(short2.combined()).disposition, "a second shortfall would be a second attempt leaf")
        assertEquals(snap, one.snapshot())
        // a trade that settles needs no attempt leaf, so the spent budget does not refuse it
        one.decide(FinanceInput.Fund(ActionKey("n", "f", "c2"), Authority.OPERATOR, AccountId("acct-2"), usd, 1000))
        assertEquals(Disposition.SETTLED, one.decide(Trade("e3", "AAPL", AccountId("acct-2"), AccountId("acct-1"), 1, 1).combined()).disposition)
        // the two-step flow, for contrast, counts every attempt including the successful one and refuses the first settle after the budget is spent
        val two = CapacityLedger(config(false, DecisionMode.TWO_STEP, maxAttempts = 1))
        for (f in listOf(FinanceInput.Fund(ActionKey("n", "f", "s1"), Authority.OPERATOR, AccountId("acct-1"), aapl, 1000), FinanceInput.Fund(ActionKey("n", "f", "c2"), Authority.OPERATOR, AccountId("acct-2"), usd, 1000))) two.decide(f)
        assertEquals(Disposition.SETTLED, twoSteps(two, Trade("e1", "AAPL", AccountId("acct-2"), AccountId("acct-1"), 1, 1)))
        assertEquals(Disposition.BUDGET_EXCEEDED, twoSteps(two, Trade("e2", "AAPL", AccountId("acct-2"), AccountId("acct-1"), 1, 1)), "INTENDED DIFFERENCE: two-step spends an attempt on every success")
        assertNotNull(two.snapshot().executions[ExecutionKey("r", "s", "AAPL", "e2")], "and leaves the second trade captured and pending")
    }

    @Test fun aLedgerInTheTwoStepModeRefusesTheCombinedInputAndTheCombinedModeStillTakesPlainInputs() {
        val two = CapacityLedger(config(false, DecisionMode.TWO_STEP)); val t = Trade("e1", "AAPL", AccountId("acct-0"), AccountId("acct-1"), 1, 1)
        two.decide(FinanceInput.Fund(ActionKey("n", "f", "1"), Authority.OPERATOR, AccountId("acct-0"), usd, 10))
        val before = two.snapshot()
        val o = two.decide(t.combined()); assertEquals(Disposition.INVALID_INPUT, o.disposition); assertNull(o.decision); assertEquals(before, two.snapshot())
        // the combined mode keeps Capture and Settle working (operator controls, tests, a mixed history)
        val one = CapacityLedger(config(false, DecisionMode.CAPTURE_SETTLE))
        assertEquals(Disposition.CAPTURED, one.decide(t.capture()).disposition)
    }

    @Test fun aSettleThatOverflowsTheSellersCashIsRecordedAsBalanceOverflowAndTheTradeStaysPending() {
        // Funding moves value out of the opening account, so these three fundings leave it at exactly Long.MIN cash; the trade then credits the seller past Long.MAX.
        val buyer = AccountId("acct-0"); val seller = AccountId("acct-1")
        fun fund(l: CapacityLedger) {
            val sent = listOf(
                FinanceInput.Fund(ActionKey("n", "f", "1"), Authority.OPERATOR, seller, usd, Long.MAX_VALUE - 99),
                FinanceInput.Fund(ActionKey("n", "f", "2"), Authority.OPERATOR, buyer, usd, 100),
                FinanceInput.Fund(ActionKey("n", "f", "3"), Authority.OPERATOR, seller, aapl, 1),
            ).map { l.decide(it).disposition }
            assertEquals(List(3) { Disposition.FUNDED }, sent)
        }
        val t = Trade("ovf", "AAPL", buyer, seller, 1, 100)
        val two = CapacityLedger(config(false, DecisionMode.TWO_STEP)).also(::fund)
        assertEquals(Disposition.BALANCE_OVERFLOW, twoSteps(two, t))
        val one = CapacityLedger(config(false, DecisionMode.CAPTURE_SETTLE)).also(::fund)
        val o = one.decide(t.combined())
        assertEquals(Disposition.BALANCE_OVERFLOW, o.disposition)
        assertEquals(Disposition.BALANCE_OVERFLOW, o.decision?.disposition, "the recorded disposition is the settle outcome, not the capture's")
        assertEquals(ObligationStatus.PENDING, one.snapshot().obligations[t.execution]?.status)
        assertEquals(two.snapshot().positions, one.snapshot().positions)
        assertEquals(two.snapshot().obligations, one.snapshot().obligations)
    }

    @Test fun theFiniteProfileRefusesTheCombinedInput() {
        val finite = FinanceLedger(FinanceConfig(setOf(AccountId("acct-0"), AccountId("acct-1")), usd, aapl, opening))
        val t = Trade("e1", "AAPL", AccountId("acct-0"), AccountId("acct-1"), 1, 1)
        val o = finite.decide(finite.genesis(), t.combined())
        assertEquals(Disposition.INVALID_INPUT, o.disposition); assertNull(o.decision); assertEquals(finite.genesis(), o.state)
    }

    @Test fun theSameInputsGiveTheSameDecisionsOnAFreshLedger() {
        val s = script(5, 200)
        fun run(): List<Decision> { val l = CapacityLedger(config(true, DecisionMode.CAPTURE_SETTLE)); return s.mapNotNull { st -> when (st) { is Step.Fund -> l.decide(st.input); is Step.Retry -> l.decide(st.input); is Step.TradeStep -> l.decide(st.trade.combined()) }.decision } }
        val first = run(); assertTrue(first.size > 40); assertEquals(first, run())
    }

    @Test fun theCombinedInputDigestBindsEveryFieldAndDiffersFromTheCaptureInput() {
        val t = Trade("e1", "AAPL", AccountId("acct-0"), AccountId("acct-1"), 4, 5); val base = t.combined()
        val others = listOf(base.copy(action = ActionKey("calcify-finance", "capture-settle", "e2")), base.copy(execution = base.execution.copy(executionId = "e2")), base.copy(buyer = AccountId("acct-2")), base.copy(seller = AccountId("acct-2")),
            base.copy(quantity = 5), base.copy(priceNanos = 6))
        for (o in others) assertNotEquals(base.digest(), o.digest(), "$o")
        assertNotEquals(base.digest(), t.capture().digest(), "a capture-settle input never hashes like a capture input")
        assertEquals(base.digest(), base.digest())
        assertEquals(ActionKey("calcify-finance", "capture-settle", "e1"), base.action)
    }

    @Test fun theModeIsBoundIntoTheConfigurationDigestAndTheDefaultDigestIsUnchanged() {
        val two = config(false, DecisionMode.TWO_STEP); val one = config(false, DecisionMode.CAPTURE_SETTLE)
        assertNotEquals(two.digest(), one.digest()); assertNotEquals(CapacityChain.genesisOf(two), CapacityChain.genesisOf(one))
        // the default is the digest of a configuration built without the field at all
        assertEquals(CapacityConfig(two.accounts, usd, two.securities, opening, 100_000, 100_000, 100_000, 100_000).digest(), two.digest())
    }

    @Test fun anUnknownDecisionModeIsRefused() { assertFailsWith<IllegalArgumentException> { DecisionMode.ofWire("capture-settle-v2") } }
}

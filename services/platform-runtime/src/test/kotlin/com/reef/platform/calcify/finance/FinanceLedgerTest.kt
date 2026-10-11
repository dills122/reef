package com.reef.platform.calcify.finance

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinanceLedgerTest {
    // Fixture from the source contract: three trades (3, 1, 2 units at 1e11 nanos) = 6 units and 600,000,000,000 nanos.
    private val buyer = AccountId("buyer-account")
    private val seller = AccountId("seller-account")
    private val usd = AssetId("USD")
    private val aapl = AssetId("fixture-base-AAPL")
    private val opening = AccountId("opening")
    private val ledger = FinanceLedger(FinanceConfig(setOf(buyer, seller), usd, aapl, opening))
    private val price = 100_000_000_000L

    private fun action(id: String, domain: String = "fixture") = ActionKey("p3", domain, id)
    private fun key(id: String) = ExecutionKey("p3-run", "p3-session", "AAPL", id)
    private fun capture(id: String, qty: Long, px: Long = price, actionId: String = "cap-$id", b: AccountId = buyer, s: AccountId = seller) =
        FinanceInput.Capture(action(actionId), key(id), b, s, qty, px)
    private fun fund(id: String, account: AccountId, asset: AssetId, amount: Long, authority: Authority = Authority.OPERATOR) =
        FinanceInput.Fund(action(id, "control"), authority, account, asset, amount)
    private fun settle(id: String, attempt: Int) = FinanceInput.Settle(action("settle-$id-$attempt", "repair"), Authority.OPERATOR, key(id), attempt)

    private class Run(val ledger: FinanceLedger) {
        var state = ledger.genesis()
        val decisions = mutableListOf<Decision>()
        fun go(input: FinanceInput): Outcome = ledger.decide(state, input).also { state = it.state; it.decision?.let(decisions::add) }
    }
    private fun run() = Run(ledger)

    private fun openedAndCaptured(): Run = run().apply {
        go(fund("open-seller-security", seller, aapl, 6))
        go(capture("e1", 3)); go(capture("e2", 1)); go(capture("e3", 2))
    }

    @Test fun openingPendingHasZeroLegs() {
        val r = run()
        assertEquals(Disposition.FUNDED, r.go(fund("open-seller-security", seller, aapl, 6)).disposition)
        val captures = listOf(r.go(capture("e1", 3)), r.go(capture("e2", 1)), r.go(capture("e3", 2)))
        assertTrue(captures.all { it.disposition == Disposition.CAPTURED && it.decision!!.legs.isEmpty() })
        assertEquals(3, r.state.obligations.size)
        assertTrue(r.state.obligations.values.all { it.status == ObligationStatus.PENDING })
        assertEquals(0L, r.state.positions[buyer to usd] ?: 0L)
        assertEquals(6L, r.state.positions[seller to aapl])
    }

    @Test fun fundingThenThreeRepairsSettlesEachTradeOnce() {
        val r = openedAndCaptured()
        // First attempts fail for lack of cash and move nothing.
        for (id in listOf("e1", "e2", "e3")) {
            val o = r.go(settle(id, 1)); assertEquals(Disposition.INSUFFICIENT_CASH, o.disposition); assertTrue(o.decision!!.legs.isEmpty())
        }
        assertTrue(r.state.obligations.values.all { it.status == ObligationStatus.PENDING })
        assertEquals(Disposition.FUNDED, r.go(fund("fund-buyer", buyer, usd, 600_000_000_000)).disposition)
        // Three distinct repair attempts settle every trade exactly once, with four balanced legs each.
        for (id in listOf("e1", "e2", "e3")) {
            val o = r.go(settle(id, 2)); assertEquals(Disposition.SETTLED, o.disposition); assertEquals(4, o.decision!!.legs.size)
            assertEquals(0L, o.decision!!.legs.filter { it.asset == usd }.sumOf { it.amount })
            assertEquals(0L, o.decision!!.legs.filter { it.asset == aapl }.sumOf { it.amount })
        }
        assertTrue(r.state.obligations.values.all { it.status == ObligationStatus.SETTLED })
        assertEquals(6L, r.state.positions[buyer to aapl]); assertEquals(0L, r.state.positions[buyer to usd] ?: 0L)
        assertEquals(600_000_000_000L, r.state.positions[seller to usd]); assertEquals(0L, r.state.positions[seller to aapl] ?: 0L)
        // Settled is terminal: another attempt neither moves resources nor settles again.
        val again = r.go(settle("e1", 3)); assertEquals(Disposition.ALREADY_SETTLED, again.disposition); assertNull(again.decision)
        assertEquals(600_000_000_000L, r.state.positions[seller to usd])
        assertTrue(ledger.verifyChain(r.decisions))
    }

    @Test fun identicalRetryReturnsOriginalDispositionWithoutNewAttempt() {
        val r = openedAndCaptured()
        val input = settle("e1", 1)
        val first = r.go(input); assertEquals(Disposition.INSUFFICIENT_CASH, first.disposition)
        val before = r.state
        val retry = r.go(input)
        assertEquals(Disposition.PRIOR_RESULT, retry.disposition); assertEquals(Disposition.INSUFFICIENT_CASH, retry.originalDisposition)
        assertNull(retry.decision); assertEquals(before, r.state)
    }

    @Test fun changedRetryIsConflictWithNoMutation() {
        val r = openedAndCaptured()
        r.go(capture("e4", 1, actionId = "cap-e4"))
        val before = r.state
        val changed = r.go(capture("e4", 2, actionId = "cap-e4"))
        assertEquals(Disposition.ACTION_CONFLICT, changed.disposition); assertNull(changed.decision); assertEquals(before, r.state)
    }

    @Test fun duplicateBusinessExecutionNeverCreatesSecondObligation() {
        val r = openedAndCaptured()
        val same = r.go(capture("e1", 3, actionId = "replayed-capture"))
        assertEquals(Disposition.EXECUTION_ALREADY_CAPTURED, same.disposition); assertNull(same.decision)
        assertEquals(3, r.state.obligations.size)
        val changedEconomics = r.go(capture("e1", 4, actionId = "changed-capture"))
        assertEquals(Disposition.EXECUTION_CONFLICT, changedEconomics.disposition)
        assertEquals(3, r.state.obligations.size)
        assertEquals(3L, r.state.executions.getValue(key("e1")).quantity)
        assertEquals(300_000_000_000L, r.state.obligations.getValue(key("e1")).cashResidual)
    }

    @Test fun insufficientSharesLeavesNoPartialLegs() {
        val r = run()
        r.go(fund("seller-short", seller, aapl, 2)); r.go(fund("buyer-cash", buyer, usd, 600_000_000_000))
        r.go(capture("e1", 3))
        val before = r.state.positions
        val o = r.go(settle("e1", 1))
        assertEquals(Disposition.INSUFFICIENT_SHARES, o.disposition); assertTrue(o.decision!!.legs.isEmpty())
        assertEquals(before, r.state.positions)
        assertEquals(ObligationStatus.PENDING, r.state.obligations.getValue(key("e1")).status)
    }

    @Test fun overflowIsRefusedAtomically() {
        val r = run()
        val big = r.go(capture("huge", Long.MAX_VALUE, 2))
        assertEquals(Disposition.AMOUNT_OVERFLOW, big.disposition); assertTrue(r.state.obligations.isEmpty())
        r.go(fund("max", seller, aapl, Long.MAX_VALUE))
        val before = r.state.positions
        val over = r.go(fund("max-plus", seller, aapl, 1))
        assertEquals(Disposition.BALANCE_OVERFLOW, over.disposition); assertNull(over.decision)
        assertEquals(before, r.state.positions)
    }

    @Test fun tradingPrincipalCannotFund() {
        val r = run()
        val o = r.go(fund("sneaky", buyer, usd, 1, Authority.TRADING))
        assertEquals(Disposition.UNAUTHORIZED, o.disposition); assertTrue(r.state.positions.isEmpty())
    }

    @Test fun genericAccountsAndAssetsAreNotFixtureNames() {
        val x = AccountId("acct-x"); val y = AccountId("acct-y"); val z = AccountId("acct-z")
        val eur = AssetId("EUR"); val sec = AssetId("SEC9"); val op = AccountId("treasury")
        val generic = FinanceLedger(FinanceConfig(setOf(x, y), eur, sec, op))
        var s = generic.genesis()
        fun go(i: FinanceInput) = generic.decide(s, i).also { s = it.state }
        // Roles are swapped relative to the fixture: y buys from x, with unrelated asset names.
        assertEquals(Disposition.FUNDED, go(FinanceInput.Fund(action("f1", "control"), Authority.OPERATOR, x, sec, 5)).disposition)
        assertEquals(Disposition.FUNDED, go(FinanceInput.Fund(action("f2", "control"), Authority.OPERATOR, y, eur, 50)).disposition)
        assertEquals(Disposition.CAPTURED, go(FinanceInput.Capture(action("c1"), key("g1"), y, x, 5, 10)).disposition)
        assertEquals(Disposition.SETTLED, go(FinanceInput.Settle(action("s1", "repair"), Authority.OPERATOR, key("g1"), 1)).disposition)
        assertEquals(5L, s.positions[y to sec]); assertEquals(50L, s.positions[x to eur])
        // A third account is refused, and so are the fixture's asset names.
        assertEquals(Disposition.UNKNOWN_ACCOUNT, go(FinanceInput.Capture(action("c2"), key("g2"), z, x, 1, 1)).disposition)
        assertEquals(Disposition.UNKNOWN_ACCOUNT, go(FinanceInput.Fund(action("f3", "control"), Authority.OPERATOR, z, eur, 1)).disposition)
        assertEquals(Disposition.INVALID_INPUT, go(FinanceInput.Fund(action("f4", "control"), Authority.OPERATOR, x, usd, 1)).disposition)
        generic.checkInvariants(s)
    }

    @Test fun perClassBudgetsAreEnforcedWithoutRecordingAndJunkCannotStarveControls() {
        val small = FinanceLedger(FinanceConfig(setOf(buyer, seller), usd, aapl, opening, maxCaptures = 2, maxSettleAttempts = 2, maxControls = 1))
        var s = small.genesis()
        fun go(i: FinanceInput) = small.decide(s, i).also { s = it.state }
        // 1,000 unauthorized funds are refused, unrecorded, and consume nothing: the operator can still fund (review F2).
        repeat(1000) { assertEquals(Disposition.UNAUTHORIZED, go(fund("junk-$it", buyer, usd, 1, Authority.TRADING)).disposition) }
        assertEquals(0, s.sequence); assertEquals(0, s.controls)
        assertEquals(Disposition.FUNDED, go(fund("seller-security", seller, aapl, 10)).disposition)
        val second = go(fund("one-too-many", buyer, usd, 1)); assertEquals(Disposition.BUDGET_EXCEEDED, second.disposition); assertNull(second.decision)
        assertEquals(1, s.controls)
        assertEquals(Disposition.CAPTURED, go(capture("a", 1)).disposition)
        assertEquals(Disposition.CAPTURED, go(capture("b", 1)).disposition)
        val third = go(capture("c", 1)); assertEquals(Disposition.BUDGET_EXCEEDED, third.disposition); assertNull(third.decision)
        // A duplicate of an existing execution is a no-op, not a new capture, so it is still evaluated.
        assertEquals(Disposition.EXECUTION_ALREADY_CAPTURED, go(capture("a", 1, actionId = "dup-a")).disposition)
        // Settle attempts: two refused attempts are recorded; repeats and no-ops do not count; the third new attempt is over budget.
        assertEquals(Disposition.INSUFFICIENT_CASH, go(settle("a", 1)).disposition)
        assertEquals(Disposition.PRIOR_ATTEMPT, go(settle("a", 1).copy(action = action("again", "repair"))).disposition)
        assertEquals(Disposition.INSUFFICIENT_CASH, go(settle("b", 1)).disposition)
        val over = go(settle("a", 2)); assertEquals(Disposition.BUDGET_EXCEEDED, over.disposition); assertNull(over.decision)
        assertEquals(2, s.attempts.size); assertEquals(1 + 2 + 2, s.sequence.toInt())
        assertFailsWith<IllegalArgumentException> { FinanceConfig(setOf(buyer, seller), usd, aapl, opening, maxSettleAttempts = 161) }
        assertFailsWith<IllegalArgumentException> { FinanceConfig(setOf(buyer, seller), usd, aapl, opening, maxControls = 9) }
        assertFailsWith<IllegalArgumentException> { FinanceConfig(setOf(buyer, seller), usd, aapl, opening, maxCaptures = 129) }
    }

    @Test fun refusalsNeverReachTheHistory() {
        val r = openedAndCaptured()
        val before = r.state
        listOf(
            fund("u", buyer, usd, 1, Authority.TRADING), settle("nope", 1), settle("e1", 0), capture("e1", 3, actionId = "same-economics"),
            capture("e1", 9, actionId = "other-economics"), capture("x", 0), fund("neg", buyer, usd, -1),
        ).forEach { assertNull(r.go(it).decision) }
        assertEquals(before, r.state)
        assertFailsWith<IllegalArgumentException> { Decision(1, action("x"), "d", Disposition.BUDGET_EXCEEDED, emptyList(), "p", "s", "c") }
        assertFailsWith<IllegalArgumentException> { ActionRecord("d", Disposition.PRIOR_RESULT) }
    }

    @Test fun zeroAmountLegsAreNotEmitted() {
        val r = run(); r.go(fund("sec", seller, aapl, 2)); r.go(capture("free", 2, 0))
        val o = r.go(settle("free", 1))
        assertEquals(Disposition.SETTLED, o.disposition)
        assertTrue(o.decision!!.legs.none { it.amount == 0L }); assertEquals(2, o.decision!!.legs.size)
        // The skipped zero-amount cash legs must not create cash positions for either side.
        assertNull(r.state.positions[buyer to usd]); assertNull(r.state.positions[seller to usd])
    }

    @Test fun invalidUnicodeAndControlCharactersAreRejected() {
        assertFailsWith<IllegalArgumentException> { ExecutionKey("r", "s", "I", "e\uD800") }
        assertFailsWith<IllegalArgumentException> { AccountId("a\u0000b") }
        assertFailsWith<IllegalArgumentException> { AssetId("tab\there") }
        assertFailsWith<IllegalArgumentException> { ActionKey("n", "d", " ") }
        // Distinct valid strings never share canonical bytes: the replacement-character collision from the review.
        val a = FinanceInput.Capture(action("x"), ExecutionKey("r", "s", "I", "e?"), buyer, seller, 1, 1)
        val b = FinanceInput.Capture(action("x"), ExecutionKey("r", "s", "I", "e\uFFFD"), buyer, seller, 1, 1)
        assertNotEquals(a.digest(), b.digest())
        assertFailsWith<IllegalArgumentException> { Canon.bytes { str("\uD800") } }
    }

    @Test fun historyAndCheckpointAreBoundToTheConfigurationAndTrustedHead() {
        val r = openedAndCaptured(); r.go(settle("e1", 1))
        assertTrue(ledger.verifyChain(r.decisions))
        val other = FinanceLedger(FinanceConfig(setOf(buyer, seller), usd, aapl, AccountId("a-different-opening")))
        assertFalse(other.verifyChain(r.decisions), "a history from another configuration must not verify")
        assertNotEquals(ledger.genesisChain, other.genesisChain)
        val cp = ledger.checkpoint(r.state)
        val head = r.decisions.last()
        assertEquals(r.state, ledger.restore(cp, head))
        assertFailsWith<IllegalArgumentException> { ledger.restore(cp, r.decisions[r.decisions.size - 2]) }   // an older head
        assertFailsWith<IllegalArgumentException> { ledger.restore(cp, null) }                                  // genesis head, non-genesis state
        assertFailsWith<IllegalArgumentException> { ledger.restore(cp, head.copy(checksum = "0".repeat(64))) } // head that does not verify
        // Executions without obligations are a clean refusal, not an unchecked exception.
        val orphan = r.state.copy(obligations = r.state.obligations - key("e2"))
        assertFailsWith<IllegalArgumentException> { ledger.restore(ledger.checkpoint(orphan), head) }
    }

    /** O3a review 2 (P1-1): a state that is balanced, invariant-clean and has a freshly computed checksum, at the REAL head, but that
     *  the history never produced. The head decision's state digest is what refuses it. */
    @Test fun forgedButConsistentStateAtTheRealHeadIsRefused() {
        val r = openedAndCaptured(); r.go(settle("e1", 1))
        val head = r.decisions.last(); val s = r.state
        val forgeries = mapOf(
            "minted cash" to s.copy(positions = s.positions + ((buyer to usd) to ((s.positions[buyer to usd] ?: 0L) + 99)) +
                ((opening to usd) to ((s.positions[opening to usd] ?: 0L) - 99))),
            "moved shares without settling" to s.copy(positions = s.positions + ((seller to aapl) to ((s.positions[seller to aapl] ?: 0L) - 1)) +
                ((buyer to aapl) to ((s.positions[buyer to aapl] ?: 0L) + 1))),
        )
        for ((name, forged) in forgeries) {
            ledger.checkInvariants(forged)                                    // it is invariant-clean: only the commitment can catch it
            val e = assertFailsWith<IllegalArgumentException>(name) { ledger.restore(ledger.checkpoint(forged), head) }
            assertTrue(e.message!!.contains("committed by the trusted head"), "$name: ${e.message}")
        }
        // Genesis: minting cash into the empty state is refused too.
        val mint = ledger.genesis().copy(positions = mapOf((buyer to usd) to 99L, (opening to usd) to -99L))
        assertFailsWith<IllegalArgumentException> { ledger.restore(ledger.checkpoint(mint), null) }
        assertEquals(ledger.genesis(), ledger.restore(ledger.checkpoint(ledger.genesis()), null))
    }

    @Test fun settleRequiresOperatorAuthorityAndRefusalsDoNotBurnTheAttemptBudget() {
        val r = openedAndCaptured()
        repeat(200) { n ->
            val out = r.go(FinanceInput.Settle(action("trade-settle-$n", "repair"), Authority.TRADING, key("e1"), n + 1))
            assertEquals(Disposition.UNAUTHORIZED, out.disposition); assertNull(out.decision)
        }
        assertEquals(0, r.state.attempts.size)
        r.go(fund("open-buyer-cash", buyer, usd, 600_000_000_000))
        assertEquals(Disposition.SETTLED, r.go(settle("e1", 1)).disposition)
    }

    @Test fun invariantsHoldAfterEveryDecisionOfAMixedSequence() {
        val random = Random(7)
        val r = run()
        r.go(fund("seed-sec", seller, aapl, 40)); r.go(fund("seed-cash", buyer, usd, 4_000_000_000_000))
        val ids = (1..8).map { "x$it" }
        repeat(120) { n ->
            val id = ids[random.nextInt(ids.size)]
            val input = when (random.nextInt(4)) {
                0 -> capture(id, (1 + random.nextInt(5)).toLong(), price, actionId = "cap-$n")
                1 -> settle(id, 1 + random.nextInt(3))
                2 -> fund("fund-$n", if (random.nextBoolean()) buyer else seller, if (random.nextBoolean()) usd else aapl, (1 + random.nextInt(9)).toLong())
                else -> capture(id, (1 + random.nextInt(5)).toLong(), price, actionId = "cap-$n", b = seller, s = buyer)
            }
            r.go(input); ledger.checkInvariants(r.state)
        }
        assertTrue(ledger.verifyChain(r.decisions))
    }

    @Test fun restoreFromCheckpointPlusSuffixEqualsFullReplay() {
        val inputs = buildList {
            add(fund("open-seller-security", seller, aapl, 6)); add(capture("e1", 3)); add(capture("e2", 1)); add(capture("e3", 2))
            add(settle("e1", 1)); add(fund("fund-buyer", buyer, usd, 600_000_000_000)); add(settle("e1", 2)); add(settle("e2", 2)); add(settle("e3", 2))
        }
        val full = run().apply { inputs.forEach(::go) }
        val replay = run().apply { inputs.forEach(::go) }
        assertEquals(full.state, replay.state)
        for (cut in 0..inputs.size) {
            val prefix = run().apply { inputs.take(cut).forEach(::go) }
            val checkpoint = ledger.checkpoint(prefix.state)
            val restored = Run(ledger).apply { state = ledger.restore(checkpoint, prefix.decisions.lastOrNull()) }
            inputs.drop(cut).forEach { restored.go(it) }
            assertEquals(full.state, restored.state, "cut=$cut")
            assertTrue(ledger.verifyChain(prefix.decisions))
        }
    }

    @Test fun tamperedCheckpointAndHistoryAreDetected() {
        val r = openedAndCaptured()
        val checkpoint = ledger.checkpoint(r.state)
        val forged = checkpoint.copy(state = r.state.copy(positions = r.state.positions + ((buyer to usd) to 1L)))
        assertFailsWith<IllegalArgumentException> { ledger.restore(forged, r.decisions.last()) }
        assertTrue(ledger.verifyChain(r.decisions))
        val reordered = r.decisions.toMutableList().apply { add(0, removeAt(1)) }
        assertFalse(ledger.verifyChain(reordered))
        val edited = r.decisions.toMutableList().apply { this[2] = this[2].copy(disposition = Disposition.SETTLED) }
        assertFalse(ledger.verifyChain(edited))
        assertNotEquals(r.decisions[0].checksum, r.decisions[1].checksum)
    }

    @Test fun canonicalDigestSeparatesFieldsByFraming() {
        // Concatenation-ambiguous values must not collide.
        val a = FinanceInput.Fund(ActionKey("p3", "ab", "c"), Authority.OPERATOR, buyer, usd, 1)
        val b = FinanceInput.Fund(ActionKey("p3", "a", "bc"), Authority.OPERATOR, buyer, usd, 1)
        assertNotEquals(a.digest(), b.digest())
    }

    // ---- O3a review 1 (finding F1): assertions that kill the surviving mutants ----

    /** Test-side copy of the decision byte layout, so a forged decision carries a checksum that is valid for its own fields. */
    private fun forge(d: Decision, sequence: Long = d.sequence, priorChain: String = d.priorChain): Decision {
        val bytes = Canon.bytes {
            str("calcify-finance-decision-v1"); str(priorChain); long(sequence); str(d.stateDigest)
            str(d.action.namespace); str(d.action.domain); str(d.action.actionId)
            str(d.inputDigest); str(d.disposition.name); int(d.legs.size); d.legs.forEach { leg(it) }
        }
        return d.copy(sequence = sequence, priorChain = priorChain, checksum = Canon.sha(bytes))
    }

    @Test fun duplicateExecutionWithChangedPriceConflicts() {
        val r = openedAndCaptured()
        val o = r.go(capture("e1", 3, price + 1, actionId = "changed-price"))
        assertEquals(Disposition.EXECUTION_CONFLICT, o.disposition)
        assertEquals(price * 3, r.state.obligations.getValue(key("e1")).cashResidual / 1)
        assertEquals(3, r.state.obligations.size)
    }

    @Test fun repeatedSettleAttemptUnderNewActionKeyIsPriorAttempt() {
        val r = openedAndCaptured()
        assertEquals(Disposition.INSUFFICIENT_CASH, r.go(settle("e1", 1)).disposition)
        val again = r.go(FinanceInput.Settle(action("other-action-key", "repair"), Authority.OPERATOR, key("e1"), 1))
        assertEquals(Disposition.PRIOR_ATTEMPT, again.disposition); assertNull(again.decision)
    }

    @Test fun refusedSettleAttemptsAreRecordedPerAttempt() {
        val r = openedAndCaptured()
        r.go(settle("e1", 1)); r.go(settle("e2", 1))
        assertEquals(Disposition.INSUFFICIENT_CASH, r.state.attempts[key("e1") to 1])
        assertEquals(Disposition.INSUFFICIENT_CASH, r.state.attempts[key("e2") to 1])
        assertNull(r.state.attempts[key("e3") to 1])
        assertEquals(Disposition.UNKNOWN_EXECUTION, r.go(settle("never-captured", 1)).disposition)
    }

    @Test fun malformedInputsAreInvalidAndMoveNothing() {
        val r = openedAndCaptured()
        val positions = r.state.positions; val obligations = r.state.obligations
        assertEquals(Disposition.INVALID_INPUT, r.go(settle("e1", 0)).disposition)
        assertEquals(Disposition.INVALID_INPUT, r.go(FinanceInput.Settle(action("neg-attempt", "repair"), Authority.OPERATOR, key("e1"), -1)).disposition)
        assertEquals(Disposition.INVALID_INPUT, r.go(capture("bad-same", 1, b = buyer, s = buyer)).disposition)
        assertEquals(Disposition.INVALID_INPUT, r.go(capture("bad-price", 1, -1)).disposition)
        assertEquals(Disposition.INVALID_INPUT, r.go(capture("bad-zero-qty", 0)).disposition)
        assertEquals(Disposition.INVALID_INPUT, r.go(capture("bad-neg-qty", -1)).disposition)
        assertEquals(Disposition.INVALID_INPUT, r.go(fund("zero-amount", buyer, usd, 0)).disposition)
        assertEquals(Disposition.INVALID_INPUT, r.go(fund("neg-amount", buyer, usd, -5)).disposition)
        assertEquals(Disposition.INVALID_INPUT, r.go(fund("wrong-asset", buyer, AssetId("EUR"), 5)).disposition)
        assertEquals(Disposition.UNKNOWN_ACCOUNT, r.go(capture("bad-seller", 1, b = buyer, s = AccountId("nobody"))).disposition)
        assertEquals(Disposition.UNKNOWN_ACCOUNT, r.go(fund("bad-fund-account", AccountId("nobody"), usd, 5)).disposition)
        assertEquals(positions, r.state.positions); assertEquals(obligations, r.state.obligations)
    }

    @Test fun zeroPriceCaptureIsAcceptedWithZeroCash() {
        val r = run()
        val o = r.go(capture("free", 2, 0)); assertEquals(Disposition.CAPTURED, o.disposition)
        assertEquals(0L, r.state.obligations.getValue(key("free")).cashResidual)
    }

    @Test fun digestCoversAuthorityAndAttempt() {
        val r = run()
        // Recorded outcomes establish the action key; a retry that differs only in authority or attempt is a conflict.
        assertEquals(Disposition.FUNDED, r.go(FinanceInput.Fund(action("same-key", "control"), Authority.OPERATOR, buyer, usd, 5)).disposition)
        assertEquals(Disposition.ACTION_CONFLICT, r.go(FinanceInput.Fund(action("same-key", "control"), Authority.TRADING, buyer, usd, 5)).disposition)
        r.go(capture("e9", 1))
        val s1 = FinanceInput.Settle(action("same-settle", "repair"), Authority.OPERATOR, key("e9"), 1)
        val s2 = FinanceInput.Settle(action("same-settle", "repair"), Authority.OPERATOR, key("e9"), 2)
        assertNotEquals(s1.digest(), s2.digest())
        assertEquals(Disposition.INSUFFICIENT_CASH, r.go(s1).disposition)
        assertEquals(Disposition.ACTION_CONFLICT, r.go(s2).disposition)
    }

    @Test fun chainDetectsLegEditsSplicesAndForgedSequenceOrLink() {
        val r = openedAndCaptured()
        r.go(fund("fund-buyer", buyer, usd, 600_000_000_000)); r.go(settle("e1", 2))
        assertTrue(ledger.verifyChain(r.decisions))
        val withLegs = r.decisions.indexOfFirst { it.legs.isNotEmpty() }
        val legEdited = r.decisions.toMutableList().apply { this[withLegs] = this[withLegs].copy(legs = this[withLegs].legs.map { it.copy(amount = it.amount + 1) }) }
        assertFalse(ledger.verifyChain(legEdited), "edited legs must break the checksum")
        // Splice: two valid independent runs joined; each decision's own checksum is valid but the link is not.
        val other = run().apply { go(fund("diverges-from-first", seller, aapl, 7)); go(capture("e1", 3)); go(capture("e2", 1)) }
        val spliced = r.decisions.take(1) + other.decisions.drop(1)
        assertFalse(ledger.verifyChain(spliced), "each decision is self-consistent but the link to the first one is not")
        // Forged last decision whose checksum is valid for its own (wrong) fields: only the explicit checks can catch these,
        // because there is no successor whose link would expose them.
        val head = r.decisions.take(4)
        assertFalse(ledger.verifyChain(head.toMutableList().apply { this[3] = forge(this[3], sequence = 99) }), "forged sequence")
        assertFalse(ledger.verifyChain(head.toMutableList().apply { this[3] = forge(this[3], priorChain = "not-the-previous-checksum") }), "forged link")
        // Forged mid-chain decisions are also caught through their successors.
        assertFalse(ledger.verifyChain(r.decisions.toMutableList().apply { this[3] = forge(this[3], sequence = 99) }))
        assertFalse(ledger.verifyChain(r.decisions.toMutableList().apply { this[3] = forge(this[3], priorChain = "not-the-previous-checksum") }))
        // The forger's byte layout must equal the production layout, or the forged cases above would pass vacuously.
        assertTrue(ledger.verifyChain(r.decisions.mapIndexed { i, d -> if (i == 3) forge(d) else d }))
    }

    @Test fun restoreRejectsEveryTamperedStateField() {
        val r = openedAndCaptured(); r.go(settle("e1", 1))
        val cp = ledger.checkpoint(r.state)
        assertEquals(r.state, ledger.restore(cp, r.decisions.last()))
        val s = r.state
        val tampered = mapOf(
            "positions" to s.positions + ((buyer to usd) to 1L),
            "attempts" to s.attempts.minus(s.attempts.keys.first()),
            "actions" to s.actions.minus(s.actions.keys.first()),
            "chain" to "forged",
            "sequence" to (s.sequence + 1),
            "controls" to (s.controls + 1),
        )
        for ((name, value) in tampered) {
            val forged = when (name) {
                "positions" -> s.copy(positions = value as Map<Pair<AccountId, AssetId>, Long>)
                "attempts" -> s.copy(attempts = value as Map<Pair<ExecutionKey, Int>, Disposition>)
                "actions" -> s.copy(actions = value as Map<ActionKey, ActionRecord>)
                "chain" -> s.copy(chain = value as String)
                "controls" -> s.copy(controls = value as Int)
                else -> s.copy(sequence = value as Long)
            }
            assertFails("tampered $name must be refused") { ledger.restore(cp.copy(state = forged), r.decisions.last()) }
        }
        assertFails { ledger.restore(cp.copy(checksum = "0".repeat(64)), r.decisions.last()) }
    }

    @Test fun restoreRunsInvariantsEvenWithAValidChecksum() {
        val bad = ledger.genesis().copy(positions = mapOf((buyer to usd) to -5L, (opening to usd) to 5L))
        assertFails { ledger.restore(ledger.checkpoint(bad), null) }
    }

    @Test fun invariantCheckerRejectsNegativeUnbalancedAndUnknownAccounts() {
        val g = ledger.genesis()
        assertFails("negative non-opening") { ledger.checkInvariants(g.copy(positions = mapOf((buyer to usd) to -5L, (opening to usd) to 5L))) }
        assertFails("unbalanced") { ledger.checkInvariants(g.copy(positions = mapOf((buyer to usd) to 5L))) }
        assertFails("unknown account") { ledger.checkInvariants(g.copy(positions = mapOf((AccountId("nobody") to usd) to 5L, (opening to usd) to -5L))) }
        ledger.checkInvariants(g.copy(positions = mapOf((buyer to usd) to 5L, (opening to usd) to -5L)))
    }

    @Test fun checkpointChecksumCoversActionsChainAndControls() {
        val r = openedAndCaptured(); r.go(settle("e1", 1))
        val s = r.state
        val base = ledger.checkpoint(s).checksum
        val firstAction = s.actions.entries.first()
        val variants = mapOf(
            "actions" to s.copy(actions = s.actions + (firstAction.key to firstAction.value.copy(digest = "0".repeat(64)))),
            "chain" to s.copy(chain = "0".repeat(64)),
            "controls" to s.copy(controls = s.controls + 1),
        )
        for ((name, v) in variants) assertNotEquals(base, ledger.checkpoint(v).checksum, "checksum must cover $name")
    }

    private fun refused(state: FinanceState, cfg: FinanceLedger = ledger, text: String) {
        val e = assertFailsWith<IllegalStateException>(text) { cfg.checkInvariants(state) }
        assertTrue(e.message!!.contains(text), "expected '$text' but was '${e.message}'")
    }

    @Test fun invariantCheckerRejectsEachStructuralViolationOnItsOwn() {
        val g = ledger.genesis()
        val ek = key("e1")
        val captured = Captured(buyer, seller, 3, price)
        val rec = ActionRecord("d", Disposition.CAPTURED)
        refused(g.copy(obligations = mapOf(ek to Obligation(ObligationStatus.PENDING, 0, 0))), text = "obligation without execution")
        refused(g.copy(executions = mapOf(ek to captured), obligations = mapOf(ek to Obligation(ObligationStatus.PENDING, 1, 3)),
            actions = mapOf(action("a") to rec), sequence = 1), text = "pending residual drift")
        refused(g.copy(executions = mapOf(ek to captured), obligations = mapOf(ek to Obligation(ObligationStatus.PENDING, 3 * price, 2)),
            actions = mapOf(action("a") to rec), sequence = 1), text = "pending residual drift")
        refused(g.copy(executions = mapOf(ek to captured), obligations = mapOf(ek to Obligation(ObligationStatus.SETTLED, 1, 0)),
            actions = mapOf(action("a") to rec), sequence = 1), text = "settled residual not zero")
        refused(g.copy(sequence = 1), text = "action count differs from sequence")
        refused(g.copy(actions = mapOf(action("a") to rec)), text = "action count differs from sequence")
    }

    @Test fun invariantCheckerEnforcesEveryBudgetClassOnItsOwn() {
        val small = FinanceLedger(FinanceConfig(setOf(buyer, seller), usd, aapl, opening, maxObligations = 1, maxCaptures = 1, maxSettleAttempts = 1, maxControls = 1))
        val g = small.genesis()
        val captured = Captured(buyer, seller, 1, 1)
        val pending = Obligation(ObligationStatus.PENDING, 1, 1)
        val cap = ActionRecord("d", Disposition.CAPTURED)
        val funded = ActionRecord("d", Disposition.FUNDED)
        refused(g.copy(executions = mapOf(key("e1") to captured, key("e2") to captured), obligations = mapOf(key("e1") to pending, key("e2") to pending),
            actions = mapOf(action("a") to cap, action("b") to cap), sequence = 2), small, "capture budget exceeded")
        refused(g.copy(attempts = mapOf((key("e1") to 1) to Disposition.INSUFFICIENT_CASH, (key("e1") to 2) to Disposition.INSUFFICIENT_CASH)), small, "settle attempt budget exceeded")
        refused(g.copy(actions = mapOf(action("a") to funded, action("b") to funded), sequence = 2, controls = 2), small, "control budget or count drift")
        refused(g.copy(actions = mapOf(action("a") to funded), sequence = 1, controls = 0), small, "control budget or count drift")
        refused(g.copy(actions = (1..4).associate { action("a$it") to cap }, sequence = 4), small, "record budget exceeded")
    }

    /** O3a review 2 (P2-2): persisted histories depend on these encodings. Goldens come from an independent Python encoder
     *  (length-framed big-endian), so a change to Canon, decisionBytes or stateBytes fails here instead of silently
     *  invalidating stored history. */
    @Test fun digestsGenesisDecisionAndCheckpointMatchIndependentGoldens() {
        assertEquals("2ff64992b6c8b48e41e8e6a215dbbfe2275f0d149e5741a8e688fa385cddebc8", ledger.config.digest())
        assertEquals("8503a08ffb4b17ea312399af0de8fdb66242a347fa356a7f9135d9501f85d84c", ledger.genesisChain)
        val input = fund("open-seller-security", seller, aapl, 6)
        assertEquals("92d081004ada17d9e3a9cd27c3149d072903ccfe606a00574840e04e3508525f", input.digest())
        val r = run(); r.go(input)
        val d = r.decisions.single()
        assertEquals("bb6d9959f179cd4909a4cf3fca487f8aacd3dc31f0dda6d3dac4e172808cf204", d.stateDigest)
        assertEquals("6169f4ec8be456aa9f5a5f23597ef626247a17ba642371db3d10b7138a2c8613", d.checksum)
        assertEquals("30ff5c5c585864f81f6b81c3c6b8aa0ee1b5b082dc7af97f2c2a224f5a57612a", ledger.checkpoint(r.state).checksum)
    }

    @Test fun invariantCheckerRejectsCrossStructureInconsistency() {
        val g = ledger.genesis()
        val ek = key("e1")
        val cap = ActionRecord("d", Disposition.CAPTURED)
        val captured = Captured(buyer, seller, 3, price)
        val pending = Obligation(ObligationStatus.PENDING, 3 * price, 3)
        val one = g.copy(executions = mapOf(ek to captured), obligations = mapOf(ek to pending), actions = mapOf(action("a") to cap), sequence = 1)
        ledger.checkInvariants(one)
        refused(g.copy(positions = mapOf((buyer to AssetId("EUR")) to 5L, (opening to AssetId("EUR")) to -5L)), text = "position in unknown asset")
        refused(g.copy(attempts = mapOf((ek to 1) to Disposition.INSUFFICIENT_CASH), actions = mapOf(action("a") to ActionRecord("d", Disposition.INSUFFICIENT_CASH)), sequence = 1),
            text = "attempt for unknown execution or ordinal")
        refused(one.copy(attempts = mapOf((ek to 0) to Disposition.INSUFFICIENT_CASH), actions = one.actions + (action("b") to ActionRecord("d", Disposition.INSUFFICIENT_CASH)), sequence = 2),
            text = "attempt for unknown execution or ordinal")
        refused(one.copy(attempts = mapOf((ek to 1) to Disposition.CAPTURED), actions = one.actions + (action("b") to ActionRecord("d", Disposition.INSUFFICIENT_CASH)), sequence = 2),
            text = "attempt with an illegal disposition")
        refused(one.copy(actions = mapOf(action("a") to ActionRecord("d", Disposition.CAPTURED), action("b") to ActionRecord("d", Disposition.CAPTURED)), sequence = 2),
            text = "captures differ from executions")
        refused(one.copy(actions = mapOf(action("a") to cap, action("b") to ActionRecord("d", Disposition.UNAUTHORIZED)), sequence = 2),
            text = "never recorded")
        refused(one.copy(actions = one.actions + (action("b") to ActionRecord("d", Disposition.INSUFFICIENT_CASH)), sequence = 2), text = "settle actions differ from attempts")
        // An obligation marked SETTLED with no SETTLED attempt, and a SETTLED attempt on a pending obligation.
        refused(one.copy(obligations = mapOf(ek to Obligation(ObligationStatus.SETTLED, 0, 0))), text = "settled attempt differs from obligation status")
        refused(one.copy(attempts = mapOf((ek to 1) to Disposition.SETTLED), actions = one.actions + (action("b") to ActionRecord("d", Disposition.SETTLED)), sequence = 2),
            text = "settled attempt differs from obligation status")
    }

    @Test fun configRejectsSharedOpeningAndIdenticalAssets() {
        assertFailsWith<IllegalArgumentException> { FinanceConfig(setOf(buyer, seller), usd, aapl, buyer) }
        assertFailsWith<IllegalArgumentException> { FinanceConfig(setOf(buyer, seller), usd, usd, opening) }
        assertFailsWith<IllegalArgumentException> { FinanceConfig(setOf(buyer), usd, aapl, opening) }
    }
}

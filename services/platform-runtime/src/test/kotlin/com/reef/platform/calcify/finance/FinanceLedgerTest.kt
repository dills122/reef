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
    private fun settle(id: String, attempt: Int) = FinanceInput.Settle(action("settle-$id-$attempt", "repair"), key(id), attempt)

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
        val again = r.go(settle("e1", 3)); assertEquals(Disposition.ALREADY_SETTLED, again.disposition); assertTrue(again.decision!!.legs.isEmpty())
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
        assertEquals(Disposition.EXECUTION_ALREADY_CAPTURED, same.disposition); assertTrue(same.decision!!.legs.isEmpty())
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
        assertEquals(Disposition.BALANCE_OVERFLOW, over.disposition); assertTrue(over.decision!!.legs.isEmpty())
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
        assertEquals(Disposition.SETTLED, go(FinanceInput.Settle(action("s1", "repair"), key("g1"), 1)).disposition)
        assertEquals(5L, s.positions[y to sec]); assertEquals(50L, s.positions[x to eur])
        // A third account is refused, and so are the fixture's asset names.
        assertEquals(Disposition.UNKNOWN_ACCOUNT, go(FinanceInput.Capture(action("c2"), key("g2"), z, x, 1, 1)).disposition)
        assertEquals(Disposition.UNKNOWN_ACCOUNT, go(FinanceInput.Fund(action("f3", "control"), Authority.OPERATOR, z, eur, 1)).disposition)
        assertEquals(Disposition.INVALID_INPUT, go(FinanceInput.Fund(action("f4", "control"), Authority.OPERATOR, x, usd, 1)).disposition)
        generic.checkInvariants(s)
    }

    @Test fun budgetsAreEnforcedWithoutRecording() {
        val small = FinanceLedger(FinanceConfig(setOf(buyer, seller), usd, aapl, opening, maxObligations = 2, maxAttempts = 3))
        var s = small.genesis()
        fun go(i: FinanceInput) = small.decide(s, i).also { s = it.state }
        assertEquals(Disposition.CAPTURED, go(capture("a", 1)).disposition)
        assertEquals(Disposition.CAPTURED, go(capture("b", 1)).disposition)
        // Obligation budget exhausted: a new execution is refused, not recorded, and does not use an attempt.
        val third = go(capture("c", 1)); assertEquals(Disposition.BUDGET_EXCEEDED, third.disposition); assertNull(third.decision)
        assertEquals(2, s.sequence)
        // A duplicate of an existing execution is still evaluated (not a new obligation).
        assertEquals(Disposition.EXECUTION_ALREADY_CAPTURED, go(capture("a", 1, actionId = "dup-a")).disposition)
        // Attempt budget exhausted.
        val fourth = go(fund("f", buyer, usd, 1)); assertEquals(Disposition.BUDGET_EXCEEDED, fourth.disposition); assertNull(fourth.decision)
        assertEquals(3, s.sequence)
        assertFailsWith<IllegalArgumentException> { FinanceConfig(setOf(buyer, seller), usd, aapl, opening, maxAttempts = 161) }
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
            val restored = Run(ledger).apply { state = ledger.restore(checkpoint) }
            inputs.drop(cut).forEach { restored.go(it) }
            assertEquals(full.state, restored.state, "cut=$cut")
            assertTrue(ledger.verifyChain(prefix.decisions))
        }
    }

    @Test fun tamperedCheckpointAndHistoryAreDetected() {
        val r = openedAndCaptured()
        val checkpoint = ledger.checkpoint(r.state)
        val forged = checkpoint.copy(state = r.state.copy(positions = r.state.positions + ((buyer to usd) to 1L)))
        assertFailsWith<IllegalArgumentException> { ledger.restore(forged) }
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
            str("calcify-finance-decision-v1"); str(priorChain); long(sequence)
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
        val again = r.go(FinanceInput.Settle(action("other-action-key", "repair"), key("e1"), 1))
        assertEquals(Disposition.PRIOR_ATTEMPT, again.disposition); assertTrue(again.decision!!.legs.isEmpty())
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
        assertEquals(Disposition.INVALID_INPUT, r.go(FinanceInput.Settle(action("neg-attempt", "repair"), key("e1"), -1)).disposition)
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
        r.go(FinanceInput.Fund(action("same-key", "control"), Authority.TRADING, buyer, usd, 5))
        val operator = r.go(FinanceInput.Fund(action("same-key", "control"), Authority.OPERATOR, buyer, usd, 5))
        assertEquals(Disposition.ACTION_CONFLICT, operator.disposition)
        val s1 = FinanceInput.Settle(action("same-settle", "repair"), key("e9"), 1)
        val s2 = FinanceInput.Settle(action("same-settle", "repair"), key("e9"), 2)
        assertNotEquals(s1.digest(), s2.digest())
        r.go(capture("e9", 1)); r.go(s1)
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
        // Forged decisions with a checksum that is valid for their own (wrong) fields.
        val forgedSeq = r.decisions.toMutableList().apply { this[3] = forge(this[3], sequence = 99) }
        assertFalse(ledger.verifyChain(forgedSeq))
        val forgedLink = r.decisions.toMutableList().apply { this[3] = forge(this[3], priorChain = "not-the-previous-checksum") }
        assertFalse(ledger.verifyChain(forgedLink))
        // The forger's byte layout must equal the production layout, or the forged cases above would pass vacuously.
        assertTrue(ledger.verifyChain(r.decisions.mapIndexed { i, d -> if (i == 3) forge(d) else d }))
    }

    @Test fun restoreRejectsEveryTamperedStateField() {
        val r = openedAndCaptured(); r.go(settle("e1", 1))
        val cp = ledger.checkpoint(r.state)
        assertEquals(r.state, ledger.restore(cp))
        val s = r.state
        val tampered = mapOf(
            "positions" to s.positions + ((buyer to usd) to 1L),
            "attempts" to s.attempts.minus(s.attempts.keys.first()),
            "actions" to s.actions.minus(s.actions.keys.first()),
            "chain" to "forged",
            "sequence" to (s.sequence + 1),
        )
        for ((name, value) in tampered) {
            val forged = when (name) {
                "positions" -> s.copy(positions = value as Map<Pair<AccountId, AssetId>, Long>)
                "attempts" -> s.copy(attempts = value as Map<Pair<ExecutionKey, Int>, Disposition>)
                "actions" -> s.copy(actions = value as Map<ActionKey, ActionRecord>)
                "chain" -> s.copy(chain = value as String)
                else -> s.copy(sequence = value as Long)
            }
            assertFails("tampered $name must be refused") { ledger.restore(cp.copy(state = forged)) }
        }
        assertFails { ledger.restore(cp.copy(checksum = "0".repeat(64))) }
    }

    @Test fun restoreRunsInvariantsEvenWithAValidChecksum() {
        val bad = ledger.genesis().copy(positions = mapOf((buyer to usd) to -5L, (opening to usd) to 5L))
        assertFails { ledger.restore(ledger.checkpoint(bad)) }
    }

    @Test fun invariantCheckerRejectsNegativeUnbalancedAndUnknownAccounts() {
        val g = ledger.genesis()
        assertFails("negative non-opening") { ledger.checkInvariants(g.copy(positions = mapOf((buyer to usd) to -5L, (opening to usd) to 5L))) }
        assertFails("unbalanced") { ledger.checkInvariants(g.copy(positions = mapOf((buyer to usd) to 5L))) }
        assertFails("unknown account") { ledger.checkInvariants(g.copy(positions = mapOf((AccountId("nobody") to usd) to 5L, (opening to usd) to -5L))) }
        ledger.checkInvariants(g.copy(positions = mapOf((buyer to usd) to 5L, (opening to usd) to -5L)))
    }

    @Test fun configRejectsSharedOpeningAndIdenticalAssets() {
        assertFailsWith<IllegalArgumentException> { FinanceConfig(setOf(buyer, seller), usd, aapl, buyer) }
        assertFailsWith<IllegalArgumentException> { FinanceConfig(setOf(buyer, seller), usd, usd, opening) }
        assertFailsWith<IllegalArgumentException> { FinanceConfig(setOf(buyer), usd, aapl, opening) }
    }
}

package com.reef.platform.calcify.finance

import com.reef.platform.calcify.CalcifySourceFixtures
import com.reef.platform.calcify.CommitmentId
import com.reef.platform.calcify.CommitmentVerificationPassed
import com.reef.platform.calcify.MatchContextResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import reef.contracts.calcify.v1.AcceptedOrderSourceV1
import reef.contracts.calcify.v1.MatchContextResolvedV1

/**
 * The capacity decoder against resolved contexts produced by the repository's own resolver code from its own recorded venue-event fixture (the Phase 2 evidence
 * fixture, 130 trades): every context must decode, the economics must match the trade facts, and the ledger must book exactly the distinct executions.
 */
class CapacityResolvedFixtureTest {
    private fun contexts(): List<MatchContextResolvedV1> {
        val batches = CalcifySourceFixtures.currentBodies().mapIndexed { offset, body -> MatchContextResolver.parseBatch(body, "source", "topic-uuid", 1, 0, offset.toLong()) }
        val orders = mutableMapOf<String, AcceptedOrderSourceV1>(); val out = ArrayList<MatchContextResolvedV1>()
        batches.forEachIndexed { offset, batch ->
            for (row in batch.acceptedOrders) orders[row.fact.orderId] = MatchContextResolver.mergeAcceptance(orders[row.fact.orderId], row)
            batch.trades.forEachIndexed { ordinal, trade ->
                out += MatchContextResolver.resolve(CommitmentVerificationPassed(CommitmentId(1, 0, offset.toLong(), ordinal), 1), trade, orders[trade.fact.buyOrderId], orders[trade.fact.sellOrderId])
            }
        }
        return out
    }

    @Test fun everyRealResolvedContextDecodesAndTheLedgerBooksExactlyTheDistinctExecutions() {
        val all = contexts(); assertEquals(130, all.size)
        val first = all.first()
        val instruments = all.map { it.trade.fact.instrumentId }.toSet()
        val plan = CapacityPlan("fixture", first.runId, first.buyAcceptedOrder.fact.venueSessionId, AssetId(first.trade.fact.price.currency), AccountId("opening"), listOf(instruments), 10_000, 10_000, 20_000, 10_000)
        val decoder = CapacityResolvedContextDecoder(plan)
        val ledger = CapacityLedger(plan.capacity(0))
        val accounts = all.flatMap { listOf(it.buyAcceptedOrder.fact.accountId, it.sellAcceptedOrder.fact.accountId) }.toSortedSet()
        var n = 0
        for (a in accounts) {
            ledger.decide(FinanceInput.Fund(ActionKey("op", "fund", "cash-${n++}"), Authority.OPERATOR, AccountId(a), plan.cashAsset, 1_000_000_000_000_000L))
            for (i in instruments) ledger.decide(FinanceInput.Fund(ActionKey("op", "fund", "sec-${n++}"), Authority.OPERATOR, AccountId(a), AssetId(i), 1_000_000_000L))
        }
        val dispositions = HashMap<Disposition, Int>()
        for (c in all) {
            val items = decoder.decode("CAPTURE", 0, 0, c.toByteArray())
            assertTrue(items.none { it is CapacityItem.Bad }, "context ${c.trade.fact.executionId} decoded: $items")
            for (item in items) dispositions.merge(ledger.decide((item as CapacityItem.Run).input).disposition, 1, Int::plus)
        }
        val distinct = all.map { it.trade.fact.executionId }.toSet().size
        assertEquals(distinct, dispositions[Disposition.CAPTURED], "$dispositions")
        assertEquals(distinct, dispositions[Disposition.SETTLED], "$dispositions")
        val state = ledger.snapshot()
        assertTrue(state.obligations.values.all { it.status == ObligationStatus.SETTLED })
        assertEquals(StateCommitment.reference(state), ledger.commitment())
        // The economics booked are the trade facts: total cash moved equals the sum of quantity x price over the distinct executions.
        val expectedCash = all.distinctBy { it.trade.fact.executionId }.sumOf { it.trade.fact.quantity.units.toLong() * it.trade.fact.price.nanos.toLong() }
        val opening = state.positions[AccountId("opening") to plan.cashAsset]!!
        val traded = state.positions.filterKeys { it.second == plan.cashAsset && it.first != AccountId("opening") }.values.sum()
        assertEquals(-opening, traded, "cash conserved")
        assertTrue(expectedCash > 0)
        println("RESOLVED-FIXTURE decoded=${all.size} distinctExecutions=$distinct instruments=$instruments accounts=${accounts.size} dispositions=$dispositions cashTraded=$expectedCash")
    }
}

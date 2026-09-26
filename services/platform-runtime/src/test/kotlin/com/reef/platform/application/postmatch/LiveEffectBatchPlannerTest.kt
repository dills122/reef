package com.reef.platform.application.postmatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LiveEffectBatchPlannerTest {
    private val planner = LiveEffectBatchPlanner()

    @Test
    fun keepsEveryTradeFactAndOnlyFinalStatePerAffectedOrder() {
        val effects = listOf(
            envelope(1, 0, state("taker", "OPEN", "10")),
            envelope(2, 0, execution("taker", "execution-buy", "TAKER")),
            envelope(2, 1, execution("maker", "execution-sell", "MAKER")),
            envelope(2, 2, trade()),
            envelope(2, 3, state("taker", "FILLED", "0")),
            envelope(2, 4, state("maker", "PARTIALLY_FILLED", "5"))
        )
        val planned = planner.plan(effects)

        assertEquals(listOf("taker", "maker"), planned.finalOrderStates.map { (it.effect as CanonicalEffect.OrderStateChanged).orderId })
        assertEquals(2L, planned.finalOrderStates.first().position.streamSequence)
        assertEquals(2, planned.executions.size)
        assertEquals(1, planned.trades.size)
    }

    @Test
    fun rejectsOutOfOrderAndDuplicateEffectPositions() {
        assertFailsWith<IllegalArgumentException> {
            planner.plan(listOf(envelope(2, 0, state("a", "OPEN", "10")), envelope(1, 0, state("a", "FILLED", "0"))))
        }
        assertFailsWith<IllegalArgumentException> {
            planner.plan(listOf(envelope(1, 0, state("a", "OPEN", "10")), envelope(1, 0, state("a", "FILLED", "0"))))
        }
    }

    private fun envelope(sequence: Long, ordinal: Int, effect: CanonicalEffect) = CanonicalEffectEnvelope(
        position = CanonicalEffectPosition("stream", 0, sequence, ordinal),
        batchId = "batch-$sequence", commandId = "command-$sequence", commandType = "SubmitOrder",
        payloadHash = "hash-$sequence", effect = effect
    )

    private fun state(orderId: String, status: String, remaining: String) = CanonicalEffect.OrderStateChanged(
        orderId, "AAPL", if (orderId == "maker") "SELL" else "BUY", status,
        "10", remaining, "100", "USD", "2026-09-26T00:00:00Z"
    )

    private fun execution(orderId: String, id: String, role: String) = CanonicalEffect.Execution(
        eventId = "event-$id", executionId = id, orderId = orderId, instrumentId = "AAPL",
        quantityUnits = "5", price = "100", currency = "USD", occurredAt = "2026-09-26T00:00:00Z", liquidityRole = role
    )

    private fun trade() = CanonicalEffect.Trade(
        eventId = "trade-event", tradeId = "trade-1", executionId = "execution",
        buyOrderId = "taker", sellOrderId = "maker", instrumentId = "AAPL",
        quantityUnits = "5", price = "100", currency = "USD", occurredAt = "2026-09-26T00:00:00Z"
    )
}

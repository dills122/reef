package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.*
import kotlin.test.*

internal fun assertRunOrderHistoryIsolation(persistence: RuntimePersistence, bulk: Boolean = false) {
    fun order(run: String, id: String, participant: String, side: String) = PersistedOrder(
        orderId = id, engineOrderId = id, instrumentId = "AAPL", participantId = participant,
        accountId = "account-$participant", side = side, orderType = "LIMIT", quantityUnits = "10",
        limitPrice = "100", currency = "USD", timeInForce = "DAY", acceptedAt = "2026-10-01T00:00:00Z",
        runId = run, venueSessionId = "session"
    )
    for (run in listOf("run-a", "run-b")) {
        val buyer = order(run, "shared", "buyer-$run", "BUY")
        val seller = order(run, "ask", "seller-$run", "SELL")
        val fills = listOf(ExecutionCreated(
            eventId = "fill-$run", executionId = "execution-$run", orderId = "shared", instrumentId = "AAPL",
            quantityUnits = "2", executionPrice = "100", currency = "USD", occurredAt = "2026-10-01T00:01:00Z", runId = run
        ))
        val trades = listOf(TradeCreated(
            eventId = "trade-$run", tradeId = "trade-$run", executionId = "execution-$run",
            buyOrderId = "shared", sellOrderId = "ask", instrumentId = "AAPL", quantityUnits = "2",
            price = "100", currency = "USD", occurredAt = "2026-10-01T00:01:00Z", runId = run
        ))
        if (bulk) {
            fun outcome(order: PersistedOrder, fills: List<ExecutionCreated>, trades: List<TradeCreated>) = PersistableSubmitOutcome(
                commandId = "submit-${order.runId}-${order.orderId}",
                result = SubmitOrderResult(accepted = EngineOrderAccepted("accepted-${order.runId}-${order.orderId}", order.orderId, order.engineOrderId, order.acceptedAt), executions = fills, trades = trades),
                acceptedOrder = order, lifecycleEvents = emptyList()
            )
            val outcomes = listOf(outcome(buyer, fills, trades), outcome(seller, emptyList(), emptyList()))
            persistence.persistSubmitOutcomes(outcomes)
            persistence.persistSubmitOutcomes(outcomes)
        } else {
            persistence.saveAcceptedOrder(buyer)
            persistence.saveAcceptedOrder(seller)
            persistence.saveExecutions(fills)
            persistence.saveTrades(trades)
        }
    }
    fun event(run: String, type: String, payload: String) = RuntimeEvent(
        eventId = "$type-$run", eventType = type, orderId = "shared", traceId = "trace-$run",
        causationId = "command-$run", correlationId = "correlation-$run", producer = "test", schemaVersion = "v1",
        occurredAt = "2026-10-01T00:02:00Z", payloadJson = payload, runId = run
    )
    val modified = event("run-a", "OrderModified", """{"quantityUnits":"12","limitPrice":"110"}""")
    val cancelled = event("run-b", "OrderCancelled", "{}")
    persistence.saveEvents(listOf(modified, cancelled))
    persistence.saveEvents(listOf(modified, cancelled))
    persistence.projectOrderLifecycleState(500)
    if (bulk) {
        assertEquals(listOf("execution-run-a"), persistence.submitResult("submit-run-a-shared")?.executions?.map { it.executionId })
        assertEquals(listOf("execution-run-b"), persistence.submitResult("submit-run-b-shared")?.executions?.map { it.executionId })
    }
    val first = RuntimeOrderIdentity("run-a", "shared")
    val second = RuntimeOrderIdentity("run-b", "shared")
    assertEquals("buyer-run-a", persistence.acceptedOrder(first)?.participantId)
    assertEquals("buyer-run-b", persistence.acceptedOrder(second)?.participantId)
    assertNull(persistence.acceptedOrder("shared"), "unscoped lookup must not pick another run")
    assertEquals("PARTIALLY_FILLED", persistence.orderLifecycleState(first)?.status)
    assertEquals("10", persistence.orderLifecycleState(first)?.remainingQuantityUnits)
    assertEquals("110", persistence.orderLifecycleState(first)?.limitPrice)
    assertEquals("CANCELLED", persistence.orderLifecycleState(second)?.status)
    assertEquals("0", persistence.orderLifecycleState(second)?.remainingQuantityUnits)
    assertEquals(listOf("execution-run-a"), persistence.executionsForParticipant("buyer-run-a").map { it.executionId })
    assertEquals(listOf("execution-run-b"), persistence.executionsForParticipant("buyer-run-b").map { it.executionId })
    assertEquals(listOf("trade-run-a"), persistence.tradesForSettlementMaterialization("run-a", "session").map { it.tradeId })
    assertEquals(listOf("trade-run-b"), persistence.tradesForSettlementMaterialization("run-b", "session").map { it.tradeId })
    assertEquals(listOf("OrderModified-run-a"), persistence.eventsForOrder(first).map { it.eventId })
    assertEquals(listOf("execution-run-a"), persistence.executionsForOrder(first).map { it.executionId })
    assertEquals(listOf("trade-run-b"), persistence.tradesForOrder(second).map { it.tradeId })
    assertEquals(4, persistence.rebuildOrderLifecycleState())
    assertEquals("PARTIALLY_FILLED", persistence.orderLifecycleState(first)?.status)
    assertEquals("CANCELLED", persistence.orderLifecycleState(second)?.status)
    for (run in listOf("run-a", "run-b")) {
        persistence.saveAcceptedOrder(order(run, "client-shared", "client-owner", "BUY").copy(clientOrderId = "shared-client"))
    }
    assertNull(persistence.findOrderByClientOrderId("client-owner", "shared-client"))
    assertEquals("run-a", persistence.findOrderByClientOrderId("client-owner", "shared-client", "run-a")?.runId)
    assertEquals("run-b", persistence.findOrderByClientOrderId("client-owner", "shared-client", "run-b")?.runId)

}

internal fun assertCanonicalRunOrderProjectionIsolation(persistence: RuntimePersistence) {
    val outcomes = listOf("run-a", "run-b").mapIndexed { index, run ->
        val order = PersistedOrder("shared", "shared", "AAPL", "buyer-$run", "account-$run", "BUY", "LIMIT", "10", "100", "USD", "DAY", "2026-10-01T00:00:00Z", runId = run, venueSessionId = "session")
        val fill = ExecutionCreated("fill-$run", "fill-$run", "shared", "AAPL", "2", "100", "USD", "2026-10-01T00:01:00Z")
        val result = """{"accepted":{"eventId":"accepted-$run","orderId":"shared","engineOrderId":"shared","occurredAt":"2026-10-01T00:00:00Z"},"acceptedOrder":${order.toJsonObject()},"executions":[${fill.toJsonObject()}],"trades":[]}"""
        VenueCommandOutcomeFact("command-$run", "SubmitOrder", index + 1L, 1, "hash-$run", "AAPL", "shared", "accepted", resultPayloadJson = result)
    }
    val batch = VenueEventBatchFact("batch", "shard", 0, "commands", "events", 1, 2, 2, "2026-10-01T00:02:00Z", payloadChecksum = "test", outcomes = outcomes)
    assertEquals(2, persistence.materializeVenueEventBatch(batch))
    assertEquals(0, persistence.materializeVenueEventBatch(batch))
    assertEquals(2, persistence.projectCanonicalCommandOutcomes("initial", 500))
    for (run in listOf("run-a", "run-b")) {
        val identity = RuntimeOrderIdentity(run, "shared")
        assertEquals("buyer-$run", persistence.acceptedOrder(identity)?.participantId)
        assertEquals(listOf("fill-$run"), persistence.executionsForOrder(identity).map { it.executionId })
        assertEquals(listOf("accepted-$run"), persistence.eventsForOrder(identity).map { it.eventId })
    }
    assertEquals(2, persistence.projectCanonicalCommandOutcomes("replay", 500))
    persistence.rebuildOrderLifecycleState()
    for (run in listOf("run-a", "run-b")) {
        assertEquals("8", persistence.orderLifecycleState(RuntimeOrderIdentity(run, "shared"))?.remainingQuantityUnits)
    }
}

internal fun assertCanonicalRunOrderLifecycleIsolation(
    persistence: RuntimePersistence,
    captureCommand: (String, String) -> Unit
) {
    assertCanonicalRunOrderProjectionIsolation(persistence)
    val commands = listOf("ModifyOrder" to "run-a", "CancelOrder" to "run-b")
    val outcomes = commands.mapIndexed { index, (type, run) ->
        val commandId = "lifecycle-$run-${java.util.UUID.randomUUID()}"
        captureCommand(commandId, """{"commandId":"$commandId","commandType":"$type","runId":"$run","orderId":"shared","quantityUnits":"12","limitPrice":"110"}""")
        VenueCommandOutcomeFact(
            commandId, type, index + 3L, 1, "hash-lifecycle-$run", "AAPL", "shared", "accepted",
            resultPayloadJson = """{"eventId":"lifecycle-event-$run","orderId":"shared","engineOrderId":"shared","occurredAt":"2026-10-01T00:03:00Z","quantityUnits":"12","limitPrice":"110"}"""
        )
    }
    val batch = VenueEventBatchFact("lifecycle-batch", "shard", 0, "commands", "events", 3, 4, 2, "2026-10-01T00:04:00Z", payloadChecksum = "lifecycle", outcomes = outcomes)
    assertEquals(2, persistence.materializeVenueEventBatch(batch))
    assertEquals(2, persistence.projectCanonicalCommandOutcomes("initial", 500))
    persistence.projectOrderLifecycleState(500)
    val modified = RuntimeOrderIdentity("run-a", "shared")
    val cancelled = RuntimeOrderIdentity("run-b", "shared")
    assertEquals(listOf("accepted-run-a", "lifecycle-event-run-a"), persistence.eventsForOrder(modified).map { it.eventId })
    assertEquals(listOf("accepted-run-b", "lifecycle-event-run-b"), persistence.eventsForOrder(cancelled).map { it.eventId })
    assertEquals("PARTIALLY_FILLED", persistence.orderLifecycleState(modified)?.status)
    assertEquals("10", persistence.orderLifecycleState(modified)?.remainingQuantityUnits)
    assertEquals("110", persistence.orderLifecycleState(modified)?.limitPrice)
    assertEquals("CANCELLED", persistence.orderLifecycleState(cancelled)?.status)
    assertEquals("0", persistence.orderLifecycleState(cancelled)?.remainingQuantityUnits)
    assertEquals(4, persistence.projectCanonicalCommandOutcomes("lifecycle-replay", 500))
    assertEquals(2, persistence.rebuildOrderLifecycleState())
    assertEquals("10", persistence.orderLifecycleState(modified)?.remainingQuantityUnits)
    assertEquals("CANCELLED", persistence.orderLifecycleState(cancelled)?.status)
    assertEquals(2, persistence.eventsForOrder(modified).size)
    assertEquals(2, persistence.eventsForOrder(cancelled).size)
}

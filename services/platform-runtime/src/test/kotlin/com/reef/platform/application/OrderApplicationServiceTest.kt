package com.reef.platform.application

import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrderApplicationServiceTest {
    @Test
    fun submitOrderDelegatesToEngineGatewayAndPersistsAcceptedArtifacts() {
        val gateway = RecordingEngineGateway()
        val persistence = InMemoryRuntimePersistence()
        val service = OrderApplicationService(gateway, persistence)

        val result = service.submitOrder(
            SubmitOrderCommand(
                commandId = "cmd-1",
                traceId = "trace-1",
                causationId = "",
                correlationId = "corr-1",
                actorId = "trader-1",
                occurredAt = "2026-03-14T18:00:00Z",
                orderId = "ord-1",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "150250000000",
                currency = "USD",
                timeInForce = "DAY"
            )
        )

        assertNotNull(gateway.lastCommand)
        assertEquals(1, gateway.submitCalls)
        assertEquals("ord-1", gateway.lastCommand?.orderId)
        assertEquals("eng-ord-1", result.accepted?.engineOrderId)
        assertEquals("eng-ord-1", service.persistedOrder("ord-1")?.engineOrderId)
        assertEquals(1, service.persistedExecutions("ord-1").size)
        assertEquals(1, service.persistedTrades("ord-1").size)
        assertEquals(3, service.persistedEvents("ord-1").size)
        assertEquals(3, service.persistedTraceEvents("trace-1").size)
    }

    @Test
    fun submitOrderDoesNotPersistRejectedArtifacts() {
        val service = OrderApplicationService(RejectingEngineGateway(), InMemoryRuntimePersistence())

        val result = service.submitOrder(
            SubmitOrderCommand(
                commandId = "cmd-2",
                traceId = "trace-2",
                causationId = "",
                correlationId = "corr-2",
                actorId = "trader-2",
                occurredAt = "2026-03-14T18:00:00Z",
                orderId = "ord-2",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "150250000000",
                currency = "USD",
                timeInForce = "DAY"
            )
        )

        assertNotNull(result.rejected)
        assertNull(service.persistedOrder("ord-2"))
        assertEquals(0, service.persistedExecutions("ord-2").size)
        assertEquals(0, service.persistedTrades("ord-2").size)
        assertEquals(1, service.persistedEvents("ord-2").size)
        assertEquals(1, service.persistedTraceEvents("trace-2").size)
    }

    @Test
    fun submitOrderIsIdempotentByCommandId() {
        val gateway = RecordingEngineGateway()
        val persistence = InMemoryRuntimePersistence()
        val service = OrderApplicationService(gateway, persistence)

        val command = SubmitOrderCommand(
            commandId = "cmd-idempotent-1",
            traceId = "trace-idempotent-1",
            causationId = "",
            correlationId = "corr-1",
            actorId = "trader-1",
            occurredAt = "2026-03-14T18:00:00Z",
            orderId = "ord-idempotent-1",
            instrumentId = "AAPL",
            participantId = "participant-1",
            accountId = "account-1",
            side = "BUY",
            orderType = "LIMIT",
            quantityUnits = "100",
            limitPrice = "150250000000",
            currency = "USD",
            timeInForce = "DAY"
        )

        val first = service.submitOrder(command)
        val second = service.submitOrder(command)

        assertEquals(first, second)
        assertEquals(1, gateway.submitCalls)
        assertEquals(1, service.persistedOrders().size)
        assertEquals(1, service.persistedTrades().size)
        assertEquals(3, service.persistedTraceEvents("trace-idempotent-1").size)
    }

    @Test
    fun cancelOrderPersistsLifecycleEvent() {
        val service = OrderApplicationService(RecordingEngineGateway(), InMemoryRuntimePersistence())

        val result = service.cancelOrder(
            CancelOrderCommand(
                commandId = "cmd-cancel-1",
                traceId = "trace-cancel-1",
                causationId = "",
                correlationId = "corr-cancel-1",
                actorId = "trader-1",
                occurredAt = "2026-03-14T18:00:00Z",
                orderId = "ord-1",
                reason = "user requested"
            )
        )

        assertNotNull(result.accepted)
        assertEquals(1, service.persistedTraceEvents("trace-cancel-1").size)
    }

    @Test
    fun submitOrderTraceEventsFollowExpectedSequence() {
        val service = OrderApplicationService(RecordingEngineGateway(), InMemoryRuntimePersistence())
        service.submitOrder(
            SubmitOrderCommand(
                commandId = "cmd-seq-1",
                traceId = "trace-seq-1",
                causationId = "",
                correlationId = "corr-seq-1",
                actorId = "trader-1",
                occurredAt = "2026-03-14T18:00:00Z",
                orderId = "ord-seq-1",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "150250000000",
                currency = "USD",
                timeInForce = "DAY"
            )
        )

        val events = service.persistedTraceEvents("trace-seq-1")
        assertEquals(listOf("OrderAccepted", "ExecutionCreated", "TradeCreated"), events.map { it.eventType })
        assertEquals("cmd-seq-1", events.first().causationId)
        assertTrue(events.drop(1).all { it.causationId == events.first().eventId })
        assertTrue(events.all { it.traceId == "trace-seq-1" })
    }
}

private class RecordingEngineGateway : EngineGateway {
    var lastCommand: SubmitOrderCommand? = null
    var submitCalls: Int = 0

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        submitCalls += 1
        lastCommand = command
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-1",
                orderId = command.orderId,
                engineOrderId = "eng-${command.orderId}",
                occurredAt = "2026-03-14T18:00:00Z"
            ),
            executions = listOf(
                ExecutionCreated(
                    eventId = "evt-exec-1",
                    executionId = "exec-1",
                    orderId = command.orderId,
                    instrumentId = command.instrumentId,
                    quantityUnits = command.quantityUnits,
                    executionPrice = command.limitPrice,
                    currency = command.currency,
                    occurredAt = "2026-03-14T18:00:00Z"
                )
            ),
            trades = listOf(
                TradeCreated(
                    eventId = "evt-trade-1",
                    tradeId = "trade-1",
                    executionId = "exec-1",
                    buyOrderId = command.orderId,
                    sellOrderId = "ord-2",
                    instrumentId = command.instrumentId,
                    quantityUnits = command.quantityUnits,
                    price = command.limitPrice,
                    currency = command.currency,
                    occurredAt = "2026-03-14T18:00:00Z"
                )
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        submitCalls += 1
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-cancel-1",
                orderId = command.orderId,
                engineOrderId = "eng-${command.orderId}",
                occurredAt = "2026-03-14T18:00:00Z"
            )
        )
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        submitCalls += 1
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-modify-1",
                orderId = command.orderId,
                engineOrderId = "eng-${command.orderId}",
                occurredAt = "2026-03-14T18:00:00Z"
            )
        )
    }
}

private class RejectingEngineGateway : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return SubmitOrderResult(
            rejected = EngineOrderRejected(
                eventId = "evt-reject-1",
                orderId = command.orderId,
                code = "VALIDATION_ERROR",
                reason = "rejected",
                occurredAt = "2026-03-14T18:00:00Z"
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        return submitOrder(
            SubmitOrderCommand(
                commandId = command.commandId,
                traceId = command.traceId,
                causationId = command.causationId,
                correlationId = command.correlationId,
                actorId = command.actorId,
                occurredAt = command.occurredAt,
                orderId = command.orderId,
                instrumentId = "",
                participantId = "",
                accountId = "",
                side = "",
                orderType = "",
                quantityUnits = "",
                limitPrice = "",
                currency = "",
                timeInForce = ""
            )
        )
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        return cancelOrder(
            CancelOrderCommand(
                commandId = command.commandId,
                traceId = command.traceId,
                causationId = command.causationId,
                correlationId = command.correlationId,
                actorId = command.actorId,
                occurredAt = command.occurredAt,
                orderId = command.orderId,
                reason = ""
            )
        )
    }
}

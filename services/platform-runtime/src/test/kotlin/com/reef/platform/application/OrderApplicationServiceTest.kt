package com.reef.platform.application

import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

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
}

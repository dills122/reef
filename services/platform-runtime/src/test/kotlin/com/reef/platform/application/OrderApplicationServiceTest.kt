package com.reef.platform.application

import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.Account
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.Participant
import com.reef.platform.domain.Permission
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrderApplicationServiceTest {
    @Test
    fun submitOrderDelegatesToEngineGatewayAndPersistsAcceptedArtifacts() {
        val gateway = RecordingEngineGateway()
        val persistence = InMemoryRuntimePersistence()
        val service = OrderApplicationService(gateway, persistence)
        seedReferenceData(service)
        seedOrderAuthorization(service, "trader-1")

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
    fun submitOrderPersistsRejectedOrderAsLifecycleStateButNoFillArtifacts() {
        val service = OrderApplicationService(RejectingEngineGateway(), InMemoryRuntimePersistence())
        seedReferenceData(service)
        seedOrderAuthorization(service, "trader-2")

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
        val persistedOrder = service.persistedOrder("ord-2")
        assertNotNull(persistedOrder)
        assertEquals("", persistedOrder.engineOrderId)
        assertEquals(0, service.persistedExecutions("ord-2").size)
        assertEquals(0, service.persistedTrades("ord-2").size)
        assertEquals(1, service.persistedEvents("ord-2").size)
        assertEquals(1, service.persistedTraceEvents("trace-2").size)

        service.rebuildOrderLifecycleState()
        val lifecycleState = service.orderLifecycleState("ord-2")
        assertNotNull(lifecycleState)
        assertEquals("REJECTED", lifecycleState.status)
    }

    @Test
    fun submitOrderIsIdempotentByCommandId() {
        val gateway = RecordingEngineGateway()
        val persistence = InMemoryRuntimePersistence()
        val service = OrderApplicationService(gateway, persistence)
        seedReferenceData(service)
        seedOrderAuthorization(service, "trader-1")

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
    fun submitOrderRejectsUnauthorizedActorBeforeEngineCall() {
        val gateway = RecordingEngineGateway()
        val service = OrderApplicationService(gateway, InMemoryRuntimePersistence())
        seedReferenceData(service)

        val result = service.submitOrder(
            SubmitOrderCommand(
                commandId = "cmd-unauthorized-submit-1",
                traceId = "trace-unauthorized-submit-1",
                causationId = "",
                correlationId = "corr-unauthorized-submit-1",
                actorId = "trader-unauthorized",
                occurredAt = "2026-03-14T18:00:00Z",
                orderId = "ord-unauthorized-submit-1",
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
        assertEquals("AUTHORIZATION_ERROR", result.rejected?.code)
        assertEquals("actorId missing permission order.submit", result.rejected?.reason)
        assertEquals(0, gateway.submitCalls)
        assertEquals(listOf("OrderRejected"), service.persistedTraceEvents("trace-unauthorized-submit-1").map { it.eventType })
    }

    @Test
    fun cancelAndModifyRejectUnauthorizedActorsBeforeEngineCall() {
        val gateway = RecordingEngineGateway()
        val service = OrderApplicationService(gateway, InMemoryRuntimePersistence())

        val cancel = service.cancelOrder(
            CancelOrderCommand(
                commandId = "cmd-unauthorized-cancel-1",
                traceId = "trace-unauthorized-cancel-1",
                causationId = "",
                correlationId = "corr-unauthorized-cancel-1",
                actorId = "trader-unauthorized",
                occurredAt = "2026-03-14T18:00:00Z",
                orderId = "ord-unauthorized-cancel-1",
                reason = "user requested"
            )
        )
        val modify = service.modifyOrder(
            ModifyOrderCommand(
                commandId = "cmd-unauthorized-modify-1",
                traceId = "trace-unauthorized-modify-1",
                causationId = "",
                correlationId = "corr-unauthorized-modify-1",
                actorId = "trader-unauthorized",
                occurredAt = "2026-03-14T18:00:00Z",
                orderId = "ord-unauthorized-modify-1",
                quantityUnits = "120",
                limitPrice = "150250000001"
            )
        )

        assertEquals("AUTHORIZATION_ERROR", cancel.rejected?.code)
        assertEquals("actorId missing permission order.cancel", cancel.rejected?.reason)
        assertEquals("AUTHORIZATION_ERROR", modify.rejected?.code)
        assertEquals("actorId missing permission order.modify", modify.rejected?.reason)
        assertEquals(0, gateway.submitCalls)
    }

    @Test
    fun cancelOrderPersistsLifecycleEvent() {
        val service = OrderApplicationService(RecordingEngineGateway(), InMemoryRuntimePersistence())
        seedOrderAuthorization(service, "trader-1")

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
        seedReferenceData(service)
        seedOrderAuthorization(service, "trader-1")
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
        assertEquals(listOf(1L, 2L, 3L), events.map { it.sequenceNumber })
        assertEquals("cmd-seq-1", events.first().causationId)
        assertTrue(events.drop(1).all { it.causationId == events.first().eventId })
        assertTrue(events.all { it.traceId == "trace-seq-1" })
    }

    @Test
    fun submitOrderRejectsWhenReferenceDataMissing() {
        val service = OrderApplicationService(RecordingEngineGateway(), InMemoryRuntimePersistence())
        seedOrderAuthorization(service, "trader-1")
        val result = service.submitOrder(
            SubmitOrderCommand(
                commandId = "cmd-missing-ref-1",
                traceId = "trace-missing-ref-1",
                causationId = "",
                correlationId = "corr-missing-ref-1",
                actorId = "trader-1",
                occurredAt = "2026-03-14T18:00:00Z",
                orderId = "ord-missing-ref-1",
                instrumentId = "UNKNOWN",
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
        assertEquals("REFERENCE_DATA_ERROR", result.rejected?.code)
    }

    @Test
    fun submitOrderRejectsWhenAccountDoesNotBelongToParticipantBeforeEngineCall() {
        val gateway = RecordingEngineGateway()
        val service = OrderApplicationService(gateway, InMemoryRuntimePersistence())
        service.createInstrument(Instrument("AAPL", "AAPL"))
        service.createParticipant(Participant("participant-1", "Participant 1"))
        service.createParticipant(Participant("participant-2", "Participant 2"))
        service.createAccount(Account("account-2", "participant-2"))
        seedOrderAuthorization(service, "trader-1")

        val result = service.submitOrder(
            SubmitOrderCommand(
                commandId = "cmd-account-mismatch-1",
                traceId = "trace-account-mismatch-1",
                causationId = "",
                correlationId = "corr-account-mismatch-1",
                actorId = "trader-1",
                occurredAt = "2026-03-14T18:00:00Z",
                orderId = "ord-account-mismatch-1",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-2",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "150250000000",
                currency = "USD",
                timeInForce = "DAY"
            )
        )

        assertNotNull(result.rejected)
        assertEquals("REFERENCE_DATA_ERROR", result.rejected?.code)
        assertEquals("accountId does not belong to participantId", result.rejected?.reason)
        assertEquals(0, gateway.submitCalls)
    }
}

private fun seedReferenceData(service: OrderApplicationService) {
    service.createInstrument(Instrument("AAPL", "AAPL"))
    service.createParticipant(Participant("participant-1", "Participant 1"))
    service.createAccount(Account("account-1", "participant-1"))
}

private fun seedOrderAuthorization(
    service: OrderApplicationService,
    actorId: String,
    permissions: List<String> = listOf(Permission.ORDER_SUBMIT, Permission.ORDER_CANCEL, Permission.ORDER_MODIFY)
) {
    service.createRole(RoleDefinition("order_trader", permissions))
    service.assignRole(ActorRoleBinding(actorId, "order_trader"))
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

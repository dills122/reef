package com.reef.platform.application

import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.EngineClient
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.reef.platform.infrastructure.persistence.RuntimePersistence

class OrderApplicationService(
    private val engineGateway: EngineGateway = EngineClient(),
    private val runtimePersistence: RuntimePersistence = InMemoryRuntimePersistence()
) {
    private val eventProducer = "platform-runtime"
    private val eventSchemaVersion = "v1"

    fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        val result = engineGateway.submitOrder(command)
        val traceId = command.traceId.ifBlank { command.orderId }

        val accepted = result.accepted
        if (accepted != null) {
            runtimePersistence.saveAcceptedOrder(
                PersistedOrder(
                    orderId = command.orderId,
                    engineOrderId = accepted.engineOrderId,
                    instrumentId = command.instrumentId,
                    participantId = command.participantId,
                    accountId = command.accountId,
                    side = command.side,
                    orderType = command.orderType,
                    quantityUnits = command.quantityUnits,
                    limitPrice = command.limitPrice,
                    currency = command.currency,
                    timeInForce = command.timeInForce,
                    acceptedAt = accepted.occurredAt
                )
            )
            runtimePersistence.saveExecutions(result.executions)
            runtimePersistence.saveTrades(result.trades)
            runtimePersistence.saveEvent(
                RuntimeEvent(
                    eventId = accepted.eventId,
                    eventType = "OrderAccepted",
                    orderId = accepted.orderId,
                    traceId = traceId,
                    causationId = command.commandId,
                    correlationId = command.correlationId,
                    producer = eventProducer,
                    schemaVersion = eventSchemaVersion,
                    occurredAt = accepted.occurredAt
                )
            )
            result.executions.forEach { execution ->
                runtimePersistence.saveEvent(
                    RuntimeEvent(
                        eventId = execution.eventId,
                        eventType = "ExecutionCreated",
                        orderId = execution.orderId,
                        traceId = traceId,
                        causationId = accepted.eventId,
                        correlationId = command.correlationId,
                        producer = eventProducer,
                        schemaVersion = eventSchemaVersion,
                        occurredAt = execution.occurredAt
                    )
                )
            }
            result.trades.forEach { trade ->
                runtimePersistence.saveEvent(
                    RuntimeEvent(
                        eventId = trade.eventId,
                        eventType = "TradeCreated",
                        orderId = command.orderId,
                        traceId = traceId,
                        causationId = accepted.eventId,
                        correlationId = command.correlationId,
                        producer = eventProducer,
                        schemaVersion = eventSchemaVersion,
                        occurredAt = trade.occurredAt
                    )
                )
            }
        } else {
            val rejected = result.rejected
            if (rejected != null) {
                runtimePersistence.saveEvent(
                    RuntimeEvent(
                        eventId = rejected.eventId,
                        eventType = "OrderRejected",
                        orderId = rejected.orderId,
                        traceId = traceId,
                        causationId = command.commandId,
                        correlationId = command.correlationId,
                        producer = eventProducer,
                        schemaVersion = eventSchemaVersion,
                        occurredAt = rejected.occurredAt
                    )
                )
            }
        }

        return result
    }

    fun persistedOrder(orderId: String) = runtimePersistence.acceptedOrder(orderId)

    fun persistedOrders() = runtimePersistence.acceptedOrders()

    fun persistedExecutions(orderId: String) = runtimePersistence.executionsForOrder(orderId)

    fun persistedTrades(orderId: String) = runtimePersistence.tradesForOrder(orderId)

    fun persistedTrades() = runtimePersistence.trades()

    fun persistedEvents(orderId: String) = runtimePersistence.eventsForOrder(orderId)

    fun persistedTraceEvents(traceId: String) = runtimePersistence.eventsForTrace(traceId)

    fun events() = runtimePersistence.events()
}

package com.reef.platform.application

import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.Account
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.Participant
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.infrastructure.engine.defaultEngineGateway
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.reef.platform.infrastructure.persistence.PostgresRuntimePersistence
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.reef.platform.infrastructure.persistence.RuntimePersistence

class OrderApplicationService(
    private val engineGateway: EngineGateway = defaultEngineGateway(),
    private val runtimePersistence: RuntimePersistence = defaultRuntimePersistence()
) {
    private val eventProducer = "platform-runtime"
    private val eventSchemaVersion = "v1"

    fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        val existingResult = runtimePersistence.submitResult(command.commandId)
        if (existingResult != null) {
            return existingResult
        }
        val traceId = traceId(command.traceId, command.orderId)

        val validationError = validateReferenceData(command)
        if (validationError != null) {
            runtimePersistence.saveSubmitResult(command.commandId, validationError)
            appendLifecycleEvent(
                command.orderId,
                command.commandId,
                command.correlationId,
                traceId,
                validationError.accepted,
                validationError.rejected,
                "OrderAccepted",
                "OrderRejected"
            )
            return validationError
        }

        val result = engineGateway.submitOrder(command)
        runtimePersistence.saveSubmitResult(command.commandId, result)

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
            val lifecycleEvents = ArrayList<RuntimeEvent>(
                1 + result.executions.size + result.trades.size
            )
            lifecycleEvents.add(
                lifecycleEvent(
                    eventId = accepted.eventId,
                    eventType = "OrderAccepted",
                    orderId = accepted.orderId,
                    traceId = traceId,
                    causationId = command.commandId,
                    correlationId = command.correlationId,
                    occurredAt = accepted.occurredAt
                )
            )
            result.executions.forEach { execution ->
                lifecycleEvents.add(
                    lifecycleEvent(
                        eventId = execution.eventId,
                        eventType = "ExecutionCreated",
                        orderId = execution.orderId,
                        traceId = traceId,
                        causationId = accepted.eventId,
                        correlationId = command.correlationId,
                        occurredAt = execution.occurredAt
                    )
                )
            }
            result.trades.forEach { trade ->
                lifecycleEvents.add(
                    lifecycleEvent(
                        eventId = trade.eventId,
                        eventType = "TradeCreated",
                        orderId = command.orderId,
                        traceId = traceId,
                        causationId = accepted.eventId,
                        correlationId = command.correlationId,
                        occurredAt = trade.occurredAt
                    )
                )
            }
            runtimePersistence.saveEvents(lifecycleEvents)
        } else {
            val rejected = result.rejected
            if (rejected != null) {
                runtimePersistence.saveEvent(
                    lifecycleEvent(
                        eventId = rejected.eventId,
                        eventType = "OrderRejected",
                        orderId = rejected.orderId,
                        traceId = traceId,
                        causationId = command.commandId,
                        correlationId = command.correlationId,
                        occurredAt = rejected.occurredAt
                    )
                )
            }
        }

        return result
    }

    fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        val existingResult = runtimePersistence.submitResult(command.commandId)
        if (existingResult != null) {
            return existingResult
        }

        val result = engineGateway.cancelOrder(command)
        val traceId = traceId(command.traceId, command.orderId)
        runtimePersistence.saveSubmitResult(command.commandId, result)
        appendLifecycleEvent(
            command.orderId,
            command.commandId,
            command.correlationId,
            traceId,
            result.accepted,
            result.rejected,
            "OrderCancelled",
            "OrderCancelRejected"
        )
        return result
    }

    fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        val existingResult = runtimePersistence.submitResult(command.commandId)
        if (existingResult != null) {
            return existingResult
        }

        val result = engineGateway.modifyOrder(command)
        val traceId = traceId(command.traceId, command.orderId)
        runtimePersistence.saveSubmitResult(command.commandId, result)
        appendLifecycleEvent(
            command.orderId,
            command.commandId,
            command.correlationId,
            traceId,
            result.accepted,
            result.rejected,
            "OrderModified",
            "OrderModifyRejected"
        )
        return result
    }

    private fun appendLifecycleEvent(
        defaultOrderId: String,
        commandId: String,
        correlationId: String,
        traceId: String,
        accepted: EngineOrderAccepted?,
        rejected: EngineOrderRejected?,
        acceptedEventType: String,
        rejectedEventType: String
    ) {
        if (accepted != null) {
            runtimePersistence.saveEvent(
                lifecycleEvent(
                    eventId = accepted.eventId,
                    eventType = acceptedEventType,
                    orderId = accepted.orderId.ifBlank { defaultOrderId },
                    traceId = traceId,
                    causationId = commandId,
                    correlationId = correlationId,
                    occurredAt = accepted.occurredAt
                )
            )
            return
        }

        if (rejected != null) {
            runtimePersistence.saveEvent(
                lifecycleEvent(
                    eventId = rejected.eventId,
                    eventType = rejectedEventType,
                    orderId = rejected.orderId.ifBlank { defaultOrderId },
                    traceId = traceId,
                    causationId = commandId,
                    correlationId = correlationId,
                    occurredAt = rejected.occurredAt
                )
            )
        }
    }

    private fun lifecycleEvent(
        eventId: String,
        eventType: String,
        orderId: String,
        traceId: String,
        causationId: String,
        correlationId: String,
        occurredAt: String
    ): RuntimeEvent {
        return RuntimeEvent(
            eventId = eventId,
            eventType = eventType,
            orderId = orderId,
            traceId = traceId,
            causationId = causationId,
            correlationId = correlationId,
            producer = eventProducer,
            schemaVersion = eventSchemaVersion,
            occurredAt = occurredAt
        )
    }

    private fun traceId(traceId: String, orderId: String): String {
        return traceId.ifBlank { orderId }
    }

    fun persistedOrder(orderId: String) = runtimePersistence.acceptedOrder(orderId)

    fun persistedOrders() = runtimePersistence.acceptedOrders()

    fun persistedExecutions(orderId: String) = runtimePersistence.executionsForOrder(orderId)

    fun persistedTrades(orderId: String) = runtimePersistence.tradesForOrder(orderId)

    fun persistedTrades() = runtimePersistence.trades()

    fun recentTrades(limit: Int) = runtimePersistence.recentTrades(limit)

    fun persistedEvents(orderId: String) = runtimePersistence.eventsForOrder(orderId)

    fun persistedTraceEvents(traceId: String) = runtimePersistence.eventsForTrace(traceId)

    fun events() = runtimePersistence.events()

    fun recentEvents(limit: Int) = runtimePersistence.recentEvents(limit)

    fun createInstrument(instrument: Instrument) = runtimePersistence.saveInstrument(instrument)

    fun createParticipant(participant: Participant) = runtimePersistence.saveParticipant(participant)

    fun createAccount(account: Account) = runtimePersistence.saveAccount(account)

    fun instruments() = runtimePersistence.instruments()

    fun participants() = runtimePersistence.participants()

    fun accounts() = runtimePersistence.accounts()

    private fun validateReferenceData(command: SubmitOrderCommand): SubmitOrderResult? {
        val now = command.occurredAt
        if (!runtimePersistence.hasInstrument(command.instrumentId)) {
            return SubmitOrderResult(
                rejected = EngineOrderRejected(
                    eventId = "evt-reject-missing-instrument-ref-${command.orderId}",
                    orderId = command.orderId,
                    code = "REFERENCE_DATA_ERROR",
                    reason = "instrumentId does not exist",
                    occurredAt = now
                )
            )
        }

        if (!runtimePersistence.hasParticipant(command.participantId)) {
            return SubmitOrderResult(
                rejected = EngineOrderRejected(
                    eventId = "evt-reject-missing-participant-ref-${command.orderId}",
                    orderId = command.orderId,
                    code = "REFERENCE_DATA_ERROR",
                    reason = "participantId does not exist",
                    occurredAt = now
                )
            )
        }

        if (!runtimePersistence.hasAccount(command.accountId)) {
            return SubmitOrderResult(
                rejected = EngineOrderRejected(
                    eventId = "evt-reject-missing-account-ref-${command.orderId}",
                    orderId = command.orderId,
                    code = "REFERENCE_DATA_ERROR",
                    reason = "accountId does not exist",
                    occurredAt = now
                )
            )
        }

        return null
    }
}

private fun defaultRuntimePersistence(): RuntimePersistence {
    val persistence = System.getenv("RUNTIME_PERSISTENCE") ?: "inmemory"
    if (persistence != "postgres") {
        return InMemoryRuntimePersistence()
    }

    val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/reef"
    val user = System.getenv("RUNTIME_POSTGRES_USER") ?: "reef"
    val password = System.getenv("RUNTIME_POSTGRES_PASSWORD") ?: "reef"
    return PostgresRuntimePersistence(RuntimeDataSources.dataSource(jdbcUrl, user, password))
}

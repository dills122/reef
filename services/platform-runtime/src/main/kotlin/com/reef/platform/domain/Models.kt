package com.reef.platform.domain

data class SubmitOrderCommand(
    val commandId: String,
    val traceId: String,
    val causationId: String,
    val correlationId: String,
    val actorId: String,
    val occurredAt: String,
    val orderId: String,
    val instrumentId: String,
    val participantId: String,
    val accountId: String,
    val side: String,
    val orderType: String,
    val quantityUnits: String,
    val limitPrice: String,
    val currency: String,
    val timeInForce: String
)

data class CancelOrderCommand(
    val commandId: String,
    val traceId: String,
    val causationId: String,
    val correlationId: String,
    val actorId: String,
    val occurredAt: String,
    val orderId: String,
    val reason: String
)

data class ModifyOrderCommand(
    val commandId: String,
    val traceId: String,
    val causationId: String,
    val correlationId: String,
    val actorId: String,
    val occurredAt: String,
    val orderId: String,
    val quantityUnits: String,
    val limitPrice: String
)

data class EngineOrderAccepted(
    val eventId: String,
    val orderId: String,
    val engineOrderId: String,
    val occurredAt: String
)

data class EngineOrderRejected(
    val eventId: String,
    val orderId: String,
    val code: String,
    val reason: String,
    val occurredAt: String
)

data class ExecutionCreated(
    val eventId: String,
    val executionId: String,
    val orderId: String,
    val instrumentId: String,
    val quantityUnits: String,
    val executionPrice: String,
    val currency: String,
    val occurredAt: String
)

data class TradeCreated(
    val eventId: String,
    val tradeId: String,
    val executionId: String,
    val buyOrderId: String,
    val sellOrderId: String,
    val instrumentId: String,
    val quantityUnits: String,
    val price: String,
    val currency: String,
    val occurredAt: String
)

data class PublicTradeTapeEntry(
    val sequence: Long,
    val tradeId: String,
    val instrumentId: String,
    val quantityUnits: String,
    val price: String,
    val currency: String,
    val occurredAt: String
)

val SupportedIntradayBarIntervals: Set<String> = setOf("1m", "5m", "15m", "1h")

data class IntradayBar(
    val instrumentId: String,
    val start: String,
    val end: String,
    val open: String,
    val high: String,
    val low: String,
    val close: String,
    val volume: String
)

val NonLifecycleRejectCodes: Set<String> = setOf("AUTHORIZATION_ERROR", "REFERENCE_DATA_ERROR")

data class OwnOrderView(
    val orderId: String,
    val instrumentId: String,
    val side: String,
    val quantityUnits: String,
    val remainingQuantityUnits: String,
    val limitPrice: String,
    val status: String
)

data class OwnExecutionView(
    val executionId: String,
    val orderId: String,
    val instrumentId: String,
    val side: String,
    val quantityUnits: String,
    val executionPrice: String,
    val currency: String,
    val occurredAt: String
)

data class SubmitOrderResult(
    val accepted: EngineOrderAccepted? = null,
    val rejected: EngineOrderRejected? = null,
    val executions: List<ExecutionCreated> = emptyList(),
    val trades: List<TradeCreated> = emptyList()
)

data class PersistedOrder(
    val orderId: String,
    val engineOrderId: String,
    val instrumentId: String,
    val participantId: String,
    val accountId: String,
    val side: String,
    val orderType: String,
    val quantityUnits: String,
    val limitPrice: String,
    val currency: String,
    val timeInForce: String,
    val acceptedAt: String,
    val clientOrderId: String = "",
    val runId: String = "",
    val venueSessionId: String = ""
)

data class Instrument(
    val instrumentId: String,
    val symbol: String
)

data class Participant(
    val participantId: String,
    val name: String
)

data class Account(
    val accountId: String,
    val participantId: String
)

data class RuntimeEvent(
    val eventId: String,
    val eventType: String,
    val orderId: String,
    val traceId: String,
    val causationId: String,
    val correlationId: String,
    val producer: String,
    val schemaVersion: String,
    val sequenceNumber: Long = 0,
    val occurredAt: String,
    val actorId: String = "",
    val payloadJson: String = "{}"
)

data class RoleDefinition(
    val roleId: String,
    val permissions: List<String>
)

data class ActorRoleBinding(
    val actorId: String,
    val roleId: String
)

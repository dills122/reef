package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.JsonCodec
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.NonLifecycleRejectCodes
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated

internal fun CanonicalCommandOutcome.toPersistableSubmitOutcome(
    commandPayloadJson: String = "{}",
    includeFills: Boolean = true
): PersistableSubmitOutcome {
    val resultPayload = JsonCodec.parseLegacyObjectOrEmpty(resultPayloadJson)
    val embeddedAcceptedOrder = resultPayload.obj("acceptedOrder")
    val commandPayload = JsonCodec.parseLegacyObjectOrEmpty(commandPayloadJson)
    val runtimeRunId = commandPayload.string("runId").ifBlank { embeddedAcceptedOrder.string("runId") }
    val traceId = commandPayload.string("traceId").ifBlank { commandId }
    val causationId = commandPayload.string("causationId").ifBlank { commandId }
    val correlationId = commandPayload.string("correlationId").ifBlank { commandId }
    val eventId = jsonString(resultPayloadJson, "eventId").ifBlank { "evt-$commandId" }
    val occurredAt = jsonString(resultPayloadJson, "occurredAt")
    val rejected = resultStatus == "rejected" || resultStatus == "failed"
    val result = if (rejected) {
        SubmitOrderResult(
            rejected = EngineOrderRejected(
                eventId = eventId,
                orderId = orderId,
                code = rejectCode.ifBlank { jsonString(resultPayloadJson, "code") },
                reason = jsonString(resultPayloadJson, "reason"),
                occurredAt = occurredAt
            ),
            executions = if (includeFills) executionsFromResultPayload(resultPayloadJson).map { it.copy(runId = runtimeRunId) } else emptyList(),
            trades = if (includeFills) tradesFromResultPayload(resultPayloadJson).map { it.copy(runId = runtimeRunId) } else emptyList()
        )
    } else {
        SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = eventId,
                orderId = orderId,
                engineOrderId = jsonString(resultPayloadJson, "engineOrderId"),
                occurredAt = occurredAt
            ),
            executions = if (includeFills) executionsFromResultPayload(resultPayloadJson).map { it.copy(runId = runtimeRunId) } else emptyList(),
            trades = if (includeFills) tradesFromResultPayload(resultPayloadJson).map { it.copy(runId = runtimeRunId) } else emptyList()
        )
    }
    val rejectCodeValue = rejectCode.ifBlank { jsonString(resultPayloadJson, "code") }
    val acceptedOrder = if (
        commandType == "SubmitOrder" &&
        (!rejected || rejectCodeValue !in NonLifecycleRejectCodes)
    ) {
        fun orderField(key: String): String {
            return embeddedAcceptedOrder.string(key).ifBlank { commandPayload.string(key) }
        }
        PersistedOrder(
            orderId = orderField("orderId").ifBlank { orderId },
            engineOrderId = if (rejected) "" else orderField("engineOrderId").ifBlank { jsonString(resultPayloadJson, "engineOrderId") },
            instrumentId = orderField("instrumentId"),
            participantId = orderField("participantId"),
            accountId = orderField("accountId"),
            side = orderField("side"),
            orderType = orderField("orderType"),
            quantityUnits = orderField("quantityUnits"),
            limitPrice = orderField("limitPrice"),
            currency = orderField("currency"),
            timeInForce = orderField("timeInForce"),
            acceptedAt = orderField("acceptedAt").ifBlank { occurredAt },
            clientOrderId = orderField("clientOrderId"),
            runId = orderField("runId"),
            venueSessionId = orderField("venueSessionId")
        ).takeIf {
            it.orderId.isNotBlank() &&
            it.instrumentId.isNotBlank() &&
                it.participantId.isNotBlank() &&
                it.accountId.isNotBlank()
        }
    } else {
        null
    }
    return PersistableSubmitOutcome(
        commandId = commandId,
        result = result,
        acceptedOrder = acceptedOrder,
        lifecycleEvents = listOf(
            RuntimeEvent(
                eventId = eventId,
                eventType = lifecycleEventType(commandType, rejected),
                orderId = orderId,
                traceId = traceId,
                causationId = causationId,
                correlationId = correlationId,
                actorId = "",
                producer = "venue-event-batch-projector",
                schemaVersion = "v1",
                occurredAt = occurredAt,
                payloadJson = resultPayloadJson.ifBlank { "{}" },
                runId = runtimeRunId
            )
        ),
        streamSequence = streamSequence
    )
}

private fun lifecycleEventType(commandType: String, rejected: Boolean): String {
    if (rejected) return "OrderRejected"
    return when (commandType) {
        "CancelOrder" -> "OrderCancelled"
        "ModifyOrder" -> "OrderModified"
        else -> "OrderAccepted"
    }
}

private fun executionsFromResultPayload(json: String): List<ExecutionCreated> {
    return JsonCodec.parseLegacyObjectOrEmpty(json).objectDocuments("executions").map { execution ->
        ExecutionCreated(
            eventId = execution.string("eventId"),
            executionId = execution.string("executionId"),
            orderId = execution.string("orderId"),
            instrumentId = execution.string("instrumentId"),
            quantityUnits = execution.string("quantityUnits"),
            executionPrice = execution.string("executionPrice"),
            currency = execution.string("currency"),
            occurredAt = execution.string("occurredAt"),
            liquidityRole = execution.string("liquidityRole").ifBlank { "UNSPECIFIED" }
        )
    }
}

private fun tradesFromResultPayload(json: String): List<TradeCreated> {
    return JsonCodec.parseLegacyObjectOrEmpty(json).objectDocuments("trades").map { trade ->
        TradeCreated(
            eventId = trade.string("eventId"),
            tradeId = trade.string("tradeId"),
            executionId = trade.string("executionId"),
            buyOrderId = trade.string("buyOrderId"),
            sellOrderId = trade.string("sellOrderId"),
            instrumentId = trade.string("instrumentId"),
            quantityUnits = trade.string("quantityUnits"),
            price = trade.string("price"),
            currency = trade.string("currency"),
            occurredAt = trade.string("occurredAt")
        )
    }
}

private fun jsonString(json: String, key: String): String {
    val document = JsonCodec.parseLegacyObjectOrEmpty(json)
    return document.string(key)
        .ifBlank { document.obj("accepted").string(key) }
        .ifBlank { document.obj("rejected").string(key) }
}

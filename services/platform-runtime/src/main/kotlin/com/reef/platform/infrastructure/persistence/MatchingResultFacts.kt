package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.JsonCodec
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.TradeCreated
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.PersistedOrder

internal fun SubmitOrderResult.matchingFactsJson(): String = "{\"executions\":${executions.toJsonArray { it.toJsonObject() }},\"trades\":${trades.toJsonArray { it.toJsonObject() }}}"

internal fun executionsFromResultPayload(json: String): List<ExecutionCreated> {
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

internal fun tradesFromResultPayload(json: String): List<TradeCreated> {
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


internal fun matchingFactsFromResultPayload(payload: String): String {
    val document = JsonCodec.parseLegacyObjectOrEmpty(payload)
    return "{\"executions\":${document.raw("executions").ifBlank { "[]" }},\"trades\":${document.raw("trades").ifBlank { "[]" }}}"
}

internal fun acceptedOrderFromResultPayload(payload: String): PersistedOrder? {
    val order = JsonCodec.parseLegacyObjectOrEmpty(payload).obj("acceptedOrder")
    if (order.string("orderId").isBlank()) return null
    return PersistedOrder(
        order.string("orderId"), order.string("engineOrderId"), order.string("instrumentId"),
        order.string("participantId"), order.string("accountId"), order.string("side"), order.string("orderType"),
        order.string("quantityUnits"), order.string("limitPrice"), order.string("currency"), order.string("timeInForce"),
        order.string("acceptedAt"), order.string("clientOrderId"), order.string("runId"), order.string("venueSessionId")
    )
}

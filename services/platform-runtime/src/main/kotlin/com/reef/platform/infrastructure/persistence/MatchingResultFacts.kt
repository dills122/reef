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
            liquidityRole = execution.string("liquidityRole").ifBlank { "UNSPECIFIED" },
            runId = execution.string("runId")
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
            occurredAt = trade.string("occurredAt"),
            runId = trade.string("runId")
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

internal fun backfillLegacyMatchingResultFacts(statement: java.sql.Statement, names: PostgresRuntimeSqlNames) {
    statement.execute(
        """
        -- Legacy NULL means unknown original facts, not an empty matching result.
        -- Restore only from immutable command facts, never later order projections.
        WITH original_results AS (
          SELECT command_id, result_status, result_payload, 1 AS source_priority
          FROM ${names.canonicalCommandOutcomes}
          UNION ALL
          SELECT command_id, result_status, result_payload, 2 AS source_priority
          FROM ${names.canonicalCommandOutcomesArchive}
          UNION ALL
          SELECT command_id, result_status, result_payload, 3 AS source_priority
          FROM ${names.canonicalCommandResults}
        ), recoverable AS (
          SELECT DISTINCT ON (stored.command_id) stored.command_id, original.result_payload
          FROM ${names.submitResults} stored
          JOIN original_results original ON original.command_id = stored.command_id
          WHERE stored.matching_facts IS NULL
            AND stored.result_type = original.result_status
            AND stored.event_id = COALESCE(original.result_payload #>> '{accepted,eventId}', original.result_payload #>> '{rejected,eventId}')
            AND stored.order_id = COALESCE(original.result_payload #>> '{accepted,orderId}', original.result_payload #>> '{rejected,orderId}')
          ORDER BY stored.command_id, original.source_priority
        )
        UPDATE ${names.submitResults} stored
        SET matching_facts = jsonb_build_object(
              'executions', COALESCE(NULLIF(original.result_payload->'executions', 'null'::jsonb), '[]'::jsonb),
              'trades', COALESCE(NULLIF(original.result_payload->'trades', 'null'::jsonb), '[]'::jsonb)),
            cancelled = COALESCE(stored.cancelled, NULLIF(original.result_payload->'cancelled', 'null'::jsonb))
        FROM recoverable original
        WHERE stored.command_id = original.command_id;
        """.trimIndent()
    )
}

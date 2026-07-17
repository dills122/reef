package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.TradeCreated

internal fun PersistedOrder.toJsonObject(): String = jsonObject(
    "orderId" to orderId,
    "engineOrderId" to engineOrderId,
    "instrumentId" to instrumentId,
    "participantId" to participantId,
    "accountId" to accountId,
    "side" to side,
    "orderType" to orderType,
    "quantityUnits" to quantityUnits,
    "limitPrice" to limitPrice,
    "currency" to currency,
    "timeInForce" to timeInForce,
    "acceptedAt" to acceptedAt,
    "clientOrderId" to clientOrderId,
    "runId" to runId,
    "venueSessionId" to venueSessionId
)

internal fun ExecutionCreated.toJsonObject(): String = jsonObject(
    "eventId" to eventId,
    "executionId" to executionId,
    "orderId" to orderId,
    "instrumentId" to instrumentId,
    "quantityUnits" to quantityUnits,
    "executionPrice" to executionPrice,
    "currency" to currency,
    "occurredAt" to occurredAt
)

internal fun TradeCreated.toJsonObject(): String = jsonObject(
    "eventId" to eventId,
    "tradeId" to tradeId,
    "executionId" to executionId,
    "buyOrderId" to buyOrderId,
    "sellOrderId" to sellOrderId,
    "instrumentId" to instrumentId,
    "quantityUnits" to quantityUnits,
    "price" to price,
    "currency" to currency,
    "occurredAt" to occurredAt
)

internal fun RuntimeEvent.toJsonObject(): String {
    val stringFields = listOf(
        "eventId" to eventId,
        "eventType" to eventType,
        "orderId" to orderId,
        "traceId" to traceId,
        "causationId" to causationId,
        "correlationId" to correlationId,
        "actorId" to actorId,
        "producer" to producer,
        "schemaVersion" to schemaVersion,
        "occurredAt" to occurredAt
    ).joinToString(",") { (key, value) ->
        "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
    }
    return "{$stringFields,\"payloadJson\":${payloadJson.ifBlank { "{}" }}}"
}

internal fun PersistableSubmitOutcome.toJsonObject(): String {
    val accepted = result.accepted
    val rejected = result.rejected
    val resultType = if (accepted != null) "accepted" else "rejected"
    val fields = listOf(
        "commandId" to commandId,
        "resultType" to resultType,
        "eventId" to (accepted?.eventId ?: rejected?.eventId.orEmpty()),
        "orderId" to (accepted?.orderId ?: rejected?.orderId.orEmpty()),
        "engineOrderId" to accepted?.engineOrderId.orEmpty(),
        "code" to rejected?.code.orEmpty(),
        "reason" to rejected?.reason.orEmpty(),
        "occurredAt" to (accepted?.occurredAt ?: rejected?.occurredAt.orEmpty()),
        "streamSequence" to streamSequence.toString()
    ).joinToString(",") { (key, value) ->
        "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
    }
    return "{$fields," +
        "\"acceptedOrder\":${acceptedOrder?.toJsonObject() ?: "null"}," +
        "\"executions\":${result.executions.toJsonArray { it.toJsonObject() }}," +
        "\"trades\":${result.trades.toJsonArray { it.toJsonObject() }}," +
        "\"events\":${lifecycleEvents.toJsonArray { it.toJsonObject() }}}"
}

internal fun CanonicalSubmitOutcome.toJsonObject(): String {
    val fields = listOf(
        "runId" to runId,
        "venueSessionId" to venueSessionId,
        "partitionId" to partitionId.toString(),
        "partitionSequence" to partitionSequence.toString(),
        "streamName" to streamName,
        "streamSequence" to streamSequence.toString(),
        "commandId" to commandId,
        "idempotencyKey" to idempotencyKey,
        "payloadHash" to payloadHash,
        "instrumentId" to instrumentId,
        "commandType" to commandType,
        "resultStatus" to resultStatus,
        "rejectCode" to rejectCode,
        "acceptedAt" to acceptedAt,
        "completedAt" to completedAt,
        "engineShardId" to engineShardId
    ).joinToString(",") { (key, value) ->
        "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
    }
    return "{$fields," +
        "\"resultPayload\":${outcome.toJsonObject()}," +
        "\"events\":${outcome.lifecycleEvents.toJsonArray { it.toJsonObject() }}}"
}

internal fun VenueEventBatchFact.toJsonObject(): String {
    val fields = listOf(
        "batchId" to batchId,
        "shardId" to shardId,
        "partition" to partition.toString(),
        "commandStream" to commandStream,
        "eventStream" to eventStream,
        "firstSequence" to firstSequence.toString(),
        "lastSequence" to lastSequence.toString(),
        "commandCount" to commandCount.toString(),
        "createdAt" to createdAt,
        "payloadChecksum" to payloadChecksum,
        "payloadFormat" to payloadFormat,
        "payloadVersion" to payloadVersion
    ).joinToString(",") { (key, value) ->
        "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
    }
    return "{$fields,\"outcomes\":${outcomes.toJsonArray { it.toJsonObject() }}}"
}

internal fun VenueCommandOutcomeFact.toJsonObject(): String {
    val fields = listOf(
        "commandId" to commandId,
        "commandType" to commandType,
        "streamSequence" to streamSequence.toString(),
        "deliveredCount" to deliveredCount.toString(),
        "payloadHash" to payloadHash,
        "instrumentId" to instrumentId,
        "orderId" to orderId,
        "status" to resultStatus,
        "rejectCode" to rejectCode
    ).joinToString(",") { (key, value) ->
        "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
    }
    return "{$fields,\"result\":${resultPayloadJson.ifBlank { "{}" }}}"
}

internal fun <T> List<T>.toJsonArray(toObject: (T) -> String): String {
    if (isEmpty()) return "[]"
    return joinToString(prefix = "[", postfix = "]") { toObject(it) }
}

internal fun jsonObject(vararg fields: Pair<String, String>): String {
    return fields.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
    }
}

internal fun escapeJson(value: String): String {
    return buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}

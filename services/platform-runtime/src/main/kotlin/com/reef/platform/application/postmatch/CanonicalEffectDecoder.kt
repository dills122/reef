package com.reef.platform.application.postmatch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper

/** Immutable identity of one retained canonical command outcome. */
data class CanonicalOutcomeSource(
    val eventStream: String,
    val partitionId: Int,
    val streamSequence: Long,
    val batchId: String,
    val commandId: String,
    val commandType: String,
    val payloadHash: String,
    val instrumentId: String,
    val orderId: String,
    val resultStatus: String,
    val resultPayloadJson: String
)

data class CanonicalEffectPosition(
    val eventStream: String,
    val partitionId: Int,
    val streamSequence: Long,
    val effectOrdinal: Int
)

data class CanonicalEffectEnvelope(
    val position: CanonicalEffectPosition,
    val batchId: String,
    val commandId: String,
    val commandType: String,
    val payloadHash: String,
    val effect: CanonicalEffect
)

sealed interface CanonicalEffect {
    data class Accepted(
        val eventId: String,
        val orderId: String,
        val occurredAt: String,
        val newOrder: CanonicalOrderIdentity?
    ) : CanonicalEffect

    data class Rejected(
        val eventId: String,
        val orderId: String,
        val code: String,
        val reason: String,
        val occurredAt: String
    ) : CanonicalEffect

    data class Failed(val code: String, val reason: String) : CanonicalEffect

    data class Execution(
        val eventId: String,
        val executionId: String,
        val orderId: String,
        val instrumentId: String,
        val quantityUnits: String,
        val price: String,
        val currency: String,
        val occurredAt: String,
        val liquidityRole: String
    ) : CanonicalEffect

    data class Trade(
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
    ) : CanonicalEffect

    data class OrderStateChanged(
        val orderId: String,
        val instrumentId: String,
        val side: String,
        val status: String,
        val originalQuantity: String,
        val remainingQuantity: String,
        val limitPrice: String,
        val currency: String,
        val occurredAt: String
    ) : CanonicalEffect
}

data class CanonicalOrderIdentity(
    val orderId: String,
    val engineOrderId: String,
    val clientOrderId: String,
    val runId: String,
    val venueSessionId: String,
    val instrumentId: String,
    val participantId: String,
    val accountId: String,
    val side: String,
    val orderType: String,
    val quantityUnits: String,
    val limitPrice: String,
    val currency: String,
    val timeInForce: String,
    val acceptedAt: String
)

/** Pure v1 decoder. No database read, clock, or consumer-specific state. */
class CanonicalEffectDecoder {
    private val mapper = JsonMapper.builder().build()

    fun decode(source: CanonicalOutcomeSource): List<CanonicalEffectEnvelope> {
        require(source.eventStream.isNotBlank() && source.partitionId >= 0 && source.streamSequence >= 0) {
            "canonical effect source position is incomplete"
        }
        require(source.batchId.isNotBlank() && source.payloadHash.isNotBlank()) {
            "canonical effect source identity is incomplete"
        }
        require(source.resultStatus == "failed" || source.commandId.isNotBlank()) {
            "business outcome lacks command ID"
        }
        val result = try {
            mapper.readTree(source.resultPayloadJson)
        } catch (ex: Exception) {
            throw IllegalArgumentException("invalid canonical result payload", ex)
        }
        require(result != null && result.isObject) { "canonical result payload must be an object" }
        require(result.requiredInt("effectVersion") == 1) { "unsupported canonical effect version" }

        val effects = mutableListOf<CanonicalEffect>()
        val eventIds = mutableSetOf<String>()
        fun addEvent(eventId: String, effect: CanonicalEffect) {
            require(eventId.isNotBlank() && eventIds.add(eventId)) { "duplicate or missing canonical event ID" }
            effects += effect
        }

        val accepted = result.optionalObject("accepted")
        val rejected = result.optionalObject("rejected")
        when (source.resultStatus) {
            "accepted" -> {
                require(accepted != null && rejected == null) { "accepted outcome payload conflicts with status" }
                require(source.instrumentId.isNotBlank()) { "accepted outcome lacks instrument ID" }
                val orderId = accepted.requiredText("orderId")
                require(orderId == source.orderId) { "accepted order ID conflicts with canonical source" }
                val newOrder = result.optionalObject("acceptedOrder")?.toOrderIdentity()
                if (source.commandType == "SubmitOrder") {
                    require(newOrder != null && newOrder.orderId == orderId) {
                        "accepted submit lacks matching immutable order identity"
                    }
                    require(newOrder.instrumentId == source.instrumentId) { "accepted order instrument conflicts with canonical source" }
                } else {
                    require(source.commandType == "ModifyOrder" || source.commandType == "CancelOrder") {
                        "unsupported accepted command type"
                    }
                    require(newOrder == null) { "modify/cancel cannot introduce an order identity" }
                }
                val eventId = accepted.requiredText("eventId")
                addEvent(eventId, CanonicalEffect.Accepted(eventId, orderId, accepted.requiredText("occurredAt"), newOrder))
            }
            "rejected" -> {
                require(rejected != null && accepted == null) { "rejected outcome payload conflicts with status" }
                require(result.objects("executions").isEmpty() && result.objects("trades").isEmpty() &&
                    result.objects("orderStates").isEmpty()) { "rejected command carries business effects" }
                val eventId = rejected.requiredText("eventId")
                addEvent(eventId, CanonicalEffect.Rejected(
                    eventId, rejected.requiredText("orderId"), rejected.requiredText("code"),
                    rejected.requiredText("reason"), rejected.requiredText("occurredAt")
                ))
            }
            "failed" -> {
                require(accepted == null && rejected != null) { "failed outcome payload conflicts with status" }
                require(result.objects("executions").isEmpty() && result.objects("trades").isEmpty() &&
                    result.objects("orderStates").isEmpty()) { "failed command carries business effects" }
                effects += CanonicalEffect.Failed(rejected.requiredText("code"), rejected.requiredText("reason"))
            }
            else -> throw IllegalArgumentException("unsupported canonical result status")
        }

        if (source.resultStatus == "accepted") {
            val executions = result.objects("executions").map { it.toExecution() }
            val trades = result.objects("trades").map { it.toTrade() }
            require(executions.size == trades.size * 2) { "trade/execution count mismatch" }
            val byExecutionId = executions.associateBy { it.executionId }
            require(byExecutionId.size == executions.size) { "duplicate execution ID" }
            for (trade in trades) {
                val buy = byExecutionId["${trade.executionId}-buy"]
                val sell = byExecutionId["${trade.executionId}-sell"]
                require(buy != null && sell != null && buy.orderId == trade.buyOrderId &&
                    sell.orderId == trade.sellOrderId && buy.quantityUnits == trade.quantityUnits &&
                    sell.quantityUnits == trade.quantityUnits && buy.price == trade.price &&
                    sell.price == trade.price && buy.currency == trade.currency && sell.currency == trade.currency &&
                    buy.instrumentId == trade.instrumentId && sell.instrumentId == trade.instrumentId &&
                    setOf(buy.liquidityRole, sell.liquidityRole) == setOf("MAKER", "TAKER")) {
                    "trade lacks matching maker/taker execution pair"
                }
                addEvent(buy.eventId, buy)
                addEvent(sell.eventId, sell)
                addEvent(trade.eventId, trade)
            }
            val states = result.objects("orderStates").map { it.toOrderStateChanged() }
            require(states.isNotEmpty() && states.first().orderId == source.orderId &&
                states.map { it.orderId }.toSet().size == states.size &&
                states.all { it.instrumentId == source.instrumentId }) {
                "accepted command lacks unique incoming order state"
            }
            val stateIds = states.map { it.orderId }.toSet()
            require(executions.all { it.orderId in stateIds } &&
                executions.all { it.instrumentId == source.instrumentId } &&
                trades.all { it.instrumentId == source.instrumentId &&
                    it.buyOrderId in stateIds && it.sellOrderId in stateIds }) {
                "changed maker/taker order state is missing"
            }
            effects += states
        }

        return effects.mapIndexed { ordinal, effect ->
            CanonicalEffectEnvelope(
                CanonicalEffectPosition(source.eventStream, source.partitionId, source.streamSequence, ordinal),
                source.batchId, source.commandId, source.commandType, source.payloadHash, effect
            )
        }
    }
}

private fun JsonNode.requiredText(key: String): String {
    val field = get(key)
    require(field != null && field.isTextual && field.textValue().isNotBlank()) { "missing or invalid $key" }
    return field.textValue()
}

private fun JsonNode.textOrEmpty(key: String): String {
    val field = get(key) ?: return ""
    require(field.isTextual) { "invalid $key" }
    return field.textValue()
}

private fun JsonNode.requiredInt(key: String): Int {
    val field = get(key)
    require(field != null && field.isIntegralNumber && field.canConvertToInt()) { "missing or invalid $key" }
    return field.intValue()
}

private fun JsonNode.optionalObject(key: String): JsonNode? {
    val field = get(key) ?: return null
    require(field.isObject) { "invalid $key" }
    return field
}

private fun JsonNode.objects(key: String): List<JsonNode> {
    val field = get(key) ?: return emptyList()
    require(field.isArray && field.all { it.isObject }) { "invalid $key" }
    return field.toList()
}

private fun JsonNode.toOrderIdentity() = CanonicalOrderIdentity(
    orderId = requiredText("orderId"),
    engineOrderId = requiredText("engineOrderId"),
    clientOrderId = textOrEmpty("clientOrderId"),
    runId = textOrEmpty("runId"),
    venueSessionId = requiredText("venueSessionId"),
    instrumentId = requiredText("instrumentId"),
    participantId = requiredText("participantId"),
    accountId = requiredText("accountId"),
    side = requiredText("side"),
    orderType = requiredText("orderType"),
    quantityUnits = requiredText("quantityUnits"),
    limitPrice = requiredText("limitPrice"),
    currency = requiredText("currency"),
    timeInForce = requiredText("timeInForce"),
    acceptedAt = requiredText("acceptedAt")
)

private fun JsonNode.toExecution() = CanonicalEffect.Execution(
    eventId = requiredText("eventId"),
    executionId = requiredText("executionId"),
    orderId = requiredText("orderId"),
    instrumentId = requiredText("instrumentId"),
    quantityUnits = requiredText("quantityUnits"),
    price = requiredText("executionPrice"),
    currency = requiredText("currency"),
    occurredAt = requiredText("occurredAt"),
    liquidityRole = requiredText("liquidityRole")
)

private fun JsonNode.toTrade() = CanonicalEffect.Trade(
    eventId = requiredText("eventId"),
    tradeId = requiredText("tradeId"),
    executionId = requiredText("executionId"),
    buyOrderId = requiredText("buyOrderId"),
    sellOrderId = requiredText("sellOrderId"),
    instrumentId = requiredText("instrumentId"),
    quantityUnits = requiredText("quantityUnits"),
    price = requiredText("price"),
    currency = requiredText("currency"),
    occurredAt = requiredText("occurredAt")
)

private fun JsonNode.toOrderStateChanged() = CanonicalEffect.OrderStateChanged(
    orderId = requiredText("orderId"),
    instrumentId = requiredText("instrumentId"),
    side = requiredText("side"),
    status = requiredText("status"),
    originalQuantity = requiredText("originalQuantity"),
    remainingQuantity = requiredText("remainingQuantity"),
    limitPrice = requiredText("limitPrice"),
    currency = requiredText("currency"),
    occurredAt = requiredText("lastUpdatedAt")
)

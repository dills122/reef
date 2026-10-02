package com.reef.platform.calcify

import com.reef.platform.api.JsonDocument
import reef.contracts.calcify.v1.*
import reef.contracts.orderexecution.v1.*
import java.time.OffsetDateTime

/** Pure source decoding and immutable context assembly; no broker/store/SQL effects. */
internal object MatchContextResolver {
    data class Batch(val acceptedOrders: List<AcceptedOrderSourceV1>, val trades: List<TradeSourceV1>)

    fun parseBatch(payload: String, topic: String, topicId: String, generation: Int, partition: Int, offset: Long): Batch =
        parseChecked(CalcifySourceBatch.checked(payload, topic, partition), topic, topicId, generation, partition, offset)

    fun parseBatch(payload: ByteArray, topic: String, topicId: String, generation: Int, partition: Int, offset: Long): Batch =
        parseChecked(CalcifySourceBatch.checked(payload, topic, partition), topic, topicId, generation, partition, offset)

    private fun parseChecked(root: JsonDocument, topic: String, topicId: String, generation: Int, partition: Int, offset: Long): Batch {
        CommitmentId(generation, partition, offset, 0)
        require(topicId.isNotBlank()) { "missing source topic identity" }
        val orders = ArrayList<AcceptedOrderSourceV1>()
        val trades = ArrayList<TradeSourceV1>()
        root.strictObjectDocuments("outcomes").forEachIndexed { ordinal, outcome ->
            val source = SourceProvenanceV1.newBuilder()
                .setSourceGeneration(generation).setSourcePartition(partition).setSourceOffset(offset)
                .setOutcomeOrdinal(ordinal).setSourceTopic(topic).setSourceTopicId(topicId)
                .setBatchId(root.strictTextField("batchId")).setBatchChecksum(root.strictTextField("payloadChecksum"))
                .setCommandId(outcome.strictTextField("commandId")).build()
            val result = outcome.strictObject("result")
            if (outcome.strictTextField("commandType") == "SubmitOrder" && outcome.strictTextField("status") == "accepted") {
                require(!result.has("rejected")) { "contradictory acceptance" }
                val accepted = result.strictObject("accepted")
                val fact = acceptedFact(result.strictObject("acceptedOrder"))
                val acceptance = OrderAccepted.newBuilder().setEventId(accepted.strictTextField("eventId"))
                    .setOrderId(accepted.strictTextField("orderId")).setEngineOrderId(accepted.strictTextField("engineOrderId"))
                    .setOccurredAt(timestamp(accepted,"occurredAt")).build()
                require(acceptance.orderId == fact.orderId && acceptance.engineOrderId == fact.engineOrderId && acceptance.occurredAt == fact.acceptedAt) { "acceptance identity mismatch" }
                require(outcome.strictTextField("orderId") == fact.orderId && outcome.strictTextField("instrumentId") == fact.instrumentId) { "acceptance outcome scope mismatch" }
                orders += AcceptedOrderSourceV1.newBuilder().setFact(fact).setAcceptance(acceptance).setSource(source).build()
            }
            for (trade in result.strictObjectDocuments("trades", required = false)) {
                require(outcome.strictTextField("status") == "accepted" && result.has("accepted") && !result.has("rejected")) { "trade in rejected outcome" }
                val fact = TradeCreated.newBuilder().setEventId(trade.strictTextField("eventId"))
                    .setTradeId(trade.strictTextField("tradeId")).setExecutionId(trade.strictTextField("executionId"))
                    .setBuyOrderId(trade.strictTextField("buyOrderId")).setSellOrderId(trade.strictTextField("sellOrderId"))
                    .setInstrumentId(trade.strictTextField("instrumentId"))
                    .setQuantity(OrderQuantity.newBuilder().setUnits(positive(trade,"quantityUnits")))
                    .setPrice(Price.newBuilder().setNanos(positive(trade,"price")).setCurrency(trade.strictTextField("currency")))
                    .setOccurredAt(timestamp(trade,"occurredAt")).build()
                require(outcome.strictTextField("instrumentId") == fact.instrumentId) { "trade outcome instrument mismatch" }
                trades += TradeSourceV1.newBuilder().setFact(fact).setSource(source).build()
            }
        }
        return Batch(orders, trades)
    }

    private fun acceptedFact(row: JsonDocument): AcceptedOrderFactV1 = AcceptedOrderFactV1.newBuilder()
        .setOrderId(row.strictTextField("orderId")).setEngineOrderId(row.strictTextField("engineOrderId"))
        .setClientOrderId(optionalText(row, "clientOrderId"))
        .setRunId(row.strictTextField("runId")).setVenueSessionId(row.strictTextField("venueSessionId"))
        .setInstrumentId(row.strictTextField("instrumentId")).setParticipantId(row.strictTextField("participantId"))
        .setAccountId(row.strictTextField("accountId"))
        .setSide(when (row.strictTextField("side")) { "BUY" -> OrderSide.ORDER_SIDE_BUY; "SELL" -> OrderSide.ORDER_SIDE_SELL; else -> errorFact("side") })
        .setOrderType(when (row.strictTextField("orderType")) { "LIMIT" -> OrderType.ORDER_TYPE_LIMIT; else -> errorFact("orderType") })
        .setQuantityUnits(positive(row,"quantityUnits")).setLimitPrice(positive(row,"limitPrice"))
        .setCurrency(row.strictTextField("currency"))
        .setTimeInForce(when (row.strictTextField("timeInForce")) { "DAY" -> TimeInForce.TIME_IN_FORCE_DAY; "IOC" -> TimeInForce.TIME_IN_FORCE_IOC; else -> errorFact("timeInForce") })
        .setAcceptedAt(timestamp(row,"acceptedAt")).build()

    private fun optionalText(row: JsonDocument, field: String): String {
        if (!row.has(field)) return ""
        require(row.raw(field).startsWith("\"")) { "invalid text $field" }
        return row.string(field)
    }
    private fun errorFact(field: String): Nothing = throw IllegalArgumentException("unsupported accepted fact $field")
    private fun positive(row: JsonDocument, field: String): String = row.strictTextField(field).also {
        require((it.toLongOrNull() ?: 0) > 0) { "invalid positive integer $field" }
    }
    private fun timestamp(row: JsonDocument, field: String): String = row.strictTextField(field).also {
        try { OffsetDateTime.parse(it) } catch (ex: java.time.format.DateTimeParseException) { throw IllegalArgumentException("invalid timestamp $field", ex) }
    }

    /** Same immutable acceptance replay preserves earliest source coordinates. New acceptance is a conflict. */
    fun mergeAcceptance(prior: AcceptedOrderSourceV1?, next: AcceptedOrderSourceV1): AcceptedOrderSourceV1 {
        if (prior == null) return next
        require(prior.fact == next.fact && prior.acceptance == next.acceptance && prior.source.commandId == next.source.commandId && sameLane(prior.source,next.source)) { "accepted fact conflict" }
        return if (beforeOrEqual(prior.source,next.source)) prior else next
    }

    fun resolve(passed: CommitmentVerificationPassed, trade: TradeSourceV1, buy: AcceptedOrderSourceV1?, sell: AcceptedOrderSourceV1?): MatchContextResolvedV1 {
        require(buy != null && sell != null) { "missing accepted order" }
        val id=passed.commitmentId; val target=trade.source; val fact=trade.fact
        require(target.sourceGeneration == id.sourceGeneration && target.sourcePartition == id.sourcePartition && target.sourceOffset == id.sourceOffset) { "target provenance mismatch" }
        require(buy.fact.side == OrderSide.ORDER_SIDE_BUY && sell.fact.side == OrderSide.ORDER_SIDE_SELL) { "side mismatch" }
        require(buy.fact.runId.isNotBlank() && buy.fact.runId == sell.fact.runId && buy.fact.venueSessionId == sell.fact.venueSessionId && buy.fact.instrumentId == sell.fact.instrumentId && fact.instrumentId == buy.fact.instrumentId) { "scope mismatch" }
        require(fact.buyOrderId == buy.fact.orderId && fact.sellOrderId == sell.fact.orderId && fact.buyOrderId != fact.sellOrderId) { "order reference mismatch" }
        require(fact.price.currency == buy.fact.currency && fact.price.currency == sell.fact.currency) { "currency mismatch" }
        for (row in listOf(buy,sell)) {
            require(sameLane(row.source,target)) { "source generation/lane mismatch" }
            require(beforeOrEqual(row.source,target)) { "acceptance follows trade" }
        }
        return MatchContextResolvedV1.newBuilder().setCommitment(CommitmentSourceV1.newBuilder()
            .setSourceGeneration(id.sourceGeneration).setSourcePartition(id.sourcePartition).setSourceOffset(id.sourceOffset).setTradeOrdinal(id.tradeOrdinal))
            .setPolicyVersion(passed.policyVersion).setRunId(buy.fact.runId).setTrade(trade)
            .setBuyAcceptedOrder(buy).setSellAcceptedOrder(sell).build()
    }

    private fun sameLane(a: SourceProvenanceV1,b: SourceProvenanceV1) = a.sourceGeneration == b.sourceGeneration && a.sourcePartition == b.sourcePartition && a.sourceTopic == b.sourceTopic && a.sourceTopicId == b.sourceTopicId
    private fun beforeOrEqual(a: SourceProvenanceV1,b: SourceProvenanceV1) = a.sourceOffset < b.sourceOffset || (a.sourceOffset == b.sourceOffset && a.outcomeOrdinal <= b.outcomeOrdinal)
}

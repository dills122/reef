package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.Account
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.Participant
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import java.math.BigDecimal
import java.time.Instant

class InMemoryRuntimePersistence : RuntimePersistence {
    private val canonicalSubmitOutcomes = linkedMapOf<String, CanonicalSubmitOutcome>()
    private val venueEventBatches = linkedMapOf<String, VenueEventBatchFact>()
    private val commandOutcomes = linkedMapOf<String, CanonicalCommandOutcome>()
    private val projectionWatermarks = mutableMapOf<String, MutableMap<Int, Long>>()
    private val submitResults = linkedMapOf<String, SubmitOrderResult>()
    private val instruments = linkedMapOf<String, Instrument>()
    private val participants = linkedMapOf<String, Participant>()
    private val accounts = linkedMapOf<String, Account>()
    private val roles = linkedMapOf<String, RoleDefinition>()
    private val actorRoleBindings = mutableListOf<ActorRoleBinding>()
    private val orders = linkedMapOf<String, PersistedOrder>()
    private val executions = mutableListOf<ExecutionCreated>()
    private val trades = mutableListOf<TradeCreated>()
    private val events = mutableListOf<RuntimeEvent>()
    private val traceSequences = mutableMapOf<String, Long>()
    private val marketDataSnapshots = linkedMapOf<String, MarketDataSnapshot>()
    private val orderLifecycleStates = linkedMapOf<String, OrderLifecycleState>()

    override fun saveSubmitResult(commandId: String, result: SubmitOrderResult) {
        submitResults[commandId] = result
    }

    override fun submitResult(commandId: String): SubmitOrderResult? {
        return submitResults[commandId] ?: canonicalSubmitOutcomes[commandId]?.outcome?.result
    }

    override fun saveInstrument(instrument: Instrument) {
        instruments[instrument.instrumentId] = instrument
    }

    override fun saveParticipant(participant: Participant) {
        participants[participant.participantId] = participant
    }

    override fun saveAccount(account: Account) {
        accounts[account.accountId] = account
    }

    override fun saveRole(role: RoleDefinition) {
        roles[role.roleId] = role
    }

    override fun saveActorRoleBinding(binding: ActorRoleBinding) {
        actorRoleBindings.removeIf { it.actorId == binding.actorId && it.roleId == binding.roleId }
        actorRoleBindings.add(binding)
    }

    override fun instruments(): List<Instrument> {
        return instruments.values.toList()
    }

    override fun participants(): List<Participant> {
        return participants.values.toList()
    }

    override fun accounts(): List<Account> {
        return accounts.values.toList()
    }

    override fun roles(): List<RoleDefinition> {
        return roles.values.toList()
    }

    override fun actorRoleBindings(actorId: String): List<ActorRoleBinding> {
        return actorRoleBindings.filter { it.actorId == actorId }
    }

    override fun hasInstrument(instrumentId: String): Boolean {
        return instruments.containsKey(instrumentId)
    }

    override fun hasParticipant(participantId: String): Boolean {
        return participants.containsKey(participantId)
    }

    override fun hasAccount(accountId: String): Boolean {
        return accounts.containsKey(accountId)
    }

    override fun saveAcceptedOrder(order: PersistedOrder) {
        orders[order.orderId] = order
    }

    override fun saveExecutions(executions: List<ExecutionCreated>) {
        this.executions.addAll(executions)
    }

    override fun saveTrades(trades: List<TradeCreated>) {
        this.trades.addAll(trades)
    }

    override fun saveEvent(event: RuntimeEvent) {
        val nextSequence = (traceSequences[event.traceId] ?: 0) + 1
        traceSequences[event.traceId] = nextSequence
        events.add(event.copy(sequenceNumber = nextSequence))
    }

    override fun acceptedOrder(orderId: String): PersistedOrder? {
        return orders[orderId]
    }

    override fun acceptedOrders(): List<PersistedOrder> {
        return orders.values.toList()
    }

    override fun executionsForOrder(orderId: String): List<ExecutionCreated> {
        return executions.filter { it.orderId == orderId }
    }

    override fun tradesForOrder(orderId: String): List<TradeCreated> {
        return trades.filter { it.buyOrderId == orderId || it.sellOrderId == orderId }
    }

    override fun trades(): List<TradeCreated> {
        return trades.toList()
    }

    override fun recentTrades(limit: Int): List<TradeCreated> {
        if (limit <= 0) return emptyList()
        val from = (trades.size - limit).coerceAtLeast(0)
        return trades.subList(from, trades.size).toList()
    }

    override fun eventsForOrder(orderId: String): List<RuntimeEvent> {
        return events.filter { it.orderId == orderId }
    }

    override fun eventsForTrace(traceId: String): List<RuntimeEvent> {
        return events.filter { it.traceId == traceId }
    }

    override fun events(): List<RuntimeEvent> {
        return events.toList()
    }

    override fun recentEvents(limit: Int): List<RuntimeEvent> {
        if (limit <= 0) return emptyList()
        val from = (events.size - limit).coerceAtLeast(0)
        return events.subList(from, events.size).toList()
    }

    override fun appendCanonicalSubmitOutcomes(outcomes: List<CanonicalSubmitOutcome>) {
        outcomes.forEach { outcome ->
            canonicalSubmitOutcomes.putIfAbsent(outcome.commandId, outcome)
        }
    }

    fun canonicalSubmitOutcomes(): List<CanonicalSubmitOutcome> {
        return canonicalSubmitOutcomes.values.toList()
    }

    override fun projectCanonicalSubmitOutcomes(projectionName: String, batchSize: Int, partitions: List<Int>): Long {
        if (batchSize <= 0) return 0
        val partitionSet = partitions.toSet()
        val watermarks = projectionWatermarks.computeIfAbsent(projectionName) { mutableMapOf() }
        val outcomes = canonicalSubmitOutcomes.values
            .filter { outcome -> partitionSet.isEmpty() || outcome.partitionId in partitionSet }
            .filter { outcome -> outcome.partitionSequence > (watermarks[outcome.partitionId] ?: 0L) }
            .groupBy { it.partitionId }
            .flatMap { (_, partitionOutcomes) ->
                partitionOutcomes.sortedBy { it.partitionSequence }.take(batchSize)
            }
            .sortedWith(compareBy<CanonicalSubmitOutcome> { it.partitionSequence }.thenBy { it.partitionId })
            .take(batchSize)
        if (outcomes.isEmpty()) return 0
        persistSubmitOutcomes(outcomes.map { it.outcome })
        outcomes
            .groupBy { it.partitionId }
            .forEach { (partitionId, partitionOutcomes) ->
                watermarks[partitionId] = maxOf(
                    watermarks[partitionId] ?: 0L,
                    partitionOutcomes.maxOf { it.partitionSequence }
                )
            }
        return outcomes.size.toLong()
    }

    override fun projectCanonicalCommandOutcomes(
        projectionName: String,
        batchSize: Int,
        partitions: List<Int>,
        includeFills: Boolean
    ): Long {
        if (batchSize <= 0) return 0
        val partitionSet = partitions.toSet()
        val watermarks = projectionWatermarks.computeIfAbsent(projectionName) { mutableMapOf() }
        val outcomes = commandOutcomes.values
            .filter { outcome -> outcome.commandType in ProjectableCommandTypes }
            .filter { outcome -> partitionSet.isEmpty() || outcome.partition in partitionSet }
            .filter { outcome -> outcome.streamSequence > (watermarks[outcome.partition] ?: 0L) }
            .groupBy { it.partition }
            .flatMap { (_, partitionOutcomes) ->
                partitionOutcomes.sortedBy { it.streamSequence }.take(batchSize)
            }
            .sortedWith(compareBy<CanonicalCommandOutcome> { it.streamSequence }.thenBy { it.partition })
            .take(batchSize)
        if (outcomes.isEmpty()) return 0
        outcomes.forEach { outcome ->
            saveSubmitResult(outcome.commandId, outcome.toSubmitOrderResult())
            saveEvent(outcome.toRuntimeEvent())
        }
        outcomes
            .groupBy { it.partition }
            .forEach { (partitionId, partitionOutcomes) ->
                watermarks[partitionId] = maxOf(
                    watermarks[partitionId] ?: 0L,
                    partitionOutcomes.maxOf { it.streamSequence }
                )
            }
        return outcomes.size.toLong()
    }

    override fun projectionStatus(projectionName: String, partitions: List<Int>, source: String): ProjectionStatus {
        val partitionSet = partitions.toSet()
        val watermarks = projectionWatermarks[projectionName].orEmpty()
        val sourceRows = if (source.isVenueEventBatchProjectionSource()) {
            commandOutcomes.values
                .filter { outcome -> outcome.commandType in ProjectableCommandTypes }
                .filter { outcome -> partitionSet.isEmpty() || outcome.partition in partitionSet }
                .map { outcome -> outcome.partition to outcome.streamSequence }
        } else {
            canonicalSubmitOutcomes.values
                .filter { outcome -> partitionSet.isEmpty() || outcome.partitionId in partitionSet }
                .map { outcome -> outcome.partitionId to outcome.partitionSequence }
        }
        val watermarkRows = sourceRows
            .groupBy({ it.first }, { it.second })
            .map { (partitionId, sequences) ->
                val projected = watermarks[partitionId] ?: 0L
                val canonicalMax = sequences.max()
                val backlogCount = sequences.count { it > projected }.toLong()
                ProjectionWatermark(
                    projectionName = projectionName,
                    partitionId = partitionId,
                    lastPartitionSequence = projected,
                    canonicalMaxPartitionSequence = canonicalMax,
                    lag = backlogCount,
                    updatedAt = "",
                    lastError = ""
                )
            }
            .sortedBy { it.partitionId }
        return ProjectionStatus(
            projectionName = projectionName,
            projectedCount = submitResults.size.toLong(),
            lag = watermarkRows.sumOf { it.lag },
            watermarks = watermarkRows
        )
    }

    override fun materializeVenueEventBatch(batch: VenueEventBatchFact): Long {
        val existing = venueEventBatches[batch.batchId]
        if (existing != null) {
            check(existing.payloadChecksum == batch.payloadChecksum) {
                "venue event batch checksum conflict for batchId ${batch.batchId}"
            }
            return 0
        }
        venueEventBatches[batch.batchId] = batch
        var inserted = 0L
        batch.outcomes.forEach { outcome ->
            val canonical = CanonicalCommandOutcome(
                commandId = outcome.commandId,
                batchId = batch.batchId,
                shardId = batch.shardId,
                partition = batch.partition,
                commandStream = batch.commandStream,
                eventStream = batch.eventStream,
                streamSequence = outcome.streamSequence,
                deliveredCount = outcome.deliveredCount,
                commandType = outcome.commandType,
                payloadHash = outcome.payloadHash,
                instrumentId = outcome.instrumentId,
                orderId = outcome.orderId,
                resultStatus = outcome.resultStatus,
                rejectCode = outcome.rejectCode,
                resultPayloadJson = outcome.resultPayloadJson
            )
            if (commandOutcomes.putIfAbsent(outcome.commandId, canonical) == null) {
                inserted++
            }
        }
        return inserted
    }

    override fun canonicalCommandOutcome(commandId: String): CanonicalCommandOutcome? {
        return commandOutcomes[commandId]
    }

    override fun rebuildOrderLifecycleState(): Long {
        orderLifecycleStates.clear()
        orders.values.forEach { order ->
            val orderExecutions = executions.filter { it.orderId == order.orderId }
            val filledQuantity = orderExecutions
                .mapNotNull { it.quantityUnits.toBigDecimalOrNull() }
                .fold(BigDecimal.ZERO, BigDecimal::add)
            val orderEvents = events
                .filter { it.orderId == order.orderId }
                .sortedWith(compareBy<RuntimeEvent> { it.occurredAt }.thenBy { it.sequenceNumber }.thenBy { it.eventId })
            val latestModify = orderEvents.lastOrNull { it.eventType == "OrderModified" }
            val currentQuantity = jsonString(latestModify?.payloadJson.orEmpty(), "quantityUnits")
                .ifBlank { order.quantityUnits }
            val currentLimitPrice = jsonString(latestModify?.payloadJson.orEmpty(), "limitPrice")
                .ifBlank { order.limitPrice }
            val quantity = currentQuantity.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val remainingQuantity = (quantity - filledQuantity).coerceAtLeast(BigDecimal.ZERO)
            val cancelled = orderEvents.any { it.eventType == "OrderCancelled" }
            val rejected = orderEvents.any { it.eventType == "OrderRejected" }
            val status = when {
                rejected -> "REJECTED"
                cancelled -> "CANCELLED"
                quantity > BigDecimal.ZERO && remainingQuantity == BigDecimal.ZERO -> "FILLED"
                filledQuantity > BigDecimal.ZERO -> "PARTIALLY_FILLED"
                else -> "OPEN"
            }
            orderLifecycleStates[order.orderId] = OrderLifecycleState(
                orderId = order.orderId,
                engineOrderId = order.engineOrderId,
                instrumentId = order.instrumentId,
                participantId = order.participantId,
                accountId = order.accountId,
                side = order.side,
                orderType = order.orderType,
                originalQuantityUnits = order.quantityUnits,
                remainingQuantityUnits = if (cancelled || rejected) "0" else decimalString(remainingQuantity),
                filledQuantityUnits = decimalString(filledQuantity),
                limitPrice = currentLimitPrice,
                currency = order.currency,
                timeInForce = order.timeInForce,
                status = status,
                acceptedAt = order.acceptedAt,
                lastEventAt = orderEvents.lastOrNull()?.occurredAt ?: order.acceptedAt,
                updatedAt = Instant.now().toString()
            )
        }
        return orderLifecycleStates.size.toLong()
    }

    override fun orderLifecycleState(orderId: String): OrderLifecycleState? {
        return orderLifecycleStates[orderId]
    }

    override fun refreshMarketDataSnapshots(projectionName: String, sourceProjectionName: String): Long {
        rebuildOrderLifecycleState()
        val sourceStatus = projectionStatus(sourceProjectionName, source = "venue-event-batch")
        val sourceWatermarks = sourceStatus.watermarks.filter { it.partitionId >= 0 }
        val lastPartitionSequence = sourceWatermarks.maxOfOrNull { it.lastPartitionSequence } ?: 0L
        val updatedAt = sourceWatermarks.lastOrNull { it.updatedAt.isNotBlank() }?.updatedAt ?: Instant.now().toString()
        marketDataSnapshots.keys
            .filter { it.startsWith("$projectionName:") }
            .toList()
            .forEach { marketDataSnapshots.remove(it) }
        orderLifecycleStates.values
            .filter {
                it.orderType == "LIMIT" &&
                    it.limitPrice.toBigDecimalOrNull() != null &&
                    it.remainingQuantityUnits.toBigDecimalOrNull() != null &&
                    it.remainingQuantityUnits.toBigDecimalOrNull()!! > BigDecimal.ZERO &&
                    it.status in OpenLifecycleStatuses
            }
            .groupBy { it.instrumentId }
            .forEach { (instrumentId, instrumentOrders) ->
                val bids = instrumentOrders.filter { it.side == "BUY" }
                val asks = instrumentOrders.filter { it.side == "SELL" }
                val bestBid = bids.maxByOrNull { it.limitPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO }
                val bestAsk = asks.minByOrNull { it.limitPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO }
                val bestBidPrice = bestBid?.limitPrice.orEmpty()
                val bestAskPrice = bestAsk?.limitPrice.orEmpty()
                marketDataSnapshots["$projectionName:$instrumentId"] = MarketDataSnapshot(
                    projectionName = projectionName,
                    sourceProjectionName = sourceProjectionName,
                    instrumentId = instrumentId,
                    bestBidPrice = bestBidPrice,
                    bestBidQuantity = aggregateLifecycleQuantity(bids, bestBidPrice),
                    bestAskPrice = bestAskPrice,
                    bestAskQuantity = aggregateLifecycleQuantity(asks, bestAskPrice),
                    currency = instrumentOrders.firstOrNull { it.currency.isNotBlank() }?.currency.orEmpty(),
                    lastPartitionSequence = lastPartitionSequence,
                    lag = sourceStatus.lag,
                    updatedAt = updatedAt
                )
            }
        return marketDataSnapshots.keys.count { it.startsWith("$projectionName:") }.toLong()
    }

    override fun marketDataSnapshot(instrumentId: String, projectionName: String): MarketDataSnapshot? {
        return marketDataSnapshots["$projectionName:$instrumentId"]
    }

    override fun marketDataDepthSnapshot(
        instrumentId: String,
        levels: Int,
        projectionName: String,
        sourceProjectionName: String
    ): MarketDataDepthSnapshot? {
        val boundedLevels = levels.coerceIn(1, 50)
        rebuildOrderLifecycleState()
        val sourceStatus = projectionStatus(sourceProjectionName, source = "venue-event-batch")
        val sourceWatermarks = sourceStatus.watermarks.filter { it.partitionId >= 0 }
        val lastPartitionSequence = sourceWatermarks.maxOfOrNull { it.lastPartitionSequence } ?: 0L
        val updatedAt = sourceWatermarks.lastOrNull { it.updatedAt.isNotBlank() }?.updatedAt ?: Instant.now().toString()
        val openOrders = orderLifecycleStates.values
            .filter { it.instrumentId == instrumentId }
            .filter {
                it.orderType == "LIMIT" &&
                    it.limitPrice.toBigDecimalOrNull() != null &&
                    it.remainingQuantityUnits.toBigDecimalOrNull() != null &&
                    it.remainingQuantityUnits.toBigDecimalOrNull()!! > BigDecimal.ZERO &&
                    it.status in OpenLifecycleStatuses
            }
        if (openOrders.isEmpty()) return null
        val bids = depthLevels(openOrders.filter { it.side == "BUY" }, descending = true, boundedLevels)
        val asks = depthLevels(openOrders.filter { it.side == "SELL" }, descending = false, boundedLevels)
        return MarketDataDepthSnapshot(
            projectionName = projectionName,
            sourceProjectionName = sourceProjectionName,
            instrumentId = instrumentId,
            bidLevels = bids,
            askLevels = asks,
            currency = openOrders.firstOrNull { it.currency.isNotBlank() }?.currency.orEmpty(),
            levels = boundedLevels,
            lastPartitionSequence = lastPartitionSequence,
            lag = sourceStatus.lag,
            updatedAt = updatedAt
        )
    }

    private fun CanonicalCommandOutcome.toSubmitOrderResult(): SubmitOrderResult {
        return if (resultStatus == "rejected") {
            SubmitOrderResult(
                rejected = EngineOrderRejected(
                    eventId = jsonString(resultPayloadJson, "eventId").ifBlank { "evt-$commandId" },
                    orderId = orderId,
                    code = rejectCode,
                    reason = jsonString(resultPayloadJson, "reason"),
                    occurredAt = jsonString(resultPayloadJson, "occurredAt")
                )
            )
        } else {
            SubmitOrderResult(
                accepted = EngineOrderAccepted(
                    eventId = jsonString(resultPayloadJson, "eventId").ifBlank { "evt-$commandId" },
                    orderId = orderId,
                    engineOrderId = jsonString(resultPayloadJson, "engineOrderId"),
                    occurredAt = jsonString(resultPayloadJson, "occurredAt")
                )
            )
        }
    }

    private fun CanonicalCommandOutcome.toRuntimeEvent(): RuntimeEvent {
        val rejected = resultStatus == "rejected"
        return RuntimeEvent(
            eventId = jsonString(resultPayloadJson, "eventId").ifBlank { "evt-$commandId" },
            eventType = lifecycleEventType(commandType, rejected),
            orderId = orderId,
            traceId = commandId,
            causationId = commandId,
            correlationId = commandId,
            producer = "venue-event-batch-projector",
            schemaVersion = "v1",
            occurredAt = jsonString(resultPayloadJson, "occurredAt"),
            actorId = "",
            payloadJson = resultPayloadJson.ifBlank { "{}" }
        )
    }

    private fun jsonString(json: String, key: String): String {
        val pattern = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return pattern.find(json)?.groupValues?.get(1).orEmpty()
    }

    private fun String.isVenueEventBatchProjectionSource(): Boolean {
        return trim().lowercase() in setOf(
            "venue-event-batch",
            "event-batch",
            "venue-events",
            "canonical-command-outcomes"
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

    private fun aggregateLifecycleQuantity(orders: List<OrderLifecycleState>, price: String): String {
        if (price.isBlank()) return ""
        val total = orders
            .filter { it.limitPrice == price }
            .mapNotNull { it.remainingQuantityUnits.toBigDecimalOrNull() }
            .fold(BigDecimal.ZERO, BigDecimal::add)
        if (total == BigDecimal.ZERO) return ""
        return decimalString(total)
    }

    private fun depthLevels(
        orders: List<OrderLifecycleState>,
        descending: Boolean,
        levels: Int
    ): List<MarketDataDepthLevel> {
        val grouped = orders
            .groupBy { it.limitPrice }
            .mapNotNull { (price, priceOrders) ->
                val numericPrice = price.toBigDecimalOrNull() ?: return@mapNotNull null
                val quantity = priceOrders
                    .mapNotNull { it.remainingQuantityUnits.toBigDecimalOrNull() }
                    .fold(BigDecimal.ZERO, BigDecimal::add)
                if (quantity <= BigDecimal.ZERO) return@mapNotNull null
                Triple(numericPrice, price, decimalString(quantity))
            }
        val sorted = if (descending) {
            grouped.sortedByDescending { it.first }
        } else {
            grouped.sortedBy { it.first }
        }
        return sorted
            .take(levels)
            .map { (_, price, quantity) -> MarketDataDepthLevel(price = price, quantity = quantity) }
    }

    private fun decimalString(value: BigDecimal): String {
        return value.stripTrailingZeros().toPlainString()
    }

    private companion object {
        val ProjectableCommandTypes = setOf("SubmitOrder", "ModifyOrder", "CancelOrder")
        val OpenLifecycleStatuses = setOf("OPEN", "PARTIALLY_FILLED")
    }
}

package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.JsonCodec
import com.reef.platform.domain.Account
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.IntradayBar
import com.reef.platform.domain.OwnExecutionView
import com.reef.platform.domain.OwnOrderView
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.Participant
import com.reef.platform.domain.PublicTradeTapeEntry
import com.reef.platform.domain.PostTradeProfile
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.ScenarioRunPostTradeProfile
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.VenueSessionPostTradeProfile
import java.math.BigDecimal
import java.time.Duration
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
    private val postTradeProfiles = linkedMapOf<String, PostTradeProfile>()
    private var activePostTradeProfileId = ""
    private val scenarioRunPostTradeProfiles = linkedMapOf<String, ScenarioRunPostTradeProfile>()
    private val venueSessionPostTradeProfiles = linkedMapOf<String, VenueSessionPostTradeProfile>()
    private val orders = linkedMapOf<String, PersistedOrder>()
    private val executions = mutableListOf<ExecutionCreated>()
    private val trades = mutableListOf<TradeCreated>()
    private val events = mutableListOf<RuntimeEvent>()
    private val traceSequences = mutableMapOf<String, Long>()
    private val marketDataSnapshots = linkedMapOf<String, MarketDataSnapshot>()
    private val orderLifecycleStates = linkedMapOf<String, OrderLifecycleState>()
    private val orderLifecycleDirty = linkedSetOf<String>()
    private val marketDataSnapshotDirty = linkedSetOf<String>()
    private val intradayBarIntervalDurations = mapOf(
        "1m" to Duration.ofMinutes(1),
        "5m" to Duration.ofMinutes(5),
        "15m" to Duration.ofMinutes(15),
        "1h" to Duration.ofHours(1)
    )
    private val intradayBarOrigin: Instant = Instant.parse("2000-01-01T00:00:00Z")

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

    override fun savePostTradeProfile(profile: PostTradeProfile) {
        postTradeProfiles[profile.profileId] = profile
        if (profile.active) {
            activePostTradeProfileId = profile.profileId
        } else if (activePostTradeProfileId.isBlank()) {
            activePostTradeProfileId = profile.profileId
        }
    }

    override fun postTradeProfiles(): List<PostTradeProfile> {
        return postTradeProfiles.values.map { it.copy(active = it.profileId == activePostTradeProfileId) }
    }

    override fun activePostTradeProfile(): PostTradeProfile {
        val profile = postTradeProfiles[activePostTradeProfileId]
            ?: throw IllegalArgumentException("no active post-trade profile")
        return profile.copy(active = true)
    }

    override fun activatePostTradeProfile(profileId: String): PostTradeProfile {
        val profile = postTradeProfiles[profileId]
            ?: throw IllegalArgumentException("unknown post-trade profile '$profileId'")
        activePostTradeProfileId = profileId
        return profile.copy(active = true)
    }

    override fun saveScenarioRunPostTradeProfile(config: ScenarioRunPostTradeProfile) {
        scenarioRunPostTradeProfiles[config.scenarioRunId] = config
    }

    override fun scenarioRunPostTradeProfileId(scenarioRunId: String): String? {
        return scenarioRunPostTradeProfiles[scenarioRunId]?.postTradeProfileId
    }

    override fun scenarioRunPostTradeProfiles(): List<ScenarioRunPostTradeProfile> {
        return scenarioRunPostTradeProfiles.values.toList()
    }

    override fun saveVenueSessionPostTradeProfile(config: VenueSessionPostTradeProfile) {
        venueSessionPostTradeProfiles[config.venueSessionId] = config
    }

    override fun venueSessionPostTradeProfileId(venueSessionId: String): String? {
        return venueSessionPostTradeProfiles[venueSessionId]?.postTradeProfileId
    }

    override fun venueSessionPostTradeProfiles(): List<VenueSessionPostTradeProfile> {
        return venueSessionPostTradeProfiles.values.toList()
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
        orderLifecycleDirty.add(order.orderId)
    }

    override fun saveExecutions(executions: List<ExecutionCreated>) {
        this.executions.addAll(executions)
        executions.forEach { orderLifecycleDirty.add(it.orderId) }
    }

    override fun saveTrades(trades: List<TradeCreated>) {
        this.trades.addAll(trades)
        trades.forEach {
            orderLifecycleDirty.add(it.buyOrderId)
            orderLifecycleDirty.add(it.sellOrderId)
        }
    }

    override fun saveEvent(event: RuntimeEvent) {
        val nextSequence = (traceSequences[event.traceId] ?: 0) + 1
        traceSequences[event.traceId] = nextSequence
        events.add(event.copy(sequenceNumber = nextSequence))
        if (event.orderId.isNotBlank()) {
            orderLifecycleDirty.add(event.orderId)
        }
    }

    override fun acceptedOrder(orderId: String): PersistedOrder? {
        return orders[orderId]
    }

    override fun acceptedOrders(orderIds: Set<String>): Map<String, PersistedOrder> {
        return orderIds.mapNotNull { orderId ->
            orders[orderId]?.let { orderId to it }
        }.toMap()
    }

    override fun acceptedOrders(): List<PersistedOrder> {
        return orders.values.toList()
    }

    override fun findOrderByClientOrderId(participantId: String, clientOrderId: String): PersistedOrder? {
        return orders.values
            .filter { it.participantId == participantId && it.clientOrderId == clientOrderId }
            .maxByOrNull { it.acceptedAt }
    }

    override fun ordersForParticipant(
        participantId: String,
        openOnly: Boolean,
        instrumentId: String,
        limit: Int
    ): List<OwnOrderView> {
        val boundedLimit = limit.coerceIn(0, 500)
        val views = orders.values
            .filter { it.participantId == participantId }
            .filter { instrumentId.isBlank() || it.instrumentId == instrumentId }
            .mapNotNull { order ->
                val state = orderLifecycleStates[order.orderId] ?: return@mapNotNull null
                if (openOnly && state.status !in OpenLifecycleStatuses) return@mapNotNull null
                OwnOrderView(
                    orderId = order.orderId,
                    instrumentId = order.instrumentId,
                    side = order.side,
                    quantityUnits = state.originalQuantityUnits,
                    remainingQuantityUnits = state.remainingQuantityUnits,
                    limitPrice = state.limitPrice,
                    status = state.status
                )
            }
        return if (boundedLimit > 0) views.take(boundedLimit) else views
    }

    override fun executionsForParticipant(
        participantId: String,
        instrumentId: String,
        limit: Int
    ): List<OwnExecutionView> {
        val boundedLimit = limit.coerceIn(0, 500)
        val participantOrders = orders.values
            .filter { it.participantId == participantId }
            .filter { instrumentId.isBlank() || it.instrumentId == instrumentId }
            .associateBy { it.orderId }
        val views = executions
            .filter { participantOrders.containsKey(it.orderId) }
            .sortedWith(compareBy<ExecutionCreated> { it.occurredAt }.thenBy { it.executionId })
            .mapNotNull { execution ->
                val order = participantOrders[execution.orderId] ?: return@mapNotNull null
                OwnExecutionView(
                    executionId = execution.executionId,
                    orderId = execution.orderId,
                    instrumentId = execution.instrumentId,
                    side = order.side,
                    quantityUnits = execution.quantityUnits,
                    executionPrice = execution.executionPrice,
                    currency = execution.currency,
                    occurredAt = execution.occurredAt
                )
            }
        return if (boundedLimit > 0) views.take(boundedLimit) else views
    }

    override fun executionsForOrder(orderId: String): List<ExecutionCreated> {
        return executions.filter { it.orderId == orderId }
    }

    override fun tradesForOrder(orderId: String): List<TradeCreated> {
        return trades.filter { it.buyOrderId == orderId || it.sellOrderId == orderId }
    }

    override fun tradesForSettlementMaterialization(scenarioRunId: String, venueSessionId: String): List<TradeCreated> {
        return trades.filter { trade ->
            val buyOrder = orders[trade.buyOrderId] ?: return@filter false
            val sellOrder = orders[trade.sellOrderId] ?: return@filter false
            val tradeRunId = sharedSettlementMetadata(buyOrder.runId, sellOrder.runId) ?: return@filter false
            val effectiveScenarioRunId = tradeRunId.ifBlank { scenarioRunId }
            if (effectiveScenarioRunId != scenarioRunId) return@filter false
            val orderVenueSessionId = sharedSettlementMetadata(buyOrder.venueSessionId, sellOrder.venueSessionId)
                ?: return@filter false
            venueSessionId.isBlank() || orderVenueSessionId.isBlank() || orderVenueSessionId == venueSessionId
        }
    }

    override fun trades(): List<TradeCreated> {
        return trades.toList()
    }

    override fun recentTrades(limit: Int): List<TradeCreated> {
        if (limit <= 0) return emptyList()
        val boundedLimit = limit.coerceAtMost(500)
        val from = (trades.size - boundedLimit).coerceAtLeast(0)
        return trades.subList(from, trades.size).toList()
    }

    private fun sharedSettlementMetadata(first: String, second: String): String? {
        val values = listOf(first, second).filter { it.isNotBlank() }.toSet()
        return when (values.size) {
            0 -> ""
            1 -> values.single()
            else -> null
        }
    }

    override fun tradeTape(instrumentId: String, limit: Int, beforeSequence: Long?): List<PublicTradeTapeEntry> {
        val effectiveLimit = limit.coerceIn(1, 500)
        return trades
            .mapIndexed { index, trade -> (index + 1).toLong() to trade }
            .filter { (sequence, trade) ->
                trade.instrumentId == instrumentId && (beforeSequence == null || sequence < beforeSequence)
            }
            .sortedByDescending { (sequence, _) -> sequence }
            .take(effectiveLimit)
            .map { (sequence, trade) ->
                PublicTradeTapeEntry(
                    sequence = sequence,
                    tradeId = trade.tradeId,
                    instrumentId = trade.instrumentId,
                    quantityUnits = trade.quantityUnits,
                    price = trade.price,
                    currency = trade.currency,
                    occurredAt = trade.occurredAt
                )
            }
    }

    override fun intradayBars(instrumentId: String, interval: String, start: String, end: String): List<IntradayBar> {
        val duration = intradayBarIntervalDurations[interval] ?: return emptyList()
        val startInstant = runCatching { Instant.parse(start) }.getOrNull() ?: return emptyList()
        val endInstant = runCatching { Instant.parse(end) }.getOrNull() ?: return emptyList()
        val bucketMillis = duration.toMillis()

        data class SequencedTrade(val instant: Instant, val price: BigDecimal, val quantity: BigDecimal, val sequence: Long)

        val relevant = trades
            .mapIndexed { index, trade -> trade to (index + 1).toLong() }
            .filter { (trade, _) -> trade.instrumentId == instrumentId }
            .mapNotNull { (trade, sequence) ->
                val instant = runCatching { Instant.parse(trade.occurredAt) }.getOrNull() ?: return@mapNotNull null
                if (instant.isBefore(startInstant) || !instant.isBefore(endInstant)) return@mapNotNull null
                SequencedTrade(
                    instant = instant,
                    price = trade.price.toBigDecimalOrNull() ?: return@mapNotNull null,
                    quantity = trade.quantityUnits.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    sequence = sequence
                )
            }

        return relevant
            .groupBy { entry ->
                val millisSinceOrigin = Duration.between(intradayBarOrigin, entry.instant).toMillis()
                val bucketIndex = Math.floorDiv(millisSinceOrigin, bucketMillis)
                intradayBarOrigin.plusMillis(bucketIndex * bucketMillis)
            }
            .toSortedMap()
            .map { (bucketStart, entries) ->
                val ordered = entries.sortedBy { it.sequence }
                IntradayBar(
                    instrumentId = instrumentId,
                    start = bucketStart.toString(),
                    end = bucketStart.plus(duration).toString(),
                    open = decimalString(ordered.first().price),
                    high = decimalString(ordered.maxOf { it.price }),
                    low = decimalString(ordered.minOf { it.price }),
                    close = decimalString(ordered.last().price),
                    volume = decimalString(ordered.fold(BigDecimal.ZERO) { acc, entry -> acc + entry.quantity })
                )
            }
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
        val boundedLimit = limit.coerceAtMost(500)
        val from = (events.size - boundedLimit).coerceAtLeast(0)
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
        includeFills: Boolean,
        eventStream: String
    ): Long {
        if (batchSize <= 0) return 0
        val partitionSet = partitions.toSet()
        val scopedEventStream = eventStream.trim()
        val watermarks = projectionWatermarks.computeIfAbsent(projectionName) { mutableMapOf() }
        val outcomes = commandOutcomes.values
            .filter { outcome -> outcome.commandType in ProjectableCommandTypes }
            .filter { outcome -> scopedEventStream.isBlank() || outcome.eventStream == scopedEventStream }
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
        if (!recordVenueEventBatch(batch)) {
            return 0
        }
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

    override fun canonicalCommandResult(commandId: String): CanonicalCommandResult? {
        val outcome = canonicalSubmitOutcomes[commandId] ?: return null
        return CanonicalCommandResult(
            commandId = outcome.commandId,
            partition = outcome.partitionId,
            commandStream = outcome.streamName,
            streamSequence = outcome.streamSequence,
            commandType = outcome.commandType,
            payloadHash = outcome.payloadHash,
            instrumentId = outcome.instrumentId,
            resultStatus = outcome.resultStatus,
            rejectCode = outcome.rejectCode,
            engineShardId = outcome.engineShardId,
            resultPayloadJson = outcome.outcome.toJsonObject()
        )
    }

    override fun venueEventBatchCommandReference(commandId: String): VenueEventBatchCommandReference? {
        return venueEventBatches.values.asSequence()
            .mapNotNull { batch ->
                val outcome = batch.outcomes.firstOrNull { it.commandId == commandId } ?: return@mapNotNull null
                VenueEventBatchCommandReference(
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
            }
            .firstOrNull()
    }

    internal fun recordVenueEventBatch(batch: VenueEventBatchFact): Boolean {
        val existing = venueEventBatches[batch.batchId]
        if (existing != null) {
            check(existing.payloadChecksum == batch.payloadChecksum) {
                "venue event batch checksum conflict for batchId ${batch.batchId}"
            }
            return false
        }
        venueEventBatches[batch.batchId] = batch
        return true
    }

    override fun rebuildOrderLifecycleState(): Long {
        orderLifecycleStates.clear()
        orders.values.forEach { order ->
            orderLifecycleStates[order.orderId] = computeLifecycleState(order)
        }
        orderLifecycleDirty.clear()
        return orderLifecycleStates.size.toLong()
    }

    override fun projectOrderLifecycleState(batchSize: Int): Long {
        if (batchSize <= 0) return 0
        val batch = orderLifecycleDirty.take(batchSize)
        batch.forEach { orderId ->
            orders[orderId]?.let { order ->
                orderLifecycleStates[orderId] = computeLifecycleState(order)
                marketDataSnapshotDirty.add(order.instrumentId)
            }
            orderLifecycleDirty.remove(orderId)
        }
        return batch.size.toLong()
    }

    private fun computeLifecycleState(order: PersistedOrder): OrderLifecycleState {
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
        return OrderLifecycleState(
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
                    it.hasPositiveRemainingQuantity() &&
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

    override fun projectMarketDataSnapshots(
        projectionName: String,
        sourceProjectionName: String,
        batchSize: Int
    ): Long {
        if (batchSize <= 0) return 0
        projectOrderLifecycleState(batchSize)
        val batch = marketDataSnapshotDirty.take(batchSize)
        val sourceStatus = projectionStatus(sourceProjectionName, source = "venue-event-batch")
        val sourceWatermarks = sourceStatus.watermarks.filter { it.partitionId >= 0 }
        val lastPartitionSequence = sourceWatermarks.maxOfOrNull { it.lastPartitionSequence } ?: 0L
        val updatedAt = sourceWatermarks.lastOrNull { it.updatedAt.isNotBlank() }?.updatedAt ?: Instant.now().toString()
        batch.forEach { instrumentId ->
            val instrumentOrders = orderLifecycleStates.values.filter {
                it.instrumentId == instrumentId &&
                    it.orderType == "LIMIT" &&
                    it.limitPrice.toBigDecimalOrNull() != null &&
                    it.hasPositiveRemainingQuantity() &&
                    it.status in OpenLifecycleStatuses
            }
            if (instrumentOrders.isEmpty()) {
                marketDataSnapshots.remove("$projectionName:$instrumentId")
            } else {
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
            marketDataSnapshotDirty.remove(instrumentId)
        }
        return batch.size.toLong()
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
                    it.hasPositiveRemainingQuantity() &&
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
        return if (resultStatus == "rejected" || resultStatus == "failed") {
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
        val rejected = resultStatus == "rejected" || resultStatus == "failed"
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
        val document = JsonCodec.parseLegacyObjectOrEmpty(json)
        return document.string(key)
            .ifBlank { document.obj("accepted").string(key) }
            .ifBlank { document.obj("rejected").string(key) }
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

    private fun OrderLifecycleState.hasPositiveRemainingQuantity(): Boolean {
        return (remainingQuantityUnits.toBigDecimalOrNull() ?: return false) > BigDecimal.ZERO
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

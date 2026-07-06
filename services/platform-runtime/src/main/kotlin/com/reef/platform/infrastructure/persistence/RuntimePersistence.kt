package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.Account
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.Participant
import com.reef.platform.domain.PublicTradeTapeEntry
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated

data class ReferenceDataValidation(
    val instrumentExists: Boolean,
    val participantExists: Boolean,
    val accountExists: Boolean,
    val accountBelongsToParticipant: Boolean = true
)

data class PersistableSubmitOutcome(
    val commandId: String,
    val result: SubmitOrderResult,
    val acceptedOrder: PersistedOrder?,
    val lifecycleEvents: List<RuntimeEvent>
)

data class CanonicalSubmitOutcome(
    val runId: String,
    val venueSessionId: String,
    val partitionId: Int,
    val partitionSequence: Long,
    val streamName: String,
    val streamSequence: Long,
    val commandId: String,
    val idempotencyKey: String,
    val payloadHash: String,
    val instrumentId: String,
    val commandType: String,
    val resultStatus: String,
    val rejectCode: String,
    val acceptedAt: String,
    val completedAt: String,
    val engineShardId: String,
    val outcome: PersistableSubmitOutcome
)

data class ProjectionWatermark(
    val projectionName: String,
    val partitionId: Int,
    val lastPartitionSequence: Long,
    val canonicalMaxPartitionSequence: Long,
    val lag: Long,
    val updatedAt: String,
    val lastError: String
)

data class ProjectionStatus(
    val projectionName: String,
    val projectedCount: Long,
    val lag: Long,
    val watermarks: List<ProjectionWatermark>
)

data class MarketDataSnapshot(
    val projectionName: String,
    val sourceProjectionName: String,
    val instrumentId: String,
    val bestBidPrice: String,
    val bestBidQuantity: String,
    val bestAskPrice: String,
    val bestAskQuantity: String,
    val currency: String,
    val lastPartitionSequence: Long,
    val lag: Long,
    val updatedAt: String
)

data class MarketDataDepthLevel(
    val price: String,
    val quantity: String
)

data class MarketDataDepthSnapshot(
    val projectionName: String,
    val sourceProjectionName: String,
    val instrumentId: String,
    val bidLevels: List<MarketDataDepthLevel>,
    val askLevels: List<MarketDataDepthLevel>,
    val currency: String,
    val levels: Int,
    val lastPartitionSequence: Long,
    val lag: Long,
    val updatedAt: String
)

data class OrderLifecycleState(
    val orderId: String,
    val engineOrderId: String,
    val instrumentId: String,
    val participantId: String,
    val accountId: String,
    val side: String,
    val orderType: String,
    val originalQuantityUnits: String,
    val remainingQuantityUnits: String,
    val filledQuantityUnits: String,
    val limitPrice: String,
    val currency: String,
    val timeInForce: String,
    val status: String,
    val acceptedAt: String,
    val lastEventAt: String,
    val updatedAt: String
)

data class VenueCommandOutcomeFact(
    val commandId: String,
    val commandType: String,
    val streamSequence: Long,
    val deliveredCount: Long,
    val payloadHash: String,
    val instrumentId: String,
    val orderId: String,
    val resultStatus: String,
    val rejectCode: String = "",
    val resultPayloadJson: String = "{}"
)

data class VenueEventBatchFact(
    val batchId: String,
    val shardId: String,
    val partition: Int,
    val commandStream: String,
    val eventStream: String,
    val firstSequence: Long,
    val lastSequence: Long,
    val commandCount: Int,
    val createdAt: String,
    val payloadChecksum: String,
    val payloadFormat: String = "venue-event-batch-json",
    val payloadVersion: String = "v1",
    val outcomes: List<VenueCommandOutcomeFact>
)

data class CanonicalCommandOutcome(
    val commandId: String,
    val batchId: String,
    val shardId: String,
    val partition: Int,
    val commandStream: String,
    val eventStream: String,
    val streamSequence: Long,
    val deliveredCount: Long,
    val commandType: String,
    val payloadHash: String,
    val instrumentId: String,
    val orderId: String,
    val resultStatus: String,
    val rejectCode: String,
    val resultPayloadJson: String
)

interface RuntimePersistence {
    fun saveSubmitResult(commandId: String, result: SubmitOrderResult)
    fun submitResult(commandId: String): SubmitOrderResult?
    fun saveInstrument(instrument: Instrument)
    fun saveParticipant(participant: Participant)
    fun saveAccount(account: Account)
    fun saveRole(role: RoleDefinition)
    fun saveActorRoleBinding(binding: ActorRoleBinding)
    fun instruments(): List<Instrument>
    fun participants(): List<Participant>
    fun accounts(): List<Account>
    fun roles(): List<RoleDefinition>
    fun actorRoleBindings(actorId: String): List<ActorRoleBinding>
    fun hasInstrument(instrumentId: String): Boolean
    fun hasParticipant(participantId: String): Boolean
    fun hasAccount(accountId: String): Boolean
    fun validateReferenceData(instrumentId: String, participantId: String, accountId: String): ReferenceDataValidation {
        return ReferenceDataValidation(
            instrumentExists = hasInstrument(instrumentId),
            participantExists = hasParticipant(participantId),
            accountExists = hasAccount(accountId),
            accountBelongsToParticipant = accounts().any { account ->
                account.accountId == accountId && account.participantId == participantId
            }
        )
    }
    fun saveAcceptedOrder(order: PersistedOrder)
    fun saveExecutions(executions: List<ExecutionCreated>)
    fun saveTrades(trades: List<TradeCreated>)
    fun saveEvent(event: RuntimeEvent)
    fun saveEvents(events: List<RuntimeEvent>) {
        events.forEach { saveEvent(it) }
    }
    fun persistSubmitOutcome(
        commandId: String,
        result: SubmitOrderResult,
        acceptedOrder: PersistedOrder?,
        lifecycleEvents: List<RuntimeEvent>
    ) {
        saveSubmitResult(commandId, result)
        if (acceptedOrder != null) {
            saveAcceptedOrder(acceptedOrder)
            saveExecutions(result.executions)
            saveTrades(result.trades)
        }
        saveEvents(lifecycleEvents)
    }
    fun persistSubmitOutcome(outcome: PersistableSubmitOutcome) {
        persistSubmitOutcome(
            commandId = outcome.commandId,
            result = outcome.result,
            acceptedOrder = outcome.acceptedOrder,
            lifecycleEvents = outcome.lifecycleEvents
        )
    }
    fun persistSubmitOutcomes(outcomes: List<PersistableSubmitOutcome>) {
        outcomes.forEach { persistSubmitOutcome(it) }
    }
    fun appendCanonicalSubmitOutcomes(outcomes: List<CanonicalSubmitOutcome>) {
        // In-memory/default persistence keeps canonical append as a no-op unless implemented by the store.
    }
    fun projectCanonicalSubmitOutcomes(projectionName: String, batchSize: Int, partitions: List<Int> = emptyList()): Long {
        return 0
    }
    fun projectCanonicalCommandOutcomes(
        projectionName: String,
        batchSize: Int,
        partitions: List<Int> = emptyList(),
        includeFills: Boolean = true
    ): Long {
        return 0
    }
    fun projectionStatus(
        projectionName: String,
        partitions: List<Int> = emptyList(),
        source: String = "canonical-submit"
    ): ProjectionStatus {
        return ProjectionStatus(projectionName, projectedCount = 0, lag = 0, watermarks = emptyList())
    }
    fun materializeVenueEventBatch(batch: VenueEventBatchFact): Long {
        return 0
    }
    fun canonicalCommandOutcome(commandId: String): CanonicalCommandOutcome? {
        return null
    }
    fun rebuildOrderLifecycleState(): Long {
        return 0
    }
    fun projectOrderLifecycleState(batchSize: Int): Long {
        return rebuildOrderLifecycleState()
    }
    fun orderLifecycleState(orderId: String): OrderLifecycleState? {
        return null
    }
    fun refreshMarketDataSnapshots(
        projectionName: String = "market-data-top-of-book",
        sourceProjectionName: String = "runtime-normalized-venue-outcomes"
    ): Long {
        return 0
    }
    fun projectMarketDataSnapshots(
        projectionName: String = "market-data-top-of-book",
        sourceProjectionName: String = "runtime-normalized-venue-outcomes",
        batchSize: Int = 500
    ): Long {
        return refreshMarketDataSnapshots(projectionName, sourceProjectionName)
    }
    fun marketDataSnapshot(
        instrumentId: String,
        projectionName: String = "market-data-top-of-book"
    ): MarketDataSnapshot? {
        return null
    }
    fun marketDataDepthSnapshot(
        instrumentId: String,
        levels: Int = 5,
        projectionName: String = "market-data-depth",
        sourceProjectionName: String = "runtime-normalized-venue-outcomes"
    ): MarketDataDepthSnapshot? {
        return null
    }
    fun acceptedOrder(orderId: String): PersistedOrder?
    fun acceptedOrders(): List<PersistedOrder>
    fun executionsForOrder(orderId: String): List<ExecutionCreated>
    fun trades(): List<TradeCreated>
    fun recentTrades(limit: Int): List<TradeCreated>
    fun tradesForOrder(orderId: String): List<TradeCreated>
    fun tradeTape(instrumentId: String, limit: Int, beforeSequence: Long? = null): List<PublicTradeTapeEntry> {
        return emptyList()
    }
    fun eventsForOrder(orderId: String): List<RuntimeEvent>
    fun eventsForTrace(traceId: String): List<RuntimeEvent>
    fun events(): List<RuntimeEvent>
    fun recentEvents(limit: Int): List<RuntimeEvent>
}

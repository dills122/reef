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
    fun projectionStatus(projectionName: String, partitions: List<Int> = emptyList()): ProjectionStatus {
        return ProjectionStatus(projectionName, projectedCount = 0, lag = 0, watermarks = emptyList())
    }
    fun acceptedOrder(orderId: String): PersistedOrder?
    fun acceptedOrders(): List<PersistedOrder>
    fun executionsForOrder(orderId: String): List<ExecutionCreated>
    fun trades(): List<TradeCreated>
    fun recentTrades(limit: Int): List<TradeCreated>
    fun tradesForOrder(orderId: String): List<TradeCreated>
    fun eventsForOrder(orderId: String): List<RuntimeEvent>
    fun eventsForTrace(traceId: String): List<RuntimeEvent>
    fun events(): List<RuntimeEvent>
    fun recentEvents(limit: Int): List<RuntimeEvent>
}

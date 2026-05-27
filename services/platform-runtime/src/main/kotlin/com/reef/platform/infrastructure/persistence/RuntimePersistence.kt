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
    val accountExists: Boolean
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
            accountExists = hasAccount(accountId)
        )
    }
    fun saveAcceptedOrder(order: PersistedOrder)
    fun saveExecutions(executions: List<ExecutionCreated>)
    fun saveTrades(trades: List<TradeCreated>)
    fun saveEvent(event: RuntimeEvent)
    fun saveEvents(events: List<RuntimeEvent>) {
        events.forEach { saveEvent(it) }
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

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

class InMemoryRuntimePersistence : RuntimePersistence {
    private val canonicalSubmitOutcomes = linkedMapOf<String, CanonicalSubmitOutcome>()
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

    override fun projectCanonicalSubmitOutcomes(projectionName: String, batchSize: Int): Long {
        if (batchSize <= 0) return 0
        val watermarks = projectionWatermarks.computeIfAbsent(projectionName) { mutableMapOf() }
        val outcomes = canonicalSubmitOutcomes.values
            .filter { outcome -> outcome.partitionSequence > (watermarks[outcome.partitionId] ?: 0L) }
            .sortedWith(compareBy<CanonicalSubmitOutcome> { it.partitionId }.thenBy { it.partitionSequence })
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

    override fun projectionStatus(projectionName: String): ProjectionStatus {
        val watermarks = projectionWatermarks[projectionName].orEmpty()
        val partitions = canonicalSubmitOutcomes.values
            .groupBy { it.partitionId }
            .map { (partitionId, outcomes) ->
                val projected = watermarks[partitionId] ?: 0L
                val canonicalMax = outcomes.maxOf { it.partitionSequence }
                ProjectionWatermark(
                    projectionName = projectionName,
                    partitionId = partitionId,
                    lastPartitionSequence = projected,
                    canonicalMaxPartitionSequence = canonicalMax,
                    lag = (canonicalMax - projected).coerceAtLeast(0L),
                    updatedAt = "",
                    lastError = ""
                )
            }
            .sortedBy { it.partitionId }
        return ProjectionStatus(
            projectionName = projectionName,
            projectedCount = submitResults.size.toLong(),
            lag = partitions.sumOf { it.lag },
            watermarks = partitions
        )
    }
}

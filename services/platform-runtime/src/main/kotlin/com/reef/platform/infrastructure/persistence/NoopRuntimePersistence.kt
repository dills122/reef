package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.Account
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.Participant
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.PostTradeProfile
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.ScenarioRunPostTradeProfile
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import com.reef.platform.domain.VenueSessionPostTradeProfile
import java.util.concurrent.ConcurrentHashMap

/**
 * Benchmark-only persistence that keeps setup/reference data but drops command outcomes.
 *
 * This is not a production-safe persistence mode. It exists to isolate runtime,
 * transport, and engine cost from write-model, audit, replay, and projection cost.
 */
class NoopRuntimePersistence : RuntimePersistence {
    private val instruments = ConcurrentHashMap<String, Instrument>()
    private val participants = ConcurrentHashMap<String, Participant>()
    private val accounts = ConcurrentHashMap<String, Account>()
    private val roles = ConcurrentHashMap<String, RoleDefinition>()
    private val actorRoleBindings = ConcurrentHashMap<String, ActorRoleBinding>()
    private val postTradeProfiles = ConcurrentHashMap<String, PostTradeProfile>()
    private val scenarioRunPostTradeProfiles = ConcurrentHashMap<String, ScenarioRunPostTradeProfile>()
    private val venueSessionPostTradeProfiles = ConcurrentHashMap<String, VenueSessionPostTradeProfile>()
    @Volatile
    private var activePostTradeProfileId = ""

    override fun saveSubmitResult(commandId: String, result: SubmitOrderResult) {}

    override fun submitResult(commandId: String): SubmitOrderResult? = null

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
        actorRoleBindings["${binding.actorId}|${binding.roleId}"] = binding
    }

    override fun savePostTradeProfile(profile: PostTradeProfile) {
        postTradeProfiles[profile.profileId] = profile
        if (profile.active || activePostTradeProfileId.isBlank()) {
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

    override fun instruments(): List<Instrument> = instruments.values.toList()

    override fun participants(): List<Participant> = participants.values.toList()

    override fun accounts(): List<Account> = accounts.values.toList()

    override fun roles(): List<RoleDefinition> = roles.values.toList()

    override fun actorRoleBindings(actorId: String): List<ActorRoleBinding> {
        return actorRoleBindings.values.filter { it.actorId == actorId }
    }

    override fun hasInstrument(instrumentId: String): Boolean = instruments.containsKey(instrumentId)

    override fun hasParticipant(participantId: String): Boolean = participants.containsKey(participantId)

    override fun hasAccount(accountId: String): Boolean = accounts.containsKey(accountId)

    override fun saveAcceptedOrder(order: PersistedOrder) {}

    override fun saveExecutions(executions: List<ExecutionCreated>) {}

    override fun saveTrades(trades: List<TradeCreated>) {}

    override fun saveEvent(event: RuntimeEvent) {}

    override fun saveEvents(events: List<RuntimeEvent>) {}

    override fun persistSubmitOutcome(
        commandId: String,
        result: SubmitOrderResult,
        acceptedOrder: PersistedOrder?,
        lifecycleEvents: List<RuntimeEvent>
    ) {}

    override fun persistSubmitOutcome(outcome: PersistableSubmitOutcome) {}

    override fun persistSubmitOutcomes(outcomes: List<PersistableSubmitOutcome>) {}

    override fun appendCanonicalSubmitOutcomes(outcomes: List<CanonicalSubmitOutcome>) {}

    override fun projectCanonicalSubmitOutcomes(projectionName: String, batchSize: Int, partitions: List<Int>): Long = 0

    fun projectCanonicalCommandOutcomes(
        projectionName: String,
        batchSize: Int,
        partitions: List<Int>,
        includeFills: Boolean,
        eventStream: String
    ): Long = 0

    override fun projectCanonicalCommandOutcomes(
        projectionName: String,
        batchSize: Int,
        partitions: List<Int>,
        includeFills: Boolean,
        eventStream: String,
        projectionStage: ProjectionStage
    ): Long = 0

    override fun projectionStatus(projectionName: String, partitions: List<Int>, source: String): ProjectionStatus {
        return ProjectionStatus(projectionName, projectedCount = 0, lag = 0, watermarks = emptyList())
    }

    override fun acceptedOrder(orderId: String): PersistedOrder? = null

    override fun acceptedOrders(): List<PersistedOrder> = emptyList()

    override fun executionsForOrder(orderId: String): List<ExecutionCreated> = emptyList()

    override fun trades(): List<TradeCreated> = emptyList()

    override fun recentTrades(limit: Int): List<TradeCreated> = emptyList()

    override fun tradesForOrder(orderId: String): List<TradeCreated> = emptyList()

    override fun eventsForOrder(orderId: String): List<RuntimeEvent> = emptyList()

    override fun eventsForTrace(traceId: String): List<RuntimeEvent> = emptyList()

    override fun events(): List<RuntimeEvent> = emptyList()

    override fun recentEvents(limit: Int): List<RuntimeEvent> = emptyList()
}

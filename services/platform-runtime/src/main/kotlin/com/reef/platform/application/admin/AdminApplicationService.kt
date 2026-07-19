package com.reef.platform.application.admin

import com.reef.platform.api.AccountRiskControlStore
import com.reef.platform.api.AccountRiskDecision
import com.reef.platform.api.JsonCodec
import com.reef.platform.application.settlement.PostTradeProfileResolver
import com.reef.platform.domain.Account
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.Participant
import com.reef.platform.domain.Permission
import com.reef.platform.domain.PostTradeProfile
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.ScenarioRunPostTradeProfile
import com.reef.platform.domain.VenueSessionPostTradeProfile
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.reef.platform.infrastructure.persistence.PostgresRuntimePersistence
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.reef.platform.infrastructure.persistence.RuntimePersistence
import java.time.Instant
import java.util.UUID

data class AdminActor(
    val actorId: String,
    val correlationId: String = "",
    val occurredAt: String
)

class AuthorizationException(message: String) : RuntimeException(message)

data class UpsertInstrumentCommand(
    val instrumentId: String,
    val symbol: String
)

data class UpsertParticipantCommand(
    val participantId: String,
    val name: String
)

data class UpsertAccountCommand(
    val accountId: String,
    val participantId: String
)

data class CalendarProfile(
    val profileId: String,
    val timezone: String,
    val settlementCycle: String
)

data class OverrideReasonCode(
    val code: String,
    val description: String
)

data class SimulationControlState(
    val status: String,
    val scenario: String = ""
)

class AdminApplicationService(
    private val runtimePersistence: RuntimePersistence = defaultRuntimePersistence(),
    private val accountRiskControlStore: AccountRiskControlStore? = null,
    private val adminIdentityService: AdminIdentityService? = null,
    private val now: () -> Instant = { Instant.now() }
) {
    private val eventProducer = "platform-runtime-admin"
    private val eventSchemaVersion = "v1"
    private val calendarProfiles = linkedMapOf<String, CalendarProfile>()
    private val overrideReasons = linkedMapOf<String, OverrideReasonCode>()
    private var simulationState = SimulationControlState(status = "stopped", scenario = "")

    init {
        seedPostTradeProfiles()
        runtimePersistence.saveRole(
            RoleDefinition(
                roleId = "system_admin",
                permissions = listOf(Permission.SUPERUSER)
            )
        )
        runtimePersistence.saveActorRoleBinding(
            ActorRoleBinding(
                actorId = System.getenv("ADMIN_ACTOR_ID") ?: "admin-cli",
                roleId = "system_admin"
            )
        )
    }

    fun upsertInstrument(actor: AdminActor, command: UpsertInstrumentCommand) {
        requirePermission(actor, Permission.REFERENCE_WRITE)
        runtimePersistence.saveInstrument(Instrument(command.instrumentId, command.symbol))
        emitAudit(actor, "AdminInstrumentUpserted", command.instrumentId, "symbol=${command.symbol}")
    }

    fun upsertParticipant(actor: AdminActor, command: UpsertParticipantCommand) {
        requirePermission(actor, Permission.REFERENCE_WRITE)
        runtimePersistence.saveParticipant(Participant(command.participantId, command.name))
        emitAudit(actor, "AdminParticipantUpserted", command.participantId, "name=${command.name}")
    }

    fun upsertAccount(actor: AdminActor, command: UpsertAccountCommand) {
        requirePermission(actor, Permission.REFERENCE_WRITE)
        runtimePersistence.saveAccount(Account(command.accountId, command.participantId))
        emitAudit(actor, "AdminAccountUpserted", command.accountId, "participantId=${command.participantId}")
    }

    fun upsertRole(actor: AdminActor, roleId: String, permissionsCsv: String) {
        requirePermission(actor, Permission.AUTH_ADMIN)
        val permissions = permissionsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
        runtimePersistence.saveRole(RoleDefinition(roleId, permissions))
        emitAudit(actor, "AdminRoleUpserted", roleId, "permissions=${permissions.joinToString(",")}")
    }

    fun assignRole(actor: AdminActor, targetActorId: String, roleId: String) {
        requirePermission(actor, Permission.AUTH_ADMIN)
        runtimePersistence.saveActorRoleBinding(ActorRoleBinding(targetActorId, roleId))
        emitAudit(actor, "AdminRoleAssigned", targetActorId, "roleId=$roleId")
    }

    fun upsertCalendarProfile(actor: AdminActor, profile: CalendarProfile) {
        requirePermission(actor, Permission.CALENDAR_ADMIN)
        calendarProfiles[profile.profileId] = profile
        emitAudit(
            actor,
            "AdminCalendarProfileUpserted",
            profile.profileId,
            "timezone=${profile.timezone},settlementCycle=${profile.settlementCycle}"
        )
    }

    fun listCalendarProfiles(): List<CalendarProfile> = calendarProfiles.values.toList()

    fun upsertPostTradeProfile(actor: AdminActor, profile: PostTradeProfile) {
        requirePermission(actor, Permission.POST_TRADE_PROFILE_ADMIN)
        validatePostTradeProfile(profile)
        val existing = runtimePersistence.postTradeProfiles().firstOrNull { it.profileId == profile.profileId }
        runtimePersistence.savePostTradeProfile(profile.copy(active = existing?.active ?: false))
        emitAudit(
            actor,
            "AdminPostTradeProfileUpserted",
            profile.profileId,
            "mode=${profile.mode},settlementCycle=${profile.settlementCycle},nettingMode=${profile.nettingMode}," +
                "ledgerPostingMode=${profile.ledgerPostingMode},policyVersion=${profile.policyVersion}"
        )
    }

    fun listPostTradeProfiles(): List<PostTradeProfile> = runtimePersistence.postTradeProfiles()

    fun activePostTradeProfile(): PostTradeProfile = runtimePersistence.activePostTradeProfile()

    fun activatePostTradeProfile(actor: AdminActor, profileId: String): PostTradeProfile {
        requirePermission(actor, Permission.POST_TRADE_PROFILE_ADMIN)
        val profile = runtimePersistence.activatePostTradeProfile(profileId)
        emitAudit(actor, "AdminPostTradeProfileActivated", profileId, "policyVersion=${profile.policyVersion}")
        return profile
    }

    fun setScenarioRunPostTradeProfile(
        actor: AdminActor,
        scenarioRunId: String,
        postTradeProfileId: String
    ): ScenarioRunPostTradeProfile {
        requirePermission(actor, Permission.POST_TRADE_PROFILE_ADMIN)
        require(scenarioRunId.isNotBlank()) { "scenarioRunId is required" }
        require(postTradeProfileId.isNotBlank()) { "postTradeProfileId is required" }
        PostTradeProfileResolver.fromPersistence(runtimePersistence).resolve(scenarioRunProfileId = postTradeProfileId)
        val config = ScenarioRunPostTradeProfile(scenarioRunId = scenarioRunId, postTradeProfileId = postTradeProfileId)
        runtimePersistence.saveScenarioRunPostTradeProfile(config)
        emitAudit(
            actor,
            "AdminScenarioRunPostTradeProfileSet",
            scenarioRunId,
            "postTradeProfileId=$postTradeProfileId"
        )
        return config
    }

    fun listScenarioRunPostTradeProfiles(): List<ScenarioRunPostTradeProfile> {
        return runtimePersistence.scenarioRunPostTradeProfiles()
    }

    fun setVenueSessionPostTradeProfile(
        actor: AdminActor,
        venueSessionId: String,
        postTradeProfileId: String
    ): VenueSessionPostTradeProfile {
        requirePermission(actor, Permission.POST_TRADE_PROFILE_ADMIN)
        require(venueSessionId.isNotBlank()) { "venueSessionId is required" }
        require(postTradeProfileId.isNotBlank()) { "postTradeProfileId is required" }
        PostTradeProfileResolver.fromPersistence(runtimePersistence).resolve(venueSessionProfileId = postTradeProfileId)
        val config = VenueSessionPostTradeProfile(venueSessionId = venueSessionId, postTradeProfileId = postTradeProfileId)
        runtimePersistence.saveVenueSessionPostTradeProfile(config)
        emitAudit(
            actor,
            "AdminVenueSessionPostTradeProfileSet",
            venueSessionId,
            "postTradeProfileId=$postTradeProfileId"
        )
        return config
    }

    fun listVenueSessionPostTradeProfiles(): List<VenueSessionPostTradeProfile> {
        return runtimePersistence.venueSessionPostTradeProfiles()
    }

    fun upsertOverrideReason(actor: AdminActor, reason: OverrideReasonCode) {
        requirePermission(actor, Permission.OVERRIDE_ADMIN)
        overrideReasons[reason.code] = reason
        emitAudit(actor, "AdminOverrideReasonUpserted", reason.code, "description=${reason.description}")
    }

    fun listOverrideReasons(): List<OverrideReasonCode> = overrideReasons.values.toList()

    fun startSimulation(actor: AdminActor, scenario: String) {
        requirePermission(actor, Permission.SIMULATION_CONTROL)
        simulationState = SimulationControlState(status = "running", scenario = scenario)
        emitAudit(actor, "AdminSimulationStarted", scenario, "status=running")
    }

    fun pauseSimulation(actor: AdminActor) {
        requirePermission(actor, Permission.SIMULATION_CONTROL)
        simulationState = simulationState.copy(status = "paused")
        emitAudit(actor, "AdminSimulationPaused", simulationState.scenario, "status=paused")
    }

    fun stopSimulation(actor: AdminActor) {
        requirePermission(actor, Permission.SIMULATION_CONTROL)
        simulationState = SimulationControlState(status = "stopped", scenario = simulationState.scenario)
        emitAudit(actor, "AdminSimulationStopped", simulationState.scenario, "status=stopped")
    }

    fun simulationState(): SimulationControlState = simulationState

    fun listInstruments(): List<Instrument> = runtimePersistence.instruments()

    fun listParticipants(): List<Participant> = runtimePersistence.participants()

    fun listAccounts(): List<Account> = runtimePersistence.accounts()

    fun listRoles(): List<RoleDefinition> = runtimePersistence.roles()

    fun listActorRoles(actorId: String): List<ActorRoleBinding> = runtimePersistence.actorRoleBindings(actorId)

    fun recentEvents(limit: Int): List<RuntimeEvent> = runtimePersistence.recentEvents(limit)

    fun traceEvents(traceId: String): List<RuntimeEvent> = runtimePersistence.eventsForTrace(traceId)

    private fun seedPostTradeProfiles() {
        val existingIds = runtimePersistence.postTradeProfiles().map { it.profileId }.toSet()
        if ("ops-realistic-v1" !in existingIds) {
            runtimePersistence.savePostTradeProfile(
                PostTradeProfile(
                    profileId = "ops-realistic-v1",
                    mode = "ops-realistic",
                    settlementCycle = "T+1",
                    nettingMode = "batch-netting",
                    ledgerPostingMode = "scheduled-finality",
                    policyVersion = 1,
                    active = true
                )
            )
        }
        if ("instant-post-trade-v1" !in existingIds) {
            runtimePersistence.savePostTradeProfile(
                PostTradeProfile(
                    profileId = "instant-post-trade-v1",
                    mode = "instant-post-trade",
                    settlementCycle = "T+0",
                    nettingMode = "gross-or-microbatch",
                    ledgerPostingMode = "near-instant-finality",
                    policyVersion = 1,
                    active = false
                )
            )
        }
    }

    private fun validatePostTradeProfile(profile: PostTradeProfile) {
        require(profile.profileId.isNotBlank()) { "post-trade profileId is required" }
        require(profile.mode in setOf("ops-realistic", "instant-post-trade")) {
            "post-trade profile mode must be ops-realistic or instant-post-trade"
        }
        require(profile.settlementCycle.isNotBlank()) { "post-trade settlementCycle is required" }
        require(profile.nettingMode.isNotBlank()) { "post-trade nettingMode is required" }
        require(profile.ledgerPostingMode.isNotBlank()) { "post-trade ledgerPostingMode is required" }
        require(profile.policyVersion > 0) { "post-trade policyVersion must be positive" }
    }

    private fun emitAudit(actor: AdminActor, eventType: String, targetId: String, detail: String) {
        val eventTime = if (actor.occurredAt.isBlank()) now().toString() else actor.occurredAt
        runtimePersistence.saveEvent(
            RuntimeEvent(
                eventId = "evt-admin-${UUID.randomUUID()}",
                eventType = eventType,
                orderId = targetId,
                traceId = "admin:${actor.actorId}",
                causationId = detail,
                correlationId = actor.correlationId,
                producer = eventProducer,
                schemaVersion = eventSchemaVersion,
                occurredAt = eventTime,
                actorId = actor.actorId,
                payloadJson = detailPayload(detail)
            )
        )
    }

    private fun detailPayload(detail: String): String {
        return JsonCodec.writeObject("detail" to detail)
    }

    private fun requirePermission(actor: AdminActor, permission: String) {
        if (adminIdentityAllowsPermission(actor.actorId, permission)) return
        val boundRoleIds = runtimePersistence.actorRoleBindings(actor.actorId).map { it.roleId }.toSet()
        val allowed = runtimePersistence.roles()
            .filter { it.roleId in boundRoleIds }
            .flatMap { it.permissions }
            .toSet()
        if (permission !in allowed && Permission.SUPERUSER !in allowed) {
            throw AuthorizationException("actor ${actor.actorId} missing permission $permission")
        }
    }

    private fun adminIdentityAllowsPermission(actorId: String, permission: String): Boolean {
        if (!actorId.startsWith("user-gh-")) return false
        val identity = adminIdentityService ?: return false
        val user = identity.user(actorId) ?: return false
        if (user.trustState != AdminTrustState.Trusted) return false
        return false
    }
}

private fun defaultRuntimePersistence(): RuntimePersistence {
    val persistence = System.getenv("RUNTIME_PERSISTENCE") ?: "inmemory"
    if (persistence != "postgres") {
        return InMemoryRuntimePersistence()
    }

    val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/reef"
    val user = System.getenv("RUNTIME_POSTGRES_USER") ?: "reef"
    val password = System.getenv("RUNTIME_POSTGRES_PASSWORD") ?: "reef"
    val projectionJdbcUrl = System.getenv("RUNTIME_PROJECTION_POSTGRES_JDBC_URL")?.trim().orEmpty()
    val projectionUser = System.getenv("RUNTIME_PROJECTION_POSTGRES_USER") ?: user
    val projectionPassword = System.getenv("RUNTIME_PROJECTION_POSTGRES_PASSWORD") ?: password
    val runtimeDataSource = RuntimeDataSources.dataSource(jdbcUrl, user, password, "admin-runtime")
    val projectionDataSource = if (projectionJdbcUrl.isBlank()) {
        runtimeDataSource
    } else {
        RuntimeDataSources.dataSource(
            projectionJdbcUrl,
            projectionUser,
            projectionPassword,
            "admin-runtime-projection"
        )
    }
    return PostgresRuntimePersistence(
        dataSource = runtimeDataSource,
        projectionDataSource = projectionDataSource
    )
}

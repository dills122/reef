package com.reef.platform.application.admin

import com.reef.platform.api.AccountRiskControlStore
import com.reef.platform.api.AccountRiskDecision
import com.reef.platform.api.JsonCodec
import com.reef.platform.application.arena.ArenaBotRegistryStore
import com.reef.platform.application.arena.ArenaBot
import com.reef.platform.application.arena.ArenaBotMetadata
import com.reef.platform.application.arena.ArenaBotVersion
import com.reef.platform.application.arena.ArenaBotVersionStatus
import com.reef.platform.application.arena.ArenaControlPlaneService
import com.reef.platform.application.arena.ArenaLeaderboardEntry
import com.reef.platform.application.arena.ArenaOperatorDecision
import com.reef.platform.application.arena.ArenaQualificationReport
import com.reef.platform.application.arena.ArenaRunBotResult
import com.reef.platform.application.arena.ArenaRunRecord
import com.reef.platform.application.arena.ArenaRunStatus
import com.reef.platform.application.arena.ArenaRunBotVersionRef
import com.reef.platform.application.arena.ArenaRuntimeConfigDescriptor
import com.reef.platform.application.arena.RegisterArenaBotCommand
import com.reef.platform.application.arena.RegisterArenaBotVersionCommand
import com.reef.platform.application.arena.RegisterArenaRunCommand
import com.reef.platform.domain.Account
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.Participant
import com.reef.platform.domain.Permission
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.ActorRoleBinding
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

data class ArenaBotVersionDecisionCommand(
    val botId: String,
    val versionId: String,
    val status: ArenaBotVersionStatus,
    val reason: String
)

data class ArenaBotRegistrationCommand(
    val botId: String,
    val fileName: String,
    val name: String,
    val publisher: String,
    val email: String,
    val description: String = "",
    val version: String = ""
)

data class ArenaBotVersionRegistrationCommand(
    val botId: String,
    val versionId: String,
    val sourceHash: String,
    val artifactHash: String,
    val sdkVersion: String,
    val apiVersion: String,
    val dependencyManifestHash: String
)

data class ArenaRunRegistrationCommand(
    val runId: String,
    val modeId: String,
    val scenarioId: String,
    val seed: Long,
    val policyVersion: String,
    val botVersions: List<ArenaRunBotVersionRef>
)

data class ArenaRunStatusCommand(
    val runId: String,
    val status: ArenaRunStatus
)

data class ArenaRunBotResultIngestionCommand(
    val runId: String,
    val botId: String,
    val versionId: String,
    val scoringPolicyVersion: String,
    val finalEquity: Long,
    val realizedPnl: Long,
    val maxDrawdown: Long,
    val actionsProposed: Int,
    val orderActionsProposed: Int,
    val dataCalls: Int,
    val signalsGenerated: Int,
    val disqualified: Boolean
)

class AdminApplicationService(
    private val runtimePersistence: RuntimePersistence = defaultRuntimePersistence(),
    private val arenaRegistryStore: ArenaBotRegistryStore? = null,
    private val accountRiskControlStore: AccountRiskControlStore? = null,
    private val now: () -> Instant = { Instant.now() }
) {
    private val eventProducer = "platform-runtime-admin"
    private val eventSchemaVersion = "v1"
    private val calendarProfiles = linkedMapOf<String, CalendarProfile>()
    private val overrideReasons = linkedMapOf<String, OverrideReasonCode>()
    private var simulationState = SimulationControlState(status = "stopped", scenario = "")

    init {
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

    fun registerArenaBot(actor: AdminActor, command: ArenaBotRegistrationCommand): ArenaBot {
        requirePermission(actor, Permission.ARENA_ADMIN)
        val bot = arenaControlPlane().registerBot(
            RegisterArenaBotCommand(
                botId = command.botId,
                fileName = command.fileName,
                metadata = ArenaBotMetadata(
                    name = command.name,
                    publisher = command.publisher,
                    email = command.email,
                    description = command.description,
                    version = command.version
                )
            )
        )
        emitAudit(actor, "AdminArenaBotRegistered", command.botId, "fileName=${command.fileName}")
        return bot
    }

    fun registerArenaBotVersion(
        actor: AdminActor,
        command: ArenaBotVersionRegistrationCommand
    ): ArenaBotVersion {
        requirePermission(actor, Permission.ARENA_ADMIN)
        val version = arenaControlPlane().registerVersion(
            RegisterArenaBotVersionCommand(
                botId = command.botId,
                versionId = command.versionId,
                sourceHash = command.sourceHash,
                artifactHash = command.artifactHash,
                sdkVersion = command.sdkVersion,
                apiVersion = command.apiVersion,
                dependencyManifestHash = command.dependencyManifestHash
            )
        )
        emitAudit(actor, "AdminArenaBotVersionRegistered", "${command.botId}/${command.versionId}", "status=${version.status.name}")
        return version
    }

    fun transitionArenaBotVersion(actor: AdminActor, command: ArenaBotVersionDecisionCommand): ArenaBotVersion {
        requirePermission(actor, Permission.ARENA_ADMIN)
        val service = arenaControlPlane()
        val updated = service.transitionVersion(
            botId = command.botId,
            versionId = command.versionId,
            toStatus = command.status,
            actorId = actor.actorId,
            reason = command.reason,
            correlationId = actor.correlationId
        )
        syncArenaBotRiskControl(command, updated)
        emitAudit(
            actor,
            "AdminArenaBotVersionTransitioned",
            "${command.botId}/${command.versionId}",
            "status=${command.status.name},reason=${command.reason}"
        )
        return updated
    }

    fun arenaBot(actor: AdminActor, botId: String): ArenaBot? {
        requirePermission(actor, Permission.ARENA_ADMIN)
        return arenaStore().bot(botId)
    }

    fun arenaBotVersion(actor: AdminActor, botId: String, versionId: String): ArenaBotVersion? {
        requirePermission(actor, Permission.ARENA_ADMIN)
        return arenaStore().version(botId, versionId)
    }

    fun arenaQualificationReports(actor: AdminActor, botId: String, versionId: String): List<ArenaQualificationReport> {
        requirePermission(actor, Permission.ARENA_ADMIN)
        return arenaStore().qualificationReports(botId, versionId)
    }

    fun arenaOperatorDecisions(actor: AdminActor, botId: String, versionId: String): List<ArenaOperatorDecision> {
        requirePermission(actor, Permission.ARENA_ADMIN)
        return arenaStore().operatorDecisions(botId, versionId)
    }

    fun registerArenaRun(actor: AdminActor, command: ArenaRunRegistrationCommand): ArenaRunRecord {
        requirePermission(actor, Permission.ARENA_ADMIN)
        val run = arenaControlPlane().registerRun(
            RegisterArenaRunCommand(
                runId = command.runId,
                modeId = command.modeId,
                scenarioId = command.scenarioId,
                seed = command.seed,
                policyVersion = command.policyVersion,
                botVersions = command.botVersions
            )
        )
        emitAudit(actor, "AdminArenaRunRegistered", command.runId, "modeId=${command.modeId},scenarioId=${command.scenarioId}")
        return run
    }

    fun updateArenaRunStatus(actor: AdminActor, command: ArenaRunStatusCommand): ArenaRunRecord {
        requirePermission(actor, Permission.ARENA_ADMIN)
        val run = arenaControlPlane().updateRunStatus(command.runId, command.status)
        emitAudit(actor, "AdminArenaRunStatusUpdated", command.runId, "status=${command.status.name}")
        return run
    }

    fun arenaRun(actor: AdminActor, runId: String): ArenaRunRecord? {
        requirePermission(actor, Permission.ARENA_ADMIN)
        return arenaStore().runRecord(runId)
    }

    fun arenaRunBotResults(actor: AdminActor, runId: String): List<ArenaRunBotResult> {
        requirePermission(actor, Permission.ARENA_ADMIN)
        return arenaControlPlane().runBotResults(runId)
    }

    fun recordArenaRunBotResult(
        actor: AdminActor,
        command: ArenaRunBotResultIngestionCommand
    ): ArenaRunBotResult {
        requirePermission(actor, Permission.ARENA_ADMIN)
        val result = arenaControlPlane().recordRunBotResult(
            ArenaRunBotResult(
                runId = command.runId,
                botId = command.botId,
                versionId = command.versionId,
                scoringPolicyVersion = command.scoringPolicyVersion,
                finalEquity = command.finalEquity,
                realizedPnl = command.realizedPnl,
                maxDrawdown = command.maxDrawdown,
                actionsProposed = command.actionsProposed,
                orderActionsProposed = command.orderActionsProposed,
                dataCalls = command.dataCalls,
                signalsGenerated = command.signalsGenerated,
                disqualified = command.disqualified,
                createdAt = now()
            )
        )
        emitAudit(
            actor,
            "AdminArenaRunBotResultIngested",
            "${command.runId}/${command.botId}/${command.versionId}",
            "scoringPolicyVersion=${command.scoringPolicyVersion},finalEquity=${command.finalEquity}"
        )
        return result
    }

    fun arenaRuntimeConfigDescriptors(
        actor: AdminActor,
        botId: String,
        versionId: String
    ): List<ArenaRuntimeConfigDescriptor> {
        requirePermission(actor, Permission.ARENA_ADMIN)
        return arenaStore().runtimeConfigDescriptors(botId, versionId)
    }

    fun arenaLeaderboard(
        actor: AdminActor,
        modeId: String,
        scoringPolicyVersion: String,
        limit: Int = 50
    ): List<ArenaLeaderboardEntry> {
        requirePermission(actor, Permission.ARENA_ADMIN)
        return arenaStore().leaderboard(modeId, scoringPolicyVersion, limit)
    }

    fun listInstruments(): List<Instrument> = runtimePersistence.instruments()

    fun listParticipants(): List<Participant> = runtimePersistence.participants()

    fun listAccounts(): List<Account> = runtimePersistence.accounts()

    fun listRoles(): List<RoleDefinition> = runtimePersistence.roles()

    fun listActorRoles(actorId: String): List<ActorRoleBinding> = runtimePersistence.actorRoleBindings(actorId)

    fun recentEvents(limit: Int): List<RuntimeEvent> = runtimePersistence.recentEvents(limit)

    fun traceEvents(traceId: String): List<RuntimeEvent> = runtimePersistence.eventsForTrace(traceId)

    private fun arenaControlPlane(): ArenaControlPlaneService {
        return ArenaControlPlaneService(arenaStore(), now)
    }

    private fun arenaStore(): ArenaBotRegistryStore {
        return arenaRegistryStore ?: error("arena registry store is not configured")
    }

    private fun syncArenaBotRiskControl(command: ArenaBotVersionDecisionCommand, updated: ArenaBotVersion) {
        val riskStore = accountRiskControlStore ?: return
        val reason = "arena ${updated.status.name}: ${command.reason}"
        when (updated.status) {
            ArenaBotVersionStatus.Approved,
            ArenaBotVersionStatus.Active -> riskStore.upsertControl(
                scopeType = "BOT",
                scopeId = updated.botId,
                decision = AccountRiskDecision.ALLOW,
                reason = reason
            )
            ArenaBotVersionStatus.Suspended,
            ArenaBotVersionStatus.Quarantined,
            ArenaBotVersionStatus.Banned,
            ArenaBotVersionStatus.Archived -> riskStore.upsertControl(
                scopeType = "BOT",
                scopeId = updated.botId,
                decision = AccountRiskDecision.DISABLED_BOT,
                reason = reason
            )
            ArenaBotVersionStatus.Draft,
            ArenaBotVersionStatus.Submitted,
            ArenaBotVersionStatus.ChecksPassed -> Unit
        }
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
        val boundRoleIds = runtimePersistence.actorRoleBindings(actor.actorId).map { it.roleId }.toSet()
        val allowed = runtimePersistence.roles()
            .filter { it.roleId in boundRoleIds }
            .flatMap { it.permissions }
            .toSet()
        if (permission !in allowed && Permission.SUPERUSER !in allowed) {
            throw AuthorizationException("actor ${actor.actorId} missing permission $permission")
        }
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

package com.reef.arena.controlplane.application

import com.reef.arena.controlplane.arena.ArenaBot
import com.reef.arena.controlplane.arena.ArenaBotMetadata
import com.reef.arena.controlplane.arena.ArenaBotRegistryStore
import com.reef.arena.controlplane.arena.ArenaBotVersion
import com.reef.arena.controlplane.arena.ArenaBotVersionStatus
import com.reef.arena.controlplane.arena.ArenaControlPlaneService
import com.reef.arena.controlplane.arena.ArenaLeaderboardEntry
import com.reef.arena.controlplane.arena.ArenaOperatorDecision
import com.reef.arena.controlplane.arena.ArenaQualificationReport
import com.reef.arena.controlplane.arena.ArenaRunBotResult
import com.reef.arena.controlplane.arena.ArenaRunBotVersionRef
import com.reef.arena.controlplane.arena.ArenaRunEnforcementEvent
import com.reef.arena.controlplane.arena.ArenaRunRecord
import com.reef.arena.controlplane.arena.ArenaRunStatus
import com.reef.arena.controlplane.arena.ArenaRuntimeConfigDescriptor
import com.reef.arena.controlplane.arena.RegisterArenaBotCommand
import com.reef.arena.controlplane.arena.RegisterArenaBotVersionCommand
import com.reef.arena.controlplane.arena.RegisterArenaRunCommand
import com.reef.platform.api.AccountRiskControlStore
import com.reef.platform.api.AccountRiskDecision
import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.AdminTrustState
import com.reef.platform.application.admin.AuthorizationException
import com.reef.platform.application.admin.AdminActor
import java.time.Instant

private object ArenaPermission {
    const val Admin = "arena.admin"
}

/**
 * Arena's admin use cases.
 *
 * This is deliberately not a copy of Reef's admin application service. Reef owns
 * reference data, roles, post-trade configuration, simulation controls and runtime
 * audit persistence; loading Arena must not mutate any of those resources.
 */
data class ArenaBotVersionDecisionCommand(val botId: String, val versionId: String, val status: ArenaBotVersionStatus, val reason: String)
data class ArenaBotRegistrationCommand(val botId: String, val fileName: String, val name: String, val publisher: String, val email: String, val description: String = "", val version: String = "")
data class ArenaBotVersionRegistrationCommand(val botId: String, val versionId: String, val sourceHash: String, val artifactHash: String, val sdkVersion: String, val apiVersion: String, val dependencyManifestHash: String)
data class ArenaRunRegistrationCommand(val runId: String, val modeId: String, val scenarioId: String, val seed: Long, val policyVersion: String, val policyEnvelopeHash: String, val scoringPolicyVersion: String, val scoringPolicyHash: String, val economicPolicyVersion: String, val economicPolicyHash: String, val botVersions: List<ArenaRunBotVersionRef>)
data class ArenaRunStatusCommand(val runId: String, val status: ArenaRunStatus)
data class ArenaRunBotResultIngestionCommand(val runId: String, val botId: String, val versionId: String, val scoringPolicyVersion: String, val scoringPolicyHash: String, val policyEnvelopeHash: String, val finalEquity: Long, val realizedPnl: Long, val maxDrawdown: Long, val actionsProposed: Int, val orderActionsProposed: Int, val dataCalls: Int, val signalsGenerated: Int, val disqualified: Boolean, val scoreEligible: Boolean = true, val publicLeaderboard: Boolean = true)
data class ArenaRunEnforcementEventIngestionCommand(val runId: String, val botId: String, val versionId: String, val decision: String, val reasonCode: String, val reason: String, val policyVersion: String, val countersJson: String)

class ArenaAdminApplicationService(
    private val arenaRegistryStore: ArenaBotRegistryStore,
    private val accountRiskControlStore: AccountRiskControlStore? = null,
    private val adminIdentityService: AdminIdentityService? = null,
    private val now: () -> Instant = { Instant.now() }
) {
    fun registerArenaBot(actor: AdminActor, command: ArenaBotRegistrationCommand): ArenaBot {
        requirePermission(actor)
        return controlPlane().registerBot(RegisterArenaBotCommand(command.botId, command.fileName, ArenaBotMetadata(command.name, command.publisher, command.email, command.description, command.version)))
    }

    fun registerArenaBotVersion(actor: AdminActor, command: ArenaBotVersionRegistrationCommand): ArenaBotVersion {
        requirePermission(actor)
        return controlPlane().registerVersion(RegisterArenaBotVersionCommand(command.botId, command.versionId, command.sourceHash, command.artifactHash, command.sdkVersion, command.apiVersion, command.dependencyManifestHash))
    }

    fun transitionArenaBotVersion(actor: AdminActor, command: ArenaBotVersionDecisionCommand): ArenaBotVersion {
        requirePermission(actor)
        return controlPlane().transitionVersion(command.botId, command.versionId, command.status, actor.actorId, command.reason, actor.correlationId).also { syncArenaBotRiskControl(command, it) }
    }

    fun arenaBot(actor: AdminActor, botId: String): ArenaBot? = authorized(actor) { arenaRegistryStore.bot(botId) }
    fun arenaBotForOwnerScopedConfig(botId: String): ArenaBot? = arenaRegistryStore.bot(botId)
    fun arenaBots(actor: AdminActor, limit: Int = 50): List<ArenaBot> = authorized(actor) { arenaRegistryStore.bots(limit) }
    fun arenaBotsById(botIds: List<String>, limit: Int = 50): List<ArenaBot> = botIds.distinct().take(limit.coerceIn(1, 500)).mapNotNull(arenaRegistryStore::bot)
    fun arenaBotVersion(actor: AdminActor, botId: String, versionId: String): ArenaBotVersion? = authorized(actor) { arenaRegistryStore.version(botId, versionId) }
    fun arenaQualificationReports(actor: AdminActor, botId: String, versionId: String): List<ArenaQualificationReport> = authorized(actor) { arenaRegistryStore.qualificationReports(botId, versionId) }
    fun arenaOperatorDecisions(actor: AdminActor, botId: String, versionId: String): List<ArenaOperatorDecision> = authorized(actor) { arenaRegistryStore.operatorDecisions(botId, versionId) }

    fun registerArenaRun(actor: AdminActor, command: ArenaRunRegistrationCommand): ArenaRunRecord = authorized(actor) {
        controlPlane().registerRun(RegisterArenaRunCommand(command.runId, command.modeId, command.scenarioId, command.seed, command.policyVersion, command.policyEnvelopeHash, command.scoringPolicyVersion, command.scoringPolicyHash, command.economicPolicyVersion, command.economicPolicyHash, command.botVersions))
    }
    fun updateArenaRunStatus(actor: AdminActor, command: ArenaRunStatusCommand): ArenaRunRecord = authorized(actor) { controlPlane().updateRunStatus(command.runId, command.status) }
    fun arenaRun(actor: AdminActor, runId: String): ArenaRunRecord? = authorized(actor) { arenaRegistryStore.runRecord(runId) }
    fun arenaRuns(actor: AdminActor, limit: Int): List<ArenaRunRecord> = authorized(actor) { arenaRegistryStore.runs(limit) }
    fun arenaRunBotResults(actor: AdminActor, runId: String): List<ArenaRunBotResult> = authorized(actor) { controlPlane().runBotResults(runId) }
    fun arenaRunEnforcementEvents(actor: AdminActor, runId: String): List<ArenaRunEnforcementEvent> = authorized(actor) { controlPlane().runEnforcementEvents(runId) }

    fun recordArenaRunBotResult(actor: AdminActor, command: ArenaRunBotResultIngestionCommand): ArenaRunBotResult = authorized(actor) {
        controlPlane().recordRunBotResult(ArenaRunBotResult(command.runId, command.botId, command.versionId, command.scoringPolicyVersion, command.scoringPolicyHash, command.policyEnvelopeHash, command.finalEquity, command.realizedPnl, command.maxDrawdown, command.actionsProposed, command.orderActionsProposed, command.dataCalls, command.signalsGenerated, command.disqualified, command.scoreEligible, command.publicLeaderboard, now()))
    }
    fun recordArenaRunEnforcementEvent(actor: AdminActor, command: ArenaRunEnforcementEventIngestionCommand): ArenaRunEnforcementEvent = authorized(actor) {
        controlPlane().recordRunEnforcementEvent(ArenaRunEnforcementEvent(command.runId, command.botId, command.versionId, command.decision, command.reasonCode, command.reason, command.policyVersion, command.countersJson, now()))
    }
    fun arenaRuntimeConfigDescriptors(actor: AdminActor, botId: String, versionId: String): List<ArenaRuntimeConfigDescriptor> = authorized(actor) { arenaRegistryStore.runtimeConfigDescriptors(botId, versionId) }
    fun arenaLeaderboard(actor: AdminActor, modeId: String, scoringPolicyVersion: String, limit: Int = 50): List<ArenaLeaderboardEntry> = authorized(actor) { arenaRegistryStore.leaderboard(modeId, scoringPolicyVersion, limit) }
    fun arenaLeaderboardPublic(modeId: String, scoringPolicyVersion: String, limit: Int = 50): List<ArenaLeaderboardEntry> = arenaRegistryStore.leaderboard(modeId, scoringPolicyVersion, limit)

    private fun controlPlane() = ArenaControlPlaneService(arenaRegistryStore, now)
    private inline fun <T> authorized(actor: AdminActor, block: () -> T): T { requirePermission(actor); return block() }
    private fun requirePermission(actor: AdminActor) {
        if (!actor.actorId.startsWith("user-gh-")) return
        val identity = adminIdentityService ?: throw AuthorizationException("Arena identity service is not configured")
        val user = identity.user(actor.actorId) ?: throw AuthorizationException("unknown Arena actor ${actor.actorId}")
        val roles = identity.rolesForUser(actor.actorId).map { it.roleId }.toSet()
        if (user.trustState != AdminTrustState.Trusted || (AdminIdentityService.RoleOperator !in roles && AdminIdentityService.RolePlatformAdmin !in roles)) {
            throw AuthorizationException("actor ${actor.actorId} missing permission ${ArenaPermission.Admin}")
        }
    }

    private fun syncArenaBotRiskControl(command: ArenaBotVersionDecisionCommand, updated: ArenaBotVersion) {
        val riskStore = accountRiskControlStore ?: return
        val decision = when (updated.status) {
            ArenaBotVersionStatus.Approved, ArenaBotVersionStatus.Active -> AccountRiskDecision.ALLOW
            ArenaBotVersionStatus.Suspended, ArenaBotVersionStatus.Quarantined, ArenaBotVersionStatus.Banned, ArenaBotVersionStatus.Archived -> AccountRiskDecision.DISABLED_BOT
            ArenaBotVersionStatus.Draft, ArenaBotVersionStatus.Submitted, ArenaBotVersionStatus.ChecksPassed -> return
        }
        riskStore.upsertControl("BOT", updated.botId, decision, "arena ${updated.status.name}: ${command.reason}")
    }
}

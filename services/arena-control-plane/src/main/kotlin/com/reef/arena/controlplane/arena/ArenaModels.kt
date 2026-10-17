package com.reef.arena.controlplane.arena

import java.time.Instant

data class ArenaBotMetadata(
    val name: String,
    val publisher: String,
    val email: String,
    val description: String = "",
    val version: String = ""
)

data class ArenaBot(
    val botId: String,
    val fileName: String,
    val metadata: ArenaBotMetadata,
    val createdAt: Instant
)

data class ArenaBotVersion(
    val botId: String,
    val versionId: String,
    val sourceHash: String,
    val artifactHash: String,
    val sdkVersion: String,
    val apiVersion: String,
    val dependencyManifestHash: String,
    val status: ArenaBotVersionStatus,
    val createdAt: Instant
)

enum class ArenaBotVersionStatus {
    Draft,
    Submitted,
    ChecksPassed,
    Approved,
    Active,
    Suspended,
    Quarantined,
    Banned,
    Archived
}

data class ArenaQualificationReport(
    val botId: String,
    val versionId: String,
    val reportId: String,
    val status: ArenaQualificationStatus,
    val issues: List<String>,
    val policyVersion: String,
    val createdAt: Instant
)

enum class ArenaQualificationStatus {
    Passed,
    Failed
}

data class ArenaOperatorDecision(
    val botId: String,
    val versionId: String,
    val fromStatus: ArenaBotVersionStatus,
    val toStatus: ArenaBotVersionStatus,
    val actorId: String,
    val reason: String,
    val correlationId: String,
    val occurredAt: Instant
)

data class ArenaRunBotVersionRef(
    val botId: String,
    val versionId: String
)

data class ArenaRunRecord(
    val runId: String,
    val modeId: String,
    val scenarioId: String,
    val seed: Long,
    val policyVersion: String,
    val admissionWindowId: String,
    val rosterSnapshotId: String,
    val rosterSnapshotHash: String,
    val seedSetHash: String,
    val actorProfileVersion: String,
    val actorProfileHash: String,
    val riskPolicyHash: String,
    val policyEnvelopeHash: String,
    val scoringPolicyVersion: String,
    val scoringPolicyHash: String,
    val economicPolicyVersion: String,
    val economicPolicyHash: String,
    val botVersions: List<ArenaRunBotVersionRef>,
    val status: ArenaRunStatus,
    val createdAt: Instant,
    val completedAt: Instant? = null
)

data class ArenaRunBotResult(
    val runId: String,
    val botId: String,
    val versionId: String,
    val scoringPolicyVersion: String,
    val scoringPolicyHash: String,
    val policyEnvelopeHash: String,
    val finalEquity: Long,
    val realizedPnl: Long,
    val maxDrawdown: Long,
    val actionsProposed: Int,
    val orderActionsProposed: Int,
    val dataCalls: Int,
    val signalsGenerated: Int,
    val disqualified: Boolean,
    val scoreEligible: Boolean = true,
    val publicLeaderboard: Boolean = true,
    val createdAt: Instant
)

data class ArenaRunEnforcementEvent(
    val runId: String,
    val botId: String,
    val versionId: String,
    val decision: String,
    val reasonCode: String,
    val reason: String,
    val policyVersion: String,
    val countersJson: String,
    val occurredAt: Instant
)

data class ArenaLeaderboardEntry(
    val rank: Int,
    val runId: String,
    val botId: String,
    val botName: String,
    val ownerHandle: String,
    val versionId: String,
    val scoringPolicyVersion: String,
    val finalEquity: Long,
    val realizedPnl: Long,
    val maxDrawdown: Long,
    val disqualified: Boolean
)

enum class ArenaRunStatus {
    Planned,
    Running,
    Completed,
    Failed,
    Cancelled
}

data class ArenaRuntimeConfigDescriptor(
    val botId: String,
    val versionId: String,
    val key: String,
    val provider: ArenaRuntimeConfigProvider,
    val secretPath: String,
    val required: Boolean,
    val description: String = ""
)

enum class ArenaRuntimeConfigProvider {
    OpenBao
}

data class RegisterArenaBotCommand(
    val botId: String,
    val fileName: String,
    val metadata: ArenaBotMetadata
)

data class RegisterArenaBotVersionCommand(
    val botId: String,
    val versionId: String,
    val sourceHash: String,
    val artifactHash: String,
    val sdkVersion: String,
    val apiVersion: String,
    val dependencyManifestHash: String
)

data class RegisterArenaRunCommand(
    val runId: String,
    val modeId: String,
    val scenarioId: String,
    val seed: Long,
    val policyVersion: String,
    val admissionWindowId: String,
    val rosterSnapshotId: String,
    val rosterSnapshotHash: String,
    val seedSetHash: String,
    val actorProfileVersion: String,
    val actorProfileHash: String,
    val riskPolicyHash: String,
    val policyEnvelopeHash: String,
    val scoringPolicyVersion: String,
    val scoringPolicyHash: String,
    val economicPolicyVersion: String,
    val economicPolicyHash: String,
    val botVersions: List<ArenaRunBotVersionRef>
)

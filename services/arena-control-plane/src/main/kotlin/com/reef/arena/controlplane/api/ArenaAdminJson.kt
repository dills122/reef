package com.reef.arena.controlplane.api

import com.reef.platform.api.*
import com.reef.platform.application.admin.AdminTrustState
import com.reef.platform.application.analytics.BotRunPerformanceSummaryRecord
import com.reef.platform.application.analytics.SimulationRunExportRecord
import com.reef.arena.controlplane.arena.ArenaBot
import com.reef.arena.controlplane.arena.ArenaBotOwnershipState
import com.reef.arena.controlplane.arena.ArenaBotVersion
import com.reef.arena.controlplane.arena.ArenaBotVersionStatus
import com.reef.arena.controlplane.arena.ArenaLeaderboardEntry
import com.reef.arena.controlplane.arena.ArenaOperatorDecision
import com.reef.arena.controlplane.arena.ArenaQualificationReport
import com.reef.arena.controlplane.arena.ArenaRunBotResult
import com.reef.arena.controlplane.arena.ArenaRunEnforcementEvent
import com.reef.arena.controlplane.arena.ArenaRunRecord
import com.reef.arena.controlplane.arena.ArenaRunStatus
import com.reef.arena.controlplane.arena.ArenaRuntimeConfigDescriptor
import com.reef.arena.controlplane.arena.ArenaSubmissionAdmission
import com.reef.arena.controlplane.arena.ArenaAdmissionWindow
import com.reef.arena.controlplane.arena.ArenaRosterEntry
import com.reef.arena.controlplane.arena.ArenaRosterSnapshot
import com.reef.arena.controlplane.arena.ArenaRosterRemoval
import com.reef.arena.controlplane.arena.ArenaRunEligibilityDecision
import java.time.Instant

/** JSON response mappers and request-value parsing helpers for [ArenaAdminGateway]. */

internal fun analyticsRunExportJson(record: SimulationRunExportRecord): Map<String, Any?> {
    return mapOf(
        "runId" to record.runId,
        "scenarioId" to record.scenarioId,
        "runKind" to record.runKind,
        "source" to record.source,
        "gitSha" to record.gitSha,
        "profile" to record.profile,
        "startedAt" to record.startedAt?.toString(),
        "completedAt" to record.completedAt?.toString(),
        "exportedAt" to record.exportedAt.toString(),
        "status" to record.status,
        "counts" to mapOf(
            "attempted" to record.attemptedCount,
            "accepted" to record.acceptedCount,
            "completed" to record.completedCount,
            "materialized" to record.materializedCount,
            "projected" to record.projectedCount,
            "failed" to record.failedCount
        ),
        "latencyMs" to mapOf(
            "p50" to record.p50LatencyMs,
            "p95" to record.p95LatencyMs,
            "p99" to record.p99LatencyMs
        ),
        "artifacts" to JsonCodec.rawJsonOrText(record.artifactManifestJson),
        "summary" to JsonCodec.rawJsonOrText(record.summaryJson)
    )
}

internal fun analyticsRunBotSummaryJson(record: BotRunPerformanceSummaryRecord): Map<String, Any?> {
    return mapOf(
        "runId" to record.runId,
        "botId" to record.botId,
        "scenarioId" to record.scenarioId,
        "profile" to record.profile,
        "source" to record.source,
        "completedAt" to record.completedAt?.toString(),
        "exportedAt" to record.exportedAt.toString(),
        "projectedAt" to record.projectedAt.toString(),
        "finalEquity" to record.finalEquity,
        "realizedPnl" to record.realizedPnl,
        "maxDrawdown" to record.maxDrawdown,
        "failCount" to record.failCount,
        "commandCount" to record.commandCount,
        "settlementScoreSummary" to JsonCodec.rawJsonOrText(record.settlementScoreSummaryJson),
        "sourceSummary" to JsonCodec.rawJsonOrText(record.sourceSummaryJson)
    )
}

internal fun arenaAdmissionWindowJson(window: ArenaAdmissionWindow): Map<String, Any?> = mapOf(
    "windowId" to window.windowId,
    "policyVersion" to window.policyVersion,
    "scheduledStart" to window.scheduledStart.toString(),
    "inviteDecisionCutoff" to window.inviteDecisionCutoff.toString(),
    "mergeReadinessCutoff" to window.mergeReadinessCutoff.toString(),
    "rosterLockAt" to window.rosterLockAt.toString(),
    "operationalRecheckAt" to window.operationalRecheckAt.toString(),
    "runInstantiationAt" to window.runInstantiationAt.toString(),
    "displayTimeZone" to window.displayTimeZone,
    "createdAt" to window.createdAt.toString()
)

internal fun arenaEligibilityDecisionJson(decision: ArenaRunEligibilityDecision): Map<String, Any?> = mapOf(
    "evaluationId" to decision.evaluationId,
    "windowId" to decision.windowId,
    "botId" to decision.botId,
    "versionId" to decision.versionId,
    "outcome" to decision.outcome.dbValue,
    "reasonCodes" to decision.reasons.map { it.code },
    "sourceHash" to decision.sourceHash,
    "artifactHash" to decision.artifactHash,
    "configHash" to decision.configHash,
    "evaluatedAt" to decision.evaluatedAt.toString(),
    "correlationId" to decision.correlationId
)

internal fun arenaRosterJson(
    roster: ArenaRosterSnapshot,
    removals: List<ArenaRosterRemoval> = emptyList()
): Map<String, Any?> {
    val removedEntries = removals.map { it.botId to it.versionId }.toSet()
    val entries = roster.entries.map(::arenaRosterEntryJson)
    return mapOf(
        "snapshotId" to roster.snapshotId,
        "windowId" to roster.windowId,
        "snapshotHash" to roster.snapshotHash,
        "maxBots" to roster.maxBots,
        "lockedAt" to roster.lockedAt.toString(),
        "lockedBy" to roster.lockedBy,
        "correlationId" to roster.correlationId,
        "policy" to mapOf(
            "modeId" to roster.policy.modeId,
            "scenarioId" to roster.policy.scenarioId,
            "seedSetHash" to roster.policy.seedSetHash,
            "actorProfileVersion" to roster.policy.actorProfileVersion,
            "actorProfileHash" to roster.policy.actorProfileHash,
            "riskPolicyVersion" to roster.policy.riskPolicyVersion,
            "riskPolicyHash" to roster.policy.riskPolicyHash,
            "scoringPolicyVersion" to roster.policy.scoringPolicyVersion,
            "scoringPolicyHash" to roster.policy.scoringPolicyHash,
            "economicPolicyVersion" to roster.policy.economicPolicyVersion,
            "economicPolicyHash" to roster.policy.economicPolicyHash
        ),
        "entries" to entries,
        "effectiveEntries" to roster.entries
            .filterNot { (it.botId to it.versionId) in removedEntries }
            .map(::arenaRosterEntryJson),
        "removals" to removals.map(::arenaRosterRemovalJson)
    )
}

internal fun arenaRosterEntryJson(entry: ArenaRosterEntry): Map<String, Any?> = mapOf(
    "botOrder" to entry.botOrder,
    "botId" to entry.botId,
    "versionId" to entry.versionId,
    "priority" to entry.priority,
    "sourceHash" to entry.sourceHash,
    "artifactHash" to entry.artifactHash,
    "configHash" to entry.configHash,
    "eligibilityEvaluationId" to entry.eligibilityEvaluationId
)

internal fun arenaRosterRemovalJson(removal: ArenaRosterRemoval): Map<String, Any?> = mapOf(
    "removalId" to removal.removalId,
    "windowId" to removal.windowId,
    "snapshotId" to removal.snapshotId,
    "botId" to removal.botId,
    "versionId" to removal.versionId,
    "reasonCode" to removal.reason.code,
    "detail" to removal.detail,
    "removedAt" to removal.removedAt.toString(),
    "removedBy" to removal.removedBy,
    "correlationId" to removal.correlationId
)

internal fun longValue(root: JsonDocument, nested: JsonDocument, rootKey: String, nestedKey: String): Long {
    return root.string(rootKey).ifBlank { nested.string(nestedKey) }.toLongOrNull() ?: 0L
}

internal fun doubleValue(root: JsonDocument, nested: JsonDocument, rootKey: String, nestedKey: String): Double? {
    return root.string(rootKey).ifBlank { nested.string(nestedKey) }.toDoubleOrNull()
}

internal fun instantOrNull(value: String): Instant? {
    if (value.isBlank()) return null
    return Instant.parse(value)
}

internal fun requiredBoolean(json: JsonDocument, key: String): Boolean = when (json.string(key).lowercase()) {
    "true" -> true
    "false" -> false
    else -> throw IllegalArgumentException("$key must be true or false")
}

internal fun normalizedRawJson(raw: String, fallback: String): String {
    return if (raw.isBlank()) fallback else JsonCodec.writeNode(JsonCodec.rawJsonOrText(raw))
}

internal fun arenaBotJson(bot: ArenaBot, owners: List<ArenaBotOwnerMetadata>): Map<String, Any?> {
    return mapOf(
        "botId" to bot.botId,
        "fileName" to bot.fileName,
        "metadata" to mapOf(
            "name" to bot.metadata.name,
            "publisher" to bot.metadata.publisher,
            "email" to bot.metadata.email,
            "description" to bot.metadata.description,
            "version" to bot.metadata.version
        ),
        "owners" to owners.map { owner ->
            mapOf(
                "reefUserId" to owner.reefUserId,
                "githubLogin" to owner.githubLogin,
                "displayName" to owner.displayName,
                "trustState" to owner.trustState.dbValue,
                "ownershipState" to owner.ownershipState.dbValue,
                "assignedAt" to owner.assignedAt.toString()
            )
        },
        "createdAt" to bot.createdAt.toString()
    )
}

internal data class ArenaBotOwnerMetadata(
    val reefUserId: String,
    val githubLogin: String,
    val displayName: String,
    val trustState: com.reef.platform.application.admin.AdminTrustState,
    val ownershipState: ArenaBotOwnershipState,
    val assignedAt: Instant
)

internal fun arenaBotVersionJson(version: ArenaBotVersion): Map<String, Any?> {
    return mapOf(
        "botId" to version.botId,
        "versionId" to version.versionId,
        "sourceHash" to version.sourceHash,
        "artifactHash" to version.artifactHash,
        "sdkVersion" to version.sdkVersion,
        "apiVersion" to version.apiVersion,
        "dependencyManifestHash" to version.dependencyManifestHash,
        "status" to version.status.name,
        "createdAt" to version.createdAt.toString()
    )
}

internal fun arenaQualificationReportJson(report: ArenaQualificationReport): Map<String, Any?> {
    return mapOf(
        "botId" to report.botId,
        "versionId" to report.versionId,
        "reportId" to report.reportId,
        "status" to report.status.name,
        "issues" to report.issues,
        "policyVersion" to report.policyVersion,
        "createdAt" to report.createdAt.toString()
    )
}

internal fun arenaOperatorDecisionJson(decision: ArenaOperatorDecision): Map<String, Any?> {
    return mapOf(
        "botId" to decision.botId,
        "versionId" to decision.versionId,
        "fromStatus" to decision.fromStatus.name,
        "toStatus" to decision.toStatus.name,
        "actorId" to decision.actorId,
        "reason" to decision.reason,
        "correlationId" to decision.correlationId,
        "occurredAt" to decision.occurredAt.toString()
    )
}

internal fun arenaRuntimeConfigDescriptorJson(descriptor: ArenaRuntimeConfigDescriptor): Map<String, Any?> {
    return mapOf(
        "botId" to descriptor.botId,
        "versionId" to descriptor.versionId,
        "key" to descriptor.key,
        "provider" to descriptor.provider.name,
        "secretPath" to descriptor.secretPath,
        "required" to descriptor.required,
        "description" to descriptor.description
    )
}

internal fun arenaRunJson(run: ArenaRunRecord): Map<String, Any?> {
    return mapOf(
        "runId" to run.runId,
        "modeId" to run.modeId,
        "scenarioId" to run.scenarioId,
        "seed" to run.seed,
        "policyVersion" to run.policyVersion,
        "admissionWindowId" to run.admissionWindowId,
        "rosterSnapshotId" to run.rosterSnapshotId,
        "rosterSnapshotHash" to run.rosterSnapshotHash,
        "seedSetHash" to run.seedSetHash,
        "actorProfileVersion" to run.actorProfileVersion,
        "actorProfileHash" to run.actorProfileHash,
        "riskPolicyHash" to run.riskPolicyHash,
        "policyEnvelopeHash" to run.policyEnvelopeHash,
        "scoringPolicyVersion" to run.scoringPolicyVersion,
        "scoringPolicyHash" to run.scoringPolicyHash,
        "economicPolicyVersion" to run.economicPolicyVersion,
        "economicPolicyHash" to run.economicPolicyHash,
        "botVersions" to run.botVersions.map {
            mapOf("botId" to it.botId, "versionId" to it.versionId)
        },
        "status" to run.status.name,
        "createdAt" to run.createdAt.toString(),
        "completedAt" to run.completedAt?.toString()
    )
}

internal fun arenaRunBotResultJson(result: ArenaRunBotResult): Map<String, Any?> {
    return mapOf(
        "runId" to result.runId,
        "botId" to result.botId,
        "versionId" to result.versionId,
        "scoringPolicyVersion" to result.scoringPolicyVersion,
        "scoringPolicyHash" to result.scoringPolicyHash,
        "policyEnvelopeHash" to result.policyEnvelopeHash,
        "finalEquity" to result.finalEquity,
        "realizedPnl" to result.realizedPnl,
        "maxDrawdown" to result.maxDrawdown,
        "actionsProposed" to result.actionsProposed,
        "orderActionsProposed" to result.orderActionsProposed,
        "dataCalls" to result.dataCalls,
        "signalsGenerated" to result.signalsGenerated,
        "disqualified" to result.disqualified,
        "scoreEligible" to result.scoreEligible,
        "publicLeaderboard" to result.publicLeaderboard,
        "createdAt" to result.createdAt.toString()
    )
}

internal fun arenaRunEnforcementEventJson(event: ArenaRunEnforcementEvent): Map<String, Any?> {
    return mapOf(
        "runId" to event.runId,
        "botId" to event.botId,
        "versionId" to event.versionId,
        "decision" to event.decision,
        "reasonCode" to event.reasonCode,
        "reason" to event.reason,
        "policyVersion" to event.policyVersion,
        "countersJson" to event.countersJson,
        "occurredAt" to event.occurredAt.toString()
    )
}

internal fun arenaSubmissionAdmissionJson(admission: ArenaSubmissionAdmission): Map<String, Any?> = mapOf(
    "repository" to admission.repository,
    "pullRequestNumber" to admission.pullRequestNumber,
    "botId" to admission.botId,
    "headRepository" to admission.headRepository,
    "headOwnerLogin" to admission.headOwnerLogin,
    "githubUserId" to admission.githubUserId,
    "githubLogin" to admission.githubLogin,
    "headSha" to admission.headSha,
    "state" to admission.state.dbValue,
    "invitationActor" to admission.invitationActor,
    "invitationReason" to admission.invitationReason,
    "invitedAt" to admission.invitedAt?.toString(),
    "createdAt" to admission.createdAt.toString(),
    "updatedAt" to admission.updatedAt.toString()
)

internal fun arenaLeaderboardEntryJson(entry: ArenaLeaderboardEntry): Map<String, Any?> {
    return mapOf(
        "rank" to entry.rank,
        "runId" to entry.runId,
        "botId" to entry.botId,
        "botName" to entry.botName,
        "ownerHandle" to entry.ownerHandle,
        "versionId" to entry.versionId,
        "scoringPolicyVersion" to entry.scoringPolicyVersion,
        "finalEquity" to entry.finalEquity,
        "realizedPnl" to entry.realizedPnl,
        "maxDrawdown" to entry.maxDrawdown,
        "disqualified" to entry.disqualified
    )
}

internal fun normalizeArenaBotVersionStatus(value: String): ArenaBotVersionStatus? {
    return when (value.trim().lowercase()) {
        "draft" -> ArenaBotVersionStatus.Draft
        "submitted" -> ArenaBotVersionStatus.Submitted
        "checks-passed", "checks_passed" -> ArenaBotVersionStatus.ChecksPassed
        "approved" -> ArenaBotVersionStatus.Approved
        "active" -> ArenaBotVersionStatus.Active
        "suspended", "freeze", "frozen" -> ArenaBotVersionStatus.Suspended
        "quarantined", "quarantine" -> ArenaBotVersionStatus.Quarantined
        "banned", "ban" -> ArenaBotVersionStatus.Banned
        "archived", "archive" -> ArenaBotVersionStatus.Archived
        else -> null
    }
}

internal fun normalizeArenaRunStatus(value: String): ArenaRunStatus? {
    return when (value.trim().lowercase()) {
        "planned" -> ArenaRunStatus.Planned
        "running" -> ArenaRunStatus.Running
        "completed", "complete" -> ArenaRunStatus.Completed
        "failed", "fail" -> ArenaRunStatus.Failed
        "cancelled", "canceled", "cancel" -> ArenaRunStatus.Cancelled
        else -> null
    }
}

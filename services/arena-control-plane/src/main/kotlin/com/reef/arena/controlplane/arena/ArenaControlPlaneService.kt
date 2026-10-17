package com.reef.arena.controlplane.arena

import java.time.Instant

class ArenaControlPlaneService(
    private val store: ArenaBotRegistryStore,
    private val now: () -> Instant = { Instant.now() }
) {
    fun registerBot(command: RegisterArenaBotCommand): ArenaBot {
        require(command.botId.isNotBlank()) { "botId is required" }
        require(command.fileName.isNotBlank()) { "fileName is required" }
        require(command.metadata.name.isNotBlank()) { "metadata.name is required" }
        require(command.metadata.publisher.isNotBlank()) { "metadata.publisher is required" }
        require(BASIC_EMAIL_REGEX.matches(command.metadata.email)) { "metadata.email must be valid" }
        require(store.bot(command.botId) == null) { "botId already exists: ${command.botId}" }
        require(store.botByFileName(command.fileName) == null) { "bot fileName already exists: ${command.fileName}" }

        val bot = ArenaBot(
            botId = command.botId,
            fileName = command.fileName,
            metadata = command.metadata,
            createdAt = now()
        )
        store.saveBot(bot)
        return bot
    }

    fun registerVersion(command: RegisterArenaBotVersionCommand): ArenaBotVersion {
        require(store.bot(command.botId) != null) { "unknown botId: ${command.botId}" }
        require(command.versionId.isNotBlank()) { "versionId is required" }
        require(command.sourceHash.isNotBlank()) { "sourceHash is required" }
        require(command.artifactHash.isNotBlank()) { "artifactHash is required" }
        require(command.sdkVersion.isNotBlank()) { "sdkVersion is required" }
        require(command.apiVersion.isNotBlank()) { "apiVersion is required" }
        require(command.dependencyManifestHash.isNotBlank()) { "dependencyManifestHash is required" }
        require(store.version(command.botId, command.versionId) == null) {
            "versionId already exists for botId: ${command.botId}/${command.versionId}"
        }

        val version = ArenaBotVersion(
            botId = command.botId,
            versionId = command.versionId,
            sourceHash = command.sourceHash,
            artifactHash = command.artifactHash,
            sdkVersion = command.sdkVersion,
            apiVersion = command.apiVersion,
            dependencyManifestHash = command.dependencyManifestHash,
            status = ArenaBotVersionStatus.Draft,
            createdAt = now()
        )
        store.saveVersion(version)
        return version
    }

    fun recordQualificationReport(
        botId: String,
        versionId: String,
        reportId: String,
        status: ArenaQualificationStatus,
        issues: List<String>,
        policyVersion: String
    ): ArenaQualificationReport {
        requireVersion(botId, versionId)
        require(reportId.isNotBlank()) { "reportId is required" }
        require(policyVersion.isNotBlank()) { "policyVersion is required" }
        val report = ArenaQualificationReport(
            botId = botId,
            versionId = versionId,
            reportId = reportId,
            status = status,
            issues = issues,
            policyVersion = policyVersion,
            createdAt = now()
        )
        store.saveQualificationReport(report)
        return report
    }

    fun transitionVersion(
        botId: String,
        versionId: String,
        toStatus: ArenaBotVersionStatus,
        actorId: String,
        reason: String,
        correlationId: String
    ): ArenaBotVersion {
        require(actorId.isNotBlank()) { "actorId is required" }
        require(reason.isNotBlank()) { "reason is required" }
        val version = requireVersion(botId, versionId)
        require(canTransition(version.status, toStatus)) {
            "invalid bot version transition: ${version.status} -> $toStatus"
        }
        val updated = version.copy(status = toStatus)
        store.saveVersionTransition(
            updated,
            ArenaOperatorDecision(
                botId = botId,
                versionId = versionId,
                fromStatus = version.status,
                toStatus = toStatus,
                actorId = actorId,
                reason = reason,
                correlationId = correlationId,
                occurredAt = now()
            )
        )
        return updated
    }

    fun mayAcceptRuntimeCommands(botId: String, versionId: String): Boolean {
        return when (requireVersion(botId, versionId).status) {
            ArenaBotVersionStatus.Approved,
            ArenaBotVersionStatus.Active -> true
            ArenaBotVersionStatus.Draft,
            ArenaBotVersionStatus.Submitted,
            ArenaBotVersionStatus.ChecksPassed,
            ArenaBotVersionStatus.Suspended,
            ArenaBotVersionStatus.Quarantined,
            ArenaBotVersionStatus.Banned,
            ArenaBotVersionStatus.Archived -> false
        }
    }

    fun registerRun(command: RegisterArenaRunCommand): ArenaRunRecord {
        require(command.runId.isNotBlank()) { "runId is required" }
        require(command.modeId.isNotBlank()) { "modeId is required" }
        require(command.scenarioId.isNotBlank()) { "scenarioId is required" }
        require(command.policyVersion.isNotBlank()) { "policyVersion is required" }
        require(command.admissionWindowId.isNotBlank()) { "admissionWindowId is required" }
        require(command.rosterSnapshotId.isNotBlank()) { "rosterSnapshotId is required" }
        requirePolicyHash(command.rosterSnapshotHash, "rosterSnapshotHash")
        requirePolicyHash(command.seedSetHash, "seedSetHash")
        require(command.actorProfileVersion.isNotBlank()) { "actorProfileVersion is required" }
        requirePolicyHash(command.actorProfileHash, "actorProfileHash")
        requirePolicyHash(command.riskPolicyHash, "riskPolicyHash")
        requirePolicyHash(command.policyEnvelopeHash, "policyEnvelopeHash")
        require(command.scoringPolicyVersion.isNotBlank()) { "scoringPolicyVersion is required" }
        requirePolicyHash(command.scoringPolicyHash, "scoringPolicyHash")
        require(command.economicPolicyVersion.isNotBlank()) { "economicPolicyVersion is required" }
        requirePolicyHash(command.economicPolicyHash, "economicPolicyHash")
        require(command.botVersions.isNotEmpty()) { "botVersions is required" }
        require(store.runRecord(command.runId) == null) { "runId already exists: ${command.runId}" }
        command.botVersions.forEach { ref ->
            require(mayAcceptRuntimeCommands(ref.botId, ref.versionId)) {
                "bot version is not approved for runtime commands: ${ref.botId}/${ref.versionId}"
            }
        }

        val run = ArenaRunRecord(
            runId = command.runId,
            modeId = command.modeId,
            scenarioId = command.scenarioId,
            seed = command.seed,
            policyVersion = command.policyVersion,
            admissionWindowId = command.admissionWindowId,
            rosterSnapshotId = command.rosterSnapshotId,
            rosterSnapshotHash = command.rosterSnapshotHash,
            seedSetHash = command.seedSetHash,
            actorProfileVersion = command.actorProfileVersion,
            actorProfileHash = command.actorProfileHash,
            riskPolicyHash = command.riskPolicyHash,
            policyEnvelopeHash = command.policyEnvelopeHash,
            scoringPolicyVersion = command.scoringPolicyVersion,
            scoringPolicyHash = command.scoringPolicyHash,
            economicPolicyVersion = command.economicPolicyVersion,
            economicPolicyHash = command.economicPolicyHash,
            botVersions = command.botVersions,
            status = ArenaRunStatus.Planned,
            createdAt = now()
        )
        store.saveRunRecord(run)
        return run
    }

    fun updateRunStatus(runId: String, status: ArenaRunStatus): ArenaRunRecord {
        val run = store.runRecord(runId) ?: error("unknown arena run: $runId")
        require(canTransitionRun(run.status, status)) {
            "invalid arena run transition: ${run.status} -> $status"
        }
        if (status == ArenaRunStatus.Completed) {
            require(store.runBotResults(runId).isNotEmpty()) { "arena run cannot publish scores without run bot results" }
        }
        val updated = run.copy(
            status = status,
            completedAt = if (status.isTerminal()) now() else run.completedAt
        )
        store.saveRunRecord(updated)
        return updated
    }

    fun recordRunBotResult(result: ArenaRunBotResult): ArenaRunBotResult {
        require(result.runId.isNotBlank()) { "runId is required" }
        require(result.botId.isNotBlank()) { "botId is required" }
        require(result.versionId.isNotBlank()) { "versionId is required" }
        require(result.scoringPolicyVersion.isNotBlank()) { "scoringPolicyVersion is required" }
        requirePolicyHash(result.scoringPolicyHash, "scoringPolicyHash")
        requirePolicyHash(result.policyEnvelopeHash, "policyEnvelopeHash")
        require(result.maxDrawdown >= 0) { "maxDrawdown must be nonnegative" }
        require(result.actionsProposed >= 0) { "actionsProposed must be nonnegative" }
        require(result.orderActionsProposed >= 0) { "orderActionsProposed must be nonnegative" }
        require(result.dataCalls >= 0) { "dataCalls must be nonnegative" }
        require(result.signalsGenerated >= 0) { "signalsGenerated must be nonnegative" }
        require(result.orderActionsProposed <= result.actionsProposed) {
            "orderActionsProposed must be less than or equal to actionsProposed"
        }
        val run = store.runRecord(result.runId) ?: error("unknown arena run: ${result.runId}")
        require(result.scoringPolicyVersion == run.scoringPolicyVersion) {
            "result scoring policy version does not match the accepted run policy lock"
        }
        require(result.scoringPolicyHash == run.scoringPolicyHash) {
            "result scoring policy hash does not match the accepted run policy lock"
        }
        require(result.policyEnvelopeHash == run.policyEnvelopeHash) {
            "result policy envelope hash does not match the accepted run policy lock"
        }
        require(run.botVersions.any { it.botId == result.botId && it.versionId == result.versionId }) {
            "bot version is not registered for arena run: ${result.botId}/${result.versionId}"
        }
        if (run.status == ArenaRunStatus.Completed) {
            val existing = store.runBotResults(result.runId).firstOrNull {
                it.botId == result.botId && it.versionId == result.versionId
            } ?: throw IllegalArgumentException("completed arena run results are immutable")
            require(existing.copy(createdAt = result.createdAt) == result) {
                "completed arena run results are immutable"
            }
            return existing
        }
        require(run.status == ArenaRunStatus.Running) {
            "run bot results may only be recorded while the arena run is running"
        }
        store.saveRunBotResult(result)
        return result
    }

    fun runBotResults(runId: String): List<ArenaRunBotResult> {
        require(runId.isNotBlank()) { "runId is required" }
        require(store.runRecord(runId) != null) { "unknown arena run: $runId" }
        return store.runBotResults(runId)
    }

    fun recordRunEnforcementEvent(event: ArenaRunEnforcementEvent): ArenaRunEnforcementEvent {
        require(event.runId.isNotBlank()) { "runId is required" }
        require(event.botId.isNotBlank()) { "botId is required" }
        require(event.versionId.isNotBlank()) { "versionId is required" }
        require(event.decision.isNotBlank()) { "decision is required" }
        require(event.reasonCode.isNotBlank()) { "reasonCode is required" }
        require(event.reason.isNotBlank()) { "reason is required" }
        require(event.policyVersion.isNotBlank()) { "policyVersion is required" }
        val run = store.runRecord(event.runId) ?: error("unknown arena run: ${event.runId}")
        require(run.botVersions.any { it.botId == event.botId && it.versionId == event.versionId }) {
            "bot version is not registered for arena run: ${event.botId}/${event.versionId}"
        }
        store.saveRunEnforcementEvent(event)
        return event
    }

    fun runEnforcementEvents(runId: String): List<ArenaRunEnforcementEvent> {
        require(runId.isNotBlank()) { "runId is required" }
        require(store.runRecord(runId) != null) { "unknown arena run: $runId" }
        return store.runEnforcementEvents(runId)
    }

    fun leaderboard(modeId: String, scoringPolicyVersion: String, limit: Int = 50): List<ArenaLeaderboardEntry> {
        require(modeId.isNotBlank()) { "modeId is required" }
        require(scoringPolicyVersion.isNotBlank()) { "scoringPolicyVersion is required" }
        return store.leaderboard(modeId, scoringPolicyVersion, limit.coerceIn(1, 500))
    }

    fun replaceRuntimeConfigDescriptors(
        botId: String,
        versionId: String,
        descriptors: List<ArenaRuntimeConfigDescriptor>
    ): List<ArenaRuntimeConfigDescriptor> {
        requireVersion(botId, versionId)
        val keys = descriptors.map { it.key }
        require(keys.distinct().size == keys.size) { "runtime config descriptor keys must be unique" }
        descriptors.forEach { descriptor ->
            require(descriptor.botId == botId) { "descriptor botId must match requested botId" }
            require(descriptor.versionId == versionId) { "descriptor versionId must match requested versionId" }
            require(descriptor.key.matches(RuntimeConfigKeyPattern)) {
                "runtime config key must be a simple identifier: ${descriptor.key}"
            }
            require(descriptor.secretPath.isNotBlank()) { "runtime config secretPath is required" }
        }
        store.replaceRuntimeConfigDescriptors(botId, versionId, descriptors)
        return store.runtimeConfigDescriptors(botId, versionId)
    }

    private fun requireVersion(botId: String, versionId: String): ArenaBotVersion {
        return store.version(botId, versionId) ?: error("unknown bot version: $botId/$versionId")
    }

    private fun canTransition(from: ArenaBotVersionStatus, to: ArenaBotVersionStatus): Boolean {
        if (from == to) return true
        if (to == ArenaBotVersionStatus.Archived) return true
        if (to == ArenaBotVersionStatus.Quarantined) return from != ArenaBotVersionStatus.Banned
        if (to == ArenaBotVersionStatus.Banned) return true
        return when (from) {
            ArenaBotVersionStatus.Draft -> to == ArenaBotVersionStatus.Submitted
            ArenaBotVersionStatus.Submitted -> to == ArenaBotVersionStatus.ChecksPassed
            ArenaBotVersionStatus.ChecksPassed -> to == ArenaBotVersionStatus.Approved
            ArenaBotVersionStatus.Approved -> to == ArenaBotVersionStatus.Active || to == ArenaBotVersionStatus.Suspended
            ArenaBotVersionStatus.Active -> to == ArenaBotVersionStatus.Suspended
            ArenaBotVersionStatus.Suspended -> to == ArenaBotVersionStatus.Active
            ArenaBotVersionStatus.Quarantined -> to == ArenaBotVersionStatus.Suspended
            ArenaBotVersionStatus.Banned,
            ArenaBotVersionStatus.Archived -> false
        }
    }

    private fun canTransitionRun(from: ArenaRunStatus, to: ArenaRunStatus): Boolean {
        if (from == to) return true
        return when (from) {
            ArenaRunStatus.Planned -> to == ArenaRunStatus.Running || to == ArenaRunStatus.Cancelled
            ArenaRunStatus.Running -> to == ArenaRunStatus.Completed || to == ArenaRunStatus.Failed || to == ArenaRunStatus.Cancelled
            ArenaRunStatus.Completed,
            ArenaRunStatus.Failed,
            ArenaRunStatus.Cancelled -> false
        }
    }

    private fun ArenaRunStatus.isTerminal(): Boolean {
        return this == ArenaRunStatus.Completed || this == ArenaRunStatus.Failed || this == ArenaRunStatus.Cancelled
    }

    private fun requirePolicyHash(value: String, field: String) {
        require(CANONICAL_POLICY_HASH.matches(value)) { "$field must be a canonical sha256 digest" }
    }

    companion object {
        private val BASIC_EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val RuntimeConfigKeyPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val CANONICAL_POLICY_HASH = Regex("^sha256:[a-f0-9]{64}$")
    }
}

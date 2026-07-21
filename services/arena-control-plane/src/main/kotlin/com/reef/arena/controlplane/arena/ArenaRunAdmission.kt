package com.reef.arena.controlplane.arena

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class ArenaAdmissionWindowPolicy(
    val policyVersion: String,
    val inviteDecisionLeadTime: Duration = Duration.ofHours(72),
    val mergeReadinessLeadTime: Duration = Duration.ofHours(48),
    val rosterLockLeadTime: Duration = Duration.ofHours(24),
    val operationalRecheckLeadTime: Duration = Duration.ofHours(2),
    val runInstantiationLeadTime: Duration = Duration.ofMinutes(30)
) {
    init {
        requireToken(policyVersion, "policyVersion")
        val leadTimes = listOf(
            inviteDecisionLeadTime,
            mergeReadinessLeadTime,
            rosterLockLeadTime,
            operationalRecheckLeadTime,
            runInstantiationLeadTime
        )
        require(leadTimes.all { !it.isNegative && !it.isZero }) { "admission lead times must be positive" }
        require(leadTimes.zipWithNext().all { (earlier, later) -> earlier > later }) {
            "admission lead times must decrease from invite decision through run instantiation"
        }
    }

    fun schedule(
        windowId: String,
        scheduledStart: Instant,
        displayTimeZone: String,
        createdAt: Instant
    ): ArenaAdmissionWindow {
        requireToken(windowId, "windowId")
        require(displayTimeZone.isNotBlank()) { "displayTimeZone is required" }
        require(createdAt.isBefore(scheduledStart)) { "createdAt must be before scheduledStart" }
        return ArenaAdmissionWindow(
            windowId = windowId,
            policyVersion = policyVersion,
            scheduledStart = scheduledStart,
            inviteDecisionCutoff = scheduledStart.minus(inviteDecisionLeadTime),
            mergeReadinessCutoff = scheduledStart.minus(mergeReadinessLeadTime),
            rosterLockAt = scheduledStart.minus(rosterLockLeadTime),
            operationalRecheckAt = scheduledStart.minus(operationalRecheckLeadTime),
            runInstantiationAt = scheduledStart.minus(runInstantiationLeadTime),
            displayTimeZone = displayTimeZone.trim(),
            createdAt = createdAt
        )
    }
}

data class ArenaAdmissionWindow(
    val windowId: String,
    val policyVersion: String,
    val scheduledStart: Instant,
    val inviteDecisionCutoff: Instant,
    val mergeReadinessCutoff: Instant,
    val rosterLockAt: Instant,
    val operationalRecheckAt: Instant,
    val runInstantiationAt: Instant,
    val displayTimeZone: String,
    val createdAt: Instant
) {
    init {
        requireToken(windowId, "windowId")
        requireToken(policyVersion, "policyVersion")
        require(displayTimeZone.isNotBlank()) { "displayTimeZone is required" }
        requireZoneId(displayTimeZone)
        val timeline = listOf(
            inviteDecisionCutoff,
            mergeReadinessCutoff,
            rosterLockAt,
            operationalRecheckAt,
            runInstantiationAt,
            scheduledStart
        )
        require(timeline.zipWithNext().all { (earlier, later) -> earlier.isBefore(later) }) {
            "admission window cutoffs must be strictly ordered"
        }
        require(!createdAt.isAfter(inviteDecisionCutoff)) { "window must be created by inviteDecisionCutoff" }
    }
}

data class ArenaEligibilityCandidate(
    val botId: String,
    val versionId: String,
    val invitedAt: Instant?,
    val approvedHeadSha: String,
    val currentHeadSha: String,
    val checksPassedAt: Instant?,
    val provisionedAt: Instant?,
    val configValidatedAt: Instant?,
    val mergedAt: Instant?,
    val registryVerifiedAt: Instant?,
    val sourceHash: String,
    val artifactHash: String,
    val configHash: String,
    val versionStatus: ArenaBotVersionStatus,
    val ownerTrusted: Boolean,
    val ownershipActive: Boolean,
    val botRestricted: Boolean,
    val ownerRestricted: Boolean,
    val secretSliceExists: Boolean,
    val gameModeAllowed: Boolean,
    val runtimeSupported: Boolean,
    val riskPreflightPassed: Boolean
) {
    init {
        requireToken(botId, "botId")
        requireToken(versionId, "versionId")
    }
}

enum class ArenaEligibilityReason(val code: String) {
    InviteNotApproved("invite_not_approved"),
    InviteAfterCutoff("invite_after_cutoff"),
    ApprovedShaChanged("approved_sha_changed"),
    ChecksNotPassed("checks_not_passed"),
    ChecksAfterMergeReadinessCutoff("checks_after_merge_readiness_cutoff"),
    NotProvisioned("not_provisioned"),
    ProvisionedAfterMergeReadinessCutoff("provisioned_after_merge_readiness_cutoff"),
    ConfigNotReady("config_not_ready"),
    ConfigAfterMergeReadinessCutoff("config_after_merge_readiness_cutoff"),
    NotMerged("not_merged"),
    MergedAfterRosterLock("merged_after_roster_lock"),
    RegistryNotVerified("registry_not_verified"),
    RegistryVerifiedAfterRosterLock("registry_verified_after_roster_lock"),
    SourceHashMissing("source_hash_missing"),
    ArtifactHashMissing("artifact_hash_missing"),
    ConfigHashMissing("config_hash_missing"),
    VersionNotEligible("version_not_eligible"),
    OwnerNotTrusted("owner_not_trusted"),
    OwnershipInactive("ownership_inactive"),
    BotRestricted("bot_restricted"),
    OwnerRestricted("owner_restricted"),
    SecretSliceMissing("secret_slice_missing"),
    GameModeNotAllowed("game_mode_not_allowed"),
    RuntimeUnsupported("runtime_unsupported"),
    RiskPreflightFailed("risk_preflight_failed"),
    CapacityUnavailable("capacity_unavailable");

    companion object {
        fun fromCode(code: String): ArenaEligibilityReason =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("unsupported Arena eligibility reason: $code")
    }
}

enum class ArenaEligibilityOutcome(val dbValue: String) {
    EligibleForRoster("eligible_for_roster"),
    RolledToNextWindow("rolled_to_next_window"),
    Excluded("excluded");

    companion object {
        fun fromDb(value: String): ArenaEligibilityOutcome =
            entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("unsupported Arena eligibility outcome: $value")
    }
}

data class ArenaRunEligibilityDecision(
    val evaluationId: String,
    val windowId: String,
    val botId: String,
    val versionId: String,
    val outcome: ArenaEligibilityOutcome,
    val reasons: List<ArenaEligibilityReason>,
    val sourceHash: String,
    val artifactHash: String,
    val configHash: String,
    val evaluatedAt: Instant,
    val correlationId: String
) {
    init {
        requireToken(evaluationId, "evaluationId")
        requireToken(windowId, "windowId")
        requireToken(botId, "botId")
        requireToken(versionId, "versionId")
        require(reasons.distinct().size == reasons.size) { "eligibility reasons must be unique" }
        require((outcome == ArenaEligibilityOutcome.EligibleForRoster) == reasons.isEmpty()) {
            "eligible decisions must have no reasons and non-eligible decisions must have reasons"
        }
        if (outcome == ArenaEligibilityOutcome.EligibleForRoster) {
            requireToken(sourceHash, "sourceHash")
            requireToken(artifactHash, "artifactHash")
            requireToken(configHash, "configHash")
        }
        require(correlationId.isNotBlank()) { "correlationId is required" }
    }
}

class ArenaEligibilityEvaluator {
    fun evaluate(
        evaluationId: String,
        window: ArenaAdmissionWindow,
        candidate: ArenaEligibilityCandidate,
        evaluatedAt: Instant,
        correlationId: String
    ): ArenaRunEligibilityDecision {
        requireToken(evaluationId, "evaluationId")
        require(correlationId.isNotBlank()) { "correlationId is required" }
        listOf(
            candidate.invitedAt,
            candidate.checksPassedAt,
            candidate.provisionedAt,
            candidate.configValidatedAt,
            candidate.mergedAt,
            candidate.registryVerifiedAt
        ).filterNotNull().forEach { factAt ->
            require(!factAt.isAfter(evaluatedAt)) { "eligibility facts cannot be dated after evaluatedAt" }
        }
        val reasons = buildList {
            cutoff(candidate.invitedAt, window.inviteDecisionCutoff, ArenaEligibilityReason.InviteNotApproved, ArenaEligibilityReason.InviteAfterCutoff)
            if (candidate.approvedHeadSha.isBlank() || candidate.approvedHeadSha != candidate.currentHeadSha) add(ArenaEligibilityReason.ApprovedShaChanged)
            cutoff(candidate.checksPassedAt, window.mergeReadinessCutoff, ArenaEligibilityReason.ChecksNotPassed, ArenaEligibilityReason.ChecksAfterMergeReadinessCutoff)
            cutoff(candidate.provisionedAt, window.mergeReadinessCutoff, ArenaEligibilityReason.NotProvisioned, ArenaEligibilityReason.ProvisionedAfterMergeReadinessCutoff)
            cutoff(candidate.configValidatedAt, window.mergeReadinessCutoff, ArenaEligibilityReason.ConfigNotReady, ArenaEligibilityReason.ConfigAfterMergeReadinessCutoff)
            cutoff(candidate.mergedAt, window.rosterLockAt, ArenaEligibilityReason.NotMerged, ArenaEligibilityReason.MergedAfterRosterLock)
            cutoff(candidate.registryVerifiedAt, window.rosterLockAt, ArenaEligibilityReason.RegistryNotVerified, ArenaEligibilityReason.RegistryVerifiedAfterRosterLock)
            if (candidate.sourceHash.isBlank()) add(ArenaEligibilityReason.SourceHashMissing)
            if (candidate.artifactHash.isBlank()) add(ArenaEligibilityReason.ArtifactHashMissing)
            if (candidate.configHash.isBlank()) add(ArenaEligibilityReason.ConfigHashMissing)
            if (candidate.versionStatus !in setOf(ArenaBotVersionStatus.Approved, ArenaBotVersionStatus.Active)) add(ArenaEligibilityReason.VersionNotEligible)
            if (!candidate.ownerTrusted) add(ArenaEligibilityReason.OwnerNotTrusted)
            if (!candidate.ownershipActive) add(ArenaEligibilityReason.OwnershipInactive)
            if (candidate.botRestricted) add(ArenaEligibilityReason.BotRestricted)
            if (candidate.ownerRestricted) add(ArenaEligibilityReason.OwnerRestricted)
            if (!candidate.secretSliceExists) add(ArenaEligibilityReason.SecretSliceMissing)
            if (!candidate.gameModeAllowed) add(ArenaEligibilityReason.GameModeNotAllowed)
            if (!candidate.runtimeSupported) add(ArenaEligibilityReason.RuntimeUnsupported)
            if (!candidate.riskPreflightPassed) add(ArenaEligibilityReason.RiskPreflightFailed)
        }.distinct().sortedBy { it.ordinal }
        val outcome = when {
            reasons.isEmpty() -> ArenaEligibilityOutcome.EligibleForRoster
            reasons.any { it in hardExclusionReasons } -> ArenaEligibilityOutcome.Excluded
            else -> ArenaEligibilityOutcome.RolledToNextWindow
        }
        return ArenaRunEligibilityDecision(
            evaluationId = evaluationId,
            windowId = window.windowId,
            botId = candidate.botId,
            versionId = candidate.versionId,
            outcome = outcome,
            reasons = reasons,
            sourceHash = candidate.sourceHash,
            artifactHash = candidate.artifactHash,
            configHash = candidate.configHash,
            evaluatedAt = evaluatedAt,
            correlationId = correlationId.trim()
        )
    }

    private fun MutableList<ArenaEligibilityReason>.cutoff(
        actual: Instant?,
        cutoff: Instant,
        missing: ArenaEligibilityReason,
        late: ArenaEligibilityReason
    ) {
        when {
            actual == null -> add(missing)
            actual.isAfter(cutoff) -> add(late)
        }
    }

    private companion object {
        val hardExclusionReasons = setOf(ArenaEligibilityReason.BotRestricted, ArenaEligibilityReason.OwnerRestricted)
    }
}

data class ArenaRosterPolicySnapshot(
    val modeId: String,
    val scenarioId: String,
    val seedSetHash: String,
    val actorProfileVersion: String,
    val actorProfileHash: String,
    val riskPolicyVersion: String,
    val riskPolicyHash: String,
    val scoringPolicyVersion: String,
    val scoringPolicyHash: String,
    val economicPolicyVersion: String,
    val economicPolicyHash: String
) {
    init {
        fields().forEach { (name, value) -> requireToken(value, name) }
    }

    internal fun canonicalFields(): List<String> = fields().map { it.second }

    private fun fields() = listOf(
        "modeId" to modeId,
        "scenarioId" to scenarioId,
        "seedSetHash" to seedSetHash,
        "actorProfileVersion" to actorProfileVersion,
        "actorProfileHash" to actorProfileHash,
        "riskPolicyVersion" to riskPolicyVersion,
        "riskPolicyHash" to riskPolicyHash,
        "scoringPolicyVersion" to scoringPolicyVersion,
        "scoringPolicyHash" to scoringPolicyHash,
        "economicPolicyVersion" to economicPolicyVersion,
        "economicPolicyHash" to economicPolicyHash
    )
}

data class ArenaRosterCandidate(
    val decision: ArenaRunEligibilityDecision,
    val priority: Int
) {
    init {
        require(priority >= 0) { "roster priority must be nonnegative" }
    }
}

data class ArenaRosterEntry(
    val botOrder: Int,
    val botId: String,
    val versionId: String,
    val priority: Int,
    val sourceHash: String,
    val artifactHash: String,
    val configHash: String,
    val eligibilityEvaluationId: String
)

data class ArenaRosterSnapshot(
    val snapshotId: String,
    val windowId: String,
    val policy: ArenaRosterPolicySnapshot,
    val maxBots: Int,
    val entries: List<ArenaRosterEntry>,
    val snapshotHash: String,
    val lockedAt: Instant,
    val lockedBy: String,
    val correlationId: String
) {
    init {
        requireToken(snapshotId, "snapshotId")
        requireToken(windowId, "windowId")
        require(maxBots > 0) { "maxBots must be positive" }
        require(entries.size <= maxBots) { "roster entries exceed maxBots" }
        require(entries.map { it.botOrder } == entries.indices.toList()) { "roster bot order must be contiguous" }
        require(entries.map { it.botId to it.versionId }.distinct().size == entries.size) { "roster bot versions must be unique" }
        requireToken(snapshotHash, "snapshotHash")
        require(lockedBy.isNotBlank()) { "lockedBy is required" }
        require(correlationId.isNotBlank()) { "correlationId is required" }
    }
}

data class ArenaRosterLockResult(
    val snapshot: ArenaRosterSnapshot,
    val overflow: List<ArenaRosterCandidate>
)

data class ArenaRosterPlan(
    val included: List<ArenaRosterCandidate>,
    val overflow: List<ArenaRosterCandidate>
)

class ArenaRosterPlanner {
    fun plan(candidates: List<ArenaRosterCandidate>, maxBots: Int): ArenaRosterPlan {
        require(maxBots > 0) { "maxBots must be positive" }
        require(candidates.map { it.decision.botId to it.decision.versionId }.distinct().size == candidates.size) {
            "roster candidates must contain unique bot versions"
        }
        candidates.forEach {
            require(it.decision.outcome == ArenaEligibilityOutcome.EligibleForRoster) { "roster candidate is not eligible" }
        }
        val ordered = candidates.sortedWith(
            compareByDescending<ArenaRosterCandidate> { it.priority }
                .thenBy { it.decision.botId }
                .thenBy { it.decision.versionId }
        )
        return ArenaRosterPlan(ordered.take(maxBots), ordered.drop(maxBots))
    }
}

class ArenaRosterLocker(
    private val planner: ArenaRosterPlanner = ArenaRosterPlanner()
) {
    fun lock(
        snapshotId: String,
        window: ArenaAdmissionWindow,
        policy: ArenaRosterPolicySnapshot,
        candidates: List<ArenaRosterCandidate>,
        maxBots: Int,
        lockedAt: Instant,
        lockedBy: String,
        correlationId: String
    ): ArenaRosterLockResult {
        requireToken(snapshotId, "snapshotId")
        require(maxBots > 0) { "maxBots must be positive" }
        require(!lockedAt.isBefore(window.rosterLockAt)) { "roster cannot lock before rosterLockAt" }
        require(lockedAt.isBefore(window.scheduledStart)) { "roster must lock before scheduledStart" }
        require(lockedBy.isNotBlank()) { "lockedBy is required" }
        require(correlationId.isNotBlank()) { "correlationId is required" }
        candidates.forEach {
            require(it.decision.windowId == window.windowId) { "eligibility decision window does not match roster window" }
            require(!it.decision.evaluatedAt.isAfter(lockedAt)) { "eligibility decision cannot be dated after roster lock" }
        }
        val plan = planner.plan(candidates, maxBots)
        val included = plan.included
        val entries = included.mapIndexed { index, candidate ->
            ArenaRosterEntry(
                botOrder = index,
                botId = candidate.decision.botId,
                versionId = candidate.decision.versionId,
                priority = candidate.priority,
                sourceHash = candidate.decision.sourceHash,
                artifactHash = candidate.decision.artifactHash,
                configHash = candidate.decision.configHash,
                eligibilityEvaluationId = candidate.decision.evaluationId
            )
        }
        val hash = rosterHash(window.windowId, policy, maxBots, entries)
        return ArenaRosterLockResult(
            snapshot = ArenaRosterSnapshot(
                snapshotId = snapshotId,
                windowId = window.windowId,
                policy = policy,
                maxBots = maxBots,
                entries = entries,
                snapshotHash = hash,
                lockedAt = lockedAt,
                lockedBy = lockedBy.trim(),
                correlationId = correlationId.trim()
            ),
            overflow = plan.overflow
        )
    }

    private fun rosterHash(
        windowId: String,
        policy: ArenaRosterPolicySnapshot,
        maxBots: Int,
        entries: List<ArenaRosterEntry>
    ): String {
        val fields = buildList {
            add("reef.arena.roster.v1")
            add(windowId)
            addAll(policy.canonicalFields())
            add(maxBots.toString())
            entries.forEach { entry ->
                add(entry.botOrder.toString())
                add(entry.botId)
                add(entry.versionId)
                add(entry.priority.toString())
                add(entry.sourceHash)
                add(entry.artifactHash)
                add(entry.configHash)
                add(entry.eligibilityEvaluationId)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(fields.joinToString("\u001f").toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }
}

enum class ArenaRosterRemovalReason(val code: String) {
    Security("security"),
    Trust("trust"),
    Config("config"),
    Availability("availability");

    companion object {
        fun fromCode(code: String): ArenaRosterRemovalReason =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("unsupported Arena roster removal reason: $code")
    }
}

data class ArenaRosterRemoval(
    val removalId: String,
    val windowId: String,
    val snapshotId: String,
    val botId: String,
    val versionId: String,
    val reason: ArenaRosterRemovalReason,
    val detail: String,
    val removedAt: Instant,
    val removedBy: String,
    val correlationId: String
) {
    init {
        requireToken(removalId, "removalId")
        requireToken(windowId, "windowId")
        requireToken(snapshotId, "snapshotId")
        requireToken(botId, "botId")
        requireToken(versionId, "versionId")
        require(detail.isNotBlank()) { "removal detail is required" }
        require(removedBy.isNotBlank()) { "removedBy is required" }
        require(correlationId.isNotBlank()) { "correlationId is required" }
    }
}

interface ArenaRunAdmissionStore {
    fun createWindow(window: ArenaAdmissionWindow): ArenaAdmissionWindow
    fun window(windowId: String): ArenaAdmissionWindow?
    fun recordDecision(decision: ArenaRunEligibilityDecision): ArenaRunEligibilityDecision
    fun decisions(windowId: String): List<ArenaRunEligibilityDecision>
    fun lockRoster(snapshot: ArenaRosterSnapshot): ArenaRosterSnapshot
    fun roster(windowId: String): ArenaRosterSnapshot?
    fun recordRemoval(removal: ArenaRosterRemoval): ArenaRosterRemoval
    fun removals(windowId: String): List<ArenaRosterRemoval>
}

class InMemoryArenaRunAdmissionStore : ArenaRunAdmissionStore {
    private val windows = linkedMapOf<String, ArenaAdmissionWindow>()
    private val decisions = linkedMapOf<String, ArenaRunEligibilityDecision>()
    private val rosters = linkedMapOf<String, ArenaRosterSnapshot>()
    private val removals = linkedMapOf<String, ArenaRosterRemoval>()

    override fun createWindow(window: ArenaAdmissionWindow): ArenaAdmissionWindow {
        val existing = windows[window.windowId]
        require(existing == null || existing == window) { "admission window is immutable: ${window.windowId}" }
        windows[window.windowId] = window
        return window
    }

    override fun window(windowId: String): ArenaAdmissionWindow? = windows[windowId]

    override fun recordDecision(decision: ArenaRunEligibilityDecision): ArenaRunEligibilityDecision {
        require(windows.containsKey(decision.windowId)) { "unknown admission window: ${decision.windowId}" }
        val existing = decisions[decision.evaluationId]
        require(existing == null || existing == decision) { "eligibility evaluation is immutable: ${decision.evaluationId}" }
        decisions[decision.evaluationId] = decision
        return decision
    }

    override fun decisions(windowId: String): List<ArenaRunEligibilityDecision> =
        decisions.values.filter { it.windowId == windowId }.sortedWith(
            compareBy<ArenaRunEligibilityDecision> { it.evaluatedAt }
                .thenBy { it.botId }
                .thenBy { it.versionId }
                .thenBy { it.evaluationId }
        )

    override fun lockRoster(snapshot: ArenaRosterSnapshot): ArenaRosterSnapshot {
        require(windows.containsKey(snapshot.windowId)) { "unknown admission window: ${snapshot.windowId}" }
        val existing = rosters[snapshot.windowId]
        require(existing == null || existing == snapshot) { "roster snapshot is immutable for window: ${snapshot.windowId}" }
        rosters[snapshot.windowId] = snapshot
        return snapshot
    }

    override fun roster(windowId: String): ArenaRosterSnapshot? = rosters[windowId]

    override fun recordRemoval(removal: ArenaRosterRemoval): ArenaRosterRemoval {
        val roster = requireNotNull(rosters[removal.windowId]) { "unknown roster for window: ${removal.windowId}" }
        require(roster.snapshotId == removal.snapshotId) { "roster snapshot does not match removal" }
        require(roster.entries.any { it.botId == removal.botId && it.versionId == removal.versionId }) {
            "roster removal target is not in the locked roster"
        }
        val sameTarget = removals.values.firstOrNull {
            it.snapshotId == removal.snapshotId && it.botId == removal.botId && it.versionId == removal.versionId
        }
        require(sameTarget == null || sameTarget == removal) { "roster entry is already removed" }
        val existing = removals[removal.removalId]
        require(existing == null || existing == removal) { "roster removal is immutable: ${removal.removalId}" }
        removals[removal.removalId] = removal
        return removal
    }

    override fun removals(windowId: String): List<ArenaRosterRemoval> = removals.values
        .filter { it.windowId == windowId }
        .sortedWith(compareBy<ArenaRosterRemoval> { it.removedAt }.thenBy { it.botId }.thenBy { it.versionId })
}

private val ArenaTokenPattern = Regex("[A-Za-z0-9][A-Za-z0-9:._/-]{0,255}")

private fun requireToken(value: String, name: String) {
    require(ArenaTokenPattern.matches(value.trim())) { "$name must be a stable non-blank token" }
}

private fun requireZoneId(value: String) {
    try {
        ZoneId.of(value.trim())
    } catch (ex: Exception) {
        throw IllegalArgumentException("displayTimeZone must be a valid IANA timezone", ex)
    }
}

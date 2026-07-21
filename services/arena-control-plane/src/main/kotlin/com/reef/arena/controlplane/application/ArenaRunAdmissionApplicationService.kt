package com.reef.arena.controlplane.application

import com.reef.arena.controlplane.arena.ArenaAdmissionWindow
import com.reef.arena.controlplane.arena.ArenaAdmissionWindowPolicy
import com.reef.arena.controlplane.arena.ArenaEligibilityCandidate
import com.reef.arena.controlplane.arena.ArenaEligibilityEvaluator
import com.reef.arena.controlplane.arena.ArenaEligibilityOutcome
import com.reef.arena.controlplane.arena.ArenaEligibilityReason
import com.reef.arena.controlplane.arena.ArenaRosterCandidate
import com.reef.arena.controlplane.arena.ArenaRosterLockResult
import com.reef.arena.controlplane.arena.ArenaRosterLocker
import com.reef.arena.controlplane.arena.ArenaRosterPlanner
import com.reef.arena.controlplane.arena.ArenaRosterPolicySnapshot
import com.reef.arena.controlplane.arena.ArenaRosterRemoval
import com.reef.arena.controlplane.arena.ArenaRosterRemovalReason
import com.reef.arena.controlplane.arena.ArenaRosterSnapshot
import com.reef.arena.controlplane.arena.ArenaRunAdmissionStore
import com.reef.arena.controlplane.arena.ArenaRunEligibilityDecision
import com.reef.platform.application.admin.AdminActor
import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.AdminTrustState
import com.reef.platform.application.admin.AuthorizationException
import java.time.Instant

data class ScheduleArenaAdmissionWindowCommand(
    val windowId: String,
    val policy: ArenaAdmissionWindowPolicy,
    val scheduledStart: Instant,
    val displayTimeZone: String
)

data class EvaluateArenaEligibilityCommand(
    val evaluationId: String,
    val windowId: String,
    val candidate: ArenaEligibilityCandidate
)

data class ArenaRosterCandidateCommand(
    val evaluationId: String,
    val priority: Int
)

data class LockArenaRosterCommand(
    val snapshotId: String,
    val windowId: String,
    val policy: ArenaRosterPolicySnapshot,
    val candidates: List<ArenaRosterCandidateCommand>,
    val maxBots: Int
)

data class PreviewArenaRosterCommand(
    val windowId: String,
    val candidates: List<ArenaRosterCandidateCommand>,
    val maxBots: Int
)

data class ArenaRosterPreview(
    val windowId: String,
    val maxBots: Int,
    val included: List<ArenaRosterCandidate>,
    val capacityOverflow: List<ArenaRosterCandidate>,
    val awaitingPriority: List<ArenaRunEligibilityDecision>,
    val rolled: List<ArenaRunEligibilityDecision>,
    val excluded: List<ArenaRunEligibilityDecision>
)

data class RemoveArenaRosterEntryCommand(
    val removalId: String,
    val windowId: String,
    val botId: String,
    val versionId: String,
    val reason: ArenaRosterRemovalReason,
    val detail: String
)

class ArenaRunAdmissionApplicationService(
    private val store: ArenaRunAdmissionStore,
    private val adminIdentityService: AdminIdentityService? = null,
    private val now: () -> Instant = { Instant.now() },
    private val evaluator: ArenaEligibilityEvaluator = ArenaEligibilityEvaluator(),
    private val rosterLocker: ArenaRosterLocker = ArenaRosterLocker(),
    private val rosterPlanner: ArenaRosterPlanner = ArenaRosterPlanner()
) {
    fun scheduleWindow(actor: AdminActor, command: ScheduleArenaAdmissionWindowCommand): ArenaAdmissionWindow =
        authorized(actor) {
            val createdAt = store.window(command.windowId)?.createdAt ?: now()
            store.createWindow(
                command.policy.schedule(
                    command.windowId,
                    command.scheduledStart,
                    command.displayTimeZone,
                    createdAt
                )
            )
        }

    fun window(actor: AdminActor, windowId: String): ArenaAdmissionWindow? = authorized(actor) { store.window(windowId) }

    fun evaluate(actor: AdminActor, command: EvaluateArenaEligibilityCommand): ArenaRunEligibilityDecision =
        authorized(actor) {
            val window = requireNotNull(store.window(command.windowId)) { "unknown admission window: ${command.windowId}" }
            val evaluatedAt = store.decisions(command.windowId)
                .firstOrNull { it.evaluationId == command.evaluationId }
                ?.evaluatedAt
                ?: now()
            store.recordDecision(
                evaluator.evaluate(
                    command.evaluationId,
                    window,
                    command.candidate,
                    evaluatedAt,
                    actor.correlationId
                )
            )
        }

    fun decisions(actor: AdminActor, windowId: String): List<ArenaRunEligibilityDecision> =
        authorized(actor) { store.decisions(windowId) }

    fun previewRoster(actor: AdminActor, command: PreviewArenaRosterCommand): ArenaRosterPreview =
        authorized(actor) {
            val window = requireNotNull(store.window(command.windowId)) { "unknown admission window: ${command.windowId}" }
            require(store.roster(command.windowId) == null) { "roster preview is closed because the window is already locked" }
            require(!now().isAfter(window.rosterLockAt)) { "roster preview is closed after rosterLockAt" }
            val decisions = store.decisions(command.windowId)
            val candidates = resolveCandidates(command.windowId, decisions, command.candidates)
            val plan = rosterPlanner.plan(candidates, command.maxBots)
            val rankedIds = candidates.map { it.decision.evaluationId }.toSet()
            ArenaRosterPreview(
                windowId = command.windowId,
                maxBots = command.maxBots,
                included = plan.included,
                capacityOverflow = plan.overflow,
                awaitingPriority = decisions.filter {
                    it.outcome == ArenaEligibilityOutcome.EligibleForRoster && it.evaluationId !in rankedIds
                },
                rolled = decisions.filter { it.outcome == ArenaEligibilityOutcome.RolledToNextWindow },
                excluded = decisions.filter { it.outcome == ArenaEligibilityOutcome.Excluded }
            )
        }

    fun lockRoster(actor: AdminActor, command: LockArenaRosterCommand): ArenaRosterLockResult =
        authorized(actor) {
            val window = requireNotNull(store.window(command.windowId)) { "unknown admission window: ${command.windowId}" }
            val decisions = store.decisions(command.windowId)
            val candidates = resolveCandidates(command.windowId, decisions, command.candidates)
            val eligibleIds = decisions.filter { it.outcome == ArenaEligibilityOutcome.EligibleForRoster }
                .map { it.evaluationId }.toSet()
            require(candidates.map { it.decision.evaluationId }.toSet() == eligibleIds) {
                "roster lock must rank every eligible decision; preview awaitingPriority before lock"
            }
            val existing = store.roster(command.windowId)
            val result = rosterLocker.lock(
                command.snapshotId,
                window,
                command.policy,
                candidates,
                command.maxBots,
                existing?.lockedAt ?: now(),
                actor.actorId,
                actor.correlationId
            )
            val savedSnapshot = store.lockRoster(result.snapshot)
            result.overflow.forEach { overflow ->
                store.recordDecision(
                    overflow.decision.copy(
                        evaluationId = "${command.snapshotId}:${overflow.decision.botId}:${overflow.decision.versionId}:capacity",
                        outcome = ArenaEligibilityOutcome.RolledToNextWindow,
                        reasons = listOf(ArenaEligibilityReason.CapacityUnavailable),
                        evaluatedAt = savedSnapshot.lockedAt,
                        correlationId = actor.correlationId
                    )
                )
            }
            result.copy(snapshot = savedSnapshot)
        }

    fun roster(actor: AdminActor, windowId: String): ArenaRosterSnapshot? = authorized(actor) { store.roster(windowId) }

    fun removeFromRoster(actor: AdminActor, command: RemoveArenaRosterEntryCommand): ArenaRosterRemoval =
        authorized(actor) {
            val window = requireNotNull(store.window(command.windowId)) { "unknown admission window: ${command.windowId}" }
            val roster = requireNotNull(store.roster(command.windowId)) { "unknown roster for window: ${command.windowId}" }
            require(roster.entries.any { it.botId == command.botId && it.versionId == command.versionId }) {
                "roster removal target is not in the locked roster"
            }
            val existing = store.removals(command.windowId).firstOrNull { it.removalId == command.removalId }
            val removedAt = existing?.removedAt ?: now()
            require(!removedAt.isBefore(roster.lockedAt)) { "roster entry cannot be removed before roster lock" }
            require(removedAt.isBefore(window.scheduledStart)) { "roster entry cannot be removed at or after game start" }
            store.recordRemoval(
                ArenaRosterRemoval(
                    removalId = command.removalId,
                    windowId = command.windowId,
                    snapshotId = roster.snapshotId,
                    botId = command.botId,
                    versionId = command.versionId,
                    reason = command.reason,
                    detail = command.detail.trim(),
                    removedAt = removedAt,
                    removedBy = actor.actorId,
                    correlationId = actor.correlationId
                )
            )
        }

    fun removals(actor: AdminActor, windowId: String): List<ArenaRosterRemoval> =
        authorized(actor) { store.removals(windowId) }

    private fun resolveCandidates(
        windowId: String,
        decisions: List<ArenaRunEligibilityDecision>,
        commands: List<ArenaRosterCandidateCommand>
    ): List<ArenaRosterCandidate> {
        require(commands.map { it.evaluationId }.distinct().size == commands.size) {
            "roster candidate evaluations must be unique"
        }
        val byId = decisions.associateBy { it.evaluationId }
        return commands.map { candidate ->
            val decision = requireNotNull(byId[candidate.evaluationId]) {
                "unknown eligibility evaluation: ${candidate.evaluationId}"
            }
            require(decision.windowId == windowId) { "eligibility decision window does not match roster window" }
            ArenaRosterCandidate(decision, candidate.priority)
        }
    }

    private inline fun <T> authorized(actor: AdminActor, block: () -> T): T {
        requirePermission(actor)
        return block()
    }

    private fun requirePermission(actor: AdminActor) {
        if (!actor.actorId.startsWith("user-gh-")) return
        val identity = adminIdentityService ?: throw AuthorizationException("Arena identity service is not configured")
        val user = identity.user(actor.actorId) ?: throw AuthorizationException("unknown Arena actor ${actor.actorId}")
        val roles = identity.rolesForUser(actor.actorId).map { it.roleId }.toSet()
        if (user.trustState != AdminTrustState.Trusted ||
            (AdminIdentityService.RoleOperator !in roles && AdminIdentityService.RolePlatformAdmin !in roles)
        ) {
            throw AuthorizationException("actor ${actor.actorId} missing permission arena.admin")
        }
    }
}

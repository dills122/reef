package com.reef.arena.controlplane.arena

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArenaRunAdmissionTest {
    private val policy = ArenaAdmissionWindowPolicy("invite-preview-v1")
    private val window = policy.schedule(
        windowId = "preview-2026-08-01",
        scheduledStart = Instant.parse("2026-08-01T18:00:00Z"),
        displayTimeZone = "America/Toronto",
        createdAt = Instant.parse("2026-07-01T00:00:00Z")
    )
    private val evaluator = ArenaEligibilityEvaluator()

    @Test
    fun computesCanonicalCutoffsAndAcceptsFactsAtExactBoundaries() {
        assertEquals(Instant.parse("2026-07-29T18:00:00Z"), window.inviteDecisionCutoff)
        assertEquals(Instant.parse("2026-07-30T18:00:00Z"), window.mergeReadinessCutoff)
        assertEquals(Instant.parse("2026-07-31T18:00:00Z"), window.rosterLockAt)
        assertEquals(Instant.parse("2026-08-01T16:00:00Z"), window.operationalRecheckAt)
        assertEquals(Instant.parse("2026-08-01T17:30:00Z"), window.runInstantiationAt)

        val decision = evaluator.evaluate(
            "evaluation-at-cutoffs",
            window,
            eligibleCandidate(
                invitedAt = window.inviteDecisionCutoff,
                checksPassedAt = window.mergeReadinessCutoff,
                provisionedAt = window.mergeReadinessCutoff,
                configValidatedAt = window.mergeReadinessCutoff,
                mergedAt = window.rosterLockAt,
                registryVerifiedAt = window.rosterLockAt
            ),
            window.rosterLockAt,
            "corr-at-cutoffs"
        )

        assertEquals(ArenaEligibilityOutcome.EligibleForRoster, decision.outcome)
        assertEquals(emptyList(), decision.reasons)
    }

    @Test
    fun reportsStableReasonsOneMillisecondAfterEachCutoff() {
        val decision = evaluator.evaluate(
            "evaluation-late",
            window,
            eligibleCandidate(
                invitedAt = window.inviteDecisionCutoff.plusMillis(1),
                checksPassedAt = window.mergeReadinessCutoff.plusMillis(1),
                provisionedAt = window.mergeReadinessCutoff.plusMillis(1),
                configValidatedAt = window.mergeReadinessCutoff.plusMillis(1),
                mergedAt = window.rosterLockAt.plusMillis(1),
                registryVerifiedAt = window.rosterLockAt.plusMillis(1)
            ),
            window.rosterLockAt.plusMillis(1),
            "corr-late"
        )

        assertEquals(ArenaEligibilityOutcome.RolledToNextWindow, decision.outcome)
        assertEquals(
            listOf(
                ArenaEligibilityReason.InviteAfterCutoff,
                ArenaEligibilityReason.ChecksAfterMergeReadinessCutoff,
                ArenaEligibilityReason.ProvisionedAfterMergeReadinessCutoff,
                ArenaEligibilityReason.ConfigAfterMergeReadinessCutoff,
                ArenaEligibilityReason.MergedAfterRosterLock,
                ArenaEligibilityReason.RegistryVerifiedAfterRosterLock
            ),
            decision.reasons
        )
    }

    @Test
    fun rejectsInvalidDisplayZoneAndFutureDatedEligibilityFacts() {
        assertFailsWith<IllegalArgumentException> {
            policy.schedule(
                "invalid-zone", window.scheduledStart, "Toronto-ish", Instant.parse("2026-07-01T00:00:00Z")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate(
                "evaluation-future-fact",
                window,
                eligibleCandidate(mergedAt = window.rosterLockAt),
                window.rosterLockAt.minusMillis(1),
                "corr-future-fact"
            )
        }
    }

    @Test
    fun distinguishesRollForwardFromHardExclusion() {
        val missingReadiness = evaluator.evaluate(
            "evaluation-roll",
            window,
            eligibleCandidate(configValidatedAt = null),
            window.rosterLockAt,
            "corr-roll"
        )
        val restricted = evaluator.evaluate(
            "evaluation-exclude",
            window,
            eligibleCandidate(ownerRestricted = true),
            window.rosterLockAt,
            "corr-exclude"
        )

        assertEquals(ArenaEligibilityOutcome.RolledToNextWindow, missingReadiness.outcome)
        assertEquals(listOf(ArenaEligibilityReason.ConfigNotReady), missingReadiness.reasons)
        assertEquals(ArenaEligibilityOutcome.Excluded, restricted.outcome)
        assertEquals(listOf(ArenaEligibilityReason.OwnerRestricted), restricted.reasons)
    }

    @Test
    fun rosterLockUsesDeterministicPriorityAndContentHash() {
        val candidates = listOf(
            rosterCandidate("bot-z", priority = 5),
            rosterCandidate("bot-b", priority = 10),
            rosterCandidate("bot-a", priority = 10)
        )
        val rosterPolicy = rosterPolicy()
        val locker = ArenaRosterLocker()

        val first = locker.lock(
            snapshotId = "snapshot-1",
            window = window,
            policy = rosterPolicy,
            candidates = candidates,
            maxBots = 2,
            lockedAt = window.rosterLockAt,
            lockedBy = "operator-1",
            correlationId = "corr-roster"
        )
        val replay = locker.lock(
            snapshotId = "snapshot-1",
            window = window,
            policy = rosterPolicy,
            candidates = candidates.reversed(),
            maxBots = 2,
            lockedAt = window.rosterLockAt,
            lockedBy = "operator-1",
            correlationId = "corr-roster"
        )

        assertEquals(listOf("bot-a", "bot-b"), first.snapshot.entries.map { it.botId })
        assertEquals(listOf("bot-z"), first.overflow.map { it.decision.botId })
        assertEquals(first.snapshot, replay.snapshot)
        assertTrue(first.snapshot.snapshotHash.matches(Regex("sha256:[0-9a-f]{64}")))
        val largerCapacity = locker.lock(
            "snapshot-1",
            window,
            rosterPolicy,
            candidates.take(1),
            maxBots = 2,
            lockedAt = window.rosterLockAt,
            lockedBy = "operator-1",
            correlationId = "corr-roster"
        )
        val exactCapacity = locker.lock(
            "snapshot-1",
            window,
            rosterPolicy,
            candidates.take(1),
            maxBots = 1,
            lockedAt = window.rosterLockAt,
            lockedBy = "operator-1",
            correlationId = "corr-roster"
        )
        assertTrue(largerCapacity.snapshot.snapshotHash != exactCapacity.snapshot.snapshotHash)
    }

    @Test
    fun storeAllowsIdempotentReplayButRejectsWindowDecisionAndRosterMutation() {
        val store = InMemoryArenaRunAdmissionStore()
        store.createWindow(window)
        assertEquals(window, store.createWindow(window))
        assertFailsWith<IllegalArgumentException> {
            store.createWindow(window.copy(displayTimeZone = "UTC"))
        }

        val candidate = rosterCandidate("bot-a", priority = 10)
        store.recordDecision(candidate.decision)
        assertEquals(candidate.decision, store.recordDecision(candidate.decision))
        assertFailsWith<IllegalArgumentException> {
            store.recordDecision(candidate.decision.copy(correlationId = "changed"))
        }

        val snapshot = ArenaRosterLocker().lock(
            "snapshot-immutable",
            window,
            rosterPolicy(),
            listOf(candidate),
            maxBots = 1,
            lockedAt = window.rosterLockAt,
            lockedBy = "operator-1",
            correlationId = "corr-immutable"
        ).snapshot
        store.lockRoster(snapshot)
        assertEquals(snapshot, store.lockRoster(snapshot))
        assertFailsWith<IllegalArgumentException> {
            store.lockRoster(snapshot.copy(lockedBy = "operator-2"))
        }
    }

    private fun rosterCandidate(botId: String, priority: Int): ArenaRosterCandidate {
        val candidate = eligibleCandidate(botId = botId)
        return ArenaRosterCandidate(
            decision = evaluator.evaluate(
                evaluationId = "evaluation-$botId",
                window = window,
                candidate = candidate,
                evaluatedAt = window.rosterLockAt,
                correlationId = "corr-$botId"
            ),
            priority = priority
        )
    }

    private fun rosterPolicy() = ArenaRosterPolicySnapshot(
        modeId = "preview-equities",
        scenarioId = "preview-scenario-v1",
        seedSetHash = "sha256:seed-set",
        actorProfileVersion = "actors-v1",
        actorProfileHash = "sha256:actors",
        riskPolicyVersion = "risk-v1",
        riskPolicyHash = "sha256:risk",
        scoringPolicyVersion = "score-v1",
        scoringPolicyHash = "sha256:score",
        economicPolicyVersion = "preview-zero-fee-v1",
        economicPolicyHash = "sha256:economics"
    )

    private fun eligibleCandidate(
        botId: String = "bot-1",
        invitedAt: Instant? = window.inviteDecisionCutoff.minusMillis(1),
        checksPassedAt: Instant? = window.mergeReadinessCutoff.minusMillis(1),
        provisionedAt: Instant? = window.mergeReadinessCutoff.minusMillis(1),
        configValidatedAt: Instant? = window.mergeReadinessCutoff.minusMillis(1),
        mergedAt: Instant? = window.rosterLockAt.minusMillis(1),
        registryVerifiedAt: Instant? = window.rosterLockAt.minusMillis(1),
        ownerRestricted: Boolean = false
    ) = ArenaEligibilityCandidate(
        botId = botId,
        versionId = "v1",
        invitedAt = invitedAt,
        approvedHeadSha = "a".repeat(40),
        currentHeadSha = "a".repeat(40),
        checksPassedAt = checksPassedAt,
        provisionedAt = provisionedAt,
        configValidatedAt = configValidatedAt,
        mergedAt = mergedAt,
        registryVerifiedAt = registryVerifiedAt,
        sourceHash = "sha256:source-$botId",
        artifactHash = "sha256:artifact-$botId",
        configHash = "sha256:config-$botId",
        versionStatus = ArenaBotVersionStatus.Approved,
        ownerTrusted = true,
        ownershipActive = true,
        botRestricted = false,
        ownerRestricted = ownerRestricted,
        secretSliceExists = true,
        gameModeAllowed = true,
        runtimeSupported = true,
        riskPreflightPassed = true
    )
}

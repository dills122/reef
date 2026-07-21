package com.reef.arena.controlplane.application

import com.reef.arena.controlplane.arena.ArenaAdmissionWindowPolicy
import com.reef.arena.controlplane.arena.ArenaBotVersionStatus
import com.reef.arena.controlplane.arena.ArenaEligibilityCandidate
import com.reef.arena.controlplane.arena.ArenaEligibilityOutcome
import com.reef.arena.controlplane.arena.ArenaEligibilityReason
import com.reef.arena.controlplane.arena.ArenaRosterPolicySnapshot
import com.reef.arena.controlplane.arena.ArenaResolvedPolicyKind
import com.reef.arena.controlplane.arena.ArenaRosterPolicyVerifier
import com.reef.arena.controlplane.arena.ArenaRosterResolvedPolicies
import com.reef.arena.controlplane.arena.ArenaRosterRemovalReason
import com.reef.arena.controlplane.arena.InMemoryArenaRunAdmissionStore
import com.reef.platform.application.admin.AdminActor
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArenaRunAdmissionApplicationServiceTest {
    private val actor = AdminActor("arena-operator", "corr-admission", "2026-08-01T00:00:00Z")
    private val verifier = ArenaRosterPolicyVerifier()
    private val resolvedPolicies = ArenaRosterResolvedPolicies(
        verifier.canonicalArtifact(
            ArenaResolvedPolicyKind.ActorProfileCatalog,
            "arena-actor-profiles",
            "actors-v1",
            """{"schemaVersion":"reef.arena.actorProfiles.v1","catalogId":"arena-actor-profiles","version":"actors-v1","profiles":[{"profileId":"competitor-standard","version":"v1","actorClass":"competitor","description":"Default competitor","difficultyBucket":"ranked-standard","scoreEffect":"eligible-for-score","allowedParamKeys":["aggression"],"params":{"aggression":0.5}}]}"""
        ),
        verifier.canonicalArtifact(
            ArenaResolvedPolicyKind.EconomicPolicy,
            "preview-zero-fee",
            "economics-v1",
            """{"schemaVersion":"reef.arena.economicPolicy.v1","policyId":"preview-zero-fee","version":"economics-v1","currency":"USD","competitionLedger":{"startingCashPerCompetitor":"1000000.00","allowNegativeCash":false},"houseLedger":{"marketMakerStartingCash":"10000000.00","npcStartingCash":"10000000.00","subsidyBudget":"0.00"},"fees":{"makerBps":"0","takerBps":"0","cancelFee":"0.00","borrowBps":"0","liquidationPenaltyBps":"0"},"rebates":{"makerBps":"0","fundingSource":"none"},"sources":[],"sinks":[],"reconciliation":{"tolerance":"0.01","requireBalancedTransfers":true,"competitionLedger":true,"houseLedger":true}}"""
        )
    )

    @Test
    fun schedulesEvaluatesAndLocksAnIdempotentCapacityBoundedRoster() {
        var clock = Instant.parse("2026-08-01T00:00:00Z")
        val store = InMemoryArenaRunAdmissionStore()
        val service = ArenaRunAdmissionApplicationService(store, now = { clock })
        val window = service.scheduleWindow(
            actor,
            ScheduleArenaAdmissionWindowCommand(
                "weekly-2026-08-10",
                ArenaAdmissionWindowPolicy("admission-v1"),
                Instant.parse("2026-08-10T00:00:00Z"),
                "America/Toronto"
            )
        )

        clock = window.rosterLockAt
        assertEquals(
            window,
            service.scheduleWindow(
                actor,
                ScheduleArenaAdmissionWindowCommand(
                    "weekly-2026-08-10",
                    ArenaAdmissionWindowPolicy("admission-v1"),
                    Instant.parse("2026-08-10T00:00:00Z"),
                    "America/Toronto"
                )
            )
        )
        val decisions = listOf("bot-b", "bot-a").mapIndexed { index, botId ->
            service.evaluate(
                actor,
                EvaluateArenaEligibilityCommand("eval-$index", window.windowId, eligible(botId, window))
            )
        }
        val command = LockArenaRosterCommand(
            snapshotId = "roster-2026-08-10",
            windowId = window.windowId,
            policy = policy(),
            resolvedPolicies = resolvedPolicies,
            candidates = decisions.map {
                ArenaRosterCandidateCommand(it.evaluationId, 10)
            },
            maxBots = 1
        )

        val preview = service.previewRoster(
            actor,
            PreviewArenaRosterCommand(window.windowId, command.candidates, maxBots = 1)
        )
        assertFailsWith<IllegalArgumentException> {
            service.lockRoster(
                actor,
                command.copy(policy = command.policy.copy(economicPolicyHash = "sha256:caller-supplied"))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.lockRoster(
                actor,
                command.copy(snapshotId = "roster-incomplete", candidates = command.candidates.take(1))
            )
        }

        val first = service.lockRoster(actor, command)
        assertFailsWith<IllegalArgumentException> {
            service.previewRoster(actor, PreviewArenaRosterCommand(window.windowId, command.candidates, maxBots = 1))
        }
        clock = clock.plusSeconds(60)
        assertEquals(
            decisions.first(),
            service.evaluate(
                actor,
                EvaluateArenaEligibilityCommand("eval-0", window.windowId, eligible("bot-b", window))
            )
        )
        val replay = service.lockRoster(actor, command)

        assertEquals("bot-a", preview.included.single().decision.botId)
        assertEquals("bot-b", preview.capacityOverflow.single().decision.botId)

        assertEquals("bot-a", first.snapshot.entries.single().botId)
        assertEquals(first.snapshot, replay.snapshot)
        assertEquals(first.snapshot, service.roster(actor, window.windowId))
        val capacityDecision = service.decisions(actor, window.windowId).single {
            ArenaEligibilityReason.CapacityUnavailable in it.reasons
        }
        assertEquals("bot-b", capacityDecision.botId)
        assertEquals(ArenaEligibilityOutcome.RolledToNextWindow, capacityDecision.outcome)

        val removalCommand = RemoveArenaRosterEntryCommand(
            "removal-1",
            window.windowId,
            "bot-a",
            "v1",
            ArenaRosterRemovalReason.Availability,
            "runner pool unavailable"
        )
        val removal = service.removeFromRoster(actor, removalCommand)
        clock = window.scheduledStart.plusSeconds(1)
        assertEquals(removal, service.removeFromRoster(actor, removalCommand))
        assertEquals(listOf(removal), service.removals(actor, window.windowId))
        assertFailsWith<IllegalArgumentException> {
            service.removeFromRoster(actor, removalCommand.copy(removalId = "removal-2"))
        }
    }

    private fun eligible(botId: String, window: com.reef.arena.controlplane.arena.ArenaAdmissionWindow) =
        ArenaEligibilityCandidate(
            botId = botId,
            versionId = "v1",
            invitedAt = window.inviteDecisionCutoff,
            approvedHeadSha = "abc123",
            currentHeadSha = "abc123",
            checksPassedAt = window.mergeReadinessCutoff,
            provisionedAt = window.mergeReadinessCutoff,
            configValidatedAt = window.mergeReadinessCutoff,
            mergedAt = window.rosterLockAt,
            registryVerifiedAt = window.rosterLockAt,
            sourceHash = "sha256:$botId-src",
            artifactHash = "sha256:$botId-bin",
            configHash = "sha256:$botId-cfg",
            versionStatus = ArenaBotVersionStatus.Approved,
            ownerTrusted = true,
            ownershipActive = true,
            botRestricted = false,
            ownerRestricted = false,
            secretSliceExists = true,
            gameModeAllowed = true,
            runtimeSupported = true,
            riskPreflightPassed = true
        )

    private fun policy() = ArenaRosterPolicySnapshot(
        "continuous-book", "baseline", "sha256:seeds", "actors-v1", resolvedPolicies.actorProfileCatalog.contentHash,
        "risk-v1", "sha256:risk", "score-v1", "sha256:score", "economics-v1", resolvedPolicies.economicPolicy.contentHash
    )
}

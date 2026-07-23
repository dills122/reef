package com.reef.arena.controlplane.api

import com.reef.arena.controlplane.application.ArenaAdminApplicationService
import com.reef.arena.controlplane.application.ArenaRunAdmissionApplicationService
import com.reef.arena.controlplane.arena.ArenaBot
import com.reef.arena.controlplane.arena.ArenaBotMetadata
import com.reef.arena.controlplane.arena.ArenaBotVersionStatus
import com.reef.arena.controlplane.arena.ArenaControlPlaneService
import com.reef.arena.controlplane.arena.ArenaAdmissionWindowPolicy
import com.reef.arena.controlplane.arena.ArenaEligibilityOutcome
import com.reef.arena.controlplane.arena.ArenaQualificationStatus
import com.reef.arena.controlplane.arena.ArenaRosterCandidate
import com.reef.arena.controlplane.arena.ArenaRosterLocker
import com.reef.arena.controlplane.arena.ArenaRosterPolicySnapshot
import com.reef.arena.controlplane.arena.ArenaRunEligibilityDecision
import com.reef.arena.controlplane.arena.ArenaRuntimeConfigDescriptor
import com.reef.arena.controlplane.arena.ArenaRuntimeConfigProvider
import com.reef.arena.controlplane.arena.InMemoryArenaBotEntitlementStore
import com.reef.arena.controlplane.arena.InMemoryArenaBotRegistryStore
import com.reef.arena.controlplane.arena.InMemoryArenaSubmissionAdmissionStore
import com.reef.arena.controlplane.arena.InMemoryArenaRunAdmissionStore
import com.reef.arena.controlplane.arena.LocalDevBotConfigService
import com.reef.arena.controlplane.arena.RegisterArenaBotCommand
import com.reef.arena.controlplane.arena.RegisterArenaBotVersionCommand
import com.reef.platform.api.AdminRequestPrincipal
import com.reef.platform.api.OptionalProductAdminRoute
import com.reef.platform.api.OptionalProductRouteExtension
import com.reef.platform.api.PlatformHotPathResponse
import com.reef.platform.api.validateOptionalProductRouteExtensions
import com.reef.platform.application.admin.AdminServiceTokenFamily
import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.AdminTrustState
import com.reef.platform.application.admin.GitHubUserIdentity
import com.reef.platform.application.admin.InMemoryAdminIdentityStore
import com.reef.platform.application.analytics.InMemorySimulationRunExportStore
import com.reef.platform.application.analytics.SimulationRunExportService
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ZERO_HASH = "sha256:0000000000000000000000000000000000000000000000000000000000000000"

class ArenaAdminGatewayTest {
    @Test
    fun replacesReadsAndDeletesOwnerScopedBotConfigThroughInjectedSecretBoundary() {
        val now = Instant.parse("2026-07-20T12:00:00Z")
        val registry = InMemoryArenaBotRegistryStore().apply {
            saveBot(
                ArenaBot(
                    botId = "bot-config-1",
                    fileName = "bot-config-1.ts",
                    metadata = ArenaBotMetadata(
                        name = "Config Bot",
                        publisher = "publisher-1",
                        email = "publisher@example.com"
                    ),
                    createdAt = now
                )
            )
        }
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(registry, now = { now }),
            adminIdentityService = null,
            analyticsRunExportService = null,
            botConfigSecretService = LocalDevBotConfigService(now = { now })
        )
        val principal = AdminRequestPrincipal("operator-1", "config-test", now.toString())

        val replaced = gateway.handleInternal(
            "PUT",
            "/internal/admin/arena/bots/config",
            null,
            """{"botId":"bot-config-1","config":{"maxInventory":100,"strategy":"maker"}}""",
            principal
        )
        val status = gateway.handleInternal(
            "GET",
            "/internal/admin/arena/bots/config",
            "botId=bot-config-1",
            "",
            principal
        )
        val deleted = gateway.handleInternal(
            "DELETE",
            "/internal/admin/arena/bots/config",
            "botId=bot-config-1",
            "",
            principal
        )
        val emptyStatus = gateway.handleInternal(
            "GET",
            "/internal/admin/arena/bots/config",
            "botId=bot-config-1",
            "",
            principal
        )

        assertEquals(200, replaced?.status, replaced?.body)
        assertContains(replaced!!.body, "\"ownerIdentity\":\"publisher-1\"")
        assertContains(replaced.body, "\"keys\":[\"maxInventory\",\"strategy\"]")
        assertEquals(200, status?.status, status?.body)
        assertContains(status!!.body, "\"hasConfig\":true")
        assertContains(status.body, "\"version\":1")
        assertEquals(200, deleted?.status, deleted?.body)
        assertContains(deleted!!.body, "\"hasConfig\":false")
        assertEquals(200, emptyStatus?.status, emptyStatus?.body)
        assertContains(emptyStatus!!.body, "\"hasConfig\":false")
    }

    @Test
    fun servesQualificationDecisionAndRuntimeDescriptorReads() {
        val registry = InMemoryArenaBotRegistryStore()
        val now = Instant.parse("2026-07-18T00:00:00Z")
        val controlPlane = ArenaControlPlaneService(registry) { now }
        controlPlane.registerBot(
            RegisterArenaBotCommand(
                "bot-1",
                "bot-1.ts",
                ArenaBotMetadata("Bot 1", "Publisher", "publisher@example.com", "test bot", "1.0.0")
            )
        )
        controlPlane.registerVersion(
            RegisterArenaBotVersionCommand(
                "bot-1",
                "v1",
                "sha256:source",
                "sha256:artifact",
                "1.5.0",
                "v1",
                "sha256:deps"
            )
        )
        controlPlane.transitionVersion(
            "bot-1",
            "v1",
            ArenaBotVersionStatus.Submitted,
            "scanner",
            "submitted",
            "gateway-test"
        )
        controlPlane.recordQualificationReport(
            "bot-1",
            "v1",
            "report-1",
            ArenaQualificationStatus.Passed,
            listOf("scanner ok"),
            "policy-v1"
        )
        controlPlane.replaceRuntimeConfigDescriptors(
            "bot-1",
            "v1",
            listOf(
                ArenaRuntimeConfigDescriptor(
                    botId = "bot-1",
                    versionId = "v1",
                    key = "maxInventory",
                    provider = ArenaRuntimeConfigProvider.OpenBao,
                    secretPath = "secret/bots/publisher/bot-1",
                    required = true,
                    description = "inventory cap"
                )
            )
        )
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(registry, now = { now }),
            adminIdentityService = null,
            analyticsRunExportService = null
        )
        val principal = AdminRequestPrincipal("admin-cli", "gateway-test", now.toString())

        val bot = gateway.handleInternal(
            "GET",
            "/internal/admin/arena/bots",
            "botId=bot-1",
            "",
            principal
        )
        val reports = gateway.handleInternal(
            "GET",
            "/internal/admin/arena/qualification-reports",
            "botId=bot-1&versionId=v1",
            "",
            principal
        )
        val decisions = gateway.handleInternal(
            "GET",
            "/internal/admin/arena/operator-decisions",
            "botId=bot-1&versionId=v1",
            "",
            principal
        )
        val descriptors = gateway.handleInternal(
            "GET",
            "/internal/admin/arena/runtime-config-descriptors",
            "botId=bot-1&versionId=v1",
            "",
            principal
        )

        listOf(bot, reports, decisions, descriptors).forEach { response ->
            assertEquals(200, response?.status, response?.body)
        }
        assertContains(bot!!.body, "\"description\":\"test bot\"")
        assertContains(reports!!.body, "\"reportId\":\"report-1\"")
        assertContains(reports.body, "\"issues\":[\"scanner ok\"]")
        assertContains(decisions!!.body, "\"toStatus\":\"Submitted\"")
        assertContains(descriptors!!.body, "\"key\":\"maxInventory\"")
        assertContains(descriptors.body, "\"provider\":\"OpenBao\"")
    }

    @Test
    fun botConfigGatewayValidatesMethodsIdentifiersAndPayloadsBeforeSecretAccess() {
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(InMemoryArenaBotRegistryStore()),
            adminIdentityService = null,
            analyticsRunExportService = null
        )
        val principal = AdminRequestPrincipal("admin-cli", "gateway-test", "2026-07-18T00:00:00Z")

        val missingGet = gateway.handleInternal(
            "GET",
            "/internal/admin/arena/bots/config",
            null,
            "",
            principal
        )
        val unknownGet = gateway.handleInternal(
            "GET",
            "/internal/admin/arena/bots/config",
            "botId=missing",
            "",
            principal
        )
        val malformedPut = gateway.handleInternal(
            "PUT",
            "/internal/admin/arena/bots/config",
            null,
            """{"botId":"""",
            principal
        )
        val missingConfigPut = gateway.handleInternal(
            "PUT",
            "/internal/admin/arena/bots/config",
            null,
            """{"botId":"bot-1"}""",
            principal
        )
        val unknownPut = gateway.handleInternal(
            "PUT",
            "/internal/admin/arena/bots/config",
            null,
            """{"botId":"missing","config":{"maxInventory":"100"}}""",
            principal
        )
        val missingDelete = gateway.handleInternal(
            "DELETE",
            "/internal/admin/arena/bots/config",
            null,
            "",
            principal
        )
        val unknownDelete = gateway.handleInternal(
            "DELETE",
            "/internal/admin/arena/bots/config",
            "botId=missing",
            "",
            principal
        )
        val unsupported = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bots/config",
            null,
            "{}",
            principal
        )

        assertEquals(400, missingGet?.status)
        assertEquals(404, unknownGet?.status)
        assertEquals(400, malformedPut?.status)
        assertEquals(400, missingConfigPut?.status)
        assertEquals(404, unknownPut?.status)
        assertEquals(400, missingDelete?.status)
        assertEquals(404, unknownDelete?.status)
        assertEquals(405, unsupported?.status)
    }

    @Test
    fun registersReadsAndTransitionsBotVersionsThroughProductGateway() {
        val registry = InMemoryArenaBotRegistryStore()
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(registry) { Instant.parse("2026-07-18T00:00:00Z") },
            adminIdentityService = null,
            analyticsRunExportService = null
        )
        val principal = AdminRequestPrincipal("admin-cli", "gateway-test", "2026-07-18T00:00:00Z")

        val bot = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bots",
            null,
            """{"botId":"bot-1","fileName":"bot-1.ts","name":"Bot 1","publisher":"Publisher","email":"publisher@example.com"}""",
            principal
        )
        val version = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bot-versions",
            null,
            """{"botId":"bot-1","versionId":"v1","sourceHash":"sha256:source","artifactHash":"sha256:artifact","sdkVersion":"1.5.0","apiVersion":"v1","dependencyManifestHash":"sha256:deps"}""",
            principal
        )
        val banned = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bot-versions/transition",
            null,
            """{"botId":"bot-1","versionId":"v1","status":"banned","reason":"policy violation"}""",
            principal
        )
        val fetched = gateway.handleInternal(
            "GET",
            "/internal/admin/arena/bot-versions",
            "botId=bot-1&versionId=v1",
            "",
            principal
        )

        assertEquals(200, bot?.status)
        assertContains(bot!!.body, "\"botId\":\"bot-1\"")
        assertEquals(200, version?.status)
        assertContains(version!!.body, "\"botVersionStatus\":\"Draft\"")
        assertEquals(200, banned?.status)
        assertContains(banned!!.body, "\"botVersionStatus\":\"Banned\"")
        assertEquals(200, fetched?.status)
        assertContains(fetched!!.body, "\"status\":\"Banned\"")
    }

    @Test
    fun recordsRunLifecycleAndServesAdminAndPublicLeaderboards() {
        val registry = InMemoryArenaBotRegistryStore()
        val now = Instant.parse("2026-08-10T00:00:00Z")
        val controlPlane = ArenaControlPlaneService(registry) { now }
        controlPlane.registerBot(
            RegisterArenaBotCommand(
                "bot-1",
                "bot-1.ts",
                ArenaBotMetadata("Bot 1", "Publisher", "publisher@example.com")
            )
        )
        controlPlane.registerVersion(
            RegisterArenaBotVersionCommand(
                "bot-1",
                "v1",
                "sha256:source",
                "sha256:artifact",
                "1.5.0",
                "v1",
                "sha256:deps"
            )
        )
        listOf(
            ArenaBotVersionStatus.Submitted,
            ArenaBotVersionStatus.ChecksPassed,
            ArenaBotVersionStatus.Approved
        ).forEach { status ->
            controlPlane.transitionVersion("bot-1", "v1", status, "admin-cli", status.name, "gateway-test")
        }
        val admissionStore = InMemoryArenaRunAdmissionStore()
        val window = ArenaAdmissionWindowPolicy("invite-v1").schedule(
            "window-1", now, "UTC", Instant.parse("2026-07-18T00:00:00Z")
        )
        admissionStore.createWindow(window)
        val decision = ArenaRunEligibilityDecision(
            "evaluation-1", window.windowId, "bot-1", "v1", ArenaEligibilityOutcome.EligibleForRoster,
            emptyList(), "sha256:source", "sha256:artifact", "sha256:config", window.rosterLockAt,
            "gateway-test"
        )
        admissionStore.recordDecision(decision)
        val policy = ArenaRosterPolicySnapshot(
            "hosted-sim", "scenario-1", ZERO_HASH, "actors-v1", ZERO_HASH,
            "policy-v1", ZERO_HASH, "score-v2", ZERO_HASH, "preview-zero-fee-v1", ZERO_HASH
        )
        val roster = ArenaRosterLocker().lock(
            "roster-1", window, policy, listOf(ArenaRosterCandidate(decision, 10)), 1,
            window.rosterLockAt, "admin-cli", "gateway-test"
        ).snapshot
        admissionStore.lockRoster(roster)
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(registry, admissionStore, now = { now }),
            adminIdentityService = null,
            analyticsRunExportService = null
        )
        val principal = AdminRequestPrincipal("admin-cli", "gateway-test", now.toString())

        val registered = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/runs",
            null,
            """{"runId":"run-1","modeId":"hosted-sim","scenarioId":"scenario-1","seed":42,"policyVersion":"policy-v1","admissionWindowId":"window-1","rosterSnapshotId":"roster-1","rosterSnapshotHash":"${roster.snapshotHash}","seedSetHash":"$ZERO_HASH","actorProfileVersion":"actors-v1","actorProfileHash":"$ZERO_HASH","riskPolicyHash":"$ZERO_HASH","policyEnvelopeHash":"$ZERO_HASH","scoringPolicyVersion":"score-v2","scoringPolicyHash":"$ZERO_HASH","economicPolicyVersion":"preview-zero-fee-v1","economicPolicyHash":"$ZERO_HASH","botVersions":[{"botId":"bot-1","versionId":"v1"}]}""",
            principal
        )
        val running = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/runs/status",
            null,
            """{"runId":"run-1","status":"running"}""",
            principal
        )
        val result = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/run-bot-results",
            null,
            """{"runId":"run-1","botId":"bot-1","versionId":"v1","scoringPolicyVersion":"score-v2","scoringPolicyHash":"sha256:0000000000000000000000000000000000000000000000000000000000000000","policyEnvelopeHash":"sha256:0000000000000000000000000000000000000000000000000000000000000000","finalEquity":1030000,"realizedPnl":30000,"maxDrawdown":900,"actionsProposed":13,"orderActionsProposed":9,"dataCalls":21,"signalsGenerated":5,"disqualified":false}""",
            principal
        )
        val completed = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/runs/status",
            null,
            """{"runId":"run-1","status":"completed"}""",
            principal
        )
        val enforcement = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/run-enforcement-events",
            null,
            """{"runId":"run-1","botId":"bot-1","versionId":"v1","decision":"freeze","reasonCode":"tick_policy_violation","reason":"max actions exceeded","policyVersion":"arena-risk-v0","countersJson":"{\"maxActionsPerTick\":11}"}""",
            principal
        )
        val runs = gateway.handleInternal("GET", "/internal/admin/arena/runs", null, "", principal)
        val results = gateway.handleInternal("GET", "/internal/admin/arena/run-bot-results", "runId=run-1", "", principal)
        val events = gateway.handleInternal("GET", "/internal/admin/arena/run-enforcement-events", "runId=run-1", "", principal)
        val leaderboard = gateway.handleInternal(
            "GET",
            "/internal/admin/arena/leaderboard",
            "modeId=hosted-sim&scoringPolicyVersion=score-v2",
            "",
            principal
        )
        val publicLeaderboard = gateway.handlePublicRead(
            "/api/v1/arena/leaderboard",
            "modeId=hosted-sim&scoringPolicyVersion=score-v2"
        )

        listOf(registered, running, completed, result, enforcement, runs, results, events, leaderboard, publicLeaderboard)
            .forEach { response -> assertEquals(200, response?.status, response?.body) }
        assertContains(completed!!.body, "\"status\":\"Completed\"")
        assertContains(runs!!.body, "\"runId\":\"run-1\"")
        assertContains(results!!.body, "\"finalEquity\":1030000")
        assertContains(events!!.body, "\"reasonCode\":\"tick_policy_violation\"")
        assertContains(leaderboard!!.body, "\"botName\":\"Bot 1\"")
        assertContains(publicLeaderboard!!.body, "\"rank\":1")
        assertFalse(publicLeaderboard.body.contains("publisher@example.com"))
    }

    @Test
    fun ingestsAndReadsAnalyticsRunExportsThroughProductGateway() {
        val analytics = SimulationRunExportService(InMemorySimulationRunExportStore()) {
            Instant.parse("2026-07-18T00:00:00Z")
        }
        val gateway = ArenaAdminGateway(
            arenaAdminService = null,
            adminIdentityService = null,
            analyticsRunExportService = analytics
        )
        val principal = AdminRequestPrincipal("sim-admin", "gateway-test", "2026-07-18T00:00:00Z")
        val posted = gateway.handleInternal(
            "POST",
            "/internal/admin/analytics/run-exports",
            null,
            """{"runId":"run-export-1","scenarioId":"stress-smoke","runKind":"stream-ack-soak","source":"local","gitSha":"abc123","profile":"10k-15m","status":"passed","counts":{"attempted":600000,"accepted":599900,"completed":599800,"materialized":599800,"projected":599800,"failed":100},"latencyMs":{"p50":4.2,"p95":12.5,"p99":25.1},"summary":{"botResults":[{"botId":"bot-a","finalEquity":1002500,"realizedPnl":2500,"maxDrawdown":125,"tradingMetrics":{"commands":{"submitted":12,"failed":1,"rejected":1,"timedOut":0}}}],"settlementScore":{"participants":[{"participantId":"bot-a","scorePenaltyPoints":25,"agedFailCount":0}]}}}""",
            principal
        )
        val fetched = gateway.handleInternal(
            "GET",
            "/internal/admin/analytics/run-exports",
            "runId=run-export-1",
            "",
            principal
        )
        val summaries = gateway.handleInternal(
            "GET",
            "/internal/admin/analytics/run-bot-summaries",
            "runId=run-export-1",
            "",
            principal
        )

        assertEquals(200, posted?.status, posted?.body)
        assertContains(posted!!.body, "\"attempted\":600000")
        assertEquals(200, fetched?.status, fetched?.body)
        assertContains(fetched!!.body, "\"profile\":\"10k-15m\"")
        assertEquals(200, summaries?.status, summaries?.body)
        assertContains(summaries!!.body, "\"botId\":\"bot-a\"")
        assertContains(summaries.body, "\"failCount\":2")
        assertContains(summaries.body, "\"authoritative\":false")
    }

    @Test
    fun openBaoProvisioningBindsOidcActorToArenaBotOwner() {
        val identities = AdminIdentityService(InMemoryAdminIdentityStore())
        val entitlements = InMemoryArenaBotEntitlementStore()
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(InMemoryArenaBotRegistryStore()),
            adminIdentityService = identities,
            analyticsRunExportService = null,
            arenaBotEntitlementStore = entitlements
        )
        val principal = AdminRequestPrincipal("admin-cli", "gateway-test", "2026-07-18T00:00:00Z")
        val assignment = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bots/ownership",
            null,
            """{"botId":"bot-1","githubUserId":123,"githubLogin":"octo","displayName":"Octo"}""",
            principal
        )
        val submitterMismatch = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bots/openbao-provision",
            null,
            openBaoProvisionBody(githubOidcToken("octo"), "attacker", "bot-1", "remove"),
            principal
        )
        val wrongOwner = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bots/openbao-provision",
            null,
            openBaoProvisionBody(githubOidcToken("attacker"), "attacker", "bot-1", "remove"),
            principal
        )

        assertEquals(200, assignment?.status, assignment?.body)
        assertEquals(403, submitterMismatch?.status, submitterMismatch?.body)
        assertContains(submitterMismatch!!.body, "exact approved fork admission is required")
        assertEquals(403, wrongOwner?.status, wrongOwner?.body)
        assertContains(wrongOwner!!.body, "not authorized for OpenBao bot secret slice")
    }

    @Test
    fun openBaoProvisioningDelegatesOnlyForExactApprovedForkAdmission() {
        val admissions = InMemoryArenaSubmissionAdmissionStore()
        val identities = trustedAdmissionIdentities()
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(InMemoryArenaBotRegistryStore()),
            adminIdentityService = identities,
            analyticsRunExportService = null,
            arenaSubmissionAdmissionStore = admissions
        )
        val sha = "a".repeat(40)
        val principal = AdminRequestPrincipal("bot-submission-ci", "gateway-test", "2026-07-23T00:00:00Z")
        val pendingBody = """{"repository":"dills122/reef","pullRequestNumber":42,"botId":"bot-1","headRepository":"octo/reef","headOwnerLogin":"octo","githubUserId":123,"githubLogin":"octo","headSha":"$sha"}"""
        gateway.handleInternal("POST", "/internal/admin/arena/submission-admissions", null, pendingBody, principal)
        gateway.handleInternal(
            "POST",
            "/internal/admin/arena/submission-admissions/approve",
            null,
            """{"repository":"dills122/reef","pullRequestNumber":42,"headSha":"$sha","approverActorId":"user-gh-999","reason":"invite verified"}""",
            AdminRequestPrincipal("operator-1", "gateway-test", "2026-07-23T00:01:00Z")
        )
        val pendingOnly = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/submission-admissions",
            null,
            """{"repository":"dills122/reef","pullRequestNumber":43,"botId":"bot-1","headRepository":"octo/reef","headOwnerLogin":"octo","githubUserId":123,"githubLogin":"octo","headSha":"$sha"}""",
            principal
        )

        val delegated = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bots/openbao-provision",
            null,
            openBaoProvisionBody(
                githubOidcToken("reviewer", "dills122/reef"),
                "octo",
                "bot-1",
                "add",
                "dills122/reef",
                42,
                sha
            ),
            principal
        )
        val staleHead = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bots/openbao-provision",
            null,
            openBaoProvisionBody(
                githubOidcToken("reviewer", "dills122/reef"),
                "octo",
                "bot-1",
                "add",
                "dills122/reef",
                42,
                "b".repeat(40)
            ),
            principal
        )
        val wrongBot = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bots/openbao-provision",
            null,
            openBaoProvisionBody(
                githubOidcToken("reviewer", "dills122/reef"),
                "octo",
                "other-bot",
                "add",
                "dills122/reef",
                42,
                sha
            ),
            principal
        )
        val wrongRepositoryClaim = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bots/openbao-provision",
            null,
            openBaoProvisionBody(
                githubOidcToken("reviewer", "other/reef"),
                "octo",
                "bot-1",
                "add",
                "dills122/reef",
                42,
                sha
            ),
            principal
        )
        val unapproved = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bots/openbao-provision",
            null,
            openBaoProvisionBody(
                githubOidcToken("reviewer", "dills122/reef"),
                "octo",
                "bot-1",
                "add",
                "dills122/reef",
                43,
                sha
            ),
            principal
        )

        assertEquals(200, pendingOnly?.status, pendingOnly?.body)
        assertEquals(503, delegated?.status, delegated?.body)
        assertContains(delegated!!.body, "BAO_ADDR is not configured")
        assertEquals(403, staleHead?.status, staleHead?.body)
        assertContains(staleHead!!.body, "exact approved fork admission is required")
        assertEquals(403, wrongBot?.status, wrongBot?.body)
        assertContains(wrongBot!!.body, "exact approved fork admission is required")
        assertEquals(403, wrongRepositoryClaim?.status, wrongRepositoryClaim?.body)
        assertContains(wrongRepositoryClaim!!.body, "exact approved fork admission is required")
        assertEquals(403, unapproved?.status, unapproved?.body)
        assertContains(unapproved!!.body, "exact approved fork admission is required")
    }

    @Test
    fun contributesDocumentedAnalyticsRoutesWithAnalyticsCredentials() {
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(arenaRegistryStore = InMemoryArenaBotRegistryStore()),
            adminIdentityService = null,
            analyticsRunExportService = null
        )

        val exportRoute = gateway.adminRoutes.single { it.externalPath == "/admin/v1/analytics/run-exports" }
        assertEquals(setOf("POST"), exportRoute.methods)
        assertEquals("/internal/admin/analytics/run-exports", exportRoute.internalPath)
        assertEquals("ANALYTICS_EXPORT_API_TOKEN", exportRoute.fallbackTokenEnv)
        assertEquals("ANALYTICS_EXPORT_API_ACTOR_ID", exportRoute.fallbackActorEnv)
        assertEquals(
            setOf(AdminServiceTokenFamily.Sim, AdminServiceTokenFamily.Admin),
            exportRoute.serviceTokenFamilies
        )
        assertEquals(setOf(AdminIdentityService.RolePlatformAdmin), exportRoute.sessionRoles)

        val summariesRoute = gateway.adminRoutes.single { it.externalPath == "/admin/v1/analytics/run-bot-summaries" }
        assertEquals(setOf("GET"), summariesRoute.methods)
        assertEquals("ANALYTICS_EXPORT_API_TOKEN", summariesRoute.fallbackTokenEnv)
        assertEquals("ANALYTICS_EXPORT_API_ACTOR_ID", summariesRoute.fallbackActorEnv)
    }

    @Test
    fun usesRouteSpecificFallbackCredentials() {
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(arenaRegistryStore = InMemoryArenaBotRegistryStore()),
            adminIdentityService = null,
            analyticsRunExportService = null
        )

        val arenaRoute = gateway.adminRoutes.single { it.externalPath == "/admin/v1/arena/bots" }
        assertEquals("ARENA_ADMIN_API_TOKEN", arenaRoute.fallbackTokenEnv)
        assertEquals("ARENA_ADMIN_API_ACTOR_ID", arenaRoute.fallbackActorEnv)

        val adminRoute = gateway.adminRoutes.single { it.externalPath == "/admin/v1/arena/bots/config" }
        assertEquals("ADMIN_API_TOKEN", adminRoute.fallbackTokenEnv)
        assertEquals("ADMIN_API_ACTOR_ID", adminRoute.fallbackActorEnv)
    }

    @Test
    fun rejectsMalformedArenaBotJson() {
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(arenaRegistryStore = InMemoryArenaBotRegistryStore()),
            adminIdentityService = null,
            analyticsRunExportService = null
        )

        val response = gateway.handleInternal(
            method = "POST",
            path = "/internal/admin/arena/bots",
            query = null,
            body = """{"botId":"""",
            principal = AdminRequestPrincipal("admin-cli", "test", "2026-07-18T00:00:00Z")
        )

        assertEquals(400, response?.status)
        assertTrue(response!!.body.contains("invalid json payload"))
    }

    @Test
    fun exposesBotConfigReplacementAsPutAndDispatchesIt() {
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(arenaRegistryStore = InMemoryArenaBotRegistryStore()),
            adminIdentityService = null,
            analyticsRunExportService = null
        )

        val route = gateway.adminRoutes.single { it.externalPath == "/admin/v1/arena/bots/config" }
        assertEquals(setOf("GET", "PUT", "DELETE"), route.methods)
        assertFalse("POST" in route.methods)

        val response = gateway.handleInternal(
            method = "PUT",
            path = route.internalPath,
            query = null,
            body = "{}",
            principal = AdminRequestPrincipal("admin-cli", "test", "2026-07-18T00:00:00Z")
        )
        assertEquals(400, response?.status)
        assertFalse(response!!.body.contains("method not allowed"))
    }

    @Test
    fun rejectsAmbiguousOptionalProductRoutesBeforeRuntimeDispatch() {
        val extension = object : OptionalProductRouteExtension {
            override val internalPaths = listOf("/internal/admin/example")
            override val publicReadPaths = emptyList<String>()
            override val adminRoutes = listOf(
                OptionalProductAdminRoute(
                    externalPath = "/admin/v1/example",
                    methods = setOf("POST"),
                    internalPath = "/internal/admin/example",
                    fallbackTokenEnv = "EXAMPLE_TOKEN",
                    fallbackActorEnv = "EXAMPLE_ACTOR",
                    serviceTokenFamilies = setOf(AdminServiceTokenFamily.Admin)
                )
            )

            override fun handleInternal(method: String, path: String, query: String?, body: String, principal: AdminRequestPrincipal): PlatformHotPathResponse? = null
            override fun handlePublicRead(path: String, query: String?): PlatformHotPathResponse? = null
        }

        val error = assertFailsWith<IllegalArgumentException> {
            validateOptionalProductRouteExtensions(listOf(extension, extension))
        }
        assertTrue(error.message!!.contains("duplicate optional product internal path"))
    }

    @Test
    fun contributesArenaAdminRoutesAndDispatchesRegistryReads() {
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(arenaRegistryStore = InMemoryArenaBotRegistryStore()),
            adminIdentityService = null,
            analyticsRunExportService = null
        )

        assertTrue(gateway.adminRoutes.any { it.externalPath == "/admin/v1/arena/bots" })
        val response = gateway.handleInternal(
            method = "GET",
            path = "/internal/admin/arena/bots",
            query = null,
            body = "",
            principal = AdminRequestPrincipal("admin-cli", "test", "2026-07-18T00:00:00Z")
        )

        assertEquals(200, response?.status)
    }

    @Test
    fun recordsAndApprovesShaBoundForkAdmission() {
        val admissions = InMemoryArenaSubmissionAdmissionStore()
        val identities = trustedAdmissionIdentities()
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(arenaRegistryStore = InMemoryArenaBotRegistryStore()),
            adminIdentityService = identities,
            analyticsRunExportService = null,
            arenaSubmissionAdmissionStore = admissions
        )
        val sha = "a".repeat(40)
        val body = """{"repository":"dills122/reef","pullRequestNumber":42,"botId":"sample-bot","headRepository":"octo/reef","headOwnerLogin":"octo","githubUserId":123,"githubLogin":"octo","headSha":"$sha"}"""

        val pending = gateway.handleInternal("POST", "/internal/admin/arena/submission-admissions", null, body, AdminRequestPrincipal("bot-submission-ci", "test", "2026-07-19T00:00:00Z"))
        val approved = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/submission-admissions/approve",
            null,
            """{"repository":"dills122/reef","pullRequestNumber":42,"headSha":"$sha","approverActorId":"user-gh-999","reason":"invite verified"}""",
            AdminRequestPrincipal("operator-1", "test", "2026-07-19T00:00:00Z")
        )

        assertEquals(200, pending?.status)
        assertTrue(pending!!.body.contains("pending_invite_review"))
        assertEquals(200, approved?.status)
        assertTrue(approved!!.body.contains("invite_approved"))
        assertEquals("user-gh-999", admissions.admission("dills122/reef", 42)?.invitationActor)
    }

    @Test
    fun rejectsInvitationApprovalWhenParticipantIsNotTrusted() {
        val admissions = InMemoryArenaSubmissionAdmissionStore()
        val identities = trustedAdmissionIdentities(participantTrusted = false)
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(arenaRegistryStore = InMemoryArenaBotRegistryStore()),
            adminIdentityService = identities,
            analyticsRunExportService = null,
            arenaSubmissionAdmissionStore = admissions
        )
        val sha = "a".repeat(40)
        val body = """{"repository":"dills122/reef","pullRequestNumber":42,"botId":"sample-bot","headRepository":"octo/reef","headOwnerLogin":"octo","githubUserId":123,"githubLogin":"octo","headSha":"$sha"}"""

        gateway.handleInternal("POST", "/internal/admin/arena/submission-admissions", null, body, AdminRequestPrincipal("bot-submission-ci", "test", "2026-07-19T00:00:00Z"))
        val response = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/submission-admissions/approve",
            null,
            """{"repository":"dills122/reef","pullRequestNumber":42,"headSha":"$sha","approverActorId":"user-gh-999","reason":"invite verified"}""",
            AdminRequestPrincipal("operator-1", "test", "2026-07-19T00:00:00Z")
        )

        assertEquals(403, response?.status)
        assertTrue(response!!.body.contains("participant is not trusted"))
        assertTrue(admissions.admission("dills122/reef", 42)?.state?.dbValue == "pending_invite_review")
    }

    @Test
    fun rejectsInvitationApprovalWhenReviewerIsNotTrustedOperator() {
        val admissions = InMemoryArenaSubmissionAdmissionStore()
        val identities = trustedAdmissionIdentities(reviewerTrusted = false)
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(arenaRegistryStore = InMemoryArenaBotRegistryStore()),
            adminIdentityService = identities,
            analyticsRunExportService = null,
            arenaSubmissionAdmissionStore = admissions
        )
        val sha = "a".repeat(40)
        val body = """{"repository":"dills122/reef","pullRequestNumber":42,"botId":"sample-bot","headRepository":"octo/reef","headOwnerLogin":"octo","githubUserId":123,"githubLogin":"octo","headSha":"$sha"}"""

        gateway.handleInternal("POST", "/internal/admin/arena/submission-admissions", null, body, AdminRequestPrincipal("bot-submission-ci", "test", "2026-07-19T00:00:00Z"))
        val response = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/submission-admissions/approve",
            null,
            """{"repository":"dills122/reef","pullRequestNumber":42,"headSha":"$sha","approverActorId":"user-gh-999","reason":"invite verified"}""",
            AdminRequestPrincipal("operator-1", "test", "2026-07-19T00:00:00Z")
        )

        assertEquals(403, response?.status)
        assertTrue(response!!.body.contains("reviewer is not a trusted Arena operator"))
    }

    @Test
    fun storesOwnershipInArenaAndReturnsOnlyActiveOwnedBots() {
        val registry = InMemoryArenaBotRegistryStore().apply {
            saveBot(
                ArenaBot(
                    botId = "sample-bot",
                    fileName = "sample-bot.zip",
                    metadata = ArenaBotMetadata("Sample Bot", "reef", "bots@reef.example"),
                    createdAt = Instant.parse("2026-07-18T00:00:00Z")
                )
            )
        }
        val entitlements = InMemoryArenaBotEntitlementStore()
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(arenaRegistryStore = registry),
            adminIdentityService = AdminIdentityService(InMemoryAdminIdentityStore()),
            analyticsRunExportService = null,
            arenaBotEntitlementStore = entitlements
        )

        val assignment = gateway.handleInternal(
            method = "POST",
            path = "/internal/admin/arena/bots/ownership",
            query = null,
            body = """{"botId":"sample-bot","githubUserId":123,"githubLogin":"octo","displayName":"Octo"}""",
            principal = AdminRequestPrincipal("admin-cli", "test", "2026-07-18T00:00:00Z")
        )
        val myBots = gateway.handleInternal(
            method = "GET",
            path = "/internal/admin/arena/my/bots",
            query = null,
            body = "",
            principal = AdminRequestPrincipal("user-gh-123", "test", "2026-07-18T00:00:00Z")
        )

        assertEquals(200, assignment?.status)
        assertEquals("sample-bot", entitlements.botOwnershipsForUser("user-gh-123").single().botId)
        assertEquals(200, myBots?.status)
        assertTrue(myBots!!.body.contains("sample-bot"))
    }

    @Test
    fun assignsForkBotOwnershipFromExactApprovedSubmissionAdmission() {
        val sha = "a".repeat(40)
        val admissions = InMemoryArenaSubmissionAdmissionStore()
        val identities = trustedAdmissionIdentities()
        val entitlements = InMemoryArenaBotEntitlementStore()
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(InMemoryArenaBotRegistryStore()),
            adminIdentityService = identities,
            analyticsRunExportService = null,
            arenaBotEntitlementStore = entitlements,
            arenaSubmissionAdmissionStore = admissions
        )
        val pendingBody = """{"repository":"dills122/reef","pullRequestNumber":42,"botId":"sample-bot","headRepository":"octo/reef","headOwnerLogin":"octo","githubUserId":123,"githubLogin":"octo","headSha":"$sha"}"""
        gateway.handleInternal(
            "POST",
            "/internal/admin/arena/submission-admissions",
            null,
            pendingBody,
            AdminRequestPrincipal("bot-submission-ci", "test", "2026-07-23T00:00:00Z")
        )
        gateway.handleInternal(
            "POST",
            "/internal/admin/arena/submission-admissions/approve",
            null,
            """{"repository":"dills122/reef","pullRequestNumber":42,"headSha":"$sha","approverActorId":"user-gh-999","reason":"invite verified"}""",
            AdminRequestPrincipal("operator-1", "test", "2026-07-23T00:01:00Z")
        )

        val assigned = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bots/ownership",
            null,
            """{"botId":"sample-bot","source":"approved-submission-admission","repository":"dills122/reef","pullRequestNumber":42,"headSha":"$sha"}""",
            AdminRequestPrincipal("bot-submission-ci", "test", "2026-07-23T00:02:00Z")
        )

        assertEquals(200, assigned?.status, assigned?.body)
        assertContains(assigned!!.body, """"githubLogin":"octo"""")
        assertEquals("user-gh-123", entitlements.botOwnerships("sample-bot").single().reefUserId)

        identities.updateTrustState("operator-1", "user-gh-123", AdminTrustState.Limited)
        val blockedAfterTrustChange = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bots/ownership",
            null,
            """{"botId":"sample-bot","source":"approved-submission-admission","repository":"dills122/reef","pullRequestNumber":42,"headSha":"$sha"}""",
            AdminRequestPrincipal("bot-submission-ci", "test", "2026-07-23T00:03:00Z")
        )
        assertEquals(403, blockedAfterTrustChange?.status)
        assertContains(blockedAfterTrustChange!!.body, "participant is not trusted")
    }

    @Test
    fun rejectsForkBotOwnershipWhenApprovedAdmissionDoesNotMatchHead() {
        val sha = "a".repeat(40)
        val admissions = InMemoryArenaSubmissionAdmissionStore()
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(InMemoryArenaBotRegistryStore()),
            adminIdentityService = trustedAdmissionIdentities(),
            analyticsRunExportService = null,
            arenaBotEntitlementStore = InMemoryArenaBotEntitlementStore(),
            arenaSubmissionAdmissionStore = admissions
        )
        val pendingBody = """{"repository":"dills122/reef","pullRequestNumber":42,"botId":"sample-bot","headRepository":"octo/reef","headOwnerLogin":"octo","githubUserId":123,"githubLogin":"octo","headSha":"$sha"}"""
        gateway.handleInternal(
            "POST",
            "/internal/admin/arena/submission-admissions",
            null,
            pendingBody,
            AdminRequestPrincipal("bot-submission-ci", "test", "2026-07-23T00:00:00Z")
        )

        val rejected = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/bots/ownership",
            null,
            """{"botId":"sample-bot","source":"approved-submission-admission","repository":"dills122/reef","pullRequestNumber":42,"headSha":"${"b".repeat(40)}"}""",
            AdminRequestPrincipal("bot-submission-ci", "test", "2026-07-23T00:02:00Z")
        )

        assertEquals(409, rejected?.status)
        assertContains(rejected!!.body, "does not match bot and head SHA")
    }

    @Test
    fun exposesAdmissionEvaluationAndImmutableRosterLockRoutes() {
        var clock = Instant.parse("2026-07-20T00:00:00Z")
        val gateway = ArenaAdminGateway(
            arenaAdminService = null,
            adminIdentityService = null,
            analyticsRunExportService = null,
            arenaRunAdmissionService = ArenaRunAdmissionApplicationService(
                InMemoryArenaRunAdmissionStore(),
                now = { clock }
            )
        )
        val principal = AdminRequestPrincipal("admin-cli", "admission-route", clock.toString())
        val scheduled = gateway.handleInternal(
            "POST", "/internal/admin/arena/admission-windows", null,
            """{"windowId":"weekly-2026-07-25","policyVersion":"admission-v1","scheduledStart":"2026-07-25T00:00:00Z","displayTimeZone":"America/Toronto"}""",
            principal
        )
        clock = Instant.parse("2026-07-24T00:00:00Z")
        val evaluated = gateway.handleInternal(
            "POST", "/internal/admin/arena/eligibility-decisions", null,
            """{
              "evaluationId":"eval-route-1","windowId":"weekly-2026-07-25","botId":"bot-route","versionId":"v1",
              "invitedAt":"2026-07-22T00:00:00Z","approvedHeadSha":"abc123","currentHeadSha":"abc123",
              "checksPassedAt":"2026-07-23T00:00:00Z","provisionedAt":"2026-07-23T00:00:00Z",
              "configValidatedAt":"2026-07-23T00:00:00Z","mergedAt":"2026-07-24T00:00:00Z",
              "registryVerifiedAt":"2026-07-24T00:00:00Z","sourceHash":"sha256:source",
              "artifactHash":"sha256:artifact","configHash":"sha256:config","versionStatus":"approved",
              "ownerTrusted":true,"ownershipActive":true,"botRestricted":false,"ownerRestricted":false,
              "secretSliceExists":true,"gameModeAllowed":true,"runtimeSupported":true,"riskPreflightPassed":true
            }""".trimIndent(),
            principal
        )
        val incompleteFlags = gateway.handleInternal(
            "POST", "/internal/admin/arena/eligibility-decisions", null,
            """{"evaluationId":"eval-invalid","windowId":"weekly-2026-07-25","botId":"bot-invalid","versionId":"v1","versionStatus":"approved"}""",
            principal
        )
        val previewed = gateway.handleInternal(
            "POST", "/internal/admin/arena/roster-previews", null,
            """{"windowId":"weekly-2026-07-25","maxBots":1,"candidates":[{"evaluationId":"eval-route-1","priority":10}]}""",
            principal
        )
        val locked = gateway.handleInternal(
            "POST", "/internal/admin/arena/rosters", null,
            """{
              "snapshotId":"roster-route-1","windowId":"weekly-2026-07-25","maxBots":1,
              "policy":{"modeId":"continuous-book","scenarioId":"baseline","seedSetHash":"sha256:seeds",
                "actorProfileVersion":"actors-v1","actorProfileHash":"sha256:e4cbc6bf525a82aa554b6404c4d9021e929f039c47a9f8e55428e60d80d5fbc4",
                "riskPolicyVersion":"risk-v1","riskPolicyHash":"sha256:risk",
                "scoringPolicyVersion":"score-v1","scoringPolicyHash":"sha256:d87133eca6c0a4994fd0aa30af3108b72ac679955128f14e64335417358dd15a",
                "economicPolicyVersion":"economics-v1","economicPolicyHash":"sha256:f2c81084b40c3654dcf2b3c15fcbb0ce938a641421d1446f8c4ad96a332b298b"},
              "resolvedPolicies":{
                "actorProfileCatalog":{"artifactId":"arena-actor-profiles","version":"actors-v1","content":{
                  "schemaVersion":"reef.arena.actorProfiles.v1","catalogId":"arena-actor-profiles","version":"actors-v1","profiles":[{
                    "profileId":"competitor-standard","version":"v1","actorClass":"competitor","description":"Default competitor",
                    "difficultyBucket":"ranked-standard","scoreEffect":"eligible-for-score","allowedParamKeys":["aggression"],"params":{"aggression":0.5}}]}},
                "economicPolicy":{"artifactId":"preview-zero-fee","version":"economics-v1","content":{
                  "schemaVersion":"reef.arena.economicPolicy.v1","policyId":"preview-zero-fee","version":"economics-v1","currency":"USD",
                  "competitionLedger":{"startingCashPerCompetitor":"1000000.00","allowNegativeCash":false},
                  "houseLedger":{"marketMakerStartingCash":"10000000.00","npcStartingCash":"10000000.00","subsidyBudget":"0.00"},
                  "fees":{"makerBps":"0","takerBps":"0","cancelFee":"0.00","borrowBps":"0","liquidationPenaltyBps":"0"},
                  "rebates":{"makerBps":"0","fundingSource":"none"},"sources":[],"sinks":[],
                  "reconciliation":{"tolerance":"0.01","requireBalancedTransfers":true,"competitionLedger":true,"houseLedger":true}}},
                "scoringPolicy":{"artifactId":"arena-score","version":"score-v1","content":{
                  "schemaVersion":"reef.arena.scoringPolicy.v1","policyId":"arena-score","version":"score-v1","status":"public-preview",
                  "formulaVersion":"score-v1-final-equity-risk-conduct","baseline":1000000,"publicScoringEnabled":true,
                  "eligibleActorClasses":["competitor"],"components":{"equity":{"enabled":true,"cap":100000},"risk":{"enabled":true,"cap":100000},
                  "conduct":{"enabled":true,"cap":100000},"marketInteraction":{"enabled":false,"cap":0},"npcDifficulty":{"enabled":false,"cap":0}},
                  "penalties":{"freeze":250000,"operationalPause":5000,"invalidIntentCap":100000},
                  "disqualification":{"freezeCount":1,"excludeFromLeaderboard":true},
                  "replayLock":{"from":"run_acceptance","until":"score_publication","requirePolicyEnvelopeHash":true}}}},
              "candidates":[{"evaluationId":"eval-route-1","priority":10}]
            }""".trimIndent(),
            principal
        )
        val removed = gateway.handleInternal(
            "POST", "/internal/admin/arena/roster-removals", null,
            """{"removalId":"removal-route-1","windowId":"weekly-2026-07-25","botId":"bot-route","versionId":"v1","reasonCode":"availability","detail":"runner unavailable"}""",
            principal
        )
        val fetched = gateway.handleInternal(
            "GET", "/internal/admin/arena/rosters", "windowId=weekly-2026-07-25", "", principal
        )

        assertEquals(200, scheduled?.status)
        assertEquals(200, evaluated?.status)
        assertContains(evaluated!!.body, "eligible_for_roster")
        assertEquals(400, incompleteFlags?.status)
        assertContains(incompleteFlags!!.body, "must be true or false")
        assertEquals(200, previewed?.status)
        assertContains(previewed!!.body, "included")
        assertEquals(200, locked?.status)
        assertContains(locked!!.body, "sha256:")
        assertEquals(200, removed?.status)
        assertContains(removed!!.body, "availability")
        assertEquals(200, fetched?.status)
        assertContains(fetched!!.body, "eval-route-1")
        assertContains(fetched.body, "effectiveEntries")
        assertContains(fetched.body, "removal-route-1")
    }

    private fun trustedAdmissionIdentities(
        participantTrusted: Boolean = true,
        reviewerTrusted: Boolean = true
    ): AdminIdentityService {
        val identities = AdminIdentityService(InMemoryAdminIdentityStore())
        val reviewer = identities.ensureGitHubUser(GitHubUserIdentity(999, "reviewer"))
        if (reviewerTrusted) {
            identities.updateTrustState("bootstrap", reviewer.reefUserId, AdminTrustState.Trusted)
            identities.assignRole("bootstrap", reviewer.reefUserId, AdminIdentityService.RoleOperator)
        }
        val participant = identities.ensureGitHubUser(GitHubUserIdentity(123, "octo"))
        if (participantTrusted) {
            identities.updateTrustState("bootstrap", participant.reefUserId, AdminTrustState.Trusted)
        }
        return identities
    }

    private fun openBaoProvisionBody(
        githubOidcToken: String,
        submitterIdentity: String,
        botId: String,
        flow: String,
        repository: String? = null,
        pullRequestNumber: Long? = null,
        headSha: String? = null
    ): String {
        val submission = if (repository != null && pullRequestNumber != null && headSha != null) {
            ",\"repository\":\"$repository\",\"pullRequestNumber\":$pullRequestNumber,\"headSha\":\"$headSha\""
        } else {
            ""
        }
        return """{"githubOidcToken":"$githubOidcToken","submitterIdentity":"$submitterIdentity","botId":"$botId","flow":"$flow"$submission}"""
    }

    private fun githubOidcToken(actor: String, repository: String = "reef/reef"): String {
        fun encode(raw: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())

        val header = encode("""{"alg":"none","typ":"JWT"}""")
        val payload = encode("""{"actor":"$actor","repository":"$repository","aud":"reef-bot-submission-ci"}""")
        return "$header.$payload.signature"
    }
}

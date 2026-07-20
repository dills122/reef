package com.reef.arena.controlplane.api

import com.reef.arena.controlplane.application.ArenaAdminApplicationService
import com.reef.arena.controlplane.arena.ArenaBot
import com.reef.arena.controlplane.arena.ArenaBotMetadata
import com.reef.arena.controlplane.arena.ArenaBotVersionStatus
import com.reef.arena.controlplane.arena.ArenaControlPlaneService
import com.reef.arena.controlplane.arena.ArenaQualificationStatus
import com.reef.arena.controlplane.arena.ArenaRuntimeConfigDescriptor
import com.reef.arena.controlplane.arena.ArenaRuntimeConfigProvider
import com.reef.arena.controlplane.arena.InMemoryArenaBotEntitlementStore
import com.reef.arena.controlplane.arena.InMemoryArenaBotRegistryStore
import com.reef.arena.controlplane.arena.InMemoryArenaSubmissionAdmissionStore
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
        val now = Instant.parse("2026-07-18T00:00:00Z")
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
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(registry, now = { now }),
            adminIdentityService = null,
            analyticsRunExportService = null
        )
        val principal = AdminRequestPrincipal("admin-cli", "gateway-test", now.toString())

        val registered = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/runs",
            null,
            """{"runId":"run-1","modeId":"hosted-sim","scenarioId":"scenario-1","seed":42,"policyVersion":"policy-v1","botVersions":[{"botId":"bot-1","versionId":"v1"}]}""",
            principal
        )
        val running = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/runs/status",
            null,
            """{"runId":"run-1","status":"running"}""",
            principal
        )
        val completed = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/runs/status",
            null,
            """{"runId":"run-1","status":"completed"}""",
            principal
        )
        val result = gateway.handleInternal(
            "POST",
            "/internal/admin/arena/run-bot-results",
            null,
            """{"runId":"run-1","botId":"bot-1","versionId":"v1","scoringPolicyVersion":"score-v2","finalEquity":1030000,"realizedPnl":30000,"maxDrawdown":900,"actionsProposed":13,"orderActionsProposed":9,"dataCalls":21,"signalsGenerated":5,"disqualified":false}""",
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
        assertEquals(400, submitterMismatch?.status, submitterMismatch?.body)
        assertContains(submitterMismatch!!.body, "submitterIdentity must match GitHub OIDC actor")
        assertEquals(403, wrongOwner?.status, wrongOwner?.body)
        assertContains(wrongOwner!!.body, "not authorized for OpenBao bot secret slice")
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
        flow: String
    ): String =
        """{"githubOidcToken":"$githubOidcToken","submitterIdentity":"$submitterIdentity","botId":"$botId","flow":"$flow"}"""

    private fun githubOidcToken(actor: String): String {
        fun encode(raw: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())

        val header = encode("""{"alg":"none","typ":"JWT"}""")
        val payload = encode("""{"actor":"$actor","repository":"reef/reef","aud":"reef-bot-submission-ci"}""")
        return "$header.$payload.signature"
    }
}

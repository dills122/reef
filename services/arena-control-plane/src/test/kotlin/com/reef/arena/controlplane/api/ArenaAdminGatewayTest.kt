package com.reef.arena.controlplane.api

import com.reef.arena.controlplane.application.ArenaAdminApplicationService
import com.reef.arena.controlplane.arena.ArenaBot
import com.reef.arena.controlplane.arena.ArenaBotMetadata
import com.reef.arena.controlplane.arena.InMemoryArenaBotEntitlementStore
import com.reef.arena.controlplane.arena.InMemoryArenaBotRegistryStore
import com.reef.arena.controlplane.arena.InMemoryArenaSubmissionAdmissionStore
import com.reef.platform.api.AdminRequestPrincipal
import com.reef.platform.api.OptionalProductAdminRoute
import com.reef.platform.api.OptionalProductRouteExtension
import com.reef.platform.api.PlatformHotPathResponse
import com.reef.platform.api.validateOptionalProductRouteExtensions
import com.reef.platform.application.admin.AdminServiceTokenFamily
import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.InMemoryAdminIdentityStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArenaAdminGatewayTest {
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
        val gateway = ArenaAdminGateway(
            arenaAdminService = ArenaAdminApplicationService(arenaRegistryStore = InMemoryArenaBotRegistryStore()),
            adminIdentityService = null,
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
}

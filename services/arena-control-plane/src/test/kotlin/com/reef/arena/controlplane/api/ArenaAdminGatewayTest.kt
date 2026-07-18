package com.reef.arena.controlplane.api

import com.reef.arena.controlplane.application.ArenaAdminApplicationService
import com.reef.arena.controlplane.arena.ArenaBot
import com.reef.arena.controlplane.arena.ArenaBotMetadata
import com.reef.arena.controlplane.arena.InMemoryArenaBotEntitlementStore
import com.reef.arena.controlplane.arena.InMemoryArenaBotRegistryStore
import com.reef.platform.api.AdminRequestPrincipal
import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.InMemoryAdminIdentityStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArenaAdminGatewayTest {
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

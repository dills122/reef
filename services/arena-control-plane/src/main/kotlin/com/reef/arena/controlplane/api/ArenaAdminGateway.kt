package com.reef.arena.controlplane.api

import com.reef.platform.api.*

import com.reef.platform.application.admin.AdminActor
import com.reef.arena.controlplane.application.ArenaAdminApplicationService
import com.reef.arena.controlplane.arena.ArenaBotEntitlementStore
import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.AdminServiceTokenFamily
import com.reef.arena.controlplane.application.ArenaBotRegistrationCommand
import com.reef.arena.controlplane.application.ArenaBotVersionDecisionCommand
import com.reef.arena.controlplane.application.ArenaBotVersionRegistrationCommand
import com.reef.arena.controlplane.application.ArenaRunBotResultIngestionCommand
import com.reef.arena.controlplane.application.ArenaRunEnforcementEventIngestionCommand
import com.reef.arena.controlplane.application.ArenaRunRegistrationCommand
import com.reef.arena.controlplane.application.ArenaRunStatusCommand
import com.reef.platform.application.admin.GitHubUserIdentity
import com.reef.platform.application.analytics.BotRunPerformanceSummaryRecord
import com.reef.platform.application.analytics.SimulationRunExportCommand
import com.reef.platform.application.analytics.SimulationRunExportRecord
import com.reef.platform.application.analytics.SimulationRunExportService
import com.reef.arena.controlplane.arena.ArenaBot
import com.reef.arena.controlplane.arena.ArenaBotOwnership
import com.reef.arena.controlplane.arena.ArenaBotOwnershipState
import com.reef.arena.controlplane.arena.ArenaBotVersion
import com.reef.arena.controlplane.arena.ArenaBotVersionStatus
import com.reef.arena.controlplane.arena.ArenaLeaderboardEntry
import com.reef.arena.controlplane.arena.ArenaOperatorDecision
import com.reef.arena.controlplane.arena.ArenaQualificationReport
import com.reef.arena.controlplane.arena.ArenaRunBotResult
import com.reef.arena.controlplane.arena.ArenaRunBotVersionRef
import com.reef.arena.controlplane.arena.ArenaRunEnforcementEvent
import com.reef.arena.controlplane.arena.ArenaRunRecord
import com.reef.arena.controlplane.arena.ArenaRunStatus
import com.reef.arena.controlplane.arena.ArenaRuntimeConfigDescriptor
import com.reef.arena.controlplane.arena.BotConfigSecretService
import com.reef.arena.controlplane.arena.LocalDevBotConfigService
import com.reef.arena.controlplane.arena.OpenBaoBotConfigService
import com.reef.arena.controlplane.arena.OpenBaoBotConfigServiceConfig
import com.reef.arena.controlplane.arena.OpenBaoClientException
import com.reef.arena.controlplane.arena.OpenBaoProvisioningConfig
import com.reef.arena.controlplane.arena.OpenBaoProvisioningService
import com.reef.platform.infrastructure.config.RuntimeEnv

private val adminBotConfigPrivilegedRoles = setOf("operator", "secret-admin", "platform-admin")

/**
 * Owns arena bot/version registration, run/leaderboard admin CRUD, per-bot OpenBao
 * secret config, and analytics run-export ingestion+query. These share one class
 * because they're all admin/data internal routes over the same ArenaAdminApplicationService,
 * not because they're one bounded context — see docs/steering/kotlin.md.
 */
internal class ArenaAdminGateway(
    private val arenaAdminService: ArenaAdminApplicationService?,
    private val adminIdentityService: AdminIdentityService?,
    private val analyticsRunExportService: SimulationRunExportService?,
    private val arenaBotEntitlementStore: ArenaBotEntitlementStore? = null
) : OptionalProductRouteExtension {
    private val requestPrincipal = ThreadLocal<AdminRequestPrincipal?>()
    override val internalPaths = listOf(
        "/internal/admin/arena/bots", "/internal/admin/arena/my/bots", "/internal/admin/arena/bot-versions",
        "/internal/admin/arena/bot-versions/transition", "/internal/admin/arena/qualification-reports",
        "/internal/admin/arena/operator-decisions", "/internal/admin/arena/runtime-config-descriptors",
        "/internal/admin/arena/runs", "/internal/admin/arena/runs/status", "/internal/admin/arena/run-bot-results",
        "/internal/admin/arena/run-enforcement-events", "/internal/admin/arena/leaderboard",
        "/internal/admin/arena/bots/openbao-provision", "/internal/admin/arena/bots/ownership",
        "/internal/admin/arena/bots/config", "/internal/admin/analytics/run-exports",
        "/internal/admin/analytics/run-bot-summaries"
    )
    override val publicReadPaths = listOf("/api/v1/arena/leaderboard")
    override val adminRoutes = listOf(
        adminRoute("/admin/v1/arena/bots", setOf("GET", "POST"), "/internal/admin/arena/bots", "arena", arenaTokens, operatorRoles),
        adminRoute("/admin/v1/arena/my/bots", setOf("GET"), "/internal/admin/arena/my/bots", "arena", emptySet()),
        adminRoute("/admin/v1/arena/bots/openbao-provision", setOf("POST"), "/internal/admin/arena/bots/openbao-provision", "arena", arenaTokens, secretRoles),
        adminRoute("/admin/v1/arena/bots/ownership", setOf("POST"), "/internal/admin/arena/bots/ownership", "arena", arenaTokens, operatorRoles),
        adminRoute("/admin/v1/arena/bots/config", setOf("GET", "PUT", "DELETE"), "/internal/admin/arena/bots/config", "admin", adminTokens),
        adminRoute("/admin/v1/arena/bot-versions", setOf("GET", "POST"), "/internal/admin/arena/bot-versions", "arena", arenaTokens, operatorRoles),
        adminRoute("/admin/v1/arena/bot-versions/transition", setOf("POST"), "/internal/admin/arena/bot-versions/transition", "arena", arenaTokens, operatorRoles),
        adminRoute("/admin/v1/arena/runs", setOf("GET", "POST"), "/internal/admin/arena/runs", "arena", arenaTokens, operatorRoles),
        adminRoute("/admin/v1/arena/runs/status", setOf("POST"), "/internal/admin/arena/runs/status", "arena", arenaTokens, operatorRoles),
        adminRoute("/admin/v1/arena/run-bot-results", setOf("GET", "POST"), "/internal/admin/arena/run-bot-results", "arena", arenaTokens, operatorRoles),
        adminRoute("/admin/v1/arena/run-enforcement-events", setOf("GET", "POST"), "/internal/admin/arena/run-enforcement-events", "arena", arenaTokens, operatorRoles),
        adminRoute("/admin/v1/arena/leaderboard", setOf("GET"), "/internal/admin/arena/leaderboard", "arena", arenaTokens, operatorRoles)
    )

    override fun handleInternal(
        method: String,
        path: String,
        query: String?,
        body: String,
        principal: AdminRequestPrincipal
    ): PlatformHotPathResponse? {
        requestPrincipal.set(principal)
        return try {
            when (path) {
        "/internal/admin/arena/bots" -> getOrPost(method, { arenaBotResponse(query) }, { registerArenaBotResponse(body) })
        "/internal/admin/arena/my/bots" -> getResponseOnly(method) { arenaMyBotsResponse(query) }
        "/internal/admin/arena/bot-versions" -> getOrPost(method, { arenaBotVersionResponse(query) }, { registerArenaBotVersionResponse(body) })
        "/internal/admin/arena/bot-versions/transition" -> postOnly(method) { transitionArenaBotVersionResponse(body) }
        "/internal/admin/arena/qualification-reports" -> getResponseOnly(method) { arenaQualificationReportsResponse(query) }
        "/internal/admin/arena/operator-decisions" -> getResponseOnly(method) { arenaOperatorDecisionsResponse(query) }
        "/internal/admin/arena/runtime-config-descriptors" -> getResponseOnly(method) { arenaRuntimeConfigDescriptorsResponse(query) }
        "/internal/admin/arena/runs" -> getOrPost(method, { arenaRunResponse(query) }, { registerArenaRunResponse(body) })
        "/internal/admin/arena/runs/status" -> postOnly(method) { updateArenaRunStatusResponse(body) }
        "/internal/admin/arena/run-bot-results" -> getOrPost(method, { arenaRunBotResultsResponse(query) }, { recordArenaRunBotResultResponse(body) })
        "/internal/admin/arena/run-enforcement-events" -> getOrPost(method, { arenaRunEnforcementEventsResponse(query) }, { recordArenaRunEnforcementEventResponse(body) })
        "/internal/admin/arena/leaderboard" -> getResponseOnly(method) { arenaLeaderboardResponse(query) }
        "/internal/admin/arena/bots/openbao-provision" -> postOnly(method) { arenaBotOpenBaoProvisionResponse(body) }
        "/internal/admin/arena/bots/ownership" -> postOnly(method) { assignArenaBotOwnershipResponse(body) }
        "/internal/admin/arena/bots/config" -> arenaBotOpenBaoConfigResponse(method, query, body)
        "/internal/admin/analytics/run-exports" -> getOrPost(method, { analyticsRunExportsResponse(query) }, { recordAnalyticsRunExportResponse(body) })
        "/internal/admin/analytics/run-bot-summaries" -> getResponseOnly(method) { analyticsRunBotSummariesResponse(query) }
                else -> null
            }
        } finally {
            requestPrincipal.remove()
        }
    }

    override fun handlePublicRead(path: String, query: String?): PlatformHotPathResponse? = when (path) {
        "/api/v1/arena/leaderboard" -> arenaLeaderboardPublicResponse(query)
        else -> null
    }

    private fun currentPrincipal(): AdminRequestPrincipal =
        requestPrincipal.get() ?: error("Arena gateway request principal is unavailable")

    private fun adminRoute(
        externalPath: String,
        methods: Set<String>,
        internalPath: String,
        _fallbackTokenFamily: String,
        serviceTokenFamilies: Set<AdminServiceTokenFamily>,
        sessionRoles: Set<String> = emptySet()
    ) = OptionalProductAdminRoute(
        externalPath,
        methods,
        internalPath,
        "ARENA_ADMIN_API_TOKEN",
        "ARENA_ADMIN_API_ACTOR_ID",
        serviceTokenFamilies,
        sessionRoles
    )

    private companion object {
        val arenaTokens = setOf(AdminServiceTokenFamily.Ci, AdminServiceTokenFamily.Admin)
        val adminTokens = setOf(AdminServiceTokenFamily.Admin)
        val operatorRoles = setOf(AdminIdentityService.RoleOperator, AdminIdentityService.RolePlatformAdmin)
        val secretRoles = setOf(AdminIdentityService.RoleSecretAdmin, AdminIdentityService.RolePlatformAdmin)
    }

    private fun getResponseOnly(method: String, response: () -> PlatformHotPathResponse): PlatformHotPathResponse =
        if (method == "GET") response() else methodNotAllowedResponse()

    private fun postOnly(method: String, response: () -> PlatformHotPathResponse): PlatformHotPathResponse =
        if (method == "POST") response() else methodNotAllowedResponse()

    private fun getOrPost(method: String, getResponse: () -> PlatformHotPathResponse, postResponse: () -> PlatformHotPathResponse): PlatformHotPathResponse = when (method) {
        "GET" -> getResponse()
        "POST" -> postResponse()
        else -> methodNotAllowedResponse()
    }

    fun transitionArenaBotVersionResponse(body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        val botId = json.string("botId")
        val versionId = json.string("versionId")
        val status = normalizeArenaBotVersionStatus(json.string("status"))
            ?: return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid arena bot version status"))
        val reason = json.string("reason").ifBlank { "operator transition" }
        if (botId.isBlank() || versionId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId and versionId are required"))
        }
        val actor = arenaAdminActor()
        return try {
            val updated = service.transitionArenaBotVersion(
                actor,
                ArenaBotVersionDecisionCommand(
                    botId = botId,
                    versionId = versionId,
                    status = status,
                    reason = reason
                )
            )
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "botId" to updated.botId,
                    "versionId" to updated.versionId,
                    "botVersionStatus" to updated.status.name
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena transition")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena transition failed")))
        }
    }

    fun registerArenaBotResponse(body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        val actor = arenaAdminActor(json)
        return try {
            val bot = service.registerArenaBot(
                actor,
                ArenaBotRegistrationCommand(
                    botId = json.string("botId"),
                    fileName = json.string("fileName"),
                    name = json.string("name"),
                    publisher = json.string("publisher"),
                    email = json.string("email"),
                    description = json.string("description"),
                    version = json.string("version")
                )
            )
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "botId" to bot.botId,
                    "fileName" to bot.fileName
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena bot")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena bot registration failed")))
        }
    }

    fun registerArenaBotVersionResponse(body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        val actor = arenaAdminActor(json)
        return try {
            val version = service.registerArenaBotVersion(
                actor,
                ArenaBotVersionRegistrationCommand(
                    botId = json.string("botId"),
                    versionId = json.string("versionId"),
                    sourceHash = json.string("sourceHash"),
                    artifactHash = json.string("artifactHash"),
                    sdkVersion = json.string("sdkVersion"),
                    apiVersion = json.string("apiVersion"),
                    dependencyManifestHash = json.string("dependencyManifestHash")
                )
            )
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "botId" to version.botId,
                    "versionId" to version.versionId,
                    "botVersionStatus" to version.status.name
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena bot version")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena bot version registration failed")))
        }
    }

    fun arenaBotOpenBaoProvisionResponse(body: String): PlatformHotPathResponse {
        if (arenaAdminService == null) {
            return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        }
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        val githubOidcToken = json.string("githubOidcToken")
        val submitterIdentity = json.string("submitterIdentity")
        val botId = json.string("botId")
        val flow = json.string("flow")
        if (githubOidcToken.isBlank() || submitterIdentity.isBlank() || botId.isBlank()) {
            return PlatformHotPathResponse(
                400,
                JsonCodec.writeObject("error" to "githubOidcToken, submitterIdentity, and botId are required")
            )
        }
        if (flow !in setOf("add", "update", "remove")) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "flow must be add, update, or remove"))
        }
        val claims = try {
            OpenBaoProvisioningService.requireSubmitterIdentity(githubOidcToken, submitterIdentity)
        } catch (ex: IllegalArgumentException) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid GitHub OIDC token")))
        }
        openBaoProvisioningAuthorizationError(flow, claims.actor, botId)?.let { return it }
        val baoAddr = RuntimeEnv.string("BAO_ADDR", "")
            .ifBlank { return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "BAO_ADDR is not configured")) }
        val service = OpenBaoProvisioningService(OpenBaoProvisioningConfig(baoAddr = baoAddr))
        return try {
            when (flow) {
                "remove" -> service.revokeBotSecretSlice(githubOidcToken, submitterIdentity, botId)
                "update" -> Unit // existing slice is reused; no new provisioning
                else -> service.provisionBotSecretSlice(githubOidcToken, submitterIdentity, botId, emptyMap())
            }
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "botId" to botId, "flow" to flow)
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid submitterIdentity or botId")))
        } catch (ex: OpenBaoClientException) {
            PlatformHotPathResponse(502, JsonCodec.writeObject("error" to "OpenBao provisioning failed"))
        }
    }

    fun assignArenaBotOwnershipResponse(body: String): PlatformHotPathResponse {
        val identity = adminIdentityService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "admin identity service unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        val botId = json.string("botId")
        val githubLogin = json.string("githubLogin")
        val displayName = json.string("displayName")
        if (botId.isBlank() || githubLogin.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId and githubLogin are required"))
        }
        return try {
            val githubUserId = json.requiredLong("githubUserId")
            val actor = currentPrincipal().actorId
            if (actor.startsWith("user-gh-")) {
                val roles = identity.rolesForUser(actor).map { it.roleId }.toSet()
                if (roles.none { it in adminBotConfigPrivilegedRoles }) {
                    return PlatformHotPathResponse(403, JsonCodec.writeObject("error" to "not authorized for bot ownership"))
                }
            }
            val user = identity.ensureGitHubUser(
                GitHubUserIdentity(
                    githubUserId = githubUserId,
                    githubLogin = githubLogin,
                    displayName = displayName
                ),
                actorId = actor
            )
            val ownership = (arenaBotEntitlementStore
                ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena entitlement store unavailable")))
                .saveBotOwnership(ArenaBotOwnership(user.reefUserId, botId, ArenaBotOwnershipState.Owner, actor, java.time.Instant.now()))
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "botId" to ownership.botId,
                    "reefUserId" to ownership.reefUserId,
                    "githubLogin" to user.githubLogin,
                    "ownershipState" to ownership.ownershipState.dbValue
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid bot ownership request")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena bot ownership assignment failed")))
        }
    }

    fun arenaBotOpenBaoConfigResponse(method: String, query: String?, body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        return when (method) {
            "GET" -> arenaBotOpenBaoConfigStatusResponse(service, query)
            "PUT" -> arenaBotOpenBaoConfigReplaceResponse(service, body)
            "DELETE" -> arenaBotOpenBaoConfigDeleteResponse(service, query)
            else -> methodNotAllowedResponse()
        }
    }

    private fun arenaBotOpenBaoConfigStatusResponse(
        service: ArenaAdminApplicationService,
        query: String?
    ): PlatformHotPathResponse {
        val botId = queryValue(query, "botId")
        if (botId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId is required"))
        }
        return try {
            val bot = service.arenaBotForOwnerScopedConfig(botId)
                ?: return PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "arena bot not found"))
            val ownerIdentity = openBaoOwnerIdentity(bot)
                ?: return PlatformHotPathResponse(409, JsonCodec.writeObject("error" to "arena bot has no linked owner"))
            if (!canManageBotOpenBaoConfig(bot.botId, write = false)) {
                return PlatformHotPathResponse(403, JsonCodec.writeObject("error" to "not authorized for bot config"))
            }
            val snapshot = openBaoBotConfigService().status(ownerIdentity, bot.botId)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "botId" to snapshot.botId,
                    "ownerIdentity" to snapshot.ownerIdentity,
                    "secretPath" to snapshot.secretPath,
                    "hasConfig" to snapshot.hasConfig,
                    "config" to snapshot.config,
                    "keys" to snapshot.keys,
                    "updatedAt" to snapshot.updatedAt,
                    "updatedBy" to snapshot.updatedBy,
                    "version" to snapshot.version
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid bot config request")))
        } catch (ex: OpenBaoClientException) {
            PlatformHotPathResponse(502, JsonCodec.writeObject("error" to "OpenBao bot config lookup failed"))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena bot config lookup failed")))
        }
    }

    private fun arenaBotOpenBaoConfigReplaceResponse(
        service: ArenaAdminApplicationService,
        body: String
    ): PlatformHotPathResponse {
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        val botId = json.string("botId")
        val configJson = json.raw("config")
        if (botId.isBlank() || configJson.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId and config are required"))
        }
        return try {
            val bot = service.arenaBotForOwnerScopedConfig(botId)
                ?: return PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "arena bot not found"))
            val ownerIdentity = openBaoOwnerIdentity(bot)
                ?: return PlatformHotPathResponse(409, JsonCodec.writeObject("error" to "arena bot has no linked owner"))
            if (!canManageBotOpenBaoConfig(bot.botId, write = true)) {
                return PlatformHotPathResponse(403, JsonCodec.writeObject("error" to "not authorized for bot config"))
            }
            val actor = currentPrincipal().actorId
            val result = openBaoBotConfigService().replaceConfig(ownerIdentity, bot.botId, configJson, actor)
            recordAdminControlPlaneAudit(
                actor,
                "AdminArenaBotConfigReplaced",
                "arena-bot",
                result.botId,
                "secretPath=${result.secretPath},keyCount=${result.keys.size}"
            )
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "botId" to result.botId,
                    "ownerIdentity" to result.ownerIdentity,
                    "secretPath" to result.secretPath,
                    "hasConfig" to true,
                    "config" to result.config,
                    "keys" to result.keys,
                    "updatedAt" to result.updatedAt
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid bot config")))
        } catch (ex: OpenBaoClientException) {
            PlatformHotPathResponse(502, JsonCodec.writeObject("error" to "OpenBao bot config write failed"))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena bot config write failed")))
        }
    }

    private fun arenaBotOpenBaoConfigDeleteResponse(
        service: ArenaAdminApplicationService,
        query: String?
    ): PlatformHotPathResponse {
        val botId = queryValue(query, "botId")
        if (botId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId is required"))
        }
        return try {
            val bot = service.arenaBotForOwnerScopedConfig(botId)
                ?: return PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "arena bot not found"))
            val ownerIdentity = openBaoOwnerIdentity(bot)
                ?: return PlatformHotPathResponse(409, JsonCodec.writeObject("error" to "arena bot has no linked owner"))
            if (!canManageBotOpenBaoConfig(bot.botId, write = true)) {
                return PlatformHotPathResponse(403, JsonCodec.writeObject("error" to "not authorized for bot config"))
            }
            openBaoBotConfigService().deleteConfig(ownerIdentity, bot.botId)
            val actor = currentPrincipal().actorId
            val secretPath = "secret/bots/$ownerIdentity/${bot.botId}"
            recordAdminControlPlaneAudit(
                actor,
                "AdminArenaBotConfigDeleted",
                "arena-bot",
                bot.botId,
                "secretPath=$secretPath"
            )
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "botId" to bot.botId,
                    "ownerIdentity" to ownerIdentity,
                    "secretPath" to secretPath,
                    "hasConfig" to false,
                    "keys" to emptyList<String>()
                )
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid bot config request")))
        } catch (ex: OpenBaoClientException) {
            PlatformHotPathResponse(502, JsonCodec.writeObject("error" to "OpenBao bot config delete failed"))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena bot config delete failed")))
        }
    }

    fun arenaBotResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val botId = queryValue(query, "botId")
        if (botId.isBlank()) {
            return arenaBotsListResponse(service, query)
        }
        return try {
            val bot = service.arenaBot(arenaAdminActor(query), botId)
                ?: return PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "arena bot not found"))
            PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "ok", "bot" to arenaBotJson(bot)))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena bot lookup failed")))
        }
    }

    fun arenaMyBotsResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val identity = adminIdentityService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "admin identity service unavailable"))
        val actorId = currentPrincipal().actorId
        if (!actorId.startsWith("user-gh-")) {
            return PlatformHotPathResponse(403, JsonCodec.writeObject("error" to "GitHub user session is required"))
        }
        val user = identity.user(actorId)
            ?: return PlatformHotPathResponse(403, JsonCodec.writeObject("error" to "admin identity is required"))
        if (user.trustState.dbValue == "banned") {
            return PlatformHotPathResponse(403, JsonCodec.writeObject("error" to "admin user is banned"))
        }
        val limit = queryValue(query, "limit").toIntOrNull() ?: 50
        return try {
            val ownerships = (arenaBotEntitlementStore
                ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena entitlement store unavailable")))
                .botOwnershipsForUser(actorId)
                .filter { it.ownershipState != ArenaBotOwnershipState.Revoked }
                .sortedByDescending { it.assignedAt }
                .take(limit.coerceIn(1, 500))
            val bots = service.arenaBotsById(ownerships.map { it.botId }, limit)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject(
                    "status" to "ok",
                    "reefUserId" to actorId,
                    "bots" to bots.map { arenaBotJson(it) }
                )
            )
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "owned arena bots query failed")))
        }
    }

    // botId omitted -> roster listing, most-recently-registered first.
    private fun arenaBotsListResponse(service: ArenaAdminApplicationService, query: String?): PlatformHotPathResponse {
        val limit = queryValue(query, "limit").toIntOrNull() ?: 50
        return try {
            val bots = service.arenaBots(arenaAdminActor(query), limit)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "bots" to bots.map { arenaBotJson(it) })
            )
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena bots query failed")))
        }
    }

    fun arenaBotVersionResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val botId = queryValue(query, "botId")
        val versionId = queryValue(query, "versionId")
        if (botId.isBlank() || versionId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId and versionId are required"))
        }
        return try {
            val version = service.arenaBotVersion(arenaAdminActor(query), botId, versionId)
                ?: return PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "arena bot version not found"))
            PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "ok", "version" to arenaBotVersionJson(version)))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena bot version lookup failed")))
        }
    }

    fun arenaQualificationReportsResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val botId = queryValue(query, "botId")
        val versionId = queryValue(query, "versionId")
        if (botId.isBlank() || versionId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId and versionId are required"))
        }
        return try {
            val reports = service.arenaQualificationReports(arenaAdminActor(query), botId, versionId)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "reports" to reports.map { arenaQualificationReportJson(it) })
            )
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena qualification reports query failed")))
        }
    }

    fun arenaOperatorDecisionsResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val botId = queryValue(query, "botId")
        val versionId = queryValue(query, "versionId")
        if (botId.isBlank() || versionId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId and versionId are required"))
        }
        return try {
            val decisions = service.arenaOperatorDecisions(arenaAdminActor(query), botId, versionId)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "decisions" to decisions.map { arenaOperatorDecisionJson(it) })
            )
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena operator decisions query failed")))
        }
    }

    fun arenaRuntimeConfigDescriptorsResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val botId = queryValue(query, "botId")
        val versionId = queryValue(query, "versionId")
        if (botId.isBlank() || versionId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "botId and versionId are required"))
        }
        return try {
            val descriptors = service.arenaRuntimeConfigDescriptors(arenaAdminActor(query), botId, versionId)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "descriptors" to descriptors.map { arenaRuntimeConfigDescriptorJson(it) })
            )
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena runtime config descriptors query failed")))
        }
    }

    fun arenaRunResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val runId = queryValue(query, "runId")
        if (runId.isBlank()) {
            return arenaRunsListResponse(service, query)
        }
        return try {
            val run = service.arenaRun(arenaAdminActor(query), runId)
                ?: return PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "arena run not found"))
            PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "ok", "run" to arenaRunJson(run)))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena run lookup failed")))
        }
    }

    // runId omitted -> recent run listing, most-recently-created first.
    private fun arenaRunsListResponse(service: ArenaAdminApplicationService, query: String?): PlatformHotPathResponse {
        val limit = queryValue(query, "limit").toIntOrNull() ?: 50
        return try {
            val runs = service.arenaRuns(arenaAdminActor(query), limit)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "runs" to runs.map { arenaRunJson(it) })
            )
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena runs query failed")))
        }
    }

    fun registerArenaRunResponse(body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        return try {
            val run = service.registerArenaRun(
                arenaAdminActor(json),
                ArenaRunRegistrationCommand(
                    runId = json.string("runId"),
                    modeId = json.string("modeId"),
                    scenarioId = json.string("scenarioId"),
                    seed = json.requiredLong("seed"),
                    policyVersion = json.string("policyVersion"),
                    botVersions = json.objectDocuments("botVersions").map { ref ->
                        ArenaRunBotVersionRef(ref.string("botId"), ref.string("versionId"))
                    }
                )
            )
            PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "ok", "run" to arenaRunJson(run)))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena run")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena run registration failed")))
        }
    }

    fun updateArenaRunStatusResponse(body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        val runId = json.string("runId")
        val status = normalizeArenaRunStatus(json.string("status"))
            ?: return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "invalid arena run status"))
        if (runId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "runId is required"))
        }
        return try {
            val run = service.updateArenaRunStatus(arenaAdminActor(json), ArenaRunStatusCommand(runId, status))
            PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "ok", "run" to arenaRunJson(run)))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena run transition")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena run transition failed")))
        }
    }

    fun arenaRunBotResultsResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val runId = queryValue(query, "runId")
        if (runId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "runId is required"))
        }
        return try {
            val results = service.arenaRunBotResults(arenaAdminActor(query), runId)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "results" to results.map { arenaRunBotResultJson(it) })
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena run bot results query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena run bot results query failed")))
        }
    }

    fun recordArenaRunBotResultResponse(body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        return try {
            val command = ArenaRunBotResultIngestionCommand(
                runId = json.string("runId"),
                botId = json.string("botId"),
                versionId = json.string("versionId"),
                scoringPolicyVersion = json.string("scoringPolicyVersion"),
                finalEquity = json.requiredLong("finalEquity"),
                realizedPnl = json.requiredLong("realizedPnl"),
                maxDrawdown = json.requiredLong("maxDrawdown"),
                actionsProposed = json.requiredInt("actionsProposed"),
                orderActionsProposed = json.requiredInt("orderActionsProposed"),
                dataCalls = json.requiredInt("dataCalls"),
                signalsGenerated = json.requiredInt("signalsGenerated"),
                disqualified = json.boolean("disqualified"),
                scoreEligible = json.booleanOrDefault("scoreEligible", true),
                publicLeaderboard = json.booleanOrDefault("publicLeaderboard", true)
            )
            val result = service.recordArenaRunBotResult(arenaAdminActor(json), command)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "result" to arenaRunBotResultJson(result))
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena run bot result")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena run bot result ingestion failed")))
        }
    }

    fun arenaRunEnforcementEventsResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val runId = queryValue(query, "runId")
        if (runId.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "runId is required"))
        }
        return try {
            val events = service.arenaRunEnforcementEvents(arenaAdminActor(query), runId)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "events" to events.map { arenaRunEnforcementEventJson(it) })
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena run enforcement query")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena run enforcement query failed")))
        }
    }

    fun recordArenaRunEnforcementEventResponse(body: String): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        return try {
            val command = ArenaRunEnforcementEventIngestionCommand(
                runId = json.string("runId"),
                botId = json.string("botId"),
                versionId = json.string("versionId"),
                decision = json.string("decision"),
                reasonCode = json.string("reasonCode"),
                reason = json.string("reason"),
                policyVersion = json.string("policyVersion"),
                countersJson = json.string("countersJson")
            )
            val event = service.recordArenaRunEnforcementEvent(arenaAdminActor(json), command)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "event" to arenaRunEnforcementEventJson(event))
            )
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid arena run enforcement event")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena run enforcement event ingestion failed")))
        }
    }

    fun arenaLeaderboardResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena admin service unavailable"))
        val modeId = queryValue(query, "modeId")
        val scoringPolicyVersion = queryValue(query, "scoringPolicyVersion")
        val limit = queryValue(query, "limit").toIntOrNull() ?: 50
        if (modeId.isBlank() || scoringPolicyVersion.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "modeId and scoringPolicyVersion are required"))
        }
        return try {
            val entries = service.arenaLeaderboard(arenaAdminActor(query), modeId, scoringPolicyVersion, limit)
            PlatformHotPathResponse(
                200,
                JsonCodec.writeObject("status" to "ok", "entries" to entries.map { arenaLeaderboardEntryJson(it) })
            )
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "arena leaderboard query failed")))
        }
    }

    // Public, unauthenticated leaderboard read (D-052) — venue-intake visibility class,
    // not admin/data. No AdminActor derivation: this must not require an admin session.
    fun arenaLeaderboardPublicResponse(query: String?): PlatformHotPathResponse {
        val service = arenaAdminService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "arena service unavailable"))
        val modeId = queryValue(query, "modeId")
        val scoringPolicyVersion = queryValue(query, "scoringPolicyVersion")
        val limit = boundedQueryLimit(queryValue(query, "limit"), defaultValue = 50)
        if (modeId.isBlank() || scoringPolicyVersion.isBlank()) {
            return PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "modeId and scoringPolicyVersion are required"))
        }
        val entries = service.arenaLeaderboardPublic(modeId, scoringPolicyVersion, limit)
        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject(
                "modeId" to modeId,
                "scoringPolicyVersion" to scoringPolicyVersion,
                "entries" to entries.map { arenaLeaderboardEntryJson(it) }
            )
        )
    }

    fun recordAnalyticsRunExportResponse(body: String): PlatformHotPathResponse {
        val service = analyticsRunExportService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "analytics run export service unavailable"))
        val json = parseGatewayJson(body) ?: return invalidJsonPayloadResponse()
        val counts = json.obj("counts")
        val latency = json.obj("latencyMs")
        val summaryJson = analyticsExportSummaryJson(json)
        return try {
            val record = service.ingest(
                SimulationRunExportCommand(
                    runId = json.string("runId"),
                    scenarioId = json.string("scenarioId"),
                    runKind = json.string("runKind"),
                    source = json.string("source"),
                    gitSha = json.string("gitSha"),
                    profile = json.string("profile"),
                    startedAt = instantOrNull(json.string("startedAt")),
                    completedAt = instantOrNull(json.string("completedAt")),
                    exportedAt = instantOrNull(json.string("exportedAt")),
                    status = json.string("status"),
                    attemptedCount = longValue(json, counts, "attemptedCount", "attempted"),
                    acceptedCount = longValue(json, counts, "acceptedCount", "accepted"),
                    completedCount = longValue(json, counts, "completedCount", "completed"),
                    materializedCount = longValue(json, counts, "materializedCount", "materialized"),
                    projectedCount = longValue(json, counts, "projectedCount", "projected"),
                    failedCount = longValue(json, counts, "failedCount", "failed"),
                    p50LatencyMs = doubleValue(json, latency, "p50LatencyMs", "p50"),
                    p95LatencyMs = doubleValue(json, latency, "p95LatencyMs", "p95"),
                    p99LatencyMs = doubleValue(json, latency, "p99LatencyMs", "p99"),
                    artifactManifestJson = normalizedRawJson(json.raw("artifactManifest").ifBlank { json.raw("artifacts") }, "[]"),
                    summaryJson = summaryJson
                )
            )
            PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "ok", "export" to analyticsRunExportJson(record)))
        } catch (ex: IllegalArgumentException) {
            PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid analytics run export")))
        } catch (ex: Exception) {
            PlatformHotPathResponse(409, JsonCodec.writeObject("error" to (ex.message ?: "analytics run export ingestion failed")))
        }
    }

    private fun analyticsExportSummaryJson(json: JsonDocument): String {
        val explicitSummary = json.raw("summary")
        if (explicitSummary.isNotBlank()) return normalizedRawJson(explicitSummary, "{}")
        if (!json.has("botResults") && !json.has("settlementScore") && !json.has("settlementScoreSummary")) {
            return "{}"
        }
        return JsonCodec.writeObject(
            "botResults" to JsonCodec.rawJsonOrText(json.raw("botResults").ifBlank { "[]" }),
            "settlementScore" to JsonCodec.rawJsonOrText(
                json.raw("settlementScore").ifBlank { json.raw("settlementScoreSummary").ifBlank { "{}" } }
            )
        )
    }

    fun analyticsRunExportsResponse(query: String?): PlatformHotPathResponse {
        val service = analyticsRunExportService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "analytics run export service unavailable"))
        val runId = queryValue(query, "runId")
        if (runId.isNotBlank()) {
            val record = service.find(runId)
                ?: return PlatformHotPathResponse(404, JsonCodec.writeObject("error" to "analytics run export not found"))
            return PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "ok", "export" to analyticsRunExportJson(record)))
        }
        val limit = queryValue(query, "limit").toIntOrNull() ?: 50
        val records = service.list(limit)
        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject(
                "status" to "ok",
                "exports" to records.map { analyticsRunExportJson(it) },
                "exportsCount" to records.size,
                "limit" to limit.coerceIn(1, 500)
            )
        )
    }

    fun analyticsRunBotSummariesResponse(query: String?): PlatformHotPathResponse {
        val service = analyticsRunExportService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "analytics run export service unavailable"))
        val runId = queryValue(query, "runId")
        val botId = queryValue(query, "botId")
        val limit = queryValue(query, "limit").toIntOrNull() ?: 50
        val records = service.listBotPerformanceSummaries(runId, botId, limit)
        return PlatformHotPathResponse(
            200,
            JsonCodec.writeObject(
                "status" to "ok",
                "summaries" to records.map { analyticsRunBotSummaryJson(it) },
                "summariesCount" to records.size,
                "limit" to limit.coerceIn(1, 500),
                "meta" to mapOf(
                    "sourceFacts" to "analytics.simulation_run_exports summary.botResults plus optional settlementScore participants",
                    "freshness" to "rebuilt during simulation-run export ingestion; idempotent upsert by runId and botId",
                    "authoritative" to false
                )
            )
        )
    }

    private fun analyticsRunExportJson(record: SimulationRunExportRecord): Map<String, Any?> {
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

    private fun analyticsRunBotSummaryJson(record: BotRunPerformanceSummaryRecord): Map<String, Any?> {
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

    private fun longValue(root: JsonDocument, nested: JsonDocument, rootKey: String, nestedKey: String): Long {
        return root.string(rootKey).ifBlank { nested.string(nestedKey) }.toLongOrNull() ?: 0L
    }

    private fun doubleValue(root: JsonDocument, nested: JsonDocument, rootKey: String, nestedKey: String): Double? {
        return root.string(rootKey).ifBlank { nested.string(nestedKey) }.toDoubleOrNull()
    }

    private fun instantOrNull(value: String): java.time.Instant? {
        if (value.isBlank()) return null
        return java.time.Instant.parse(value)
    }

    private fun normalizedRawJson(raw: String, fallback: String): String {
        return if (raw.isBlank()) fallback else JsonCodec.writeNode(JsonCodec.rawJsonOrText(raw))
    }

    private fun arenaAdminActor(json: JsonDocument): AdminActor {
        return arenaAdminActor()
    }

    private fun arenaAdminActor(): AdminActor {
        val principal = currentPrincipal()
        return AdminActor(
            actorId = principal.actorId,
            correlationId = principal.correlationId,
            occurredAt = principal.occurredAt
        )
    }

    private fun arenaAdminActor(query: String?): AdminActor {
        return arenaAdminActor()
    }

    private fun openBaoBotConfigService(): BotConfigSecretService {
        val baoAddr = RuntimeEnv.string("BAO_ADDR", "")
        if (baoAddr.isBlank()) {
            return localDevBotConfigService()
        }
        val roleId = RuntimeEnv.string("BAO_BOT_CONFIG_ROLE_ID", "")
            .ifBlank { throw IllegalArgumentException("BAO_BOT_CONFIG_ROLE_ID is not configured") }
        val secretId = RuntimeEnv.string("BAO_BOT_CONFIG_SECRET_ID", "")
            .ifBlank { throw IllegalArgumentException("BAO_BOT_CONFIG_SECRET_ID is not configured") }
        return OpenBaoBotConfigService(
            OpenBaoBotConfigServiceConfig(
                baoAddr = baoAddr,
                roleId = roleId,
                secretId = secretId
            )
        )
    }

    private fun localDevBotConfigService(): BotConfigSecretService {
        val store = RuntimeEnv.string("LOCAL_DEV_BOT_CONFIG_STORE", "").trim().lowercase()
        val profile = listOf(
            RuntimeEnv.string("REEF_ENV", ""),
            RuntimeEnv.string("PLATFORM_RUNTIME_PROFILE", ""),
            RuntimeEnv.string("ENVIRONMENT", ""),
            RuntimeEnv.string("APP_ENV", "")
        )
            .map { it.trim().lowercase() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (store == "memory" && profile in setOf("local", "dev", "development", "test", "ci")) {
            return LocalDevBotConfigService.shared
        }
        throw IllegalArgumentException("BAO_ADDR is not configured")
    }

    private fun openBaoOwnerIdentity(bot: ArenaBot): String? {
        val owner = botOwnerMetadata(bot.botId)
            .firstOrNull()
            ?.githubLogin
        return owner?.ifBlank { null } ?: bot.metadata.publisher.takeIf { it.isNotBlank() }
    }

    private fun canManageBotOpenBaoConfig(botId: String, write: Boolean): Boolean {
        val identityService = adminIdentityService ?: return true
        val actorId = currentPrincipal().actorId
        if (!actorId.startsWith("user-gh-")) return true
        val user = identityService.user(actorId) ?: return false
        if (user.trustState.dbValue == "banned") return false
        if (write && user.trustState.dbValue == "limited") return false
        return botOwnerMetadata(botId).any { owner ->
            owner.reefUserId == actorId && owner.ownershipState.dbValue in setOf("owner", "maintainer")
        }
    }

    private fun openBaoProvisioningAuthorizationError(
        flow: String,
        submitterIdentity: String,
        botId: String
    ): PlatformHotPathResponse? {
        val identityService = adminIdentityService
            ?: return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "admin identity service unavailable"))
        val owners = botOwnerMetadata(botId)
        if (owners.isEmpty()) {
            return if (flow == "add") {
                null
            } else {
                PlatformHotPathResponse(403, JsonCodec.writeObject("error" to "bot has no linked owner for OpenBao provisioning"))
            }
        }
        val authorizedOwner = owners.any { owner ->
            owner.githubLogin.equals(submitterIdentity, ignoreCase = true) &&
                owner.trustState.dbValue != "banned" &&
                owner.ownershipState.dbValue in setOf("owner", "maintainer")
        }
        if (authorizedOwner) return null
        return PlatformHotPathResponse(403, JsonCodec.writeObject("error" to "not authorized for OpenBao bot secret slice"))
    }

    private fun recordAdminControlPlaneAudit(
        actorId: String,
        eventType: String,
        targetType: String,
        targetId: String,
        detail: String
    ) {
        val identityService = adminIdentityService ?: return
        try {
            identityService.recordControlPlaneAudit(actorId, eventType, targetType, targetId, detail)
        } catch (ex: Exception) {
            System.err.println(
                "admin_control_plane_audit_failed eventType=$eventType targetId=$targetId message=${JsonFields.escape(ex.message ?: "unknown")}"
            )
        }
    }

    private fun arenaBotJson(bot: ArenaBot): Map<String, Any?> {
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
            "owners" to botOwnerMetadata(bot.botId).map { owner ->
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

    /**
     * Ownership is Arena state; identity and trust remain platform-owned. Joining them
     * at this adapter keeps the optional product boundary explicit and avoids exposing
     * Arena entitlements through the Reef identity store.
     */
    private fun botOwnerMetadata(botId: String): List<ArenaBotOwnerMetadata> {
        val identityService = adminIdentityService ?: return emptyList()
        val entitlementStore = arenaBotEntitlementStore ?: return emptyList()
        return entitlementStore.botOwnerships(botId)
            .asSequence()
            .filter { it.ownershipState != ArenaBotOwnershipState.Revoked }
            .mapNotNull { ownership ->
                identityService.user(ownership.reefUserId)?.let { user ->
                    ArenaBotOwnerMetadata(
                        reefUserId = user.reefUserId,
                        githubLogin = user.githubLogin,
                        displayName = user.displayName,
                        trustState = user.trustState,
                        ownershipState = ownership.ownershipState,
                        assignedAt = ownership.assignedAt
                    )
                }
            }
            .toList()
    }

    private data class ArenaBotOwnerMetadata(
        val reefUserId: String,
        val githubLogin: String,
        val displayName: String,
        val trustState: com.reef.platform.application.admin.AdminTrustState,
        val ownershipState: ArenaBotOwnershipState,
        val assignedAt: java.time.Instant
    )

    private fun arenaBotVersionJson(version: ArenaBotVersion): Map<String, Any?> {
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

    private fun arenaQualificationReportJson(report: ArenaQualificationReport): Map<String, Any?> {
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

    private fun arenaOperatorDecisionJson(decision: ArenaOperatorDecision): Map<String, Any?> {
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

    private fun arenaRuntimeConfigDescriptorJson(descriptor: ArenaRuntimeConfigDescriptor): Map<String, Any?> {
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

    private fun arenaRunJson(run: ArenaRunRecord): Map<String, Any?> {
        return mapOf(
            "runId" to run.runId,
            "modeId" to run.modeId,
            "scenarioId" to run.scenarioId,
            "seed" to run.seed,
            "policyVersion" to run.policyVersion,
            "botVersions" to run.botVersions.map {
                mapOf("botId" to it.botId, "versionId" to it.versionId)
            },
            "status" to run.status.name,
            "createdAt" to run.createdAt.toString(),
            "completedAt" to run.completedAt?.toString()
        )
    }

    private fun arenaRunBotResultJson(result: ArenaRunBotResult): Map<String, Any?> {
        return mapOf(
            "runId" to result.runId,
            "botId" to result.botId,
            "versionId" to result.versionId,
            "scoringPolicyVersion" to result.scoringPolicyVersion,
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

    private fun arenaRunEnforcementEventJson(event: ArenaRunEnforcementEvent): Map<String, Any?> {
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

    private fun arenaLeaderboardEntryJson(entry: ArenaLeaderboardEntry): Map<String, Any?> {
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

    private fun normalizeArenaBotVersionStatus(value: String): ArenaBotVersionStatus? {
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

    private fun normalizeArenaRunStatus(value: String): ArenaRunStatus? {
        return when (value.trim().lowercase()) {
            "planned" -> ArenaRunStatus.Planned
            "running" -> ArenaRunStatus.Running
            "completed", "complete" -> ArenaRunStatus.Completed
            "failed", "fail" -> ArenaRunStatus.Failed
            "cancelled", "canceled", "cancel" -> ArenaRunStatus.Cancelled
            else -> null
        }
    }
}

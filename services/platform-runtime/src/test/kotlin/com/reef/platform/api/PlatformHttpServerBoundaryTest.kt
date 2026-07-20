package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.application.admin.AdminAuthService
import com.reef.platform.application.admin.AdminGitHubOAuthClient
import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.AdminServiceTokenFamily
import com.reef.platform.application.admin.AdminTrustState
import com.reef.platform.application.admin.GitHubUserIdentity
import com.reef.platform.application.admin.InMemoryAdminAuthStore
import com.reef.platform.application.admin.InMemoryAdminIdentityStore
import com.reef.platform.application.settlement.DefaultPostTradePolicyVersion
import com.reef.platform.application.settlement.DefaultPostTradeProfileId
import com.reef.platform.application.settlement.InMemorySettlementFactStore
import com.reef.platform.application.settlement.PostTradeProfileResolver
import com.reef.platform.application.settlement.SettlementFactStore
import com.reef.platform.application.settlement.TradeSettlementObligationMaterializer
import com.reef.platform.domain.PostTradeProfile
import com.reef.platform.domain.ScenarioRunPostTradeProfile
import com.reef.platform.domain.Account
import com.reef.platform.domain.ActorRoleBinding
import com.reef.platform.domain.CancelOrderCommand
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderRejected
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.ModifyOrderCommand
import com.reef.platform.domain.Participant
import com.reef.platform.domain.Permission
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RoleDefinition
import com.reef.platform.domain.SubmitOrderCommand
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import com.reef.platform.infrastructure.engine.EngineGateway
import com.reef.platform.infrastructure.persistence.CanonicalSubmitOutcome
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.reef.platform.infrastructure.persistence.PersistableSubmitOutcome
import com.reef.platform.infrastructure.persistence.VenueCommandOutcomeFact
import com.reef.platform.infrastructure.persistence.VenueEventBatchFact
import com.sun.net.httpserver.Headers
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PlatformHttpServerBoundaryTest {
    private fun apiReadHeaders(
        clientId: String = "client-1",
        participantId: String = "participant-1"
    ): Map<String, String> {
        return mapOf(
            "X-Client-Id" to clientId,
            "X-Participant-Id" to participantId
        )
    }

    @Test
    fun adminGatewayRouteMapIncludesCoreAliasesAndExcludesOptionalProducts() {
        assertEquals(
            AdminGatewayRoute(
                "/reference/instruments",
                "admin",
                setOf(AdminServiceTokenFamily.Admin)
            ),
            adminGatewayRouteFor("/admin/v1/reference/instruments", "POST")
        )
        assertEquals(
            AdminGatewayRoute(
                "/internal/admin/access/users",
                "admin",
                setOf(AdminServiceTokenFamily.Admin),
                setOf(AdminIdentityService.RoleOperator, AdminIdentityService.RolePlatformAdmin)
            ),
            adminGatewayRouteFor("/admin/v1/access/users", "GET")
        )
        assertEquals(
            "/internal/admin/account-risk/controls",
            adminGatewayRouteFor("/admin/v1/risk/account-controls", "POST")?.internalPath
        )
        assertEquals(
            "/internal/boundary/account-risk/controls",
            adminGatewayRouteFor("/admin/v1/risk/account-controls", "GET")?.internalPath
        )
        assertEquals(
            "/internal/admin/settlement/facts",
            adminGatewayRouteFor("/admin/v1/settlement/facts", "POST")?.internalPath
        )
        assertEquals(null, adminGatewayRouteFor("/admin/v1/arena/bots", "GET"))
        assertEquals(null, adminGatewayRouteFor("/admin/v1/analytics/run-exports", "GET"))
    }

    @Test
    fun optionalProductRouteComposesThroughCoreAdminGateway() {
        val extension = object : OptionalProductRouteExtension {
            override val internalPaths = listOf("/internal/admin/example")
            override val publicReadPaths = emptyList<String>()
            override val adminRoutes = listOf(
                OptionalProductAdminRoute(
                    externalPath = "/admin/v1/example",
                    methods = setOf("GET"),
                    internalPath = "/internal/admin/example",
                    fallbackTokenEnv = "EXAMPLE_API_TOKEN",
                    fallbackActorEnv = "EXAMPLE_API_ACTOR_ID",
                    serviceTokenFamilies = setOf(AdminServiceTokenFamily.Admin)
                )
            )

            override fun handleInternal(
                method: String,
                path: String,
                query: String?,
                body: String,
                principal: AdminRequestPrincipal
            ): PlatformHotPathResponse? {
                if (path != "/internal/admin/example") return null
                return PlatformHotPathResponse(
                    200,
                    JsonCodec.writeObject(
                        "method" to method,
                        "actorId" to principal.actorId
                    )
                )
            }

            override fun handlePublicRead(path: String, query: String?): PlatformHotPathResponse? = null
        }
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            localDevAdminAuthBypass = true,
            productRouteExtensions = listOf(extension)
        )
        try {
            val allowed = get(server.address.port, "/admin/v1/example")
            val unsupportedMethod = post(server.address.port, "/admin/v1/example", emptyMap(), "{}")

            assertEquals(200, allowed.status, allowed.body)
            assertContains(allowed.body, "\"method\":\"GET\"")
            assertContains(allowed.body, "\"actorId\":\"admin-cli\"")
            assertEquals(404, unsupportedMethod.status, unsupportedMethod.body)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun optionalProductRouteEnforcesDeclaredServiceTokenFamily() {
        val extension = object : OptionalProductRouteExtension {
            override val internalPaths = listOf("/internal/admin/example")
            override val publicReadPaths = emptyList<String>()
            override val adminRoutes = listOf(
                OptionalProductAdminRoute(
                    externalPath = "/admin/v1/example",
                    methods = setOf("GET"),
                    internalPath = "/internal/admin/example",
                    fallbackTokenEnv = "EXAMPLE_API_TOKEN",
                    fallbackActorEnv = "EXAMPLE_API_ACTOR_ID",
                    serviceTokenFamilies = setOf(AdminServiceTokenFamily.Admin)
                )
            )

            override fun handleInternal(
                method: String,
                path: String,
                query: String?,
                body: String,
                principal: AdminRequestPrincipal
            ): PlatformHotPathResponse? = if (path == "/internal/admin/example") {
                PlatformHotPathResponse(200, JsonCodec.writeObject("actorId" to principal.actorId))
            } else {
                null
            }

            override fun handlePublicRead(path: String, query: String?): PlatformHotPathResponse? = null
        }
        val auth = testAdminAuth()
        val adminToken = auth.authService.issueServiceToken(
            AdminServiceTokenFamily.Admin,
            "admin-service",
            ttl = null
        )
        val simToken = auth.authService.issueServiceToken(
            AdminServiceTokenFamily.Sim,
            "sim-service",
            ttl = null
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            adminAuthService = auth.authService,
            adminIdentityService = auth.identityService,
            productRouteExtensions = listOf(extension)
        )
        try {
            val denied = get(
                server.address.port,
                "/admin/v1/example",
                mapOf("Authorization" to "Bearer ${simToken.token}")
            )
            val allowed = get(
                server.address.port,
                "/admin/v1/example",
                mapOf("Authorization" to "Bearer ${adminToken.token}")
            )

            assertEquals(401, denied.status, denied.body)
            assertEquals(200, allowed.status, allowed.body)
            assertContains(allowed.body, "\"actorId\":\"admin-service\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun adminGitHubOAuthCallbackIssuesSessionCookie() {
        val auth = testAdminAuth()
        val github = FakeAdminGitHubOAuthClient()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            adminAuthService = auth.authService,
            adminIdentityService = auth.identityService,
            adminGitHubOAuthClient = github
        )
        try {
            val start = get(server.address.port, "/admin/auth/github/start?redirectPath=/admin")
            assertEquals(302, start.status)
            val location = responseHeader(start, "Location")
            assertContains(location, "https://github.test/oauth?")
            val state = location.substringAfter("state=").substringBefore("&")
            assertTrue(state.isNotBlank())

            val callback = get(server.address.port, "/admin/auth/github/callback?code=github-code&state=$state")

            assertEquals(302, callback.status)
            assertEquals("/admin", responseHeader(callback, "Location"))
            val cookie = responseHeader(callback, "Set-Cookie")
            assertTrue(cookie.isNotBlank())
            assertContains(cookie, "reef_admin_session=")
            assertContains(cookie, "HttpOnly")
            assertContains(cookie, "SameSite=Lax")

            val session = get(
                server.address.port,
                "/admin/auth/session",
                headers = mapOf("Cookie" to cookie.substringBefore(";"))
            )
            assertEquals(200, session.status)
            assertContains(session.body, "\"reefUserId\":\"user-gh-12345\"")
            assertContains(session.body, "\"githubLogin\":\"octo\"")
            assertContains(session.body, "\"displayName\":\"Octo User\"")
            assertContains(session.body, "\"trustState\":\"new\"")
            assertContains(session.body, "\"roles\":[\"participant\"]")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun adminGitHubOAuthCallbackCanRedirectToLocalDevAdminUi() {
        val auth = testAdminAuth()
        val github = FakeAdminGitHubOAuthClient()
        val localUiBase = localDevAdminUiBaseUrl(
            envLookup(
                "REEF_ENV" to "local",
                "LOCAL_DEV_ADMIN_UI_BASE_URL" to "http://localhost:5174"
            )
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            adminAuthService = auth.authService,
            adminIdentityService = auth.identityService,
            adminGitHubOAuthClient = github,
            localDevAdminUiBaseUrl = localUiBase
        )
        try {
            val start = get(server.address.port, "/admin/auth/github/start?redirectPath=/admin")
            val state = responseHeader(start, "Location").substringAfter("state=").substringBefore("&")

            val callback = get(server.address.port, "/admin/auth/github/callback?code=github-code&state=$state")

            assertEquals(302, callback.status)
            assertEquals("http://localhost:5174/admin", responseHeader(callback, "Location"))
            assertContains(responseHeader(callback, "Set-Cookie"), "reef_admin_session=")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun localDevAdminUiCorsAllowsConfiguredLoopbackOriginOnly() {
        val localUiBase = localDevAdminUiBaseUrl(
            envLookup(
                "REEF_ENV" to "local",
                "LOCAL_DEV_ADMIN_UI_BASE_URL" to "http://localhost:5174"
            )
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            localDevAdminUiBaseUrl = localUiBase
        )
        try {
            val preflight = options(
                server.address.port,
                "/admin/v1/access/roles",
                headers = mapOf(
                    "Origin" to "http://localhost:5174",
                    "Access-Control-Request-Headers" to "content-type"
                )
            )
            assertEquals(204, preflight.status)
            assertEquals("http://localhost:5174", responseHeader(preflight, "Access-Control-Allow-Origin"))
            assertEquals("true", responseHeader(preflight, "Access-Control-Allow-Credentials"))
            assertEquals("content-type", responseHeader(preflight, "Access-Control-Allow-Headers"))

            val authSession = requestWithHeaders(
                "GET",
                server.address.port,
                "/admin/auth/session",
                headers = mapOf("Origin" to "http://localhost:5174")
            )
            assertEquals("http://localhost:5174", responseHeader(authSession, "Access-Control-Allow-Origin"))
            assertEquals("true", responseHeader(authSession, "Access-Control-Allow-Credentials"))

            val deniedOrigin = requestWithHeaders(
                "GET",
                server.address.port,
                "/admin/auth/session",
                headers = mapOf("Origin" to "https://reef.example")
            )
            assertEquals("", responseHeader(deniedOrigin, "Access-Control-Allow-Origin"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun localDevAdminUiBaseUrlIsIgnoredOutsideLocalProfile() {
        assertEquals(
            null,
            localDevAdminUiBaseUrl(
                envLookup(
                    "REEF_ENV" to "prod",
                    "LOCAL_DEV_ADMIN_UI_BASE_URL" to "http://localhost:5174"
                )
            )
        )
    }

    @Test
    fun adminGitHubOAuthCallbackReturnsGatewayErrorForExchangeFailure() {
        val auth = testAdminAuth()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            adminAuthService = auth.authService,
            adminIdentityService = auth.identityService,
            adminGitHubOAuthClient = ThrowingAdminGitHubOAuthClient()
        )
        try {
            val start = get(server.address.port, "/admin/auth/github/start?redirectPath=/admin")
            val state = responseHeader(start, "Location").substringAfter("state=").substringBefore("&")

            val callback = get(server.address.port, "/admin/auth/github/callback?code=github-code&state=$state")

            assertEquals(502, callback.status)
            assertContains(callback.body, "admin auth upstream failed")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun localDevAdminAuthBypassRequiresFlagAndLocalProfile() {
        assertFalse(localDevAdminAuthBypassEnabled { null })
        assertFalse(
            localDevAdminAuthBypassEnabled(
                envLookup(
                    "LOCAL_DEV_ADMIN_AUTH_BYPASS" to "true",
                    "REEF_ENV" to "prod"
                )
            )
        )
        assertTrue(
            localDevAdminAuthBypassEnabled(
                envLookup(
                    "LOCAL_DEV_ADMIN_AUTH_BYPASS" to "true",
                    "REEF_ENV" to "local"
                )
            )
        )
    }

    @Test
    fun localDevAdminAuthSessionReturnsFakeLoopbackUserWhenEnabled() {
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            localDevAdminAuthBypass = true
        )
        try {
            val response = get(server.address.port, "/admin/auth/session")

            assertEquals(200, response.status)
            assertContains(response.body, "\"reefUserId\":\"admin-cli\"")
            assertContains(response.body, "\"githubLogin\":\"local-dev-admin\"")
            assertContains(response.body, "\"roles\":[\"operator\",\"participant\"]")
            assertContains(response.body, "\"authProvider\":\"local-dev\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun localDevAdminAuthBypassAllowsAdminGatewayWithoutCookie() {
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            localDevAdminAuthBypass = true
        )
        try {
            val response = post(
                server.address.port,
                "/admin/v1/reference/instruments",
                emptyMap(),
                """{"instrumentId":"LOCAL","symbol":"LOCAL"}"""
            )

            assertEquals(200, response.status)
            assertContains(response.body, "\"instrumentId\":\"LOCAL\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun adminGatewayAcceptsSessionCookieAndServiceToken() {
        val auth = testAdminAuth()
        val user = auth.identityService.ensureGitHubUser(GitHubUserIdentity(12345, "octo"))
        grantTrustedPlatformAdmin(auth, user.reefUserId)
        val session = auth.authService.createSession(user.reefUserId)
        val serviceToken = auth.authService.issueServiceToken(
            tokenFamily = AdminServiceTokenFamily.Admin,
            subjectActorId = "ci-admin",
            ttl = null
        )
        val wrongFamilyToken = auth.authService.issueServiceToken(
            tokenFamily = AdminServiceTokenFamily.Ci,
            subjectActorId = "ci-arena",
            ttl = null
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            adminAuthService = auth.authService,
            adminIdentityService = auth.identityService,
            adminGitHubOAuthClient = FakeAdminGitHubOAuthClient()
        )
        try {
            val denied = post(server.address.port, "/admin/v1/risk/account-controls", emptyMap(), "{}")
            val allowedByCookie = post(
                server.address.port,
                "/admin/v1/risk/account-controls",
                headers = mapOf("Cookie" to "reef_admin_session=${session.token}"),
                body = "{}"
            )
            val allowedByServiceToken = post(
                server.address.port,
                "/admin/v1/risk/account-controls",
                headers = mapOf("Authorization" to "Bearer ${serviceToken.token}"),
                body = "{}"
            )
            val deniedByWrongFamily = post(
                server.address.port,
                "/admin/v1/risk/account-controls",
                headers = mapOf("Authorization" to "Bearer ${wrongFamilyToken.token}"),
                body = "{}"
            )

            assertEquals(401, denied.status)
            assertEquals(503, allowedByCookie.status)
            assertContains(allowedByCookie.body, "account risk control store unavailable")
            assertEquals(503, allowedByServiceToken.status)
            assertContains(allowedByServiceToken.body, "account risk control store unavailable")
            assertEquals(401, deniedByWrongFamily.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun adminAccessGatewayListsUsersAndLetsTrustedOperatorAssignReviewerRole() {
        val auth = testAdminAuth()
        val operator = auth.identityService.ensureGitHubUser(GitHubUserIdentity(12345, "octo"))
        val target = auth.identityService.ensureGitHubUser(GitHubUserIdentity(67890, "mona"))
        grantTrustedOperator(auth, operator.reefUserId)
        val session = auth.authService.createSession(operator.reefUserId)
        val headers = mapOf("Cookie" to "reef_admin_session=${session.token}")
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            adminAuthService = auth.authService,
            adminIdentityService = auth.identityService,
            adminGitHubOAuthClient = FakeAdminGitHubOAuthClient()
        )
        try {
            val users = get(server.address.port, "/admin/v1/access/users", headers)
            val roles = get(server.address.port, "/admin/v1/access/roles", headers)
            val assigned = post(
                server.address.port,
                "/admin/v1/access/users/roles",
                headers,
                JsonCodec.writeObject(
                    "reefUserId" to target.reefUserId,
                    "roleId" to AdminIdentityService.RoleReviewer,
                    "reason" to "ready to review submissions"
                )
            )
            val limited = post(
                server.address.port,
                "/admin/v1/access/users/trust-state",
                headers,
                JsonCodec.writeObject(
                    "reefUserId" to target.reefUserId,
                    "trustState" to AdminTrustState.Limited.dbValue,
                    "reason" to "temporary moderation"
                )
            )

            assertEquals(200, users.status)
            assertContains(users.body, "\"reefUserId\":\"${operator.reefUserId}\"")
            assertContains(users.body, "\"githubLogin\":\"mona\"")
            assertContains(users.body, "\"roles\":[")
            assertEquals(200, roles.status)
            assertContains(roles.body, "\"roleId\":\"operator\"")
            assertEquals(200, assigned.status)
            assertContains(assigned.body, "\"roleId\":\"reviewer\"")
            assertEquals(200, limited.status)
            assertContains(limited.body, "\"trustState\":\"limited\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun adminAccessGatewayLeavesPrivilegedRoleChangesToPlatformAdmins() {
        val auth = testAdminAuth()
        val operator = auth.identityService.ensureGitHubUser(GitHubUserIdentity(20001, "operator"))
        val platformAdmin = auth.identityService.ensureGitHubUser(GitHubUserIdentity(20002, "platform-admin"))
        val target = auth.identityService.ensureGitHubUser(GitHubUserIdentity(20003, "target-user"))
        grantTrustedOperator(auth, operator.reefUserId)
        grantTrustedPlatformAdmin(auth, platformAdmin.reefUserId)
        val operatorHeaders = mapOf("Cookie" to "reef_admin_session=${auth.authService.createSession(operator.reefUserId).token}")
        val platformHeaders = mapOf(
            "Cookie" to "reef_admin_session=${auth.authService.createSession(platformAdmin.reefUserId).token}"
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            adminAuthService = auth.authService,
            adminIdentityService = auth.identityService,
            adminGitHubOAuthClient = FakeAdminGitHubOAuthClient()
        )
        try {
            val body = JsonCodec.writeObject(
                "reefUserId" to target.reefUserId,
                "roleId" to AdminIdentityService.RoleOperator,
                "reason" to "promote to operator"
            )
            val denied = post(server.address.port, "/admin/v1/access/users/roles", operatorHeaders, body)
            val allowed = post(server.address.port, "/admin/v1/access/users/roles", platformHeaders, body)
            val banned = post(
                server.address.port,
                "/admin/v1/access/users/trust-state",
                platformHeaders,
                JsonCodec.writeObject(
                    "reefUserId" to target.reefUserId,
                    "trustState" to AdminTrustState.Banned.dbValue,
                    "reason" to "account compromise"
                )
            )

            assertEquals(400, denied.status)
            assertContains(denied.body, "trusted platform-admin role required")
            assertEquals(200, allowed.status)
            assertContains(allowed.body, "\"roleId\":\"operator\"")
            assertEquals(200, banned.status)
            assertContains(banned.body, "\"trustState\":\"banned\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun adminGatewayReferenceAndAuthSetupBypassesLegacyMutationRouteGate() {
        val auth = testAdminAuth()
        val serviceToken = auth.authService.issueServiceToken(
            tokenFamily = AdminServiceTokenFamily.Admin,
            subjectActorId = "setup-admin",
            ttl = null
        )
        val headers = mapOf("Authorization" to "Bearer ${serviceToken.token}")
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            adminAuthService = auth.authService,
            adminIdentityService = auth.identityService,
            adminGitHubOAuthClient = FakeAdminGitHubOAuthClient(),
            legacyMutationRoutesEnabled = false
        )
        try {
            val legacyReference = post(
                port = server.address.port,
                path = "/reference/instruments",
                headers = mapOf("X-Reef-Internal-Route" to "true"),
                body = """{"instrumentId":"MSFT","symbol":"MSFT"}"""
            )
            val instrument = post(
                server.address.port,
                "/admin/v1/reference/instruments",
                headers,
                """{"instrumentId":"MSFT","symbol":"MSFT","assetClass":"US_EQ","currency":"USD"}"""
            )
            val participant = post(
                server.address.port,
                "/admin/v1/reference/participants",
                headers,
                """{"participantId":"participant-gateway","name":"Gateway Participant"}"""
            )
            val account = post(
                server.address.port,
                "/admin/v1/reference/accounts",
                headers,
                """{"accountId":"account-gateway","participantId":"participant-gateway","accountType":"HOUSE"}"""
            )
            val role = post(
                server.address.port,
                "/admin/v1/auth/roles",
                headers,
                """{"roleId":"gateway_trader","permissions":"order.submit"}"""
            )
            val actorRole = post(
                server.address.port,
                "/admin/v1/auth/actor-roles",
                headers,
                """{"actorId":"gateway-user","roleId":"gateway_trader"}"""
            )
            val instruments = get(server.address.port, "/admin/v1/reference/instruments", headers)
            val actorRoles = get(server.address.port, "/admin/v1/auth/actor-roles?actorId=gateway-user", headers)

            assertEquals(403, legacyReference.status)
            assertEquals(200, instrument.status)
            assertEquals(200, participant.status)
            assertEquals(200, account.status)
            assertEquals(200, role.status)
            assertEquals(200, actorRole.status)
            assertContains(instruments.body, "\"instrumentId\":\"MSFT\"")
            assertContains(actorRoles.body, "\"actorId\":\"gateway-user\"")
            assertContains(actorRoles.body, "\"roleId\":\"gateway_trader\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun adminGatewayStaticFallbackTokenBindsActorFromConfig() {
        val route = adminGatewayRouteFor("/admin/v1/reference/instruments", "POST")
            ?: error("expected reference-data admin gateway route")
        val auth = AdminSessionAuth(
            adminAuthService = null,
            adminIdentityService = null,
            adminGitHubOAuthClient = null,
            adminSessionCookieName = "reef_admin_session",
            adminSessionCookieSecure = true,
            localDevAdminAuthBypass = false,
            internalHttpExposureMode = InternalHttpExposureMode.LocalOnly,
            envLookup = mapOf(
                "ADMIN_API_TOKEN" to "admin-token",
                "ADMIN_ACTOR_ID" to "setup-admin"
            )::get
        )
        val headers = Headers().apply {
            add("Authorization", "Bearer admin-token")
            add("X-Reef-Actor-Id", "admin-cli")
            add("X-Correlation-Id", "corr-static-token")
        }

        val principal = auth.authorizeGateway(
            PlatformHotPathRequest(
                method = "POST",
                path = "/admin/v1/reference/instruments",
                query = null,
                headers = headers,
                remoteAddress = "203.0.113.10",
                body = "{}"
            ),
            route
        )

        assertEquals("setup-admin", principal?.actorId)
        assertEquals("corr-static-token", principal?.correlationId)
    }

    @Test
    fun adminGatewaySensitiveSessionRoutesRequireTrustedRouteRole() {
        val auth = testAdminAuth()
        val user = auth.identityService.ensureGitHubUser(GitHubUserIdentity(12345, "octo"))
        val session = auth.authService.createSession(user.reefUserId)
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            adminAuthService = auth.authService,
            adminIdentityService = auth.identityService,
            adminGitHubOAuthClient = FakeAdminGitHubOAuthClient(),
            settlementFactStore = InMemorySettlementFactStore()
        )
        try {
            val headers = mapOf("Cookie" to "reef_admin_session=${session.token}")
            val accountRisk = post(server.address.port, "/admin/v1/risk/account-controls", headers, "{}")
            val priceCollar = post(server.address.port, "/admin/v1/risk/price-collars", headers, "{}")
            val circuitBreaker = post(server.address.port, "/admin/v1/risk/circuit-breakers", headers, "{}")
            val settlementFacts = post(
                server.address.port,
                "/admin/v1/settlement/facts",
                headers,
                p2SettlementFactsBody("p2-denied-admin-gateway")
            )
            val settlementMaterialize = post(
                server.address.port,
                "/admin/v1/settlement/obligations/materialize",
                headers,
                """{"scenarioRunId":"p2-denied-admin-gateway"}"""
            )
            listOf(accountRisk, priceCollar, circuitBreaker, settlementFacts, settlementMaterialize).forEach { response ->
                assertEquals(403, response.status, response.body)
                assertContains(response.body, "trusted admin identity is required")
            }

            auth.identityService.updateTrustState("admin-cli", user.reefUserId, AdminTrustState.Trusted)
            val trustedWithoutRole = post(server.address.port, "/admin/v1/risk/account-controls", headers, "{}")
            assertEquals(403, trustedWithoutRole.status, trustedWithoutRole.body)
            assertContains(trustedWithoutRole.body, "admin role required")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun adminGatewayUnknownBearerTokenReturnsCleanUnauthorizedWhenAdminAuthEnabled() {
        val auth = testAdminAuth()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            adminAuthService = auth.authService,
            adminIdentityService = auth.identityService,
            adminGitHubOAuthClient = FakeAdminGitHubOAuthClient()
        )
        try {
            val response = get(
                server.address.port,
                "/admin/v1/reference/instruments",
                headers = mapOf("Authorization" to "Bearer static-fallback-token")
            )

            assertEquals(401, response.status)
            assertContains(response.body, "unauthorized")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun adminGatewaySettlementFactsAliasAppendsFacts() {
        val auth = testAdminAuth()
        val serviceToken = auth.authService.issueServiceToken(
            tokenFamily = AdminServiceTokenFamily.Admin,
            subjectActorId = "settlement-admin",
            ttl = null
        )
        val settlementStore = InMemorySettlementFactStore()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            adminAuthService = auth.authService,
            adminIdentityService = auth.identityService,
            settlementFactStore = settlementStore
        )
        try {
            val denied = post(
                server.address.port,
                "/admin/v1/settlement/facts",
                emptyMap(),
                p2SettlementFactsBody("p2-admin-gateway")
            )
            val posted = post(
                server.address.port,
                "/admin/v1/settlement/facts",
                headers = mapOf("Authorization" to "Bearer ${serviceToken.token}"),
                body = p2SettlementFactsBody("p2-admin-gateway")
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-admin-gateway")

            assertEquals(401, denied.status)
            assertEquals(200, posted.status)
            assertContains(posted.body, "\"status\":\"ok\"")
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"settlementObligationId\":\"obl-1\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun adminGatewayRiskAccountControlsGetReturnsCurrentState() {
        val auth = testAdminAuth()
        val user = auth.identityService.ensureGitHubUser(GitHubUserIdentity(12345, "octo"))
        grantTrustedPlatformAdmin(auth, user.reefUserId)
        val session = auth.authService.createSession(user.reefUserId)
        val accountRiskStore = RecordingAccountRiskStore()
        accountRiskStore.upsertControl(
            scopeType = "BOT",
            scopeId = "bot-1",
            decision = AccountRiskDecision.DISABLED_BOT,
            reason = "operator disabled",
            maxQuantityUnits = "",
            maxNotional = "",
            currency = "USD"
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            accountRiskCheck = accountRiskStore,
            adminAuthService = auth.authService,
            adminIdentityService = auth.identityService,
            adminGitHubOAuthClient = FakeAdminGitHubOAuthClient()
        )
        try {
            val response = get(
                server.address.port,
                "/admin/v1/risk/account-controls",
                headers = mapOf("Cookie" to "reef_admin_session=${session.token}")
            )

            assertEquals(200, response.status)
            assertContains(response.body, "\"scopeId\":\"bot-1\"")
            assertContains(response.body, "\"decision\":\"DISABLED_BOT\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitReturnsBoundaryErrorEnvelopeWhenClientIdMissing() {
        val server = testServer()
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf("Idempotency-Key" to "idem-1"),
                body = "{}"
            )
            assertEquals(401, response.status)
            assertContains(response.body, "\"code\":\"CLIENT_ID_REQUIRED\"")
            assertContains(response.body, "\"correlationId\":\"\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1DataAvailabilityReportsReadSurfaceFreshness() {
        val server = testServer()
        try {
            val response = get(server.address.port, "/api/v1/data/availability", headers = apiReadHeaders())

            assertEquals(200, response.status)
            assertContains(response.body, "\"source\":\"venue-event-batch\"")
            assertContains(response.body, "\"name\":\"marketDataSnapshots\"")
            assertContains(response.body, "\"endpoint\":\"/api/v1/market-data/snapshots/{instrumentId}\"")
            assertContains(response.body, "\"name\":\"currentOrders\"")
            assertContains(response.body, "\"freshness\":\"dirty-tracked lifecycle projection\"")
            assertContains(response.body, "\"name\":\"tradeTape\"")
            assertContains(response.body, "\"freshness\":\"durable fact rows\"")
            assertContains(response.body, "\"name\":\"settlementFacts\"")
            assertContains(response.body, "\"endpoint\":\"/api/v1/settlement/facts/{scenarioRunId}\"")
            assertContains(response.body, "\"name\":\"settlementObligations\"")
            assertContains(response.body, "\"endpoint\":\"/api/v1/settlement/obligations/{scenarioRunId}\"")
            assertContains(response.body, "\"name\":\"settlementLedger\"")
            assertContains(response.body, "\"endpoint\":\"/api/v1/settlement/ledger/{scenarioRunId}\"")
            assertContains(response.body, "\"name\":\"settlementExceptions\"")
            assertContains(response.body, "\"endpoint\":\"/api/v1/settlement/exceptions/{scenarioRunId}\"")
            assertContains(response.body, "\"name\":\"settlementProof\"")
            assertContains(response.body, "\"endpoint\":\"/api/v1/settlement/proof/{scenarioRunId}\"")
            assertContains(response.body, "\"name\":\"settlementScore\"")
            assertContains(response.body, "\"endpoint\":\"/api/v1/settlement/score/{scenarioRunId}\"")
            assertFalse(response.body.contains("\"name\":\"arenaLeaderboard\""))
            assertFalse(response.body.contains("/api/v1/arena/leaderboard"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1ReadEndpointsRequireClientPrincipal() {
        val server = testServer()
        try {
            listOf(
                "/api/v1/commands/missing-command",
                "/api/v1/data/availability",
                "/api/v1/market-data/snapshots/AAPL",
                "/api/v1/market-data/depth/AAPL",
                "/api/v1/market-data/trades/AAPL",
                "/api/v1/market-data/bars/AAPL?interval=1m&start=2026-07-16T00:00:00Z&end=2026-07-16T01:00:00Z",
                "/api/v1/orders/current?participantId=participant-1",
                "/api/v1/orders/history?participantId=participant-1",
                "/api/v1/orders/fills?participantId=participant-1",
                "/api/v1/settlement/facts/run-public-denied",
                "/api/v1/settlement/obligations/run-public-denied",
                "/api/v1/settlement/ledger/run-public-denied",
                "/api/v1/settlement/proof/run-public-denied",
                "/api/v1/settlement/score/run-public-denied"
            ).forEach { path ->
                val response = get(server.address.port, path)

                assertEquals(401, response.status, path)
                assertContains(response.body, "\"code\":\"CLIENT_ID_REQUIRED\"")
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun healthzAndReadyzExposeCheapRuntimeStatus() {
        val server = testServer()
        try {
            val health = get(server.address.port, "/healthz")
            val readiness = get(server.address.port, "/readyz")

            assertEquals(200, health.status)
            assertContains(health.body, "\"status\":\"ok\"")
            assertEquals(200, readiness.status)
            assertContains(readiness.body, "\"status\":\"ok\"")
            assertContains(readiness.body, "\"internalHttpMode\":\"localonly\"")
            assertContains(readiness.body, "\"pipeline\"")
            assertContains(readiness.body, "\"commandProcessingMode\":\"sync-result\"")
            assertContains(readiness.body, "\"dependencies\"")
            assertContains(readiness.body, "\"enabledDependencies\"")
            assertContains(readiness.body, "\"dbPools\":{\"enabled\":true,\"ready\":true")
            assertContains(readiness.body, "\"streamIngress\":{\"enabled\":false,\"ready\":true")
            assertContains(readiness.body, "\"adminStore\":{\"enabled\":false,\"ready\":true")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun readyzDegradesWhenStreamAckPipelineIsNotConfigured() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            commandProcessingMode = CommandProcessingMode.StreamAck
        )
        try {
            val readiness = get(server.address.port, "/readyz")

            assertEquals(200, readiness.status)
            assertContains(readiness.body, "\"status\":\"degraded\"")
            assertContains(readiness.body, "\"commandProcessingMode\":\"stream-ack\"")
            assertContains(readiness.body, "\"streamAckRequired\":true")
            assertContains(readiness.body, "\"streamPipelineConfigured\":false")
            assertContains(readiness.body, "\"streamReady\":false")
            assertContains(readiness.body, "\"streamIngress\":{\"enabled\":true,\"ready\":false")
            assertContains(readiness.body, "stream-ack pipeline is not fully configured or healthy")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1ParticipantOrderReadsRequireMatchingParticipantPrincipal() {
        val server = testServer()
        try {
            val denied = get(
                server.address.port,
                "/api/v1/orders/current?participantId=participant-2",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "X-Participant-Id" to "participant-1"
                )
            )
            val allowed = get(
                server.address.port,
                "/api/v1/orders/current?participantId=participant-1",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "X-Participant-Id" to "participant-1"
                )
            )
            val historyDenied = get(
                server.address.port,
                "/api/v1/orders/history?participantId=participant-2",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "X-Participant-Id" to "participant-1"
                )
            )
            val historyAllowed = get(
                server.address.port,
                "/api/v1/orders/history?participantId=participant-1",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "X-Participant-Id" to "participant-1"
                )
            )
            val fillsDenied = get(
                server.address.port,
                "/api/v1/orders/fills?participantId=participant-2",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "X-Participant-Id" to "participant-1"
                )
            )
            val fillsAllowed = get(
                server.address.port,
                "/api/v1/orders/fills?participantId=participant-1",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "X-Participant-Id" to "participant-1"
                )
            )
            val missingParticipant = get(
                server.address.port,
                "/api/v1/orders/current?participantId=participant-1",
                headers = mapOf("X-Client-Id" to "client-1")
            )

            assertEquals(403, denied.status)
            assertContains(denied.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
            assertEquals(200, allowed.status)
            assertContains(allowed.body, "\"orders\"")
            assertEquals(403, historyDenied.status)
            assertContains(historyDenied.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
            assertEquals(200, historyAllowed.status)
            assertContains(historyAllowed.body, "\"orders\"")
            assertEquals(403, fillsDenied.status)
            assertContains(fillsDenied.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
            assertEquals(200, fillsAllowed.status)
            assertContains(fillsAllowed.body, "\"fills\"")
            assertEquals(403, missingParticipant.status)
            assertContains(missingParticipant.body, "\"code\":\"OBJECT_AUTH_REQUIRED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1ParticipantOrderReadsUseAuthenticatedTokenScopeBeforeParticipantHeader() {
        val boundary = ExternalApiBoundary(
            authHook = StaticTokenAuthHook(
                mapOf(
                    "client-1" to StaticTokenClientConfig(
                        token = "token-1",
                        principal = ExternalApiPrincipal(
                            clientId = "client-1",
                            participantIds = setOf("participant-1")
                        )
                    )
                )
            )
        )
        val server = testServer(boundary = boundary)
        try {
            val allowed = get(
                server.address.port,
                "/api/v1/orders/current?participantId=participant-1",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Authorization" to "Bearer token-1"
                )
            )
            val denied = get(
                server.address.port,
                "/api/v1/orders/current?participantId=participant-2",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Authorization" to "Bearer token-1",
                    "X-Participant-Id" to "participant-2"
                )
            )

            assertEquals(200, allowed.status)
            assertContains(allowed.body, "\"orders\"")
            assertEquals(403, denied.status)
            assertContains(denied.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun legacyGlobalReadEndpointsRequireApiBoundaryIdentity() {
        val server = testServerWithGateway(EchoOrderEngineGateway())
        try {
            listOf(
                "/orders",
                "/orders/ord-legacy-read",
                "/orders/ord-legacy-read/events",
                "/trades",
                "/events"
            ).forEach { path ->
                val denied = get(server.address.port, path)
                val allowed = get(server.address.port, path, headers = apiReadHeaders())

                assertEquals(401, denied.status, path)
                assertContains(denied.body, "\"code\":\"CLIENT_ID_REQUIRED\"")
                assertTrue(allowed.status == 200 || allowed.status == 404, path)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitReturnsRateLimitedFromHook() {
        val server = testServer(
            boundary = ExternalApiBoundary(
                authHook = AllowAllAuthHook(),
                rateLimitHook = object : RateLimitHook {
                    override fun allow(clientId: String, route: String): BoundaryError? {
                        return BoundaryError(429, "RATE_LIMITED", "rate limit exceeded")
                    }
                }
            )
        )
        try {
            val reset = post(server.address.port, "/internal/perf/hot-path", emptyMap(), "")
            assertEquals(200, reset.status)

            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-1"
                ),
                body = "{}"
            )
            assertEquals(429, response.status)
            assertContains(response.body, "\"code\":\"RATE_LIMITED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitReplaysFirstResponseForSameIdempotencyKey() {
        val server = testServerWithGateway(EchoOrderEngineGateway())
        try {
            val reset = post(server.address.port, "/internal/perf/hot-path", emptyMap(), "")
            assertEquals(200, reset.status)

            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-1"
            )
            val first = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers,
                body = """
                    {
                      "commandId":"cmd-1",
                      "traceId":"trace-1",
                      "causationId":"",
                      "correlationId":"corr-1",
                      "actorId":"bot-1",
                      "occurredAt":"2026-05-22T00:00:00Z",
                      "orderId":"ord-first",
                      "instrumentId":"AAPL",
                      "participantId":"participant-1",
                      "accountId":"account-1",
                      "side":"BUY",
                      "orderType":"LIMIT",
                      "quantityUnits":"100",
                      "limitPrice":"150250000000",
                      "currency":"USD",
                      "timeInForce":"DAY"
                    }
                """.trimIndent()
            )
            val second = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers,
                body = """
                    {
                      "commandId":"cmd-2",
                      "traceId":"trace-2",
                      "causationId":"",
                      "correlationId":"corr-2",
                      "actorId":"bot-2",
                      "occurredAt":"2026-05-22T00:00:01Z",
                      "orderId":"ord-second",
                      "instrumentId":"AAPL",
                      "participantId":"participant-1",
                      "accountId":"account-1",
                      "side":"BUY",
                      "orderType":"LIMIT",
                      "quantityUnits":"200",
                      "limitPrice":"150250000001",
                      "currency":"USD",
                      "timeInForce":"DAY"
                    }
                """.trimIndent()
            )
            assertEquals(200, first.status)
            assertEquals(200, second.status)
            assertEquals(first.body, second.body)
            assertContains(second.body, "\"orderId\":\"ord-first\"")

            val hotPath = get(server.address.port, "/internal/perf/hot-path")
            assertEquals(200, hotPath.status)
            assertContains(hotPath.body, "\"api.mutation.total\"")
            assertContains(hotPath.body, "\"api.parse.submitOrder\"")
            assertContains(hotPath.body, "\"runtime.submitOrder.total\"")
            assertContains(hotPath.body, "\"api.writeResponse\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitCapturesReceivedAndCompletedLifecycle() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-capture-1"
                ),
                body = """
                    {
                      "commandId":"cmd-capture-1",
                      "traceId":"trace-capture-1",
                      "causationId":"",
                      "correlationId":"corr-capture-1",
                      "actorId":"bot-capture-1",
                      "occurredAt":"2026-05-22T00:00:00Z",
                      "orderId":"ord-capture-1",
                      "instrumentId":"AAPL",
                      "participantId":"participant-1",
                      "accountId":"account-1",
                      "side":"BUY",
                      "orderType":"LIMIT",
                      "quantityUnits":"100",
                      "limitPrice":"150250000000",
                      "currency":"USD",
                      "timeInForce":"DAY"
                    }
                """.trimIndent()
            )
            assertEquals(200, response.status)
            assertEquals(1, captureStore.receivedCalls)
            assertEquals(1, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
            assertEquals("cmd-capture-1", JsonCodec.parseObject(captureStore.lastReceivedPayload).string("commandId"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun legacyMutationRoutesRejectWhenInternalGateDisabled() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            legacyMutationRoutesEnabled = false
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/orders/submit",
                headers = mapOf("X-Reef-Internal-Route" to "true"),
                body = validSubmitBody("cmd-legacy-disabled", "trace-legacy-disabled", "ord-legacy-disabled")
            )
            val reference = post(
                port = server.address.port,
                path = "/reference/instruments",
                headers = mapOf("X-Reef-Internal-Route" to "true"),
                body = """{"instrumentId":"AAPL","symbol":"AAPL"}"""
            )
            val role = post(
                port = server.address.port,
                path = "/auth/roles",
                headers = mapOf("X-Reef-Internal-Route" to "true"),
                body = """{"roleId":"order_trader","permissions":"order.submit"}"""
            )

            assertEquals(403, submit.status)
            assertEquals(403, reference.status)
            assertEquals(403, role.status)
            assertContains(submit.body, "\"error\":\"legacy mutation route disabled\"")
            assertContains(reference.body, "\"error\":\"legacy mutation route disabled\"")
            assertContains(role.body, "\"error\":\"legacy mutation route disabled\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun workerRoleDoesNotExposePublicCommandIntake() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            runtimeRole = PlatformRuntimeRole.Worker
        )
        try {
            val internal = get(server.address.port, "/internal/stream-ack/worker/stats")
            val publicSubmit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-worker-role"
                ),
                body = validSubmitBody("cmd-worker-role", "trace-worker-role", "ord-worker-role", extra = streamRoutingExtra())
            )

            assertEquals(200, internal.status)
            assertEquals(404, publicSubmit.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun materializerRoleExposesOnlyInternalMaterializerStats() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            runtimeRole = PlatformRuntimeRole.Materializer,
            commandProcessingMode = CommandProcessingMode.StreamAck,
            venueEventMaterializerEnabled = true
        )
        try {
            val internal = get(server.address.port, "/internal/venue-event-materializer/stats")
            val publicSubmit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-materializer-role"
                ),
                body = validSubmitBody("cmd-materializer-role", "trace-materializer-role", "ord-materializer-role", extra = streamRoutingExtra())
            )

            assertEquals(200, internal.status)
            assertContains(internal.body, "\"role\":\"materializer\"")
            assertContains(internal.body, "\"source\":\"kafka\"")
            assertEquals(404, publicSubmit.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun legacyMutationRoutesRequireInternalMarkerWhenGateEnabled() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            legacyMutationRoutesEnabled = true
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/orders/submit",
                headers = emptyMap(),
                body = validSubmitBody("cmd-legacy-marker", "trace-legacy-marker", "ord-legacy-marker")
            )
            val reference = post(
                port = server.address.port,
                path = "/reference/instruments",
                headers = emptyMap(),
                body = """{"instrumentId":"AAPL","symbol":"AAPL"}"""
            )
            val role = post(
                port = server.address.port,
                path = "/auth/roles",
                headers = emptyMap(),
                body = """{"roleId":"order_trader","permissions":"order.submit"}"""
            )

            assertEquals(403, submit.status)
            assertEquals(403, reference.status)
            assertEquals(403, role.status)
            assertContains(submit.body, "\"header\":\"X-Reef-Internal-Route\"")
            assertContains(reference.body, "\"header\":\"X-Reef-Internal-Route\"")
            assertContains(role.body, "\"header\":\"X-Reef-Internal-Route\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun legacyMutationRoutesRejectRemoteSpoofedInternalMarkerWhenLocalOnly() {
        val persistence = InMemoryRuntimePersistence()
        seedOrderReferenceData(persistence)
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = EchoOrderEngineGateway(),
                runtimePersistence = persistence
            )
        )
        val server = PlatformHttpServer(
            port = 0,
            api = api,
            boundary = ExternalApiBoundary(),
            idempotencyStore = InMemoryIdempotencyStore(),
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            legacyMutationRoutesEnabled = true,
            internalHttpExposureMode = InternalHttpExposureMode.LocalOnly
        )
        val headers = Headers().apply {
            add("X-Reef-Internal-Route", "true")
        }

        val response = server.handleHotPathRequest(
            PlatformHotPathRequest(
                method = "POST",
                path = "/auth/roles",
                query = null,
                headers = headers,
                remoteAddress = "203.0.113.10",
                body = """{"roleId":"order_trader","permissions":"order.submit"}"""
            )
        )

        assertEquals(403, response?.status)
        assertContains(response?.body.orEmpty(), "\"error\":\"legacy mutation route requires loopback access\"")
    }

    @Test
    fun legacyMutationRoutesAllowRemoteInternalMarkerWhenExplicitlyEnabled() {
        val persistence = InMemoryRuntimePersistence()
        seedOrderReferenceData(persistence)
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = EchoOrderEngineGateway(),
                runtimePersistence = persistence
            )
        )
        val server = PlatformHttpServer(
            port = 0,
            api = api,
            boundary = ExternalApiBoundary(),
            idempotencyStore = InMemoryIdempotencyStore(),
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            legacyMutationRoutesEnabled = true,
            internalHttpExposureMode = InternalHttpExposureMode.Enabled
        )
        val headers = Headers().apply {
            add("X-Reef-Internal-Route", "true")
        }

        val response = server.handleHotPathRequest(
            PlatformHotPathRequest(
                method = "POST",
                path = "/auth/roles",
                query = null,
                headers = headers,
                remoteAddress = "203.0.113.10",
                body = """{"roleId":"order_trader","permissions":"order.submit"}"""
            )
        )

        assertEquals(200, response?.status)
        assertContains(response?.body.orEmpty(), "\"roleId\":\"order_trader\"")
    }

    @Test
    fun legacyMutationRoutesReturnNotFoundWhenInternalHttpDisabled() {
        val persistence = InMemoryRuntimePersistence()
        seedOrderReferenceData(persistence)
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = EchoOrderEngineGateway(),
                runtimePersistence = persistence
            )
        )
        val server = PlatformHttpServer(
            port = 0,
            api = api,
            boundary = ExternalApiBoundary(),
            idempotencyStore = InMemoryIdempotencyStore(),
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            legacyMutationRoutesEnabled = true,
            internalHttpExposureMode = InternalHttpExposureMode.Disabled
        )
        val headers = Headers().apply {
            add("X-Reef-Internal-Route", "true")
        }

        val response = server.handleHotPathRequest(
            PlatformHotPathRequest(
                method = "POST",
                path = "/auth/roles",
                query = null,
                headers = headers,
                remoteAddress = "127.0.0.1",
                body = """{"roleId":"order_trader","permissions":"order.submit"}"""
            )
        )

        assertEquals(404, response?.status)
        assertEquals("", response?.body)
    }

    @Test
    fun internalAuthSeedRoutesAllowExplicitOrderActors() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            seedOrderAuthorization = false
        )
        try {
            seedReferenceData(server.address.port)
            seedOrderRoleBindings(server.address.port, "bot-capture-1")

            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-auth-seeded"
                ),
                body = validSubmitBody("cmd-auth-seeded", "trace-auth-seeded", "ord-auth-seeded")
            )

            assertEquals(200, response.status)
            assertContains(response.body, "\"accepted\"")
            assertContains(response.body, "\"orderId\":\"ord-auth-seeded\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitRejectsParticipantAndAccountOutsideAuthenticatedScopeBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val boundary = ExternalApiBoundary(
            authHook = StaticTokenAuthHook(
                mapOf(
                    "client-1" to StaticTokenClientConfig(
                        token = "token-1",
                        principal = ExternalApiPrincipal(
                            clientId = "client-1",
                            participantIds = setOf("participant-1"),
                            accountIds = setOf("account-1")
                        )
                    )
                )
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            boundary = boundary,
            captureStore = captureStore
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Authorization" to "Bearer token-1",
                "Idempotency-Key" to "idem-scope-submit"
            )
            val participantSpoof = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers,
                body = validSubmitBody("cmd-scope-participant", "trace-scope-participant", "ord-scope-participant")
                    .replace("\"participantId\":\"participant-1\"", "\"participantId\":\"participant-2\"")
            )
            val accountSpoof = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers + ("Idempotency-Key" to "idem-scope-account"),
                body = validSubmitBody("cmd-scope-account", "trace-scope-account", "ord-scope-account")
                    .replace("\"accountId\":\"account-1\"", "\"accountId\":\"account-2\"")
            )

            assertEquals(403, participantSpoof.status)
            assertContains(participantSpoof.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
            assertContains(participantSpoof.body, "participant scope")
            assertEquals(403, accountSpoof.status)
            assertContains(accountSpoof.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
            assertContains(accountSpoof.body, "account scope")
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1OrderMutationsRejectActorOutsideAuthenticatedScopeBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val boundary = ExternalApiBoundary(
            authHook = StaticTokenAuthHook(
                mapOf(
                    "client-1" to StaticTokenClientConfig(
                        token = "token-1",
                        principal = ExternalApiPrincipal(
                            clientId = "client-1",
                            actorIds = setOf("bot-capture-1")
                        )
                    )
                )
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            boundary = boundary,
            captureStore = captureStore
        )
        try {
            val cases = listOf(
                "/api/v1/orders/submit" to validSubmitBody("cmd-scope-submit", "trace-scope-submit", "ord-scope-submit"),
                "/api/v1/orders/cancel" to validCancelBody("cmd-scope-cancel", "trace-scope-cancel", "ord-scope-cancel"),
                "/api/v1/orders/modify" to validModifyBody("cmd-scope-modify", "trace-scope-modify", "ord-scope-modify")
            )
            cases.forEachIndexed { index, (route, body) ->
                val response = post(
                    port = server.address.port,
                    path = route,
                    headers = mapOf(
                        "X-Client-Id" to "client-1",
                        "Authorization" to "Bearer token-1",
                        "Idempotency-Key" to "idem-scope-actor-$index"
                    ),
                    body = body.replace("\"actorId\":\"bot-capture-1\"", "\"actorId\":\"bot-capture-2\"")
                )

                assertEquals(403, response.status, route)
                assertContains(response.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
                assertContains(response.body, "actor scope")
            }
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1OrderMutationsRejectMalformedJsonBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            listOf(
                "/api/v1/orders/submit",
                "/api/v1/orders/cancel",
                "/api/v1/orders/modify"
            ).forEachIndexed { index, route ->
                val response = post(
                    port = server.address.port,
                    path = route,
                    headers = mapOf(
                        "X-Client-Id" to "client-1",
                        "Idempotency-Key" to "idem-malformed-$index",
                        "X-Correlation-Id" to "corr-malformed-$index"
                    ),
                    body = """{"commandId":"""
                )

                assertEquals(400, response.status)
                assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
                assertContains(response.body, "\"message\":\"invalid json payload\"")
                assertContains(response.body, "\"correlationId\":\"corr-malformed-$index\"")
            }
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1OrderMutationsRejectUnknownFieldsBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val cases = listOf(
                "/api/v1/orders/submit" to validSubmitBody("cmd-unknown-submit", "trace-unknown-submit", "ord-unknown-submit", extra = ""","unexpected":"value""""),
                "/api/v1/orders/cancel" to validCancelBody("cmd-unknown-cancel", "trace-unknown-cancel", "ord-unknown-cancel", extra = ""","unexpected":"value""""),
                "/api/v1/orders/modify" to validModifyBody("cmd-unknown-modify", "trace-unknown-modify", "ord-unknown-modify", extra = ""","unexpected":"value"""")
            )
            cases.forEachIndexed { index, (route, body) ->
                val response = post(
                    port = server.address.port,
                    path = route,
                    headers = mapOf(
                        "X-Client-Id" to "client-1",
                        "Idempotency-Key" to "idem-unknown-$index"
                    ),
                    body = body
                )

                assertEquals(400, response.status)
                assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
                assertContains(response.body, "\"message\":\"unknown field: unexpected\"")
            }
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1OrderMutationsRejectMissingRequiredFieldsBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val cases = listOf(
                "/api/v1/orders/submit" to bodyWithoutField(validSubmitBody("cmd-missing-submit", "trace-missing-submit", "ord-missing-submit"), "orderId"),
                "/api/v1/orders/cancel" to bodyWithoutField(validCancelBody("cmd-missing-cancel", "trace-missing-cancel", "ord-missing-cancel"), "reason"),
                "/api/v1/orders/modify" to bodyWithoutField(validModifyBody("cmd-missing-modify", "trace-missing-modify", "ord-missing-modify"), "limitPrice")
            )
            cases.forEachIndexed { index, (route, body) ->
                val response = post(
                    port = server.address.port,
                    path = route,
                    headers = mapOf(
                        "X-Client-Id" to "client-1",
                        "Idempotency-Key" to "idem-missing-$index"
                    ),
                    body = body
                )

                assertEquals(400, response.status, route)
                assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
                assertContains(response.body, "\"message\":\"missing required field:")
            }
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitRejectsInvalidEnumValuesBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-invalid-enum"
                ),
                body = validSubmitBody("cmd-invalid-enum", "trace-invalid-enum", "ord-invalid-enum")
                    .replace("\"side\":\"BUY\"", "\"side\":\"BID\"")
            )

            assertEquals(400, response.status)
            assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
            assertContains(response.body, "\"message\":\"invalid side: BID\"")
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitRejectsUnauthorizedActorBeforeEngineCall() {
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            seedOrderAuthorization = false
        )
        try {
            seedReferenceData(server.address.port)
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-unauthorized"
                ),
                body = validSubmitBody("cmd-unauthorized", "trace-unauthorized", "ord-unauthorized")
            )

            assertEquals(200, response.status)
            assertContains(response.body, "\"rejected\"")
            assertContains(response.body, "\"code\":\"AUTHORIZATION_ERROR\"")
            assertContains(response.body, "\"reason\":\"actorId missing permission order.submit\"")
            assertEquals(0, gateway.submitCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitReturnsRetryableErrorWhenEngineTransportFails() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = ThrowingEngineGateway(),
            captureStore = captureStore
        )
        try {
            seedReferenceData(server.address.port)
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-engine-down"
                ),
                body = validSubmitBody("cmd-engine-down", "trace-engine-down", "ord-engine-down")
            )

            assertEquals(503, response.status)
            assertContains(response.body, "\"error\":\"runtime unavailable\"")
            assertFalse(response.body.contains("\"rejected\""))
            assertEquals(1, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(1, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusEndpointReturnsCapturedCommandState() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-status-1"
                ),
                body = validSubmitBody("cmd-status-1", "trace-status-1", "ord-status-1")
            )
            val status = get(server.address.port, "/api/v1/commands/cmd-status-1", headers = apiReadHeaders())

            assertEquals(200, response.status)
            assertEquals(200, status.status)
            assertContains(status.body, "\"commandId\":\"cmd-status-1\"")
            assertContains(status.body, "\"status\":\"COMPLETED\"")
            assertContains(status.body, "\"internalStatus\":\"COMPLETED\"")
            assertContains(status.body, "\"processingMode\":\"captured-sync-engine\"")
            assertContains(status.body, "\"responseStatus\":200")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusEndpointPrefersCanonicalCommandOutcome() {
        val persistence = InMemoryRuntimePersistence()
        persistence.materializeVenueEventBatch(
            venueEventBatch(
                batchId = "batch-status-canonical",
                commandId = "cmd-status-canonical",
                resultStatus = "accepted"
            )
        )
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine,
            runtimePersistence = persistence
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-canonical"
                ),
                body = validSubmitBody("cmd-status-canonical", "trace-status-canonical", "ord-cmd-status-canonical")
            )
            val status = get(server.address.port, "/api/v1/commands/cmd-status-canonical", headers = apiReadHeaders())

            assertEquals(200, submit.status)
            assertEquals(200, status.status)
            assertContains(status.body, "\"commandId\":\"cmd-status-canonical\"")
            assertContains(status.body, "\"status\":\"COMPLETED\"")
            assertContains(status.body, "\"internalStatus\":\"COMPLETED\"")
            assertContains(status.body, "\"processingMode\":\"stream-ack\"")
            assertContains(status.body, "\"canonicalMaterialized\":true")
            assertContains(status.body, "\"batchId\":\"batch-status-canonical\"")
            assertContains(status.body, "\"resultStatus\":\"accepted\"")
            assertContains(status.body, "\"commandType\":\"SubmitOrder\"")
            assertContains(status.body, "\"instrumentId\":\"AAPL\"")
            assertContains(status.body, "\"participantId\":\"participant-1\"")
            assertContains(status.body, "\"orderId\":\"ord-cmd-status-canonical\"")
            assertContains(status.body, "\"clientId\":\"client-1\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusEndpointReturnsCompactCanonicalCommandResult() {
        val persistence = InMemoryRuntimePersistence()
        persistence.appendCanonicalSubmitOutcomes(
            listOf(
                CanonicalSubmitOutcome(
                    runId = "run-status-compact",
                    venueSessionId = "session-status-compact",
                    partitionId = 7,
                    partitionSequence = 42L,
                    streamName = "stream-status-compact",
                    streamSequence = 12L,
                    commandId = "cmd-status-compact",
                    idempotencyKey = "idem-status-compact",
                    payloadHash = "hash-status-compact",
                    instrumentId = "MSFT",
                    commandType = "SubmitOrder",
                    resultStatus = "accepted",
                    rejectCode = "",
                    acceptedAt = "2026-07-04T14:30:00.000Z",
                    completedAt = "2026-07-04T14:30:00.000Z",
                    engineShardId = "shard-compact",
                    outcome = PersistableSubmitOutcome(
                        commandId = "cmd-status-compact",
                        result = SubmitOrderResult(
                            accepted = EngineOrderAccepted(
                                eventId = "evt-status-compact",
                                orderId = "ord-status-compact",
                                engineOrderId = "eng-status-compact",
                                occurredAt = "2026-07-04T14:30:00.000Z"
                            )
                        ),
                        acceptedOrder = PersistedOrder(
                            orderId = "ord-status-compact",
                            engineOrderId = "eng-status-compact",
                            instrumentId = "MSFT",
                            participantId = "participant-compact",
                            accountId = "account-compact",
                            side = "BUY",
                            orderType = "LIMIT",
                            quantityUnits = "1",
                            limitPrice = "100000000",
                            currency = "USD",
                            timeInForce = "DAY",
                            acceptedAt = "2026-07-04T14:30:00.000Z"
                        ),
                        lifecycleEvents = emptyList()
                    )
                )
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = NoopCommandCaptureStore(),
            runtimePersistence = persistence
        )
        try {
            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-status-compact",
                headers = apiReadHeaders(participantId = "participant-compact")
            )

            assertEquals(200, status.status)
            assertContains(status.body, "\"commandId\":\"cmd-status-compact\"")
            assertContains(status.body, "\"status\":\"COMPLETED\"")
            assertContains(status.body, "\"internalStatus\":\"COMPLETED\"")
            assertContains(status.body, "\"processingMode\":\"stream-ack\"")
            assertContains(status.body, "\"canonicalMaterialized\":true")
            assertContains(status.body, "\"resultStatus\":\"accepted\"")
            assertContains(status.body, "\"source\":\"canonical_result\"")
            assertContains(status.body, "\"partition\":7")
            assertContains(status.body, "\"streamSequence\":12")
            assertContains(status.body, "\"commandStream\":\"stream-status-compact\"")
            assertContains(status.body, "\"shardId\":\"shard-compact\"")
            assertContains(status.body, "\"instrumentId\":\"MSFT\"")
            assertContains(status.body, "\"participantId\":\"participant-compact\"")
            assertContains(status.body, "\"orderId\":\"ord-status-compact\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusEndpointReturnsEventPublishedForDurableVenueEventBatchBeforeCanonicalOutcome() {
        val persistence = InMemoryRuntimePersistence()
        persistence.recordVenueEventBatch(
            venueEventBatch(
                batchId = "batch-status-event-published",
                commandId = "cmd-status-event-published",
                resultStatus = "accepted"
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = NoopCommandCaptureStore(),
            runtimePersistence = persistence
        )
        try {
            val status = get(server.address.port, "/api/v1/commands/cmd-status-event-published", headers = apiReadHeaders())

            assertEquals(200, status.status)
            assertContains(status.body, "\"commandId\":\"cmd-status-event-published\"")
            assertContains(status.body, "\"status\":\"EVENT_PUBLISHED\"")
            assertContains(status.body, "\"internalStatus\":\"PROCESSING\"")
            assertContains(status.body, "\"processingMode\":\"stream-ack\"")
            assertContains(status.body, "\"responseStatus\":202")
            assertContains(status.body, "\"canonicalMaterialized\":false")
            assertContains(status.body, "\"batchId\":\"batch-status-event-published\"")
            assertContains(status.body, "\"resultStatus\":\"accepted\"")
            assertContains(status.body, "\"commandType\":\"SubmitOrder\"")
            assertContains(status.body, "\"source\":\"event_batch\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusRejectsMismatchedReadPrincipal() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-status-scope"
                ),
                body = validSubmitBody("cmd-status-scope", "trace-status-scope", "ord-status-scope")
            )
            val deniedClient = get(
                server.address.port,
                "/api/v1/commands/cmd-status-scope",
                headers = apiReadHeaders(clientId = "client-2")
            )
            val deniedParticipant = get(
                server.address.port,
                "/api/v1/commands/cmd-status-scope",
                headers = apiReadHeaders(participantId = "participant-2")
            )
            val missingParticipant = get(
                server.address.port,
                "/api/v1/commands/cmd-status-scope",
                headers = mapOf("X-Client-Id" to "client-1")
            )

            assertEquals(200, submit.status)
            assertEquals(403, deniedClient.status)
            assertContains(deniedClient.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
            assertEquals(403, deniedParticipant.status)
            assertContains(deniedParticipant.body, "\"code\":\"OBJECT_AUTH_DENIED\"")
            assertEquals(403, missingParticipant.status)
            assertContains(missingParticipant.body, "\"code\":\"OBJECT_AUTH_REQUIRED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusUsesStreamReferenceScope() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-status-stream-scope"
                ),
                body = validSubmitBody("cmd-status-stream-scope", "trace-status-stream-scope", "ord-status-stream-scope", extra = streamRoutingExtra())
            )
            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-status-stream-scope",
                headers = apiReadHeaders()
            )

            assertEquals(202, submit.status)
            assertEquals(200, status.status, status.body)
            assertContains(status.body, "\"status\":\"ACCEPTED\"")
            assertContains(status.body, "\"clientId\":\"client-1\"")
            assertContains(status.body, "\"participantId\":\"participant-1\"")
            assertContains(status.body, "\"source\":\"stream_reference\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusUsesHeaderScopeForStreamCancelReference() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.StreamAck
        )
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val extra = ""","instrumentId":"AAPL"${streamRoutingExtra()}"""
            val cancel = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "X-Participant-Id" to "participant-1",
                    "Idempotency-Key" to "idem-status-stream-cancel-scope"
                ),
                body = validCancelBody("cmd-status-stream-cancel-scope", "trace-status-stream-cancel-scope", "ord-status-stream-cancel-scope", extra = extra)
            )
            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-status-stream-cancel-scope",
                headers = apiReadHeaders()
            )

            assertEquals(202, cancel.status)
            assertEquals(200, status.status, status.body)
            assertContains(status.body, "\"status\":\"ACCEPTED\"")
            assertContains(status.body, "\"commandType\":\"CancelOrder\"")
            assertContains(status.body, "\"participantId\":\"participant-1\"")
            assertContains(status.body, "\"source\":\"stream_reference\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusUsesStreamReferenceScopeForCanonicalCancelOutcome() {
        val persistence = InMemoryRuntimePersistence()
        persistence.materializeVenueEventBatch(
            venueEventBatch(
                batchId = "batch-status-stream-cancel-canonical",
                commandId = "cmd-status-stream-cancel-canonical",
                resultStatus = "accepted",
                commandType = "CancelOrder",
                orderId = "ord-status-stream-cancel-canonical",
                resultPayloadJson = """{"resultType":"accepted","acceptedOrder":null,"commandId":"cmd-status-stream-cancel-canonical","orderId":"ord-status-stream-cancel-canonical"}"""
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore().also { intake ->
                val envelope = when (val envelopeResult = StreamCommandEnvelopeBuilder.fromRequest(
                    clientId = "client-1",
                    participantId = "participant-1",
                    route = "/api/v1/orders/cancel",
                    idempotencyKey = "idem-status-stream-cancel-canonical",
                    body = validCancelBody(
                        "cmd-status-stream-cancel-canonical",
                        "trace-status-stream-cancel-canonical",
                        "ord-status-stream-cancel-canonical",
                        extra = ""","instrumentId":"AAPL"${streamRoutingExtra()}"""
                    )
                )) {
                    is EitherBoundaryError.Envelope -> envelopeResult.envelope
                    is EitherBoundaryError.Error -> error(envelopeResult.error.message)
                }
                val reference = envelope.reference("REEF_COMMANDS", streamSequence = 7001)
                assertTrue(intake.reserve(envelope, reference) is StreamCommandReservation.Reserved)
                assertTrue(intake.markPublished(envelope.scope, envelope.idempotencyKey, 7001))
            },
            streamCommandPublisher = RecordingStreamCommandPublisher(),
            runtimePersistence = persistence
        )
        try {
            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-status-stream-cancel-canonical",
                headers = apiReadHeaders()
            )

            assertEquals(200, status.status, status.body)
            assertContains(status.body, "\"status\":\"COMPLETED\"")
            assertContains(status.body, "\"commandType\":\"CancelOrder\"")
            assertContains(status.body, "\"canonicalMaterialized\":true")
            assertContains(status.body, "\"participantId\":\"participant-1\"")
            assertContains(status.body, "\"clientId\":\"client-1\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusUsesAuthenticatedTokenScopeForParticipantStatus() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val boundary = ExternalApiBoundary(
            authHook = StaticTokenAuthHook(
                mapOf(
                    "client-1" to StaticTokenClientConfig(
                        token = "token-1",
                        principal = ExternalApiPrincipal(
                            clientId = "client-1",
                            participantIds = setOf("participant-1")
                        )
                    )
                )
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            boundary = boundary,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Authorization" to "Bearer token-1",
                    "Idempotency-Key" to "idem-status-token-scope"
                ),
                body = validSubmitBody("cmd-status-token-scope", "trace-status-token-scope", "ord-status-token-scope")
            )
            val allowed = get(
                server.address.port,
                "/api/v1/commands/cmd-status-token-scope",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Authorization" to "Bearer token-1"
                )
            )
            val denied = get(
                server.address.port,
                "/api/v1/commands/cmd-status-token-scope",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Authorization" to "Bearer token-1",
                    "X-Participant-Id" to "participant-2"
                )
            )

            assertEquals(200, submit.status)
            assertEquals(200, allowed.status)
            assertContains(allowed.body, "\"commandId\":\"cmd-status-token-scope\"")
            assertEquals(200, denied.status)
            assertContains(denied.body, "\"commandId\":\"cmd-status-token-scope\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusEndpointExposesCanonicalRejectMetadata() {
        val persistence = InMemoryRuntimePersistence()
        persistence.materializeVenueEventBatch(
            venueEventBatch(
                batchId = "batch-status-rejected",
                commandId = "cmd-status-rejected",
                resultStatus = "rejected",
                rejectCode = "ORDER_NOT_FOUND"
            )
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = NoopCommandCaptureStore(),
            runtimePersistence = persistence
        )
        try {
            val status = get(server.address.port, "/api/v1/commands/cmd-status-rejected", headers = apiReadHeaders())

            assertEquals(200, status.status)
            assertContains(status.body, "\"status\":\"REJECTED\"")
            assertContains(status.body, "\"internalStatus\":\"COMPLETED\"")
            assertContains(status.body, "\"canonicalMaterialized\":true")
            assertContains(status.body, "\"resultStatus\":\"rejected\"")
            assertContains(status.body, "\"rejectCode\":\"ORDER_NOT_FOUND\"")
            assertContains(status.body, "\"responseStatus\":422")
            assertContains(status.body, "\"resultPayloadJson\":\"{\\\"rejected\\\":{\\\"code\\\":\\\"ORDER_NOT_FOUND\\\",\\\"participantId\\\":\\\"participant-1\\\",\\\"orderId\\\":\\\"ord-cmd-status-rejected\\\"}}\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancelByClientOrderResolvesAndCancelsUnderlyingOrder() {
        val persistence = InMemoryRuntimePersistence()
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "ord-resolved-1",
                engineOrderId = "eng-ord-resolved-1",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "100000000000",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-07-06T00:00:00Z",
                clientOrderId = "co-1",
                runId = "run-1",
                venueSessionId = "session-1"
            )
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            runtimePersistence = persistence
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel-by-client-order",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-cancel-by-client-order-1"
                ),
                body = JsonCodec.writeObject(
                    "commandId" to "cmd-cancel-by-client-order-1",
                    "traceId" to "trace-cancel-by-client-order-1",
                    "correlationId" to "corr-cancel-by-client-order-1",
                    "causationId" to "cause-cancel-by-client-order-1",
                    "actorId" to "bot-1",
                    "participantId" to "participant-1",
                    "clientOrderId" to "co-1",
                    "reason" to "test cancel"
                )
            )

            assertEquals(200, response.status)
            assertContains(response.body, "\"orderId\":\"ord-resolved-1\"")
            assertEquals(1, gateway.cancelCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancelByClientOrderAuthenticatesBeforeClientOrderLookup() {
        val persistence = InMemoryRuntimePersistence()
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "ord-auth-before-lookup",
                engineOrderId = "eng-ord-auth-before-lookup",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "100000000000",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-07-06T00:00:00Z",
                clientOrderId = "co-auth-before-lookup",
                runId = "run-1",
                venueSessionId = "session-1"
            )
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            boundary = ExternalApiBoundary(
                authHook = StaticTokenAuthHook(
                    mapOf(
                        "client-1" to StaticTokenClientConfig(
                            token = "token-1",
                            principal = ExternalApiPrincipal(
                                clientId = "client-1",
                                actorIds = setOf("bot-1"),
                                participantIds = setOf("participant-1"),
                                accountIds = setOf("account-1")
                            )
                        )
                    )
                )
            ),
            runtimePersistence = persistence
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel-by-client-order",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Authorization" to "Bearer wrong",
                    "Idempotency-Key" to "idem-cancel-by-client-order-auth"
                ),
                body = JsonCodec.writeObject(
                    "commandId" to "cmd-cancel-by-client-order-auth",
                    "traceId" to "trace-cancel-by-client-order-auth",
                    "correlationId" to "corr-cancel-by-client-order-auth",
                    "actorId" to "bot-1",
                    "participantId" to "participant-1",
                    "clientOrderId" to "co-auth-before-lookup",
                    "reason" to "test cancel"
                )
            )

            assertEquals(401, response.status)
            assertContains(response.body, "\"code\":\"UNAUTHORIZED\"")
            assertEquals(0, gateway.cancelCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancelByClientOrderReturns404WhenClientOrderUnknown() {
        val server = testServerWithGateway(gateway = EchoOrderEngineGateway())
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel-by-client-order",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-cancel-by-client-order-missing"
                ),
                body = JsonCodec.writeObject(
                    "commandId" to "cmd-cancel-by-client-order-missing",
                    "traceId" to "trace-cancel-by-client-order-missing",
                    "correlationId" to "corr-cancel-by-client-order-missing",
                    "actorId" to "bot-1",
                    "participantId" to "participant-1",
                    "clientOrderId" to "co-unknown",
                    "reason" to "test cancel"
                )
            )

            assertEquals(404, response.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancelByClientOrderReturns400WhenJsonMalformed() {
        val server = testServerWithGateway(gateway = EchoOrderEngineGateway())
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel-by-client-order",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-cancel-by-client-order-malformed"
                ),
                body = """{"participantId":"""
            )

            assertEquals(400, response.status)
            assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
            assertContains(response.body, "invalid json payload")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancelByClientOrderReturns400WhenMissingRequiredFields() {
        val server = testServerWithGateway(gateway = EchoOrderEngineGateway())
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel-by-client-order",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-cancel-by-client-order-invalid"
                ),
                body = JsonCodec.writeObject(
                    "commandId" to "cmd-cancel-by-client-order-invalid",
                    "actorId" to "bot-1",
                    "participantId" to "participant-1"
                )
            )

            assertEquals(400, response.status)
            assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
            assertContains(response.body, "missing required field: clientOrderId")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedSyncEngineReturnsSynchronousResultAndCompletesCommandStatus() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-sync-engine-1"
                ),
                body = validSubmitBody("cmd-sync-engine-1", "trace-sync-engine-1", "ord-sync-engine-1")
            )
            val record = commandLogStore.findByCommandId("cmd-sync-engine-1")

            assertEquals(200, response.status)
            assertContains(response.body, "\"orderId\":\"ord-sync-engine-1\"")
            assertEquals(CommandLogStatus.COMPLETED, record?.status)
            assertEquals(1, record?.attemptCount)
            assertEquals(200, record?.responseStatus)
            assertTrue(record?.responsePayloadJson?.contains("ord-sync-engine-1") == true)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedSyncEngineReplaysCompletedCommandForDuplicateCommandId() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        try {
            seedReferenceData(server.address.port)
            val first = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-duplicate-command-first"
                ),
                body = validSubmitBody("cmd-duplicate-command", "trace-duplicate-command-first", "ord-duplicate-first")
            )
            val second = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-duplicate-command-second"
                ),
                body = validSubmitBody("cmd-duplicate-command", "trace-duplicate-command-second", "ord-duplicate-second")
            )

            assertEquals(200, first.status)
            assertEquals(200, second.status)
            assertContains(second.body, "\"orderId\":\"ord-duplicate-first\"")
            assertEquals(1, gateway.submitCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedSyncEngineRejectsDuplicateInFlightCommandBeforeSecondEngineCall() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val gateway = BlockingFirstSubmitGateway()
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedSyncEngine
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            seedReferenceData(server.address.port)
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-inflight-duplicate"
            )
            val first = executor.submit<HttpResponse> {
                post(
                    port = server.address.port,
                    path = "/api/v1/orders/submit",
                    headers = headers,
                    body = validSubmitBody("cmd-inflight-duplicate", "trace-inflight-first", "ord-inflight-first")
                )
            }
            assertTrue(gateway.awaitFirstSubmit())

            val duplicate = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers,
                body = validSubmitBody("cmd-inflight-duplicate", "trace-inflight-second", "ord-inflight-second")
            )

            assertEquals(409, duplicate.status)
            assertContains(duplicate.body, "\"code\":\"COMMAND_ALREADY_IN_PROGRESS\"")
            assertEquals(1, gateway.submitCalls)

            gateway.release()
            assertEquals(200, first.get(5, TimeUnit.SECONDS).status)
        } finally {
            gateway.release()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun syncResultRejectsDuplicateInFlightMutationsBeforeSecondEngineCall() {
        val cases = listOf(
            Triple(
                "/api/v1/orders/submit",
                validSubmitBody("cmd-sync-inflight-submit-first", "trace-sync-inflight-submit-first", "ord-sync-inflight-submit-first"),
                validSubmitBody("cmd-sync-inflight-submit-second", "trace-sync-inflight-submit-second", "ord-sync-inflight-submit-second")
            ),
            Triple(
                "/api/v1/orders/cancel",
                validCancelBody("cmd-sync-inflight-cancel-first", "trace-sync-inflight-cancel-first", "ord-sync-inflight-cancel-first"),
                validCancelBody("cmd-sync-inflight-cancel-second", "trace-sync-inflight-cancel-second", "ord-sync-inflight-cancel-second")
            ),
            Triple(
                "/api/v1/orders/modify",
                validModifyBody("cmd-sync-inflight-modify-first", "trace-sync-inflight-modify-first", "ord-sync-inflight-modify-first"),
                validModifyBody("cmd-sync-inflight-modify-second", "trace-sync-inflight-modify-second", "ord-sync-inflight-modify-second")
            )
        )
        cases.forEachIndexed { index, (route, firstBody, secondBody) ->
            val gateway = BlockingFirstSubmitGateway()
            val server = testServerWithGateway(
                gateway = gateway,
                captureStore = InMemoryCommandCaptureStore()
            )
            val executor = Executors.newSingleThreadExecutor()
            try {
                if (route.endsWith("/submit")) {
                    seedReferenceData(server.address.port)
                }
                val headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-sync-inflight-duplicate-$index"
                )
                val first = executor.submit<HttpResponse> {
                    post(
                        port = server.address.port,
                        path = route,
                        headers = headers,
                        body = firstBody
                    )
                }
                assertTrue(gateway.awaitFirstSubmit())

                val duplicate = post(
                    port = server.address.port,
                    path = route,
                    headers = headers,
                    body = secondBody
                )

                assertEquals(409, duplicate.status)
                assertContains(duplicate.body, "\"code\":\"COMMAND_ALREADY_IN_PROGRESS\"")
                assertEquals(1, gateway.submitCalls)

                gateway.release()
                assertEquals(200, first.get(5, TimeUnit.SECONDS).status)
            } finally {
                gateway.release()
                executor.shutdownNow()
                server.stop(0)
            }
        }
    }

    @Test
    fun capturedAckReturnsAcceptedCommandWithoutExecutingEngine() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-ack-1"
                ),
                body = validSubmitBody("cmd-ack-1", "trace-ack-1", "ord-ack-1")
            )
            val status = get(server.address.port, "/api/v1/commands/cmd-ack-1", headers = apiReadHeaders())

            assertEquals(202, response.status)
            assertContains(response.body, "\"commandId\":\"cmd-ack-1\"")
            assertContains(response.body, "\"status\":\"ACCEPTED\"")
            assertContains(response.body, "\"processingMode\":\"captured-ack\"")
            assertContains(response.body, "\"statusUrl\":\"/api/v1/commands/cmd-ack-1\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(200, status.status)
            assertContains(status.body, "\"status\":\"ACCEPTED\"")
            assertContains(status.body, "\"internalStatus\":\"RECEIVED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedAckRiskRejectsBeforeCommandLogAppend() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            accountRiskCheck = StaticAccountRiskCheck(rejectedAccounts = setOf("account-1"))
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-risk-reject",
                    "Idempotency-Key" to "idem-risk-reject"
                ),
                body = validSubmitBody("cmd-risk-reject", "trace-risk-reject", "ord-risk-reject")
            )
            val status = get(server.address.port, "/api/v1/commands/cmd-risk-reject", headers = apiReadHeaders("client-risk-reject"))

            assertEquals(403, response.status)
            assertContains(response.body, "\"code\":\"ACCOUNT_RISK_REJECTED\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(404, status.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedAckAccountRiskReceivesSubmitEconomicsBeforeCommandLogAppend() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val accountRiskCheck = object : AccountRiskCheck {
            var lastRequest: AccountRiskCheckRequest? = null

            override fun evaluate(request: AccountRiskCheckRequest): AccountRiskCheckResult {
                lastRequest = request
                return AccountRiskCheckResult(
                    decision = AccountRiskDecision.REJECT,
                    code = "ACCOUNT_RISK_MAX_NOTIONAL_EXCEEDED",
                    message = "max notional exceeded"
                )
            }
        }
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            accountRiskCheck = accountRiskCheck
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-risk-limit",
                    "Idempotency-Key" to "idem-risk-limit"
                ),
                body = validSubmitBody("cmd-risk-limit", "trace-risk-limit", "ord-risk-limit")
            )
            val status = get(server.address.port, "/api/v1/commands/cmd-risk-limit", headers = apiReadHeaders("client-risk-limit"))

            assertEquals(403, response.status)
            assertContains(response.body, "\"code\":\"ACCOUNT_RISK_MAX_NOTIONAL_EXCEEDED\"")
            assertEquals("100", accountRiskCheck.lastRequest?.quantityUnits)
            assertEquals("150250000000", accountRiskCheck.lastRequest?.limitPrice)
            assertEquals("USD", accountRiskCheck.lastRequest?.currency)
            assertEquals(0, gateway.submitCalls)
            assertEquals(404, status.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedAckCircuitBreakerRejectsBeforeCommandLogAppend() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            commandCircuitBreakerCheck = StaticCircuitBreakerCheck("AAPL")
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-breaker-reject",
                    "Idempotency-Key" to "idem-breaker-reject"
                ),
                body = validSubmitBody("cmd-breaker-reject", "trace-breaker-reject", "ord-breaker-reject")
            )
            val status = get(server.address.port, "/api/v1/commands/cmd-breaker-reject", headers = apiReadHeaders("client-breaker-reject"))

            assertEquals(503, response.status)
            assertContains(response.body, "\"code\":\"COMMAND_CIRCUIT_BREAKER_TRIPPED\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(404, status.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun boundaryControlDiagnosticsExposeControlsDecisionsAndBreakers() {
        val accountRiskStore = RecordingAccountRiskStore()
        accountRiskStore.upsertControl("BOT", "bot-1", AccountRiskDecision.DISABLED_BOT, "disabled", "100", "15025000000000", "USD")
        accountRiskStore.decisions.add(
            AccountRiskDecisionAudit(
                decisionId = "risk-decision-1",
                decidedAt = "2026-07-04T12:00:00Z",
                decision = AccountRiskDecision.DISABLED_BOT,
                code = "BOT_DISABLED",
                message = "bot is disabled",
                clientId = "client-1",
                route = "/api/v1/orders/submit",
                commandType = "SubmitOrder",
                commandId = "cmd-risk",
                correlationId = "corr-risk",
                actorId = "bot-1",
                participantId = "participant-1",
                accountId = "account-1",
                botId = "bot-1",
                venueSessionId = "session-1",
                instrumentId = "AAPL",
                orderId = "ord-risk",
                quantityUnits = "101",
                limitPrice = "150250000000",
                currency = "USD"
            )
        )
        val breakerStore = RecordingCommandCircuitBreakerStore()
        breakerStore.setBreaker("INSTRUMENT", "AAPL", true, "halted")
        val collarStore = RecordingInstrumentPriceCollarStore()
        collarStore.setCollar("AAPL", "150000000000", "151000000000", "USD", "regular band")
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            accountRiskCheck = accountRiskStore,
            accountRiskControlStore = accountRiskStore,
            accountRiskDecisionLog = accountRiskStore,
            commandCircuitBreakerCheck = breakerStore,
            commandCircuitBreakerStore = breakerStore,
            instrumentPriceCollarCheck = collarStore,
            instrumentPriceCollarStore = collarStore
        )
        try {
            val controls = get(server.address.port, "/internal/boundary/account-risk/controls")
            val decisions = get(server.address.port, "/internal/boundary/account-risk/decisions/recent?limit=10")
            val breakers = get(server.address.port, "/internal/boundary/circuit-breakers")
            val collars = get(server.address.port, "/internal/boundary/price-collars")

            assertEquals(200, controls.status)
            assertContains(controls.body, "\"controlsCount\":1")
            assertContains(controls.body, "\"scopeType\":\"BOT\"")
            assertContains(controls.body, "\"decision\":\"DISABLED_BOT\"")
            assertContains(controls.body, "\"maxQuantityUnits\":\"100\"")
            assertContains(controls.body, "\"maxNotional\":\"15025000000000\"")
            assertContains(controls.body, "\"currency\":\"USD\"")
            assertEquals(200, decisions.status)
            assertContains(decisions.body, "\"decisionsCount\":1")
            assertContains(decisions.body, "\"code\":\"BOT_DISABLED\"")
            assertContains(decisions.body, "\"commandId\":\"cmd-risk\"")
            assertContains(decisions.body, "\"quantityUnits\":\"101\"")
            assertEquals(200, breakers.status)
            assertContains(breakers.body, "\"breakersCount\":1")
            assertContains(breakers.body, "\"scopeType\":\"INSTRUMENT\"")
            assertContains(breakers.body, "\"tripped\":true")
            assertEquals(200, collars.status)
            assertContains(collars.body, "\"collarsCount\":1")
            assertContains(collars.body, "\"instrumentId\":\"AAPL\"")
            assertContains(collars.body, "\"minPrice\":\"150000000000\"")
            assertContains(collars.body, "\"maxPrice\":\"151000000000\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun internalAdminAccountRiskEndpointSetsControlAndAuditsChange() {
        val accountRiskStore = RecordingAccountRiskStore()
        val persistence = InMemoryRuntimePersistence()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            accountRiskCheck = accountRiskStore,
            runtimePersistence = persistence
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/internal/admin/account-risk/controls",
                headers = mapOf("X-Reef-Actor-Id" to "ops-1", "X-Correlation-Id" to "corr-admin-risk"),
                body = """
                    {
                      "scopeType":"bot",
                      "scopeId":"bot-1",
                      "decision":"disabled-bot",
                      "reason":"operator disabled",
                      "maxQuantityUnits":"100",
                      "maxNotional":"15025000000000",
                      "currency":"usd",
                      "actorId":"spoofed-ops",
                      "correlationId":"spoofed-corr"
                    }
                """.trimIndent()
            )
            val controls = get(server.address.port, "/internal/boundary/account-risk/controls")
            val auditEvents = persistence.eventsForTrace("admin:ops-1")

            assertEquals(200, response.status)
            assertContains(response.body, "\"status\":\"ok\"")
            assertContains(response.body, "\"decision\":\"DISABLED_BOT\"")
            assertContains(response.body, "\"maxQuantityUnits\":\"100\"")
            assertContains(response.body, "\"maxNotional\":\"15025000000000\"")
            assertContains(response.body, "\"currency\":\"USD\"")
            assertContains(controls.body, "\"scopeType\":\"BOT\"")
            assertContains(controls.body, "\"reason\":\"operator disabled\"")
            assertEquals(1, auditEvents.size)
            assertEquals("AccountRiskControlChanged", auditEvents.single().eventType)
            assertContains(auditEvents.single().payloadJson, "\"previousDecision\":\"\"")
            assertContains(auditEvents.single().payloadJson, "\"decision\":\"DISABLED_BOT\"")
            assertContains(auditEvents.single().payloadJson, "\"maxQuantityUnits\":\"100\"")
            assertContains(auditEvents.single().payloadJson, "\"maxNotional\":\"15025000000000\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1ProjectionMutationEndpointsRequireApiWriteBoundary() {
        val server = testServerWithGateway(StaticAcceptedEngineGateway())
        try {
            val lifecycleDenied = post(
                server.address.port,
                "/api/v1/orders/lifecycle-state",
                emptyMap(),
                ""
            )
            val snapshotsDenied = post(
                server.address.port,
                "/api/v1/market-data/snapshots",
                emptyMap(),
                ""
            )
            val lifecycleAllowed = post(
                server.address.port,
                "/api/v1/orders/lifecycle-state",
                mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-lifecycle-rebuild"
                ),
                ""
            )
            val snapshotsAllowed = post(
                server.address.port,
                "/api/v1/market-data/snapshots",
                mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-snapshot-refresh"
                ),
                ""
            )

            assertEquals(401, lifecycleDenied.status)
            assertContains(lifecycleDenied.body, "\"code\":\"CLIENT_ID_REQUIRED\"")
            assertEquals(401, snapshotsDenied.status)
            assertContains(snapshotsDenied.body, "\"code\":\"CLIENT_ID_REQUIRED\"")
            assertEquals(200, lifecycleAllowed.status)
            assertEquals(200, snapshotsAllowed.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementFactsEndpointAppendsAndReadsP2FactsByScenarioRunId() {
        val settlementStore = InMemorySettlementFactStore()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore
        )
        try {
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                p2SettlementFactsBody("p2-run-api")
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-run-api")

            assertEquals(200, posted.status)
            assertContains(posted.body, "\"status\":\"ok\"")
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"scenarioRunId\":\"p2-run-api\"")
            assertContains(fetched.body, "\"settlementObligationId\":\"obl-1\"")
            assertContains(fetched.body, "\"settlementInstructionId\":\"instruction-1\"")
            assertContains(fetched.body, "\"settlementAttemptId\":\"attempt-1\"")
            assertContains(fetched.body, "\"postTradeProfileId\":\"instant-post-trade-v1\"")
            assertContains(fetched.body, "\"postTradePolicyVersion\":2")
            assertContains(fetched.body, "\"reason\":\"CASH_LEG_FAILED\"")
            assertContains(fetched.body, "\"repairAction\":\"POST_CASH_LEG_REPAIR\"")
            assertContains(fetched.body, "\"settlementState\":\"RESOLVED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun hotPathSettlementFactsEndpointReadsAppendedFactsByScenarioRunId() {
        val settlementStore = InMemorySettlementFactStore()
        val server = PlatformHttpServer(
            port = 0,
            boundary = ExternalApiBoundary(),
            idempotencyStore = InMemoryIdempotencyStore(),
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            settlementFactStore = settlementStore
        )
        val readHeaders = Headers().apply {
            add("X-Client-Id", "client-hot-path-settlement")
            add("X-Participant-Id", "participant-1")
        }

        val posted = server.handleHotPathRequest(
            PlatformHotPathRequest(
                method = "POST",
                path = "/internal/admin/settlement/facts",
                query = null,
                headers = Headers(),
                remoteAddress = "127.0.0.1",
                body = p2SettlementFactsBody("p2-run-hot-path")
            )
        )
        val fetched = server.handleHotPathRequest(
            PlatformHotPathRequest(
                method = "GET",
                path = "/api/v1/settlement/facts/p2-run-hot-path",
                query = null,
                headers = readHeaders,
                remoteAddress = "203.0.113.10",
                body = ""
            )
        )

        assertEquals(200, posted?.status)
        assertContains(posted?.body.orEmpty(), "\"status\":\"ok\"")
        assertEquals(200, fetched?.status)
        assertContains(fetched?.body.orEmpty(), "\"scenarioRunId\":\"p2-run-hot-path\"")
        assertContains(fetched?.body.orEmpty(), "\"settlementObligationId\":\"obl-1\"")
        assertContains(fetched?.body.orEmpty(), "\"settlementInstructionId\":\"instruction-1\"")
        assertContains(fetched?.body.orEmpty(), "\"settlementAttemptId\":\"attempt-1\"")
    }

    @Test
    fun adminGatewaySettlementFactsEndpointAppendsAndReadsP2FactsByScenarioRunId() {
        val auth = testAdminAuth()
        val serviceToken = auth.authService.issueServiceToken(
            tokenFamily = AdminServiceTokenFamily.Admin,
            subjectActorId = "settlement-seeder",
            ttl = null
        )
        val settlementStore = InMemorySettlementFactStore()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            adminAuthService = auth.authService,
            adminIdentityService = auth.identityService,
            settlementFactStore = settlementStore
        )
        try {
            val posted = post(
                server.address.port,
                "/admin/v1/settlement/facts",
                mapOf("Authorization" to "Bearer ${serviceToken.token}"),
                p2SettlementFactsBody("p2-run-admin-gateway")
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-run-admin-gateway")

            assertEquals(200, posted.status)
            assertContains(posted.body, "\"status\":\"ok\"")
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"scenarioRunId\":\"p2-run-admin-gateway\"")
            assertContains(fetched.body, "\"settlementObligationId\":\"obl-1\"")
            assertContains(fetched.body, "\"settlementState\":\"RESOLVED\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementFactsEndpointUsesConfiguredPostTradeProfileDefault() {
        val settlementStore = InMemorySettlementFactStore()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            defaultPostTradeProfileId = "instant-post-trade-v1",
            defaultPostTradePolicyVersion = 4
        )
        try {
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                p2SettlementFactsBody("p2-run-default", includePostTradeProfile = false)
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-run-default")

            assertEquals(200, posted.status)
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"postTradeProfileId\":\"instant-post-trade-v1\"")
            assertContains(fetched.body, "\"postTradePolicyVersion\":4")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementFactsEndpointUsesPersistedActivePostTradeProfile() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.savePostTradeProfile(
            PostTradeProfile(
                profileId = "instant-post-trade-v1",
                mode = "instant-post-trade",
                settlementCycle = "T+0",
                nettingMode = "gross-or-microbatch",
                ledgerPostingMode = "near-instant-finality",
                policyVersion = 5,
                active = true
            )
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            runtimePersistence = persistence,
            postTradeProfileResolver = PostTradeProfileResolver.fromPersistence(persistence)
        )
        try {
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                p2SettlementFactsBody("p2-run-persisted-default", includePostTradeProfile = false)
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-run-persisted-default")

            assertEquals(200, posted.status)
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"postTradeProfileId\":\"instant-post-trade-v1\"")
            assertContains(fetched.body, "\"postTradePolicyVersion\":5")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementFactsEndpointUsesVenueSessionPostTradeProfileOverride() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.savePostTradeProfile(
            PostTradeProfile(
                profileId = "instant-post-trade-v1",
                mode = "instant-post-trade",
                settlementCycle = "T+0",
                nettingMode = "gross-or-microbatch",
                ledgerPostingMode = "near-instant-finality",
                policyVersion = 6
            )
        )
        persistence.saveVenueSessionPostTradeProfile(
            com.reef.platform.domain.VenueSessionPostTradeProfile(
                venueSessionId = "session-fast",
                postTradeProfileId = "instant-post-trade-v1"
            )
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            runtimePersistence = persistence,
            postTradeProfileResolver = PostTradeProfileResolver.fromPersistence(persistence),
            venueSessionPostTradeProfileLookup = { persistence.venueSessionPostTradeProfileId(it) }
        )
        try {
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                p2SettlementFactsBody(
                    scenarioRunId = "p2-run-venue-override",
                    includePostTradeProfile = false,
                    venueSessionId = "session-fast"
                )
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-run-venue-override")

            assertEquals(200, posted.status)
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"postTradeProfileId\":\"instant-post-trade-v1\"")
            assertContains(fetched.body, "\"postTradePolicyVersion\":6")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementFactsEndpointUsesScenarioRunPostTradeProfileOverride() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.savePostTradeProfile(
            PostTradeProfile(
                profileId = "scenario-instant-v1",
                mode = "instant-post-trade",
                settlementCycle = "T+0",
                nettingMode = "gross-or-microbatch",
                ledgerPostingMode = "near-instant-finality",
                policyVersion = 8
            )
        )
        persistence.saveScenarioRunPostTradeProfile(
            ScenarioRunPostTradeProfile(
                scenarioRunId = "p2-run-scenario-override",
                postTradeProfileId = "scenario-instant-v1"
            )
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            runtimePersistence = persistence,
            postTradeProfileResolver = PostTradeProfileResolver.fromPersistence(persistence),
            scenarioRunPostTradeProfileLookup = { persistence.scenarioRunPostTradeProfileId(it) }
        )
        try {
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                p2SettlementFactsBody(
                    scenarioRunId = "p2-run-scenario-override",
                    includePostTradeProfile = false
                )
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-run-scenario-override")

            assertEquals(200, posted.status)
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"postTradeProfileId\":\"scenario-instant-v1\"")
            assertContains(fetched.body, "\"postTradePolicyVersion\":8")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementFactsEndpointExplicitProfileWinsOverScenarioRunOverride() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.savePostTradeProfile(
            PostTradeProfile(
                profileId = "scenario-instant-v1",
                mode = "instant-post-trade",
                settlementCycle = "T+0",
                nettingMode = "gross-or-microbatch",
                ledgerPostingMode = "near-instant-finality",
                policyVersion = 8
            )
        )
        persistence.saveScenarioRunPostTradeProfile(
            ScenarioRunPostTradeProfile(
                scenarioRunId = "p2-run-explicit-wins",
                postTradeProfileId = "scenario-instant-v1"
            )
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            runtimePersistence = persistence,
            postTradeProfileResolver = PostTradeProfileResolver.fromPersistence(persistence),
            scenarioRunPostTradeProfileLookup = { persistence.scenarioRunPostTradeProfileId(it) }
        )
        try {
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                p2SettlementFactsBody("p2-run-explicit-wins", includePostTradeProfile = true)
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/p2-run-explicit-wins")

            assertEquals(200, posted.status)
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"postTradeProfileId\":\"instant-post-trade-v1\"")
            assertContains(fetched.body, "\"postTradePolicyVersion\":2")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementMaterializeEndpointCreatesObligationsFromTrades() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.savePostTradeProfile(
            PostTradeProfile(
                profileId = "scenario-instant-v1",
                mode = "instant-post-trade",
                settlementCycle = "T+0",
                nettingMode = "gross-or-microbatch",
                ledgerPostingMode = "near-instant-finality",
                policyVersion = 9
            )
        )
        persistence.saveScenarioRunPostTradeProfile(
            ScenarioRunPostTradeProfile("run-materialize", "scenario-instant-v1")
        )
        persistence.saveAcceptedOrder(
            persistedOrder("buy-order-materialize", "buyer-1", "BUY", "run-materialize", "session-fast")
        )
        persistence.saveAcceptedOrder(
            persistedOrder("sell-order-materialize", "seller-1", "SELL", "run-materialize", "session-fast")
        )
        persistence.saveTrades(
            listOf(
                TradeCreated(
                    eventId = "evt-trade-materialize",
                    tradeId = "trade-materialize",
                    executionId = "exec-materialize",
                    buyOrderId = "buy-order-materialize",
                    sellOrderId = "sell-order-materialize",
                    instrumentId = "AAPL",
                    quantityUnits = "100",
                    price = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-01-01T00:00:00Z"
                )
            )
        )
        val materializer = TradeSettlementObligationMaterializer(
            runtimePersistence = persistence,
            settlementFactStore = settlementStore,
            postTradeProfileResolver = PostTradeProfileResolver.fromPersistence(persistence)
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            settlementObligationMaterializer = materializer,
            runtimePersistence = persistence
        )
        try {
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/obligations/materialize",
                emptyMap(),
                """{"scenarioRunId":"run-materialize"}"""
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/run-materialize")
            val obligations = apiGet(server.address.port, "/api/v1/settlement/obligations/run-materialize")
            val ledger = apiGet(server.address.port, "/api/v1/settlement/ledger/run-materialize")
            val exceptions = apiGet(server.address.port, "/api/v1/settlement/exceptions/run-materialize")
            val proof = apiGet(server.address.port, "/api/v1/settlement/proof/run-materialize")
            val score = apiGet(server.address.port, "/api/v1/settlement/score/run-materialize")

            assertEquals(200, posted.status)
            assertContains(posted.body, "\"materializedObligations\":1")
            assertContains(posted.body, "\"materializedInstructions\":1")
            assertContains(posted.body, "\"materializedAttempts\":1")
            assertContains(posted.body, "\"materializedLegOutcomes\":2")
            assertContains(posted.body, "\"materializedLedgerEntries\":4")
            assertContains(posted.body, "\"materializedSettlements\":1")
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"settlementObligationId\":\"settlement-obligation-trade-materialize\"")
            assertContains(fetched.body, "\"settlementClearingSubmissionId\":\"settlement-clearing-submission-settlement-obligation-trade-materialize\"")
            assertContains(fetched.body, "\"settlementClearingAcceptanceId\":\"settlement-clearing-acceptance-settlement-obligation-trade-materialize\"")
            assertContains(fetched.body, "\"settlementNovationId\":\"settlement-novation-settlement-obligation-trade-materialize\"")
            assertContains(fetched.body, "\"settlementInstructionId\":\"settlement-instruction-settlement-obligation-trade-materialize-1\"")
            assertContains(fetched.body, "\"settlementAttemptId\":\"settlement-attempt-settlement-obligation-trade-materialize-1\"")
            assertContains(fetched.body, "\"settlementId\":\"settlement-final-settlement-obligation-trade-materialize\"")
            assertContains(fetched.body, "\"ledgerEntryId\":\"settlement-ledger-settlement-attempt-settlement-obligation-trade-materialize-1-buyer-cash-debit\"")
            assertContains(fetched.body, "\"postTradeProfileId\":\"scenario-instant-v1\"")
            assertContains(fetched.body, "\"postTradePolicyVersion\":9")
            assertContains(fetched.body, "\"cashAmount\":\"15025000000000\"")
            assertEquals(200, obligations.status)
            assertContains(obligations.body, "\"obligationsCount\":1")
            assertContains(obligations.body, "\"settlementState\":\"SETTLED\"")
            assertContains(obligations.body, "\"exceptionState\":\"NONE\"")
            assertContains(obligations.body, "\"clearingState\":\"NOVATION_RECORDED\"")
            assertContains(obligations.body, "\"settlementNovationId\":\"settlement-novation-settlement-obligation-trade-materialize\"")
            assertContains(obligations.body, "\"settlementInstructionId\":\"settlement-instruction-settlement-obligation-trade-materialize-1\"")
            assertContains(obligations.body, "\"settlementAttemptNumber\":1")
            assertContains(obligations.body, "\"cashLegState\":\"LEG_SUCCEEDED\"")
            assertContains(obligations.body, "\"securityLegState\":\"LEG_SUCCEEDED\"")
            assertContains(obligations.body, "\"ledgerEntryCount\":4")
            assertEquals(200, ledger.status)
            assertContains(ledger.body, "\"balancesCount\":4")
            assertContains(ledger.body, "\"settlementProofsCount\":1")
            assertContains(ledger.body, "\"participantId\":\"buyer-1\"")
            assertContains(ledger.body, "\"assetType\":\"CASH\"")
            assertContains(ledger.body, "\"netQuantity\":\"-15025000000000\"")
            assertContains(ledger.body, "\"proofState\":\"PROVEN\"")
            assertContains(ledger.body, "\"cashBalanced\":true")
            assertContains(ledger.body, "\"securityBalanced\":true")
            assertEquals(200, exceptions.status)
            assertContains(exceptions.body, "\"exceptionsCount\":0")
            assertContains(exceptions.body, "\"openCount\":0")
            assertEquals(200, proof.status)
            assertContains(proof.body, "\"proofStatus\":\"CLEAN\"")
            assertContains(proof.body, "\"checksumAlgorithm\":\"SHA-256\"")
            assertContains(proof.body, "\"profilePolicies\"")
            assertContains(proof.body, "\"causationGapsCount\":0")
            assertContains(proof.body, "\"ledgerEntryIds\"")
            assertContains(proof.body, "\"settlement-ledger-settlement-attempt-settlement-obligation-trade-materialize-1-buyer-cash-debit\"")
            assertEquals(200, score.status)
            assertContains(score.body, "\"agedFailAfterSeconds\":86400")
            assertContains(score.body, "\"participantsCount\":2")
            assertContains(score.body, "\"participantId\":\"buyer-1\"")
            assertContains(score.body, "\"pendingValue\":\"0\"")
            assertContains(score.body, "\"scorePenaltyPoints\":0")
            assertContains(score.body, "\"agedFailCount\":0")

            val reversed = post(
                server.address.port,
                "/internal/admin/settlement/reverse-ledger-entry",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize",
                  "ledgerEntryId":"settlement-ledger-settlement-attempt-settlement-obligation-trade-materialize-1-buyer-cash-debit",
                  "actorId":"ops-user-1",
                  "reasonNote":"reverse buyer cash debit for operator test",
                  "occurredAt":"2026-01-01T00:00:03Z"
                }
                """.trimIndent()
            )
            val reversedFacts = apiGet(server.address.port, "/api/v1/settlement/facts/run-materialize")
            val reversedLedger = apiGet(server.address.port, "/api/v1/settlement/ledger/run-materialize")

            assertEquals(200, reversed.status)
            assertContains(reversed.body, "\"action\":\"REVERSE_LEDGER_ENTRY\"")
            assertContains(reversed.body, "\"reasonNote\":\"reverse buyer cash debit for operator test\"")
            assertContains(reversed.body, "\"ledgerEntryId\":\"settlement-ledger-reversal-settlement-ledger-settlement-attempt-settlement-obligation-trade-materialize-1-buyer-cash-debit\"")
            assertEquals(200, reversedFacts.status)
            assertContains(reversedFacts.body, "\"operatorActions\"")
            assertContains(reversedFacts.body, "\"settlementOperatorActionId\":\"operator-reverse-ledger-settlement-ledger-settlement-attempt-settlement-obligation-trade-materialize-1-buyer-cash-debit\"")
            assertEquals(200, reversedLedger.status)
            assertContains(reversedLedger.body, "\"settlementProofsCount\":1")
            assertContains(reversedLedger.body, "\"cashBalanced\":true")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementMaterializeEndpointCreatesBreakWhenSeededResourcesAreInsufficient() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.saveAcceptedOrder(
            persistedOrder("buy-order-materialize-fail", "buyer-1", "BUY", "run-materialize-fail", "session-fast")
        )
        persistence.saveAcceptedOrder(
            persistedOrder("sell-order-materialize-fail", "seller-1", "SELL", "run-materialize-fail", "session-fast")
        )
        persistence.saveTrades(
            listOf(
                TradeCreated(
                    eventId = "evt-trade-materialize-fail",
                    tradeId = "trade-materialize-fail",
                    executionId = "exec-materialize-fail",
                    buyOrderId = "buy-order-materialize-fail",
                    sellOrderId = "sell-order-materialize-fail",
                    instrumentId = "AAPL",
                    quantityUnits = "100",
                    price = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-01-01T00:00:00Z"
                )
            )
        )
        val resolver = PostTradeProfileResolver.envOnly(
            profileId = "instant-post-trade-v1",
            policyVersion = 4
        )
        val materializer = TradeSettlementObligationMaterializer(
            runtimePersistence = persistence,
            settlementFactStore = settlementStore,
            postTradeProfileResolver = resolver
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            settlementObligationMaterializer = materializer,
            runtimePersistence = persistence,
            defaultPostTradeProfileId = "instant-post-trade-v1",
            defaultPostTradePolicyVersion = 4,
            postTradeProfileResolver = resolver
        )
        try {
            val seeded = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize-fail",
                  "resourcePositions":[
                    {
                      "resourcePositionId":"resource-run-materialize-fail-seller-security",
                      "correlationId":"corr-resource-fail",
                      "causationId":"seed-resource-fail",
                      "participantId":"seller-1",
                      "accountId":"account-seller-1",
                      "assetType":"SECURITY",
                      "assetId":"AAPL",
                      "quantity":"100",
                      "occurredAt":"2025-12-31T23:59:59Z"
                    }
                  ]
                }
                """.trimIndent()
            )
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/obligations/materialize",
                emptyMap(),
                """{"scenarioRunId":"run-materialize-fail"}"""
            )
            val fetched = apiGet(server.address.port, "/api/v1/settlement/facts/run-materialize-fail")
            val obligations = apiGet(server.address.port, "/api/v1/settlement/obligations/run-materialize-fail")
            val exceptions = apiGet(server.address.port, "/api/v1/settlement/exceptions/run-materialize-fail")

            assertEquals(200, seeded.status)
            assertEquals(200, posted.status)
            assertContains(posted.body, "\"materializedLedgerEntries\":0")
            assertContains(posted.body, "\"materializedSettlements\":0")
            assertContains(posted.body, "\"materializedBreaks\":1")
            assertContains(posted.body, "\"materializedResolutions\":0")
            assertEquals(200, fetched.status)
            assertContains(fetched.body, "\"resourcePositionId\":\"resource-run-materialize-fail-seller-security\"")
            assertContains(fetched.body, "\"legType\":\"CASH\"")
            assertContains(fetched.body, "\"state\":\"LEG_FAILED\"")
            assertContains(fetched.body, "\"reason\":\"CASH_LEG_FAILED\"")
            assertEquals(200, obligations.status)
            assertContains(obligations.body, "\"settlementState\":\"BROKEN\"")
            assertContains(obligations.body, "\"cashLegState\":\"LEG_FAILED\"")
            assertContains(obligations.body, "\"securityLegState\":\"LEG_SUCCEEDED\"")
            assertContains(obligations.body, "\"ledgerEntryCount\":0")
            assertEquals(200, exceptions.status)
            assertContains(exceptions.body, "\"exceptionsCount\":1")
            assertContains(exceptions.body, "\"openCount\":1")
            assertContains(exceptions.body, "\"exceptionType\":\"SETTLEMENT_BREAK\"")
            assertContains(exceptions.body, "\"severity\":\"MEDIUM\"")
            assertContains(exceptions.body, "\"ownerRole\":\"SETTLEMENT_OPS\"")
            assertContains(exceptions.body, "\"actionRequired\":\"POST_CASH_LEG_REPAIR\"")
            assertContains(exceptions.body, "\"repairAction\":\"POST_CASH_LEG_REPAIR\"")

            val missingOccurredAtRepair = post(
                server.address.port,
                "/internal/admin/settlement/repairs/cash",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize-fail",
                  "settlementBreakId":"settlement-break-settlement-obligation-trade-materialize-fail-1",
                  "accountId":"account-buyer-1",
                  "actorId":"ops-user-1"
                }
                """.trimIndent()
            )
            val wrongRepairRoute = post(
                server.address.port,
                "/internal/admin/settlement/repairs/security",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize-fail",
                  "settlementBreakId":"settlement-break-settlement-obligation-trade-materialize-fail-1",
                  "accountId":"account-seller-1",
                  "actorId":"ops-user-1",
                  "occurredAt":"2026-01-01T00:00:01Z"
                }
                """.trimIndent()
            )
            val repairPosted = post(
                server.address.port,
                "/internal/admin/settlement/repairs/cash",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize-fail",
                  "settlementRepairId":"repair-run-materialize-fail-1",
                  "resourcePositionId":"resource-run-materialize-fail-buyer-cash-repair",
                  "settlementBreakId":"settlement-break-settlement-obligation-trade-materialize-fail-1",
                  "accountId":"account-buyer-1",
                  "quantity":"15025000000000",
                  "actorId":"ops-user-1",
                  "occurredAt":"2026-01-01T00:00:02Z"
                }
                """.trimIndent()
            )
            val repaired = post(
                server.address.port,
                "/internal/admin/settlement/obligations/materialize",
                emptyMap(),
                """{"scenarioRunId":"run-materialize-fail"}"""
            )
            val repairedFetched = apiGet(server.address.port, "/api/v1/settlement/facts/run-materialize-fail")
            val repairedObligations = apiGet(server.address.port, "/api/v1/settlement/obligations/run-materialize-fail")
            val repairedLedger = apiGet(server.address.port, "/api/v1/settlement/ledger/run-materialize-fail")

            assertEquals(400, missingOccurredAtRepair.status)
            assertContains(missingOccurredAtRepair.body, "\"error\":\"occurredAt is required\"")
            assertEquals(400, wrongRepairRoute.status)
            assertContains(wrongRepairRoute.body, "\"error\":\"settlementBreakId is not a security break\"")
            assertEquals(200, repairPosted.status)
            assertContains(repairPosted.body, "\"resourcePositionId\":\"resource-run-materialize-fail-buyer-cash-repair\"")
            assertContains(repairPosted.body, "\"settlementRepairId\":\"repair-run-materialize-fail-1\"")
            assertContains(repairPosted.body, "\"repairAction\":\"POST_CASH_LEG_REPAIR\"")
            assertEquals(200, repaired.status)
            assertContains(repaired.body, "\"materializedAttempts\":1")
            assertContains(repaired.body, "\"materializedLedgerEntries\":4")
            assertContains(repaired.body, "\"materializedSettlements\":1")
            assertContains(repaired.body, "\"materializedResolutions\":1")
            assertEquals(200, repairedFetched.status)
            assertContains(repairedFetched.body, "\"settlementAttemptId\":\"settlement-attempt-settlement-obligation-trade-materialize-fail-2\"")
            assertContains(repairedFetched.body, "\"settlementResolutionId\":\"settlement-resolution-settlement-break-settlement-obligation-trade-materialize-fail-1-repair-run-materialize-fail-1\"")
            assertEquals(200, repairedObligations.status)
            assertContains(repairedObligations.body, "\"settlementState\":\"SETTLED\"")
            assertContains(repairedObligations.body, "\"exceptionState\":\"RESOLVED\"")
            assertContains(repairedObligations.body, "\"settlementAttemptNumber\":2")
            assertContains(repairedObligations.body, "\"ledgerEntryCount\":4")
            assertEquals(200, repairedLedger.status)
            assertContains(repairedLedger.body, "\"settlementProofsCount\":1")
            assertContains(repairedLedger.body, "\"proofState\":\"PROVEN\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementSecurityRepairCommandReattemptsBrokenSettlement() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.saveAcceptedOrder(
            persistedOrder("buy-order-materialize-security-fail", "buyer-1", "BUY", "run-materialize-security-fail", "session-fast")
        )
        persistence.saveAcceptedOrder(
            persistedOrder("sell-order-materialize-security-fail", "seller-1", "SELL", "run-materialize-security-fail", "session-fast")
        )
        persistence.saveTrades(
            listOf(
                TradeCreated(
                    eventId = "evt-trade-materialize-security-fail",
                    tradeId = "trade-materialize-security-fail",
                    executionId = "exec-materialize-security-fail",
                    buyOrderId = "buy-order-materialize-security-fail",
                    sellOrderId = "sell-order-materialize-security-fail",
                    instrumentId = "AAPL",
                    quantityUnits = "100",
                    price = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-01-01T00:00:00Z"
                )
            )
        )
        val resolver = PostTradeProfileResolver.envOnly(
            profileId = "instant-post-trade-v1",
            policyVersion = 4
        )
        val materializer = TradeSettlementObligationMaterializer(
            runtimePersistence = persistence,
            settlementFactStore = settlementStore,
            postTradeProfileResolver = resolver
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            settlementObligationMaterializer = materializer,
            runtimePersistence = persistence,
            defaultPostTradeProfileId = "instant-post-trade-v1",
            defaultPostTradePolicyVersion = 4,
            postTradeProfileResolver = resolver
        )
        try {
            val seeded = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize-security-fail",
                  "resourcePositions":[
                    {
                      "resourcePositionId":"resource-run-materialize-security-fail-buyer-cash",
                      "correlationId":"corr-resource-security-fail",
                      "causationId":"seed-resource-security-fail",
                      "participantId":"buyer-1",
                      "accountId":"account-buyer-1",
                      "assetType":"CASH",
                      "assetId":"USD",
                      "quantity":"15025000000000",
                      "occurredAt":"2025-12-31T23:59:59Z"
                    }
                  ]
                }
                """.trimIndent()
            )
            val posted = post(
                server.address.port,
                "/internal/admin/settlement/obligations/materialize",
                emptyMap(),
                """{"scenarioRunId":"run-materialize-security-fail"}"""
            )
            val brokenObligations = apiGet(server.address.port, "/api/v1/settlement/obligations/run-materialize-security-fail")

            assertEquals(200, seeded.status)
            assertEquals(200, posted.status)
            assertContains(posted.body, "\"materializedLedgerEntries\":0")
            assertContains(posted.body, "\"materializedBreaks\":1")
            assertEquals(200, brokenObligations.status)
            assertContains(brokenObligations.body, "\"settlementState\":\"BROKEN\"")
            assertContains(brokenObligations.body, "\"cashLegState\":\"LEG_SUCCEEDED\"")
            assertContains(brokenObligations.body, "\"securityLegState\":\"LEG_FAILED\"")

            val wrongRepairRoute = post(
                server.address.port,
                "/internal/admin/settlement/repairs/cash",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize-security-fail",
                  "settlementBreakId":"settlement-break-settlement-obligation-trade-materialize-security-fail-1",
                  "accountId":"account-buyer-1",
                  "actorId":"ops-user-1",
                  "occurredAt":"2026-01-01T00:00:01Z"
                }
                """.trimIndent()
            )
            val repairPosted = post(
                server.address.port,
                "/internal/admin/settlement/repairs/security",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-materialize-security-fail",
                  "settlementRepairId":"repair-run-materialize-security-fail-1",
                  "resourcePositionId":"resource-run-materialize-security-fail-seller-security-repair",
                  "settlementBreakId":"settlement-break-settlement-obligation-trade-materialize-security-fail-1",
                  "accountId":"account-seller-1",
                  "actorId":"ops-user-1",
                  "occurredAt":"2026-01-01T00:00:02Z"
                }
                """.trimIndent()
            )
            val repaired = post(
                server.address.port,
                "/internal/admin/settlement/obligations/materialize",
                emptyMap(),
                """{"scenarioRunId":"run-materialize-security-fail"}"""
            )
            val repairedFetched = apiGet(server.address.port, "/api/v1/settlement/facts/run-materialize-security-fail")
            val repairedObligations = apiGet(server.address.port, "/api/v1/settlement/obligations/run-materialize-security-fail")
            val repairedLedger = apiGet(server.address.port, "/api/v1/settlement/ledger/run-materialize-security-fail")

            assertEquals(400, wrongRepairRoute.status)
            assertContains(wrongRepairRoute.body, "\"error\":\"settlementBreakId is not a cash break\"")
            assertEquals(200, repairPosted.status)
            assertContains(repairPosted.body, "\"resourcePositionId\":\"resource-run-materialize-security-fail-seller-security-repair\"")
            assertContains(repairPosted.body, "\"repairAction\":\"POST_SECURITY_LEG_REPAIR\"")
            assertContains(repairPosted.body, "\"assetType\":\"SECURITY\"")
            assertContains(repairPosted.body, "\"assetId\":\"AAPL\"")
            assertContains(repairPosted.body, "\"quantity\":\"100\"")
            assertEquals(200, repaired.status)
            assertContains(repaired.body, "\"materializedAttempts\":1")
            assertContains(repaired.body, "\"materializedLedgerEntries\":4")
            assertContains(repaired.body, "\"materializedSettlements\":1")
            assertContains(repaired.body, "\"materializedResolutions\":1")
            assertEquals(200, repairedFetched.status)
            assertContains(repairedFetched.body, "\"settlementAttemptId\":\"settlement-attempt-settlement-obligation-trade-materialize-security-fail-2\"")
            assertContains(repairedFetched.body, "\"settlementResolutionId\":\"settlement-resolution-settlement-break-settlement-obligation-trade-materialize-security-fail-1-repair-run-materialize-security-fail-1\"")
            assertEquals(200, repairedObligations.status)
            assertContains(repairedObligations.body, "\"settlementState\":\"SETTLED\"")
            assertContains(repairedObligations.body, "\"exceptionState\":\"RESOLVED\"")
            assertContains(repairedObligations.body, "\"settlementAttemptNumber\":2")
            assertContains(repairedObligations.body, "\"ledgerEntryCount\":4")
            assertEquals(200, repairedLedger.status)
            assertContains(repairedLedger.body, "\"settlementProofsCount\":1")
            assertContains(repairedLedger.body, "\"proofState\":\"PROVEN\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settlementForceSettleCommandRepairsAndMaterializesBreak() {
        val settlementStore = InMemorySettlementFactStore()
        val persistence = InMemoryRuntimePersistence()
        persistence.saveAcceptedOrder(
            persistedOrder("buy-order-force-settle", "buyer-1", "BUY", "run-force-settle", "session-fast")
        )
        persistence.saveAcceptedOrder(
            persistedOrder("sell-order-force-settle", "seller-1", "SELL", "run-force-settle", "session-fast")
        )
        persistence.saveTrades(
            listOf(
                TradeCreated(
                    eventId = "evt-trade-force-settle",
                    tradeId = "trade-force-settle",
                    executionId = "exec-force-settle",
                    buyOrderId = "buy-order-force-settle",
                    sellOrderId = "sell-order-force-settle",
                    instrumentId = "AAPL",
                    quantityUnits = "100",
                    price = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-01-01T00:00:00Z"
                )
            )
        )
        val resolver = PostTradeProfileResolver.envOnly("instant-post-trade-v1", 4)
        val materializer = TradeSettlementObligationMaterializer(
            runtimePersistence = persistence,
            settlementFactStore = settlementStore,
            postTradeProfileResolver = resolver
        )
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            settlementFactStore = settlementStore,
            settlementObligationMaterializer = materializer,
            runtimePersistence = persistence,
            defaultPostTradeProfileId = "instant-post-trade-v1",
            defaultPostTradePolicyVersion = 4,
            postTradeProfileResolver = resolver
        )
        try {
            val seeded = post(
                server.address.port,
                "/internal/admin/settlement/facts",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-force-settle",
                  "resourcePositions":[
                    {
                      "resourcePositionId":"resource-run-force-settle-seller-security",
                      "correlationId":"corr-force-settle",
                      "causationId":"seed-force-settle",
                      "participantId":"seller-1",
                      "accountId":"account-seller-1",
                      "assetType":"SECURITY",
                      "assetId":"AAPL",
                      "quantity":"100",
                      "occurredAt":"2025-12-31T23:59:59Z"
                    }
                  ]
                }
                """.trimIndent()
            )
            val broken = post(
                server.address.port,
                "/internal/admin/settlement/obligations/materialize",
                emptyMap(),
                """{"scenarioRunId":"run-force-settle"}"""
            )
            val missingReason = post(
                server.address.port,
                "/internal/admin/settlement/force-settle",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-force-settle",
                  "settlementBreakId":"settlement-break-settlement-obligation-trade-force-settle-1",
                  "accountId":"account-buyer-1",
                  "actorId":"ops-user-1",
                  "occurredAt":"2026-01-01T00:00:02Z"
                }
                """.trimIndent()
            )
            val forced = post(
                server.address.port,
                "/internal/admin/settlement/force-settle",
                emptyMap(),
                """
                {
                  "scenarioRunId":"run-force-settle",
                  "settlementBreakId":"settlement-break-settlement-obligation-trade-force-settle-1",
                  "accountId":"account-buyer-1",
                  "actorId":"ops-user-1",
                  "reasonNote":"force settle cash fail in scenario",
                  "occurredAt":"2026-01-01T00:00:02Z"
                }
                """.trimIndent()
            )
            val facts = apiGet(server.address.port, "/api/v1/settlement/facts/run-force-settle")
            val obligations = apiGet(server.address.port, "/api/v1/settlement/obligations/run-force-settle")
            val proof = apiGet(server.address.port, "/api/v1/settlement/proof/run-force-settle")

            assertEquals(200, seeded.status)
            assertEquals(200, broken.status)
            assertContains(broken.body, "\"materializedBreaks\":1")
            assertEquals(400, missingReason.status)
            assertContains(missingReason.body, "\"error\":\"reasonNote is required\"")
            assertEquals(200, forced.status)
            assertContains(forced.body, "\"action\":\"FORCE_SETTLE\"")
            assertContains(forced.body, "\"reasonNote\":\"force settle cash fail in scenario\"")
            assertContains(forced.body, "\"materializedSettlements\":1")
            assertContains(forced.body, "\"materializedResolutions\":1")
            assertEquals(200, facts.status)
            assertContains(facts.body, "\"settlementOperatorActionId\":\"operator-force-settle-settlement-break-settlement-obligation-trade-force-settle-1\"")
            assertEquals(200, obligations.status)
            assertContains(obligations.body, "\"settlementState\":\"SETTLED\"")
            assertContains(obligations.body, "\"exceptionState\":\"RESOLVED\"")
            assertEquals(200, proof.status)
            assertContains(proof.body, "\"operatorActionsCount\":1")
            assertContains(proof.body, "\"proofStatus\":\"CLEAN\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun internalAdminCircuitBreakerEndpointSetsBreakerAndAuditsChange() {
        val breakerStore = RecordingCommandCircuitBreakerStore()
        val persistence = InMemoryRuntimePersistence()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            commandCircuitBreakerCheck = breakerStore,
            runtimePersistence = persistence
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/internal/admin/circuit-breakers",
                headers = mapOf("X-Reef-Actor-Id" to "ops-2", "X-Correlation-Id" to "corr-admin-breaker"),
                body = """
                    {
                      "scopeType":"instrument",
                      "scopeId":"AAPL",
                      "action":"trip",
                      "reason":"operator halt",
                      "actorId":"spoofed-ops",
                      "correlationId":"spoofed-corr"
                    }
                """.trimIndent()
            )
            val breakers = get(server.address.port, "/internal/boundary/circuit-breakers")
            val auditEvents = persistence.eventsForTrace("admin:ops-2")

            assertEquals(200, response.status)
            assertContains(response.body, "\"status\":\"ok\"")
            assertContains(response.body, "\"tripped\":true")
            assertContains(breakers.body, "\"scopeType\":\"INSTRUMENT\"")
            assertContains(breakers.body, "\"reason\":\"operator halt\"")
            assertEquals(1, auditEvents.size)
            assertEquals("CommandCircuitBreakerChanged", auditEvents.single().eventType)
            assertContains(auditEvents.single().payloadJson, "\"previousTripped\":false")
            assertContains(auditEvents.single().payloadJson, "\"tripped\":true")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun internalAdminPriceCollarEndpointSetsCollarAndAuditsChange() {
        val collarStore = RecordingInstrumentPriceCollarStore()
        val persistence = InMemoryRuntimePersistence()
        val server = testServerWithGateway(
            gateway = StaticAcceptedEngineGateway(),
            instrumentPriceCollarCheck = collarStore,
            runtimePersistence = persistence
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/internal/admin/price-collars",
                headers = mapOf("X-Reef-Actor-Id" to "ops-3", "X-Correlation-Id" to "corr-admin-collar"),
                body = """
                    {
                      "instrumentId":"AAPL",
                      "minPrice":"150000000000",
                      "maxPrice":"151000000000",
                      "currency":"usd",
                      "reason":"regular band",
                      "actorId":"spoofed-ops",
                      "correlationId":"spoofed-corr"
                    }
                """.trimIndent()
            )
            val collars = get(server.address.port, "/internal/boundary/price-collars")
            val auditEvents = persistence.eventsForTrace("admin:ops-3")

            assertEquals(200, response.status)
            assertContains(response.body, "\"status\":\"ok\"")
            assertContains(response.body, "\"instrumentId\":\"AAPL\"")
            assertContains(response.body, "\"currency\":\"USD\"")
            assertContains(collars.body, "\"minPrice\":\"150000000000\"")
            assertContains(collars.body, "\"maxPrice\":\"151000000000\"")
            assertEquals(1, auditEvents.size)
            assertEquals("InstrumentPriceCollarChanged", auditEvents.single().eventType)
            assertContains(auditEvents.single().payloadJson, "\"previousMinPrice\":\"\"")
            assertContains(auditEvents.single().payloadJson, "\"maxPrice\":\"151000000000\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun accountRiskControlRejectsThenClearAllowsCapturedAckCommand() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val accountRiskStore = RecordingAccountRiskStore()
        accountRiskStore.upsertControl("BOT", "bot-1", AccountRiskDecision.DISABLED_BOT, "disabled", "", "", "")
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            accountRiskCheck = accountRiskStore
        )
        try {
            val rejected = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-risk-smoke",
                    "Idempotency-Key" to "idem-risk-smoke-reject"
                ),
                body = validSubmitBody(
                    "cmd-risk-smoke-reject",
                    "trace-risk-smoke-reject",
                    "ord-risk-smoke-reject",
                    extra = ",\"botId\":\"bot-1\""
                )
            )
            assertEquals(403, rejected.status)
            assertContains(rejected.body, "\"code\":\"BOT_DISABLED\"")
            assertEquals(
                404,
                get(
                    server.address.port,
                    "/api/v1/commands/cmd-risk-smoke-reject",
                    headers = apiReadHeaders("client-risk-smoke")
                ).status
            )

            accountRiskStore.upsertControl("BOT", "bot-1", AccountRiskDecision.ALLOW, "cleared", "", "", "")
            val accepted = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-risk-smoke",
                    "Idempotency-Key" to "idem-risk-smoke-accept"
                ),
                body = validSubmitBody(
                    "cmd-risk-smoke-accept",
                    "trace-risk-smoke-accept",
                    "ord-risk-smoke-accept",
                    extra = ",\"botId\":\"bot-1\""
                )
            )

            assertEquals(202, accepted.status)
            assertContains(accepted.body, "\"commandId\":\"cmd-risk-smoke-accept\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(
                200,
                get(
                    server.address.port,
                    "/api/v1/commands/cmd-risk-smoke-accept",
                    headers = apiReadHeaders("client-risk-smoke")
                ).status
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun commandCircuitBreakerRejectsThenResetAllowsCapturedAckCommand() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val breakerStore = RecordingCommandCircuitBreakerStore()
        breakerStore.setBreaker("INSTRUMENT", "AAPL", true, "halted")
        val rejectionLog = RecordingBoundaryRejectionLog()
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            commandCircuitBreakerCheck = breakerStore,
            boundaryRejectionLog = rejectionLog
        )
        try {
            val rejected = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-breaker-smoke",
                    "Idempotency-Key" to "idem-breaker-smoke-reject"
                ),
                body = validSubmitBody("cmd-breaker-smoke-reject", "trace-breaker-smoke-reject", "ord-breaker-smoke-reject")
            )
            assertEquals(503, rejected.status)
            assertContains(rejected.body, "\"code\":\"COMMAND_CIRCUIT_BREAKER_TRIPPED\"")
            assertEquals(
                404,
                get(
                    server.address.port,
                    "/api/v1/commands/cmd-breaker-smoke-reject",
                    headers = apiReadHeaders("client-breaker-smoke")
                ).status
            )
            assertEquals(1, rejectionLog.records.size)
            assertEquals("command-circuit-breaker", rejectionLog.records.single().guardrailType)
            assertEquals("INSTRUMENT", rejectionLog.records.single().scopeType)
            assertEquals("AAPL", rejectionLog.records.single().scopeId)

            breakerStore.setBreaker("INSTRUMENT", "AAPL", false, "cleared")
            val accepted = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-breaker-smoke",
                    "Idempotency-Key" to "idem-breaker-smoke-accept"
                ),
                body = validSubmitBody("cmd-breaker-smoke-accept", "trace-breaker-smoke-accept", "ord-breaker-smoke-accept")
            )

            assertEquals(202, accepted.status)
            assertContains(accepted.body, "\"commandId\":\"cmd-breaker-smoke-accept\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(
                200,
                get(
                    server.address.port,
                    "/api/v1/commands/cmd-breaker-smoke-accept",
                    headers = apiReadHeaders("client-breaker-smoke")
                ).status
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun instrumentPriceCollarRejectsThenClearAllowsCapturedAckCommand() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val collarStore = RecordingInstrumentPriceCollarStore()
        collarStore.setCollar("AAPL", "150000000000", "151000000000", "USD", "regular band")
        val rejectionLog = RecordingBoundaryRejectionLog()
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            instrumentPriceCollarCheck = collarStore,
            boundaryRejectionLog = rejectionLog
        )
        try {
            val rejected = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-collar-smoke",
                    "Idempotency-Key" to "idem-collar-smoke-reject"
                ),
                body = validSubmitBody(
                    "cmd-collar-smoke-reject",
                    "trace-collar-smoke-reject",
                    "ord-collar-smoke-reject",
                    extra = ",\"limitPrice\":\"149999999999\""
                )
            )
            assertEquals(422, rejected.status)
            assertContains(rejected.body, "\"code\":\"PRICE_COLLAR_LOW\"")
            assertEquals(
                404,
                get(
                    server.address.port,
                    "/api/v1/commands/cmd-collar-smoke-reject",
                    headers = apiReadHeaders("client-collar-smoke")
                ).status
            )
            assertEquals(1, rejectionLog.records.size)
            assertEquals("instrument-price-collar", rejectionLog.records.single().guardrailType)
            assertEquals("INSTRUMENT", rejectionLog.records.single().scopeType)
            assertEquals("AAPL", rejectionLog.records.single().scopeId)
            assertEquals("cmd-collar-smoke-reject", rejectionLog.records.single().request.commandId)

            collarStore.setCollar("AAPL", "", "", "USD", "cleared")
            val accepted = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-collar-smoke",
                    "Idempotency-Key" to "idem-collar-smoke-accept"
                ),
                body = validSubmitBody("cmd-collar-smoke-accept", "trace-collar-smoke-accept", "ord-collar-smoke-accept")
            )

            assertEquals(202, accepted.status)
            assertContains(accepted.body, "\"commandId\":\"cmd-collar-smoke-accept\"")
            assertEquals(0, gateway.submitCalls)
            assertEquals(
                200,
                get(
                    server.address.port,
                    "/api/v1/commands/cmd-collar-smoke-accept",
                    headers = apiReadHeaders("client-collar-smoke")
                ).status
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun asyncCommandStatsEndpointReturnsQueueDepths() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-async-stats"
                ),
                body = validSubmitBody("cmd-async-stats", "trace-async-stats", "ord-async-stats")
            )
            val stats = get(server.address.port, "/internal/commands/async/stats")

            assertEquals(202, submit.status)
            assertEquals(200, stats.status)
            assertContains(stats.body, "\"processingMode\":\"captured-ack\"")
            assertContains(stats.body, "\"workerThreads\":1")
            assertContains(stats.body, "\"sampleMs\":100")
            assertContains(stats.body, "\"RECEIVED\":1")
            assertContains(stats.body, "\"PROCESSING\":0")
            assertContains(stats.body, "\"COMPLETED\":0")
            assertContains(stats.body, "\"FAILED\":0")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun acceptedAsyncSubmitReturnsReceiptBeforeEngineCompletes() {
        val gateway = BlockingFirstSubmitGateway()
        val server = testServerWithGateway(
            gateway = gateway,
            commandProcessingMode = CommandProcessingMode.AcceptedAsync
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-accepted-async",
                    "Idempotency-Key" to "idem-accepted-async"
                ),
                body = validSubmitBody("cmd-accepted-async", "trace-accepted-async", "ord-accepted-async")
            )
            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-accepted-async",
                headers = apiReadHeaders("client-accepted-async")
            )

            assertEquals(202, response.status)
            assertContains(response.body, "\"commandId\":\"cmd-accepted-async\"")
            assertContains(response.body, "\"processingMode\":\"accepted-async\"")
            assertTrue(gateway.awaitFirstSubmit())
            assertEquals(200, status.status)
            assertContains(status.body, "\"processingMode\":\"accepted-async\"")
            assertTrue(status.body.contains("\"status\":\"ACCEPTED\"") || status.body.contains("\"status\":\"IN_FLIGHT\""))
            assertTrue(status.body.contains("\"internalStatus\":\"RECEIVED\"") || status.body.contains("\"internalStatus\":\"PROCESSING\""))

            gateway.release()
            val completed = waitForCommandStatus(
                server.address.port,
                "cmd-accepted-async",
                "COMPLETED",
                headers = apiReadHeaders("client-accepted-async")
            )
            assertContains(completed.body, "\"status\":\"COMPLETED\"")
            assertContains(completed.body, "\"responseStatus\":200")
        } finally {
            gateway.release()
            server.stop(0)
        }
    }

    @Test
    fun acceptedAsyncStatsEndpointReportsLaneDrain() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            commandProcessingMode = CommandProcessingMode.AcceptedAsync
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-accepted-async-stats",
                    "Idempotency-Key" to "idem-accepted-async-stats"
                ),
                body = validSubmitBody("cmd-accepted-async-stats", "trace-accepted-async-stats", "ord-accepted-async-stats")
            )
            waitForCommandStatus(
                server.address.port,
                "cmd-accepted-async-stats",
                "COMPLETED",
                headers = apiReadHeaders("client-accepted-async-stats")
            )
            val stats = get(server.address.port, "/internal/commands/async/stats")

            assertEquals(202, response.status)
            assertEquals(200, stats.status)
            assertContains(stats.body, "\"processingMode\":\"accepted-async\"")
            assertContains(stats.body, "\"acceptedAsync\"")
            assertContains(stats.body, "\"enabled\":true")
            assertContains(stats.body, "\"activeLaneCount\"")
            assertContains(stats.body, "\"lanes\"")
            assertContains(stats.body, "\"received\":1")
            assertContains(stats.body, "\"completed\":1")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun nettyHotPathAcceptedAsyncSubmitStatusAndStats() {
        val server = testNettyHotPathServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            commandProcessingMode = CommandProcessingMode.AcceptedAsync
        )
        try {
            val health = get(server.port, "/health")
            val response = post(
                port = server.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-netty-accepted-async",
                    "Idempotency-Key" to "idem-netty-accepted-async"
                ),
                body = validSubmitBody("cmd-netty-accepted-async", "trace-netty-accepted-async", "ord-netty-accepted-async")
            )
            val completed = waitForCommandStatus(
                server.port,
                "cmd-netty-accepted-async",
                "COMPLETED",
                headers = apiReadHeaders("client-netty-accepted-async")
            )
            val stats = get(server.port, "/internal/commands/async/stats")

            assertEquals(200, health.status)
            assertEquals(202, response.status)
            assertContains(response.body, "\"commandId\":\"cmd-netty-accepted-async\"")
            assertEquals(200, completed.status)
            assertContains(completed.body, "\"status\":\"COMPLETED\"")
            assertEquals(200, stats.status)
            assertContains(stats.body, "\"processingMode\":\"accepted-async\"")
            assertContains(stats.body, "\"inFlightPerLane\"")
            assertContains(stats.body, "\"completed\":1")
        } finally {
            server.stop()
        }
    }

    @Test
    fun nettyHotPathServesAdminRiskGatewayReads() {
        val riskStore = RecordingAccountRiskStore()
        riskStore.upsertControl(
            scopeType = "ACCOUNT",
            scopeId = "account-1",
            decision = AccountRiskDecision.REJECT,
            reason = "test risk",
            maxQuantityUnits = "",
            maxNotional = "",
            currency = "USD"
        )
        val server = testNettyHotPathServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            accountRiskCheck = riskStore,
            localDevAdminAuthBypass = true
        )
        try {
            val controls = get(server.port, "/admin/v1/risk/account-controls")

            assertEquals(200, controls.status, controls.body)
            assertContains(controls.body, "\"scopeId\":\"account-1\"")
            assertContains(controls.body, "\"decision\":\"REJECT\"")
        } finally {
            server.stop()
        }
    }

    @Test
    fun nettyHotPathStreamAckPublishesThroughPartitionedPipeline() {
        val publisher = RecordingStreamCommandPublisher()
        val streamPublisher = PartitionedStreamCommandPublisher(
            delegate = publisher,
            queueCapacityPerLane = 10,
            maxInFlightPerLane = 1
        )
        val server = testNettyHotPathServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = streamPublisher
        )
        try {
            val response = post(
                port = server.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-netty-stream",
                    "Idempotency-Key" to "idem-netty-stream"
                ),
                body = validSubmitBody("cmd-netty-stream", "trace-netty-stream", "ord-netty-stream", extra = streamRoutingExtra())
            )
            val health = get(server.port, "/internal/stream-ack/health")

            assertEquals(202, response.status)
            assertContains(response.body, "\"processingMode\":\"stream-ack\"")
            assertContains(response.body, "\"statusUrl\":\"/api/v1/commands/cmd-netty-stream\"")
            assertEquals(1, publisher.published.size)
            assertEquals(200, health.status)
            assertContains(health.body, "\"publishMode\":\"partitioned-blocking-delegate:sync\"")
            assertContains(health.body, "\"publishLaneCount\":16")
            assertContains(health.body, "\"publishCompleted\":1")
        } finally {
            server.stop()
        }
    }

    @Test
    fun nettyHotPathStreamAckPublishesModifyAndCancelRoutes() {
        val publisher = RecordingStreamCommandPublisher()
        val streamPublisher = PartitionedStreamCommandPublisher(
            delegate = publisher,
            queueCapacityPerLane = 10,
            maxInFlightPerLane = 1
        )
        val server = testNettyHotPathServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = streamPublisher
        )
        try {
            val modifyCancelRoutingExtra = ""","instrumentId":"AAPL"${streamRoutingExtra()}"""
            val modify = post(
                port = server.port,
                path = "/api/v1/orders/modify",
                headers = mapOf(
                    "X-Client-Id" to "client-netty-stream",
                    "Idempotency-Key" to "idem-netty-stream-modify"
                ),
                body = validModifyBody("cmd-netty-stream-modify", "trace-netty-stream-modify", "ord-netty-stream", extra = modifyCancelRoutingExtra)
            )
            val cancel = post(
                port = server.port,
                path = "/api/v1/orders/cancel",
                headers = mapOf(
                    "X-Client-Id" to "client-netty-stream",
                    "Idempotency-Key" to "idem-netty-stream-cancel"
                ),
                body = validCancelBody("cmd-netty-stream-cancel", "trace-netty-stream-cancel", "ord-netty-stream", extra = modifyCancelRoutingExtra)
            )

            assertEquals(202, modify.status, modify.body)
            assertEquals(202, cancel.status, cancel.body)
            assertContains(modify.body, "\"processingMode\":\"stream-ack\"")
            assertContains(cancel.body, "\"processingMode\":\"stream-ack\"")
            assertEquals(
                listOf("/api/v1/orders/modify", "/api/v1/orders/cancel"),
                publisher.published.map { it.route }
            )
            assertEquals(listOf("ModifyOrder", "CancelOrder"), publisher.published.map { it.commandType })
        } finally {
            server.stop()
        }
    }

    @Test
    fun commandAccountingEndpointReturnsRunScopedCounts() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-accounting"
                ),
                body = validSubmitBody("cmd-accounting", "trace-accounting", "ord-accounting")
                    .replace("\"timeInForce\":\"DAY\"", "\"timeInForce\":\"DAY\",\"runId\":\"run-1\",\"runKind\":\"stress\",\"scenarioId\":\"scenario-1\"")
            )
            val accounting = get(server.address.port, "/internal/commands/accounting?runId=run-1")

            assertEquals(202, submit.status)
            assertEquals(200, accounting.status)
            assertContains(accounting.body, "\"available\":true")
            assertContains(accounting.body, "\"runId\":\"run-1\"")
            assertContains(accounting.body, "\"accepted\":1")
            assertContains(accounting.body, "\"received\":1")
            assertContains(accounting.body, "\"active\":1")
            assertContains(accounting.body, "\"terminal\":0")
            assertContains(accounting.body, "\"accountingGap\":0")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedAckBackpressureRejectsNewCommandsButAllowsDuplicateReplay() {
        val commandLogStore = InMemoryCommandLogStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = commandLogStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            commandIntakeMaxActive = 1,
            commandIntakeBackpressureSampleMs = 0
        )
        try {
            val first = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-backpressure-1"
                ),
                body = validSubmitBody("cmd-backpressure-1", "trace-backpressure-1", "ord-backpressure-1")
            )
            val duplicate = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-backpressure-1"
                ),
                body = validSubmitBody("cmd-backpressure-1", "trace-backpressure-1", "ord-backpressure-1")
            )
            val rejected = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-backpressure-2"
                ),
                body = validSubmitBody("cmd-backpressure-2", "trace-backpressure-2", "ord-backpressure-2")
            )

            assertEquals(202, first.status)
            assertEquals(202, duplicate.status)
            assertEquals(429, rejected.status)
            assertContains(rejected.body, "\"code\":\"COMMAND_INTAKE_BACKPRESSURE\"")
            assertEquals(1L, commandLogStore.accountingSnapshot().accepted)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun dbPoolStatsEndpointReturnsPoolList() {
        val server = testServer()
        try {
            val response = get(server.address.port, "/internal/perf/db-pools")

            assertEquals(200, response.status)
            assertContains(response.body, "\"pools\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedAckReplaysFirstAcceptedResponseForSameIdempotencyKey() {
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = InMemoryCommandLogStore(),
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-ack-replay"
            )
            val first = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers,
                body = validSubmitBody("cmd-ack-first", "trace-ack-first", "ord-ack-first")
            )
            val second = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers,
                body = validSubmitBody("cmd-ack-second", "trace-ack-second", "ord-ack-second")
            )

            assertEquals(202, first.status)
            assertEquals(202, second.status)
            assertEquals(first.body, second.body)
            assertContains(second.body, "\"commandId\":\"cmd-ack-first\"")
            assertEquals(0, gateway.submitCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedAckSkipsIdempotencyStoreForNewAcceptedCommand() {
        val idempotencyStore = CountingIdempotencyStore()
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = InMemoryCommandLogStore(),
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            captureStore = captureStore,
            commandProcessingMode = CommandProcessingMode.CapturedAck,
            idempotencyStore = idempotencyStore
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-ack-no-idempotency"
                ),
                body = validSubmitBody("cmd-ack-no-idempotency", "trace-ack-no-idempotency", "ord-ack-no-idempotency")
            )

            assertEquals(202, response.status)
            assertContains(response.body, "\"commandId\":\"cmd-ack-no-idempotency\"")
            assertEquals(0, idempotencyStore.findCalls)
            assertEquals(0, idempotencyStore.saveCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckPublishesCommandAndReturnsAcceptedReferenceWithoutExecutingEngine() {
        val publisher = RecordingStreamCommandPublisher()
        val gateway = CountingEngineGateway(EchoOrderEngineGateway())
        val server = testServerWithGateway(
            gateway = gateway,
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-1"
                ),
                body = validSubmitBody("cmd-stream-1", "trace-stream-1", "ord-stream-1", extra = streamRoutingExtra())
            )

            assertEquals(202, response.status)
            assertContains(response.body, "\"commandId\":\"cmd-stream-1\"")
            assertContains(response.body, "\"status\":\"ACCEPTED\"")
            assertContains(response.body, "\"processingMode\":\"stream-ack\"")
            assertContains(response.body, "\"statusUrl\":\"/api/v1/commands/cmd-stream-1\"")
            assertEquals(1, publisher.published.size)
            assertEquals("client-1|/api/v1/orders/submit|idem-stream-1", publisher.published.single().natsMessageId)
            assertEquals(0, gateway.submitCalls)

            val hotPath = get(server.address.port, "/internal/perf/hot-path")
            assertEquals(200, hotPath.status)
            assertContains(hotPath.body, "\"api.streamAck.reserve\"")
            assertContains(hotPath.body, "\"api.streamAck.publishAck\"")
            assertContains(hotPath.body, "\"api.streamAck.markPublished\"")
            assertContains(hotPath.body, "\"api.streamAck.total\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckCommandStatusReturnsScopedStreamReferenceBeforeMaterialization() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val submit = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-status-pending"
                ),
                body = validSubmitBody("cmd-stream-status-pending", "trace-stream-status-pending", "ord-stream-status-pending", extra = streamRoutingExtra())
            )
            assertEquals(202, submit.status)

            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-stream-status-pending",
                headers = apiReadHeaders()
            )

            assertEquals(200, status.status, status.body)
            assertContains(status.body, "\"status\":\"ACCEPTED\"")
            assertContains(status.body, "\"commandType\":\"SubmitOrder\"")
            assertContains(status.body, "\"participantId\":\"participant-1\"")
            assertContains(status.body, "\"source\":\"stream_reference\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRiskDisabledBotRejectsBeforeReserveOrPublish() {
        val publisher = RecordingStreamCommandPublisher()
        val intakeStore = InMemoryStreamCommandIntakeStore()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = intakeStore,
            streamCommandPublisher = publisher,
            accountRiskCheck = StaticAccountRiskCheck(disabledBots = setOf("bot-1"))
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-risk-disabled-bot"
                ),
                body = validSubmitBody("cmd-stream-risk-disabled-bot", "trace-stream-risk-disabled-bot", "ord-stream-risk-disabled-bot", extra = streamRoutingExtra())
            )

            assertEquals(403, response.status)
            assertContains(response.body, "\"code\":\"BOT_DISABLED\"")
            assertEquals(0, publisher.published.size)
            assertEquals(null, intakeStore.findByCommandId("cmd-stream-risk-disabled-bot"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckReplaysSameIdempotencyKeyAndBodyWithoutRepublishing() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-stream-replay"
            )
            val body = validSubmitBody("cmd-stream-replay", "trace-stream-replay", "ord-stream-replay", extra = streamRoutingExtra())
            val first = post(server.address.port, "/api/v1/orders/submit", headers, body)
            val second = post(server.address.port, "/api/v1/orders/submit", headers, body)

            assertEquals(202, first.status)
            assertEquals(202, second.status)
            assertContains(first.body, "\"commandId\":\"cmd-stream-replay\"")
            assertContains(second.body, "\"commandId\":\"cmd-stream-replay\"")
            assertContains(second.body, "\"statusUrl\":\"/api/v1/commands/cmd-stream-replay\"")
            assertEquals(1, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRejectsSameIdempotencyKeyWithDifferentBody() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-stream-conflict"
            )
            val first = post(
                server.address.port,
                "/api/v1/orders/submit",
                headers,
                validSubmitBody("cmd-stream-conflict", "trace-stream-conflict", "ord-stream-conflict", extra = streamRoutingExtra())
            )
            val second = post(
                server.address.port,
                "/api/v1/orders/submit",
                headers,
                validSubmitBody("cmd-stream-conflict-2", "trace-stream-conflict-2", "ord-stream-conflict-2", extra = streamRoutingExtra())
            )

            assertEquals(202, first.status)
            assertEquals(409, second.status)
            assertContains(second.body, "\"code\":\"IDEMPOTENCY_PAYLOAD_CONFLICT\"")
            assertEquals(1, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRequiresRoutingMetadataBeforePublish() {
        val publisher = RecordingStreamCommandPublisher()
        val intakeStore = InMemoryStreamCommandIntakeStore()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = intakeStore,
            streamCommandPublisher = publisher
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/cancel",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-missing-routing"
                ),
                body = validCancelBody("cmd-stream-missing-routing", "trace-stream-missing-routing", "ord-stream-missing-routing")
            )

            assertEquals(400, response.status)
            assertContains(response.body, "\"code\":\"STREAM_ROUTING_METADATA_REQUIRED\"")
            assertEquals(0, publisher.published.size)
            assertEquals(null, intakeStore.findByCommandId("cmd-stream-missing-routing"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRejectsMalformedJsonBeforeReserveOrPublish() {
        val publisher = RecordingStreamCommandPublisher()
        val intakeStore = InMemoryStreamCommandIntakeStore()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = intakeStore,
            streamCommandPublisher = publisher
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-malformed"
                ),
                body = """{"commandId":"cmd-stream-malformed""""
            )

            assertEquals(400, response.status)
            assertContains(response.body, "\"code\":\"VALIDATION_ERROR\"")
            assertContains(response.body, "\"message\":\"invalid json payload\"")
            assertEquals(0, publisher.published.size)
            assertEquals(null, intakeStore.findByCommandId("cmd-stream-malformed"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckReturnsUnavailableWhenPublishAckFails() {
        val publisher = RecordingStreamCommandPublisher(fail = true)
        val intakeStore = InMemoryStreamCommandIntakeStore()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = intakeStore,
            streamCommandPublisher = publisher
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-publish-fail"
                ),
                body = validSubmitBody("cmd-stream-publish-fail", "trace-stream-publish-fail", "ord-stream-publish-fail", extra = streamRoutingExtra())
            )

            assertEquals(503, response.status)
            assertContains(response.body, "\"error\":\"stream command publish unavailable\"")
            assertEquals(1, publisher.published.size)
            assertEquals(0L, intakeStore.findByCommandId("cmd-stream-publish-fail")?.streamSequence)

            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-stream-publish-fail",
                headers = apiReadHeaders()
            )
            assertEquals(404, status.status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckLatePublishAckAfterResponseTimeoutReplaysAcceptedReference() {
        val store = InMemoryStreamCommandIntakeStore()
        val publisher = DelayedAckStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = store,
            streamCommandPublisher = publisher,
            streamCommandPublishResponseTimeoutMs = 25L
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-stream-late-ack"
            )
            val body = validSubmitBody("cmd-stream-late-ack", "trace-stream-late-ack", "ord-stream-late-ack", extra = streamRoutingExtra())
            val first = post(server.address.port, "/api/v1/orders/submit", headers, body)
            assertEquals(503, first.status)
            assertContains(first.body, "timed out waiting 25ms")
            assertEquals(1, publisher.published.size)

            val beforeAckStatus = get(
                server.address.port,
                "/api/v1/commands/cmd-stream-late-ack",
                headers = apiReadHeaders()
            )
            assertEquals(404, beforeAckStatus.status)

            publisher.complete(StreamPublishAck("REEF_COMMANDS", 123L))

            val replay = post(server.address.port, "/api/v1/orders/submit", headers, body)
            assertEquals(202, replay.status)
            assertContains(replay.body, "\"commandId\":\"cmd-stream-late-ack\"")
            assertEquals(1, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRetryAfterPublishAckBeforeMarkerUpdateRepublishesAndConverges() {
        val store = SkipFirstPublishedMarkerIntakeStore(InMemoryStreamCommandIntakeStore())
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = store,
            streamCommandPublisher = publisher
        )
        try {
            val headers = mapOf(
                "X-Client-Id" to "client-1",
                "Idempotency-Key" to "idem-stream-marker-crash"
            )
            val body = validSubmitBody(
                "cmd-stream-marker-crash",
                "trace-stream-marker-crash",
                "ord-stream-marker-crash",
                extra = streamRoutingExtra()
            )

            val first = post(server.address.port, "/api/v1/orders/submit", headers, body)
            assertEquals(202, first.status)
            assertContains(first.body, "\"commandId\":\"cmd-stream-marker-crash\"")
            assertEquals(1, publisher.published.size)
            assertEquals(0L, store.findByCommandId("cmd-stream-marker-crash")?.streamSequence)

            val retry = post(server.address.port, "/api/v1/orders/submit", headers, body)
            assertEquals(202, retry.status)
            assertContains(retry.body, "\"commandId\":\"cmd-stream-marker-crash\"")
            assertEquals(2, publisher.published.size)
            assertEquals(2L, store.findByCommandId("cmd-stream-marker-crash")?.streamSequence)

            val replay = post(server.address.port, "/api/v1/orders/submit", headers, body)
            assertEquals(202, replay.status)
            assertContains(replay.body, "\"commandId\":\"cmd-stream-marker-crash\"")
            assertEquals(2, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckHealthEndpointReturnsStreamSnapshot() {
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = RecordingStreamCommandPublisher(),
            streamCommandHealthCheck = FixedStreamCommandHealthCheck(
                StreamCommandHealthSnapshot(
                    available = true,
                    streamName = "REEF_COMMANDS",
                    messageCount = 3,
                    byteCount = 512,
                    maxBytes = 1024,
                    storageUtilization = 0.5,
                    publishAckLastMs = 7,
                    publishAckMaxMs = 11,
                    producerMetrics = mapOf("request-latency-max" to 13.0)
                )
            )
        )
        try {
            val response = get(server.address.port, "/internal/stream-ack/health")

            assertEquals(200, response.status)
            assertContains(response.body, "\"available\":true")
            assertContains(response.body, "\"processingMode\":\"stream-ack\"")
            assertContains(response.body, "\"stream\":\"REEF_COMMANDS\"")
            assertContains(response.body, "\"messages\":3")
            assertContains(response.body, "\"storageUtilization\":0.5")
            assertContains(response.body, "\"publishAckLastMs\":7")
            assertContains(response.body, "\"producerMetrics\":{\"request-latency-max\":13.0}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckBackpressureCachesStreamHealthWithinSampleWindow() {
        val publisher = RecordingStreamCommandPublisher()
        val healthCheck = CountingStreamCommandHealthCheck(
            StreamCommandHealthSnapshot(
                available = true,
                streamName = "REEF_COMMANDS",
                byteCount = 100,
                maxBytes = 1000,
                storageUtilization = 0.1
            )
        )
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher,
            streamCommandHealthCheck = healthCheck,
            streamCommandBackpressureSampleMs = 10_000L
        )
        try {
            val first = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-health-cache-1"
                ),
                body = validSubmitBody("cmd-stream-health-cache-1", "trace-stream-health-cache-1", "ord-stream-health-cache-1", extra = streamRoutingExtra())
            )
            val second = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-health-cache-2"
                ),
                body = validSubmitBody("cmd-stream-health-cache-2", "trace-stream-health-cache-2", "ord-stream-health-cache-2", extra = streamRoutingExtra())
            )

            assertEquals(202, first.status)
            assertEquals(202, second.status)
            assertEquals(2, publisher.published.size)
            assertEquals(1, healthCheck.calls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRejectsBeforePublishWhenStreamHealthUnavailable() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher,
            streamCommandHealthCheck = FixedStreamCommandHealthCheck(
                StreamCommandHealthSnapshot(
                    available = false,
                    streamName = "REEF_COMMANDS",
                    error = "stream not found"
                )
            )
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-health-down"
                ),
                body = validSubmitBody("cmd-stream-health-down", "trace-stream-health-down", "ord-stream-health-down", extra = streamRoutingExtra())
            )

            assertEquals(503, response.status)
            assertContains(response.body, "\"code\":\"STREAM_COMMAND_STREAM_UNAVAILABLE\"")
            assertEquals(0, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamAckRejectsBeforePublishWhenStreamStorageIsOverThreshold() {
        val publisher = RecordingStreamCommandPublisher()
        val server = testServerWithGateway(
            gateway = CountingEngineGateway(EchoOrderEngineGateway()),
            commandProcessingMode = CommandProcessingMode.StreamAck,
            streamCommandIntakeStore = InMemoryStreamCommandIntakeStore(),
            streamCommandPublisher = publisher,
            streamCommandHealthCheck = FixedStreamCommandHealthCheck(
                StreamCommandHealthSnapshot(
                    available = true,
                    streamName = "REEF_COMMANDS",
                    byteCount = 950,
                    maxBytes = 1000,
                    storageUtilization = 0.95
                )
            ),
            streamCommandMaxStorageUtilization = 0.90
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-stream-storage-full"
                ),
                body = validSubmitBody("cmd-stream-storage-full", "trace-stream-storage-full", "ord-stream-storage-full", extra = streamRoutingExtra())
            )

            assertEquals(429, response.status)
            assertContains(response.body, "\"code\":\"STREAM_COMMAND_BACKPRESSURE\"")
            assertEquals(0, publisher.published.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun capturedModesRequireCommandStatusLookup() {
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = NoopCommandCaptureStore(),
            commandProcessingMode = CommandProcessingMode.CapturedAck
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-no-status"
                ),
                body = validSubmitBody("cmd-no-status", "trace-no-status", "ord-no-status")
            )

            assertEquals(503, response.status)
            assertContains(response.body, "\"error\":\"command status unavailable\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1CommandStatusEndpointReturnsNotFoundForUnknownCommand() {
        val captureStore = CommandLogCommandCaptureStore(
            delegate = NoopCommandCaptureStore(),
            commandLogStore = InMemoryCommandLogStore()
        )
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val response = get(server.address.port, "/api/v1/commands/missing-command", headers = apiReadHeaders())

            assertEquals(404, response.status)
            assertContains(response.body, "\"error\":\"command not found\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun syncResultCommandCaptureExposesCommandStatusEndpoint() {
        val captureStore = InMemoryCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val submitted = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-sync-status",
                    "Idempotency-Key" to "idem-sync-status"
                ),
                body = validSubmitBody("cmd-sync-status", "trace-sync-status", "ord-sync-status")
            )
            val ready = get(server.address.port, "/readyz")
            val status = get(
                server.address.port,
                "/api/v1/commands/cmd-sync-status",
                headers = apiReadHeaders("client-sync-status")
            )

            assertEquals(200, submitted.status)
            assertEquals(200, ready.status)
            assertContains(ready.body, "\"commandStatusLookup\":true")
            assertEquals(200, status.status)
            assertContains(status.body, "\"commandId\":\"cmd-sync-status\"")
            assertContains(status.body, "\"status\":\"COMPLETED\"")
            assertContains(status.body, "\"processingMode\":\"sync-result\"")
            assertContains(status.body, "\"source\":\"command_capture\"")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitRejectsOversizedBodyBeforeCapture() {
        val captureStore = RecordingCommandCaptureStore()
        val server = testServerWithGateway(
            gateway = EchoOrderEngineGateway(),
            captureStore = captureStore
        )
        try {
            val response = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = mapOf(
                    "X-Client-Id" to "client-1",
                    "Idempotency-Key" to "idem-too-large"
                ),
                body = """{"payload":"${"x".repeat(1024 * 1024)}"}"""
            )
            assertEquals(413, response.status)
            assertContains(response.body, "\"error\":\"request body too large\"")
            assertEquals(0, captureStore.receivedCalls)
            assertEquals(0, captureStore.completedCalls)
            assertEquals(0, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun apiV1SubmitBlocksClientAfterRejectRateThreshold() {
        val captureStore = RecordingCommandCaptureStore()
        val hook = RejectRateAbuseProtectionHook(
            maxRejects = 1,
            windowSeconds = 60,
            blockSeconds = 120,
            trackedRejectCodes = setOf("INVALID_STATE"),
            trackedRoutes = setOf("/api/v1/orders/submit"),
            clock = { java.time.Instant.ofEpochSecond(100) }
        )
        val server = testServerWithGateway(
            gateway = StaticRejectedEngineGateway("INVALID_STATE", "invalid transition"),
            captureStore = captureStore,
            abuseProtectionHook = hook
        )
        try {
            val headers = mapOf("X-Client-Id" to "client-1")
            seedReferenceData(server.address.port)
            val first = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers + ("Idempotency-Key" to "idem-breaker-1"),
                body = validSubmitBody("cmd-breaker-1", "trace-breaker-1", "ord-breaker-1")
            )
            assertEquals(200, first.status)
            assertContains(first.body, "\"rejected\"")
            assertContains(first.body, "\"code\":\"INVALID_STATE\"")

            val second = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers + ("Idempotency-Key" to "idem-breaker-2"),
                body = validSubmitBody("cmd-breaker-2", "trace-breaker-2", "ord-breaker-2")
            )
            assertEquals(200, second.status)
            assertContains(second.body, "\"code\":\"INVALID_STATE\"")

            val third = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers + ("Idempotency-Key" to "idem-breaker-3"),
                body = validSubmitBody("cmd-breaker-3", "trace-breaker-3", "ord-breaker-3")
            )
            assertEquals(429, third.status)
            assertContains(third.body, "\"code\":\"ABUSE_BLOCKED\"")
            assertEquals(3, captureStore.receivedCalls)
            assertEquals(2, captureStore.completedCalls)
            assertEquals(1, captureStore.failedCalls)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun internalAbuseStatsEndpointReturnsCountersAndConfig() {
        var now = java.time.Instant.ofEpochSecond(300)
        val hook = RejectRateAbuseProtectionHook(
            maxRejects = 1,
            windowSeconds = 60,
            blockSeconds = 30,
            trackedRejectCodes = setOf("INVALID_STATE"),
            trackedRoutes = setOf("/api/v1/orders/submit"),
            routePolicies = mapOf(
                "/api/v1/orders/submit" to RejectRatePolicy(1, 60, 30)
            ),
            clock = { now }
        )
        val server = testServerWithGateway(
            gateway = StaticRejectedEngineGateway("INVALID_STATE", "invalid transition"),
            abuseProtectionHook = hook
        )
        try {
            seedReferenceData(server.address.port)
            val headers = mapOf("X-Client-Id" to "client-stats")
            post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers + ("Idempotency-Key" to "idem-stats-1"),
                body = validSubmitBody("cmd-stats-1", "trace-stats-1", "ord-stats-1")
            )
            post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers + ("Idempotency-Key" to "idem-stats-2"),
                body = validSubmitBody("cmd-stats-2", "trace-stats-2", "ord-stats-2")
            )
            val blocked = post(
                port = server.address.port,
                path = "/api/v1/orders/submit",
                headers = headers + ("Idempotency-Key" to "idem-stats-3"),
                body = validSubmitBody("cmd-stats-3", "trace-stats-3", "ord-stats-3")
            )
            assertEquals(429, blocked.status)

            val statsResponse = get(server.address.port, "/internal/boundary/abuse/stats")
            assertEquals(200, statsResponse.status)
            assertContains(statsResponse.body, "\"mode\":\"reject-rate\"")
            assertContains(statsResponse.body, "\"enabled\":true")
            assertContains(statsResponse.body, "\"trackedRoutes\":[\"/api/v1/orders/submit\"]")
            assertContains(statsResponse.body, "\"routePolicyOverrides\":{\"/api/v1/orders/submit\":\"1/60/30\"}")
            assertContains(statsResponse.body, "\"trips\":1")
            assertContains(statsResponse.body, "\"activeBlockedClients\":1")

            now = now.plusSeconds(40)
            assertFalse(hook.allow("client-stats", "/api/v1/orders/submit")?.code == "ABUSE_BLOCKED")
        } finally {
            server.stop(0)
        }
    }

    data class HttpResponse(
        val status: Int,
        val body: String,
        val headers: Map<String, List<String>> = emptyMap()
    )

    private data class TestAdminAuth(
        val authService: AdminAuthService,
        val identityService: AdminIdentityService
    )

    private fun testAdminAuth(): TestAdminAuth {
        val identityStore = InMemoryAdminIdentityStore()
        val authStore = InMemoryAdminAuthStore()
        val identityService = AdminIdentityService(identityStore)
        return TestAdminAuth(
            authService = AdminAuthService(
                authStore = authStore,
                identityStore = identityStore,
                tokenFactory = { "tok-${tokenCounter.incrementAndGet()}-abcdefghijklmnopqrstuvwxyz0123456789" },
                tokenIdFactory = { "svc-${tokenCounter.incrementAndGet()}" }
            ),
            identityService = identityService
        )
    }

    private fun grantTrustedPlatformAdmin(auth: TestAdminAuth, reefUserId: String) {
        auth.identityService.updateTrustState("admin-cli", reefUserId, AdminTrustState.Trusted)
        auth.identityService.assignRole("admin-cli", reefUserId, AdminIdentityService.RolePlatformAdmin)
    }

    private fun grantTrustedOperator(auth: TestAdminAuth, reefUserId: String) {
        auth.identityService.updateTrustState("admin-cli", reefUserId, AdminTrustState.Trusted)
        auth.identityService.assignRole("admin-cli", reefUserId, AdminIdentityService.RoleOperator)
    }

    private class FakeAdminGitHubOAuthClient : AdminGitHubOAuthClient {
        override fun authorizationUrl(stateToken: String): String {
            return "https://github.test/oauth?state=$stateToken"
        }

        override fun exchangeCode(code: String): GitHubUserIdentity {
            require(code == "github-code") { "unexpected code" }
            return GitHubUserIdentity(12345, "octo", "Octo User")
        }
    }

    private class ThrowingAdminGitHubOAuthClient : AdminGitHubOAuthClient {
        override fun authorizationUrl(stateToken: String): String {
            return "https://github.test/oauth?state=$stateToken"
        }

        override fun exchangeCode(code: String): GitHubUserIdentity {
            throw IOException("github unavailable")
        }
    }

    private companion object {
        val tokenCounter = AtomicInteger(0)
    }

    private fun testServer(boundary: ExternalApiBoundary = ExternalApiBoundary()): com.sun.net.httpserver.HttpServer {
        return testServerWithGateway(StaticAcceptedEngineGateway(), boundary = boundary)
    }

    private fun testServerWithGateway(
        gateway: EngineGateway,
        runtimeRole: PlatformRuntimeRole = PlatformRuntimeRole.Api,
        boundary: ExternalApiBoundary = ExternalApiBoundary(),
        captureStore: CommandCaptureStore = NoopCommandCaptureStore(),
        abuseProtectionHook: AbuseProtectionHook = AllowAllAbuseProtectionHook(),
        accountRiskCheck: AccountRiskCheck = AllowAllAccountRiskCheck(),
        accountRiskControlStore: AccountRiskControlStore? = accountRiskCheck as? AccountRiskControlStore,
        accountRiskDecisionLog: AccountRiskDecisionLog? = accountRiskCheck as? AccountRiskDecisionLog,
        commandCircuitBreakerCheck: CommandCircuitBreakerCheck = AllowAllCommandCircuitBreakerCheck(),
        commandCircuitBreakerStore: CommandCircuitBreakerStore? = commandCircuitBreakerCheck as? CommandCircuitBreakerStore,
        instrumentPriceCollarCheck: InstrumentPriceCollarCheck = AllowAllInstrumentPriceCollarCheck(),
        instrumentPriceCollarStore: InstrumentPriceCollarStore? = instrumentPriceCollarCheck as? InstrumentPriceCollarStore,
        adminAuthService: AdminAuthService? = null,
        adminIdentityService: AdminIdentityService? = null,
        adminGitHubOAuthClient: AdminGitHubOAuthClient? = null,
        settlementFactStore: SettlementFactStore? = null,
        settlementObligationMaterializer: TradeSettlementObligationMaterializer? = null,
        defaultPostTradeProfileId: String = DefaultPostTradeProfileId,
        defaultPostTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
        postTradeProfileResolver: PostTradeProfileResolver =
            PostTradeProfileResolver.envOnly(defaultPostTradeProfileId, defaultPostTradePolicyVersion),
        scenarioRunPostTradeProfileLookup: (String) -> String? = { null },
        venueSessionPostTradeProfileLookup: (String) -> String? = { null },
        boundaryRejectionLog: BoundaryRejectionLog = NoopBoundaryRejectionLog(),
        commandProcessingMode: CommandProcessingMode = CommandProcessingMode.SyncResult,
        legacyMutationRoutesEnabled: Boolean = true,
        localDevAdminAuthBypass: Boolean = false,
        seedOrderAuthorization: Boolean = true,
        commandIntakeMaxActive: Long = 0L,
        commandIntakeMaxStaleProcessing: Long = 0L,
        commandIntakeBackpressureSampleMs: Long = 100L,
        idempotencyStore: IdempotencyStore = InMemoryIdempotencyStore(),
        streamCommandIntakeStore: StreamCommandIntakeStore? = null,
        streamCommandPublisher: StreamCommandPublisher? = null,
        streamCommandHealthCheck: StreamCommandHealthCheck? = streamCommandPublisher as? StreamCommandHealthCheck,
        streamCommandConfig: StreamCommandConfig = StreamCommandConfig(),
        streamCommandMaxStorageUtilization: Double = 0.95,
        streamCommandBackpressureSampleMs: Long = 100L,
        streamCommandPublishResponseTimeoutMs: Long = 2_000L,
        venueEventMaterializerEnabled: Boolean = false,
        localDevAdminUiBaseUrl: String? = null,
        runtimePersistence: InMemoryRuntimePersistence = InMemoryRuntimePersistence(),
        productRouteExtensions: List<OptionalProductRouteExtension> = emptyList()
    ): com.sun.net.httpserver.HttpServer {
        val persistence = runtimePersistence
        seedOrderReferenceData(persistence)
        if (seedOrderAuthorization) {
            seedOrderAuthorization(
                persistence,
                "bot-capture-1",
                "bot-1",
                "bot-2"
            )
        }
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        return PlatformHttpServer(
            port = 0,
            runtimeRole = runtimeRole,
            api = api,
            boundary = boundary,
            abuseProtectionHook = abuseProtectionHook,
            accountRiskCheck = accountRiskCheck,
            accountRiskControlStore = accountRiskControlStore,
            accountRiskDecisionLog = accountRiskDecisionLog,
            commandCircuitBreakerCheck = commandCircuitBreakerCheck,
            commandCircuitBreakerStore = commandCircuitBreakerStore,
            instrumentPriceCollarCheck = instrumentPriceCollarCheck,
            instrumentPriceCollarStore = instrumentPriceCollarStore,
            adminAuthService = adminAuthService,
            adminIdentityService = adminIdentityService,
            adminGitHubOAuthClient = adminGitHubOAuthClient,
            settlementFactStore = settlementFactStore,
            settlementObligationMaterializer = settlementObligationMaterializer,
            defaultPostTradeProfileId = defaultPostTradeProfileId,
            defaultPostTradePolicyVersion = defaultPostTradePolicyVersion,
            postTradeProfileResolver = postTradeProfileResolver,
            scenarioRunPostTradeProfileLookup = scenarioRunPostTradeProfileLookup,
            venueSessionPostTradeProfileLookup = venueSessionPostTradeProfileLookup,
            boundaryRejectionLog = boundaryRejectionLog,
            idempotencyStore = idempotencyStore,
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            commandCaptureStore = captureStore,
            commandStatusLookup = captureStore as? CommandStatusLookup,
            streamCommandIntakeStore = streamCommandIntakeStore,
            streamCommandPublisher = streamCommandPublisher,
            streamCommandHealthCheck = streamCommandHealthCheck,
            streamCommandConfig = streamCommandConfig,
            streamCommandMaxStorageUtilization = streamCommandMaxStorageUtilization,
            streamCommandBackpressureSampleMs = streamCommandBackpressureSampleMs,
            streamCommandPublishResponseTimeoutMs = streamCommandPublishResponseTimeoutMs,
            venueEventMaterializerEnabled = venueEventMaterializerEnabled,
            commandProcessingMode = commandProcessingMode,
            commandIntakeMaxActive = commandIntakeMaxActive,
            commandIntakeMaxStaleProcessing = commandIntakeMaxStaleProcessing,
            commandIntakeBackpressureSampleMs = commandIntakeBackpressureSampleMs,
            legacyMutationRoutesEnabled = legacyMutationRoutesEnabled,
            localDevAdminAuthBypass = localDevAdminAuthBypass,
            localDevAdminUiBaseUrl = localDevAdminUiBaseUrl,
            productRouteExtensions = productRouteExtensions
        ).start()
    }

    private fun testNettyHotPathServerWithGateway(
        gateway: EngineGateway,
        runtimeRole: PlatformRuntimeRole = PlatformRuntimeRole.Api,
        boundary: ExternalApiBoundary = ExternalApiBoundary(),
        captureStore: CommandCaptureStore = NoopCommandCaptureStore(),
        abuseProtectionHook: AbuseProtectionHook = AllowAllAbuseProtectionHook(),
        accountRiskCheck: AccountRiskCheck = AllowAllAccountRiskCheck(),
        accountRiskControlStore: AccountRiskControlStore? = accountRiskCheck as? AccountRiskControlStore,
        accountRiskDecisionLog: AccountRiskDecisionLog? = accountRiskCheck as? AccountRiskDecisionLog,
        commandCircuitBreakerCheck: CommandCircuitBreakerCheck = AllowAllCommandCircuitBreakerCheck(),
        commandCircuitBreakerStore: CommandCircuitBreakerStore? = commandCircuitBreakerCheck as? CommandCircuitBreakerStore,
        commandProcessingMode: CommandProcessingMode = CommandProcessingMode.SyncResult,
        commandIntakeMaxActive: Long = 0L,
        commandIntakeMaxStaleProcessing: Long = 0L,
        commandIntakeBackpressureSampleMs: Long = 100L,
        idempotencyStore: IdempotencyStore = InMemoryIdempotencyStore(),
        streamCommandIntakeStore: StreamCommandIntakeStore? = null,
        streamCommandPublisher: StreamCommandPublisher? = null,
        streamCommandHealthCheck: StreamCommandHealthCheck? = streamCommandPublisher as? StreamCommandHealthCheck,
        streamCommandConfig: StreamCommandConfig = StreamCommandConfig(),
        localDevAdminAuthBypass: Boolean = false
    ): RunningPlatformNettyServer {
        val persistence = InMemoryRuntimePersistence()
        seedOrderReferenceData(persistence)
        seedOrderAuthorization(
            persistence,
            "bot-capture-1",
            "bot-1",
            "bot-2"
        )
        val api = PlatformApi(
            OrderApplicationService(
                engineGateway = gateway,
                runtimePersistence = persistence
            )
        )
        val delegate = PlatformHttpServer(
            port = 0,
            runtimeRole = runtimeRole,
            api = api,
            boundary = boundary,
            abuseProtectionHook = abuseProtectionHook,
            accountRiskCheck = accountRiskCheck,
            accountRiskControlStore = accountRiskControlStore,
            accountRiskDecisionLog = accountRiskDecisionLog,
            commandCircuitBreakerCheck = commandCircuitBreakerCheck,
            commandCircuitBreakerStore = commandCircuitBreakerStore,
            idempotencyStore = idempotencyStore,
            idempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
            commandCaptureStore = captureStore,
            commandStatusLookup = captureStore as? CommandStatusLookup,
            streamCommandIntakeStore = streamCommandIntakeStore,
            streamCommandPublisher = streamCommandPublisher,
            streamCommandHealthCheck = streamCommandHealthCheck,
            streamCommandConfig = streamCommandConfig,
            commandProcessingMode = commandProcessingMode,
            commandIntakeMaxActive = commandIntakeMaxActive,
            commandIntakeMaxStaleProcessing = commandIntakeMaxStaleProcessing,
            commandIntakeBackpressureSampleMs = commandIntakeBackpressureSampleMs,
            legacyMutationRoutesEnabled = true,
            localDevAdminAuthBypass = localDevAdminAuthBypass
        )
        return PlatformNettyHotPathServer(
            delegate = delegate,
            port = 0,
            applicationThreads = 4
        ).start()
    }

    private fun post(port: Int, path: String, headers: Map<String, String>, body: String): HttpResponse {
        val connection = java.net.URI.create("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        return HttpResponse(connection.responseCode, text, responseHeaders(connection))
    }

    private fun get(port: Int, path: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        val connection = java.net.URI.create("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        return HttpResponse(connection.responseCode, text, responseHeaders(connection))
    }

    private fun options(port: Int, path: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        return requestWithHeaders("OPTIONS", port, path, headers)
    }

    private fun requestWithHeaders(
        method: String,
        port: Int,
        path: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse {
        val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:$port$path"))
            .method(method, java.net.http.HttpRequest.BodyPublishers.noBody())
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = java.net.http.HttpClient.newHttpClient().send(
            builder.build(),
            java.net.http.HttpResponse.BodyHandlers.ofString()
        )
        return HttpResponse(response.statusCode(), response.body(), response.headers().map())
    }

    private fun apiGet(port: Int, path: String, headers: Map<String, String> = apiReadHeaders()): HttpResponse {
        return get(port, path, headers)
    }

    private fun responseHeaders(connection: HttpURLConnection): Map<String, List<String>> {
        return connection.headerFields.entries.mapNotNull { (key, value) ->
            key?.let { it to value.toList() }
        }.toMap()
    }

    private fun responseHeader(response: HttpResponse, name: String): String {
        return response.headers.entries
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            .orEmpty()
    }

    private fun envLookup(vararg pairs: Pair<String, String>): (String) -> String? {
        val values = pairs.toMap()
        return { key -> values[key] }
    }

    private fun waitForCommandStatus(
        port: Int,
        commandId: String,
        status: String,
        headers: Map<String, String> = apiReadHeaders()
    ): HttpResponse {
        var last = get(port, "/api/v1/commands/$commandId", headers = headers)
        repeat(50) {
            if (last.status == 200 && last.body.contains("\"status\":\"$status\"")) {
                return last
            }
            Thread.sleep(20)
            last = get(port, "/api/v1/commands/$commandId", headers = headers)
        }
        return last
    }

    private fun validSubmitBody(commandId: String, traceId: String, orderId: String, extra: String = ""): String {
        return """
            {
              "commandId":"$commandId",
              "traceId":"$traceId",
              "causationId":"",
              "correlationId":"$traceId",
              "actorId":"bot-capture-1",
              "occurredAt":"2026-05-22T00:00:00Z",
              "orderId":"$orderId",
              "instrumentId":"AAPL",
              "participantId":"participant-1",
              "accountId":"account-1",
              "side":"BUY",
              "orderType":"LIMIT",
              "quantityUnits":"100",
              "limitPrice":"150250000000",
              "currency":"USD",
              "timeInForce":"DAY"$extra
            }
        """.trimIndent()
    }

    private fun validCancelBody(commandId: String, traceId: String, orderId: String, extra: String = ""): String {
        return """
            {
              "commandId":"$commandId",
              "traceId":"$traceId",
              "causationId":"",
              "correlationId":"$traceId",
              "actorId":"bot-capture-1",
              "occurredAt":"2026-05-22T00:00:00Z",
              "orderId":"$orderId",
              "reason":"test"$extra
            }
        """.trimIndent()
    }

    private fun validModifyBody(commandId: String, traceId: String, orderId: String, extra: String = ""): String {
        return """
            {
              "commandId":"$commandId",
              "traceId":"$traceId",
              "causationId":"",
              "correlationId":"$traceId",
              "actorId":"bot-capture-1",
              "occurredAt":"2026-05-22T00:00:00Z",
              "orderId":"$orderId",
              "quantityUnits":"100",
              "limitPrice":"150250000001"$extra
            }
        """.trimIndent()
    }

    private fun p2SettlementFactsBody(
        scenarioRunId: String,
        includePostTradeProfile: Boolean = true,
        venueSessionId: String = ""
    ): String {
        val postTradeProfile = if (includePostTradeProfile) {
            """
              "postTradeProfileId":"instant-post-trade-v1",
              "postTradePolicyVersion":2,
            """.trimIndent()
        } else {
            ""
        }
        val venueSession = if (venueSessionId.isNotBlank()) {
            """"venueSessionId":"$venueSessionId","""
        } else {
            ""
        }
        return """
            {
              "scenarioRunId":"$scenarioRunId",
              $venueSession
              $postTradeProfile
              "obligations":[{
                "settlementObligationId":"obl-1",
                "scenarioRunId":"$scenarioRunId",
                "correlationId":"corr-1",
                "causationId":"trade-1",
                "tradeId":"trade-1",
                "buyerParticipantId":"buyer-1",
                "sellerParticipantId":"seller-1",
                "instrumentId":"AAPL",
                "quantity":"100",
                "cashAmount":"15000.00",
                "currency":"USD",
                "state":"OBLIGATION_CREATED",
                "occurredAt":"2026-01-01T00:00:00Z"
              }],
              "instructions":[{
                "settlementInstructionId":"instruction-1",
                "settlementObligationId":"obl-1",
                "scenarioRunId":"$scenarioRunId",
                "correlationId":"corr-1",
                "causationId":"obl-1",
                "instructionType":"DVP",
                "state":"INSTRUCTION_CREATED",
                "occurredAt":"2026-01-01T00:00:00Z"
              }],
              "attempts":[{
                "settlementAttemptId":"attempt-1",
                "settlementObligationId":"obl-1",
                "settlementInstructionId":"instruction-1",
                "scenarioRunId":"$scenarioRunId",
                "correlationId":"corr-1",
                "causationId":"instruction-1",
                "attemptNumber":1,
                "state":"ATTEMPT_STARTED",
                "occurredAt":"2026-01-01T00:00:00Z"
              }],
              "breaks":[{
                "settlementBreakId":"break-1",
                "settlementObligationId":"obl-1",
                "scenarioRunId":"$scenarioRunId",
                "correlationId":"corr-1",
                "causationId":"trade-1",
                "reason":"CASH_LEG_FAILED",
                "state":"BROKEN",
                "occurredAt":"2026-01-01T00:00:01Z"
              }],
              "repairs":[{
                "settlementRepairId":"repair-1",
                "settlementBreakId":"break-1",
                "settlementObligationId":"obl-1",
                "scenarioRunId":"$scenarioRunId",
                "correlationId":"corr-1",
                "causationId":"repair-command-1",
                "repairAction":"POST_CASH_LEG_REPAIR",
                "actorType":"USER",
                "actorId":"ops-user-1",
                "occurredAt":"2026-01-01T00:00:02Z"
              }],
              "resolutions":[{
                "settlementResolutionId":"resolution-1",
                "settlementObligationId":"obl-1",
                "settlementBreakId":"break-1",
                "settlementRepairId":"repair-1",
                "scenarioRunId":"$scenarioRunId",
                "correlationId":"corr-1",
                "causationId":"repair-command-1",
                "settlementState":"RESOLVED",
                "exceptionState":"RESOLVED",
                "occurredAt":"2026-01-01T00:00:03Z"
              }]
            }
        """.trimIndent()
    }

    private fun streamRoutingExtra(): String {
        return ",\"runId\":\"run-1\",\"venueSessionId\":\"session-1\",\"clientOrderId\":\"clord-1\",\"botId\":\"bot-1\",\"botVersion\":\"v1\""
    }

    private fun venueEventBatch(
        batchId: String,
        commandId: String,
        resultStatus: String,
        rejectCode: String = "",
        commandType: String = "SubmitOrder",
        orderId: String = "ord-$commandId",
        resultPayloadJson: String? = null
    ): VenueEventBatchFact {
        val resultPayload = resultPayloadJson ?: if (resultStatus == "rejected") {
            """{"rejected":{"code":"$rejectCode","participantId":"participant-1","orderId":"$orderId"}}"""
        } else {
            """{"accepted":{"eventId":"evt-$commandId","participantId":"participant-1","orderId":"$orderId"}}"""
        }
        return VenueEventBatchFact(
            batchId = batchId,
            shardId = "engine-0",
            partition = 7,
            commandStream = "REEF_COMMANDS",
            eventStream = "REEF_VENUE_EVENTS",
            firstSequence = 7001,
            lastSequence = 7001,
            commandCount = 1,
            createdAt = "2026-07-04T18:00:00Z",
            payloadChecksum = "checksum-$batchId",
            outcomes = listOf(
                VenueCommandOutcomeFact(
                    commandId = commandId,
                    commandType = commandType,
                    streamSequence = 7001,
                    deliveredCount = 1,
                    payloadHash = "payload-hash-$commandId",
                    instrumentId = "AAPL",
                    orderId = orderId,
                    resultStatus = resultStatus,
                    rejectCode = rejectCode,
                    resultPayloadJson = resultPayload
                )
            )
        )
    }

    private fun bodyWithoutField(body: String, field: String): String {
        val withoutField = body
            .lines()
            .filterNot { it.trimStart().startsWith("\"$field\":") }
            .joinToString("\n")
        return Regex(""",(\s*})""").replace(withoutField) { match ->
            match.groupValues[1]
        }
    }

    private fun seedReferenceData(port: Int) {
        post(
            port = port,
            path = "/reference/instruments",
            headers = mapOf("X-Reef-Internal-Route" to "true"),
            body = """{"instrumentId":"AAPL","symbol":"AAPL"}"""
        )
        post(
            port = port,
            path = "/reference/participants",
            headers = mapOf("X-Reef-Internal-Route" to "true"),
            body = """{"participantId":"participant-1","name":"Participant 1"}"""
        )
        post(
            port = port,
            path = "/reference/accounts",
            headers = mapOf("X-Reef-Internal-Route" to "true"),
            body = """{"accountId":"account-1","participantId":"participant-1"}"""
        )
    }

    private fun seedOrderRoleBindings(port: Int, vararg actorIds: String) {
        post(
            port = port,
            path = "/auth/roles",
            headers = mapOf("X-Reef-Internal-Route" to "true"),
            body = """{"roleId":"order_trader","permissions":"order.submit,order.cancel,order.modify"}"""
        )
        actorIds.forEach { actorId ->
            post(
                port = port,
                path = "/auth/actor-roles",
                headers = mapOf("X-Reef-Internal-Route" to "true"),
                body = """{"actorId":"$actorId","roleId":"order_trader"}"""
            )
        }
    }

    private fun seedOrderReferenceData(persistence: InMemoryRuntimePersistence) {
        persistence.saveInstrument(Instrument("AAPL", "AAPL"))
        persistence.saveParticipant(Participant("participant-1", "Participant 1"))
        persistence.saveAccount(Account("account-1", "participant-1"))
    }

    private fun seedOrderAuthorization(persistence: InMemoryRuntimePersistence, vararg actorIds: String) {
        persistence.saveRole(
            RoleDefinition(
                "order_trader",
                listOf(Permission.ORDER_SUBMIT, Permission.ORDER_CANCEL, Permission.ORDER_MODIFY)
            )
        )
        actorIds.forEach { actorId ->
            persistence.saveActorRoleBinding(ActorRoleBinding(actorId, "order_trader"))
        }
    }

    private fun persistedOrder(
        orderId: String,
        participantId: String,
        side: String,
        runId: String,
        venueSessionId: String
    ): PersistedOrder {
        return PersistedOrder(
            orderId = orderId,
            engineOrderId = "eng-$orderId",
            instrumentId = "AAPL",
            participantId = participantId,
            accountId = "account-$participantId",
            side = side,
            orderType = "LIMIT",
            quantityUnits = "100",
            limitPrice = "150250000000",
            currency = "USD",
            timeInForce = "DAY",
            acceptedAt = "2026-01-01T00:00:00Z",
            runId = runId,
            venueSessionId = venueSessionId
        )
    }

}

private class RecordingCommandCaptureStore : CommandCaptureStore {
    var receivedCalls: Int = 0
    var completedCalls: Int = 0
    var failedCalls: Int = 0
    var lastReceivedPayload: String = ""

    override fun captureReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ) {
        receivedCalls++
        lastReceivedPayload = requestPayload
    }

    override fun markProcessing(
        clientId: String,
        route: String,
        idempotencyKey: String
    ) {
    }

    override fun markCompleted(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        responsePayload: String
    ) {
        completedCalls++
    }

    override fun markFailed(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        errorClass: String,
        errorMessage: String
    ) {
        failedCalls++
    }
}

private class CountingIdempotencyStore : IdempotencyStore {
    var findCalls: Int = 0
    var saveCalls: Int = 0

    override fun find(clientId: String, route: String, idempotencyKey: String): IdempotencyResult? {
        findCalls++
        return null
    }

    override fun save(
        clientId: String,
        route: String,
        idempotencyKey: String,
        result: IdempotencyResult,
        ttlClass: IdempotencyTtlClass
    ) {
        saveCalls++
    }
}

private class RecordingStreamCommandPublisher(
    private val fail: Boolean = false
) : StreamCommandPublisher {
    val published = mutableListOf<StreamCommandEnvelope>()

    override fun publish(envelope: StreamCommandEnvelope): StreamPublishAck {
        published.add(envelope)
        if (fail) {
            error("publish ack timeout")
        }
        return StreamPublishAck("REEF_COMMANDS", published.size.toLong())
    }
}

private class SkipFirstPublishedMarkerIntakeStore(
    private val delegate: StreamCommandIntakeStore
) : StreamCommandIntakeStore {
    private val skipNext = AtomicBoolean(true)

    override fun reserve(envelope: StreamCommandEnvelope, reference: StreamCommandReference): StreamCommandReservation {
        return delegate.reserve(envelope, reference)
    }

    override fun markPublished(scope: String, idempotencyKey: String, streamSequence: Long): Boolean {
        if (skipNext.compareAndSet(true, false)) {
            return true
        }
        return delegate.markPublished(scope, idempotencyKey, streamSequence)
    }

    override fun markPublishedByCommandId(commandId: String, streamSequence: Long): Boolean {
        if (skipNext.compareAndSet(true, false)) {
            return true
        }
        return delegate.markPublishedByCommandId(commandId, streamSequence)
    }

    override fun markPublishedByCommandIds(commands: List<Pair<String, Long>>): Int {
        return delegate.markPublishedByCommandIds(commands)
    }

    override fun findByCommandId(commandId: String): StreamCommandReference? {
        return delegate.findByCommandId(commandId)
    }
}

private class DelayedAckStreamCommandPublisher : StreamCommandPublisher, AsyncStreamCommandPublisher {
    val published = mutableListOf<StreamCommandEnvelope>()
    private val pending = CompletableFuture<StreamPublishAck>()

    override fun publish(envelope: StreamCommandEnvelope): StreamPublishAck {
        return publishAsync(envelope).get(2, TimeUnit.SECONDS)
    }

    override fun publishAsync(envelope: StreamCommandEnvelope): CompletableFuture<StreamPublishAck> {
        published.add(envelope)
        return pending
    }

    fun complete(ack: StreamPublishAck) {
        pending.complete(ack)
    }
}

private class FixedStreamCommandHealthCheck(
    private val snapshot: StreamCommandHealthSnapshot
) : StreamCommandHealthCheck {
    override fun snapshot(): StreamCommandHealthSnapshot = snapshot
}

private class CountingStreamCommandHealthCheck(
    private val snapshot: StreamCommandHealthSnapshot
) : StreamCommandHealthCheck {
    val calls = AtomicInteger(0)

    override fun snapshot(): StreamCommandHealthSnapshot {
        calls.incrementAndGet()
        return snapshot
    }
}

private class StaticAcceptedEngineGateway : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-1",
                orderId = "ord-1",
                engineOrderId = "eng-1",
                occurredAt = "2026-05-22T00:00:00Z"
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "")
    )

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "")
    )
}

private class EchoOrderEngineGateway : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-${command.orderId}",
                orderId = command.orderId,
                engineOrderId = "eng-${command.orderId}",
                occurredAt = "2026-05-22T00:00:00Z"
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", command.orderId, "", "", "", "", "", "", "", "", "")
    )

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", command.orderId, "", "", "", "", "", "", "", "", "")
    )
}

private class CountingEngineGateway(
    private val delegate: EngineGateway
) : EngineGateway {
    var submitCalls: Int = 0
    var cancelCalls: Int = 0

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        submitCalls++
        return delegate.submitOrder(command)
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        cancelCalls++
        return delegate.cancelOrder(command)
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        return delegate.modifyOrder(command)
    }
}

private class BlockingFirstSubmitGateway : EngineGateway {
    private val calls = AtomicInteger(0)
    private val firstStarted = CountDownLatch(1)
    private val releaseFirst = CountDownLatch(1)

    val submitCalls: Int
        get() = calls.get()

    fun awaitFirstSubmit(): Boolean {
        return firstStarted.await(5, TimeUnit.SECONDS)
    }

    fun release() {
        releaseFirst.countDown()
    }

    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        val call = calls.incrementAndGet()
        if (call == 1) {
            firstStarted.countDown()
            releaseFirst.await(5, TimeUnit.SECONDS)
        }
        return SubmitOrderResult(
            accepted = EngineOrderAccepted(
                eventId = "evt-${command.orderId}",
                orderId = command.orderId,
                engineOrderId = "eng-${command.orderId}",
                occurredAt = "2026-05-22T00:00:00Z"
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", command.orderId, "", "", "", "", "", "", "", "", "")
    )

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", command.orderId, "", "", "", "", "", "", "", "", "")
    )
}

private class StaticRejectedEngineGateway(
    private val code: String,
    private val reason: String
) : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        return SubmitOrderResult(
            rejected = EngineOrderRejected(
                eventId = "evt-rejected-${command.orderId}",
                orderId = command.orderId,
                code = code,
                reason = reason,
                occurredAt = "2026-05-22T00:00:00Z"
            )
        )
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", command.orderId, "", "", "", "", "", "", "", "", "")
    )

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult = submitOrder(
        SubmitOrderCommand("", "", "", "", "", "", command.orderId, "", "", "", "", "", "", "", "", "")
    )
}

private class StaticCircuitBreakerCheck(
    private val trippedInstrumentId: String
) : CommandCircuitBreakerCheck {
    override fun evaluate(request: CommandCircuitBreakerRequest): BoundaryError? {
        if (request.instrumentId != trippedInstrumentId) return null
        return BoundaryError(
            503,
            "COMMAND_CIRCUIT_BREAKER_TRIPPED",
            "command circuit breaker tripped for INSTRUMENT:${request.instrumentId}"
        )
    }
}

private class RecordingAccountRiskStore : AccountRiskCheck, AccountRiskControlStore, AccountRiskDecisionLog {
    private val controls = linkedMapOf<String, AccountRiskControl>()
    val decisions = mutableListOf<AccountRiskDecisionAudit>()

    override fun evaluate(request: AccountRiskCheckRequest): AccountRiskCheckResult {
        val botDecision = controls["BOT|${request.botId}"]
        if (botDecision != null && botDecision.decision != AccountRiskDecision.ALLOW) {
            return AccountRiskCheckResult(botDecision.decision, message = botDecision.reason)
        }
        val accountDecision = controls["ACCOUNT|${request.accountId}"]
        if (accountDecision != null && accountDecision.decision != AccountRiskDecision.ALLOW) {
            return AccountRiskCheckResult(accountDecision.decision, message = accountDecision.reason)
        }
        listOfNotNull(botDecision, accountDecision).forEach { control ->
            val quantity = request.quantityUnits.toBigDecimalOrNull()
            val maxQuantity = control.maxQuantityUnits.toBigDecimalOrNull()
            if (quantity != null && maxQuantity != null && quantity > maxQuantity) {
                return AccountRiskCheckResult(
                    AccountRiskDecision.REJECT,
                    code = "ACCOUNT_RISK_MAX_QUANTITY_EXCEEDED",
                    message = "max quantity exceeded"
                )
            }
        }
        return AccountRiskCheckResult(AccountRiskDecision.ALLOW)
    }

    override fun upsertControl(
        scopeType: String,
        scopeId: String,
        decision: AccountRiskDecision,
        reason: String,
        maxQuantityUnits: String,
        maxNotional: String,
        currency: String
    ) {
        controls["$scopeType|$scopeId"] = AccountRiskControl(
            scopeType = scopeType,
            scopeId = scopeId,
            decision = decision,
            reason = reason,
            maxQuantityUnits = maxQuantityUnits,
            maxNotional = maxNotional,
            currency = currency,
            updatedAt = "2026-07-04T12:00:00Z"
        )
    }

    override fun listControls(): List<AccountRiskControl> = controls.values.toList()

    override fun recentDecisions(limit: Int): List<AccountRiskDecisionAudit> = decisions.take(limit)
}

private class RecordingCommandCircuitBreakerStore : CommandCircuitBreakerStore {
    private val breakers = linkedMapOf<String, CommandCircuitBreakerState>()

    override fun evaluate(request: CommandCircuitBreakerRequest): BoundaryError? {
        val breaker = breakers["INSTRUMENT|${request.instrumentId}"] ?: breakers["VENUE_SESSION|${request.venueSessionId}"] ?: breakers["GLOBAL|*"]
        if (breaker?.tripped != true) return null
        return BoundaryError(
            503,
            "COMMAND_CIRCUIT_BREAKER_TRIPPED",
            "command circuit breaker tripped for ${breaker.scopeType}:${breaker.scopeId}"
        )
    }

    override fun setBreaker(scopeType: String, scopeId: String, tripped: Boolean, reason: String) {
        breakers["$scopeType|$scopeId"] = CommandCircuitBreakerState(
            scopeType = scopeType,
            scopeId = scopeId,
            tripped = tripped,
            reason = reason,
            updatedAt = "2026-07-04T12:00:00Z"
        )
    }

    override fun listBreakers(): List<CommandCircuitBreakerState> = breakers.values.toList()
}

private class RecordingInstrumentPriceCollarStore : InstrumentPriceCollarStore {
    private val collars = linkedMapOf<String, InstrumentPriceCollarState>()

    override fun evaluate(request: InstrumentPriceCollarRequest): BoundaryError? {
        if (request.commandType != "SubmitOrder") return null
        val collar = collars[request.instrumentId] ?: return null
        if (collar.currency.isNotBlank() && !request.currency.equals(collar.currency, ignoreCase = true)) return null
        val price = request.limitPrice.toBigDecimalOrNull() ?: return null
        val minPrice = collar.minPrice.toBigDecimalOrNull()
        if (minPrice != null && price < minPrice) {
            return BoundaryError(
                422,
                "PRICE_COLLAR_LOW",
                "limit price below collar for ${collar.instrumentId}"
            )
        }
        val maxPrice = collar.maxPrice.toBigDecimalOrNull()
        if (maxPrice != null && price > maxPrice) {
            return BoundaryError(
                422,
                "PRICE_COLLAR_HIGH",
                "limit price above collar for ${collar.instrumentId}"
            )
        }
        return null
    }

    override fun setCollar(instrumentId: String, minPrice: String, maxPrice: String, currency: String, reason: String) {
        collars[instrumentId] = InstrumentPriceCollarState(
            instrumentId = instrumentId,
            minPrice = minPrice,
            maxPrice = maxPrice,
            currency = currency,
            reason = reason,
            updatedAt = "2026-07-04T12:00:00Z"
        )
    }

    override fun listCollars(): List<InstrumentPriceCollarState> = collars.values.toList()
}

private class RecordingBoundaryRejectionLog : BoundaryRejectionLog {
    data class Record(
        val guardrailType: String,
        val scopeType: String,
        val scopeId: String,
        val request: AccountRiskCheckRequest,
        val error: BoundaryError
    )

    val records = mutableListOf<Record>()

    override fun recordRejection(
        guardrailType: String,
        scopeType: String,
        scopeId: String,
        request: AccountRiskCheckRequest,
        error: BoundaryError
    ) {
        records.add(Record(guardrailType, scopeType, scopeId, request, error))
    }
}

private class ThrowingEngineGateway : EngineGateway {
    override fun submitOrder(command: SubmitOrderCommand): SubmitOrderResult {
        error("engine transport unavailable")
    }

    override fun cancelOrder(command: CancelOrderCommand): SubmitOrderResult {
        error("engine transport unavailable")
    }

    override fun modifyOrder(command: ModifyOrderCommand): SubmitOrderResult {
        error("engine transport unavailable")
    }
}

package com.reef.platform.api

import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.AdminServiceTokenFamily
import com.reef.platform.application.admin.AdminTrustState
import com.reef.platform.application.admin.GitHubUserIdentity
import com.reef.platform.application.settlement.InMemorySettlementFactStore
import com.reef.platform.domain.Participant
import com.sun.net.httpserver.Headers
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformHttpAdminGatewayBoundaryTest : PlatformHttpBoundaryTestSupport() {
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
}

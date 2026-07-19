package com.reef.platform.api

import com.reef.platform.application.admin.AdminAuthService
import com.reef.platform.application.admin.AdminGitHubOAuthClient
import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.AdminTrustState
import com.reef.platform.application.admin.AuthorizationException
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.time.Instant

data class AdminRequestPrincipal(
    val actorId: String,
    val correlationId: String,
    val occurredAt: String
)

private const val localDevAdminActorId = "admin-cli"

enum class InternalHttpExposureMode {
    Disabled,
    LocalOnly,
    Enabled;

    companion object {
        fun fromEnv(lookup: (String) -> String? = System::getenv): InternalHttpExposureMode {
            return when (RuntimeEnv.string("PLATFORM_INTERNAL_HTTP_MODE", "local", lookup).trim().lowercase()) {
                "disabled", "off", "false", "0" -> Disabled
                "enabled", "all", "raw-external" -> Enabled
                else -> LocalOnly
            }
        }
    }
}

/**
 * Owns admin session/cookie state, GitHub OAuth login, and the auth decision for
 * internal admin-gateway routes (session cookie, service token, or static bearer token).
 * Callers thread the resolved [AdminRequestPrincipal] through [withPrincipal] for the
 * duration of a request so downstream gateways can read it via [currentPrincipal].
 */
internal class AdminSessionAuth(
    private val adminAuthService: AdminAuthService?,
    private val adminIdentityService: AdminIdentityService?,
    private val adminGitHubOAuthClient: AdminGitHubOAuthClient?,
    private val adminSessionCookieName: String,
    private val adminSessionCookieSecure: Boolean,
    private val localDevAdminAuthBypass: Boolean,
    private val internalHttpExposureMode: InternalHttpExposureMode,
    private val envLookup: (String) -> String? = System::getenv,
    private val localDevAdminUiBaseUrl: String? = localDevAdminUiBaseUrl(envLookup)
) {
    private val adminRequestPrincipal = ThreadLocal<AdminRequestPrincipal?>()

    fun register(server: HttpServer) {
        server.createContext("/admin/auth/github/start") { exchange ->
            if (handleLocalDevAdminUiCorsPreflight(exchange)) return@createContext
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val auth = adminAuthService
            val github = adminGitHubOAuthClient
            if (auth == null || github == null) {
                writeHotPathResponse(exchange, adminAuthUnavailableResponse())
                return@createContext
            }
            try {
                val redirectPath = queryParam(exchange, "redirectPath")
                    .ifBlank { queryParam(exchange, "redirect") }
                    .ifBlank { "/" }
                val start = auth.beginGitHubOAuth(redirectPath)
                redirect(exchange, github.authorizationUrl(start.stateToken))
            } catch (ex: IllegalArgumentException) {
                writeHotPathResponse(exchange, PlatformHotPathResponse(400, JsonCodec.writeObject("error" to (ex.message ?: "invalid admin auth start"))))
            }
        }

        server.createContext("/admin/auth/github/callback") { exchange ->
            if (handleLocalDevAdminUiCorsPreflight(exchange)) return@createContext
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val auth = adminAuthService
            val identity = adminIdentityService
            val github = adminGitHubOAuthClient
            if (auth == null || identity == null || github == null) {
                writeHotPathResponse(exchange, adminAuthUnavailableResponse())
                return@createContext
            }
            val error = queryParam(exchange, "error")
            if (error.isNotBlank()) {
                writeHotPathResponse(exchange, PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "GitHub OAuth rejected request")))
                return@createContext
            }
            val code = queryParam(exchange, "code")
            val stateToken = queryParam(exchange, "state")
            if (code.isBlank() || stateToken.isBlank()) {
                writeHotPathResponse(exchange, PlatformHotPathResponse(400, JsonCodec.writeObject("error" to "code and state are required")))
                return@createContext
            }
            try {
                val state = auth.consumeGitHubOAuthState(stateToken)
                val githubIdentity = github.exchangeCode(code)
                val user = identity.ensureGitHubUser(githubIdentity)
                val session = auth.createSession(user.reefUserId)
                setAdminSessionCookie(exchange, session.token)
                redirect(exchange, adminUiRedirectLocation(state.redirectPath))
            } catch (ex: IllegalArgumentException) {
                writeHotPathResponse(exchange, PlatformHotPathResponse(401, JsonCodec.writeObject("error" to "admin auth failed")))
            } catch (ex: Exception) {
                System.err.println("admin_github_oauth_callback_failed error=${ex.javaClass.simpleName} message=${ex.message.orEmpty()}")
                writeHotPathResponse(exchange, PlatformHotPathResponse(502, JsonCodec.writeObject("error" to "admin auth upstream failed")))
            }
        }

        server.createContext("/admin/auth/session") { exchange ->
            if (handleLocalDevAdminUiCorsPreflight(exchange)) return@createContext
            if (exchange.requestMethod != "GET") {
                methodNotAllowed(exchange)
                return@createContext
            }
            if (allowLocalDevAdminAuthBypass(exchange)) {
                writeLocalDevAdminSession(exchange)
                return@createContext
            }
            val auth = adminAuthService
            if (auth == null) {
                writeHotPathResponse(exchange, adminAuthUnavailableResponse())
                return@createContext
            }
            val token = adminSessionCookie(exchange)
            if (token.isBlank()) {
                writeHotPathResponse(exchange, PlatformHotPathResponse(401, JsonCodec.writeObject("error" to "unauthorized")))
                return@createContext
            }
            try {
                val session = auth.authenticateSession(token)
                val user = adminIdentityService?.user(session.reefUserId)
                val identityRoles = adminIdentityService
                    ?.rolesForUser(session.reefUserId)
                    ?.map { it.roleId }
                    .orEmpty()
                val roles = identityRoles.distinct().sorted()
                writeJson(
                    exchange,
                    200,
                    JsonCodec.writeObject(
                        "status" to "ok",
                        "reefUserId" to session.reefUserId,
                        "githubLogin" to (user?.githubLogin ?: ""),
                        "displayName" to (user?.displayName ?: ""),
                        "trustState" to (user?.trustState?.dbValue ?: ""),
                        "roles" to roles,
                        "authProvider" to session.authProvider.dbValue,
                        "expiresAt" to session.expiresAt.toString()
                    )
                )
            } catch (ex: IllegalArgumentException) {
                clearAdminSessionCookie(exchange)
                writeHotPathResponse(exchange, PlatformHotPathResponse(401, JsonCodec.writeObject("error" to "unauthorized")))
            }
        }

        server.createContext("/admin/auth/logout") { exchange ->
            if (handleLocalDevAdminUiCorsPreflight(exchange)) return@createContext
            if (exchange.requestMethod != "POST") {
                methodNotAllowed(exchange)
                return@createContext
            }
            val auth = adminAuthService
            if (auth == null) {
                writeHotPathResponse(exchange, adminAuthUnavailableResponse())
                return@createContext
            }
            val token = adminSessionCookie(exchange)
            if (token.isNotBlank()) {
                try {
                    auth.revokeSession(token)
                } catch (_: IllegalArgumentException) {
                    // Logout is idempotent from the browser perspective.
                }
            }
            clearAdminSessionCookie(exchange)
            writeJson(exchange, 200, JsonCodec.writeObject("status" to "ok"))
        }
    }

    fun allowInternalHttpRoute(exchange: HttpExchange): Boolean {
        return when (internalHttpExposureMode) {
            InternalHttpExposureMode.Disabled -> {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                false
            }
            InternalHttpExposureMode.LocalOnly -> {
                if (isLoopback(exchange.remoteAddress.address)) {
                    true
                } else {
                    writeHotPathResponse(
                        exchange,
                        PlatformHotPathResponse(
                            403,
                            JsonCodec.writeObject(
                                "error" to "internal HTTP route requires loopback access",
                                "mode" to "local"
                            )
                        )
                    )
                    false
                }
            }
            InternalHttpExposureMode.Enabled -> true
        }
    }

    fun handleLocalDevAdminUiCorsPreflight(exchange: HttpExchange): Boolean {
        if (exchange.requestMethod != "OPTIONS") return false
        if (!applyLocalDevAdminUiCors(exchange)) return false
        exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,OPTIONS")
        val requestedHeaders = headerValue(exchange, "Access-Control-Request-Headers").trim()
        exchange.responseHeaders.add(
            "Access-Control-Allow-Headers",
            requestedHeaders.ifBlank { "authorization,content-type,x-client-id,x-correlation-id,x-reef-actor-id" }
        )
        exchange.responseHeaders.add("Access-Control-Max-Age", "600")
        exchange.sendResponseHeaders(204, -1)
        exchange.close()
        return true
    }

    fun applyLocalDevAdminUiCors(exchange: HttpExchange): Boolean {
        val baseUrl = localDevAdminUiBaseUrl ?: return false
        val origin = headerValue(exchange, "Origin").trim()
        if (origin != baseUrl) return false
        exchange.responseHeaders.add("Vary", "Origin")
        exchange.responseHeaders.add("Access-Control-Allow-Origin", baseUrl)
        exchange.responseHeaders.add("Access-Control-Allow-Credentials", "true")
        return true
    }

    private fun isLoopback(address: InetAddress?): Boolean {
        return address?.isLoopbackAddress ?: false
    }

    fun isLoopback(address: String?): Boolean {
        if (address.isNullOrBlank()) return true
        return try {
            InetAddress.getByName(address).isLoopbackAddress
        } catch (_: Exception) {
            false
        }
    }

    fun withPrincipal(exchange: HttpExchange, block: () -> Unit) {
        withPrincipal(principal(exchange.requestHeaders), block)
    }

    fun <T> withPrincipal(principal: AdminRequestPrincipal, block: () -> T): T {
        val prior = adminRequestPrincipal.get()
        adminRequestPrincipal.set(principal)
        try {
            return block()
        } finally {
            adminRequestPrincipal.set(prior)
        }
    }

    private fun principal(exchange: HttpExchange): AdminRequestPrincipal {
        return principal(exchange.requestHeaders)
    }

    fun principal(headers: Headers): AdminRequestPrincipal {
        return AdminRequestPrincipal(
            actorId = headerValue(headers, "X-Reef-Actor-Id").ifBlank { "admin-cli" },
            correlationId = headerValue(headers, "X-Correlation-Id").ifBlank {
                headerValue(headers, "X-Reef-Correlation-Id").ifBlank { "internal-admin" }
            },
            occurredAt = headerValue(headers, "X-Reef-Occurred-At").ifBlank { Instant.now().toString() }
        )
    }

    fun currentPrincipal(): AdminRequestPrincipal {
        return adminRequestPrincipal.get() ?: AdminRequestPrincipal(
            actorId = "admin-cli",
            correlationId = "internal-admin",
            occurredAt = Instant.now().toString()
        )
    }

    fun authorizeGateway(exchange: HttpExchange, route: AdminGatewayRoute): AdminRequestPrincipal? {
        if (allowLocalDevAdminAuthBypass(exchange)) {
            return adminPrincipalForActor(exchange, localDevAdminActorId)
        }

        val auth = adminAuthService
        if (auth != null) {
            val sessionToken = adminSessionCookie(exchange)
            if (sessionToken.isNotBlank()) {
                try {
                    val session = auth.authenticateSession(sessionToken)
                    if (!authorizeSessionForRoute(exchange, session.reefUserId, route)) {
                        return null
                    }
                    return adminPrincipalForActor(exchange, session.reefUserId)
                } catch (_: IllegalArgumentException) {
                    clearAdminSessionCookie(exchange)
                    writeHotPathResponse(exchange, PlatformHotPathResponse(401, JsonCodec.writeObject("error" to "unauthorized")))
                    return null
                }
            }
            bearerToken(exchange)?.let { token ->
                route.serviceTokenFamilies.forEach { family ->
                    try {
                        val serviceToken = auth.authenticateServiceToken(token, family)
                        return adminPrincipalForActor(exchange, serviceToken.subjectActorId)
                    } catch (_: AuthorizationException) {
                        // Unknown DB-issued service tokens may still be valid
                        // static gateway fallback tokens configured in env.
                    } catch (_: IllegalArgumentException) {
                        // Try the next permitted service-token family for this route.
                    }
                }
            }
        }

        val envName = adminGatewayFallbackTokenEnv(route)
        val token = RuntimeEnv.string(envName, "", envLookup)
        val expected = "Bearer $token"
        if (token.isNotBlank() && headerValue(exchange, "Authorization") == expected) {
            return staticFallbackPrincipal(exchange, route)
        }
        if (auth != null) {
            writeHotPathResponse(exchange, PlatformHotPathResponse(401, JsonCodec.writeObject("error" to "unauthorized")))
            return null
        }
        if (token.isBlank()) {
            writeHotPathResponse(exchange, PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "$envName is not configured")))
            return null
        }
        writeHotPathResponse(exchange, PlatformHotPathResponse(401, JsonCodec.writeObject("error" to "unauthorized")))
        return null
    }

    fun authorizeGateway(request: PlatformHotPathRequest, route: AdminGatewayRoute): AdminRequestPrincipal? {
        if (allowLocalDevAdminAuthBypass(request)) {
            return adminPrincipalForActor(request.headers, localDevAdminActorId)
        }

        val auth = adminAuthService
        if (auth != null) {
            val sessionToken = adminSessionCookie(request.headers)
            if (sessionToken.isNotBlank()) {
                try {
                    val session = auth.authenticateSession(sessionToken)
                    if (!authorizeSessionForRoute(session.reefUserId, route)) {
                        return null
                    }
                    return adminPrincipalForActor(request.headers, session.reefUserId)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            }
            bearerToken(request.headers)?.let { token ->
                route.serviceTokenFamilies.forEach { family ->
                    try {
                        val serviceToken = auth.authenticateServiceToken(token, family)
                        return adminPrincipalForActor(request.headers, serviceToken.subjectActorId)
                    } catch (_: AuthorizationException) {
                        // Unknown DB-issued service tokens may still be valid
                        // static gateway fallback tokens configured in env.
                    } catch (_: IllegalArgumentException) {
                        // Try the next permitted service-token family for this route.
                    }
                }
            }
        }

        val envName = adminGatewayFallbackTokenEnv(route)
        val token = RuntimeEnv.string(envName, "", envLookup)
        val expected = "Bearer $token"
        if (token.isNotBlank() && headerValue(request.headers, "Authorization") == expected) {
            return staticFallbackPrincipal(request.headers, route)
        }
        return null
    }

    fun unauthorizedGatewayResponse(route: AdminGatewayRoute): PlatformHotPathResponse {
        if (adminAuthService != null) {
            return PlatformHotPathResponse(401, JsonCodec.writeObject("error" to "unauthorized"))
        }
        val envName = adminGatewayFallbackTokenEnv(route)
        if (RuntimeEnv.string(envName, "", envLookup).isBlank()) {
            return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "$envName is not configured"))
        }
        return PlatformHotPathResponse(401, JsonCodec.writeObject("error" to "unauthorized"))
    }

    private fun staticFallbackPrincipal(exchange: HttpExchange, route: AdminGatewayRoute): AdminRequestPrincipal {
        return staticFallbackPrincipal(exchange.requestHeaders, route)
    }

    private fun staticFallbackPrincipal(headers: Headers, route: AdminGatewayRoute): AdminRequestPrincipal {
        return adminPrincipalForActor(headers, staticFallbackActorId(route))
    }

    private fun staticFallbackActorId(route: AdminGatewayRoute): String {
        val familyActorEnv = route.fallbackActorEnv ?: when (route.fallbackTokenFamily) {
            "analytics" -> "ANALYTICS_EXPORT_API_ACTOR_ID"
            "admin" -> "ADMIN_API_ACTOR_ID"
            else -> error("optional product routes must declare a fallback actor environment variable")
        }
        return RuntimeEnv.string(
            familyActorEnv,
            RuntimeEnv.string("ADMIN_ACTOR_ID", localDevAdminActorId, envLookup),
            envLookup
        ).trim().ifBlank { localDevAdminActorId }
    }

    private fun adminGatewayFallbackTokenEnv(route: AdminGatewayRoute): String {
        return route.fallbackTokenEnv ?: when (route.fallbackTokenFamily) {
            "analytics" -> "ANALYTICS_EXPORT_API_TOKEN"
            "admin" -> "ADMIN_API_TOKEN"
            else -> error("optional product routes must declare a fallback token environment variable")
        }
    }

    private fun authorizeSessionForRoute(
        exchange: HttpExchange,
        reefUserId: String,
        route: AdminGatewayRoute
    ): Boolean {
        val error = sessionRouteAuthorizationError(reefUserId, route)
        if (error == null) return true
        writeHotPathResponse(exchange, error)
        return false
    }

    private fun authorizeSessionForRoute(reefUserId: String, route: AdminGatewayRoute): Boolean {
        return sessionRouteAuthorizationError(reefUserId, route) == null
    }

    private fun sessionRouteAuthorizationError(
        reefUserId: String,
        route: AdminGatewayRoute
    ): PlatformHotPathResponse? {
        if (route.sessionRoles.isEmpty()) return null
        val identity = adminIdentityService
            ?: return PlatformHotPathResponse(403, JsonCodec.writeObject("error" to "admin identity service is required"))
        val user = identity.user(reefUserId)
            ?: return PlatformHotPathResponse(403, JsonCodec.writeObject("error" to "admin identity is required"))
        if (user.trustState != AdminTrustState.Trusted) {
            return PlatformHotPathResponse(403, JsonCodec.writeObject("error" to "trusted admin identity is required"))
        }
        val roles = identity.rolesForUser(reefUserId).map { it.roleId }.toSet()
        if (roles.any { it in route.sessionRoles }) return null
        return PlatformHotPathResponse(
            403,
            JsonCodec.writeObject(
                "error" to "admin role required",
                "requiredRoles" to route.sessionRoles.sorted()
            )
        )
    }

    private fun bearerToken(exchange: HttpExchange): String? {
        return bearerToken(exchange.requestHeaders)
    }

    private fun bearerToken(headers: Headers): String? {
        val authorization = headerValue(headers, "Authorization")
        if (!authorization.startsWith("Bearer ")) return null
        return authorization.removePrefix("Bearer ").trim().ifBlank { null }
    }

    private fun adminPrincipalForActor(exchange: HttpExchange, actorId: String): AdminRequestPrincipal {
        return adminPrincipalForActor(exchange.requestHeaders, actorId)
    }

    private fun adminPrincipalForActor(headers: Headers, actorId: String): AdminRequestPrincipal {
        val headerPrincipal = principal(headers)
        return headerPrincipal.copy(actorId = actorId)
    }

    private fun adminAuthUnavailableResponse(): PlatformHotPathResponse {
        return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "admin auth is not configured"))
    }

    private fun allowLocalDevAdminAuthBypass(exchange: HttpExchange): Boolean {
        return localDevAdminAuthBypass && isLoopback(exchange.remoteAddress.address)
    }

    private fun allowLocalDevAdminAuthBypass(request: PlatformHotPathRequest): Boolean {
        return localDevAdminAuthBypass && isLoopback(request.remoteAddress)
    }

    private fun writeLocalDevAdminSession(exchange: HttpExchange) {
        writeJson(
            exchange,
            200,
            JsonCodec.writeObject(
                "status" to "ok",
                "reefUserId" to localDevAdminActorId,
                "githubLogin" to "local-dev-admin",
                "displayName" to "Local Dev Admin",
                "trustState" to "trusted",
                "roles" to listOf("operator", "participant"),
                "authProvider" to "local-dev",
                "expiresAt" to ""
            )
        )
    }

    private fun adminSessionCookie(exchange: HttpExchange): String {
        return adminSessionCookie(exchange.requestHeaders)
    }

    private fun adminSessionCookie(headers: Headers): String {
        return headerValue(headers, "Cookie")
            .split(";")
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$adminSessionCookieName=") }
            ?.substringAfter("=")
            .orEmpty()
    }

    private fun setAdminSessionCookie(exchange: HttpExchange, token: String) {
        exchange.responseHeaders.add(
            "Set-Cookie",
            adminCookieValue("$adminSessionCookieName=$token; Max-Age=43200")
        )
    }

    private fun clearAdminSessionCookie(exchange: HttpExchange) {
        exchange.responseHeaders.add(
            "Set-Cookie",
            adminCookieValue("$adminSessionCookieName=; Max-Age=0")
        )
    }

    private fun adminCookieValue(prefix: String): String {
        val secure = if (adminSessionCookieSecure) "; Secure" else ""
        return "$prefix; Path=/; HttpOnly; SameSite=Lax$secure"
    }

    private fun adminUiRedirectLocation(redirectPath: String): String {
        val path = com.reef.platform.application.admin.AdminAuthTokenCodec.redirectPath(redirectPath)
        return localDevAdminUiBaseUrl?.let { baseUrl -> "$baseUrl$path" } ?: path
    }

    // Duplicated from PlatformHttpServer's own header/query/json-writer helpers rather
    // than shared, since those stay generic HTTP utilities used well beyond admin auth;
    // see docs/steering/kotlin.md API-layer guidance for the tradeoff.
    private fun headerValue(exchange: HttpExchange, name: String): String {
        return headerValue(exchange.requestHeaders, name)
    }

    private fun headerValue(headers: Headers, name: String): String {
        return headers[name]?.firstOrNull().orEmpty()
    }

    private fun queryParam(exchange: HttpExchange, name: String): String {
        val raw = exchange.requestURI.rawQuery ?: return ""
        return raw.split("&").asSequence()
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index < 0) return@mapNotNull null
                val key = urlDecode(part.substring(0, index))
                if (key == name) urlDecode(part.substring(index + 1)) else null
            }
            .firstOrNull()
            .orEmpty()
    }

    private fun urlDecode(value: String): String {
        return URLDecoder.decode(value, Charsets.UTF_8)
    }

    private fun redirect(exchange: HttpExchange, location: String) {
        applyLocalDevAdminUiCors(exchange)
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun writeJson(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray()
        applyLocalDevAdminUiCors(exchange)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }

    private fun writeHotPathResponse(exchange: HttpExchange, response: PlatformHotPathResponse) {
        applyLocalDevAdminUiCors(exchange)
        if (response.body.isEmpty() && response.contentType == null) {
            exchange.sendResponseHeaders(response.status, -1)
            exchange.close()
            return
        }
        val bytes = response.body.toByteArray()
        response.contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
        exchange.sendResponseHeaders(response.status, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }

    private fun methodNotAllowed(exchange: HttpExchange) {
        applyLocalDevAdminUiCors(exchange)
        exchange.sendResponseHeaders(405, -1)
        exchange.close()
    }
}

internal fun localDevAdminUiBaseUrl(lookup: (String) -> String? = System::getenv): String? {
    val reefEnv = RuntimeEnv.string("REEF_ENV", "", lookup).trim().lowercase()
    if (reefEnv != "local") return null
    val raw = RuntimeEnv.string("LOCAL_DEV_ADMIN_UI_BASE_URL", "", lookup).trim().trimEnd('/')
    if (raw.isBlank()) return null
    val uri = URI(raw)
    require(uri.scheme == "http") { "LOCAL_DEV_ADMIN_UI_BASE_URL must use http in local dev" }
    val host = uri.host?.lowercase().orEmpty()
    require(host == "localhost" || host == "127.0.0.1" || host == "::1") {
        "LOCAL_DEV_ADMIN_UI_BASE_URL must be a loopback host"
    }
    require(uri.rawPath.isNullOrBlank() || uri.rawPath == "/") {
        "LOCAL_DEV_ADMIN_UI_BASE_URL must not include a path"
    }
    require(uri.rawQuery == null && uri.rawFragment == null) {
        "LOCAL_DEV_ADMIN_UI_BASE_URL must not include query or fragment"
    }
    require(uri.port > 0) { "LOCAL_DEV_ADMIN_UI_BASE_URL must include a port" }
    return raw
}

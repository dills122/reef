package com.reef.platform.api

import com.reef.platform.application.admin.AdminApplicationService
import com.reef.platform.application.admin.AdminAuthService
import com.reef.platform.application.admin.AdminGitHubOAuthClient
import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.AuthorizationException
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.URLDecoder
import java.time.Instant

internal data class AdminRequestPrincipal(
    val actorId: String,
    val correlationId: String,
    val occurredAt: String
)

private const val localDevAdminActorId = "admin-cli"

internal enum class InternalHttpExposureMode {
    Disabled,
    LocalOnly,
    Enabled;

    companion object {
        fun fromEnv(): InternalHttpExposureMode {
            return when (RuntimeEnv.string("PLATFORM_INTERNAL_HTTP_MODE", "local").trim().lowercase()) {
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
    private val arenaAdminService: AdminApplicationService?,
    private val adminAuthService: AdminAuthService?,
    private val adminIdentityService: AdminIdentityService?,
    private val adminGitHubOAuthClient: AdminGitHubOAuthClient?,
    private val adminSessionCookieName: String,
    private val adminSessionCookieSecure: Boolean,
    private val localDevAdminAuthBypass: Boolean,
    private val internalHttpExposureMode: InternalHttpExposureMode
) {
    private val adminRequestPrincipal = ThreadLocal<AdminRequestPrincipal?>()

    fun register(server: HttpServer) {
        server.createContext("/admin/auth/github/start") { exchange ->
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
                redirect(exchange, state.redirectPath)
            } catch (ex: IllegalArgumentException) {
                writeHotPathResponse(exchange, PlatformHotPathResponse(401, JsonCodec.writeObject("error" to "admin auth failed")))
            }
        }

        server.createContext("/admin/auth/session") { exchange ->
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
                val runtimeRoles = arenaAdminService
                    ?.listActorRoles(session.reefUserId)
                    ?.map { it.roleId }
                    .orEmpty()
                val roles = (identityRoles + runtimeRoles).distinct().sorted()
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

        val envName = when (route.fallbackTokenFamily) {
            "analytics" -> "ANALYTICS_EXPORT_API_TOKEN"
            "admin" -> "ADMIN_API_TOKEN"
            else -> "ARENA_ADMIN_API_TOKEN"
        }
        val token = RuntimeEnv.string(envName, "")
        val expected = "Bearer $token"
        if (token.isNotBlank() && headerValue(exchange, "Authorization") == expected) {
            return principal(exchange)
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

    private fun bearerToken(exchange: HttpExchange): String? {
        val authorization = headerValue(exchange, "Authorization")
        if (!authorization.startsWith("Bearer ")) return null
        return authorization.removePrefix("Bearer ").trim().ifBlank { null }
    }

    private fun adminPrincipalForActor(exchange: HttpExchange, actorId: String): AdminRequestPrincipal {
        val headerPrincipal = principal(exchange)
        return headerPrincipal.copy(actorId = actorId)
    }

    private fun adminAuthUnavailableResponse(): PlatformHotPathResponse {
        return PlatformHotPathResponse(503, JsonCodec.writeObject("error" to "admin auth is not configured"))
    }

    private fun allowLocalDevAdminAuthBypass(exchange: HttpExchange): Boolean {
        return localDevAdminAuthBypass && isLoopback(exchange.remoteAddress.address)
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
                "roles" to listOf("arena-operator", "participant", "local-dev"),
                "authProvider" to "local-dev",
                "expiresAt" to ""
            )
        )
    }

    private fun adminSessionCookie(exchange: HttpExchange): String {
        return headerValue(exchange, "Cookie")
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
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun writeJson(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }

    private fun writeHotPathResponse(exchange: HttpExchange, response: PlatformHotPathResponse) {
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
        exchange.sendResponseHeaders(405, -1)
        exchange.close()
    }
}

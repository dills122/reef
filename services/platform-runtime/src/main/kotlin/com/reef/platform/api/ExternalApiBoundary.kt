package com.reef.platform.api

import com.sun.net.httpserver.Headers
import java.sql.DriverManager
import java.time.Instant

data class BoundaryError(
    val status: Int,
    val code: String,
    val message: String
)

interface AuthHook {
    fun authorize(clientId: String, token: String?): BoundaryError?
}

interface RateLimitHook {
    fun allow(clientId: String, route: String): BoundaryError?
}

interface IdempotencyPolicy {
    fun validate(clientId: String, route: String, idempotencyKey: String?): BoundaryError?
}

interface IdempotencyStore {
    fun find(clientId: String, route: String, idempotencyKey: String): IdempotencyResult?
    fun save(clientId: String, route: String, idempotencyKey: String, result: IdempotencyResult)
}

data class IdempotencyResult(
    val status: Int,
    val payload: String
)

class AllowAllAuthHook : AuthHook {
    override fun authorize(clientId: String, token: String?): BoundaryError? = null
}

class StaticTokenAuthHook(
    private val tokensByClientId: Map<String, String>
) : AuthHook {
    override fun authorize(clientId: String, token: String?): BoundaryError? {
        val expected = tokensByClientId[clientId]
            ?: return BoundaryError(401, "UNAUTHORIZED", "unknown client id")
        val provided = token?.removePrefix("Bearer ")?.trim()
        if (provided.isNullOrBlank() || provided != expected) {
            return BoundaryError(401, "UNAUTHORIZED", "invalid token")
        }
        return null
    }
}

class AllowAllRateLimitHook : RateLimitHook {
    override fun allow(clientId: String, route: String): BoundaryError? = null
}

class FixedWindowRateLimitHook(
    private val store: RateLimitStore,
    private val maxRequests: Int,
    private val windowSeconds: Long,
    private val clock: () -> Instant = { Instant.now() }
) : RateLimitHook {
    override fun allow(clientId: String, route: String): BoundaryError? {
        if (maxRequests <= 0 || windowSeconds <= 0) return null
        val now = clock()
        val windowStart = now.epochSecond / windowSeconds * windowSeconds
        val count = store.increment(clientId, route, windowStart)
        if (count > maxRequests) {
            return BoundaryError(429, "RATE_LIMITED", "rate limit exceeded")
        }
        return null
    }
}

class RequiredIdempotencyPolicy : IdempotencyPolicy {
    override fun validate(clientId: String, route: String, idempotencyKey: String?): BoundaryError? {
        if (idempotencyKey.isNullOrBlank()) {
            return BoundaryError(
                status = 400,
                code = "IDEMPOTENCY_KEY_REQUIRED",
                message = "idempotency key header is required for mutating requests"
            )
        }
        return null
    }
}

class InMemoryIdempotencyStore : IdempotencyStore {
    private val results = java.util.concurrent.ConcurrentHashMap<String, IdempotencyResult>()

    override fun find(clientId: String, route: String, idempotencyKey: String): IdempotencyResult? {
        return results["$clientId|$route|$idempotencyKey"]
    }

    override fun save(clientId: String, route: String, idempotencyKey: String, result: IdempotencyResult) {
        results.putIfAbsent("$clientId|$route|$idempotencyKey", result)
    }
}

class PostgresIdempotencyStore(
    private val jdbcUrl: String,
    private val dbUser: String,
    private val dbPassword: String
) : IdempotencyStore {
    init {
        connection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS api_idempotency_records (
                      client_id TEXT NOT NULL,
                      route TEXT NOT NULL,
                      idempotency_key TEXT NOT NULL,
                      status INTEGER NOT NULL,
                      payload TEXT NOT NULL,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      PRIMARY KEY (client_id, route, idempotency_key)
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override fun find(clientId: String, route: String, idempotencyKey: String): IdempotencyResult? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT status, payload
                FROM api_idempotency_records
                WHERE client_id = ? AND route = ? AND idempotency_key = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, clientId)
                ps.setString(2, route)
                ps.setString(3, idempotencyKey)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return IdempotencyResult(
                        status = rs.getInt("status"),
                        payload = rs.getString("payload")
                    )
                }
            }
        }
    }

    override fun save(clientId: String, route: String, idempotencyKey: String, result: IdempotencyResult) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO api_idempotency_records(client_id, route, idempotency_key, status, payload)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (client_id, route, idempotency_key) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, clientId)
                ps.setString(2, route)
                ps.setString(3, idempotencyKey)
                ps.setInt(4, result.status)
                ps.setString(5, result.payload)
                ps.executeUpdate()
            }
        }
    }

    private fun connection() = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)
}

interface RateLimitStore {
    fun increment(clientId: String, route: String, windowStartEpochSeconds: Long): Int
}

class InMemoryRateLimitStore : RateLimitStore {
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    override fun increment(clientId: String, route: String, windowStartEpochSeconds: Long): Int {
        val key = "$clientId|$route|$windowStartEpochSeconds"
        return counts.merge(key, 1) { current, one -> current + one } ?: 1
    }
}

class ExternalApiBoundary(
    private val authHook: AuthHook = AllowAllAuthHook(),
    private val rateLimitHook: RateLimitHook = AllowAllRateLimitHook(),
    private val idempotencyPolicy: IdempotencyPolicy = RequiredIdempotencyPolicy()
) {
    fun checkWrite(headers: Headers, route: String): BoundaryError? {
        val clientId = clientId(headers)
            ?: return BoundaryError(401, "CLIENT_ID_REQUIRED", "missing X-Client-Id header")

        val authError = authHook.authorize(clientId, headers.firstValue("Authorization"))
        if (authError != null) return authError

        val rateLimitError = rateLimitHook.allow(clientId, route)
        if (rateLimitError != null) return rateLimitError

        return idempotencyPolicy.validate(clientId, route, idempotencyKey(headers))
    }

    fun toErrorJson(error: BoundaryError, correlationId: String): String {
        return """
            {
              "code":"${JsonFields.escape(error.code)}",
              "message":"${JsonFields.escape(error.message)}",
              "correlationId":"${JsonFields.escape(correlationId)}"
            }
        """.trimIndent()
    }

    fun clientId(headers: Headers): String? = headers.firstValue("X-Client-Id")
    fun idempotencyKey(headers: Headers): String? = headers.firstValue("Idempotency-Key")
}

private fun Headers.firstValue(name: String): String? {
    return this[name]?.firstOrNull()
}

data class BoundaryHooks(
    val authHook: AuthHook,
    val rateLimitHook: RateLimitHook,
    val idempotencyStore: IdempotencyStore
)

fun defaultBoundaryHooks(): BoundaryHooks {
    val authMode = (System.getenv("EXTERNAL_API_AUTH_MODE") ?: "allow-all").lowercase()
    val authHook = when (authMode) {
        "static-token" -> StaticTokenAuthHook(parseStaticTokens(System.getenv("EXTERNAL_API_TOKENS")))
        else -> AllowAllAuthHook()
    }

    val rateMode = (System.getenv("EXTERNAL_API_RATE_LIMIT_MODE") ?: "allow-all").lowercase()
    val rateLimitHook = when (rateMode) {
        "fixed-window" -> {
            val max = System.getenv("EXTERNAL_API_RATE_LIMIT_MAX")?.toIntOrNull() ?: 120
            val window = System.getenv("EXTERNAL_API_RATE_LIMIT_WINDOW_SECONDS")?.toLongOrNull() ?: 60L
            FixedWindowRateLimitHook(InMemoryRateLimitStore(), max, window)
        }
        else -> AllowAllRateLimitHook()
    }

    val idempotencyMode = (System.getenv("EXTERNAL_API_IDEMPOTENCY_STORE") ?: "inmemory").lowercase()
    val idempotencyStore = if (idempotencyMode == "postgres") {
        val jdbcUrl = System.getenv("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
        val dbUser = System.getenv("RUNTIME_DB_USER") ?: "reef"
        val dbPassword = System.getenv("RUNTIME_DB_PASSWORD") ?: "reef"
        PostgresIdempotencyStore(jdbcUrl, dbUser, dbPassword)
    } else {
        InMemoryIdempotencyStore()
    }

    return BoundaryHooks(
        authHook = authHook,
        rateLimitHook = rateLimitHook,
        idempotencyStore = idempotencyStore
    )
}

private fun parseStaticTokens(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(",")
        .mapNotNull { pair ->
            val parts = pair.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val clientId = parts[0].trim()
            val token = parts[1].trim()
            if (clientId.isBlank() || token.isBlank()) return@mapNotNull null
            clientId to token
        }
        .toMap()
}

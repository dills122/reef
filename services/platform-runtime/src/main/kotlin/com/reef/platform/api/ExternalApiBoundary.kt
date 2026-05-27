package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.sun.net.httpserver.Headers
import java.time.Instant
import javax.sql.DataSource

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

interface AbuseProtectionHook {
    fun allow(clientId: String, route: String): BoundaryError?
    fun observe(clientId: String, route: String, responseStatus: Int, rejectCode: String?)
}

interface IdempotencyStore {
    fun find(clientId: String, route: String, idempotencyKey: String): IdempotencyResult?
    fun save(clientId: String, route: String, idempotencyKey: String, result: IdempotencyResult, ttlClass: IdempotencyTtlClass)
    fun cleanupExpired(now: Instant = Instant.now()) {}
}

data class IdempotencyResult(
    val status: Int,
    val payload: String
)

enum class IdempotencyTtlClass {
    SHORT,
    STANDARD,
    LONG
}

interface IdempotencyRetentionPolicy {
    fun ttlFor(route: String): IdempotencyTtlClass
    fun durationSeconds(ttlClass: IdempotencyTtlClass): Long
}

class DefaultIdempotencyRetentionPolicy : IdempotencyRetentionPolicy {
    override fun ttlFor(route: String): IdempotencyTtlClass {
        return when {
            route.contains("/cancel") || route.contains("/modify") -> IdempotencyTtlClass.STANDARD
            else -> IdempotencyTtlClass.LONG
        }
    }

    override fun durationSeconds(ttlClass: IdempotencyTtlClass): Long {
        return when (ttlClass) {
            IdempotencyTtlClass.SHORT -> 15 * 60L
            IdempotencyTtlClass.STANDARD -> 24 * 60 * 60L
            IdempotencyTtlClass.LONG -> 7 * 24 * 60 * 60L
        }
    }
}

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

class AllowAllAbuseProtectionHook : AbuseProtectionHook {
    override fun allow(clientId: String, route: String): BoundaryError? = null

    override fun observe(clientId: String, route: String, responseStatus: Int, rejectCode: String?) {}
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
    private data class Entry(val result: IdempotencyResult, val expiresAtEpochSeconds: Long)

    private val results = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val retentionPolicy: IdempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy()

    override fun find(clientId: String, route: String, idempotencyKey: String): IdempotencyResult? {
        val entry = results["$clientId|$route|$idempotencyKey"] ?: return null
        if (entry.expiresAtEpochSeconds <= Instant.now().epochSecond) {
            results.remove("$clientId|$route|$idempotencyKey")
            return null
        }
        return entry.result
    }

    override fun save(clientId: String, route: String, idempotencyKey: String, result: IdempotencyResult, ttlClass: IdempotencyTtlClass) {
        val expiresAt = Instant.now().epochSecond + retentionPolicy.durationSeconds(ttlClass)
        results.putIfAbsent("$clientId|$route|$idempotencyKey", Entry(result, expiresAt))
    }

    override fun cleanupExpired(now: Instant) {
        results.entries.removeIf { it.value.expiresAtEpochSeconds <= now.epochSecond }
    }
}

class PostgresIdempotencyStore(
    private val dataSource: DataSource,
    private val retentionPolicy: IdempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy()
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
                      expires_at TIMESTAMPTZ NOT NULL,
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
                  AND expires_at > NOW()
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

    override fun save(clientId: String, route: String, idempotencyKey: String, result: IdempotencyResult, ttlClass: IdempotencyTtlClass) {
        val ttlSeconds = retentionPolicy.durationSeconds(ttlClass)
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO api_idempotency_records(client_id, route, idempotency_key, status, payload, expires_at)
                VALUES (?, ?, ?, ?, ?, NOW() + (? * INTERVAL '1 second'))
                ON CONFLICT (client_id, route, idempotency_key) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, clientId)
                ps.setString(2, route)
                ps.setString(3, idempotencyKey)
                ps.setInt(4, result.status)
                ps.setString(5, result.payload)
                ps.setLong(6, ttlSeconds)
                ps.executeUpdate()
            }
        }
    }

    override fun cleanupExpired(now: Instant) {
        connection().use { conn ->
            conn.prepareStatement(
                "DELETE FROM api_idempotency_records WHERE expires_at <= to_timestamp(?)"
            ).use { ps ->
                ps.setLong(1, now.epochSecond)
                ps.executeUpdate()
            }
        }
    }

    private fun connection() = dataSource.connection
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

class RejectRateAbuseProtectionHook(
    private val maxRejects: Int,
    private val windowSeconds: Long,
    private val blockSeconds: Long,
    private val trackedRejectCodes: Set<String>,
    private val warningOnly: Boolean = false,
    private val clock: () -> Instant = { Instant.now() }
) : AbuseProtectionHook {
    private data class RejectState(
        val windowStartEpochSeconds: Long,
        val rejectCount: Int,
        val blockedUntilEpochSeconds: Long,
        val warnedInWindow: Boolean
    )

    private val states = java.util.concurrent.ConcurrentHashMap<String, RejectState>()
    private val tripCount = java.util.concurrent.atomic.AtomicLong(0)
    private val blockedCount = java.util.concurrent.atomic.AtomicLong(0)
    private val releaseCount = java.util.concurrent.atomic.AtomicLong(0)

    override fun allow(clientId: String, route: String): BoundaryError? {
        if (!isConfigured()) return null
        val now = clock().epochSecond
        val key = key(clientId, route)
        val state = states.computeIfPresent(key) { _, current ->
            if (current.blockedUntilEpochSeconds > 0 && now >= current.blockedUntilEpochSeconds) {
                releaseCount.incrementAndGet()
                current.copy(blockedUntilEpochSeconds = 0, warnedInWindow = false)
            } else {
                current
            }
        }
        if (state != null && state.blockedUntilEpochSeconds > now) {
            blockedCount.incrementAndGet()
            val secondsRemaining = state.blockedUntilEpochSeconds - now
            return BoundaryError(429, "ABUSE_BLOCKED", "client temporarily blocked for abusive traffic ($secondsRemaining seconds remaining)")
        }
        return null
    }

    override fun observe(clientId: String, route: String, responseStatus: Int, rejectCode: String?) {
        if (!isConfigured()) return
        if (responseStatus < 200 || responseStatus >= 300) return
        val normalizedCode = rejectCode?.uppercase()?.trim().orEmpty()
        if (normalizedCode.isBlank() || !trackedRejectCodes.contains(normalizedCode)) return

        val now = clock().epochSecond
        val currentWindowStart = now / windowSeconds * windowSeconds
        val key = key(clientId, route)
        states.compute(key) { _, current ->
            val base = when {
                current == null -> RejectState(currentWindowStart, 0, 0, false)
                current.windowStartEpochSeconds != currentWindowStart -> RejectState(currentWindowStart, 0, 0, false)
                current.blockedUntilEpochSeconds > 0 && now >= current.blockedUntilEpochSeconds -> current.copy(blockedUntilEpochSeconds = 0, warnedInWindow = false)
                else -> current
            }
            val updatedCount = base.rejectCount + 1
            if (updatedCount <= maxRejects) {
                return@compute base.copy(rejectCount = updatedCount)
            }

            if (warningOnly) {
                if (!base.warnedInWindow) {
                    System.err.println(
                        "abuse_breaker_warning mode=reject-rate clientId=$clientId route=$route rejectCode=$normalizedCode rejects=$updatedCount windowSeconds=$windowSeconds"
                    )
                }
                return@compute base.copy(rejectCount = updatedCount, warnedInWindow = true)
            }

            if (base.blockedUntilEpochSeconds > now) {
                return@compute base
            }

            val blockedUntil = now + blockSeconds
            val trips = tripCount.incrementAndGet()
            System.err.println(
                "abuse_breaker_trip mode=reject-rate clientId=$clientId route=$route rejectCode=$normalizedCode rejects=$updatedCount maxRejects=$maxRejects windowSeconds=$windowSeconds blockSeconds=$blockSeconds trips=$trips"
            )
            base.copy(rejectCount = updatedCount, blockedUntilEpochSeconds = blockedUntil, warnedInWindow = false)
        }
    }

    private fun isConfigured(): Boolean {
        return maxRejects > 0 && windowSeconds > 0 && blockSeconds > 0 && trackedRejectCodes.isNotEmpty()
    }

    private fun key(clientId: String, route: String): String {
        return "$clientId|$route"
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
    val abuseProtectionHook: AbuseProtectionHook,
    val idempotencyStore: IdempotencyStore,
    val idempotencyRetentionPolicy: IdempotencyRetentionPolicy,
    val commandCaptureStore: CommandCaptureStore
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

    val abuseMode = (System.getenv("EXTERNAL_API_ABUSE_BREAKER_MODE") ?: "off").lowercase()
    val abuseProtectionHook = when (abuseMode) {
        "reject-rate" -> {
            val enabled = envBool(System.getenv("EXTERNAL_API_ABUSE_BREAKER_ENABLED"), true)
            val rejectRateEnabled = envBool(System.getenv("EXTERNAL_API_ABUSE_BREAKER_REJECT_RATE_ENABLED"), true)
            if (!enabled || !rejectRateEnabled) {
                AllowAllAbuseProtectionHook()
            } else {
                val maxRejects = System.getenv("EXTERNAL_API_ABUSE_BREAKER_MAX_REJECTS")?.toIntOrNull() ?: 50
                val windowSeconds = System.getenv("EXTERNAL_API_ABUSE_BREAKER_WINDOW_SECONDS")?.toLongOrNull() ?: 30L
                val blockSeconds = System.getenv("EXTERNAL_API_ABUSE_BREAKER_BLOCK_SECONDS")?.toLongOrNull() ?: 60L
                val warningOnly = envBool(System.getenv("EXTERNAL_API_ABUSE_BREAKER_WARN_ONLY"), false)
                val codes = parseRejectCodes(System.getenv("EXTERNAL_API_ABUSE_BREAKER_REJECT_CODES"))
                RejectRateAbuseProtectionHook(
                    maxRejects = maxRejects,
                    windowSeconds = windowSeconds,
                    blockSeconds = blockSeconds,
                    trackedRejectCodes = codes,
                    warningOnly = warningOnly
                )
            }
        }
        else -> AllowAllAbuseProtectionHook()
    }

    val idempotencyMode = (System.getenv("EXTERNAL_API_IDEMPOTENCY_STORE") ?: "inmemory").lowercase()
    val retentionPolicy = DefaultIdempotencyRetentionPolicy()
    val idempotencyStore = if (idempotencyMode == "postgres") {
        val jdbcUrl = System.getenv("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
        val dbUser = System.getenv("RUNTIME_DB_USER") ?: "reef"
        val dbPassword = System.getenv("RUNTIME_DB_PASSWORD") ?: "reef"
        PostgresIdempotencyStore(RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword), retentionPolicy)
    } else {
        InMemoryIdempotencyStore()
    }

    return BoundaryHooks(
        authHook = authHook,
        rateLimitHook = rateLimitHook,
        abuseProtectionHook = abuseProtectionHook,
        idempotencyStore = idempotencyStore,
        idempotencyRetentionPolicy = retentionPolicy,
        commandCaptureStore = defaultCommandCaptureStore()
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

private fun parseRejectCodes(raw: String?): Set<String> {
    val fallback = setOf("INVALID_STATE", "NOT_FOUND", "REFERENCE_DATA_ERROR", "VALIDATION_ERROR")
    if (raw.isNullOrBlank()) return fallback
    val values = raw.split(",")
        .map { it.trim().uppercase() }
        .filter { it.isNotBlank() }
        .toSet()
    return if (values.isEmpty()) fallback else values
}

private fun envBool(raw: String?, defaultValue: Boolean): Boolean {
    if (raw.isNullOrBlank()) return defaultValue
    return when (raw.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> defaultValue
    }
}

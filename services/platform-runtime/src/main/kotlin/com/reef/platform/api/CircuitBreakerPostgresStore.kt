package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import javax.sql.DataSource

class PostgresCommandCircuitBreakerStore(
    private val dataSource: DataSource,
    private val names: PostgresBoundarySqlNames = PostgresBoundarySqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv(),
    private val cacheTtlMillis: Long = 1_000L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) : CommandCircuitBreakerStore {
    private data class CachedState(
        val state: CommandCircuitBreakerState?,
        val expiresAtMillis: Long
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedState>()

    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.boundaryCommandCircuitBreakers(names.commandCircuitBreakers)
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.commandCircuitBreakers} (
                      scope_type TEXT NOT NULL,
                      scope_id TEXT NOT NULL,
                      tripped BOOLEAN NOT NULL,
                      reason TEXT NOT NULL DEFAULT '',
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      PRIMARY KEY (scope_type, scope_id),
                      CHECK (scope_type IN ('GLOBAL', 'VENUE_SESSION', 'INSTRUMENT'))
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override fun evaluate(request: CommandCircuitBreakerRequest): BoundaryError? {
        val tripped = firstTripped(
            stateFor("GLOBAL", "*"),
            if (request.venueSessionId.isBlank()) null else stateFor("VENUE_SESSION", request.venueSessionId),
            if (request.instrumentId.isBlank()) null else stateFor("INSTRUMENT", request.instrumentId)
        ) ?: return null
        val suffix = if (tripped.reason.isBlank()) {
            ""
        } else {
            ": ${tripped.reason}"
        }
        return BoundaryError(
            503,
            "COMMAND_CIRCUIT_BREAKER_TRIPPED",
            "command circuit breaker tripped for ${tripped.scopeType}:${tripped.scopeId}$suffix"
        )
    }

    override fun setBreaker(scopeType: String, scopeId: String, tripped: Boolean, reason: String) {
        require(scopeType == "GLOBAL" || scopeType == "VENUE_SESSION" || scopeType == "INSTRUMENT") {
            "scopeType must be GLOBAL, VENUE_SESSION, or INSTRUMENT"
        }
        val normalizedScopeId = if (scopeType == "GLOBAL") "*" else scopeId
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.commandCircuitBreakers}(scope_type, scope_id, tripped, reason, updated_at)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (scope_type, scope_id)
                DO UPDATE SET tripped = EXCLUDED.tripped, reason = EXCLUDED.reason, updated_at = NOW()
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, scopeType)
                ps.setString(2, normalizedScopeId)
                ps.setBoolean(3, tripped)
                ps.setString(4, reason)
                ps.executeUpdate()
            }
        }
        cache.remove("$scopeType|$normalizedScopeId")
    }

    override fun listBreakers(): List<CommandCircuitBreakerState> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT scope_type, scope_id, tripped, reason, updated_at
                FROM ${names.commandCircuitBreakers}
                ORDER BY scope_type, scope_id
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<CommandCircuitBreakerState>()
                    while (rs.next()) {
                        rows.add(
                            CommandCircuitBreakerState(
                                scopeType = rs.getString("scope_type"),
                                scopeId = rs.getString("scope_id"),
                                tripped = rs.getBoolean("tripped"),
                                reason = rs.getString("reason").orEmpty(),
                                updatedAt = rs.getString("updated_at").orEmpty()
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    private fun firstTripped(vararg states: CommandCircuitBreakerState?): CommandCircuitBreakerState? {
        return states.firstOrNull { it?.tripped == true }
    }

    private fun stateFor(scopeType: String, scopeId: String): CommandCircuitBreakerState? {
        val key = "$scopeType|$scopeId"
        val now = nowMillis()
        cache[key]?.let { cached ->
            if (cached.expiresAtMillis > now) return cached.state
        }
        val loaded = loadState(scopeType, scopeId)
        if (cacheTtlMillis > 0) {
            cache[key] = CachedState(loaded, now + cacheTtlMillis)
        }
        return loaded
    }

    private fun loadState(scopeType: String, scopeId: String): CommandCircuitBreakerState? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT scope_type, scope_id, tripped, reason, updated_at
                FROM ${names.commandCircuitBreakers}
                WHERE scope_type = ? AND scope_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, scopeType)
                ps.setString(2, scopeId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return CommandCircuitBreakerState(
                        scopeType = rs.getString("scope_type"),
                        scopeId = rs.getString("scope_id"),
                        tripped = rs.getBoolean("tripped"),
                        reason = rs.getString("reason").orEmpty(),
                        updatedAt = rs.getString("updated_at").orEmpty()
                    )
                }
            }
        }
    }

    private fun connection() = dataSource.connection
}

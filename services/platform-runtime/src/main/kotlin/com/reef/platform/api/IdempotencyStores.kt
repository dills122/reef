package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

class InMemoryIdempotencyStore(
    private val retentionPolicy: IdempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
    private val clock: Clock = Clock.systemUTC()
) : IdempotencyStore {
    private data class Entry(val result: IdempotencyResult, val expiresAtEpochSeconds: Long)

    private val results = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    override fun find(clientId: String, route: String, idempotencyKey: String): IdempotencyResult? {
        val key = "$clientId|$route|$idempotencyKey"
        val entry = results[key] ?: return null
        if (entry.expiresAtEpochSeconds <= clock.instant().epochSecond) {
            results.remove(key, entry)
            return null
        }
        return entry.result
    }

    override fun save(clientId: String, route: String, idempotencyKey: String, result: IdempotencyResult, ttlClass: IdempotencyTtlClass) {
        results.compute("$clientId|$route|$idempotencyKey") { _, existing ->
            val now = clock.instant().epochSecond
            if (existing == null || existing.expiresAtEpochSeconds <= now) {
                Entry(result, now + retentionPolicy.durationSeconds(ttlClass))
            } else {
                existing
            }
        }
    }

    override fun cleanupExpired(now: Instant) {
        results.forEach { (key, entry) ->
            if (entry.expiresAtEpochSeconds <= now.epochSecond) results.remove(key, entry)
        }
    }
}

class PostgresIdempotencyStore(
    private val dataSource: DataSource,
    private val retentionPolicy: IdempotencyRetentionPolicy = DefaultIdempotencyRetentionPolicy(),
    private val names: PostgresBoundarySqlNames = PostgresBoundarySqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : IdempotencyStore {
    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.boundaryIdempotency(names.idempotencyRecords)
                )
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE SCHEMA IF NOT EXISTS ${names.schemaName}
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.idempotencyRecords} (
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
                FROM ${names.idempotencyRecords}
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
                INSERT INTO ${names.idempotencyRecords} AS stored(client_id, route, idempotency_key, status, payload, expires_at)
                VALUES (?, ?, ?, ?, ?, NOW() + (? * INTERVAL '1 second'))
                ON CONFLICT (client_id, route, idempotency_key) DO UPDATE
                SET status = EXCLUDED.status,
                    payload = EXCLUDED.payload,
                    created_at = NOW(),
                    expires_at = EXCLUDED.expires_at
                WHERE stored.expires_at <= NOW()
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
                "DELETE FROM ${names.idempotencyRecords} WHERE expires_at <= to_timestamp(?)"
            ).use { ps ->
                ps.setLong(1, now.epochSecond)
                ps.executeUpdate()
            }
        }
    }

    private fun connection() = dataSource.connection
}

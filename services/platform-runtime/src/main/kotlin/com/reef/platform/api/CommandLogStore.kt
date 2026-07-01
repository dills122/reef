package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import java.sql.ResultSet
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

enum class CommandLogStatus {
    RECEIVED,
    PROCESSING,
    COMPLETED,
    FAILED
}

data class CommandLogRecord(
    val commandId: String,
    val clientId: String,
    val route: String,
    val idempotencyKey: String,
    val traceId: String,
    val correlationId: String,
    val actorId: String,
    val commandType: String,
    val receivedAt: Instant,
    val payloadJson: String,
    val status: CommandLogStatus = CommandLogStatus.RECEIVED,
    val attemptCount: Int = 0,
    val lastError: String = "",
    val responseStatus: Int = 0,
    val responsePayloadJson: String = "{}"
)

data class CommandLogAppendResult(
    val appended: Boolean,
    val record: CommandLogRecord
)

interface CommandLogStore {
    fun append(record: CommandLogRecord): CommandLogAppendResult
    fun findByCommandId(commandId: String): CommandLogRecord?
    fun findByIdempotency(clientId: String, route: String, idempotencyKey: String): CommandLogRecord?
    fun findByStatus(status: CommandLogStatus, limit: Int): List<CommandLogRecord>
    fun claimReceived(limit: Int): List<CommandLogRecord>
    fun statusCounts(): Map<CommandLogStatus, Long>
    fun markProcessing(commandId: String)
    fun markCompleted(commandId: String, responseStatus: Int, responsePayloadJson: String)
    fun markFailed(commandId: String, responseStatus: Int, errorMessage: String)
}

class InMemoryCommandLogStore : CommandLogStore {
    private val byCommandId = ConcurrentHashMap<String, CommandLogRecord>()
    private val idempotencyToCommandId = ConcurrentHashMap<String, String>()

    override fun append(record: CommandLogRecord): CommandLogAppendResult {
        val idempotencyKey = idempotencyKey(record.clientId, record.route, record.idempotencyKey)
        val existingByIdempotency = idempotencyToCommandId[idempotencyKey]?.let { byCommandId[it] }
        if (existingByIdempotency != null) {
            return CommandLogAppendResult(appended = false, record = existingByIdempotency)
        }

        val existingByCommandId = byCommandId.putIfAbsent(record.commandId, record)
        if (existingByCommandId != null) {
            return CommandLogAppendResult(appended = false, record = existingByCommandId)
        }

        val previousCommandId = idempotencyToCommandId.putIfAbsent(idempotencyKey, record.commandId)
        if (previousCommandId != null) {
            byCommandId.remove(record.commandId, record)
            return CommandLogAppendResult(appended = false, record = byCommandId.getValue(previousCommandId))
        }

        return CommandLogAppendResult(appended = true, record = record)
    }

    override fun findByCommandId(commandId: String): CommandLogRecord? = byCommandId[commandId]

    override fun findByIdempotency(clientId: String, route: String, idempotencyKey: String): CommandLogRecord? {
        return idempotencyToCommandId[idempotencyKey(clientId, route, idempotencyKey)]?.let { byCommandId[it] }
    }

    override fun findByStatus(status: CommandLogStatus, limit: Int): List<CommandLogRecord> {
        if (limit <= 0) return emptyList()
        return byCommandId.values
            .asSequence()
            .filter { it.status == status }
            .sortedBy { it.receivedAt }
            .take(limit)
            .toList()
    }

    override fun claimReceived(limit: Int): List<CommandLogRecord> {
        if (limit <= 0) return emptyList()
        return synchronized(this) {
            byCommandId.values
                .asSequence()
                .filter { it.status == CommandLogStatus.RECEIVED }
                .sortedBy { it.receivedAt }
                .take(limit)
                .map { existing ->
                    val claimed = existing.copy(
                        status = CommandLogStatus.PROCESSING,
                        attemptCount = existing.attemptCount + 1,
                        lastError = ""
                    )
                    byCommandId[existing.commandId] = claimed
                    claimed
                }
                .toList()
        }
    }

    override fun statusCounts(): Map<CommandLogStatus, Long> {
        val counts = CommandLogStatus.values().associateWith { 0L }.toMutableMap()
        byCommandId.values.forEach { record ->
            counts[record.status] = counts.getValue(record.status) + 1L
        }
        return counts
    }

    override fun markProcessing(commandId: String) {
        byCommandId.computeIfPresent(commandId) { _, existing ->
            existing.copy(
                status = CommandLogStatus.PROCESSING,
                attemptCount = existing.attemptCount + 1,
                lastError = ""
            )
        }
    }

    override fun markCompleted(commandId: String, responseStatus: Int, responsePayloadJson: String) {
        byCommandId.computeIfPresent(commandId) { _, existing ->
            existing.copy(
                status = CommandLogStatus.COMPLETED,
                responseStatus = responseStatus,
                responsePayloadJson = responsePayloadJson,
                lastError = ""
            )
        }
    }

    override fun markFailed(commandId: String, responseStatus: Int, errorMessage: String) {
        byCommandId.computeIfPresent(commandId) { _, existing ->
            existing.copy(
                status = CommandLogStatus.FAILED,
                responseStatus = responseStatus,
                lastError = errorMessage
            )
        }
    }

    private fun idempotencyKey(clientId: String, route: String, idempotencyKey: String): String {
        return "$clientId|$route|$idempotencyKey"
    }
}

class PostgresCommandLogStore(
    private val dataSource: DataSource,
    private val names: PostgresCommandLogSqlNames = PostgresCommandLogSqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : CommandLogStore {
    init {
        dataSource.connection.use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(conn, PostgresSchemaRequirements.commandLog(names.commands))
                return@use
            }
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS ${names.schemaName}")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.commands} (
                      command_id TEXT PRIMARY KEY,
                      client_id TEXT NOT NULL,
                      route TEXT NOT NULL,
                      idempotency_key TEXT NOT NULL,
                      trace_id TEXT NOT NULL,
                      correlation_id TEXT NOT NULL,
                      actor_id TEXT NOT NULL,
                      command_type TEXT NOT NULL,
                      received_at TIMESTAMPTZ NOT NULL,
                      payload_json JSONB NOT NULL,
                      status TEXT NOT NULL,
                      attempt_count INTEGER NOT NULL DEFAULT 0,
                      last_error TEXT NOT NULL DEFAULT '',
                      response_status INTEGER NOT NULL DEFAULT 0,
                      response_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      UNIQUE (client_id, route, idempotency_key),
                      CHECK (status IN ('RECEIVED', 'PROCESSING', 'COMPLETED', 'FAILED'))
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_command_log_commands_status_received
                    ON ${names.commands}(status, received_at)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.commands}
                      ADD COLUMN IF NOT EXISTS response_status INTEGER NOT NULL DEFAULT 0,
                      ADD COLUMN IF NOT EXISTS response_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb
                    """.trimIndent()
                )
            }
        }
    }

    override fun append(record: CommandLogRecord): CommandLogAppendResult {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.commands}(
                  command_id,
                  client_id,
                  route,
                  idempotency_key,
                  trace_id,
                  correlation_id,
                  actor_id,
                  command_type,
                  received_at,
                  payload_json,
                  status,
                  attempt_count,
                  last_error
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::jsonb, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, record.commandId)
                ps.setString(2, record.clientId)
                ps.setString(3, record.route)
                ps.setString(4, record.idempotencyKey)
                ps.setString(5, record.traceId)
                ps.setString(6, record.correlationId)
                ps.setString(7, record.actorId)
                ps.setString(8, record.commandType)
                ps.setString(9, record.receivedAt.toString())
                ps.setString(10, record.payloadJson)
                ps.setString(11, record.status.name)
                ps.setInt(12, record.attemptCount)
                ps.setString(13, record.lastError)
                val inserted = ps.executeUpdate() == 1
                if (inserted) return CommandLogAppendResult(appended = true, record = record)
            }
        }

        val existing = findByIdempotency(record.clientId, record.route, record.idempotencyKey)
            ?: findByCommandId(record.commandId)
            ?: error("command log append conflicted but existing command could not be found")
        return CommandLogAppendResult(appended = false, record = existing)
    }

    override fun findByCommandId(commandId: String): CommandLogRecord? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT *
                FROM ${names.commands}
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toCommandLogRecord() else null
                }
            }
        }
    }

    override fun findByIdempotency(clientId: String, route: String, idempotencyKey: String): CommandLogRecord? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT *
                FROM ${names.commands}
                WHERE client_id = ? AND route = ? AND idempotency_key = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, clientId)
                ps.setString(2, route)
                ps.setString(3, idempotencyKey)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toCommandLogRecord() else null
                }
            }
        }
    }

    override fun findByStatus(status: CommandLogStatus, limit: Int): List<CommandLogRecord> {
        if (limit <= 0) return emptyList()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT *
                FROM ${names.commands}
                WHERE status = ?
                ORDER BY received_at, command_id
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, status.name)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    val records = mutableListOf<CommandLogRecord>()
                    while (rs.next()) {
                        records.add(rs.toCommandLogRecord())
                    }
                    return records
                }
            }
        }
    }

    override fun claimReceived(limit: Int): List<CommandLogRecord> {
        if (limit <= 0) return emptyList()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                WITH claimed AS (
                  SELECT command_id
                  FROM ${names.commands}
                  WHERE status = 'RECEIVED'
                  ORDER BY received_at, command_id
                  LIMIT ?
                  FOR UPDATE SKIP LOCKED
                )
                UPDATE ${names.commands} commands
                SET status = 'PROCESSING',
                    attempt_count = commands.attempt_count + 1,
                    last_error = ''
                FROM claimed
                WHERE commands.command_id = claimed.command_id
                RETURNING commands.*
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, limit)
                ps.executeQuery().use { rs ->
                    val records = mutableListOf<CommandLogRecord>()
                    while (rs.next()) {
                        records.add(rs.toCommandLogRecord())
                    }
                    return records
                }
            }
        }
    }

    override fun statusCounts(): Map<CommandLogStatus, Long> {
        val counts = CommandLogStatus.values().associateWith { 0L }.toMutableMap()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT status, COUNT(*) AS count
                FROM ${names.commands}
                GROUP BY status
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        counts[CommandLogStatus.valueOf(rs.getString("status"))] = rs.getLong("count")
                    }
                }
            }
        }
        return counts
    }

    override fun markProcessing(commandId: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE ${names.commands}
                SET status = 'PROCESSING',
                    attempt_count = attempt_count + 1,
                    last_error = ''
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeUpdate()
            }
        }
    }

    override fun markCompleted(commandId: String, responseStatus: Int, responsePayloadJson: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE ${names.commands}
                SET status = 'COMPLETED',
                    response_status = ?,
                    response_payload_json = ?::jsonb,
                    last_error = ''
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, responseStatus)
                ps.setString(2, responsePayloadJson)
                ps.setString(3, commandId)
                ps.executeUpdate()
            }
        }
    }

    override fun markFailed(commandId: String, responseStatus: Int, errorMessage: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE ${names.commands}
                SET status = 'FAILED',
                    response_status = ?,
                    last_error = ?
                WHERE command_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, responseStatus)
                ps.setString(2, errorMessage)
                ps.setString(3, commandId)
                ps.executeUpdate()
            }
        }
    }

    private fun ResultSet.toCommandLogRecord(): CommandLogRecord {
        return CommandLogRecord(
            commandId = getString("command_id"),
            clientId = getString("client_id"),
            route = getString("route"),
            idempotencyKey = getString("idempotency_key"),
            traceId = getString("trace_id"),
            correlationId = getString("correlation_id"),
            actorId = getString("actor_id"),
            commandType = getString("command_type"),
            receivedAt = getTimestamp("received_at").toInstant(),
            payloadJson = getString("payload_json"),
            status = CommandLogStatus.valueOf(getString("status")),
            attemptCount = getInt("attempt_count"),
            lastError = getString("last_error"),
            responseStatus = getInt("response_status"),
            responsePayloadJson = getString("response_payload_json")
        )
    }
}

data class PostgresCommandLogSqlNames(
    private val schema: String = "command_log"
) {
    val schemaName = schemaNameOrDefault(schema)
    val commands = "$schemaName.commands"

    private fun schemaNameOrDefault(schema: String): String {
        val candidate = schema.trim().ifBlank { "command_log" }
        require(candidate.matches(IdentifierPattern)) { "Postgres schema name must be a simple identifier: $candidate" }
        return candidate
    }

    private companion object {
        val IdentifierPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

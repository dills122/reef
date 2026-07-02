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
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.commandLog(
                        commands = names.commands,
                        workQueue = names.commandWorkQueue,
                        results = names.commandResults
                    )
                )
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
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.commandWorkQueue} (
                      command_id TEXT PRIMARY KEY REFERENCES ${names.commands}(command_id) ON DELETE CASCADE,
                      status TEXT NOT NULL,
                      attempt_count INTEGER NOT NULL DEFAULT 0,
                      last_error TEXT NOT NULL DEFAULT '',
                      leased_by TEXT NOT NULL DEFAULT '',
                      leased_until TIMESTAMPTZ,
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      CHECK (status IN ('RECEIVED', 'PROCESSING', 'COMPLETED', 'FAILED'))
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_command_log_work_queue_status_updated
                    ON ${names.commandWorkQueue}(status, updated_at, command_id)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${names.commandResults} (
                      command_id TEXT PRIMARY KEY REFERENCES ${names.commands}(command_id) ON DELETE CASCADE,
                      status TEXT NOT NULL DEFAULT 'COMPLETED',
                      attempt_count INTEGER NOT NULL DEFAULT 0,
                      last_error TEXT NOT NULL DEFAULT '',
                      response_status INTEGER NOT NULL,
                      response_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                      completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      CHECK (status IN ('COMPLETED', 'FAILED'))
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    ALTER TABLE ${names.commandResults}
                      ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'COMPLETED',
                      ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0,
                      ADD COLUMN IF NOT EXISTS last_error TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_command_log_results_status_completed
                    ON ${names.commandResults}(status, completed_at, command_id)
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    INSERT INTO ${names.commandWorkQueue}(command_id, status, attempt_count, last_error, updated_at)
                    SELECT command_id, status, attempt_count, last_error, created_at
                    FROM ${names.commands}
                    ON CONFLICT (command_id) DO NOTHING
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    INSERT INTO ${names.commandResults}(
                      command_id,
                      status,
                      attempt_count,
                      last_error,
                      response_status,
                      response_payload_json,
                      completed_at
                    )
                    SELECT command_id, status, attempt_count, last_error, response_status, response_payload_json, created_at
                    FROM ${names.commands}
                    WHERE status IN ('COMPLETED', 'FAILED') OR response_status > 0
                    ON CONFLICT (command_id) DO NOTHING
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    DELETE FROM ${names.commandWorkQueue}
                    WHERE status IN ('COMPLETED', 'FAILED')
                    """.trimIndent()
                )
            }
        }
    }

    override fun append(record: CommandLogRecord): CommandLogAppendResult {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                WITH inserted_command AS (
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
                  RETURNING command_id
                )
                INSERT INTO ${names.commandWorkQueue}(command_id, status, attempt_count, last_error)
                SELECT command_id, ?, 0, ''
                FROM inserted_command
                ON CONFLICT (command_id) DO NOTHING
                RETURNING command_id
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
                ps.setString(11, CommandLogStatus.RECEIVED.name)
                ps.setInt(12, 0)
                ps.setString(13, "")
                ps.setString(14, CommandLogStatus.RECEIVED.name)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        return CommandLogAppendResult(
                            appended = true,
                            record = record.copy(
                                status = CommandLogStatus.RECEIVED,
                                attemptCount = 0,
                                lastError = "",
                                responseStatus = 0,
                                responsePayloadJson = "{}"
                            )
                        )
                    }
                }
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
                ${selectComposedCommandLogRecord()}
                WHERE commands.command_id = ?
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
                ${selectComposedCommandLogRecord()}
                WHERE commands.client_id = ? AND commands.route = ? AND commands.idempotency_key = ?
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
        val terminalStatus = status == CommandLogStatus.COMPLETED || status == CommandLogStatus.FAILED
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                if (terminalStatus) {
                    """
                    ${selectComposedCommandLogRecord()}
                    WHERE results.status = ?
                    ORDER BY results.completed_at, commands.command_id
                    LIMIT ?
                    """.trimIndent()
                } else {
                    """
                    ${selectComposedCommandLogRecord()}
                    WHERE queue.status = ?
                    ORDER BY commands.received_at, commands.command_id
                    LIMIT ?
                    """.trimIndent()
                }
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
                  SELECT queue.command_id
                  FROM ${names.commandWorkQueue} queue
                  JOIN ${names.commands} commands ON commands.command_id = queue.command_id
                  WHERE queue.status = 'RECEIVED'
                  ORDER BY commands.received_at, commands.command_id
                  LIMIT ?
                  FOR UPDATE SKIP LOCKED
                ),
                updated AS (
                  UPDATE ${names.commandWorkQueue} queue
                  SET status = 'PROCESSING',
                      attempt_count = queue.attempt_count + 1,
                      last_error = '',
                      leased_by = '',
                      leased_until = NULL,
                      updated_at = NOW()
                  FROM claimed
                  WHERE queue.command_id = claimed.command_id
                  RETURNING queue.*
                )
                ${selectComposedCommandLogRecord(queueTable = "updated queue", queueJoin = "JOIN")}
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
                FROM ${names.commandWorkQueue}
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
                UPDATE ${names.commandWorkQueue}
                SET status = 'PROCESSING',
                    attempt_count = attempt_count + 1,
                    last_error = '',
                    leased_by = '',
                    leased_until = NULL,
                    updated_at = NOW()
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
                WITH deleted_queue AS (
                  DELETE FROM ${names.commandWorkQueue}
                  WHERE command_id = ?
                  RETURNING command_id, attempt_count
                )
                INSERT INTO ${names.commandResults}(
                  command_id,
                  status,
                  attempt_count,
                  last_error,
                  response_status,
                  response_payload_json,
                  completed_at
                )
                SELECT command_id, 'COMPLETED', attempt_count, '', ?, ?::jsonb, NOW()
                FROM deleted_queue
                ON CONFLICT (command_id) DO UPDATE SET
                  status = EXCLUDED.status,
                  attempt_count = EXCLUDED.attempt_count,
                  last_error = EXCLUDED.last_error,
                  response_status = EXCLUDED.response_status,
                  response_payload_json = EXCLUDED.response_payload_json,
                  completed_at = EXCLUDED.completed_at
                RETURNING command_id
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.setInt(2, responseStatus)
                ps.setString(3, responsePayloadJson)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                    }
                }
            }
        }
    }

    override fun markFailed(commandId: String, responseStatus: Int, errorMessage: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                WITH deleted_queue AS (
                  DELETE FROM ${names.commandWorkQueue}
                  WHERE command_id = ?
                  RETURNING command_id, attempt_count
                )
                INSERT INTO ${names.commandResults}(
                  command_id,
                  status,
                  attempt_count,
                  last_error,
                  response_status,
                  response_payload_json,
                  completed_at
                )
                SELECT command_id, 'FAILED', attempt_count, ?, ?, '{}'::jsonb, NOW()
                FROM deleted_queue
                ON CONFLICT (command_id) DO UPDATE SET
                  status = EXCLUDED.status,
                  attempt_count = EXCLUDED.attempt_count,
                  last_error = EXCLUDED.last_error,
                  response_status = EXCLUDED.response_status,
                  response_payload_json = EXCLUDED.response_payload_json,
                  completed_at = EXCLUDED.completed_at
                RETURNING command_id
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.setString(2, errorMessage)
                ps.setInt(3, responseStatus)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                    }
                }
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

    private fun selectComposedCommandLogRecord(
        queueTable: String = "${names.commandWorkQueue} queue",
        queueJoin: String = "LEFT JOIN"
    ): String {
        return """
            SELECT
              commands.command_id,
              commands.client_id,
              commands.route,
              commands.idempotency_key,
              commands.trace_id,
              commands.correlation_id,
              commands.actor_id,
              commands.command_type,
              commands.received_at,
              commands.payload_json,
              COALESCE(queue.status, results.status, commands.status) AS status,
              COALESCE(queue.attempt_count, results.attempt_count, commands.attempt_count) AS attempt_count,
              COALESCE(queue.last_error, results.last_error, commands.last_error) AS last_error,
              COALESCE(results.response_status, commands.response_status, 0) AS response_status,
              COALESCE(results.response_payload_json, commands.response_payload_json, '{}'::jsonb) AS response_payload_json
            FROM ${names.commands} commands
            $queueJoin $queueTable ON queue.command_id = commands.command_id
            LEFT JOIN ${names.commandResults} results ON results.command_id = commands.command_id
        """.trimIndent()
    }
}

data class PostgresCommandLogSqlNames(
    private val schema: String = "command_log"
) {
    val schemaName = schemaNameOrDefault(schema)
    val commands = "$schemaName.commands"
    val commandWorkQueue = "$schemaName.command_work_queue"
    val commandResults = "$schemaName.command_results"

    private fun schemaNameOrDefault(schema: String): String {
        val candidate = schema.trim().ifBlank { "command_log" }
        require(candidate.matches(IdentifierPattern)) { "Postgres schema name must be a simple identifier: $candidate" }
        return candidate
    }

    private companion object {
        val IdentifierPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

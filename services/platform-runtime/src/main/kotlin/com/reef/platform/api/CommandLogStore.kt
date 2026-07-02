package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
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
    val runId: String = "",
    val runKind: String = "",
    val scenarioId: String = "",
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

data class CommandLogAccountingSnapshot(
    val runId: String,
    val accepted: Long,
    val received: Long,
    val processing: Long,
    val completed: Long,
    val failed: Long,
    val active: Long,
    val terminal: Long,
    val accountingGap: Long,
    val staleProcessing: Long
)

interface CommandLogStore {
    fun append(record: CommandLogRecord): CommandLogAppendResult
    fun findByCommandId(commandId: String): CommandLogRecord?
    fun findByIdempotency(clientId: String, route: String, idempotencyKey: String): CommandLogRecord?
    fun findByStatus(status: CommandLogStatus, limit: Int): List<CommandLogRecord>
    fun claimReceived(limit: Int): List<CommandLogRecord>
    fun statusCounts(): Map<CommandLogStatus, Long>
    fun accountingSnapshot(runId: String = ""): CommandLogAccountingSnapshot
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

    override fun accountingSnapshot(runId: String): CommandLogAccountingSnapshot {
        val records = byCommandId.values.filter { runId.isBlank() || it.runId == runId }
        val counts = CommandLogStatus.values().associateWith { 0L }.toMutableMap()
        records.forEach { record ->
            counts[record.status] = counts.getValue(record.status) + 1L
        }
        val received = counts.getValue(CommandLogStatus.RECEIVED)
        val processing = counts.getValue(CommandLogStatus.PROCESSING)
        val completed = counts.getValue(CommandLogStatus.COMPLETED)
        val failed = counts.getValue(CommandLogStatus.FAILED)
        val active = received + processing
        val terminal = completed + failed
        val accepted = records.size.toLong()
        return CommandLogAccountingSnapshot(
            runId = runId,
            accepted = accepted,
            received = received,
            processing = processing,
            completed = completed,
            failed = failed,
            active = active,
            terminal = terminal,
            accountingGap = accepted - active - terminal,
            staleProcessing = 0L
        )
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

enum class PostgresCommandLogAppendMode {
    Inline,
    Function;

    companion object {
        fun from(value: String?): PostgresCommandLogAppendMode {
            return when (val normalized = value?.trim()?.lowercase()) {
                null, "", "inline" -> Inline
                "function", "stored-function", "stored-procedure" -> Function
                else -> throw IllegalArgumentException("Unsupported EXTERNAL_API_COMMAND_LOG_APPEND_MODE: $normalized")
            }
        }

        fun fromEnv(): PostgresCommandLogAppendMode {
            return from(RuntimeEnv.string("EXTERNAL_API_COMMAND_LOG_APPEND_MODE", "inline"))
        }
    }
}

class PostgresCommandLogStore(
    private val dataSource: DataSource,
    private val names: PostgresCommandLogSqlNames = PostgresCommandLogSqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv(),
    private val appendMode: PostgresCommandLogAppendMode = PostgresCommandLogAppendMode.fromEnv(),
    private val processingLeaseMs: Long = RuntimeEnv.long(
        "EXTERNAL_API_COMMAND_ASYNC_WORKER_LEASE_MS",
        60_000L,
        min = 1_000L
    )
) : CommandLogStore {
    init {
        dataSource.connection.use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.commandLog(
                        commands = names.commands,
                        workQueue = names.commandWorkQueue,
                        results = names.commandResults,
                        retentionPins = names.retentionPins,
                        appendFunction = names.commandAppendFunction
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
                      run_id TEXT NOT NULL DEFAULT '',
                      run_kind TEXT NOT NULL DEFAULT '',
                      scenario_id TEXT NOT NULL DEFAULT '',
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
                      ADD COLUMN IF NOT EXISTS response_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                      ADD COLUMN IF NOT EXISTS run_id TEXT NOT NULL DEFAULT '',
                      ADD COLUMN IF NOT EXISTS run_kind TEXT NOT NULL DEFAULT '',
                      ADD COLUMN IF NOT EXISTS scenario_id TEXT NOT NULL DEFAULT ''
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
                    CREATE TABLE IF NOT EXISTS ${names.retentionPins} (
                      pin_id TEXT PRIMARY KEY,
                      selector_type TEXT NOT NULL,
                      selector_value TEXT NOT NULL,
                      reason TEXT NOT NULL DEFAULT '',
                      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      UNIQUE (selector_type, selector_value),
                      CHECK (selector_type IN ('command_id', 'idempotency_prefix', 'trace_id', 'correlation_id', 'client_id', 'run_id'))
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_command_log_retention_pins_selector
                    ON ${names.retentionPins}(selector_type, selector_value)
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
                stmt.execute("DROP FUNCTION IF EXISTS ${names.commandAppendFunction}(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TIMESTAMPTZ, JSONB)")
                stmt.execute("DROP FUNCTION IF EXISTS ${names.commandAppendFunction}(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TIMESTAMPTZ, JSONB)")
                stmt.execute(commandAppendFunctionSql())
            }
        }
    }

    override fun append(record: CommandLogRecord): CommandLogAppendResult {
        return when (appendMode) {
            PostgresCommandLogAppendMode.Inline -> appendInline(record)
            PostgresCommandLogAppendMode.Function -> appendWithFunction(record)
        }
    }

    private fun appendInline(record: CommandLogRecord): CommandLogAppendResult {
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
                      run_id,
                      run_kind,
                      scenario_id,
                      received_at,
                      payload_json,
                      status,
                      attempt_count,
                      last_error
                    )
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::jsonb, ?, ?, ?)
                  ON CONFLICT DO NOTHING
                  RETURNING command_id
                )
                INSERT INTO ${names.commandWorkQueue}(command_id, status, attempt_count, last_error, updated_at)
                SELECT command_id, ?, 0, '', ?::timestamptz
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
                ps.setString(9, record.runId)
                ps.setString(10, record.runKind)
                ps.setString(11, record.scenarioId)
                ps.setString(12, record.receivedAt.toString())
                ps.setString(13, record.payloadJson)
                ps.setString(14, CommandLogStatus.RECEIVED.name)
                ps.setInt(15, 0)
                ps.setString(16, "")
                ps.setString(17, CommandLogStatus.RECEIVED.name)
                ps.setString(18, record.receivedAt.toString())
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

    private fun appendWithFunction(record: CommandLogRecord): CommandLogAppendResult {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT
                  out_appended AS appended,
                  out_command_id AS command_id,
                  out_client_id AS client_id,
                  out_route AS route,
                  out_idempotency_key AS idempotency_key,
                  out_trace_id AS trace_id,
                  out_correlation_id AS correlation_id,
                  out_actor_id AS actor_id,
                  out_command_type AS command_type,
                  out_run_id AS run_id,
                  out_run_kind AS run_kind,
                  out_scenario_id AS scenario_id,
                  out_received_at AS received_at,
                  out_payload_json AS payload_json,
                  out_status AS status,
                  out_attempt_count AS attempt_count,
                  out_last_error AS last_error,
                  out_response_status AS response_status,
                  out_response_payload_json AS response_payload_json
                FROM ${names.commandAppendFunction}(
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::jsonb
                )
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
                ps.setString(9, record.runId)
                ps.setString(10, record.runKind)
                ps.setString(11, record.scenarioId)
                ps.setString(12, record.receivedAt.toString())
                ps.setString(13, record.payloadJson)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        return CommandLogAppendResult(
                            appended = rs.getBoolean("appended"),
                            record = rs.toCommandLogRecord()
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
                  WHERE queue.status = 'RECEIVED'
                     OR (
                       queue.status = 'PROCESSING'
                       AND (
                         queue.leased_until < NOW()
                         OR (
                           queue.leased_until IS NULL
                           AND queue.updated_at < NOW() - (?::double precision * INTERVAL '1 millisecond')
                         )
                       )
                     )
                  ORDER BY queue.updated_at, queue.command_id
                  LIMIT ?
                  FOR UPDATE SKIP LOCKED
                ),
                updated AS (
                  UPDATE ${names.commandWorkQueue} queue
                  SET status = 'PROCESSING',
                      attempt_count = queue.attempt_count + 1,
                      last_error = '',
                      leased_by = 'async-command-worker',
                      leased_until = NOW() + (?::double precision * INTERVAL '1 millisecond'),
                      updated_at = NOW()
                  FROM claimed
                  WHERE queue.command_id = claimed.command_id
                  RETURNING queue.*
                )
                ${selectComposedCommandLogRecord(queueTable = "updated queue", queueJoin = "JOIN")}
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, processingLeaseMs)
                ps.setInt(2, limit)
                ps.setLong(3, processingLeaseMs)
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

    override fun accountingSnapshot(runId: String): CommandLogAccountingSnapshot {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                WITH command_count AS (
                  SELECT COUNT(*) AS accepted
                  FROM ${names.commands} commands
                  WHERE (? = '' OR commands.run_id = ?)
                ),
                queue_counts AS (
                  SELECT
                    COUNT(*) FILTER (WHERE queue.status = 'RECEIVED') AS received,
                    COUNT(*) FILTER (WHERE queue.status = 'PROCESSING') AS processing,
                    COUNT(*) FILTER (
                      WHERE queue.status = 'PROCESSING'
                        AND (
                          queue.leased_until < NOW()
                          OR (
                            queue.leased_until IS NULL
                            AND queue.updated_at < NOW() - (?::double precision * INTERVAL '1 millisecond')
                          )
                        )
                    ) AS stale_processing
                  FROM ${names.commandWorkQueue} queue
                  JOIN ${names.commands} commands ON commands.command_id = queue.command_id
                  WHERE (? = '' OR commands.run_id = ?)
                ),
                result_counts AS (
                  SELECT
                    COUNT(*) FILTER (WHERE results.status = 'COMPLETED') AS completed,
                    COUNT(*) FILTER (WHERE results.status = 'FAILED') AS failed
                  FROM ${names.commandResults} results
                  JOIN ${names.commands} commands ON commands.command_id = results.command_id
                  WHERE (? = '' OR commands.run_id = ?)
                )
                SELECT
                  command_count.accepted,
                  COALESCE(queue_counts.received, 0) AS received,
                  COALESCE(queue_counts.processing, 0) AS processing,
                  COALESCE(queue_counts.stale_processing, 0) AS stale_processing,
                  COALESCE(result_counts.completed, 0) AS completed,
                  COALESCE(result_counts.failed, 0) AS failed
                FROM command_count, queue_counts, result_counts
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, runId)
                ps.setString(2, runId)
                ps.setLong(3, processingLeaseMs)
                ps.setString(4, runId)
                ps.setString(5, runId)
                ps.setString(6, runId)
                ps.setString(7, runId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val accepted = rs.getLong("accepted")
                        val received = rs.getLong("received")
                        val processing = rs.getLong("processing")
                        val completed = rs.getLong("completed")
                        val failed = rs.getLong("failed")
                        val active = received + processing
                        val terminal = completed + failed
                        return CommandLogAccountingSnapshot(
                            runId = runId,
                            accepted = accepted,
                            received = received,
                            processing = processing,
                            completed = completed,
                            failed = failed,
                            active = active,
                            terminal = terminal,
                            accountingGap = accepted - active - terminal,
                            staleProcessing = rs.getLong("stale_processing")
                        )
                    }
                }
            }
        }
        return CommandLogAccountingSnapshot(
            runId = runId,
            accepted = 0L,
            received = 0L,
            processing = 0L,
            completed = 0L,
            failed = 0L,
            active = 0L,
            terminal = 0L,
            accountingGap = 0L,
            staleProcessing = 0L
        )
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
            runId = getString("run_id"),
            runKind = getString("run_kind"),
            scenarioId = getString("scenario_id"),
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
              commands.run_id,
              commands.run_kind,
              commands.scenario_id,
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

    private fun commandAppendFunctionSql(): String {
        return """
            CREATE OR REPLACE FUNCTION ${names.commandAppendFunction}(
              p_command_id TEXT,
              p_client_id TEXT,
              p_route TEXT,
              p_idempotency_key TEXT,
              p_trace_id TEXT,
              p_correlation_id TEXT,
              p_actor_id TEXT,
              p_command_type TEXT,
              p_run_id TEXT,
              p_run_kind TEXT,
              p_scenario_id TEXT,
              p_received_at TIMESTAMPTZ,
              p_payload_json JSONB
            )
            RETURNS TABLE (
              out_appended BOOLEAN,
              out_command_id TEXT,
              out_client_id TEXT,
              out_route TEXT,
              out_idempotency_key TEXT,
              out_trace_id TEXT,
              out_correlation_id TEXT,
              out_actor_id TEXT,
              out_command_type TEXT,
              out_run_id TEXT,
              out_run_kind TEXT,
              out_scenario_id TEXT,
              out_received_at TIMESTAMPTZ,
              out_payload_json JSONB,
              out_status TEXT,
              out_attempt_count INTEGER,
              out_last_error TEXT,
              out_response_status INTEGER,
              out_response_payload_json JSONB
            )
            LANGUAGE plpgsql
            AS $$
            DECLARE
              v_inserted INTEGER := 0;
              v_command_id TEXT;
            BEGIN
              INSERT INTO ${names.commands} AS command_row(
                command_id,
                client_id,
                route,
                idempotency_key,
                trace_id,
                correlation_id,
                actor_id,
                command_type,
                run_id,
                run_kind,
                scenario_id,
                received_at,
                payload_json,
                status,
                attempt_count,
                last_error
              )
              VALUES (
                p_command_id,
                p_client_id,
                p_route,
                p_idempotency_key,
                p_trace_id,
                p_correlation_id,
                p_actor_id,
                p_command_type,
                p_run_id,
                p_run_kind,
                p_scenario_id,
                p_received_at,
                p_payload_json,
                'RECEIVED',
                0,
                ''
              )
              ON CONFLICT DO NOTHING;

              GET DIAGNOSTICS v_inserted = ROW_COUNT;

              IF v_inserted = 1 THEN
                INSERT INTO ${names.commandWorkQueue}(command_id, status, attempt_count, last_error, updated_at)
                VALUES (p_command_id, 'RECEIVED', 0, '', p_received_at)
                ON CONFLICT (command_id) DO NOTHING;

                v_command_id := p_command_id;
              ELSE
                SELECT c.command_id
                INTO v_command_id
                FROM ${names.commands} c
                WHERE c.client_id = p_client_id
                  AND c.route = p_route
                  AND c.idempotency_key = p_idempotency_key;

                IF v_command_id IS NULL THEN
                  SELECT c.command_id
                  INTO v_command_id
                  FROM ${names.commands} c
                  WHERE c.command_id = p_command_id;
                END IF;
              END IF;

              RETURN QUERY
              SELECT
                v_inserted = 1 AS out_appended,
                c.command_id AS out_command_id,
                c.client_id AS out_client_id,
                c.route AS out_route,
                c.idempotency_key AS out_idempotency_key,
                c.trace_id AS out_trace_id,
                c.correlation_id AS out_correlation_id,
                c.actor_id AS out_actor_id,
                c.command_type AS out_command_type,
                c.run_id AS out_run_id,
                c.run_kind AS out_run_kind,
                c.scenario_id AS out_scenario_id,
                c.received_at AS out_received_at,
                c.payload_json AS out_payload_json,
                COALESCE(q.status, r.status, c.status) AS out_status,
                COALESCE(q.attempt_count, r.attempt_count, c.attempt_count) AS out_attempt_count,
                COALESCE(q.last_error, r.last_error, c.last_error) AS out_last_error,
                COALESCE(r.response_status, c.response_status, 0) AS out_response_status,
                COALESCE(r.response_payload_json, c.response_payload_json, '{}'::jsonb) AS out_response_payload_json
              FROM ${names.commands} c
              LEFT JOIN ${names.commandWorkQueue} q ON q.command_id = c.command_id
              LEFT JOIN ${names.commandResults} r ON r.command_id = c.command_id
              WHERE c.command_id = v_command_id;
            END;
            $$;
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
    val retentionPins = "$schemaName.retention_pins"
    val commandAppendFunction = "$schemaName.command_append"

    private fun schemaNameOrDefault(schema: String): String {
        val candidate = schema.trim().ifBlank { "command_log" }
        require(candidate.matches(IdentifierPattern)) { "Postgres schema name must be a simple identifier: $candidate" }
        return candidate
    }

    private companion object {
        val IdentifierPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

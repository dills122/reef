package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import java.sql.ResultSet
import javax.sql.DataSource

class PostgresCommandLogStore(
    private val dataSource: DataSource,
    private val names: PostgresCommandLogSqlNames = PostgresCommandLogSqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv(),
    private val appendMode: PostgresCommandLogAppendMode = PostgresCommandLogAppendMode.fromEnv(),
    private val payloadMode: PostgresCommandLogPayloadMode = PostgresCommandLogPayloadMode.fromEnv(),
    private val processingLeaseMs: Long = RuntimeEnv.long(
        "EXTERNAL_API_COMMAND_ASYNC_WORKER_LEASE_MS",
        60_000L,
        min = 1_000L
    )
) : CommandLogStore {
    init {
        PostgresCommandLogBootstrap.ensure(dataSource, names, bootstrapMode)
    }

    override fun append(record: CommandLogRecord): CommandLogAppendResult {
        return when (appendMode) {
            PostgresCommandLogAppendMode.Inline -> appendInline(record)
            PostgresCommandLogAppendMode.Function -> appendWithFunction(record)
        }
    }

    private fun appendInline(record: CommandLogRecord): CommandLogAppendResult {
        val commandPayloadJson = if (payloadMode == PostgresCommandLogPayloadMode.SideTable) "{}" else record.payloadJson
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
                      payload_json
                    )
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::jsonb)
                  ON CONFLICT DO NOTHING
                  RETURNING command_id
                ),
                inserted_payload AS (
                  INSERT INTO ${names.commandPayloads}(command_id, payload_json, created_at)
                  SELECT command_id, ?::jsonb, ?::timestamptz
                  FROM inserted_command
                  WHERE ?::boolean
                  ON CONFLICT (command_id) DO NOTHING
                  RETURNING command_id
                )
                INSERT INTO ${names.commandWorkQueue}(command_id, status, attempt_count, last_error, updated_at)
                SELECT command_id, 'RECEIVED', 0, '', ?::timestamptz
                FROM inserted_command
                ON CONFLICT (command_id) DO NOTHING
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
                ps.setString(13, commandPayloadJson)
                ps.setString(14, record.payloadJson)
                ps.setString(15, record.receivedAt.toString())
                ps.setBoolean(16, payloadMode == PostgresCommandLogPayloadMode.SideTable)
                ps.setString(17, record.receivedAt.toString())
                if (ps.executeUpdate() > 0) {
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
                    ${selectComposedCommandLogRecord(includeArchive = false)}
                    WHERE results.status = ?
                    ORDER BY results.completed_at, commands.command_id
                    LIMIT ?
                    """.trimIndent()
                } else {
                    """
                    ${selectComposedCommandLogRecord(includeArchive = false)}
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
                ${selectComposedCommandLogRecord(queueTable = "updated queue", queueJoin = "JOIN", includeArchive = false)}
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
                    COUNT(*) FILTER (WHERE terminal.status = 'COMPLETED') AS completed,
                    COUNT(*) FILTER (WHERE terminal.status = 'FAILED') AS failed
                  FROM (
                    SELECT results.command_id, results.status
                    FROM ${names.commandResults} results
                    JOIN ${names.commands} commands ON commands.command_id = results.command_id
                    WHERE (? = '' OR commands.run_id = ?)
                    UNION ALL
                    SELECT archived.command_id, archived.status
                    FROM ${names.commandResultsArchive} archived
                    JOIN ${names.commands} commands ON commands.command_id = archived.command_id
                    WHERE (? = '' OR commands.run_id = ?)
                      AND NOT EXISTS (
                        SELECT 1
                        FROM ${names.commandResults} live_results
                        WHERE live_results.command_id = archived.command_id
                      )
                  ) terminal
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
                ps.setString(8, runId)
                ps.setString(9, runId)
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

    fun archiveTerminalResults(cutoffCompletedAt: java.time.Instant, limit: Int): CommandResultsArchiveResult {
        if (limit <= 0) {
            return CommandResultsArchiveResult(candidateCount = 0, archivedCount = 0, deletedLiveCount = 0)
        }
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                WITH candidates AS (
                  SELECT
                    results.command_id,
                    results.status,
                    results.attempt_count,
                    results.last_error,
                    results.response_status,
                    results.response_payload_json,
                    results.completed_at
                  FROM ${names.commandResults} results
                  JOIN ${names.commands} commands ON commands.command_id = results.command_id
                  WHERE results.completed_at < ?::timestamptz
                    AND NOT EXISTS (
                      SELECT 1
                      FROM ${names.retentionPins} pins
                      WHERE (pins.selector_type = 'command_id' AND pins.selector_value = commands.command_id)
                         OR (pins.selector_type = 'idempotency_prefix' AND commands.idempotency_key LIKE pins.selector_value || '%')
                         OR (pins.selector_type = 'trace_id' AND pins.selector_value = commands.trace_id)
                         OR (pins.selector_type = 'correlation_id' AND pins.selector_value = commands.correlation_id)
                         OR (pins.selector_type = 'client_id' AND pins.selector_value = commands.client_id)
                         OR (pins.selector_type = 'run_id' AND pins.selector_value = commands.run_id)
                    )
                  ORDER BY results.completed_at, results.command_id
                  LIMIT ?
                  FOR UPDATE OF results SKIP LOCKED
                ),
                archived AS (
                  INSERT INTO ${names.commandResultsArchive}(
                    command_id,
                    status,
                    attempt_count,
                    last_error,
                    response_status,
                    response_payload_json,
                    completed_at,
                    archived_at
                  )
                  SELECT
                    command_id,
                    status,
                    attempt_count,
                    last_error,
                    response_status,
                    response_payload_json,
                    completed_at,
                    NOW()
                  FROM candidates
                  ON CONFLICT (completed_at, command_id) DO UPDATE SET
                    status = EXCLUDED.status,
                    attempt_count = EXCLUDED.attempt_count,
                    last_error = EXCLUDED.last_error,
                    response_status = EXCLUDED.response_status,
                    response_payload_json = EXCLUDED.response_payload_json,
                    archived_at = EXCLUDED.archived_at
                  RETURNING command_id, completed_at
                ),
                deleted_live AS (
                  DELETE FROM ${names.commandResults} results
                  USING archived
                  WHERE results.command_id = archived.command_id
                    AND results.completed_at = archived.completed_at
                  RETURNING results.command_id
                )
                SELECT
                  (SELECT COUNT(*) FROM candidates) AS candidate_count,
                  (SELECT COUNT(*) FROM archived) AS archived_count,
                  (SELECT COUNT(*) FROM deleted_live) AS deleted_live_count
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, cutoffCompletedAt.toString())
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        return CommandResultsArchiveResult(
                            candidateCount = rs.getLong("candidate_count"),
                            archivedCount = rs.getLong("archived_count"),
                            deletedLiveCount = rs.getLong("deleted_live_count")
                        )
                    }
                }
            }
        }
        return CommandResultsArchiveResult(candidateCount = 0, archivedCount = 0, deletedLiveCount = 0)
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
                ),
                terminal_source AS (
                  SELECT command_id, attempt_count
                  FROM deleted_queue
                  UNION ALL
                  SELECT commands.command_id,
                         COALESCE(results.attempt_count, commands.attempt_count)
                  FROM ${names.commands} commands
                  LEFT JOIN ${names.commandResults} results ON results.command_id = commands.command_id
                  WHERE commands.command_id = ?
                    AND NOT EXISTS (SELECT 1 FROM deleted_queue)
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
                FROM terminal_source
                LIMIT 1
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
                ps.setString(2, commandId)
                ps.setInt(3, responseStatus)
                ps.setString(4, responsePayloadJson)
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
                ),
                terminal_source AS (
                  SELECT command_id, attempt_count
                  FROM deleted_queue
                  UNION ALL
                  SELECT commands.command_id,
                         COALESCE(results.attempt_count, commands.attempt_count)
                  FROM ${names.commands} commands
                  LEFT JOIN ${names.commandResults} results ON results.command_id = commands.command_id
                  WHERE commands.command_id = ?
                    AND NOT EXISTS (SELECT 1 FROM deleted_queue)
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
                FROM terminal_source
                LIMIT 1
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
                ps.setString(2, commandId)
                ps.setString(3, errorMessage)
                ps.setInt(4, responseStatus)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                    }
                }
            }
        }
    }

    override fun markTerminal(updates: List<CommandTerminalUpdate>) {
        if (updates.isEmpty()) return
        dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    WITH deleted_queue AS (
                      DELETE FROM ${names.commandWorkQueue}
                      WHERE command_id = ?
                      RETURNING command_id, attempt_count
                    ),
                    terminal_source AS (
                      SELECT command_id, attempt_count
                      FROM deleted_queue
                      UNION ALL
                      SELECT commands.command_id,
                             COALESCE(results.attempt_count, commands.attempt_count)
                      FROM ${names.commands} commands
                      LEFT JOIN ${names.commandResults} results ON results.command_id = commands.command_id
                      WHERE commands.command_id = ?
                        AND NOT EXISTS (SELECT 1 FROM deleted_queue)
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
                    SELECT command_id, ?, attempt_count, ?, ?, ?::jsonb, NOW()
                    FROM terminal_source
                    LIMIT 1
                    ON CONFLICT (command_id) DO UPDATE SET
                      status = EXCLUDED.status,
                      attempt_count = EXCLUDED.attempt_count,
                      last_error = EXCLUDED.last_error,
                      response_status = EXCLUDED.response_status,
                      response_payload_json = EXCLUDED.response_payload_json,
                      completed_at = EXCLUDED.completed_at
                    """.trimIndent()
                ).use { ps ->
                    updates.forEach { update ->
                        require(update.status == CommandLogStatus.COMPLETED || update.status == CommandLogStatus.FAILED) {
                            "terminal update status must be COMPLETED or FAILED"
                        }
                        ps.setString(1, update.commandId)
                        ps.setString(2, update.commandId)
                        ps.setString(3, update.status.name)
                        ps.setString(4, if (update.status == CommandLogStatus.FAILED) update.errorMessage else "")
                        ps.setInt(5, update.responseStatus)
                        ps.setString(
                            6,
                            if (update.status == CommandLogStatus.FAILED) "{}" else update.responsePayloadJson
                        )
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
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
        queueJoin: String = "LEFT JOIN",
        includeArchive: Boolean = true
    ): String {
        val archivedStatus = if (includeArchive) "archived.status" else "NULL::TEXT"
        val archivedAttemptCount = if (includeArchive) "archived.attempt_count" else "NULL::INTEGER"
        val archivedLastError = if (includeArchive) "archived.last_error" else "NULL::TEXT"
        val archivedResponseStatus = if (includeArchive) "archived.response_status" else "NULL::INTEGER"
        val archivedResponsePayload = if (includeArchive) "archived.response_payload_json" else "NULL::JSONB"
        val archiveJoin = if (includeArchive) {
            """
            LEFT JOIN LATERAL (
              SELECT archive.*
              FROM ${names.commandResultsArchive} archive
              WHERE archive.command_id = commands.command_id
              ORDER BY archive.completed_at DESC
              LIMIT 1
            ) archived ON true
            """.trimIndent()
        } else {
            ""
        }
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
              COALESCE(payloads.payload_json, commands.payload_json) AS payload_json,
              COALESCE(queue.status, results.status, $archivedStatus, commands.status) AS status,
              COALESCE(queue.attempt_count, results.attempt_count, $archivedAttemptCount, commands.attempt_count) AS attempt_count,
              COALESCE(queue.last_error, results.last_error, $archivedLastError, commands.last_error) AS last_error,
              COALESCE(results.response_status, $archivedResponseStatus, commands.response_status, 0) AS response_status,
              COALESCE(
                results.response_payload_json,
                $archivedResponsePayload,
                commands.response_payload_json,
                '{}'::jsonb
              ) AS response_payload_json
            FROM ${names.commands} commands
            $queueJoin $queueTable ON queue.command_id = commands.command_id
            LEFT JOIN ${names.commandPayloads} payloads ON payloads.command_id = commands.command_id
            LEFT JOIN ${names.commandResults} results ON results.command_id = commands.command_id
            $archiveJoin
        """.trimIndent()
    }

}

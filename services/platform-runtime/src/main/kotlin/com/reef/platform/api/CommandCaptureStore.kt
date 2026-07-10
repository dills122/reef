package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface CommandCaptureStore {
    fun reserveReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ): CommandCaptureReceipt {
        captureReceived(clientId, route, idempotencyKey, correlationId, requestPayload)
        return CommandCaptureReceipt.Accepted
    }

    fun captureReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    )

    fun markProcessing(
        clientId: String,
        route: String,
        idempotencyKey: String
    ) {
    }

    fun markCompleted(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        responsePayload: String
    )

    fun markFailed(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        errorClass: String,
        errorMessage: String
    )
}

data class CommandCaptureReceipt(
    val accepted: Boolean,
    val existingCommandStatus: CommandStatusView? = null
) {
    companion object {
        val Accepted = CommandCaptureReceipt(accepted = true)
    }
}

interface CapturedCommandQueue {
    fun claimReceivedCommands(limit: Int): List<CommandLogRecord>
    fun statusCounts(): Map<CommandLogStatus, Long>
    fun accountingSnapshot(runId: String = ""): CommandLogAccountingSnapshot
    fun markCommandProcessing(commandId: String)
    fun markCommandCompleted(commandId: String, responseStatus: Int, responsePayloadJson: String)
    fun markCommandFailed(commandId: String, responseStatus: Int, errorMessage: String)
    fun markCommandTerminal(updates: List<CommandTerminalUpdate>)
}

class NoopCommandCaptureStore : CommandCaptureStore {
    override fun captureReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ) {
    }

    override fun markCompleted(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        responsePayload: String
    ) {
    }

    override fun markFailed(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        errorClass: String,
        errorMessage: String
    ) {
    }
}

class InMemoryCommandCaptureStore : CommandCaptureStore, CommandStatusLookup {
    private data class CapturedCommand(
        val commandId: String,
        val clientId: String,
        val route: String,
        val idempotencyKey: String,
        val correlationId: String,
        val requestPayload: String,
        val receivedAtEpochSeconds: Long,
        val status: String,
        val responseStatus: Int,
        val responsePayload: String,
        val errorClass: String,
        val errorMessage: String,
        val updatedAtEpochSeconds: Long
    )

    private val records = java.util.concurrent.ConcurrentHashMap<String, CapturedCommand>()

    override fun reserveReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ): CommandCaptureReceipt {
        val now = Instant.now().epochSecond
        val key = key(clientId, route, idempotencyKey)
        val commandId = commandId(clientId, route, idempotencyKey, requestPayload)
        var accepted = false
        records.compute(key) { _, existing ->
            if (existing == null) {
                accepted = true
                CapturedCommand(
                    commandId = commandId,
                    clientId = clientId,
                    route = route,
                    idempotencyKey = idempotencyKey,
                    correlationId = correlationId,
                    requestPayload = requestPayload,
                    receivedAtEpochSeconds = now,
                    status = "RECEIVED",
                    responseStatus = 0,
                    responsePayload = "",
                    errorClass = "",
                    errorMessage = "",
                    updatedAtEpochSeconds = now
                )
            } else {
                existing.copy(updatedAtEpochSeconds = now)
            }
        }
        return if (accepted) CommandCaptureReceipt.Accepted else CommandCaptureReceipt(accepted = false)
    }

    override fun captureReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ) {
        reserveReceived(clientId, route, idempotencyKey, correlationId, requestPayload)
    }

    override fun markCompleted(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        responsePayload: String
    ) {
        val now = Instant.now().epochSecond
        val key = key(clientId, route, idempotencyKey)
        records.computeIfPresent(key) { _, existing ->
            existing.copy(
                status = "COMPLETED",
                responseStatus = responseStatus,
                responsePayload = responsePayload,
                errorClass = "",
                errorMessage = "",
                updatedAtEpochSeconds = now
            )
        }
    }

    override fun markFailed(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        errorClass: String,
        errorMessage: String
    ) {
        val now = Instant.now().epochSecond
        val key = key(clientId, route, idempotencyKey)
        records.computeIfPresent(key) { _, existing ->
            existing.copy(
                status = "FAILED",
                responseStatus = responseStatus,
                errorClass = errorClass,
                errorMessage = errorMessage,
                updatedAtEpochSeconds = now
            )
        }
    }

    override fun findCommandStatus(commandId: String): CommandStatusView? {
        return records.values.firstOrNull { it.commandId == commandId }?.toStatusView()
    }

    override fun findCommandStatus(clientId: String, route: String, idempotencyKey: String): CommandStatusView? {
        return records[key(clientId, route, idempotencyKey)]?.toStatusView()
    }

    private fun key(clientId: String, route: String, idempotencyKey: String): String {
        return "$clientId|$route|$idempotencyKey"
    }

    private fun commandId(clientId: String, route: String, idempotencyKey: String, requestPayload: String): String {
        val parsedCommandId = try {
            JsonCodec.parseObject(requestPayload).string("commandId")
        } catch (_: Exception) {
            ""
        }
        if (parsedCommandId.isNotBlank()) return parsedCommandId
        val source = "$clientId|$route|$idempotencyKey"
        return "generated-${UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8))}"
    }

    private fun CapturedCommand.toStatusView(): CommandStatusView {
        return CommandStatusView(
            commandId = commandId,
            clientId = clientId,
            route = route,
            idempotencyKey = idempotencyKey,
            status = CommandLogStatus.valueOf(status),
            processingMode = CommandProcessingMode.SyncResult,
            responseStatus = responseStatus,
            responsePayloadJson = responsePayload,
            lastError = errorMessage,
            participantId = commandStatusParticipantId(requestPayload),
            source = "command_capture"
        )
    }
}

class CommandLogCommandCaptureStore(
    private val delegate: CommandCaptureStore,
    private val commandLogStore: CommandLogStore,
    private val commandProcessingMode: CommandProcessingMode = CommandProcessingMode.SyncResult,
    private val clock: () -> Instant = { Instant.now() }
) : CommandCaptureStore, CommandStatusLookup, CapturedCommandQueue {
    override fun reserveReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ): CommandCaptureReceipt {
        val record = HotPathMetrics.time("api.commandCapture.buildRecord") {
            newCommandLogRecord(clientId, route, idempotencyKey, correlationId, requestPayload)
        }
        val appendResult = HotPathMetrics.time("api.commandLog.append") {
            commandLogStore.append(record)
        }
        if (!appendResult.appended) {
            return CommandCaptureReceipt(
                accepted = false,
                existingCommandStatus = appendResult.record.toStatusView(commandProcessingMode)
            )
        }
        delegate.captureReceived(clientId, route, idempotencyKey, correlationId, requestPayload)
        return CommandCaptureReceipt.Accepted
    }

    override fun captureReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ) {
        reserveReceived(clientId, route, idempotencyKey, correlationId, requestPayload)
    }

    override fun markProcessing(clientId: String, route: String, idempotencyKey: String) {
        commandLogStore.findByIdempotency(clientId, route, idempotencyKey)?.let { record ->
            commandLogStore.markProcessing(record.commandId)
        }
        delegate.markProcessing(clientId, route, idempotencyKey)
    }

    override fun markCompleted(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        responsePayload: String
    ) {
        commandLogStore.findByIdempotency(clientId, route, idempotencyKey)?.let { record ->
            commandLogStore.markCompleted(record.commandId, responseStatus, payloadJson(responsePayload))
        }
        delegate.markCompleted(clientId, route, idempotencyKey, responseStatus, responsePayload)
    }

    override fun markFailed(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        errorClass: String,
        errorMessage: String
    ) {
        commandLogStore.findByIdempotency(clientId, route, idempotencyKey)?.let { record ->
            commandLogStore.markFailed(record.commandId, responseStatus, errorMessage)
        }
        delegate.markFailed(clientId, route, idempotencyKey, responseStatus, errorClass, errorMessage)
    }

    override fun findCommandStatus(commandId: String): CommandStatusView? {
        return commandLogStore.findByCommandId(commandId)?.toStatusView(commandProcessingMode)
    }

    override fun findCommandStatus(clientId: String, route: String, idempotencyKey: String): CommandStatusView? {
        return commandLogStore.findByIdempotency(clientId, route, idempotencyKey)?.toStatusView(commandProcessingMode)
    }

    override fun claimReceivedCommands(limit: Int): List<CommandLogRecord> {
        return commandLogStore.claimReceived(limit)
    }

    override fun statusCounts(): Map<CommandLogStatus, Long> {
        return commandLogStore.statusCounts()
    }

    override fun accountingSnapshot(runId: String): CommandLogAccountingSnapshot {
        return commandLogStore.accountingSnapshot(runId)
    }

    override fun markCommandProcessing(commandId: String) {
        commandLogStore.markProcessing(commandId)
    }

    override fun markCommandCompleted(commandId: String, responseStatus: Int, responsePayloadJson: String) {
        commandLogStore.markCompleted(commandId, responseStatus, responsePayloadJson)
    }

    override fun markCommandFailed(commandId: String, responseStatus: Int, errorMessage: String) {
        commandLogStore.markFailed(commandId, responseStatus, errorMessage)
    }

    override fun markCommandTerminal(updates: List<CommandTerminalUpdate>) {
        commandLogStore.markTerminal(updates)
    }

    private fun commandId(clientId: String, route: String, idempotencyKey: String, payload: JsonDocument?): String {
        val parsedCommandId = payload?.string("commandId").orEmpty()
        if (parsedCommandId.isNotBlank()) return parsedCommandId
        val source = "$clientId|$route|$idempotencyKey"
        return "generated-${UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8))}"
    }

    private fun newCommandLogRecord(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ): CommandLogRecord {
        val parsedPayload = parsePayloadOrNull(requestPayload)
        return CommandLogRecord(
            commandId = commandId(clientId, route, idempotencyKey, parsedPayload),
            clientId = clientId,
            route = route,
            idempotencyKey = idempotencyKey,
            traceId = parsedPayload?.string("traceId").orEmpty().ifBlank { correlationId },
            correlationId = parsedPayload?.string("correlationId").orEmpty().ifBlank { correlationId },
            actorId = parsedPayload?.string("actorId").orEmpty().ifBlank { clientId },
            commandType = commandType(route),
            runId = parsedPayload?.string("runId").orEmpty()
                .ifBlank { parsedPayload?.string("scenarioRunId").orEmpty() },
            runKind = parsedPayload?.string("runKind").orEmpty(),
            scenarioId = parsedPayload?.string("scenarioId").orEmpty(),
            receivedAt = clock(),
            payloadJson = payloadJson(requestPayload, parsedPayload)
        )
    }

    private fun parsePayloadOrNull(requestPayload: String): JsonDocument? {
        return try {
            JsonCodec.parseObject(requestPayload)
        } catch (_: Exception) {
            null
        }
    }

    private fun payloadJson(requestPayload: String, parsedPayload: JsonDocument?): String {
        if (parsedPayload != null) return requestPayload
        return JsonCodec.writeObject("rawPayload" to requestPayload)
    }

    private fun payloadJson(requestPayload: String): String {
        return payloadJson(requestPayload, parsePayloadOrNull(requestPayload))
    }

    private fun commandType(route: String): String {
        return when {
            route.endsWith("/orders/submit") -> "SubmitOrder"
            route.endsWith("/orders/cancel") -> "CancelOrder"
            route.endsWith("/orders/modify") -> "ModifyOrder"
            else -> "UnknownCommand"
        }
    }
}

class PostgresCommandCaptureStore(
    private val dataSource: DataSource,
    private val names: PostgresBoundarySqlNames = PostgresBoundarySqlNames(),
    private val bootstrapMode: PostgresBootstrapMode = PostgresBootstrapMode.fromEnv()
) : CommandCaptureStore, CommandStatusLookup {
    init {
        connection().use { conn ->
            if (bootstrapMode == PostgresBootstrapMode.Validate) {
                PostgresSchemaValidator.validate(
                    conn,
                    PostgresSchemaRequirements.boundaryCommandCapture(names.commandCaptures)
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
                    CREATE TABLE IF NOT EXISTS ${names.commandCaptures} (
                      client_id TEXT NOT NULL,
                      route TEXT NOT NULL,
                      idempotency_key TEXT NOT NULL,
                      correlation_id TEXT NOT NULL,
                      request_payload TEXT NOT NULL,
                      status TEXT NOT NULL,
                      response_status INTEGER NOT NULL DEFAULT 0,
                      response_payload TEXT NOT NULL DEFAULT '',
                      error_class TEXT NOT NULL DEFAULT '',
                      error_message TEXT NOT NULL DEFAULT '',
                      first_received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      last_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      PRIMARY KEY (client_id, route, idempotency_key)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS ${names.commandCapturesStatusUpdatedIndex}
                    ON ${names.commandCaptures}(status, last_updated_at DESC)
                    """.trimIndent()
                )
            }
        }
    }

    override fun reserveReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ): CommandCaptureReceipt {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO ${names.commandCaptures}(
                  client_id,
                  route,
                  idempotency_key,
                  correlation_id,
                  request_payload,
                  status
                )
                VALUES (?, ?, ?, ?, ?, 'RECEIVED')
                ON CONFLICT (client_id, route, idempotency_key) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, clientId)
                ps.setString(2, route)
                ps.setString(3, idempotencyKey)
                ps.setString(4, correlationId)
                ps.setString(5, requestPayload)
                if (ps.executeUpdate() == 1) {
                    return CommandCaptureReceipt.Accepted
                }
            }
            conn.prepareStatement(
                """
                UPDATE ${names.commandCaptures}
                SET last_updated_at = NOW()
                WHERE client_id = ? AND route = ? AND idempotency_key = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, clientId)
                ps.setString(2, route)
                ps.setString(3, idempotencyKey)
                ps.executeUpdate()
            }
        }
        return CommandCaptureReceipt(accepted = false)
    }

    override fun captureReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ) {
        reserveReceived(clientId, route, idempotencyKey, correlationId, requestPayload)
    }

    override fun markCompleted(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        responsePayload: String
    ) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE ${names.commandCaptures}
                SET status = 'COMPLETED',
                    response_status = ?,
                    response_payload = ?,
                    error_class = '',
                    error_message = '',
                    last_updated_at = NOW()
                WHERE client_id = ? AND route = ? AND idempotency_key = ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, responseStatus)
                ps.setString(2, responsePayload)
                ps.setString(3, clientId)
                ps.setString(4, route)
                ps.setString(5, idempotencyKey)
                ps.executeUpdate()
            }
        }
    }

    override fun markFailed(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        errorClass: String,
        errorMessage: String
    ) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE ${names.commandCaptures}
                SET status = 'FAILED',
                    response_status = ?,
                    error_class = ?,
                    error_message = ?,
                    last_updated_at = NOW()
                WHERE client_id = ? AND route = ? AND idempotency_key = ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, responseStatus)
                ps.setString(2, errorClass)
                ps.setString(3, errorMessage)
                ps.setString(4, clientId)
                ps.setString(5, route)
                ps.setString(6, idempotencyKey)
                ps.executeUpdate()
            }
        }
    }

    override fun findCommandStatus(commandId: String): CommandStatusView? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT client_id,
                       route,
                       idempotency_key,
                       request_payload,
                       status,
                       response_status,
                       response_payload,
                       error_message
                FROM ${names.commandCaptures}
                WHERE request_payload::jsonb ->> 'commandId' = ?
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return postgresStatusView(commandId, rs)
                }
            }
        }
    }

    override fun findCommandStatus(clientId: String, route: String, idempotencyKey: String): CommandStatusView? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT client_id,
                       route,
                       idempotency_key,
                       request_payload,
                       status,
                       response_status,
                       response_payload,
                       error_message
                FROM ${names.commandCaptures}
                WHERE client_id = ? AND route = ? AND idempotency_key = ?
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, clientId)
                ps.setString(2, route)
                ps.setString(3, idempotencyKey)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return postgresStatusView(commandId(clientId, route, idempotencyKey, rs.getString("request_payload")), rs)
                }
            }
        }
    }

    private fun postgresStatusView(commandId: String, rs: java.sql.ResultSet): CommandStatusView {
        val requestPayload = rs.getString("request_payload")
        return CommandStatusView(
            commandId = commandId,
            clientId = rs.getString("client_id"),
            route = rs.getString("route"),
            idempotencyKey = rs.getString("idempotency_key"),
            status = CommandLogStatus.valueOf(rs.getString("status")),
            processingMode = CommandProcessingMode.SyncResult,
            responseStatus = rs.getInt("response_status"),
            responsePayloadJson = rs.getString("response_payload"),
            lastError = rs.getString("error_message"),
            participantId = commandStatusParticipantId(requestPayload),
            source = "command_capture"
        )
    }

    private fun commandId(clientId: String, route: String, idempotencyKey: String, requestPayload: String): String {
        val parsedCommandId = try {
            JsonCodec.parseObject(requestPayload).string("commandId")
        } catch (_: Exception) {
            ""
        }
        if (parsedCommandId.isNotBlank()) return parsedCommandId
        val source = "$clientId|$route|$idempotencyKey"
        return "generated-${UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8))}"
    }

    private fun connection() = dataSource.connection
}

fun defaultCommandCaptureStore(
    commandProcessingMode: CommandProcessingMode = CommandProcessingMode.fromEnv()
): CommandCaptureStore {
    return defaultCommandCaptureStore(commandProcessingMode) { key -> System.getenv(key) }
}

internal fun defaultCommandCaptureStore(
    commandProcessingMode: CommandProcessingMode = CommandProcessingMode.SyncResult,
    lookup: (String) -> String?
): CommandCaptureStore {
    val mode = (lookup("EXTERNAL_API_COMMAND_CAPTURE_MODE") ?: "postgres").trim().lowercase()
    val captureStore = when (mode) {
        "disabled", "off", "none" -> NoopCommandCaptureStore()
        "inmemory" -> InMemoryCommandCaptureStore()
        else -> {
            val jdbcUrl = lookup("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
            val dbUser = lookup("RUNTIME_DB_USER") ?: "reef"
            val dbPassword = lookup("RUNTIME_DB_PASSWORD") ?: "reef"
            PostgresCommandCaptureStore(RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "command-capture"))
        }
    }
    return when (val commandLogMode = (lookup("EXTERNAL_API_COMMAND_LOG_MODE") ?: "disabled").trim().lowercase()) {
        "disabled", "off", "none" -> captureStore
        "inmemory" -> CommandLogCommandCaptureStore(
            delegate = captureStore,
            commandLogStore = InMemoryCommandLogStore(),
            commandProcessingMode = commandProcessingMode
        )
        "postgres" -> {
            val jdbcUrl = lookup("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
            val dbUser = lookup("RUNTIME_DB_USER") ?: "reef"
            val dbPassword = lookup("RUNTIME_DB_PASSWORD") ?: "reef"
            CommandLogCommandCaptureStore(
                delegate = captureStore,
                commandLogStore = PostgresCommandLogStore(
                    RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword, "command-log")
                ),
                commandProcessingMode = commandProcessingMode
            )
        }
        else -> throw IllegalArgumentException("Unsupported EXTERNAL_API_COMMAND_LOG_MODE: $commandLogMode")
    }
}

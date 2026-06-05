package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.PostgresBootstrapMode
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirements
import com.reef.platform.infrastructure.persistence.PostgresSchemaValidator
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface CommandCaptureStore {
    fun captureReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    )

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

class InMemoryCommandCaptureStore : CommandCaptureStore {
    private data class CapturedCommand(
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

    override fun captureReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ) {
        val now = Instant.now().epochSecond
        val key = key(clientId, route, idempotencyKey)
        records.compute(key) { _, existing ->
            if (existing == null) {
                CapturedCommand(
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

    private fun key(clientId: String, route: String, idempotencyKey: String): String {
        return "$clientId|$route|$idempotencyKey"
    }
}

class CommandLogCommandCaptureStore(
    private val delegate: CommandCaptureStore,
    private val commandLogStore: CommandLogStore,
    private val clock: () -> Instant = { Instant.now() }
) : CommandCaptureStore {
    override fun captureReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ) {
        commandLogStore.append(
            CommandLogRecord(
                commandId = commandId(clientId, route, idempotencyKey, requestPayload),
                clientId = clientId,
                route = route,
                idempotencyKey = idempotencyKey,
                traceId = JsonCodec.fieldAsString(requestPayload, "traceId").ifBlank { correlationId },
                correlationId = JsonCodec.fieldAsString(requestPayload, "correlationId").ifBlank { correlationId },
                actorId = JsonCodec.fieldAsString(requestPayload, "actorId").ifBlank { clientId },
                commandType = commandType(route),
                receivedAt = clock(),
                payloadJson = payloadJson(requestPayload)
            )
        )
        delegate.captureReceived(clientId, route, idempotencyKey, correlationId, requestPayload)
    }

    override fun markCompleted(
        clientId: String,
        route: String,
        idempotencyKey: String,
        responseStatus: Int,
        responsePayload: String
    ) {
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
        delegate.markFailed(clientId, route, idempotencyKey, responseStatus, errorClass, errorMessage)
    }

    private fun commandId(clientId: String, route: String, idempotencyKey: String, requestPayload: String): String {
        val parsedCommandId = JsonCodec.fieldAsString(requestPayload, "commandId")
        if (parsedCommandId.isNotBlank()) return parsedCommandId
        val source = "$clientId|$route|$idempotencyKey"
        return "generated-${UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8))}"
    }

    private fun payloadJson(requestPayload: String): String {
        return try {
            JsonCodec.parseObject(requestPayload)
            requestPayload
        } catch (_: Exception) {
            JsonCodec.writeObject("rawPayload" to requestPayload)
        }
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
) : CommandCaptureStore {
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

    override fun captureReceived(
        clientId: String,
        route: String,
        idempotencyKey: String,
        correlationId: String,
        requestPayload: String
    ) {
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
                ON CONFLICT (client_id, route, idempotency_key) DO UPDATE SET
                  last_updated_at = NOW()
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, clientId)
                ps.setString(2, route)
                ps.setString(3, idempotencyKey)
                ps.setString(4, correlationId)
                ps.setString(5, requestPayload)
                ps.executeUpdate()
            }
        }
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

    private fun connection() = dataSource.connection
}

fun defaultCommandCaptureStore(): CommandCaptureStore {
    return defaultCommandCaptureStore { key -> System.getenv(key) }
}

internal fun defaultCommandCaptureStore(lookup: (String) -> String?): CommandCaptureStore {
    val mode = (lookup("EXTERNAL_API_COMMAND_CAPTURE_MODE") ?: "postgres").trim().lowercase()
    val captureStore = when (mode) {
        "disabled", "off", "none" -> NoopCommandCaptureStore()
        "inmemory" -> InMemoryCommandCaptureStore()
        else -> {
            val jdbcUrl = lookup("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
            val dbUser = lookup("RUNTIME_DB_USER") ?: "reef"
            val dbPassword = lookup("RUNTIME_DB_PASSWORD") ?: "reef"
            PostgresCommandCaptureStore(RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword))
        }
    }
    return when (val commandLogMode = (lookup("EXTERNAL_API_COMMAND_LOG_MODE") ?: "disabled").trim().lowercase()) {
        "disabled", "off", "none" -> captureStore
        "inmemory" -> CommandLogCommandCaptureStore(captureStore, InMemoryCommandLogStore())
        "postgres" -> {
            val jdbcUrl = lookup("RUNTIME_DB_URL") ?: "jdbc:postgresql://localhost:5432/reef"
            val dbUser = lookup("RUNTIME_DB_USER") ?: "reef"
            val dbPassword = lookup("RUNTIME_DB_PASSWORD") ?: "reef"
            CommandLogCommandCaptureStore(
                captureStore,
                PostgresCommandLogStore(RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword))
            )
        }
        else -> throw IllegalArgumentException("Unsupported EXTERNAL_API_COMMAND_LOG_MODE: $commandLogMode")
    }
}

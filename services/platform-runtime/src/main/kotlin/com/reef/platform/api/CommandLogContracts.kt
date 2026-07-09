package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import java.time.Instant

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

data class CommandTerminalUpdate(
    val commandId: String,
    val status: CommandLogStatus,
    val responseStatus: Int,
    val responsePayloadJson: String,
    val errorMessage: String = ""
)

data class CommandResultsArchiveResult(
    val candidateCount: Long,
    val archivedCount: Long,
    val deletedLiveCount: Long
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
    fun markTerminal(updates: List<CommandTerminalUpdate>)
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

enum class PostgresCommandLogPayloadMode {
    Inline,
    SideTable;

    companion object {
        fun from(value: String?): PostgresCommandLogPayloadMode {
            return when (val normalized = value?.trim()?.lowercase()) {
                null, "", "side-table", "sidetable", "split" -> SideTable
                "inline", "commands" -> Inline
                else -> throw IllegalArgumentException("Unsupported EXTERNAL_API_COMMAND_LOG_PAYLOAD_MODE: $normalized")
            }
        }

        fun fromEnv(): PostgresCommandLogPayloadMode {
            return from(RuntimeEnv.string("EXTERNAL_API_COMMAND_LOG_PAYLOAD_MODE", "side-table"))
        }
    }
}

data class PostgresCommandLogSqlNames(
    private val schema: String = "command_log"
) {
    val schemaName = schemaNameOrDefault(schema)
    val commands = "$schemaName.commands"
    val commandPayloads = "$schemaName.command_payloads"
    val commandWorkQueue = "$schemaName.command_work_queue"
    val commandResults = "$schemaName.command_results"
    val commandResultsArchive = "$schemaName.command_results_archive"
    val commandResultsArchiveDefault = "$schemaName.command_results_archive_default"
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

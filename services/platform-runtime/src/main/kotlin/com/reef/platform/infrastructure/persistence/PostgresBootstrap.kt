package com.reef.platform.infrastructure.persistence

import com.reef.platform.infrastructure.config.RuntimeEnv
import java.sql.Connection

enum class PostgresBootstrapMode {
    Compat,
    Validate;

    companion object {
        fun from(value: String?): PostgresBootstrapMode {
            return when (val normalized = value?.trim()?.lowercase()) {
                null, "" -> Compat
                "compat" -> Compat
                "validate" -> Validate
                else -> throw IllegalArgumentException("Unsupported RUNTIME_DB_BOOTSTRAP_MODE: $normalized")
            }
        }

        fun fromEnv(): PostgresBootstrapMode {
            return from(RuntimeEnv.string("RUNTIME_DB_BOOTSTRAP_MODE", "compat"))
        }
    }
}

data class PostgresSchemaObject(val schema: String, val name: String) {
    val qualifiedName = "$schema.$name"

    companion object {
        fun parse(qualifiedName: String): PostgresSchemaObject {
            val parts = qualifiedName.split(".")
            require(parts.size == 2 && parts.all { it.isNotBlank() }) {
                "Expected schema-qualified Postgres object name: $qualifiedName"
            }
            return PostgresSchemaObject(parts[0], parts[1])
        }
    }
}

data class PostgresSchemaColumn(
    val table: PostgresSchemaObject,
    val name: String,
    val expectedDataType: String? = null
) {
    val qualifiedName = "${table.qualifiedName}.$name"
}

data class PostgresSchemaRequirement(
    val tables: List<PostgresSchemaObject>,
    val functions: List<PostgresSchemaObject> = emptyList(),
    val columns: List<PostgresSchemaColumn> = emptyList()
)

object PostgresSchemaRequirements {
    fun runtime(names: PostgresRuntimeSqlNames): PostgresSchemaRequirement {
        val runtimeEvents = PostgresSchemaObject.parse(names.runtimeEvents)
        return PostgresSchemaRequirement(
            tables = listOf(
                names.referenceInstruments,
                names.referenceParticipants,
                names.referenceAccounts,
                names.orders,
                names.executions,
                names.trades,
                names.runtimeEvents,
                names.runtimeTraceSequences,
                names.submitResults,
                names.authRoles,
                names.authActorRoles
            ).map(PostgresSchemaObject::parse),
            functions = listOf(
                names.validateReferenceDataFunction,
                names.persistSubmitOutcomeFunction
            ).map(PostgresSchemaObject::parse),
            columns = listOf(
                PostgresSchemaColumn(runtimeEvents, "event_id", "text"),
                PostgresSchemaColumn(runtimeEvents, "occurred_at", "text"),
                PostgresSchemaColumn(runtimeEvents, "actor_id", "text"),
                PostgresSchemaColumn(runtimeEvents, "payload_json", "jsonb"),
                PostgresSchemaColumn(runtimeEvents, "sequence_number", "bigint")
            )
        )
    }

    fun boundaryIdempotency(idempotencyRecords: String): PostgresSchemaRequirement {
        return PostgresSchemaRequirement(tables = listOf(PostgresSchemaObject.parse(idempotencyRecords)))
    }

    fun boundaryCommandCapture(commandCaptures: String): PostgresSchemaRequirement {
        val table = PostgresSchemaObject.parse(commandCaptures)
        return PostgresSchemaRequirement(
            tables = listOf(table),
            columns = listOf(
                "client_id",
                "route",
                "idempotency_key",
                "correlation_id",
                "request_payload",
                "status",
                "response_status",
                "response_payload",
                "error_class",
                "error_message",
                "first_received_at",
                "last_updated_at"
            ).map { column -> PostgresSchemaColumn(table, column) }
        )
    }

    fun commandLog(
        commands: String,
        workQueue: String = "command_log.command_work_queue",
        results: String = "command_log.command_results"
    ): PostgresSchemaRequirement {
        val commandTable = PostgresSchemaObject.parse(commands)
        val queueTable = PostgresSchemaObject.parse(workQueue)
        val resultTable = PostgresSchemaObject.parse(results)
        return PostgresSchemaRequirement(
            tables = listOf(commandTable, queueTable, resultTable),
            columns = listOf(
                listOf(
                    "command_id",
                    "client_id",
                    "route",
                    "idempotency_key",
                    "trace_id",
                    "correlation_id",
                    "actor_id",
                    "command_type",
                    "received_at",
                    "payload_json",
                    "status",
                    "attempt_count",
                    "last_error",
                    "created_at",
                    "response_status",
                    "response_payload_json"
                ).map { column -> PostgresSchemaColumn(commandTable, column) },
                listOf(
                    "command_id",
                    "status",
                    "attempt_count",
                    "last_error",
                    "leased_by",
                    "leased_until",
                    "updated_at"
                ).map { column -> PostgresSchemaColumn(queueTable, column) },
                listOf(
                    "command_id",
                    "status",
                    "attempt_count",
                    "last_error",
                    "response_status",
                    "response_payload_json",
                    "completed_at"
                ).map { column -> PostgresSchemaColumn(resultTable, column) }
            ).flatten()
        )
    }
}

object PostgresSchemaValidator {
    fun validate(conn: Connection, requirement: PostgresSchemaRequirement) {
        val missing = mutableListOf<String>()

        requirement.tables.forEach { table ->
            if (!tableExists(conn, table)) missing.add("table ${table.qualifiedName}")
        }
        requirement.functions.forEach { function ->
            if (!functionExists(conn, function)) missing.add("function ${function.qualifiedName}")
        }
        requirement.columns.forEach { column ->
            if (!columnMatches(conn, column)) {
                val expected = column.expectedDataType?.let { " type $it" }.orEmpty()
                missing.add("column ${column.qualifiedName}$expected")
            }
        }

        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Postgres schema validation failed; missing ${missing.joinToString(", ")}. " +
                    "Run make dev-db-migrate or set RUNTIME_DB_BOOTSTRAP_MODE=compat for local repair."
            )
        }
    }

    private fun tableExists(conn: Connection, table: PostgresSchemaObject): Boolean {
        conn.prepareStatement(
            """
            SELECT 1
            FROM information_schema.tables
            WHERE table_schema = ? AND table_name = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, table.schema)
            ps.setString(2, table.name)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun functionExists(conn: Connection, function: PostgresSchemaObject): Boolean {
        conn.prepareStatement(
            """
            SELECT 1
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = ? AND p.proname = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, function.schema)
            ps.setString(2, function.name)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun columnMatches(conn: Connection, column: PostgresSchemaColumn): Boolean {
        conn.prepareStatement(
            """
            SELECT data_type
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ? AND column_name = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, column.table.schema)
            ps.setString(2, column.table.name)
            ps.setString(3, column.name)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return false
                val expected = column.expectedDataType ?: return true
                return rs.getString("data_type") == expected
            }
        }
    }
}

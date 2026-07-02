package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.PostgresBoundarySqlNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgresSchemaRequirementsTest {
    @Test
    fun runtimeRequirementsCoverMigratedRuntimeAndAuthObjects() {
        val requirements = PostgresSchemaRequirements.runtime(PostgresRuntimeSqlNames())

        assertEquals(
            setOf(
                "runtime.reference_instruments",
                "runtime.reference_participants",
                "runtime.reference_accounts",
                "runtime.orders",
                "runtime.executions",
                "runtime.trades",
                "runtime.runtime_events",
                "runtime.runtime_trace_sequences",
                "runtime.submit_results",
                "auth.auth_roles",
                "auth.auth_actor_roles"
            ),
            requirements.tables.map { it.qualifiedName }.toSet()
        )
        assertEquals(
            setOf(
                "runtime.runtime_validate_reference_data",
                "runtime.runtime_persist_submit_outcome"
            ),
            requirements.functions.map { it.qualifiedName }.toSet()
        )
        assertEquals(
            setOf(
                "runtime.runtime_events.event_id:text",
                "runtime.runtime_events.occurred_at:text",
                "runtime.runtime_events.actor_id:text",
                "runtime.runtime_events.payload_json:jsonb",
                "runtime.runtime_events.sequence_number:bigint"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.runtime_events" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
    }

    @Test
    fun boundaryIdempotencyRequirementsCoverMigratedTable() {
        val names = PostgresBoundarySqlNames()
        val requirements = PostgresSchemaRequirements.boundaryIdempotency(names.idempotencyRecords)

        assertEquals(setOf("boundary.api_idempotency_records"), requirements.tables.map { it.qualifiedName }.toSet())
        assertTrue(requirements.functions.isEmpty())
        assertTrue(requirements.columns.isEmpty())
    }

    @Test
    fun boundaryCommandCaptureRequirementsCoverLiveStoreColumns() {
        val names = PostgresBoundarySqlNames()
        val requirements = PostgresSchemaRequirements.boundaryCommandCapture(names.commandCaptures)

        assertEquals(setOf("boundary.api_command_captures"), requirements.tables.map { it.qualifiedName }.toSet())
        assertEquals(
            setOf(
                "boundary.api_command_captures.client_id",
                "boundary.api_command_captures.route",
                "boundary.api_command_captures.idempotency_key",
                "boundary.api_command_captures.correlation_id",
                "boundary.api_command_captures.request_payload",
                "boundary.api_command_captures.status",
                "boundary.api_command_captures.response_status",
                "boundary.api_command_captures.response_payload",
                "boundary.api_command_captures.error_class",
                "boundary.api_command_captures.error_message",
                "boundary.api_command_captures.first_received_at",
                "boundary.api_command_captures.last_updated_at"
            ),
            requirements.columns.map { it.qualifiedName }.toSet()
        )
    }

    @Test
    fun commandLogRequirementsCoverAppendOnlyCommandTable() {
        val requirements = PostgresSchemaRequirements.commandLog("command_log.commands")

        assertEquals(
            setOf(
                "command_log.commands",
                "command_log.command_work_queue",
                "command_log.command_results",
                "command_log.retention_pins"
            ),
            requirements.tables.map { it.qualifiedName }.toSet()
        )
        assertEquals(setOf("command_log.command_append"), requirements.functions.map { it.qualifiedName }.toSet())
        assertEquals(
            setOf(
                "command_log.commands.command_id",
                "command_log.commands.client_id",
                "command_log.commands.route",
                "command_log.commands.idempotency_key",
                "command_log.commands.trace_id",
                "command_log.commands.correlation_id",
                "command_log.commands.actor_id",
                "command_log.commands.command_type",
                "command_log.commands.run_id",
                "command_log.commands.run_kind",
                "command_log.commands.scenario_id",
                "command_log.commands.received_at",
                "command_log.commands.payload_json",
                "command_log.commands.status",
                "command_log.commands.attempt_count",
                "command_log.commands.last_error",
                "command_log.commands.created_at",
                "command_log.commands.response_status",
                "command_log.commands.response_payload_json",
                "command_log.command_work_queue.command_id",
                "command_log.command_work_queue.status",
                "command_log.command_work_queue.attempt_count",
                "command_log.command_work_queue.last_error",
                "command_log.command_work_queue.leased_by",
                "command_log.command_work_queue.leased_until",
                "command_log.command_work_queue.updated_at",
                "command_log.command_results.command_id",
                "command_log.command_results.status",
                "command_log.command_results.attempt_count",
                "command_log.command_results.last_error",
                "command_log.command_results.response_status",
                "command_log.command_results.response_payload_json",
                "command_log.command_results.completed_at",
                "command_log.retention_pins.pin_id",
                "command_log.retention_pins.selector_type",
                "command_log.retention_pins.selector_value",
                "command_log.retention_pins.reason",
                "command_log.retention_pins.created_at",
                "command_log.retention_pins.updated_at"
            ),
            requirements.columns.map { it.qualifiedName }.toSet()
        )
    }
}

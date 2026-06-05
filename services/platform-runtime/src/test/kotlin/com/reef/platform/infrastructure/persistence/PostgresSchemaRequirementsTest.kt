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
}

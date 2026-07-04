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
                "runtime.canonical_command_results",
                "runtime.canonical_venue_events",
                "runtime.canonical_venue_event_batches",
                "runtime.canonical_command_outcomes",
                "runtime.projection_watermarks",
                "runtime.order_lifecycle_state",
                "runtime.market_data_snapshots",
                "auth.auth_roles",
                "auth.auth_actor_roles"
            ),
            requirements.tables.map { it.qualifiedName }.toSet()
        )
        assertEquals(
            setOf(
                "runtime.runtime_validate_reference_data",
                "runtime.runtime_persist_submit_outcome",
                "runtime.runtime_persist_submit_outcomes",
                "runtime.runtime_append_canonical_submit_outcomes",
                "runtime.runtime_project_canonical_submit_outcomes",
                "runtime.runtime_project_canonical_command_outcomes",
                "runtime.runtime_materialize_venue_event_batch"
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
        assertEquals(
            setOf(
                "runtime.canonical_command_results.command_id:text",
                "runtime.canonical_command_results.partition_seq:bigint",
                "runtime.canonical_command_results.stream_seq:bigint",
                "runtime.canonical_command_results.result_payload:jsonb"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.canonical_command_results" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.canonical_venue_events.event_id:text",
                "runtime.canonical_venue_events.event_seq:bigint",
                "runtime.canonical_venue_events.payload:jsonb"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.canonical_venue_events" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.canonical_venue_event_batches.batch_id:text",
                "runtime.canonical_venue_event_batches.payload_checksum:text",
                "runtime.canonical_venue_event_batches.payload_json:jsonb"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.canonical_venue_event_batches" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.canonical_command_outcomes.command_id:text",
                "runtime.canonical_command_outcomes.stream_sequence:bigint",
                "runtime.canonical_command_outcomes.result_payload:jsonb"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.canonical_command_outcomes" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.projection_watermarks.projection_name:text",
                "runtime.projection_watermarks.partition_id:integer",
                "runtime.projection_watermarks.last_partition_seq:bigint",
                "runtime.projection_watermarks.last_error:text"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.projection_watermarks" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.order_lifecycle_state.order_id:text",
                "runtime.order_lifecycle_state.remaining_quantity_units:text",
                "runtime.order_lifecycle_state.filled_quantity_units:text",
                "runtime.order_lifecycle_state.status:text"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.order_lifecycle_state" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.market_data_snapshots.projection_name:text",
                "runtime.market_data_snapshots.source_projection_name:text",
                "runtime.market_data_snapshots.instrument_id:text",
                "runtime.market_data_snapshots.last_partition_seq:bigint",
                "runtime.market_data_snapshots.lag:bigint"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.market_data_snapshots" }
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
    fun boundaryAccountRiskRequirementsCoverControlsAndDecisionAudit() {
        val names = PostgresBoundarySqlNames()
        val requirements = PostgresSchemaRequirements.boundaryAccountRisk(
            names.accountRiskControls,
            names.accountRiskDecisions
        )

        assertEquals(
            setOf("boundary.account_risk_controls", "boundary.account_risk_decisions"),
            requirements.tables.map { it.qualifiedName }.toSet()
        )
        assertEquals(
            setOf(
                "boundary.account_risk_controls.scope_type:text",
                "boundary.account_risk_controls.scope_id:text",
                "boundary.account_risk_controls.decision:text",
                "boundary.account_risk_controls.reason:text",
                "boundary.account_risk_controls.updated_at:timestamp with time zone",
                "boundary.account_risk_decisions.decision_id:text",
                "boundary.account_risk_decisions.decided_at:timestamp with time zone",
                "boundary.account_risk_decisions.decision:text",
                "boundary.account_risk_decisions.code:text",
                "boundary.account_risk_decisions.message:text",
                "boundary.account_risk_decisions.client_id:text",
                "boundary.account_risk_decisions.route:text",
                "boundary.account_risk_decisions.command_type:text",
                "boundary.account_risk_decisions.command_id:text",
                "boundary.account_risk_decisions.idempotency_key:text",
                "boundary.account_risk_decisions.correlation_id:text",
                "boundary.account_risk_decisions.actor_id:text",
                "boundary.account_risk_decisions.participant_id:text",
                "boundary.account_risk_decisions.account_id:text",
                "boundary.account_risk_decisions.bot_id:text",
                "boundary.account_risk_decisions.run_id:text",
                "boundary.account_risk_decisions.venue_session_id:text",
                "boundary.account_risk_decisions.instrument_id:text",
                "boundary.account_risk_decisions.order_id:text",
                "boundary.account_risk_decisions.payload_hash:text"
            ),
            requirements.columns.map { "${it.qualifiedName}:${it.expectedDataType}" }.toSet()
        )
    }

    @Test
    fun commandLogRequirementsCoverAppendOnlyCommandTable() {
        val requirements = PostgresSchemaRequirements.commandLog("command_log.commands")

        assertEquals(
            setOf(
                "command_log.commands",
                "command_log.command_payloads",
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
                "command_log.command_payloads.command_id",
                "command_log.command_payloads.payload_json",
                "command_log.command_payloads.created_at",
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

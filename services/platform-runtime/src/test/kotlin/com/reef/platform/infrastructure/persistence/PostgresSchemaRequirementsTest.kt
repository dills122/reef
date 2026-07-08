package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.PostgresBoundarySqlNames
import com.reef.platform.application.arena.PostgresArenaSqlNames
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
                "runtime.order_lifecycle_dirty",
                "runtime.market_data_snapshots",
                "runtime.market_data_snapshot_dirty",
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
                "runtime.runtime_materialize_venue_event_batch",
                "runtime.runtime_project_order_lifecycle_state",
                "runtime.runtime_project_market_data_snapshots"
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
                "runtime.order_lifecycle_state.original_quantity_units_num:numeric",
                "runtime.order_lifecycle_state.remaining_quantity_units:text",
                "runtime.order_lifecycle_state.remaining_quantity_units_num:numeric",
                "runtime.order_lifecycle_state.filled_quantity_units:text",
                "runtime.order_lifecycle_state.filled_quantity_units_num:numeric",
                "runtime.order_lifecycle_state.limit_price_num:numeric",
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
                "runtime.market_data_snapshots.best_bid_price_num:numeric",
                "runtime.market_data_snapshots.best_bid_quantity_num:numeric",
                "runtime.market_data_snapshots.best_ask_price_num:numeric",
                "runtime.market_data_snapshots.best_ask_quantity_num:numeric",
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
    fun arenaRegistryRequirementsCoverControlPlaneObjects() {
        val names = PostgresArenaSqlNames()
        val requirements = PostgresSchemaRequirements.arenaRegistry(
            bots = names.bots,
            botVersions = names.botVersions,
            qualificationReports = names.qualificationReports,
            qualificationReportIssues = names.qualificationReportIssues,
            operatorDecisions = names.operatorDecisions,
            runRecords = names.runRecords,
            runBotVersions = names.runBotVersions,
            runBotResults = names.runBotResults,
            runEnforcementEvents = names.runEnforcementEvents,
            runtimeConfigDescriptors = names.runtimeConfigDescriptors
        )

        assertEquals(
            setOf(
                "arena.bots",
                "arena.bot_versions",
                "arena.qualification_reports",
                "arena.qualification_report_issues",
                "arena.operator_decisions",
                "arena.run_records",
                "arena.run_bot_versions",
                "arena.run_bot_results",
                "arena.run_enforcement_events",
                "arena.runtime_config_descriptors"
            ),
            requirements.tables.map { it.qualifiedName }.toSet()
        )
        assertTrue(
            requirements.columns
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .containsAll(
                    setOf(
                        "arena.bot_versions.status:text",
                        "arena.run_records.seed:bigint",
                        "arena.run_bot_results.final_equity:bigint",
                        "arena.run_bot_results.disqualified:boolean",
                        "arena.run_bot_results.score_eligible:boolean",
                        "arena.run_bot_results.public_leaderboard:boolean",
                        "arena.run_enforcement_events.reason_code:text",
                        "arena.run_enforcement_events.counters_json:text",
                        "arena.runtime_config_descriptors.secret_path:text",
                        "arena.runtime_config_descriptors.required:boolean"
                    )
                )
        )
        assertEquals(
            setOf(
                "arena.run_bot_results.run_id:text",
                "arena.run_bot_results.bot_id:text",
                "arena.run_bot_results.version_id:text",
                "arena.run_bot_results.scoring_policy_version:text",
                "arena.run_bot_results.final_equity:bigint",
                "arena.run_bot_results.realized_pnl:bigint",
                "arena.run_bot_results.max_drawdown:bigint",
                "arena.run_bot_results.actions_proposed:integer",
                "arena.run_bot_results.order_actions_proposed:integer",
                "arena.run_bot_results.data_calls:integer",
                "arena.run_bot_results.signals_generated:integer",
                "arena.run_bot_results.disqualified:boolean",
                "arena.run_bot_results.score_eligible:boolean",
                "arena.run_bot_results.public_leaderboard:boolean",
                "arena.run_bot_results.created_at:timestamp with time zone"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "arena.run_bot_results" }
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
                "boundary.account_risk_controls.max_quantity_units:text",
                "boundary.account_risk_controls.max_notional:text",
                "boundary.account_risk_controls.currency:text",
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
                "boundary.account_risk_decisions.quantity_units:text",
                "boundary.account_risk_decisions.limit_price:text",
                "boundary.account_risk_decisions.currency:text",
                "boundary.account_risk_decisions.payload_hash:text"
            ),
            requirements.columns.map { "${it.qualifiedName}:${it.expectedDataType}" }.toSet()
        )
    }

    @Test
    fun boundaryCommandCircuitBreakerRequirementsCoverBreakerTable() {
        val names = PostgresBoundarySqlNames()
        val requirements = PostgresSchemaRequirements.boundaryCommandCircuitBreakers(names.commandCircuitBreakers)

        assertEquals(setOf("boundary.command_circuit_breakers"), requirements.tables.map { it.qualifiedName }.toSet())
        assertEquals(
            setOf(
                "boundary.command_circuit_breakers.scope_type:text",
                "boundary.command_circuit_breakers.scope_id:text",
                "boundary.command_circuit_breakers.tripped:boolean",
                "boundary.command_circuit_breakers.reason:text",
                "boundary.command_circuit_breakers.updated_at:timestamp with time zone"
            ),
            requirements.columns.map { "${it.qualifiedName}:${it.expectedDataType}" }.toSet()
        )
    }

    @Test
    fun boundaryInstrumentPriceCollarRequirementsCoverCollarTable() {
        val names = PostgresBoundarySqlNames()
        val requirements = PostgresSchemaRequirements.boundaryInstrumentPriceCollars(names.instrumentPriceCollars)

        assertEquals(setOf("boundary.instrument_price_collars"), requirements.tables.map { it.qualifiedName }.toSet())
        assertEquals(
            setOf(
                "boundary.instrument_price_collars.instrument_id:text",
                "boundary.instrument_price_collars.min_price:text",
                "boundary.instrument_price_collars.max_price:text",
                "boundary.instrument_price_collars.currency:text",
                "boundary.instrument_price_collars.reason:text",
                "boundary.instrument_price_collars.updated_at:timestamp with time zone"
            ),
            requirements.columns.map { "${it.qualifiedName}:${it.expectedDataType}" }.toSet()
        )
    }

    @Test
    fun boundaryRejectionRequirementsCoverGuardrailAuditTable() {
        val names = PostgresBoundarySqlNames()
        val requirements = PostgresSchemaRequirements.boundaryRejections(names.boundaryRejections)

        assertEquals(setOf("boundary.boundary_rejections"), requirements.tables.map { it.qualifiedName }.toSet())
        assertTrue(requirements.columns.any { it.qualifiedName == "boundary.boundary_rejections.guardrail_type" })
        assertTrue(requirements.columns.any { it.qualifiedName == "boundary.boundary_rejections.command_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "boundary.boundary_rejections.payload_hash" })
    }

    @Test
    fun settlementFactsRequirementsCoverAllFourTables() {
        val requirements = PostgresSchemaRequirements.settlementFacts(
            obligations = "settlement.obligations",
            breaks = "settlement.breaks",
            repairs = "settlement.repairs",
            resolutions = "settlement.resolutions"
        )

        assertEquals(
            setOf("settlement.obligations", "settlement.breaks", "settlement.repairs", "settlement.resolutions"),
            requirements.tables.map { it.qualifiedName }.toSet()
        )
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.obligations.trade_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.breaks.reason" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.repairs.actor_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.resolutions.settlement_state" })
    }

    @Test
    fun boundaryStreamCommandIntakeRequirementsCoverIntakeTable() {
        val requirements = PostgresSchemaRequirements.boundaryStreamCommandIntake("boundary.stream_command_intake")

        assertEquals(setOf("boundary.stream_command_intake"), requirements.tables.map { it.qualifiedName }.toSet())
        assertEquals(
            setOf(
                "boundary.stream_command_intake.scope",
                "boundary.stream_command_intake.idempotency_key",
                "boundary.stream_command_intake.payload_hash",
                "boundary.stream_command_intake.command_id",
                "boundary.stream_command_intake.route",
                "boundary.stream_command_intake.subject",
                "boundary.stream_command_intake.stream_name",
                "boundary.stream_command_intake.partition",
                "boundary.stream_command_intake.stream_sequence",
                "boundary.stream_command_intake.published",
                "boundary.stream_command_intake.first_seen_at",
                "boundary.stream_command_intake.published_at"
            ),
            requirements.columns.map { it.qualifiedName }.toSet()
        )
    }

    @Test
    fun analyticsRunExportsRequirementsCoverExportTable() {
        val requirements = PostgresSchemaRequirements.analyticsRunExports("analytics.simulation_run_exports")

        assertEquals(setOf("analytics.simulation_run_exports"), requirements.tables.map { it.qualifiedName }.toSet())
        assertTrue(requirements.columns.any { it.qualifiedName == "analytics.simulation_run_exports.run_id" && it.expectedDataType == "text" })
        assertTrue(requirements.columns.any { it.qualifiedName == "analytics.simulation_run_exports.attempted_count" && it.expectedDataType == "bigint" })
        assertTrue(requirements.columns.any { it.qualifiedName == "analytics.simulation_run_exports.artifact_manifest" && it.expectedDataType == "jsonb" })
        assertEquals(21, requirements.columns.size)
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

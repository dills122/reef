package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.PostgresBoundarySqlNames
import com.reef.platform.application.admin.PostgresAdminAuthSqlNames
import com.reef.platform.application.admin.PostgresAdminIdentitySqlNames
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
                "runtime.reference_scenario_runs",
                "runtime.reference_venue_sessions",
                "runtime.orders",
                "runtime.executions",
                "runtime.trades",
                "runtime.trades_archive",
                "runtime.runtime_events",
                "runtime.runtime_event_payloads",
                "runtime.runtime_events_archive",
                "runtime.runtime_trace_sequences",
                "runtime.submit_results",
                "runtime.canonical_command_results",
                "runtime.canonical_venue_events",
                "runtime.canonical_venue_event_batches",
                "runtime.canonical_venue_event_batches_archive",
                "runtime.canonical_command_outcomes",
                "runtime.canonical_command_outcomes_archive",
                "runtime.projection_watermarks",
                "runtime.order_lifecycle_state",
                "runtime.order_lifecycle_dirty",
                "runtime.market_data_snapshots",
                "runtime.market_data_snapshot_dirty",
                "auth.auth_roles",
                "auth.auth_actor_roles",
                "admin.post_trade_profiles"
            ),
            requirements.tables.map { it.qualifiedName }.toSet()
        )
        assertEquals(
            setOf(
                "runtime.runtime_validate_reference_data",
                "runtime.runtime_persist_submit_outcome",
                "runtime.runtime_persist_submit_outcomes",
                "runtime.runtime_persist_submit_outcome_status_stage",
                "runtime.runtime_persist_submit_outcome_timeline_stage",
                "runtime.runtime_reject_execution_replay_conflict",
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
                "runtime.orders.client_order_id:text",
                "runtime.orders.run_id:text",
                "runtime.orders.venue_session_id:text",
                "runtime.orders.quantity_units_num:numeric",
                "runtime.orders.limit_price_num:numeric",
                "runtime.orders.accepted_at:text",
                "runtime.orders.accepted_at_ts:timestamp with time zone"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.orders" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.executions.event_id:text",
                "runtime.executions.event_id_uuid:uuid",
                "runtime.executions.quantity_units_num:numeric",
                "runtime.executions.execution_price_num:numeric",
                "runtime.executions.liquidity_role:text",
                "runtime.executions.occurred_at:text",
                "runtime.executions.occurred_at_ts:timestamp with time zone"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.executions" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.trades.event_id:text",
                "runtime.trades.event_id_uuid:uuid",
                "runtime.trades.quantity_units_num:numeric",
                "runtime.trades.price_num:numeric",
                "runtime.trades.occurred_at:text",
                "runtime.trades.occurred_at_ts:timestamp with time zone"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.trades" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.trades_archive.event_id:text",
                "runtime.trades_archive.instrument_id:text",
                "runtime.trades_archive.price_num:numeric",
                "runtime.trades_archive.occurred_at_ts:timestamp with time zone",
                "runtime.trades_archive.archived_at:timestamp with time zone"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.trades_archive" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.runtime_events.event_id:text",
                "runtime.runtime_events.event_id_uuid:uuid",
                "runtime.runtime_events.occurred_at:text",
                "runtime.runtime_events.occurred_at_ts:timestamp with time zone",
                "runtime.runtime_events.actor_id:text",
                "runtime.runtime_events.payload_json:jsonb",
                "runtime.runtime_events.modify_quantity_units:text",
                "runtime.runtime_events.modify_limit_price:text",
                "runtime.runtime_events.sequence_number:bigint"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.runtime_events" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.runtime_event_payloads.event_id:text",
                "runtime.runtime_event_payloads.payload_json:jsonb"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.runtime_event_payloads" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.runtime_events_archive.event_id:text",
                "runtime.runtime_events_archive.event_id_uuid:uuid",
                "runtime.runtime_events_archive.occurred_at_ts:timestamp with time zone",
                "runtime.runtime_events_archive.payload_json:jsonb",
                "runtime.runtime_events_archive.archived_at:timestamp with time zone"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.runtime_events_archive" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.submit_results.command_id:text",
                "runtime.submit_results.event_id:text",
                "runtime.submit_results.event_id_uuid:uuid",
                "runtime.submit_results.occurred_at:text",
                "runtime.submit_results.occurred_at_ts:timestamp with time zone",
                "runtime.submit_results.result_type:text"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.submit_results" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.canonical_command_results.command_id:text",
                "runtime.canonical_command_results.partition_seq:bigint",
                "runtime.canonical_command_results.stream_seq:bigint",
                "runtime.canonical_command_results.result_payload:jsonb",
                "runtime.canonical_command_results.accepted_at_ts:timestamp with time zone",
                "runtime.canonical_command_results.completed_at_ts:timestamp with time zone"
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
                "runtime.canonical_venue_events.payload:jsonb",
                "runtime.canonical_venue_events.emitted_at_ts:timestamp with time zone"
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
                "runtime.canonical_venue_event_batches.payload_json:jsonb",
                "runtime.canonical_venue_event_batches.created_at_ts:timestamp with time zone"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.canonical_venue_event_batches" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.canonical_venue_event_batches_archive.batch_id:text",
                "runtime.canonical_venue_event_batches_archive.payload_checksum:text",
                "runtime.canonical_venue_event_batches_archive.payload_json:jsonb",
                "runtime.canonical_venue_event_batches_archive.materialized_at:timestamp with time zone",
                "runtime.canonical_venue_event_batches_archive.archived_at:timestamp with time zone"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.canonical_venue_event_batches_archive" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.canonical_command_outcomes.command_id:text",
                "runtime.canonical_command_outcomes.stream_sequence:bigint",
                "runtime.canonical_command_outcomes.result_payload:jsonb",
                "runtime.canonical_command_outcomes.occurred_at_ts:timestamp with time zone"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.canonical_command_outcomes" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.canonical_command_outcomes_archive.command_id:text",
                "runtime.canonical_command_outcomes_archive.stream_sequence:bigint",
                "runtime.canonical_command_outcomes_archive.result_payload:jsonb",
                "runtime.canonical_command_outcomes_archive.materialized_at:timestamp with time zone",
                "runtime.canonical_command_outcomes_archive.archived_at:timestamp with time zone"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.canonical_command_outcomes_archive" }
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
        assertEquals(
            setOf(
                "admin.post_trade_profiles.profile_id:text",
                "admin.post_trade_profiles.mode:text",
                "admin.post_trade_profiles.settlement_cycle:text",
                "admin.post_trade_profiles.netting_mode:text",
                "admin.post_trade_profiles.ledger_posting_mode:text",
                "admin.post_trade_profiles.policy_version:integer",
                "admin.post_trade_profiles.active:boolean"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "admin.post_trade_profiles" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.reference_scenario_runs.scenario_run_id:text",
                "runtime.reference_scenario_runs.post_trade_profile_id:text"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.reference_scenario_runs" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
        assertEquals(
            setOf(
                "runtime.reference_venue_sessions.venue_session_id:text",
                "runtime.reference_venue_sessions.post_trade_profile_id:text"
            ),
            requirements.columns
                .filter { it.table.qualifiedName == "runtime.reference_venue_sessions" }
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .toSet()
        )
    }

    @Test
    fun adminIdentityRequirementsCoverUserRoleAndAuditObjects() {
        val names = PostgresAdminIdentitySqlNames()
        val requirements = PostgresSchemaRequirements.adminIdentity(
            users = names.users,
            roles = names.roles,
            userRoles = names.userRoles,
            auditEvents = names.auditEvents
        )

        assertEquals(
            setOf(
                "admin.users",
                "admin.roles",
                "admin.user_roles",
                "admin.audit_events"
            ),
            requirements.tables.map { it.qualifiedName }.toSet()
        )
        assertTrue(
            requirements.columns
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .containsAll(
                    setOf(
                        "admin.users.github_user_id:bigint",
                        "admin.users.trust_state:text",
                        "admin.user_roles.role_id:text",
                        "admin.audit_events.event_type:text",
                        "admin.audit_events.target_id:text"
                    )
                )
        )
    }

    @Test
    fun adminAuthRequirementsCoverOAuthSessionsAndServiceTokens() {
        val names = PostgresAdminAuthSqlNames()
        val requirements = PostgresSchemaRequirements.adminAuth(
            oauthStates = names.oauthStates,
            sessions = names.sessions,
            serviceTokens = names.serviceTokens
        )

        assertEquals(
            setOf(
                "admin.oauth_states",
                "admin.sessions",
                "admin.service_tokens"
            ),
            requirements.tables.map { it.qualifiedName }.toSet()
        )
        assertTrue(
            requirements.columns
                .map { "${it.qualifiedName}:${it.expectedDataType}" }
                .containsAll(
                    setOf(
                        "admin.oauth_states.state_hash:text",
                        "admin.oauth_states.consumed_at:timestamp with time zone",
                        "admin.sessions.session_hash:text",
                        "admin.sessions.reef_user_id:text",
                        "admin.sessions.revoked_at:timestamp with time zone",
                        "admin.service_tokens.token_hash:text",
                        "admin.service_tokens.token_family:text",
                        "admin.service_tokens.subject_actor_id:text"
                    )
                )
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
    fun settlementFactsRequirementsCoverAllSettlementTables() {
        val requirements = PostgresSchemaRequirements.settlementFacts(
            resourcePositions = "settlement.resource_positions",
            obligations = "settlement.obligations",
            allocations = "settlement.allocations",
            confirmations = "settlement.confirmations",
            affirmations = "settlement.affirmations",
            clearingSubmissions = "settlement.clearing_submissions",
            clearingAcceptances = "settlement.clearing_acceptances",
            clearingRejections = "settlement.clearing_rejections",
            novations = "settlement.novations",
            instructions = "settlement.instructions",
            attempts = "settlement.attempts",
            legOutcomes = "settlement.leg_outcomes",
            ledgerEntries = "settlement.ledger_entries",
            settlements = "settlement.settlements",
            breaks = "settlement.breaks",
            repairs = "settlement.repairs",
            resolutions = "settlement.resolutions",
            operatorActions = "settlement.operator_actions"
        )

        assertEquals(
            setOf(
                "settlement.resource_positions",
                "settlement.obligations",
                "settlement.allocations",
                "settlement.confirmations",
                "settlement.affirmations",
                "settlement.clearing_submissions",
                "settlement.clearing_acceptances",
                "settlement.clearing_rejections",
                "settlement.novations",
                "settlement.instructions",
                "settlement.attempts",
                "settlement.leg_outcomes",
                "settlement.ledger_entries",
                "settlement.settlements",
                "settlement.breaks",
                "settlement.repairs",
                "settlement.resolutions",
                "settlement.operator_actions"
            ),
            requirements.tables.map { it.qualifiedName }.toSet()
        )
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.resource_positions.resource_position_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.resource_positions.quantity" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.obligations.trade_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.obligations.post_trade_profile_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.allocations.settlement_allocation_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.allocations.buy_order_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.confirmations.settlement_confirmation_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.confirmations.settlement_allocation_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.affirmations.settlement_affirmation_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.affirmations.settlement_confirmation_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.clearing_submissions.settlement_clearing_submission_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.clearing_submissions.settlement_affirmation_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.clearing_acceptances.settlement_clearing_acceptance_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.clearing_acceptances.settlement_clearing_submission_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.clearing_rejections.settlement_clearing_rejection_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.clearing_rejections.reason" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.novations.settlement_novation_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.novations.settlement_clearing_acceptance_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.instructions.settlement_instruction_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.instructions.instruction_type" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.attempts.settlement_attempt_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.attempts.settlement_instruction_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.attempts.attempt_number" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.leg_outcomes.leg_type" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.ledger_entries.direction" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.settlements.settlement_state" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.breaks.reason" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.breaks.post_trade_policy_version" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.repairs.actor_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.repairs.post_trade_profile_id" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.resolutions.settlement_state" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.resolutions.post_trade_policy_version" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.operator_actions.action" })
        assertTrue(requirements.columns.any { it.qualifiedName == "settlement.operator_actions.reason_note" })
    }

    @Test
    fun boundaryStreamCommandIntakeRequirementsCoverIntakeTable() {
        val requirements = PostgresSchemaRequirements.boundaryStreamCommandIntake("boundary.stream_command_intake")

        assertEquals(setOf("boundary.stream_command_intake"), requirements.tables.map { it.qualifiedName }.toSet())
        assertEquals(
            setOf(
                "boundary.stream_command_intake.scope",
                "boundary.stream_command_intake.client_id",
                "boundary.stream_command_intake.idempotency_key",
                "boundary.stream_command_intake.participant_id",
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
    fun analyticsRunBotPerformanceRequirementsCoverSummaryTable() {
        val requirements = PostgresSchemaRequirements.analyticsRunBotPerformanceSummaries("analytics.run_bot_performance_summaries")

        assertEquals(setOf("analytics.run_bot_performance_summaries"), requirements.tables.map { it.qualifiedName }.toSet())
        assertTrue(requirements.columns.any { it.qualifiedName == "analytics.run_bot_performance_summaries.run_id" && it.expectedDataType == "text" })
        assertTrue(requirements.columns.any { it.qualifiedName == "analytics.run_bot_performance_summaries.bot_id" && it.expectedDataType == "text" })
        assertTrue(requirements.columns.any { it.qualifiedName == "analytics.run_bot_performance_summaries.final_equity" && it.expectedDataType == "double precision" })
        assertTrue(requirements.columns.any { it.qualifiedName == "analytics.run_bot_performance_summaries.settlement_score_summary" && it.expectedDataType == "jsonb" })
        assertEquals(15, requirements.columns.size)
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
                "command_log.command_results_archive",
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
                "command_log.command_results_archive.command_id",
                "command_log.command_results_archive.status",
                "command_log.command_results_archive.attempt_count",
                "command_log.command_results_archive.last_error",
                "command_log.command_results_archive.response_status",
                "command_log.command_results_archive.response_payload_json",
                "command_log.command_results_archive.completed_at",
                "command_log.command_results_archive.archived_at",
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

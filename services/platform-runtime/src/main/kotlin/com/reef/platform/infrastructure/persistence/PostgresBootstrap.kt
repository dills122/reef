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
        val runtimeEventPayloads = PostgresSchemaObject.parse(names.runtimeEventPayloads)
        val canonicalCommandResults = PostgresSchemaObject.parse(names.canonicalCommandResults)
        val canonicalVenueEvents = PostgresSchemaObject.parse(names.canonicalVenueEvents)
        val canonicalVenueEventBatches = PostgresSchemaObject.parse(names.canonicalVenueEventBatches)
        val canonicalVenueEventBatchesArchive = PostgresSchemaObject.parse(names.canonicalVenueEventBatchesArchive)
        val canonicalCommandOutcomes = PostgresSchemaObject.parse(names.canonicalCommandOutcomes)
        val canonicalCommandOutcomesArchive = PostgresSchemaObject.parse(names.canonicalCommandOutcomesArchive)
        val projectionWatermarks = PostgresSchemaObject.parse(names.projectionWatermarks)
        val marketDataSnapshots = PostgresSchemaObject.parse(names.marketDataSnapshots)
        val orderLifecycleState = PostgresSchemaObject.parse(names.orderLifecycleState)
        val orders = PostgresSchemaObject.parse(names.orders)
        val executions = PostgresSchemaObject.parse(names.executions)
        val trades = PostgresSchemaObject.parse(names.trades)
        val tradesArchive = PostgresSchemaObject.parse(names.tradesArchive)
        val submitResults = PostgresSchemaObject.parse(names.submitResults)
        val runtimeEventsArchive = PostgresSchemaObject.parse(names.runtimeEventsArchive)
        val postTradeProfiles = PostgresSchemaObject.parse(names.adminPostTradeProfiles)
        val scenarioRuns = PostgresSchemaObject.parse(names.referenceScenarioRuns)
        val venueSessions = PostgresSchemaObject.parse(names.referenceVenueSessions)
        return PostgresSchemaRequirement(
            tables = listOf(
                names.referenceInstruments,
                names.referenceParticipants,
                names.referenceAccounts,
                names.referenceScenarioRuns,
                names.referenceVenueSessions,
                names.orders,
                names.executions,
                names.trades,
                names.tradesArchive,
                names.runtimeEvents,
                names.runtimeEventPayloads,
                names.runtimeEventsArchive,
                names.runtimeTraceSequences,
                names.submitResults,
                names.canonicalCommandResults,
                names.canonicalVenueEvents,
                names.canonicalVenueEventBatches,
                names.canonicalVenueEventBatchesArchive,
                names.canonicalCommandOutcomes,
                names.canonicalCommandOutcomesArchive,
                names.projectionWatermarks,
                names.orderLifecycleState,
                names.orderLifecycleDirty,
                names.marketDataSnapshots,
                names.marketDataSnapshotDirty,
                names.authRoles,
                names.authActorRoles,
                names.adminPostTradeProfiles
            ).map(PostgresSchemaObject::parse),
            functions = listOf(
                names.validateReferenceDataFunction,
                names.persistSubmitOutcomeFunction,
                names.persistSubmitOutcomesFunction,
                names.persistSubmitOutcomeStatusStageFunction,
                names.persistSubmitOutcomeTimelineStageFunction,
                names.rejectExecutionReplayConflictFunction,
                names.appendCanonicalSubmitOutcomesFunction,
                names.projectCanonicalSubmitOutcomesFunction,
                names.projectCanonicalCommandOutcomesFunction,
                names.materializeVenueEventBatchFunction,
                names.projectOrderLifecycleStateFunction,
                names.projectMarketDataSnapshotsFunction
            ).map(PostgresSchemaObject::parse),
            columns = listOf(
                PostgresSchemaColumn(orders, "client_order_id", "text"),
                PostgresSchemaColumn(orders, "run_id", "text"),
                PostgresSchemaColumn(orders, "venue_session_id", "text"),
                PostgresSchemaColumn(orders, "quantity_units_num", "numeric"),
                PostgresSchemaColumn(orders, "limit_price_num", "numeric"),
                PostgresSchemaColumn(orders, "accepted_at", "text"),
                PostgresSchemaColumn(orders, "accepted_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(executions, "event_id", "text"),
                PostgresSchemaColumn(executions, "event_id_uuid", "uuid"),
                PostgresSchemaColumn(executions, "quantity_units_num", "numeric"),
                PostgresSchemaColumn(executions, "execution_price_num", "numeric"),
                PostgresSchemaColumn(executions, "liquidity_role", "text"),
                PostgresSchemaColumn(executions, "occurred_at", "text"),
                PostgresSchemaColumn(executions, "occurred_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(trades, "event_id", "text"),
                PostgresSchemaColumn(trades, "event_id_uuid", "uuid"),
                PostgresSchemaColumn(trades, "quantity_units_num", "numeric"),
                PostgresSchemaColumn(trades, "price_num", "numeric"),
                PostgresSchemaColumn(trades, "occurred_at", "text"),
                PostgresSchemaColumn(trades, "occurred_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(tradesArchive, "event_id", "text"),
                PostgresSchemaColumn(tradesArchive, "instrument_id", "text"),
                PostgresSchemaColumn(tradesArchive, "price_num", "numeric"),
                PostgresSchemaColumn(tradesArchive, "occurred_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(tradesArchive, "archived_at", "timestamp with time zone"),
                PostgresSchemaColumn(runtimeEvents, "event_id", "text"),
                PostgresSchemaColumn(runtimeEvents, "event_id_uuid", "uuid"),
                PostgresSchemaColumn(runtimeEvents, "occurred_at", "text"),
                PostgresSchemaColumn(runtimeEvents, "occurred_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(runtimeEvents, "actor_id", "text"),
                PostgresSchemaColumn(runtimeEvents, "payload_json", "jsonb"),
                PostgresSchemaColumn(runtimeEvents, "modify_quantity_units", "text"),
                PostgresSchemaColumn(runtimeEvents, "modify_limit_price", "text"),
                PostgresSchemaColumn(runtimeEvents, "sequence_number", "bigint"),
                PostgresSchemaColumn(runtimeEventPayloads, "event_id", "text"),
                PostgresSchemaColumn(runtimeEventPayloads, "payload_json", "jsonb"),
                PostgresSchemaColumn(runtimeEventsArchive, "event_id", "text"),
                PostgresSchemaColumn(runtimeEventsArchive, "event_id_uuid", "uuid"),
                PostgresSchemaColumn(runtimeEventsArchive, "occurred_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(runtimeEventsArchive, "payload_json", "jsonb"),
                PostgresSchemaColumn(runtimeEventsArchive, "archived_at", "timestamp with time zone"),
                PostgresSchemaColumn(submitResults, "command_id", "text"),
                PostgresSchemaColumn(submitResults, "event_id", "text"),
                PostgresSchemaColumn(submitResults, "event_id_uuid", "uuid"),
                PostgresSchemaColumn(submitResults, "occurred_at", "text"),
                PostgresSchemaColumn(submitResults, "occurred_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(submitResults, "result_type", "text"),
                PostgresSchemaColumn(canonicalCommandResults, "command_id", "text"),
                PostgresSchemaColumn(canonicalCommandResults, "partition_seq", "bigint"),
                PostgresSchemaColumn(canonicalCommandResults, "stream_seq", "bigint"),
                PostgresSchemaColumn(canonicalCommandResults, "result_payload", "jsonb"),
                PostgresSchemaColumn(canonicalCommandResults, "accepted_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(canonicalCommandResults, "completed_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(canonicalVenueEvents, "event_id", "text"),
                PostgresSchemaColumn(canonicalVenueEvents, "event_seq", "bigint"),
                PostgresSchemaColumn(canonicalVenueEvents, "payload", "jsonb"),
                PostgresSchemaColumn(canonicalVenueEvents, "emitted_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(canonicalVenueEventBatches, "batch_id", "text"),
                PostgresSchemaColumn(canonicalVenueEventBatches, "payload_checksum", "text"),
                PostgresSchemaColumn(canonicalVenueEventBatches, "payload_json", "jsonb"),
                PostgresSchemaColumn(canonicalVenueEventBatches, "created_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(canonicalVenueEventBatchesArchive, "batch_id", "text"),
                PostgresSchemaColumn(canonicalVenueEventBatchesArchive, "payload_checksum", "text"),
                PostgresSchemaColumn(canonicalVenueEventBatchesArchive, "payload_json", "jsonb"),
                PostgresSchemaColumn(canonicalVenueEventBatchesArchive, "materialized_at", "timestamp with time zone"),
                PostgresSchemaColumn(canonicalVenueEventBatchesArchive, "archived_at", "timestamp with time zone"),
                PostgresSchemaColumn(canonicalCommandOutcomes, "command_id", "text"),
                PostgresSchemaColumn(canonicalCommandOutcomes, "stream_sequence", "bigint"),
                PostgresSchemaColumn(canonicalCommandOutcomes, "result_payload", "jsonb"),
                PostgresSchemaColumn(canonicalCommandOutcomes, "occurred_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(canonicalCommandOutcomesArchive, "command_id", "text"),
                PostgresSchemaColumn(canonicalCommandOutcomesArchive, "stream_sequence", "bigint"),
                PostgresSchemaColumn(canonicalCommandOutcomesArchive, "result_payload", "jsonb"),
                PostgresSchemaColumn(canonicalCommandOutcomesArchive, "materialized_at", "timestamp with time zone"),
                PostgresSchemaColumn(canonicalCommandOutcomesArchive, "archived_at", "timestamp with time zone"),
                PostgresSchemaColumn(projectionWatermarks, "projection_name", "text"),
                PostgresSchemaColumn(projectionWatermarks, "partition_id", "integer"),
                PostgresSchemaColumn(projectionWatermarks, "last_partition_seq", "bigint"),
                PostgresSchemaColumn(projectionWatermarks, "last_error", "text"),
                PostgresSchemaColumn(orderLifecycleState, "order_id", "text"),
                PostgresSchemaColumn(orderLifecycleState, "original_quantity_units_num", "numeric"),
                PostgresSchemaColumn(orderLifecycleState, "remaining_quantity_units", "text"),
                PostgresSchemaColumn(orderLifecycleState, "remaining_quantity_units_num", "numeric"),
                PostgresSchemaColumn(orderLifecycleState, "filled_quantity_units", "text"),
                PostgresSchemaColumn(orderLifecycleState, "filled_quantity_units_num", "numeric"),
                PostgresSchemaColumn(orderLifecycleState, "limit_price_num", "numeric"),
                PostgresSchemaColumn(orderLifecycleState, "status", "text"),
                PostgresSchemaColumn(marketDataSnapshots, "projection_name", "text"),
                PostgresSchemaColumn(marketDataSnapshots, "source_projection_name", "text"),
                PostgresSchemaColumn(marketDataSnapshots, "instrument_id", "text"),
                PostgresSchemaColumn(marketDataSnapshots, "best_bid_price_num", "numeric"),
                PostgresSchemaColumn(marketDataSnapshots, "best_bid_quantity_num", "numeric"),
                PostgresSchemaColumn(marketDataSnapshots, "best_ask_price_num", "numeric"),
                PostgresSchemaColumn(marketDataSnapshots, "best_ask_quantity_num", "numeric"),
                PostgresSchemaColumn(marketDataSnapshots, "last_partition_seq", "bigint"),
                PostgresSchemaColumn(marketDataSnapshots, "lag", "bigint"),
                PostgresSchemaColumn(postTradeProfiles, "profile_id", "text"),
                PostgresSchemaColumn(postTradeProfiles, "mode", "text"),
                PostgresSchemaColumn(postTradeProfiles, "settlement_cycle", "text"),
                PostgresSchemaColumn(postTradeProfiles, "netting_mode", "text"),
                PostgresSchemaColumn(postTradeProfiles, "ledger_posting_mode", "text"),
                PostgresSchemaColumn(postTradeProfiles, "policy_version", "integer"),
                PostgresSchemaColumn(postTradeProfiles, "active", "boolean"),
                PostgresSchemaColumn(scenarioRuns, "scenario_run_id", "text"),
                PostgresSchemaColumn(scenarioRuns, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(venueSessions, "venue_session_id", "text"),
                PostgresSchemaColumn(venueSessions, "post_trade_profile_id", "text")
            )
        )
    }

    fun boundaryIdempotency(idempotencyRecords: String): PostgresSchemaRequirement {
        return PostgresSchemaRequirement(tables = listOf(PostgresSchemaObject.parse(idempotencyRecords)))
    }

    fun settlementFacts(
        resourcePositions: String,
        obligations: String,
        allocations: String,
        confirmations: String,
        affirmations: String,
        clearingSubmissions: String,
        clearingAcceptances: String,
        clearingRejections: String,
        novations: String,
        instructions: String,
        attempts: String,
        legOutcomes: String,
        ledgerEntries: String,
        settlements: String,
        breaks: String,
        repairs: String,
        resolutions: String,
        operatorActions: String
    ): PostgresSchemaRequirement {
        val resourcePositionTable = PostgresSchemaObject.parse(resourcePositions)
        val obligationTable = PostgresSchemaObject.parse(obligations)
        val allocationTable = PostgresSchemaObject.parse(allocations)
        val confirmationTable = PostgresSchemaObject.parse(confirmations)
        val affirmationTable = PostgresSchemaObject.parse(affirmations)
        val clearingSubmissionTable = PostgresSchemaObject.parse(clearingSubmissions)
        val clearingAcceptanceTable = PostgresSchemaObject.parse(clearingAcceptances)
        val clearingRejectionTable = PostgresSchemaObject.parse(clearingRejections)
        val novationTable = PostgresSchemaObject.parse(novations)
        val instructionTable = PostgresSchemaObject.parse(instructions)
        val attemptTable = PostgresSchemaObject.parse(attempts)
        val legOutcomeTable = PostgresSchemaObject.parse(legOutcomes)
        val ledgerEntryTable = PostgresSchemaObject.parse(ledgerEntries)
        val settlementTable = PostgresSchemaObject.parse(settlements)
        val breakTable = PostgresSchemaObject.parse(breaks)
        val repairTable = PostgresSchemaObject.parse(repairs)
        val resolutionTable = PostgresSchemaObject.parse(resolutions)
        val operatorActionTable = PostgresSchemaObject.parse(operatorActions)
        return PostgresSchemaRequirement(
            tables = listOf(
                resourcePositionTable,
                obligationTable,
                allocationTable,
                confirmationTable,
                affirmationTable,
                clearingSubmissionTable,
                clearingAcceptanceTable,
                clearingRejectionTable,
                novationTable,
                instructionTable,
                attemptTable,
                legOutcomeTable,
                ledgerEntryTable,
                settlementTable,
                breakTable,
                repairTable,
                resolutionTable,
                operatorActionTable
            ),
            columns = listOf(
                PostgresSchemaColumn(resourcePositionTable, "resource_position_id", "text"),
                PostgresSchemaColumn(resourcePositionTable, "scenario_run_id", "text"),
                PostgresSchemaColumn(resourcePositionTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(resourcePositionTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(resourcePositionTable, "participant_id", "text"),
                PostgresSchemaColumn(resourcePositionTable, "account_id", "text"),
                PostgresSchemaColumn(resourcePositionTable, "asset_type", "text"),
                PostgresSchemaColumn(resourcePositionTable, "asset_id", "text"),
                PostgresSchemaColumn(resourcePositionTable, "quantity", "text"),
                PostgresSchemaColumn(obligationTable, "settlement_obligation_id", "text"),
                PostgresSchemaColumn(obligationTable, "scenario_run_id", "text"),
                PostgresSchemaColumn(obligationTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(obligationTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(obligationTable, "trade_id", "text"),
                PostgresSchemaColumn(obligationTable, "occurred_at", "timestamp with time zone"),
                PostgresSchemaColumn(allocationTable, "settlement_allocation_id", "text"),
                PostgresSchemaColumn(allocationTable, "settlement_obligation_id", "text"),
                PostgresSchemaColumn(allocationTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(allocationTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(allocationTable, "trade_id", "text"),
                PostgresSchemaColumn(allocationTable, "buy_order_id", "text"),
                PostgresSchemaColumn(allocationTable, "sell_order_id", "text"),
                PostgresSchemaColumn(allocationTable, "state", "text"),
                PostgresSchemaColumn(confirmationTable, "settlement_confirmation_id", "text"),
                PostgresSchemaColumn(confirmationTable, "settlement_allocation_id", "text"),
                PostgresSchemaColumn(confirmationTable, "settlement_obligation_id", "text"),
                PostgresSchemaColumn(confirmationTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(confirmationTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(confirmationTable, "state", "text"),
                PostgresSchemaColumn(affirmationTable, "settlement_affirmation_id", "text"),
                PostgresSchemaColumn(affirmationTable, "settlement_confirmation_id", "text"),
                PostgresSchemaColumn(affirmationTable, "settlement_allocation_id", "text"),
                PostgresSchemaColumn(affirmationTable, "settlement_obligation_id", "text"),
                PostgresSchemaColumn(affirmationTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(affirmationTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(affirmationTable, "actor_id", "text"),
                PostgresSchemaColumn(affirmationTable, "state", "text"),
                PostgresSchemaColumn(clearingSubmissionTable, "settlement_clearing_submission_id", "text"),
                PostgresSchemaColumn(clearingSubmissionTable, "settlement_obligation_id", "text"),
                PostgresSchemaColumn(clearingSubmissionTable, "settlement_affirmation_id", "text"),
                PostgresSchemaColumn(clearingSubmissionTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(clearingSubmissionTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(clearingSubmissionTable, "state", "text"),
                PostgresSchemaColumn(clearingAcceptanceTable, "settlement_clearing_acceptance_id", "text"),
                PostgresSchemaColumn(clearingAcceptanceTable, "settlement_clearing_submission_id", "text"),
                PostgresSchemaColumn(clearingAcceptanceTable, "settlement_obligation_id", "text"),
                PostgresSchemaColumn(clearingAcceptanceTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(clearingAcceptanceTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(clearingAcceptanceTable, "state", "text"),
                PostgresSchemaColumn(clearingRejectionTable, "settlement_clearing_rejection_id", "text"),
                PostgresSchemaColumn(clearingRejectionTable, "settlement_clearing_submission_id", "text"),
                PostgresSchemaColumn(clearingRejectionTable, "settlement_obligation_id", "text"),
                PostgresSchemaColumn(clearingRejectionTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(clearingRejectionTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(clearingRejectionTable, "reason", "text"),
                PostgresSchemaColumn(clearingRejectionTable, "state", "text"),
                PostgresSchemaColumn(novationTable, "settlement_novation_id", "text"),
                PostgresSchemaColumn(novationTable, "settlement_clearing_acceptance_id", "text"),
                PostgresSchemaColumn(novationTable, "settlement_obligation_id", "text"),
                PostgresSchemaColumn(novationTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(novationTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(novationTable, "state", "text"),
                PostgresSchemaColumn(instructionTable, "settlement_instruction_id", "text"),
                PostgresSchemaColumn(instructionTable, "settlement_obligation_id", "text"),
                PostgresSchemaColumn(instructionTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(instructionTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(instructionTable, "instruction_type", "text"),
                PostgresSchemaColumn(instructionTable, "state", "text"),
                PostgresSchemaColumn(attemptTable, "settlement_attempt_id", "text"),
                PostgresSchemaColumn(attemptTable, "settlement_obligation_id", "text"),
                PostgresSchemaColumn(attemptTable, "settlement_instruction_id", "text"),
                PostgresSchemaColumn(attemptTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(attemptTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(attemptTable, "attempt_number", "integer"),
                PostgresSchemaColumn(attemptTable, "state", "text"),
                PostgresSchemaColumn(legOutcomeTable, "settlement_leg_outcome_id", "text"),
                PostgresSchemaColumn(legOutcomeTable, "settlement_attempt_id", "text"),
                PostgresSchemaColumn(legOutcomeTable, "leg_type", "text"),
                PostgresSchemaColumn(legOutcomeTable, "state", "text"),
                PostgresSchemaColumn(ledgerEntryTable, "ledger_entry_id", "text"),
                PostgresSchemaColumn(ledgerEntryTable, "settlement_attempt_id", "text"),
                PostgresSchemaColumn(ledgerEntryTable, "asset_type", "text"),
                PostgresSchemaColumn(ledgerEntryTable, "asset_id", "text"),
                PostgresSchemaColumn(ledgerEntryTable, "direction", "text"),
                PostgresSchemaColumn(ledgerEntryTable, "quantity", "text"),
                PostgresSchemaColumn(settlementTable, "settlement_id", "text"),
                PostgresSchemaColumn(settlementTable, "settlement_attempt_id", "text"),
                PostgresSchemaColumn(settlementTable, "settlement_state", "text"),
                PostgresSchemaColumn(breakTable, "settlement_break_id", "text"),
                PostgresSchemaColumn(breakTable, "settlement_obligation_id", "text"),
                PostgresSchemaColumn(breakTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(breakTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(breakTable, "reason", "text"),
                PostgresSchemaColumn(repairTable, "settlement_repair_id", "text"),
                PostgresSchemaColumn(repairTable, "settlement_break_id", "text"),
                PostgresSchemaColumn(repairTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(repairTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(repairTable, "actor_id", "text"),
                PostgresSchemaColumn(resolutionTable, "settlement_resolution_id", "text"),
                PostgresSchemaColumn(resolutionTable, "settlement_repair_id", "text"),
                PostgresSchemaColumn(resolutionTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(resolutionTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(resolutionTable, "settlement_state", "text"),
                PostgresSchemaColumn(resolutionTable, "exception_state", "text"),
                PostgresSchemaColumn(operatorActionTable, "settlement_operator_action_id", "text"),
                PostgresSchemaColumn(operatorActionTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(operatorActionTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(operatorActionTable, "action", "text"),
                PostgresSchemaColumn(operatorActionTable, "target_id", "text"),
                PostgresSchemaColumn(operatorActionTable, "reason_note", "text"),
                PostgresSchemaColumn(operatorActionTable, "actor_id", "text")
            )
        )
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

    fun boundaryStreamCommandIntake(streamCommandIntake: String): PostgresSchemaRequirement {
        val table = PostgresSchemaObject.parse(streamCommandIntake)
        return PostgresSchemaRequirement(
            tables = listOf(table),
            columns = listOf(
                "scope",
                "client_id",
                "idempotency_key",
                "participant_id",
                "payload_hash",
                "command_id",
                "route",
                "subject",
                "stream_name",
                "partition",
                "stream_sequence",
                "published",
                "first_seen_at",
                "published_at"
            ).map { column -> PostgresSchemaColumn(table, column) }
        )
    }

    fun boundaryAccountRisk(
        accountRiskControls: String,
        accountRiskDecisions: String
    ): PostgresSchemaRequirement {
        val controls = PostgresSchemaObject.parse(accountRiskControls)
        val decisions = PostgresSchemaObject.parse(accountRiskDecisions)
        return PostgresSchemaRequirement(
            tables = listOf(controls, decisions),
            columns = listOf(
                PostgresSchemaColumn(controls, "scope_type", "text"),
                PostgresSchemaColumn(controls, "scope_id", "text"),
                PostgresSchemaColumn(controls, "decision", "text"),
                PostgresSchemaColumn(controls, "reason", "text"),
                PostgresSchemaColumn(controls, "max_quantity_units", "text"),
                PostgresSchemaColumn(controls, "max_notional", "text"),
                PostgresSchemaColumn(controls, "currency", "text"),
                PostgresSchemaColumn(controls, "updated_at", "timestamp with time zone"),
                PostgresSchemaColumn(decisions, "decision_id", "text"),
                PostgresSchemaColumn(decisions, "decided_at", "timestamp with time zone"),
                PostgresSchemaColumn(decisions, "decision", "text"),
                PostgresSchemaColumn(decisions, "code", "text"),
                PostgresSchemaColumn(decisions, "message", "text"),
                PostgresSchemaColumn(decisions, "client_id", "text"),
                PostgresSchemaColumn(decisions, "route", "text"),
                PostgresSchemaColumn(decisions, "command_type", "text"),
                PostgresSchemaColumn(decisions, "command_id", "text"),
                PostgresSchemaColumn(decisions, "idempotency_key", "text"),
                PostgresSchemaColumn(decisions, "correlation_id", "text"),
                PostgresSchemaColumn(decisions, "actor_id", "text"),
                PostgresSchemaColumn(decisions, "participant_id", "text"),
                PostgresSchemaColumn(decisions, "account_id", "text"),
                PostgresSchemaColumn(decisions, "bot_id", "text"),
                PostgresSchemaColumn(decisions, "run_id", "text"),
                PostgresSchemaColumn(decisions, "venue_session_id", "text"),
                PostgresSchemaColumn(decisions, "instrument_id", "text"),
                PostgresSchemaColumn(decisions, "order_id", "text"),
                PostgresSchemaColumn(decisions, "quantity_units", "text"),
                PostgresSchemaColumn(decisions, "limit_price", "text"),
                PostgresSchemaColumn(decisions, "currency", "text"),
                PostgresSchemaColumn(decisions, "payload_hash", "text")
            )
        )
    }

    fun boundaryCommandCircuitBreakers(commandCircuitBreakers: String): PostgresSchemaRequirement {
        val table = PostgresSchemaObject.parse(commandCircuitBreakers)
        return PostgresSchemaRequirement(
            tables = listOf(table),
            columns = listOf(
                PostgresSchemaColumn(table, "scope_type", "text"),
                PostgresSchemaColumn(table, "scope_id", "text"),
                PostgresSchemaColumn(table, "tripped", "boolean"),
                PostgresSchemaColumn(table, "reason", "text"),
                PostgresSchemaColumn(table, "updated_at", "timestamp with time zone")
            )
        )
    }

    fun boundaryInstrumentPriceCollars(instrumentPriceCollars: String): PostgresSchemaRequirement {
        val table = PostgresSchemaObject.parse(instrumentPriceCollars)
        return PostgresSchemaRequirement(
            tables = listOf(table),
            columns = listOf(
                PostgresSchemaColumn(table, "instrument_id", "text"),
                PostgresSchemaColumn(table, "min_price", "text"),
                PostgresSchemaColumn(table, "max_price", "text"),
                PostgresSchemaColumn(table, "currency", "text"),
                PostgresSchemaColumn(table, "reason", "text"),
                PostgresSchemaColumn(table, "updated_at", "timestamp with time zone")
            )
        )
    }

    fun boundaryRejections(boundaryRejections: String): PostgresSchemaRequirement {
        val table = PostgresSchemaObject.parse(boundaryRejections)
        return PostgresSchemaRequirement(
            tables = listOf(table),
            columns = listOf(
                PostgresSchemaColumn(table, "rejection_id", "text"),
                PostgresSchemaColumn(table, "rejected_at", "timestamp with time zone"),
                PostgresSchemaColumn(table, "guardrail_type", "text"),
                PostgresSchemaColumn(table, "scope_type", "text"),
                PostgresSchemaColumn(table, "scope_id", "text"),
                PostgresSchemaColumn(table, "status", "integer"),
                PostgresSchemaColumn(table, "code", "text"),
                PostgresSchemaColumn(table, "message", "text"),
                PostgresSchemaColumn(table, "client_id", "text"),
                PostgresSchemaColumn(table, "route", "text"),
                PostgresSchemaColumn(table, "command_type", "text"),
                PostgresSchemaColumn(table, "command_id", "text"),
                PostgresSchemaColumn(table, "idempotency_key", "text"),
                PostgresSchemaColumn(table, "correlation_id", "text"),
                PostgresSchemaColumn(table, "actor_id", "text"),
                PostgresSchemaColumn(table, "participant_id", "text"),
                PostgresSchemaColumn(table, "account_id", "text"),
                PostgresSchemaColumn(table, "bot_id", "text"),
                PostgresSchemaColumn(table, "run_id", "text"),
                PostgresSchemaColumn(table, "venue_session_id", "text"),
                PostgresSchemaColumn(table, "instrument_id", "text"),
                PostgresSchemaColumn(table, "order_id", "text"),
                PostgresSchemaColumn(table, "quantity_units", "text"),
                PostgresSchemaColumn(table, "limit_price", "text"),
                PostgresSchemaColumn(table, "currency", "text"),
                PostgresSchemaColumn(table, "payload_hash", "text")
            )
        )
    }

    fun commandLog(
        commands: String,
        payloads: String = "command_log.command_payloads",
        workQueue: String = "command_log.command_work_queue",
        results: String = "command_log.command_results",
        resultsArchive: String = "command_log.command_results_archive",
        retentionPins: String = "command_log.retention_pins",
        appendFunction: String = "command_log.command_append"
    ): PostgresSchemaRequirement {
        val commandTable = PostgresSchemaObject.parse(commands)
        val payloadTable = PostgresSchemaObject.parse(payloads)
        val queueTable = PostgresSchemaObject.parse(workQueue)
        val resultTable = PostgresSchemaObject.parse(results)
        val resultArchiveTable = PostgresSchemaObject.parse(resultsArchive)
        val retentionPinTable = PostgresSchemaObject.parse(retentionPins)
        return PostgresSchemaRequirement(
            tables = listOf(commandTable, payloadTable, queueTable, resultTable, resultArchiveTable, retentionPinTable),
            functions = listOf(PostgresSchemaObject.parse(appendFunction)),
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
                    "run_id",
                    "run_kind",
                    "scenario_id",
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
                    "payload_json",
                    "created_at"
                ).map { column -> PostgresSchemaColumn(payloadTable, column) },
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
                ).map { column -> PostgresSchemaColumn(resultTable, column) },
                listOf(
                    "command_id",
                    "status",
                    "attempt_count",
                    "last_error",
                    "response_status",
                    "response_payload_json",
                    "completed_at",
                    "archived_at"
                ).map { column -> PostgresSchemaColumn(resultArchiveTable, column) },
                listOf(
                    "pin_id",
                    "selector_type",
                    "selector_value",
                    "reason",
                    "created_at",
                    "updated_at"
                ).map { column -> PostgresSchemaColumn(retentionPinTable, column) }
            ).flatten()
        )
    }


    fun adminIdentity(
        users: String,
        roles: String,
        userRoles: String,
        auditEvents: String
    ): PostgresSchemaRequirement {
        val usersTable = PostgresSchemaObject.parse(users)
        val rolesTable = PostgresSchemaObject.parse(roles)
        val userRolesTable = PostgresSchemaObject.parse(userRoles)
        val auditTable = PostgresSchemaObject.parse(auditEvents)
        return PostgresSchemaRequirement(
            tables = listOf(
                usersTable,
                rolesTable,
                userRolesTable,
                auditTable
            ),
            columns = listOf(
                PostgresSchemaColumn(usersTable, "reef_user_id", "text"),
                PostgresSchemaColumn(usersTable, "github_user_id", "bigint"),
                PostgresSchemaColumn(usersTable, "github_login", "text"),
                PostgresSchemaColumn(usersTable, "display_name", "text"),
                PostgresSchemaColumn(usersTable, "trust_state", "text"),
                PostgresSchemaColumn(usersTable, "created_at", "timestamp with time zone"),
                PostgresSchemaColumn(usersTable, "last_seen_at", "timestamp with time zone"),
                PostgresSchemaColumn(usersTable, "updated_at", "timestamp with time zone"),
                PostgresSchemaColumn(rolesTable, "role_id", "text"),
                PostgresSchemaColumn(rolesTable, "description", "text"),
                PostgresSchemaColumn(userRolesTable, "reef_user_id", "text"),
                PostgresSchemaColumn(userRolesTable, "role_id", "text"),
                PostgresSchemaColumn(userRolesTable, "assigned_by", "text"),
                PostgresSchemaColumn(auditTable, "event_id", "text"),
                PostgresSchemaColumn(auditTable, "actor_id", "text"),
                PostgresSchemaColumn(auditTable, "event_type", "text"),
                PostgresSchemaColumn(auditTable, "target_type", "text"),
                PostgresSchemaColumn(auditTable, "target_id", "text")
            )
        )
    }

    fun adminAuth(
        oauthStates: String,
        sessions: String,
        serviceTokens: String
    ): PostgresSchemaRequirement {
        val oauthStatesTable = PostgresSchemaObject.parse(oauthStates)
        val sessionsTable = PostgresSchemaObject.parse(sessions)
        val serviceTokensTable = PostgresSchemaObject.parse(serviceTokens)
        return PostgresSchemaRequirement(
            tables = listOf(
                oauthStatesTable,
                sessionsTable,
                serviceTokensTable
            ),
            columns = listOf(
                PostgresSchemaColumn(oauthStatesTable, "state_hash", "text"),
                PostgresSchemaColumn(oauthStatesTable, "provider", "text"),
                PostgresSchemaColumn(oauthStatesTable, "redirect_path", "text"),
                PostgresSchemaColumn(oauthStatesTable, "expires_at", "timestamp with time zone"),
                PostgresSchemaColumn(oauthStatesTable, "consumed_at", "timestamp with time zone"),
                PostgresSchemaColumn(sessionsTable, "session_hash", "text"),
                PostgresSchemaColumn(sessionsTable, "reef_user_id", "text"),
                PostgresSchemaColumn(sessionsTable, "auth_provider", "text"),
                PostgresSchemaColumn(sessionsTable, "expires_at", "timestamp with time zone"),
                PostgresSchemaColumn(sessionsTable, "last_seen_at", "timestamp with time zone"),
                PostgresSchemaColumn(sessionsTable, "revoked_at", "timestamp with time zone"),
                PostgresSchemaColumn(serviceTokensTable, "token_id", "text"),
                PostgresSchemaColumn(serviceTokensTable, "token_hash", "text"),
                PostgresSchemaColumn(serviceTokensTable, "token_family", "text"),
                PostgresSchemaColumn(serviceTokensTable, "subject_actor_id", "text"),
                PostgresSchemaColumn(serviceTokensTable, "expires_at", "timestamp with time zone"),
                PostgresSchemaColumn(serviceTokensTable, "last_used_at", "timestamp with time zone"),
                PostgresSchemaColumn(serviceTokensTable, "revoked_at", "timestamp with time zone")
            )
        )
    }

    fun analyticsRunExports(simulationRunExports: String): PostgresSchemaRequirement {
        val table = PostgresSchemaObject.parse(simulationRunExports)
        return PostgresSchemaRequirement(
            tables = listOf(table),
            columns = listOf(
                PostgresSchemaColumn(table, "run_id", "text"),
                PostgresSchemaColumn(table, "scenario_id", "text"),
                PostgresSchemaColumn(table, "run_kind", "text"),
                PostgresSchemaColumn(table, "source", "text"),
                PostgresSchemaColumn(table, "git_sha", "text"),
                PostgresSchemaColumn(table, "profile", "text"),
                PostgresSchemaColumn(table, "started_at", "timestamp with time zone"),
                PostgresSchemaColumn(table, "completed_at", "timestamp with time zone"),
                PostgresSchemaColumn(table, "exported_at", "timestamp with time zone"),
                PostgresSchemaColumn(table, "status", "text"),
                PostgresSchemaColumn(table, "attempted_count", "bigint"),
                PostgresSchemaColumn(table, "accepted_count", "bigint"),
                PostgresSchemaColumn(table, "completed_count", "bigint"),
                PostgresSchemaColumn(table, "materialized_count", "bigint"),
                PostgresSchemaColumn(table, "projected_count", "bigint"),
                PostgresSchemaColumn(table, "failed_count", "bigint"),
                PostgresSchemaColumn(table, "p50_latency_ms", "double precision"),
                PostgresSchemaColumn(table, "p95_latency_ms", "double precision"),
                PostgresSchemaColumn(table, "p99_latency_ms", "double precision"),
                PostgresSchemaColumn(table, "artifact_manifest", "jsonb"),
                PostgresSchemaColumn(table, "summary", "jsonb")
            )
        )
    }

    fun analyticsRunBotPerformanceSummaries(runBotPerformanceSummaries: String): PostgresSchemaRequirement {
        val table = PostgresSchemaObject.parse(runBotPerformanceSummaries)
        return PostgresSchemaRequirement(
            tables = listOf(table),
            columns = listOf(
                PostgresSchemaColumn(table, "run_id", "text"),
                PostgresSchemaColumn(table, "bot_id", "text"),
                PostgresSchemaColumn(table, "scenario_id", "text"),
                PostgresSchemaColumn(table, "profile", "text"),
                PostgresSchemaColumn(table, "source", "text"),
                PostgresSchemaColumn(table, "completed_at", "timestamp with time zone"),
                PostgresSchemaColumn(table, "exported_at", "timestamp with time zone"),
                PostgresSchemaColumn(table, "projected_at", "timestamp with time zone"),
                PostgresSchemaColumn(table, "final_equity", "double precision"),
                PostgresSchemaColumn(table, "realized_pnl", "double precision"),
                PostgresSchemaColumn(table, "max_drawdown", "double precision"),
                PostgresSchemaColumn(table, "fail_count", "bigint"),
                PostgresSchemaColumn(table, "command_count", "bigint"),
                PostgresSchemaColumn(table, "settlement_score_summary", "jsonb"),
                PostgresSchemaColumn(table, "source_summary", "jsonb")
            )
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

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
        val canonicalCommandResults = PostgresSchemaObject.parse(names.canonicalCommandResults)
        val canonicalVenueEvents = PostgresSchemaObject.parse(names.canonicalVenueEvents)
        val canonicalVenueEventBatches = PostgresSchemaObject.parse(names.canonicalVenueEventBatches)
        val canonicalCommandOutcomes = PostgresSchemaObject.parse(names.canonicalCommandOutcomes)
        val projectionWatermarks = PostgresSchemaObject.parse(names.projectionWatermarks)
        val marketDataSnapshots = PostgresSchemaObject.parse(names.marketDataSnapshots)
        val orderLifecycleState = PostgresSchemaObject.parse(names.orderLifecycleState)
        val orders = PostgresSchemaObject.parse(names.orders)
        val executions = PostgresSchemaObject.parse(names.executions)
        val trades = PostgresSchemaObject.parse(names.trades)
        val submitResults = PostgresSchemaObject.parse(names.submitResults)
        val postTradeProfiles = PostgresSchemaObject.parse(names.adminPostTradeProfiles)
        val venueSessions = PostgresSchemaObject.parse(names.referenceVenueSessions)
        return PostgresSchemaRequirement(
            tables = listOf(
                names.referenceInstruments,
                names.referenceParticipants,
                names.referenceAccounts,
                names.referenceVenueSessions,
                names.orders,
                names.executions,
                names.trades,
                names.runtimeEvents,
                names.runtimeTraceSequences,
                names.submitResults,
                names.canonicalCommandResults,
                names.canonicalVenueEvents,
                names.canonicalVenueEventBatches,
                names.canonicalCommandOutcomes,
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
                PostgresSchemaColumn(executions, "occurred_at", "text"),
                PostgresSchemaColumn(executions, "occurred_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(trades, "event_id", "text"),
                PostgresSchemaColumn(trades, "event_id_uuid", "uuid"),
                PostgresSchemaColumn(trades, "quantity_units_num", "numeric"),
                PostgresSchemaColumn(trades, "price_num", "numeric"),
                PostgresSchemaColumn(trades, "occurred_at", "text"),
                PostgresSchemaColumn(trades, "occurred_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(runtimeEvents, "event_id", "text"),
                PostgresSchemaColumn(runtimeEvents, "event_id_uuid", "uuid"),
                PostgresSchemaColumn(runtimeEvents, "occurred_at", "text"),
                PostgresSchemaColumn(runtimeEvents, "occurred_at_ts", "timestamp with time zone"),
                PostgresSchemaColumn(runtimeEvents, "actor_id", "text"),
                PostgresSchemaColumn(runtimeEvents, "payload_json", "jsonb"),
                PostgresSchemaColumn(runtimeEvents, "sequence_number", "bigint"),
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
                PostgresSchemaColumn(canonicalCommandOutcomes, "command_id", "text"),
                PostgresSchemaColumn(canonicalCommandOutcomes, "stream_sequence", "bigint"),
                PostgresSchemaColumn(canonicalCommandOutcomes, "result_payload", "jsonb"),
                PostgresSchemaColumn(canonicalCommandOutcomes, "occurred_at_ts", "timestamp with time zone"),
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
                PostgresSchemaColumn(venueSessions, "venue_session_id", "text"),
                PostgresSchemaColumn(venueSessions, "post_trade_profile_id", "text")
            )
        )
    }

    fun boundaryIdempotency(idempotencyRecords: String): PostgresSchemaRequirement {
        return PostgresSchemaRequirement(tables = listOf(PostgresSchemaObject.parse(idempotencyRecords)))
    }

    fun settlementFacts(
        obligations: String,
        breaks: String,
        repairs: String,
        resolutions: String
    ): PostgresSchemaRequirement {
        val obligationTable = PostgresSchemaObject.parse(obligations)
        val breakTable = PostgresSchemaObject.parse(breaks)
        val repairTable = PostgresSchemaObject.parse(repairs)
        val resolutionTable = PostgresSchemaObject.parse(resolutions)
        return PostgresSchemaRequirement(
            tables = listOf(obligationTable, breakTable, repairTable, resolutionTable),
            columns = listOf(
                PostgresSchemaColumn(obligationTable, "settlement_obligation_id", "text"),
                PostgresSchemaColumn(obligationTable, "scenario_run_id", "text"),
                PostgresSchemaColumn(obligationTable, "post_trade_profile_id", "text"),
                PostgresSchemaColumn(obligationTable, "post_trade_policy_version", "integer"),
                PostgresSchemaColumn(obligationTable, "trade_id", "text"),
                PostgresSchemaColumn(obligationTable, "occurred_at", "timestamp with time zone"),
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
                PostgresSchemaColumn(resolutionTable, "exception_state", "text")
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
                "idempotency_key",
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
        retentionPins: String = "command_log.retention_pins",
        appendFunction: String = "command_log.command_append"
    ): PostgresSchemaRequirement {
        val commandTable = PostgresSchemaObject.parse(commands)
        val payloadTable = PostgresSchemaObject.parse(payloads)
        val queueTable = PostgresSchemaObject.parse(workQueue)
        val resultTable = PostgresSchemaObject.parse(results)
        val retentionPinTable = PostgresSchemaObject.parse(retentionPins)
        return PostgresSchemaRequirement(
            tables = listOf(commandTable, payloadTable, queueTable, resultTable, retentionPinTable),
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

    fun arenaRegistry(
        bots: String,
        botVersions: String,
        qualificationReports: String,
        qualificationReportIssues: String,
        operatorDecisions: String,
        runRecords: String,
        runBotVersions: String,
        runBotResults: String,
        runEnforcementEvents: String,
        runtimeConfigDescriptors: String
    ): PostgresSchemaRequirement {
        val botsTable = PostgresSchemaObject.parse(bots)
        val versionsTable = PostgresSchemaObject.parse(botVersions)
        val reportsTable = PostgresSchemaObject.parse(qualificationReports)
        val reportIssuesTable = PostgresSchemaObject.parse(qualificationReportIssues)
        val decisionsTable = PostgresSchemaObject.parse(operatorDecisions)
        val runsTable = PostgresSchemaObject.parse(runRecords)
        val runBotsTable = PostgresSchemaObject.parse(runBotVersions)
        val runResultsTable = PostgresSchemaObject.parse(runBotResults)
        val enforcementEventsTable = PostgresSchemaObject.parse(runEnforcementEvents)
        val configTable = PostgresSchemaObject.parse(runtimeConfigDescriptors)
        return PostgresSchemaRequirement(
            tables = listOf(
                botsTable,
                versionsTable,
                reportsTable,
                reportIssuesTable,
                decisionsTable,
                runsTable,
                runBotsTable,
                runResultsTable,
                enforcementEventsTable,
                configTable
            ),
            columns = listOf(
                listOf(
                    PostgresSchemaColumn(botsTable, "bot_id", "text"),
                    PostgresSchemaColumn(botsTable, "file_name", "text"),
                    PostgresSchemaColumn(botsTable, "email", "text"),
                    PostgresSchemaColumn(versionsTable, "bot_id", "text"),
                    PostgresSchemaColumn(versionsTable, "version_id", "text"),
                    PostgresSchemaColumn(versionsTable, "status", "text"),
                    PostgresSchemaColumn(reportsTable, "report_id", "text"),
                    PostgresSchemaColumn(reportsTable, "policy_version", "text"),
                    PostgresSchemaColumn(reportIssuesTable, "issue", "text"),
                    PostgresSchemaColumn(decisionsTable, "actor_id", "text"),
                    PostgresSchemaColumn(decisionsTable, "to_status", "text"),
                    PostgresSchemaColumn(runsTable, "run_id", "text"),
                    PostgresSchemaColumn(runsTable, "seed", "bigint"),
                    PostgresSchemaColumn(runsTable, "policy_version", "text"),
                    PostgresSchemaColumn(runBotsTable, "bot_order", "integer"),
                    PostgresSchemaColumn(runResultsTable, "run_id", "text"),
                    PostgresSchemaColumn(runResultsTable, "bot_id", "text"),
                    PostgresSchemaColumn(runResultsTable, "version_id", "text"),
                    PostgresSchemaColumn(runResultsTable, "scoring_policy_version", "text"),
                    PostgresSchemaColumn(runResultsTable, "final_equity", "bigint"),
                    PostgresSchemaColumn(runResultsTable, "realized_pnl", "bigint"),
                    PostgresSchemaColumn(runResultsTable, "max_drawdown", "bigint"),
                    PostgresSchemaColumn(runResultsTable, "actions_proposed", "integer"),
                    PostgresSchemaColumn(runResultsTable, "order_actions_proposed", "integer"),
                    PostgresSchemaColumn(runResultsTable, "data_calls", "integer"),
                    PostgresSchemaColumn(runResultsTable, "signals_generated", "integer"),
                    PostgresSchemaColumn(runResultsTable, "disqualified", "boolean"),
                    PostgresSchemaColumn(runResultsTable, "score_eligible", "boolean"),
                    PostgresSchemaColumn(runResultsTable, "public_leaderboard", "boolean"),
                    PostgresSchemaColumn(runResultsTable, "created_at", "timestamp with time zone"),
                    PostgresSchemaColumn(enforcementEventsTable, "decision", "text"),
                    PostgresSchemaColumn(enforcementEventsTable, "reason_code", "text"),
                    PostgresSchemaColumn(enforcementEventsTable, "counters_json", "text"),
                    PostgresSchemaColumn(configTable, "config_key", "text"),
                    PostgresSchemaColumn(configTable, "provider", "text"),
                    PostgresSchemaColumn(configTable, "secret_path", "text"),
                    PostgresSchemaColumn(configTable, "required", "boolean")
                )
            ).flatten()
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

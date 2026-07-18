package com.reef.arena.controlplane.arena

import com.reef.platform.infrastructure.persistence.PostgresSchemaColumn
import com.reef.platform.infrastructure.persistence.PostgresSchemaObject
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirement

object ArenaPostgresSchemaRequirements {
    fun registry(names: PostgresArenaSqlNames): PostgresSchemaRequirement {
        val botsTable = PostgresSchemaObject.parse(names.bots)
        val versionsTable = PostgresSchemaObject.parse(names.botVersions)
        val reportsTable = PostgresSchemaObject.parse(names.qualificationReports)
        val reportIssuesTable = PostgresSchemaObject.parse(names.qualificationReportIssues)
        val decisionsTable = PostgresSchemaObject.parse(names.operatorDecisions)
        val runsTable = PostgresSchemaObject.parse(names.runRecords)
        val runBotsTable = PostgresSchemaObject.parse(names.runBotVersions)
        val runResultsTable = PostgresSchemaObject.parse(names.runBotResults)
        val enforcementEventsTable = PostgresSchemaObject.parse(names.runEnforcementEvents)
        val configTable = PostgresSchemaObject.parse(names.runtimeConfigDescriptors)
        return PostgresSchemaRequirement(
            tables = listOf(botsTable, versionsTable, reportsTable, reportIssuesTable, decisionsTable, runsTable, runBotsTable, runResultsTable, enforcementEventsTable, configTable),
            columns = listOf(
                PostgresSchemaColumn(botsTable, "bot_id", "text"), PostgresSchemaColumn(botsTable, "file_name", "text"), PostgresSchemaColumn(botsTable, "email", "text"),
                PostgresSchemaColumn(versionsTable, "bot_id", "text"), PostgresSchemaColumn(versionsTable, "version_id", "text"), PostgresSchemaColumn(versionsTable, "status", "text"),
                PostgresSchemaColumn(reportsTable, "report_id", "text"), PostgresSchemaColumn(reportsTable, "policy_version", "text"), PostgresSchemaColumn(reportIssuesTable, "issue", "text"),
                PostgresSchemaColumn(decisionsTable, "actor_id", "text"), PostgresSchemaColumn(decisionsTable, "to_status", "text"),
                PostgresSchemaColumn(runsTable, "run_id", "text"), PostgresSchemaColumn(runsTable, "seed", "bigint"), PostgresSchemaColumn(runsTable, "policy_version", "text"),
                PostgresSchemaColumn(runBotsTable, "bot_order", "integer"),
                PostgresSchemaColumn(runResultsTable, "run_id", "text"), PostgresSchemaColumn(runResultsTable, "bot_id", "text"), PostgresSchemaColumn(runResultsTable, "version_id", "text"), PostgresSchemaColumn(runResultsTable, "scoring_policy_version", "text"),
                PostgresSchemaColumn(runResultsTable, "final_equity", "bigint"), PostgresSchemaColumn(runResultsTable, "realized_pnl", "bigint"), PostgresSchemaColumn(runResultsTable, "max_drawdown", "bigint"),
                PostgresSchemaColumn(runResultsTable, "actions_proposed", "integer"), PostgresSchemaColumn(runResultsTable, "order_actions_proposed", "integer"), PostgresSchemaColumn(runResultsTable, "data_calls", "integer"), PostgresSchemaColumn(runResultsTable, "signals_generated", "integer"),
                PostgresSchemaColumn(runResultsTable, "disqualified", "boolean"), PostgresSchemaColumn(runResultsTable, "score_eligible", "boolean"), PostgresSchemaColumn(runResultsTable, "public_leaderboard", "boolean"), PostgresSchemaColumn(runResultsTable, "created_at", "timestamp with time zone"),
                PostgresSchemaColumn(enforcementEventsTable, "decision", "text"), PostgresSchemaColumn(enforcementEventsTable, "reason_code", "text"), PostgresSchemaColumn(enforcementEventsTable, "counters_json", "text"),
                PostgresSchemaColumn(configTable, "config_key", "text"), PostgresSchemaColumn(configTable, "provider", "text"), PostgresSchemaColumn(configTable, "secret_path", "text"), PostgresSchemaColumn(configTable, "required", "boolean")
            )
        )
    }
}

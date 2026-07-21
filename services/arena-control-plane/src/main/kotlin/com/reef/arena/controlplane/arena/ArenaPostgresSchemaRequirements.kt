package com.reef.arena.controlplane.arena

import com.reef.platform.infrastructure.persistence.PostgresSchemaColumn
import com.reef.platform.infrastructure.persistence.PostgresSchemaObject
import com.reef.platform.infrastructure.persistence.PostgresSchemaRequirement

object ArenaPostgresSchemaRequirements {
    fun entitlements(names: PostgresArenaSqlNames): PostgresSchemaRequirement {
        val ownershipsTable = PostgresSchemaObject.parse(names.userBotOwnerships)
        return PostgresSchemaRequirement(
            tables = listOf(ownershipsTable),
            columns = listOf(
                PostgresSchemaColumn(ownershipsTable, "reef_user_id", "text"),
                PostgresSchemaColumn(ownershipsTable, "bot_id", "text"),
                PostgresSchemaColumn(ownershipsTable, "ownership_state", "text"),
                PostgresSchemaColumn(ownershipsTable, "assigned_by", "text"),
                PostgresSchemaColumn(ownershipsTable, "assigned_at", "timestamp with time zone")
            )
        )
    }

    fun submissionAdmissions(names: PostgresArenaSqlNames): PostgresSchemaRequirement {
        val admissionsTable = PostgresSchemaObject.parse(names.submissionAdmissions)
        return PostgresSchemaRequirement(
            tables = listOf(admissionsTable),
            columns = listOf(
                PostgresSchemaColumn(admissionsTable, "repository", "text"),
                PostgresSchemaColumn(admissionsTable, "pull_request_number", "bigint"),
                PostgresSchemaColumn(admissionsTable, "bot_id", "text"),
                PostgresSchemaColumn(admissionsTable, "head_repository", "text"),
                PostgresSchemaColumn(admissionsTable, "head_owner_login", "text"),
                PostgresSchemaColumn(admissionsTable, "github_user_id", "bigint"),
                PostgresSchemaColumn(admissionsTable, "github_login", "text"),
                PostgresSchemaColumn(admissionsTable, "head_sha", "text"),
                PostgresSchemaColumn(admissionsTable, "state", "text"),
                PostgresSchemaColumn(admissionsTable, "invitation_actor", "text"),
                PostgresSchemaColumn(admissionsTable, "invitation_reason", "text"),
                PostgresSchemaColumn(admissionsTable, "invited_at", "timestamp with time zone"),
                PostgresSchemaColumn(admissionsTable, "created_at", "timestamp with time zone"),
                PostgresSchemaColumn(admissionsTable, "updated_at", "timestamp with time zone")
            )
        )
    }

    fun runAdmission(names: PostgresArenaSqlNames): PostgresSchemaRequirement {
        val windowsTable = PostgresSchemaObject.parse(names.admissionWindows)
        val decisionsTable = PostgresSchemaObject.parse(names.eligibilityDecisions)
        val reasonsTable = PostgresSchemaObject.parse(names.eligibilityDecisionReasons)
        val snapshotsTable = PostgresSchemaObject.parse(names.rosterSnapshots)
        val entriesTable = PostgresSchemaObject.parse(names.rosterSnapshotEntries)
        val removalsTable = PostgresSchemaObject.parse(names.rosterRemovals)
        return PostgresSchemaRequirement(
            tables = listOf(windowsTable, decisionsTable, reasonsTable, snapshotsTable, entriesTable, removalsTable),
            columns = listOf(
                PostgresSchemaColumn(windowsTable, "window_id", "text"),
                PostgresSchemaColumn(windowsTable, "scheduled_start", "timestamp with time zone"),
                PostgresSchemaColumn(windowsTable, "roster_lock_at", "timestamp with time zone"),
                PostgresSchemaColumn(decisionsTable, "evaluation_id", "text"),
                PostgresSchemaColumn(decisionsTable, "outcome", "text"),
                PostgresSchemaColumn(decisionsTable, "source_hash", "text"),
                PostgresSchemaColumn(decisionsTable, "artifact_hash", "text"),
                PostgresSchemaColumn(decisionsTable, "config_hash", "text"),
                PostgresSchemaColumn(reasonsTable, "reason_order", "integer"),
                PostgresSchemaColumn(reasonsTable, "reason_code", "text"),
                PostgresSchemaColumn(snapshotsTable, "snapshot_id", "text"),
                PostgresSchemaColumn(snapshotsTable, "snapshot_hash", "text"),
                PostgresSchemaColumn(snapshotsTable, "max_bots", "integer"),
                PostgresSchemaColumn(entriesTable, "bot_order", "integer"),
                PostgresSchemaColumn(entriesTable, "eligibility_evaluation_id", "text"),
                PostgresSchemaColumn(removalsTable, "removal_id", "text"),
                PostgresSchemaColumn(removalsTable, "reason_code", "text"),
                PostgresSchemaColumn(removalsTable, "removed_at", "timestamp with time zone")
            )
        )
    }

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

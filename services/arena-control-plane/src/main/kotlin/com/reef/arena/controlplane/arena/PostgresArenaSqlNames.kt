package com.reef.arena.controlplane.arena

data class PostgresArenaSqlNames(
    private val schema: String = "arena"
) {
    val schemaName = schemaOrDefault(schema)
    val bots = qualify("bots")
    val botVersions = qualify("bot_versions")
    val qualificationReports = qualify("qualification_reports")
    val qualificationReportIssues = qualify("qualification_report_issues")
    val operatorDecisions = qualify("operator_decisions")
    val runRecords = qualify("run_records")
    val runBotVersions = qualify("run_bot_versions")
    val runBotResults = qualify("run_bot_results")
    val runEnforcementEvents = qualify("run_enforcement_events")
    val runtimeConfigDescriptors = qualify("runtime_config_descriptors")

    private fun schemaOrDefault(schema: String): String {
        val candidate = schema.trim().ifBlank { "arena" }
        require(candidate.matches(IdentifierPattern)) { "Postgres schema name must be a simple identifier: $candidate" }
        return candidate
    }

    private fun qualify(name: String): String = "$schemaName.$name"

    private companion object {
        val IdentifierPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

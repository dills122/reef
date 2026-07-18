package com.reef.arena.controlplane.arena

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class PostgresArenaSqlNamesTest {
    @Test
    fun defaultsArenaTablesToArenaSchema() {
        val names = PostgresArenaSqlNames()

        assertEquals("arena.bots", names.bots)
        assertEquals("arena.bot_versions", names.botVersions)
        assertEquals("arena.qualification_reports", names.qualificationReports)
        assertEquals("arena.qualification_report_issues", names.qualificationReportIssues)
        assertEquals("arena.operator_decisions", names.operatorDecisions)
        assertEquals("arena.run_records", names.runRecords)
        assertEquals("arena.run_bot_versions", names.runBotVersions)
        assertEquals("arena.run_bot_results", names.runBotResults)
        assertEquals("arena.run_enforcement_events", names.runEnforcementEvents)
        assertEquals("arena.runtime_config_descriptors", names.runtimeConfigDescriptors)
    }

    @Test
    fun blankSchemaNameFallsBackToArenaSchema() {
        val names = PostgresArenaSqlNames(schema = "")

        assertFalse(names.bots.startsWith("."))
        assertFalse(names.bots.endsWith("."))
    }

    @Test
    fun rejectsUnsafeSchemaName() {
        assertFailsWith<IllegalArgumentException> {
            PostgresArenaSqlNames(schema = "arena;drop schema runtime")
        }
    }
}

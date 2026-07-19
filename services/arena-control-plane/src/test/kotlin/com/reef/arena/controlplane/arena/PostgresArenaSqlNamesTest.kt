package com.reef.arena.controlplane.arena

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
        assertEquals("arena.user_bot_ownerships", names.userBotOwnerships)
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

    @Test
    fun registryRequirementsCoverArenaControlPlaneObjects() {
        val requirements = ArenaPostgresSchemaRequirements.registry(PostgresArenaSqlNames())

        assertEquals(
            setOf(
                "arena.bots", "arena.bot_versions", "arena.qualification_reports", "arena.qualification_report_issues",
                "arena.operator_decisions", "arena.run_records", "arena.run_bot_versions", "arena.run_bot_results",
                "arena.run_enforcement_events", "arena.runtime_config_descriptors"
            ),
            requirements.tables.map { it.qualifiedName }.toSet()
        )
        assertTrue(requirements.columns.map { "${it.qualifiedName}:${it.expectedDataType}" }.containsAll(
            setOf(
                "arena.bot_versions.status:text", "arena.run_records.seed:bigint", "arena.run_bot_results.final_equity:bigint",
                "arena.run_bot_results.disqualified:boolean", "arena.run_bot_results.score_eligible:boolean",
                "arena.run_bot_results.public_leaderboard:boolean", "arena.run_enforcement_events.reason_code:text",
                "arena.runtime_config_descriptors.secret_path:text"
            )
        ))
    }

    @Test
    fun entitlementRequirementsCoverArenaOwnedEntitlementObjects() {
        val requirements = ArenaPostgresSchemaRequirements.entitlements(PostgresArenaSqlNames())

        assertEquals(
            setOf("arena.user_bot_ownerships"),
            requirements.tables.map { it.qualifiedName }.toSet()
        )
        assertTrue(requirements.columns.map { "${it.qualifiedName}:${it.expectedDataType}" }.containsAll(
            setOf(
                "arena.user_bot_ownerships.bot_id:text",
                "arena.user_bot_ownerships.ownership_state:text"
            )
        ))
    }
}

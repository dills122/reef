package com.reef.platform.application.analytics

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulationRunExportStoreTest {
    @Test
    fun ingestRejectsBlankRunId() {
        val service = SimulationRunExportService(InMemorySimulationRunExportStore())
        assertFailsWith<IllegalArgumentException> {
            service.ingest(SimulationRunExportCommand(runId = "  "))
        }
    }

    @Test
    fun ingestAppliesDefaultsAndClampsNegativeCounts() {
        val fixedNow = Instant.parse("2026-07-07T12:00:00Z")
        val service = SimulationRunExportService(InMemorySimulationRunExportStore()) { fixedNow }

        val record = service.ingest(
            SimulationRunExportCommand(
                runId = "run-1",
                attemptedCount = -5L,
                acceptedCount = -1L,
                completedCount = -2L,
                materializedCount = -3L,
                projectedCount = -4L,
                failedCount = -6L,
                artifactManifestJson = "  ",
                summaryJson = ""
            )
        )

        assertEquals("run-1", record.runId)
        assertEquals(fixedNow, record.exportedAt)
        assertEquals(0L, record.attemptedCount)
        assertEquals(0L, record.acceptedCount)
        assertEquals(0L, record.completedCount)
        assertEquals(0L, record.materializedCount)
        assertEquals(0L, record.projectedCount)
        assertEquals(0L, record.failedCount)
        assertEquals("[]", record.artifactManifestJson)
        assertEquals("{}", record.summaryJson)
    }

    @Test
    fun ingestPreservesExplicitExportedAtAndPositiveCounts() {
        val explicitExportedAt = Instant.parse("2026-01-01T00:00:00Z")
        val service = SimulationRunExportService(InMemorySimulationRunExportStore())

        val record = service.ingest(
            SimulationRunExportCommand(
                runId = "run-2",
                scenarioId = "scenario-1",
                exportedAt = explicitExportedAt,
                attemptedCount = 10L,
                acceptedCount = 9L,
                artifactManifestJson = "[\"a\"]",
                summaryJson = "{\"ok\":true}"
            )
        )

        assertEquals(explicitExportedAt, record.exportedAt)
        assertEquals("scenario-1", record.scenarioId)
        assertEquals(10L, record.attemptedCount)
        assertEquals(9L, record.acceptedCount)
        assertEquals("[\"a\"]", record.artifactManifestJson)
        assertEquals("{\"ok\":true}", record.summaryJson)
    }

    @Test
    fun ingestUpsertsIntoStoreAndFindReturnsRecord() {
        val service = SimulationRunExportService(InMemorySimulationRunExportStore())
        service.ingest(SimulationRunExportCommand(runId = "run-3"))

        val found = service.find("run-3")
        assertEquals("run-3", found?.runId)
        assertNull(service.find("does-not-exist"))
    }

    @Test
    fun listClampsLimitAndOrdersByCompletionThenExportRecency() {
        val store = InMemorySimulationRunExportStore()
        val service = SimulationRunExportService(store)

        service.ingest(
            SimulationRunExportCommand(
                runId = "run-early",
                completedAt = Instant.parse("2026-01-01T00:00:00Z")
            )
        )
        service.ingest(
            SimulationRunExportCommand(
                runId = "run-late",
                completedAt = Instant.parse("2026-02-01T00:00:00Z")
            )
        )
        service.ingest(
            SimulationRunExportCommand(
                runId = "run-no-completion",
                exportedAt = Instant.parse("2026-03-01T00:00:00Z")
            )
        )

        val listed = service.list(2)
        assertEquals(2, listed.size)
        assertEquals("run-no-completion", listed[0].runId)
        assertEquals("run-late", listed[1].runId)
    }

    @Test
    fun listClampsToAllowedRange() {
        val store = InMemorySimulationRunExportStore()
        repeat(3) { i -> store.upsert(minimalRecord("run-$i")) }

        assertEquals(1, store.list(0).size)
        assertEquals(3, store.list(1000).size.coerceAtMost(500))
    }

    @Test
    fun inMemoryStoreUpsertOverwritesExistingRecord() {
        val store = InMemorySimulationRunExportStore()
        store.upsert(minimalRecord("run-x").copy(status = "PENDING"))
        store.upsert(minimalRecord("run-x").copy(status = "DONE"))

        assertEquals("DONE", store.find("run-x")?.status)
        assertEquals(1, store.list(50).size)
    }

    @Test
    fun analyticsSqlNamesDefaultsToAnalyticsSchema() {
        val names = PostgresAnalyticsSqlNames()
        assertEquals("analytics", names.schemaName)
        assertEquals("analytics.simulation_run_exports", names.simulationRunExports)
    }

    @Test
    fun analyticsSqlNamesQualifiesCustomSchema() {
        val names = PostgresAnalyticsSqlNames(schema = "custom_schema")
        assertEquals("custom_schema", names.schemaName)
        assertEquals("custom_schema.simulation_run_exports", names.simulationRunExports)
    }

    @Test
    fun analyticsSqlNamesFallsBackToDefaultWhenBlank() {
        val names = PostgresAnalyticsSqlNames(schema = "   ")
        assertEquals("analytics", names.schemaName)
    }

    @Test
    fun analyticsSqlNamesRejectsInvalidIdentifiers() {
        val error = assertFailsWith<IllegalArgumentException> {
            PostgresAnalyticsSqlNames(schema = "bad schema;drop table")
        }
        assertTrue(error.message?.contains("simple identifier") == true)
    }

    private fun minimalRecord(runId: String): SimulationRunExportRecord {
        return SimulationRunExportRecord(
            runId = runId,
            scenarioId = "",
            runKind = "",
            source = "",
            gitSha = "",
            profile = "",
            startedAt = null,
            completedAt = null,
            exportedAt = Instant.parse("2026-07-07T00:00:00Z"),
            status = "",
            attemptedCount = 0,
            acceptedCount = 0,
            completedCount = 0,
            materializedCount = 0,
            projectedCount = 0,
            failedCount = 0,
            p50LatencyMs = null,
            p95LatencyMs = null,
            p99LatencyMs = null,
            artifactManifestJson = "[]",
            summaryJson = "{}"
        )
    }
}

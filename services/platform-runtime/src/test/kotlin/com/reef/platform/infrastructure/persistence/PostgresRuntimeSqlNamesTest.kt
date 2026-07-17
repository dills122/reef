package com.reef.platform.infrastructure.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class PostgresRuntimeSqlNamesTest {
    @Test
    fun defaultsRuntimeTablesToRuntimeSchemaAndAuthTablesToAuthSchema() {
        val names = PostgresRuntimeSqlNames()

        assertEquals("runtime.orders", names.orders)
        assertEquals("runtime.executions", names.executions)
        assertEquals("runtime.trades", names.trades)
        assertEquals("runtime.trades_archive", names.tradesArchive)
        assertEquals("runtime.trades_archive_default", names.tradesArchiveDefault)
        assertEquals("runtime.runtime_events", names.runtimeEvents)
        assertEquals("runtime.runtime_events_archive", names.runtimeEventsArchive)
        assertEquals("runtime.runtime_events_archive_default", names.runtimeEventsArchiveDefault)
        assertEquals("runtime.submit_results", names.submitResults)
        assertEquals("runtime.order_lifecycle_state", names.orderLifecycleState)
        assertEquals("runtime.market_data_snapshots", names.marketDataSnapshots)
        assertEquals("runtime.canonical_command_results", names.canonicalCommandResults)
        assertEquals("runtime.canonical_venue_events", names.canonicalVenueEvents)
        assertEquals("runtime.canonical_venue_event_batches", names.canonicalVenueEventBatches)
        assertEquals("runtime.canonical_venue_event_batches_archive", names.canonicalVenueEventBatchesArchive)
        assertEquals("runtime.canonical_venue_event_batches_archive_default", names.canonicalVenueEventBatchesArchiveDefault)
        assertEquals("runtime.canonical_command_outcomes", names.canonicalCommandOutcomes)
        assertEquals("runtime.canonical_command_outcomes_archive", names.canonicalCommandOutcomesArchive)
        assertEquals("runtime.canonical_command_outcomes_archive_default", names.canonicalCommandOutcomesArchiveDefault)
        assertEquals("runtime.projection_watermarks", names.projectionWatermarks)
        assertEquals("runtime.reference_instruments", names.referenceInstruments)
        assertEquals("runtime.reference_scenario_runs", names.referenceScenarioRuns)
        assertEquals("runtime.reference_venue_sessions", names.referenceVenueSessions)
        assertEquals("runtime.runtime_persist_submit_outcome", names.persistSubmitOutcomeFunction)
        assertEquals("runtime.runtime_persist_submit_outcomes", names.persistSubmitOutcomesFunction)
        assertEquals("runtime.runtime_persist_submit_outcome_status_stage", names.persistSubmitOutcomeStatusStageFunction)
        assertEquals("runtime.runtime_persist_submit_outcome_timeline_stage", names.persistSubmitOutcomeTimelineStageFunction)
        assertEquals("runtime.runtime_append_canonical_submit_outcomes", names.appendCanonicalSubmitOutcomesFunction)
        assertEquals("runtime.runtime_project_canonical_submit_outcomes", names.projectCanonicalSubmitOutcomesFunction)
        assertEquals("runtime.runtime_project_canonical_command_outcomes", names.projectCanonicalCommandOutcomesFunction)
        assertEquals("runtime.runtime_materialize_venue_event_batch", names.materializeVenueEventBatchFunction)
        assertEquals("auth.auth_roles", names.authRoles)
        assertEquals("auth.auth_actor_roles", names.authActorRoles)
        assertEquals("admin.post_trade_profiles", names.adminPostTradeProfiles)
    }

    @Test
    fun blankSchemaNamesFallBackToDefaultSchemas() {
        val names = PostgresRuntimeSqlNames(runtimeSchema = "", authSchema = "", adminSchema = "")

        listOf(
            names.orders,
            names.referenceInstruments,
            names.referenceScenarioRuns,
            names.referenceVenueSessions,
            names.authRoles,
            names.adminPostTradeProfiles
        ).forEach { qualifiedName ->
            assertFalse(qualifiedName.startsWith("."))
            assertFalse(qualifiedName.endsWith("."))
        }
    }

    @Test
    fun rejectsUnsafeSchemaNames() {
        assertFailsWith<IllegalArgumentException> {
            PostgresRuntimeSqlNames(runtimeSchema = "runtime;drop schema auth", authSchema = "auth")
        }
        assertFailsWith<IllegalArgumentException> {
            PostgresRuntimeSqlNames(runtimeSchema = "runtime", authSchema = "auth.actor_roles")
        }
        assertFailsWith<IllegalArgumentException> {
            PostgresRuntimeSqlNames(runtimeSchema = "runtime", authSchema = "auth", adminSchema = "admin.policy")
        }
    }
}

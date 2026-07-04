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
        assertEquals("runtime.runtime_events", names.runtimeEvents)
        assertEquals("runtime.submit_results", names.submitResults)
        assertEquals("runtime.order_lifecycle_state", names.orderLifecycleState)
        assertEquals("runtime.market_data_snapshots", names.marketDataSnapshots)
        assertEquals("runtime.canonical_command_results", names.canonicalCommandResults)
        assertEquals("runtime.canonical_venue_events", names.canonicalVenueEvents)
        assertEquals("runtime.canonical_venue_event_batches", names.canonicalVenueEventBatches)
        assertEquals("runtime.canonical_command_outcomes", names.canonicalCommandOutcomes)
        assertEquals("runtime.projection_watermarks", names.projectionWatermarks)
        assertEquals("runtime.reference_instruments", names.referenceInstruments)
        assertEquals("runtime.runtime_persist_submit_outcome", names.persistSubmitOutcomeFunction)
        assertEquals("runtime.runtime_persist_submit_outcomes", names.persistSubmitOutcomesFunction)
        assertEquals("runtime.runtime_append_canonical_submit_outcomes", names.appendCanonicalSubmitOutcomesFunction)
        assertEquals("runtime.runtime_project_canonical_submit_outcomes", names.projectCanonicalSubmitOutcomesFunction)
        assertEquals("runtime.runtime_project_canonical_command_outcomes", names.projectCanonicalCommandOutcomesFunction)
        assertEquals("runtime.runtime_materialize_venue_event_batch", names.materializeVenueEventBatchFunction)
        assertEquals("auth.auth_roles", names.authRoles)
        assertEquals("auth.auth_actor_roles", names.authActorRoles)
    }

    @Test
    fun blankSchemaNamesFallBackToDefaultSchemas() {
        val names = PostgresRuntimeSqlNames(runtimeSchema = "", authSchema = "")

        listOf(
            names.orders,
            names.referenceInstruments,
            names.authRoles
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
    }
}

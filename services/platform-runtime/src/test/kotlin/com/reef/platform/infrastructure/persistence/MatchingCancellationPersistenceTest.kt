package com.reef.platform.infrastructure.persistence

import com.reef.platform.api.JsonCodec
import com.reef.platform.domain.EngineOrderAccepted
import com.reef.platform.domain.EngineOrderCancelled
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.SubmitOrderResult
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue

class MatchingCancellationPersistenceTest {
    @Test
    fun canonicalStreamCancellationClosesInMemoryOrderAndSurvivesRedelivery() {
        val store = InMemoryRuntimePersistence()
        val order = order("memory-ioc")
        val batch = batch(order)
        assertEquals(1L, store.materializeVenueEventBatch(batch))
        store.materializeVenueEventBatch(batch)
        assertEquals(1L, store.projectCanonicalCommandOutcomes("ioc", 100))
        store.rebuildOrderLifecycleState()
        assertEquals("CANCELLED", store.ordersForParticipant("participant", false).single().status)
        assertEquals("0", store.ordersForParticipant("participant", false).single().remainingQuantityUnits)
        assertEquals(result(order).cancelled, store.submitResult("cmd-${order.orderId}")?.cancelled)
        val cancellation = store.eventsForOrder(order.orderId).last()
        assertEquals("OrderCancelled", cancellation.eventType)
        assertEquals("10", JsonCodec.parseObject(cancellation.payloadJson).string("cancelledQuantityUnits"))
    }

    @Test
    fun canonicalStreamPartialAndFullFillsKeepExactResults() {
        for (filled in listOf("4", "10")) {
            val store = InMemoryRuntimePersistence()
            val order = order("memory-fill-$filled")
            val expected = result(order).copy(
                cancelled = if (filled == "10") null else result(order).cancelled!!.copy(cancelledQuantityUnits = "6"),
                executions = listOf(com.reef.platform.domain.ExecutionCreated("exec-$filled", "exec-$filled", order.orderId, "AAPL", filled, "100", "USD", order.acceptedAt)))
            store.materializeVenueEventBatch(batch(order, expected))
            store.projectCanonicalCommandOutcomes("fills", 100)
            store.rebuildOrderLifecycleState()
            val state = store.ordersForParticipant("participant", false).single()
            assertEquals(expected.executions, store.executionsForOrder(order.orderId))
            assertEquals(if (filled == "10") "FILLED" else "CANCELLED", state.status)
            assertEquals(expected, store.submitResult("cmd-${order.orderId}"))
        }
    }

    @Test
    fun postgresDirectRetryAndRestartKeepExactCancellation() {
        val source = source()
        source.use {
            val persistence = PostgresRuntimePersistence(source)
            val order = order("direct-${UUID.randomUUID()}")
            val expected = result(order)
            persistence.persistSubmitOutcome("cmd-${order.orderId}", expected, order, emptyList())
            assertEquals(expected, persistence.submitResult("cmd-${order.orderId}"))
            val restarted = PostgresRuntimePersistence(source)
            assertEquals(expected, restarted.submitResult("cmd-${order.orderId}"))
        }
    }

    @Test
    fun postgresStreamCancellationClosesOrderAndPreservesRetry() {
        val source = source()
        source.use {
            val persistence = PostgresRuntimePersistence(source)
            val order = order("stream-${UUID.randomUUID()}")
            val batch = batch(order)
            persistence.materializeVenueEventBatch(batch)
            persistence.materializeVenueEventBatch(batch)
            assertEquals(1L, persistence.projectCanonicalCommandOutcomes("projection-${order.orderId}", 100, eventStream = batch.eventStream))
            persistence.rebuildOrderLifecycleState()
            assertEquals("CANCELLED", persistence.ordersForParticipant("participant", false).single { it.orderId == order.orderId }.status)
            assertEquals(result(order).cancelled, persistence.submitResult("cmd-${order.orderId}")?.cancelled)
        }
    }

    @Test
    fun deployedSqlProjectionKeepsTerminalFactAndRejectsChangedReplay() {
        val source = source()
        source.use {
            val store = PostgresRuntimePersistence(source)
            source.connection.use { conn -> conn.createStatement().use { sql ->
                sql.execute("CREATE SCHEMA IF NOT EXISTS command_log")
                sql.execute("CREATE TABLE IF NOT EXISTS command_log.command_payloads(command_id TEXT PRIMARY KEY, payload_json JSONB, created_at TIMESTAMPTZ NOT NULL DEFAULT now())")
                for (migration in listOf("0020_order_lifecycle_incremental.sql", "0040_split_submit_outcome_projection_stages.sql", "0041_deterministic_timeline_projection_sequence.sql", "0049_execution_replay_conflicts.sql", "0059_trade_replay_and_parse.sql", "0068_event_replay_conflicts.sql", "0073_runtime_order_run_identity.sql", "0074_matching_ioc_cancellation.sql", "0075_instrument_quote_currency.sql")) {
                    sql.execute(java.nio.file.Files.readString(java.nio.file.Path.of("../../scripts/dev/db/migrations/runtime/$migration")))
                }
            } }
            val order = order("sql-${UUID.randomUUID()}")
            val batch = batch(order)
            store.materializeVenueEventBatch(batch)
            val members = """[{"partitionId":0,"streamSequence":1,"commandId":"cmd-${order.orderId}","canonicalBatchId":"${batch.batchId}","commandType":"SubmitOrder","payloadHash":"hash"}]"""
            source.connection.use { conn -> conn.prepareStatement("SELECT runtime.runtime_project_canonical_command_outcome_members(?, ?::jsonb, TRUE)").use { sql ->
                sql.setString(1, "sql-${order.orderId}")
                sql.setString(2, members)
                repeat(2) { sql.executeQuery().use { rs -> rs.next(); assertEquals(1L, rs.getLong(1)) } }
            } }
            store.rebuildOrderLifecycleState()
            assertEquals("CANCELLED", store.ordersForParticipant("participant", false).single { it.orderId == order.orderId }.status)
            assertEquals(result(order).cancelled, store.submitResult("cmd-${order.orderId}")?.cancelled)
            val changed = result(order).copy(cancelled = result(order).cancelled!!.copy(cancelledQuantityUnits = "9"))
            source.connection.use { conn -> conn.prepareStatement("SELECT runtime.runtime_persist_submit_outcome_status_stage(?::jsonb)").use { sql ->
                sql.setString(1, "[${PersistableSubmitOutcome("cmd-${order.orderId}", changed, order, emptyList()).toJsonObject()}]")
                kotlin.test.assertFails { sql.executeQuery() }
            } }
        }
    }

    @Test
    fun migrationBackfillsLegacyCanonicalFactsBeforeExactReplay() = checkLegacyReplay(archived = false, compatibilityBootstrap = false)

    @Test
    fun migrationBackfillsArchivedCanonicalFactsBeforeExactReplay() = checkLegacyReplay(archived = true, compatibilityBootstrap = false)

    @Test
    fun compatibilityBootstrapBackfillsLegacyCanonicalFacts() = checkLegacyReplay(archived = false, compatibilityBootstrap = true)

    private fun checkLegacyReplay(archived: Boolean, compatibilityBootstrap: Boolean) {
        val source = source()
        source.use {
            val store = PostgresRuntimePersistence(source)
            val order = order("legacy-replay-${UUID.randomUUID()}")
            val expected = result(order).copy(cancelled = null, executions = listOf(
                com.reef.platform.domain.ExecutionCreated("legacy-exec-${order.orderId}", "legacy-exec-${order.orderId}", order.orderId, "AAPL", "4", "100", "USD", order.acceptedAt)))
            store.materializeVenueEventBatch(batch(order, expected))
            store.persistSubmitOutcome("cmd-${order.orderId}", expected, order, emptyList())
            source.connection.use { conn -> conn.createStatement().use { sql ->
                if (archived) {
                    sql.execute("INSERT INTO runtime.canonical_command_outcomes_archive SELECT canonical.*, now() FROM runtime.canonical_command_outcomes canonical WHERE command_id = 'cmd-${order.orderId}'")
                    sql.execute("DELETE FROM runtime.canonical_command_outcomes WHERE command_id = 'cmd-${order.orderId}'")
                }
                sql.execute("UPDATE runtime.submit_results SET matching_facts = NULL WHERE command_id = 'cmd-${order.orderId}'")
                for (migration in listOf("0020_order_lifecycle_incremental.sql", "0040_split_submit_outcome_projection_stages.sql", "0041_deterministic_timeline_projection_sequence.sql", "0049_execution_replay_conflicts.sql", "0059_trade_replay_and_parse.sql", "0068_event_replay_conflicts.sql", "0073_runtime_order_run_identity.sql", "0074_matching_ioc_cancellation.sql")) {
                    sql.execute(java.nio.file.Files.readString(java.nio.file.Path.of("../../scripts/dev/db/migrations/runtime/$migration")))
                }
            } }
            if (compatibilityBootstrap) {
                source.connection.use { conn -> conn.createStatement().use { sql ->
                    sql.execute("UPDATE runtime.submit_results SET matching_facts = NULL WHERE command_id = 'cmd-${order.orderId}'")
                } }
                val restarted = PostgresRuntimePersistence(source)
                assertEquals(expected, restarted.submitResult("cmd-${order.orderId}"))
                // Reinstall deployed strict replay function after exercising compatibility bootstrap.
                source.connection.use { conn -> conn.createStatement().use { sql ->
                    sql.execute(java.nio.file.Files.readString(java.nio.file.Path.of("../../scripts/dev/db/migrations/runtime/0074_matching_ioc_cancellation.sql")))
                } }
            }
            source.connection.use { conn -> conn.prepareStatement("SELECT runtime.runtime_persist_submit_outcome_status_stage(?::jsonb)").use { sql ->
                sql.setString(1, "[${PersistableSubmitOutcome("cmd-${order.orderId}", expected, order, emptyList()).toJsonObject()}]")
                sql.executeQuery().close()
                val changed = expected.copy(executions = expected.executions.map { it.copy(quantityUnits = "5") })
                sql.setString(1, "[${PersistableSubmitOutcome("cmd-${order.orderId}", changed, order, emptyList()).toJsonObject()}]")
                kotlin.test.assertFails { sql.executeQuery() }
            } }
            assertEquals(expected, store.submitResult("cmd-${order.orderId}"))
        }
    }

    @Test
    fun persistedInstrumentQuoteSurvivesRestartAndCannotChange() {
        val source = source()
        source.use {
            val store = PostgresRuntimePersistence(source)
            val id = "cad-${UUID.randomUUID()}"
            store.saveInstrument(com.reef.platform.domain.Instrument(id, id, "CAD"))
            val restarted = PostgresRuntimePersistence(source)
            assertEquals("CAD", restarted.instruments().single { it.instrumentId == id }.quoteCurrency)
            assertEquals("CAD", restarted.validateReferenceData(id, "missing", "missing").instrumentQuoteCurrency)
            kotlin.test.assertFailsWith<IllegalArgumentException> { restarted.saveInstrument(com.reef.platform.domain.Instrument(id, id, "USD")) }
        }
    }

    @Test
    fun compatibilityBootstrapRejectsHistoricalQuoteContradictionWithoutRelabelingFacts() {
        val source = source()
        source.use {
            val schema = "contradictory_quote_${UUID.randomUUID().toString().replace("-", "")}"
            val names = PostgresRuntimeSqlNames(schema, schema + "_auth", schema + "_admin")
            val store = PostgresRuntimePersistence(source, names, PostgresBootstrapMode.Compat)
            store.saveInstrument(com.reef.platform.domain.Instrument("legacy", "legacy", "USD"))
            val historical = order("legacy-order").copy(instrumentId = "legacy", currency = "CAD")
            store.saveAcceptedOrder(historical)
            store.saveSubmitResult("cmd-${historical.orderId}", result(historical))
            kotlin.test.assertFailsWith<IllegalStateException> { PostgresRuntimePersistence(source, names, PostgresBootstrapMode.Compat) }
            source.connection.use { conn -> conn.createStatement().use { sql ->
                val migration = java.nio.file.Files.readString(java.nio.file.Path.of("../../scripts/dev/db/migrations/runtime/0075_instrument_quote_currency.sql"))
                kotlin.test.assertFails { sql.execute(migration.replace("runtime.", "${names.runtimeSchemaName}.")) }
            } }
            assertEquals("CAD", store.acceptedOrders(setOf(historical.orderId))[historical.orderId]?.currency)
        }
    }

    @Test
    fun rejectedStreamQuoteDoesNotBlockRestart() = withRejectedQuoteHistory { names, store, source ->
        val restarted = PostgresRuntimePersistence(source, names, PostgresBootstrapMode.Compat)
        assertEquals("USD", restarted.validateReferenceData("AAPL", "participant", "account").instrumentQuoteCurrency)
        assertEquals("CAD", store.acceptedOrders(setOf("wrong-quote"))["wrong-quote"]?.currency)
    }

    @Test
    fun rejectedStreamQuoteDoesNotBlockMigration() = withRejectedQuoteHistory { names, store, source ->
        source.connection.use { conn -> conn.createStatement().use { sql ->
            val migration = java.nio.file.Files.readString(java.nio.file.Path.of("../../scripts/dev/db/migrations/runtime/0075_instrument_quote_currency.sql"))
            sql.execute(migration.replace("runtime.", "${names.runtimeSchemaName}."))
        } }
        assertEquals("CAD", store.acceptedOrders(setOf("wrong-quote"))["wrong-quote"]?.currency)
    }

    private fun withRejectedQuoteHistory(block: (PostgresRuntimeSqlNames, PostgresRuntimePersistence, com.zaxxer.hikari.HikariDataSource) -> Unit) {
        val source = source()
        source.use {
            val schema = "rejected_quote_${UUID.randomUUID().toString().replace("-", "")}"
            val names = PostgresRuntimeSqlNames(schema, schema + "_auth", schema + "_admin")
            val store = PostgresRuntimePersistence(source, names, PostgresBootstrapMode.Compat)
            store.saveInstrument(com.reef.platform.domain.Instrument("AAPL", "AAPL", "USD"))
            val accepted = order("valid-quote")
            store.persistSubmitOutcome("cmd-${accepted.orderId}", result(accepted), accepted, emptyList())
            val rejected = order("wrong-quote").copy(currency = "CAD")
            val payload = """{"rejected":{"eventId":"reject-wrong-quote","orderId":"wrong-quote","code":"CURRENCY_MISMATCH","reason":"wrong quote","occurredAt":"${rejected.acceptedAt}"},"acceptedOrder":${rejected.toJsonObject()}}"""
            val rejectedBatch = batch(rejected).let { batch -> batch.copy(outcomes = listOf(batch.outcomes.single().copy(resultStatus = "rejected", rejectCode = "CURRENCY_MISMATCH", resultPayloadJson = payload))) }
            store.materializeVenueEventBatch(rejectedBatch)
            store.projectCanonicalCommandOutcomes("rejected-quote", 100)
            store.rebuildOrderLifecycleState()
            assertEquals("REJECTED", store.ordersForParticipant("participant", false).single { it.orderId == rejected.orderId }.status)
            block(names, store, source)
        }
    }

    private fun source(): com.zaxxer.hikari.HikariDataSource {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST")
        assumeTrue(url != null, "requires isolated Postgres test database")
        return RuntimeDataSources.dataSource(assertNotNull(url), System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: "matching_test",
            System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: "matching_test_local", "matching-ioc-${UUID.randomUUID()}") as com.zaxxer.hikari.HikariDataSource
    }

    private fun order(id: String) = PersistedOrder(id, id, "AAPL", "participant", "account", "BUY", "LIMIT", "10", "100", "USD", "IOC", "2026-10-02T01:00:00Z")
    private fun result(order: PersistedOrder) = SubmitOrderResult(
        accepted = EngineOrderAccepted("accepted-${order.orderId}", order.orderId, order.engineOrderId, order.acceptedAt),
        cancelled = EngineOrderCancelled("cancelled-${order.orderId}", order.orderId, "10", "IOC_RESIDUAL", order.acceptedAt)
    )
    private fun batch(order: PersistedOrder, expected: SubmitOrderResult = result(order)): VenueEventBatchFact {
        val payload = """{"accepted":{"eventId":"accepted-${order.orderId}","orderId":"${order.orderId}","engineOrderId":"${order.orderId}","occurredAt":"${order.acceptedAt}"},"acceptedOrder":${order.toJsonObject()},"cancelled":${expected.cancelled?.toJsonObject() ?: "null"},"executions":${expected.executions.toJsonArray { it.toJsonObject() }},"trades":${expected.trades.toJsonArray { it.toJsonObject() }}}"""
        return VenueEventBatchFact("batch-${order.orderId}", "shard", 0, "commands", "events-${order.orderId}", 1, 1, 1, order.acceptedAt,
            payloadChecksum = "checksum-${order.orderId}", outcomes = listOf(VenueCommandOutcomeFact("cmd-${order.orderId}", "SubmitOrder", 1, 1, "hash", "AAPL", order.orderId, "accepted", resultPayloadJson = payload)))
    }
}

package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresRunOrderIdentityMigrationIntegrationTest {
    @Test
    fun externalCanonicalHistoryRebuildsFreshStoreWithoutRewritingLegacyFacts() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") != null)
        val legacySchema = "legacy_projection_${UUID.randomUUID().toString().replace("-", "")}"
        val freshSchema = "fresh_projection_${UUID.randomUUID().toString().replace("-", "")}"
        val source = RuntimeDataSources.dataSource(
            requireNotNull(System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST")),
            requireNotNull(System.getenv("RUNTIME_POSTGRES_USER_TEST")),
            requireNotNull(System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST")), legacySchema
        )
        val directory = Path.of("../../scripts/dev/db/migrations/runtime")
        val retainedOutcomes = listOf("run-a", "run-b").map { run ->
            val order = PersistedOrder("shared", "shared", "AAPL", "buyer-$run", "account-$run", "BUY", "LIMIT", "10", "100", "USD", "DAY", "2026-10-01T00:00:00Z", runId = run, venueSessionId = "session")
            val event = RuntimeEvent("accepted-$run", "OrderAccepted", "shared", "trace-$run", "command-$run", "correlation-$run", "test", "v1", occurredAt = order.acceptedAt, runId = run)
            val fill = ExecutionCreated("fill-$run", "fill-$run", "shared", "AAPL", "2", "100", "USD", "2026-10-01T00:01:00Z", runId = run)
            PersistableSubmitOutcome("command-$run", SubmitOrderResult(accepted = EngineOrderAccepted(event.eventId, "shared", "shared", order.acceptedAt), executions = listOf(fill)), order, listOf(event))
        }
        try {
            source.connection.use { connection ->
                connection.createStatement().use { it.execute("CREATE SCHEMA $legacySchema") }
                Files.list(directory).use { paths ->
                    paths.filter { it.fileName.toString().endsWith(".sql") && it.fileName.toString() < "0073" }.sorted().forEach { file ->
                        connection.createStatement().use { it.execute(Files.readString(file).replace(Regex("\\bruntime\\b"), legacySchema)) }
                    }
                }
                val payload = retainedOutcomes.joinToString(prefix = "[", postfix = "]") { it.toJsonObject() }
                retainedOutcomes.forEach { outcome ->
                    connection.prepareStatement("SELECT $legacySchema.runtime_persist_submit_outcomes(?::jsonb)").use {
                        it.setString(1, "[${outcome.toJsonObject()}]")
                        it.executeQuery().close()
                    }
                }
                connection.autoCommit = false
                connection.createStatement().use { it.execute(Files.readString(directory.resolve("0073_runtime_order_run_identity.sql")).replace(Regex("\\bruntime\\b"), legacySchema)) }
                connection.commit()
                connection.autoCommit = true
                val conflict = kotlin.test.assertFailsWith<org.postgresql.util.PSQLException> {
                    connection.prepareStatement("SELECT $legacySchema.runtime_persist_submit_outcomes(?::jsonb)").use {
                        it.setString(1, payload); it.executeQuery().close()
                    }
                }
                kotlin.test.assertContains(conflict.message.orEmpty(), "replay conflict")
            }
            val fresh = PostgresRuntimePersistence(source, PostgresRuntimeSqlNames(runtimeSchema = freshSchema), PostgresBootstrapMode.Compat)
            // Retained canonical snapshots carry proven scope; legacy projection rows are never copied.
            fresh.appendCanonicalSubmitOutcomes(retainedOutcomes.mapIndexed { index, outcome ->
                CanonicalSubmitOutcome(
                    runId = requireNotNull(outcome.acceptedOrder).runId, venueSessionId = "session",
                    partitionId = 0, partitionSequence = index + 1L, streamName = "commands",
                    streamSequence = index + 1L, commandId = outcome.commandId, idempotencyKey = outcome.commandId,
                    payloadHash = "hash-${outcome.commandId}", instrumentId = "AAPL", commandType = "SubmitOrder",
                    resultStatus = "accepted", rejectCode = "", acceptedAt = "2026-10-01T00:00:00Z",
                    completedAt = "2026-10-01T00:01:00Z", engineShardId = "shard", outcome = outcome
                )
            })
            assertEquals(2, fresh.projectCanonicalSubmitOutcomes("fresh-rebuild", 500))
            assertEquals(0, fresh.projectCanonicalSubmitOutcomes("fresh-rebuild", 500))
            assertEquals(2, fresh.projectCanonicalSubmitOutcomes("fresh-replay", 500))
            assertEquals(2, fresh.rebuildOrderLifecycleState())
            for (run in listOf("run-a", "run-b")) {
                val identity = RuntimeOrderIdentity(run, "shared")
                assertEquals("buyer-$run", fresh.acceptedOrder(identity)?.participantId)
                assertEquals(listOf("fill-$run"), fresh.executionsForOrder(identity).map { it.eventId })
                assertEquals(listOf("accepted-$run"), fresh.eventsForOrder(identity).map { it.eventId })
                assertEquals("8", fresh.orderLifecycleState(identity)?.remainingQuantityUnits)
            }
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM $legacySchema.executions WHERE run_id = ''").use { rows -> rows.next(); assertEquals(2, rows.getLong(1), "legacy evidence retained") }
                }
            }
        } finally {
            source.connection.use { connection -> connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $legacySchema CASCADE; DROP SCHEMA IF EXISTS $freshSchema CASCADE") } }
            (source as? AutoCloseable)?.close()
        }
    }

    @Test
    fun upgradeRecoversCanonicalAcceptancesAndPreservesUnprovenLegacyEvents() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") != null)
        val schema = "order_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        val source = RuntimeDataSources.dataSource(requireNotNull(System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST")), requireNotNull(System.getenv("RUNTIME_POSTGRES_USER_TEST")), requireNotNull(System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST")), schema)
        val directory = Path.of("../../scripts/dev/db/migrations/runtime")
        fun schemaSql(sql: String) = sql.replace(Regex("\\bruntime\\b"), schema)
        try {
            source.connection.use { connection ->
                connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
                Files.list(directory).use { paths ->
                    paths.filter { it.fileName.toString().endsWith(".sql") && it.fileName.toString() < "0073" }.sorted().forEach { file ->
                        connection.createStatement().use { it.execute(schemaSql(Files.readString(file))) }
                    }
                }
                for (run in listOf("run-a", "run-b")) {
                    val order = PersistedOrder("shared", "shared", "AAPL", "buyer-$run", "account-$run", "BUY", "LIMIT", "10", "100", "USD", "DAY", "2026-10-01T00:00:00Z", runId = run, venueSessionId = "session")
                    val event = RuntimeEvent("accepted-$run", "OrderAccepted", "shared", "trace-$run", "command-$run", "correlation-$run", "test", "v1", occurredAt = order.acceptedAt, runId = run)
                    val fill = ExecutionCreated("fill-$run", "fill-$run", "shared", "AAPL", "2", "100", "USD", "2026-10-01T00:01:00Z", runId = run)
                    val outcome = PersistableSubmitOutcome("command-$run", SubmitOrderResult(accepted = EngineOrderAccepted(event.eventId, "shared", "shared", order.acceptedAt), executions = listOf(fill)), order, listOf(event))
                    connection.prepareStatement("SELECT $schema.runtime_persist_submit_outcomes(?::jsonb)").use {
                        it.setString(1, "[${outcome.toJsonObject()}]")
                        it.executeQuery().close()
                    }
                    connection.prepareStatement("""
                        INSERT INTO $schema.canonical_command_results(command_id,run_id,venue_session_id,partition_id,partition_seq,stream_name,stream_seq,idempotency_key,payload_hash,instrument_id,command_type,result_status,reject_code,accepted_at,completed_at,engine_shard_id,result_payload)
                        VALUES (?,?,'session',0,?,'commands',?,?,'hash','AAPL','SubmitOrder','accepted','','2026-10-01T00:00:00Z','2026-10-01T00:00:00Z','shard',?::jsonb)
                    """.trimIndent()).use {
                        val sequence = if (run == "run-a") 1L else 2L
                        it.setString(1, outcome.commandId)
                        it.setString(2, run)
                        it.setLong(3, sequence)
                        it.setLong(4, sequence)
                        it.setString(5, outcome.commandId)
                        it.setString(6, outcome.toJsonObject())
                        it.executeUpdate()
                    }
                }
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM $schema.orders").use { rows -> rows.next(); assertEquals(1, rows.getLong(1), "baseline overwrites previous run") }
                    statement.execute("INSERT INTO $schema.runtime_events(event_id,event_type,order_id,trace_id,causation_id,correlation_id,producer,schema_version,sequence_number,occurred_at) VALUES ('unproven','OrderCancelled','shared','legacy','legacy','legacy','test','v1',1,'2026-10-01T00:02:00Z')")
                }
                connection.autoCommit = false
                try {
                    connection.createStatement().use { it.execute(schemaSql(Files.readString(directory.resolve("0073_runtime_order_run_identity.sql")))) }
                    connection.commit()
                } catch (error: Exception) {
                    connection.rollback()
                    throw error
                }
            }
            val persistence = PostgresRuntimePersistence(source, PostgresRuntimeSqlNames(runtimeSchema = schema), PostgresBootstrapMode.Validate)
            assertEquals(2, persistence.acceptedOrders().size)
            assertEquals(2, persistence.rebuildOrderLifecycleState())
            for (run in listOf("run-a", "run-b")) {
                val identity = RuntimeOrderIdentity(run, "shared")
                assertEquals("buyer-$run", persistence.acceptedOrder(identity)?.participantId)
                assertEquals("8", persistence.orderLifecycleState(identity)?.remainingQuantityUnits)
                assertEquals("PARTIALLY_FILLED", persistence.orderLifecycleState(identity)?.status)
                assertEquals(listOf("accepted-$run"), persistence.eventsForOrder(identity).map { it.eventId })
            }
            assertEquals(listOf("unproven"), persistence.eventsForOrder(RuntimeOrderIdentity("", "shared")).map { it.eventId })
        } finally {
            source.connection.use { connection -> connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
            (source as? AutoCloseable)?.close()
        }
    }
}

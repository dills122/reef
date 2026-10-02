package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.application.settlement.PostgresSettlementFactStore
import com.reef.platform.application.settlement.PostgresSettlementSqlNames
import com.reef.platform.application.settlement.TradeSettlementObligationMaterializer
import com.reef.platform.domain.*
import com.reef.platform.infrastructure.engine.EngineClient
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue

class MatchingFactsReconciliationIntegrationTest {
    @Test
    fun realMatchingFactsSurviveDurableRetryAndSettlementReplay() {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST")
        val engineUrl = System.getenv("MATCHING_ENGINE_URL_TEST")
        assumeTrue(url != null && engineUrl != null, "requires isolated Postgres and matching engine")
        val key = UUID.randomUUID().toString().replace("-", "")
        val source = RuntimeDataSources.dataSource(assertNotNull(url), System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: "matching_test",
            System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: "matching_test_local", "matching-reconcile-$key") as com.zaxxer.hikari.HikariDataSource
        source.use {
            val names = PostgresRuntimeSqlNames("matching_$key", "matching_auth_$key", "matching_admin_$key")
            val store = PostgresRuntimePersistence(source, names, PostgresBootstrapMode.Compat)
            val service = OrderApplicationService(EngineClient(assertNotNull(engineUrl)), store)
            service.createInstrument(Instrument("AAPL", "AAPL", "USD"))
            service.createRole(RoleDefinition("trader", listOf(Permission.ORDER_SUBMIT)))
            service.assignRole(ActorRoleBinding("actor", "trader"))
            for (side in listOf("BUY", "SELL")) {
                service.createParticipant(Participant(side, side))
                service.createAccount(Account("account-$side", side))
            }
            val run = "reconcile-$key"
            fun command(id: String, side: String, quantity: String, tif: String = "DAY") = SubmitOrderCommand(
                "cmd-$id", "trace-$id", "", "corr-$run", "actor", "2026-10-02T01:00:00Z", id, "AAPL", side,
                "account-$side", side, "LIMIT", quantity, "100", "USD", tif, runId = run, venueSessionId = run)
            val makerCommand = command("maker-$key", "SELL", "4")
            val makerResult = service.submitOrder(makerCommand)
            val ioc = command("ioc-$key", "BUY", "10", "IOC")
            val result = service.submitOrder(ioc)
            assertEquals("6", result.cancelled?.cancelledQuantityUnits)
            assertEquals(2, result.executions.size)
            assertEquals(1, result.trades.size)
            assertEquals(result, service.submitOrder(ioc), "durable retry must preserve both maker and taker facts")
            val restarted = OrderApplicationService(EngineClient(engineUrl), PostgresRuntimePersistence(source, names, PostgresBootstrapMode.Compat))
            assertEquals(result, restarted.submitOrder(ioc), "restart must preserve exact matching result")
            assertEquals(result.trades, store.trades())
            assertEquals(result.executions.sortedBy { it.executionId }, (store.executionsForOrder("maker-$key") + store.executionsForOrder("ioc-$key")).sortedBy { it.executionId })
            val acceptedOrders = store.acceptedOrders(setOf(makerCommand.orderId, ioc.orderId))
            val streamOrders = listOf(makerCommand to makerResult, ioc to result)
            val outcomes = streamOrders.mapIndexed { index, (command, outcome) ->
                val payload = PersistableSubmitOutcome(command.commandId, outcome, acceptedOrders[command.orderId], emptyList()).toJsonObject()
                VenueCommandOutcomeFact(command.commandId, "SubmitOrder", index + 1L, 1L, "hash-$index", "AAPL", command.orderId, "accepted", resultPayloadJson = payload)
            }
            val batch = VenueEventBatchFact("batch-$key", "shard", 0, "commands", "events-$key", 1, 1, 2, ioc.occurredAt, payloadChecksum = "checksum-$key", outcomes = outcomes)
            val streamSchema = "matching_stream_$key"
            val streamStore = PostgresRuntimePersistence(source, PostgresRuntimeSqlNames(streamSchema, "matching_auth_$key", "matching_admin_$key"), PostgresBootstrapMode.Compat)
            source.connection.use { conn -> conn.createStatement().use { sql ->
                // Compat bootstrap omits deployed split-stage overload; apply existing migration to isolated schema.
                for (migration in listOf("0020_order_lifecycle_incremental.sql", "0040_split_submit_outcome_projection_stages.sql", "0041_deterministic_timeline_projection_sequence.sql", "0049_execution_replay_conflicts.sql", "0059_trade_replay_and_parse.sql", "0068_event_replay_conflicts.sql", "0073_matching_ioc_cancellation.sql")) {
                    val sourceSql = java.nio.file.Files.readString(java.nio.file.Path.of("../../scripts/dev/db/migrations/runtime/$migration"))
                    sql.execute(sourceSql.replace("runtime.", "$streamSchema."))
                }
            } }
            val memory = InMemoryRuntimePersistence()
            for (projection in listOf<RuntimePersistence>(streamStore, memory)) {
                projection.materializeVenueEventBatch(batch)
                projection.materializeVenueEventBatch(batch)
                assertEquals(2L, projection.projectCanonicalCommandOutcomes("status-$key", 100, includeFills = false, eventStream = batch.eventStream, projectionStage = ProjectionStage.CommandStatus))
                assertEquals(result, projection.submitResult(ioc.commandId), "status stage must retain original response even before fill projection")
                assertEquals(2L, projection.projectCanonicalCommandOutcomes("stream-$key", 100, eventStream = batch.eventStream))
                projection.rebuildOrderLifecycleState()
                assertEquals(result, projection.submitResult(ioc.commandId))
                assertEquals("CANCELLED", projection.ordersForParticipant("BUY", false).single().status)
                assertEquals(result.trades, projection.trades())
            }
            service.rebuildOrderLifecycleState()
            assertEquals("CANCELLED", service.ordersForParticipant("BUY", false).single().status)
            store.savePostTradeProfile(PostTradeProfile("instant", "instant-post-trade", "T+0", "gross-or-microbatch", "near-instant-finality", 1))
            store.saveScenarioRunPostTradeProfile(ScenarioRunPostTradeProfile(run, "instant"))
            val settlement = PostgresSettlementFactStore(source, PostgresSettlementSqlNames("matching_settlement_$key"), PostgresBootstrapMode.Compat)
            val materializer = TradeSettlementObligationMaterializer(store, settlement)
            materializer.materialize(run)
            val facts = settlement.factsByScenarioRunId(run)
            assertEquals(result.trades.single().tradeId, facts.obligations.single().tradeId)
            assertEquals("4", facts.obligations.single().quantity)
            assertEquals("400", facts.obligations.single().cashAmount)
            assertEquals("USD", facts.obligations.single().currency)
            assertEquals(setOf("USD"), facts.ledgerEntries.filter { it.assetType == "CASH" }.map { it.assetId }.toSet())
            materializer.materialize(run)
            assertEquals(facts, settlement.factsByScenarioRunId(run), "settlement replay must preserve exact facts")
        }
    }
}

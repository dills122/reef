package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalSourceCoverageVerifier
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Runs on the isolated postmatch test database in the schema-placement CI job. */
class PostMatchLiveEffectWriterIntegrationTest {
    @Test
    fun appliesMakerFillAndCancellationWithFinalOrderStateAndExactTradeFacts() {
        val jdbcUrl = System.getenv("POSTMATCH_DB_URL_TEST") ?: return
        val user = System.getenv("POSTMATCH_DB_USER_TEST") ?: return
        val password = System.getenv("POSTMATCH_DB_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, user, password, "postmatch-live-integration")
        val store = PostMatchOperationalStore(dataSource)
        val writer = PostMatchLiveEffectWriter()
        val verifier = CanonicalSourceCoverageVerifier()
        val token = UUID.randomUUID().toString()
        val stream = "live-test-$token"
        val generation = "generation-$token"
        val consumer = "live-$token"
        val maker = "maker-$token"
        val taker = "taker-$token"
        val outcomes = listOf(
            source(stream, 1, maker, "SubmitOrder", makerSubmit(maker)),
            source(stream, 2, taker, "SubmitOrder", takerSubmit(taker, maker)),
            source(stream, 3, maker, "CancelOrder", cancelMaker(maker))
        )
        val opening = verifier.verify(consumer, stream, 0, generation, 0, 1, outcomes.take(1))
        val window = verifier.verify(consumer, stream, 0, generation, 1, 3, outcomes.drop(1))

        try {
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(opening, writer::apply))
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(window, writer::apply))
            assertEquals(PostMatchApplyResult.DUPLICATE, store.apply(opening, writer::apply))
            assertEquals(PostMatchApplyResult.DUPLICATE, store.apply(window, writer::apply))
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT order_id, status, original_quantity, remaining_quantity, filled_quantity FROM postmatch.live_order_state WHERE event_stream = ? AND source_generation = ? ORDER BY order_id"
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, generation)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals(maker, rows.getString(1))
                        assertEquals("CANCELLED", rows.getString(2))
                        assertEquals("10", rows.getBigDecimal(3).stripTrailingZeros().toPlainString())
                        assertEquals("0", rows.getBigDecimal(4).stripTrailingZeros().toPlainString())
                        assertEquals("6", rows.getBigDecimal(5).stripTrailingZeros().toPlainString())
                        check(rows.next())
                        assertEquals(taker, rows.getString(1))
                        assertEquals("FILLED", rows.getString(2))
                        assertEquals("6", rows.getBigDecimal(5).stripTrailingZeros().toPlainString())
                        check(!rows.next())
                    }
                }
                connection.prepareStatement("SELECT COUNT(*) FROM postmatch.live_execution_facts WHERE event_stream = ?").use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows -> check(rows.next()); assertEquals(2L, rows.getLong(1)) }
                }
                connection.prepareStatement("SELECT COUNT(*) FROM postmatch.live_trade_facts WHERE event_stream = ?").use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows -> check(rows.next()); assertEquals(1L, rows.getLong(1)) }
                }
            }
        } finally {
            clean(dataSource, stream, generation, consumer)
        }
    }

    @Test
    fun missingMakerIdentityRollsBackTakerAndFrontier() {
        val jdbcUrl = System.getenv("POSTMATCH_DB_URL_TEST") ?: return
        val user = System.getenv("POSTMATCH_DB_USER_TEST") ?: return
        val password = System.getenv("POSTMATCH_DB_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, user, password, "postmatch-live-integration")
        val token = UUID.randomUUID().toString()
        val stream = "live-test-$token"
        val generation = "generation-$token"
        val consumer = "live-$token"
        val taker = "taker-$token"
        val window = CanonicalSourceCoverageVerifier().verify(
            consumer, stream, 0, generation, 0, 1,
            listOf(source(stream, 1, taker, "SubmitOrder", takerSubmit(taker, "absent-maker-$token")))
        )
        try {
            assertFailsWith<IllegalStateException> {
                PostMatchOperationalStore(dataSource).apply(window, PostMatchLiveEffectWriter()::apply)
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM postmatch.consumer_frontiers WHERE consumer_name = ?").use { statement ->
                    statement.setString(1, consumer)
                    statement.executeQuery().use { rows -> check(rows.next()); assertEquals(0L, rows.getLong(1)) }
                }
                connection.prepareStatement("SELECT COUNT(*) FROM postmatch.canonical_order_directory WHERE event_stream = ?").use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows -> check(rows.next()); assertEquals(0L, rows.getLong(1)) }
                }
            }
        } finally {
            clean(dataSource, stream, generation, consumer)
        }
    }

    @Test
    fun makerAcceptedAfterTradeDoesNotSatisfyCausalOwnership() {
        val jdbcUrl = System.getenv("POSTMATCH_DB_URL_TEST") ?: return
        val user = System.getenv("POSTMATCH_DB_USER_TEST") ?: return
        val password = System.getenv("POSTMATCH_DB_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, user, password, "postmatch-live-integration")
        val token = UUID.randomUUID().toString()
        val stream = "live-test-$token"
        val generation = "generation-$token"
        val consumer = "live-$token"
        val maker = "maker-$token"
        val taker = "taker-$token"
        val window = CanonicalSourceCoverageVerifier().verify(
            consumer, stream, 0, generation, 0, 2,
            listOf(
                source(stream, 1, taker, "SubmitOrder", takerSubmit(taker, maker)),
                source(stream, 2, maker, "SubmitOrder", makerSubmit(maker))
            )
        )
        try {
            assertFailsWith<IllegalStateException> {
                PostMatchOperationalStore(dataSource).apply(window, PostMatchLiveEffectWriter()::apply)
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM postmatch.canonical_order_directory WHERE event_stream = ?").use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows -> check(rows.next()); assertEquals(0L, rows.getLong(1)) }
                }
            }
        } finally {
            clean(dataSource, stream, generation, consumer)
        }
    }

    @Test
    fun activeOrderWithUnexplainedMissingQuantityRollsBack() {
        val jdbcUrl = System.getenv("POSTMATCH_DB_URL_TEST") ?: return
        val user = System.getenv("POSTMATCH_DB_USER_TEST") ?: return
        val password = System.getenv("POSTMATCH_DB_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, user, password, "postmatch-live-integration")
        val token = UUID.randomUUID().toString()
        val stream = "live-test-$token"
        val generation = "generation-$token"
        val consumer = "live-$token"
        val maker = "maker-$token"
        val malformed = makerSubmit(maker).replace("\"remainingQuantity\":\"10\"", "\"remainingQuantity\":\"9\"")
        val window = CanonicalSourceCoverageVerifier().verify(
            consumer, stream, 0, generation, 0, 1,
            listOf(source(stream, 1, maker, "SubmitOrder", malformed))
        )
        try {
            assertFailsWith<IllegalStateException> {
                PostMatchOperationalStore(dataSource).apply(window, PostMatchLiveEffectWriter()::apply)
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM postmatch.consumer_frontiers WHERE consumer_name = ?").use { statement ->
                    statement.setString(1, consumer)
                    statement.executeQuery().use { rows -> check(rows.next()); assertEquals(0L, rows.getLong(1)) }
                }
            }
        } finally {
            clean(dataSource, stream, generation, consumer)
        }
    }

    private fun clean(dataSource: javax.sql.DataSource, stream: String, generation: String, consumer: String) {
        dataSource.connection.use { connection ->
            listOf("live_trade_facts", "live_execution_facts", "live_order_state", "canonical_order_directory").forEach { table ->
                connection.prepareStatement("DELETE FROM postmatch.$table WHERE event_stream = ? AND source_generation = ?").use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, generation)
                    statement.executeUpdate()
                }
            }
            listOf("consumer_outcome_receipts", "consumer_source_coverage", "consumer_frontiers").forEach { table ->
                connection.prepareStatement("DELETE FROM postmatch.$table WHERE consumer_name = ? AND event_stream = ?").use { statement ->
                    statement.setString(1, consumer)
                    statement.setString(2, stream)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun source(stream: String, sequence: Long, orderId: String, commandType: String, result: String) = CanonicalOutcomeSource(
        eventStream = stream, partitionId = 0, streamSequence = sequence, batchId = "batch-$sequence",
        commandId = "command-$sequence", commandType = commandType, payloadHash = "hash-$sequence",
        instrumentId = "AAPL", orderId = orderId, resultStatus = "accepted", resultPayloadJson = result
    )

    private fun makerSubmit(maker: String) = """
        {"effectVersion":1,"accepted":{"eventId":"accept-maker","orderId":"$maker","occurredAt":"2026-09-26T00:00:00Z"},
         "acceptedOrder":{"orderId":"$maker","engineOrderId":"engine-maker","venueSessionId":"session-1",
         "instrumentId":"AAPL","participantId":"participant-maker","accountId":"account-maker","side":"SELL",
         "orderType":"LIMIT","quantityUnits":"10","limitPrice":"100","currency":"USD","timeInForce":"DAY",
         "acceptedAt":"2026-09-26T00:00:00Z"},
         "orderStates":[{"orderId":"$maker","instrumentId":"AAPL","side":"SELL","status":"ACCEPTED",
         "originalQuantity":"10","remainingQuantity":"10","limitPrice":"100","currency":"USD","lastUpdatedAt":"2026-09-26T00:00:00Z"}]}
    """.trimIndent()

    private fun takerSubmit(taker: String, maker: String) = """
        {"effectVersion":1,"accepted":{"eventId":"accept-taker","orderId":"$taker","occurredAt":"2026-09-26T00:00:01Z"},
         "acceptedOrder":{"orderId":"$taker","engineOrderId":"engine-taker","venueSessionId":"session-1",
         "instrumentId":"AAPL","participantId":"participant-taker","accountId":"account-taker","side":"BUY",
         "orderType":"LIMIT","quantityUnits":"6","limitPrice":"100","currency":"USD","timeInForce":"DAY",
         "acceptedAt":"2026-09-26T00:00:01Z"},
         "executions":[
           {"eventId":"exec-buy-event","executionId":"match-1-buy","orderId":"$taker","instrumentId":"AAPL","quantityUnits":"6","executionPrice":"100","currency":"USD","occurredAt":"2026-09-26T00:00:01Z","liquidityRole":"TAKER"},
           {"eventId":"exec-sell-event","executionId":"match-1-sell","orderId":"$maker","instrumentId":"AAPL","quantityUnits":"6","executionPrice":"100","currency":"USD","occurredAt":"2026-09-26T00:00:01Z","liquidityRole":"MAKER"}],
         "trades":[{"eventId":"trade-event","tradeId":"trade-1","executionId":"match-1","buyOrderId":"$taker","sellOrderId":"$maker","instrumentId":"AAPL","quantityUnits":"6","price":"100","currency":"USD","occurredAt":"2026-09-26T00:00:01Z"}],
         "orderStates":[
           {"orderId":"$taker","instrumentId":"AAPL","side":"BUY","status":"FILLED","originalQuantity":"6","remainingQuantity":"0","limitPrice":"100","currency":"USD","lastUpdatedAt":"2026-09-26T00:00:01Z"},
           {"orderId":"$maker","instrumentId":"AAPL","side":"SELL","status":"PARTIALLY_FILLED","originalQuantity":"10","remainingQuantity":"4","limitPrice":"100","currency":"USD","lastUpdatedAt":"2026-09-26T00:00:01Z"}]}
    """.trimIndent()

    private fun cancelMaker(maker: String) = """
        {"effectVersion":1,"accepted":{"eventId":"cancel-maker","orderId":"$maker","occurredAt":"2026-09-26T00:00:02Z"},
         "orderStates":[{"orderId":"$maker","instrumentId":"AAPL","side":"SELL","status":"CANCELLED",
         "originalQuantity":"10","remainingQuantity":"0","limitPrice":"100","currency":"USD","lastUpdatedAt":"2026-09-26T00:00:02Z"}]}
    """.trimIndent()
}

package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.application.postmatch.CanonicalSourceCoverageVerifier
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Runs with the isolated postmatch PostgreSQL service in schema-placement CI. */
class PostMatchMarketMaintainerIntegrationTest {
    @Test
    fun visibleMakerFillAndCancelAdvanceIndependentMarketFrontier() {
        val dataSource = testDataSource() ?: return
        val token = UUID.randomUUID().toString()
        val stream = "market-test-$token"
        val generation = "generation-$token"
        val liveConsumer = "live-$token"
        val marketConsumer = PostMatchMarketMaintainer.CONSUMER_NAME
        val visible = "visible-$token"
        val hidden = "hidden-$token"
        val taker = "taker-$token"
        val verifier = CanonicalSourceCoverageVerifier()
        val liveStore = PostMatchOperationalStore(dataSource)
        val writer = PostMatchLiveEffectWriter()
        val maintainer = PostMatchMarketMaintainer(dataSource)
        val sources = listOf(
            source(stream, 1, visible, "SubmitOrder", submit(visible, "SELL", "LIMIT", "10", "100")),
            source(stream, 2, hidden, "SubmitOrder", submit(hidden, "SELL", "LIMIT_HIDDEN", "8", "99")),
            source(stream, 3, taker, "SubmitOrder", take(taker, visible)),
            source(stream, 4, visible, "CancelOrder", cancel(visible))
        )
        try {
            sources.forEachIndexed { index, source ->
                val from = index.toLong()
                val window = verifier.verify(liveConsumer, stream, 0, generation, from, from + 1, listOf(source))
                assertEquals(PostMatchApplyResult.APPLIED, liveStore.apply(window, writer::apply))
            }
            assertEquals(MarketAdvanceResult.APPLIED, maintainer.applyNext(stream, 0, generation))
            assertAsk(dataSource, stream, generation, "100", "10")
            assertEquals(MarketAdvanceResult.APPLIED, maintainer.applyNext(stream, 0, generation))
            assertAsk(dataSource, stream, generation, "100", "10")
            assertEquals(MarketAdvanceResult.APPLIED, maintainer.applyNext(stream, 0, generation))
            assertAsk(dataSource, stream, generation, "100", "4")
            assertEquals(MarketAdvanceResult.APPLIED, maintainer.applyNext(stream, 0, generation))
            assertAsk(dataSource, stream, generation, null, null)
            assertEquals(MarketAdvanceResult.NO_WORK, maintainer.applyNext(stream, 0, generation))
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT last_stream_sequence FROM postmatch.consumer_frontiers WHERE consumer_name = ? AND event_stream = ?"
                ).use { statement ->
                    statement.setString(1, marketConsumer)
                    statement.setString(2, stream)
                    statement.executeQuery().use { rows -> check(rows.next()); assertEquals(4L, rows.getLong(1)) }
                }
                connection.prepareStatement(
                    "SELECT change_count FROM postmatch.live_market_change_windows WHERE event_stream = ? ORDER BY through_inclusive_sequence"
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows ->
                        assertEquals(listOf(1, 0, 1, 1), buildList { while (rows.next()) add(rows.getInt(1)) })
                    }
                }
            }
        } finally {
            clean(dataSource, stream, generation, liveConsumer, marketConsumer)
        }
    }

    @Test
    fun sourceGapDoesNotAdvanceMarketFrontier() {
        val dataSource = testDataSource() ?: return
        val token = UUID.randomUUID().toString()
        val stream = "market-test-$token"
        val generation = "generation-$token"
        val consumer = PostMatchMarketMaintainer.CONSUMER_NAME
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """INSERT INTO postmatch.live_market_change_windows(
                       event_stream, source_generation, partition_id, from_exclusive_sequence,
                       through_inclusive_sequence, source_digest, change_count) VALUES (?, ?, 0, 2, 3, ?, 0)"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, generation)
                    statement.setString(3, "a".repeat(64))
                    statement.executeUpdate()
                }
            }
            assertFailsWith<IllegalStateException> {
                PostMatchMarketMaintainer(dataSource).applyNext(stream, 0, generation)
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM postmatch.consumer_frontiers WHERE consumer_name = ?").use { statement ->
                    statement.setString(1, consumer)
                    statement.executeQuery().use { rows -> check(rows.next()); assertEquals(0L, rows.getLong(1)) }
                }
            }
        } finally {
            clean(dataSource, stream, generation, "unused-$token", consumer)
        }
    }

    @Test
    fun nonzeroPartitionStartsAtEncodedOrigin() {
        val dataSource = testDataSource() ?: return
        val token = UUID.randomUUID().toString()
        val stream = "market-test-$token"
        val generation = "generation-$token"
        val origin = CanonicalStreamPosition.origin(1)
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """INSERT INTO postmatch.live_market_change_windows(
                       event_stream, source_generation, partition_id, from_exclusive_sequence,
                       through_inclusive_sequence, source_digest, change_count) VALUES (?, ?, 1, ?, ?, ?, 0)"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, generation)
                    statement.setLong(3, origin)
                    statement.setLong(4, origin + 1)
                    statement.setString(5, "a".repeat(64))
                    statement.executeUpdate()
                }
            }
            val maintainer = PostMatchMarketMaintainer(dataSource)
            assertEquals(MarketAdvanceResult.APPLIED, maintainer.applyNext(stream, 1, generation))
            assertEquals(MarketAdvanceResult.NO_WORK, maintainer.applyNext(stream, 1, generation))
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT last_stream_sequence FROM postmatch.consumer_frontiers WHERE consumer_name = ? AND event_stream = ? AND partition_id = 1"
                ).use { statement ->
                    statement.setString(1, PostMatchMarketMaintainer.CONSUMER_NAME)
                    statement.setString(2, stream)
                    statement.executeQuery().use { rows -> check(rows.next()); assertEquals(origin + 1, rows.getLong(1)) }
                }
            }
        } finally {
            clean(dataSource, stream, generation, "unused-$token", PostMatchMarketMaintainer.CONSUMER_NAME)
        }
    }

    @Test
    fun sameSessionAndInstrumentStayIsolatedAcrossRuns() {
        val dataSource = testDataSource() ?: return
        val token = UUID.randomUUID().toString()
        val stream = "market-test-$token"
        val generation = "generation-$token"
        val liveConsumer = "live-$token"
        val marketConsumer = PostMatchMarketMaintainer.CONSUMER_NAME
        val verifier = CanonicalSourceCoverageVerifier()
        val liveStore = PostMatchOperationalStore(dataSource)
        val writer = PostMatchLiveEffectWriter()
        val maintainer = PostMatchMarketMaintainer(dataSource)
        try {
            listOf(
                source(stream, 1, "one-$token", "SubmitOrder", submit("one-$token", "SELL", "LIMIT", "10", "100")),
                source(stream, 2, "two-$token", "SubmitOrder", submit("two-$token", "SELL", "LIMIT", "7", "99", "run-2"))
            ).forEachIndexed { index, source ->
                val from = index.toLong()
                val window = verifier.verify(liveConsumer, stream, 0, generation, from, from + 1, listOf(source))
                assertEquals(PostMatchApplyResult.APPLIED, liveStore.apply(window, writer::apply))
                assertEquals(MarketAdvanceResult.APPLIED, maintainer.applyNext(stream, 0, generation))
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT run_id, best_ask_price, best_ask_quantity FROM postmatch.market_snapshots
                       WHERE event_stream = ? AND source_generation = ? ORDER BY run_id"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, generation)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals("run-1", rows.getString(1))
                        assertEquals("100", rows.getBigDecimal(2).stripTrailingZeros().toPlainString())
                        assertEquals("10", rows.getBigDecimal(3).stripTrailingZeros().toPlainString())
                        check(rows.next())
                        assertEquals("run-2", rows.getString(1))
                        assertEquals("99", rows.getBigDecimal(2).stripTrailingZeros().toPlainString())
                        assertEquals("7", rows.getBigDecimal(3).stripTrailingZeros().toPlainString())
                        check(!rows.next())
                    }
                }
            }
        } finally {
            clean(dataSource, stream, generation, liveConsumer, marketConsumer)
        }
    }

    private fun assertAsk(dataSource: DataSource, stream: String, generation: String, price: String?, quantity: String?) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT best_ask_price, best_ask_quantity FROM postmatch.market_snapshots
                   WHERE event_stream = ? AND source_generation = ? AND run_id = 'run-1' AND venue_session_id = 'session-1'
                     AND instrument_id = 'AAPL' AND currency = 'USD'"""
            ).use { statement ->
                statement.setString(1, stream)
                statement.setString(2, generation)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    val actualPrice = rows.getBigDecimal(1)?.stripTrailingZeros()?.toPlainString()
                    val actualQuantity = rows.getBigDecimal(2)?.stripTrailingZeros()?.toPlainString()
                    if (price == null) assertNull(actualPrice) else assertEquals(price, actualPrice)
                    if (quantity == null) assertNull(actualQuantity) else assertEquals(quantity, actualQuantity)
                }
            }
        }
    }

    private fun testDataSource(): DataSource? {
        val url = System.getenv("POSTMATCH_DB_URL_TEST") ?: return null
        val user = System.getenv("POSTMATCH_DB_USER_TEST") ?: return null
        val password = System.getenv("POSTMATCH_DB_PASSWORD_TEST") ?: return null
        return RuntimeDataSources.dataSource(url, user, password, "postmatch-market-integration")
    }

    private fun clean(dataSource: DataSource, stream: String, generation: String, liveConsumer: String, marketConsumer: String) {
        dataSource.connection.use { connection ->
            listOf("market_snapshots", "market_price_levels", "live_market_order_changes", "live_market_change_windows",
                "live_trade_facts", "live_execution_facts", "live_order_state", "canonical_order_directory")
                .forEach { table ->
                    connection.prepareStatement("DELETE FROM postmatch.$table WHERE event_stream = ? AND source_generation = ?").use { statement ->
                        statement.setString(1, stream)
                        statement.setString(2, generation)
                        statement.executeUpdate()
                    }
                }
            listOf("consumer_outcome_receipts", "consumer_source_coverage", "consumer_frontiers").forEach { table ->
                connection.prepareStatement("DELETE FROM postmatch.$table WHERE event_stream = ? AND consumer_name IN (?, ?)").use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, liveConsumer)
                    statement.setString(3, marketConsumer)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun source(stream: String, sequence: Long, orderId: String, commandType: String, payload: String) = CanonicalOutcomeSource(
        eventStream = stream, partitionId = 0, streamSequence = sequence, batchId = "batch-$sequence",
        commandId = "command-$sequence", commandType = commandType, payloadHash = "hash-$sequence",
        instrumentId = "AAPL", orderId = orderId, resultStatus = "accepted", resultPayloadJson = payload
    )

    private fun submit(orderId: String, side: String, orderType: String, quantity: String, price: String, runId: String = "run-1") = """
        {"effectVersion":1,"accepted":{"eventId":"accepted-$orderId","orderId":"$orderId","occurredAt":"2026-09-26T00:00:00Z"},
         "acceptedOrder":{"orderId":"$orderId","engineOrderId":"engine-$orderId","runId":"$runId","venueSessionId":"session-1",
         "instrumentId":"AAPL","participantId":"participant-$orderId","accountId":"account-$orderId","side":"$side",
         "orderType":"$orderType","quantityUnits":"$quantity","limitPrice":"$price","currency":"USD","timeInForce":"DAY",
         "acceptedAt":"2026-09-26T00:00:00Z"},
         "orderStates":[{"orderId":"$orderId","instrumentId":"AAPL","side":"$side","status":"ACCEPTED",
         "originalQuantity":"$quantity","remainingQuantity":"$quantity","limitPrice":"$price","currency":"USD",
         "lastUpdatedAt":"2026-09-26T00:00:00Z"}]}
    """.trimIndent()

    private fun take(taker: String, maker: String) = """
        {"effectVersion":1,"accepted":{"eventId":"accepted-$taker","orderId":"$taker","occurredAt":"2026-09-26T00:00:01Z"},
         "acceptedOrder":{"orderId":"$taker","engineOrderId":"engine-$taker","runId":"run-1","venueSessionId":"session-1",
         "instrumentId":"AAPL","participantId":"participant-$taker","accountId":"account-$taker","side":"BUY",
         "orderType":"LIMIT","quantityUnits":"6","limitPrice":"100","currency":"USD","timeInForce":"DAY",
         "acceptedAt":"2026-09-26T00:00:01Z"},
         "executions":[
           {"eventId":"exec-buy-$taker","executionId":"match-1-buy","orderId":"$taker","instrumentId":"AAPL","quantityUnits":"6","executionPrice":"100","currency":"USD","occurredAt":"2026-09-26T00:00:01Z","liquidityRole":"TAKER"},
           {"eventId":"exec-sell-$maker","executionId":"match-1-sell","orderId":"$maker","instrumentId":"AAPL","quantityUnits":"6","executionPrice":"100","currency":"USD","occurredAt":"2026-09-26T00:00:01Z","liquidityRole":"MAKER"}],
         "trades":[{"eventId":"trade-1","tradeId":"trade-1","executionId":"match-1","buyOrderId":"$taker","sellOrderId":"$maker","instrumentId":"AAPL","quantityUnits":"6","price":"100","currency":"USD","occurredAt":"2026-09-26T00:00:01Z"}],
         "orderStates":[
           {"orderId":"$taker","instrumentId":"AAPL","side":"BUY","status":"FILLED","originalQuantity":"6","remainingQuantity":"0","limitPrice":"100","currency":"USD","lastUpdatedAt":"2026-09-26T00:00:01Z"},
           {"orderId":"$maker","instrumentId":"AAPL","side":"SELL","status":"PARTIALLY_FILLED","originalQuantity":"10","remainingQuantity":"4","limitPrice":"100","currency":"USD","lastUpdatedAt":"2026-09-26T00:00:01Z"}]}
    """.trimIndent()

    private fun cancel(orderId: String) = """
        {"effectVersion":1,"accepted":{"eventId":"cancel-$orderId","orderId":"$orderId","occurredAt":"2026-09-26T00:00:02Z"},
         "orderStates":[{"orderId":"$orderId","instrumentId":"AAPL","side":"SELL","status":"CANCELLED",
         "originalQuantity":"10","remainingQuantity":"0","limitPrice":"100","currency":"USD",
         "lastUpdatedAt":"2026-09-26T00:00:02Z"}]}
    """.trimIndent()
}

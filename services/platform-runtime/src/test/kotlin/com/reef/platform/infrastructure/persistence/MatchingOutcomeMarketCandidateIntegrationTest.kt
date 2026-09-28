package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class MatchingOutcomeMarketCandidateIntegrationTest {
    @Test
    fun directMatchingOutcomesBuildDepthAndKeepExecutedTapeIndependently() {
        withDatabase { dataSource, schema, stream ->
            val sources = listOf(
                maker(stream, 1, "seller-1", "50"),
                maker(stream, 2, "seller-2", "51"),
                taker(stream, 3, "buyer-1", "seller-1"))
            insertSources(dataSource, sources)
            val market = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema)
            market.initialize(stream, 0, "source-generation", "projection-a")
            val lagging = market.readBook(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD", depth = 2)
            assertEquals(3, lagging.frontier.lagPositions)
            assertEquals(3, market.readVector(stream, "projection-a",
                mapOf(0 to "source-generation")).frontiers.getValue(0).lagPositions)
            assertFailsWith<IllegalStateException> {
                market.readBook(stream, 0, "projection-a", "source-generation",
                    "run", "session", "AAPL", "USD", requireCurrent = true)
            }
            assertEquals(MatchingMarketAdvance.APPLIED,
                market.advanceNext(stream, 0, "source-generation", "projection-a", maxOutcomes = 1))
            assertEquals(MatchingMarketAdvance.APPLIED,
                market.advanceNext(stream, 0, "source-generation", "projection-a", maxOutcomes = 1))
            val depth = market.readBook(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD", depth = 2)
            assertEquals(listOf(BigDecimal("50"), BigDecimal("51")), depth.asks.map { it.price })
            assertEquals(listOf(BigDecimal.ONE, BigDecimal.ONE), depth.asks.map { it.quantity })
            assertEquals(1, depth.frontier.lagPositions)
            assertEquals(MatchingMarketAdvance.APPLIED,
                market.advanceNext(stream, 0, "source-generation", "projection-a", maxOutcomes = 1))
            assertEquals(MatchingMarketAdvance.NO_WORK,
                market.advanceNext(stream, 0, "source-generation", "projection-a", maxOutcomes = 1))
            val current = market.readBook(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD", depth = 2, requireCurrent = true)
            assertEquals(listOf(BigDecimal("51")), current.asks.map { it.price })
            assertTrue(current.bids.isEmpty())
            assertEquals(3, current.frontier.projectedSequence)
            assertEquals(0, current.frontier.lagPositions)
            assertEquals(0, market.readVector(stream, "projection-a",
                mapOf(0 to "source-generation"), requireCurrent = true)
                .frontiers.getValue(0).lagPositions)
            val tape = market.readTape(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD", requireCurrent = true)
            assertEquals(listOf("trade-3"), tape.trades.map { it.tradeId })
            assertEquals(3, tape.trades.single().sourceSequence)
            assertEquals(0, market.readTape(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD", beforeSequence = 3,
                beforeOrdinal = tape.trades.single().effectOrdinal).trades.size)
            market.verifyLastWindow(stream, 0, "source-generation", "projection-a")
            market.verifySourcePrefix(stream, 0, "source-generation", "projection-a", 3)
            assertFailsWith<IllegalStateException> {
                market.verifySourcePrefix(stream, 0, "source-generation", "projection-a", 2)
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE runtime.canonical_command_outcomes
                       SET result_payload = jsonb_set(result_payload,
                           '{accepted,occurredAt}', '"2026-09-28T00:00:02Z"'::jsonb)
                       WHERE event_stream = ? AND stream_sequence = 1"""
                ).use { statement ->
                    statement.setString(1, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            market.verifyLastWindow(stream, 0, "source-generation", "projection-a")
            assertFailsWith<IllegalStateException> {
                market.verifySourcePrefix(stream, 0, "source-generation", "projection-a", 3)
            }
            val restarted = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema)
            assertFailsWith<IllegalStateException> {
                restarted.advanceNext(stream, 0, "source-generation", "projection-a")
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE runtime.canonical_command_outcomes
                       SET result_payload = ?::jsonb
                       WHERE event_stream = ? AND stream_sequence = 1"""
                ).use { statement ->
                    statement.setString(1, sources.first().resultPayloadJson)
                    statement.setString(2, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            val tooSmallRecoveryBound = MatchingOutcomeMarketCandidate(dataSource, dataSource,
                schema, maxRecoveryWindows = 2)
            assertFailsWith<IllegalStateException> {
                tooSmallRecoveryBound.advanceNext(stream, 0, "source-generation", "projection-a")
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE runtime.canonical_command_outcomes
                       SET result_payload = jsonb_set(result_payload,
                           '{trades,0,price}', '"51"'::jsonb)
                       WHERE event_stream = ? AND stream_sequence = 3"""
                ).use { statement ->
                    statement.setString(1, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<Exception> {
                market.verifyLastWindow(stream, 0, "source-generation", "projection-a")
            }
            // Source tampering cannot rewrite a previously projected executed trade.
            assertEquals(listOf("trade-3"), market.readTape(stream, 0, "projection-a",
                "source-generation", "run", "session", "AAPL", "USD").trades.map { it.tradeId })
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE runtime.canonical_command_outcomes
                       SET result_payload = ?::jsonb
                       WHERE event_stream = ? AND stream_sequence = 3"""
                ).use { statement ->
                    statement.setString(1, sources.last().resultPayloadJson)
                    statement.setString(2, stream)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.prepareStatement(
                    """DELETE FROM $schema.matching_market_candidate_windows
                       WHERE event_stream = ? AND through_inclusive_sequence = 3"""
                ).use { statement ->
                    statement.setString(1, stream)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> {
                market.verifyLastWindow(stream, 0, "source-generation", "projection-a")
            }
        }
    }

    @Test
    fun gapAndCrashNeverAdvanceMarketFrontier() {
        withDatabase { dataSource, schema, stream ->
            insertSources(dataSource, listOf(maker(stream, 1, "seller-1", "50"),
                maker(stream, 3, "seller-2", "51")))
            val market = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema)
            assertFailsWith<IllegalArgumentException> {
                market.advanceNext(stream, 0, "source-generation", "projection-a")
            }
            assertEquals(0, market.readBook(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD").frontier.projectedSequence)
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """DELETE FROM runtime.canonical_command_outcomes
                       WHERE event_stream = ? AND stream_sequence = 3"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeUpdate()
                }
            }
            val crashing = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema,
                beforeCommit = { error("projector crash") })
            assertFailsWith<IllegalStateException> {
                crashing.advanceNext(stream, 0, "source-generation", "projection-a")
            }
            val before = market.readBook(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD")
            assertEquals(0, before.frontier.projectedSequence)
            assertTrue(before.asks.isEmpty())
            market.advanceNext(stream, 0, "source-generation", "projection-a")
            assertEquals(listOf(BigDecimal("50")), market.readBook(stream, 0,
                "projection-a", "source-generation", "run", "session", "AAPL", "USD",
                requireCurrent = true).asks.map { it.price })
            assertFailsWith<IllegalStateException> {
                market.advanceNext(stream, 0, "other-source-generation", "projection-a")
            }
        }
    }

    @Test
    fun acceptedNonLimitOrderNeverBecomesRestingDepth() {
        withDatabase { dataSource, schema, stream ->
            insertSources(dataSource, listOf(maker(stream, 1, "market-order", "0", "MARKET")))
            val market = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema)
            assertEquals(MatchingMarketAdvance.APPLIED,
                market.advanceNext(stream, 0, "source-generation", "projection-a"))
            val book = market.readBook(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD", requireCurrent = true)
            assertTrue(book.asks.isEmpty())
        }
    }

    @Test
    fun filledStateWithoutExecutionCannotEraseBookOrAdvanceCoverage() {
        withDatabase { dataSource, schema, stream ->
            val malformed = source(stream, 2, "seller-1",
                """{"effectVersion":1,"accepted":{"eventId":"accept-2","orderId":"seller-1","occurredAt":"2026-09-28T00:00:01Z"},"orderStates":[${state("seller-1", "SELL", "FILLED", "0", "50") }]}""")
                .copy(commandType = "ModifyOrder")
            insertSources(dataSource, listOf(maker(stream, 1, "seller-1", "50"), malformed))
            val market = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema)
            assertEquals(MatchingMarketAdvance.APPLIED,
                market.advanceNext(stream, 0, "source-generation", "projection-a",
                    maxOutcomes = 1))
            assertFailsWith<IllegalStateException> {
                market.advanceNext(stream, 0, "source-generation", "projection-a",
                    maxOutcomes = 1)
            }
            val book = market.readBook(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD")
            assertEquals(1, book.frontier.projectedSequence)
            assertEquals(listOf(BigDecimal("50")), book.asks.map { it.price })
            assertTrue(market.readTape(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD").trades.isEmpty())
        }
    }

    private fun maker(stream: String, sequence: Long, order: String, price: String,
        orderType: String = "LIMIT"):
        CanonicalOutcomeSource {
        val payload = """{"effectVersion":1,"accepted":{"eventId":"accept-$sequence","orderId":"$order","occurredAt":"2026-09-28T00:00:00Z"},"acceptedOrder":${identity(order, "seller", "SELL", price, orderType)},"orderStates":[${state(order, "SELL", "ACCEPTED", "1", price)}]}"""
        return source(stream, sequence, order, payload)
    }

    private fun taker(stream: String, sequence: Long, buyer: String, sellerOrder: String):
        CanonicalOutcomeSource {
        val order = "$buyer-order"
        val trade = "trade-$sequence"
        val at = "2026-09-28T00:00:01Z"
        val payload = """{"effectVersion":1,"accepted":{"eventId":"accept-$sequence","orderId":"$order","occurredAt":"$at"},"acceptedOrder":${identity(order, buyer, "BUY", "50")},"executions":[{"eventId":"buy-$trade","executionId":"$trade-buy","orderId":"$order","instrumentId":"AAPL","quantityUnits":"1","executionPrice":"50","currency":"USD","occurredAt":"$at","liquidityRole":"TAKER"},{"eventId":"sell-$trade","executionId":"$trade-sell","orderId":"$sellerOrder","instrumentId":"AAPL","quantityUnits":"1","executionPrice":"50","currency":"USD","occurredAt":"$at","liquidityRole":"MAKER"}],"trades":[{"eventId":"event-$trade","tradeId":"$trade","executionId":"$trade","buyOrderId":"$order","sellOrderId":"$sellerOrder","instrumentId":"AAPL","quantityUnits":"1","price":"50","currency":"USD","occurredAt":"$at"}],"orderStates":[${state(order, "BUY", "FILLED", "0", "50")},${state(sellerOrder, "SELL", "FILLED", "0", "50")}]}"""
        return source(stream, sequence, order, payload)
    }

    private fun identity(order: String, owner: String, side: String, price: String,
        orderType: String = "LIMIT") =
        """{"orderId":"$order","engineOrderId":"engine-$order","clientOrderId":"client-$order","runId":"run","venueSessionId":"session","instrumentId":"AAPL","participantId":"$owner","accountId":"$owner-account","side":"$side","orderType":"$orderType","quantityUnits":"1","limitPrice":"$price","currency":"USD","timeInForce":"DAY","acceptedAt":"2026-09-28T00:00:00Z"}"""

    private fun state(order: String, side: String, status: String, remaining: String, price: String) =
        """{"orderId":"$order","instrumentId":"AAPL","side":"$side","status":"$status","originalQuantity":"1","remainingQuantity":"$remaining","limitPrice":"$price","currency":"USD","lastUpdatedAt":"2026-09-28T00:00:01Z"}"""

    private fun source(stream: String, sequence: Long, order: String, payload: String) =
        CanonicalOutcomeSource(stream, 0, sequence, "batch-$sequence", "command-$sequence",
            "SubmitOrder", "hash-$sequence", "AAPL", order, "accepted", payload)

    private fun insertSources(dataSource: DataSource, sources: List<CanonicalOutcomeSource>) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO runtime.canonical_venue_event_batches
                   (event_stream,batch_id,partition_id,first_sequence,last_sequence)
                   VALUES (?,?,?,?,?)"""
            ).use { batches ->
                connection.prepareStatement(
                    """INSERT INTO runtime.canonical_command_outcomes
                       (event_stream,partition_id,stream_sequence,batch_id,command_id,
                        command_type,payload_hash,instrument_id,order_id,result_status,result_payload)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)"""
                ).use { outcomes ->
                    sources.forEach { source ->
                        batches.setString(1, source.eventStream)
                        batches.setString(2, source.batchId)
                        batches.setInt(3, source.partitionId)
                        batches.setLong(4, source.streamSequence)
                        batches.setLong(5, source.streamSequence)
                        batches.executeUpdate()
                        outcomes.setString(1, source.eventStream)
                        outcomes.setInt(2, source.partitionId)
                        outcomes.setLong(3, source.streamSequence)
                        outcomes.setString(4, source.batchId)
                        outcomes.setString(5, source.commandId)
                        outcomes.setString(6, source.commandType)
                        outcomes.setString(7, source.payloadHash)
                        outcomes.setString(8, source.instrumentId)
                        outcomes.setString(9, source.orderId)
                        outcomes.setString(10, source.resultStatus)
                        outcomes.setString(11, source.resultPayloadJson)
                        outcomes.executeUpdate()
                    }
                }
            }
        }
    }

    private fun withDatabase(run: (DataSource, String, String) -> Unit) {
        val url = System.getenv("SETTLEMENT_POSTGRES_JDBC_URL_TEST")
        val user = System.getenv("SETTLEMENT_POSTGRES_USER_TEST")
        val password = System.getenv("SETTLEMENT_POSTGRES_PASSWORD_TEST")
        assumeTrue(url != null && user != null && password != null,
            "disposable settlement PostgreSQL test database required")
        val dataSource = RuntimeDataSources.dataSource(url, user, password, "matching-market-candidate")
        val schema = "market_candidate_test_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
                statement.execute("CREATE SCHEMA runtime")
                statement.execute("""CREATE TABLE runtime.canonical_venue_event_batches (
                    event_stream TEXT NOT NULL, batch_id TEXT NOT NULL, partition_id INTEGER NOT NULL,
                    first_sequence BIGINT NOT NULL, last_sequence BIGINT NOT NULL)""")
                statement.execute("""CREATE TABLE runtime.canonical_command_outcomes (
                    event_stream TEXT NOT NULL, partition_id INTEGER NOT NULL,
                    stream_sequence BIGINT NOT NULL, batch_id TEXT NOT NULL,
                    command_id TEXT NOT NULL, command_type TEXT NOT NULL,
                    payload_hash TEXT NOT NULL, instrument_id TEXT NOT NULL,
                    order_id TEXT NOT NULL, result_status TEXT NOT NULL,
                    result_payload JSONB NOT NULL)""")
                statement.execute("""CREATE INDEX ON runtime.canonical_command_outcomes
                    (event_stream, partition_id, stream_sequence DESC)""")
            }
        }
        try {
            val migration = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .map { it.resolve("scripts/dev/db/migrations/postmatch/0006_matching_outcome_market_candidate.sql") }
                .first(Files::exists)
            val sql = Files.readString(migration).lineSequence()
                .filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                .replace("postmatch.", "$schema.")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    sql.split(';').map(String::trim).filter(String::isNotBlank)
                        .forEach(statement::execute)
                }
            }
            run(dataSource, schema, "market-${UUID.randomUUID()}")
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA runtime CASCADE")
                    statement.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }
}

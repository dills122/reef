package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalStreamPosition
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test
    fun rollbackOnlyReplayRejectsTargetOrderLevelAndTapeTamper() {
        withDatabase { dataSource, schema, stream ->
            insertSources(dataSource, listOf(
                maker(stream, 1, "seller-1", "50"),
                maker(stream, 2, "seller-2", "51"),
                taker(stream, 3, "buyer-1", "seller-1")))
            val market = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema)
            repeat(3) {
                assertEquals(MatchingMarketAdvance.APPLIED,
                    market.advanceNext(stream, 0, "source-generation", "projection-a",
                        maxOutcomes = 1))
            }
            market.verifyTargetReplay(stream, 0, "source-generation", "projection-a",
                maxWindows = 3, maxSourceBytes = 100_000, maxRows = 100)
            assertEquals(0L, proofFrontierCount(dataSource, schema, stream))
            val restartedRead = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema)
            assertEquals(listOf(BigDecimal("51")), restartedRead.readBook(stream, 0,
                "projection-a", "source-generation", "run", "session", "AAPL", "USD",
                requireCurrent = true).asks.map { it.price })
            val expectedDigest = restartedRead.readBook(stream, 0, "projection-a",
                "source-generation", "run", "session", "AAPL", "USD")
                .frontier.lastWindowDigest!!
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """UPDATE $schema.matching_market_candidate_frontiers
                       SET last_window_digest = ? WHERE event_stream = ?
                         AND projector_generation = 'projection-a'"""
                ).use { statement ->
                    statement.setString(1, "f".repeat(64))
                    statement.setString(2, stream)
                    assertEquals(1, statement.executeUpdate())
                    assertFailsWith<IllegalStateException> {
                        restartedRead.readBook(stream, 0, "projection-a", "source-generation",
                            "run", "session", "AAPL", "USD")
                    }
                    statement.setString(1, expectedDigest)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            restartedRead.readBook(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD", requireCurrent = true)
            assertFailsWith<IllegalStateException> {
                market.verifyTargetReplay(stream, 0, "source-generation", "projection-a",
                    maxWindows = 2)
            }
            assertFailsWith<Exception> {
                market.verifyTargetReplay(stream, 0, "source-generation", "projection-a",
                    maxSourceBytes = 1)
            }
            assertFailsWith<IllegalStateException> {
                market.verifyTargetReplay(stream, 0, "source-generation", "projection-a",
                    maxRows = 1)
            }
            assertEquals(0L, proofFrontierCount(dataSource, schema, stream))

            fun update(sql: String) {
                dataSource.connection.use { connection ->
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, stream)
                        assertEquals(1, statement.executeUpdate())
                    }
                }
            }
            fun rejectsTarget() {
                assertFailsWith<IllegalStateException> {
                    market.verifyTargetReplay(stream, 0, "source-generation", "projection-a",
                        maxWindows = 3, maxSourceBytes = 100_000, maxRows = 100)
                }
                assertEquals(0L, proofFrontierCount(dataSource, schema, stream))
                val restarted = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema)
                assertFailsWith<IllegalStateException> {
                    restarted.advanceNext(stream, 0, "source-generation", "projection-a")
                }
                assertEquals(0L, proofFrontierCount(dataSource, schema, stream))
            }

            update("""UPDATE $schema.matching_market_candidate_orders
                SET status = 'CANCELLED', remaining_quantity = 0
                WHERE event_stream = ? AND order_id = 'seller-2'""")
            rejectsTarget()
            update("""UPDATE $schema.matching_market_candidate_orders
                SET status = 'ACCEPTED', remaining_quantity = 1
                WHERE event_stream = ? AND order_id = 'seller-2'""")
            market.verifyTargetReplay(stream, 0, "source-generation", "projection-a")

            update("""UPDATE $schema.matching_market_candidate_levels
                SET quantity = 2 WHERE event_stream = ? AND price = 51""")
            rejectsTarget()
            update("""UPDATE $schema.matching_market_candidate_levels
                SET quantity = 1 WHERE event_stream = ? AND price = 51""")
            market.verifyTargetReplay(stream, 0, "source-generation", "projection-a")

            update("""UPDATE $schema.matching_market_candidate_tape
                SET price = 49 WHERE event_stream = ? AND trade_id = 'trade-3'""")
            rejectsTarget()
            update("""UPDATE $schema.matching_market_candidate_tape
                SET price = 50 WHERE event_stream = ? AND trade_id = 'trade-3'""")
            market.verifyTargetReplay(stream, 0, "source-generation", "projection-a")
            assertEquals(0L, proofFrontierCount(dataSource, schema, stream))
        }
    }

    @Test
    fun emptyOriginReplayRejectsInjectedRowsBeforeReadOrAdvance() {
        withDatabase { dataSource, schema, stream ->
            val market = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema)
            market.initialize(stream, 0, "source-generation", "projection-a")
            fun mutate(sql: String) {
                dataSource.connection.use { connection ->
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, stream)
                        assertEquals(1, statement.executeUpdate())
                    }
                }
            }
            mutate("""INSERT INTO $schema.matching_market_candidate_orders
                (event_stream, partition_id, projector_generation, order_id, run_id,
                 venue_session_id, instrument_id, currency, side, order_type, status,
                 original_quantity, remaining_quantity, filled_quantity, limit_price)
                VALUES (?, 0, 'projection-a', 'injected', 'run', 'session', 'AAPL',
                        'USD', 'SELL', 'LIMIT', 'ACCEPTED', 1, 1, 0, 50)""")
            assertFailsWith<IllegalStateException> {
                market.readBook(stream, 0, "projection-a", "source-generation",
                    "run", "session", "AAPL", "USD")
            }
            mutate("""DELETE FROM $schema.matching_market_candidate_orders
                WHERE event_stream = ? AND order_id = 'injected'""")
            mutate("""INSERT INTO $schema.matching_market_candidate_levels
                (event_stream, partition_id, projector_generation, run_id,
                 venue_session_id, instrument_id, currency, side, price, quantity)
                VALUES (?, 0, 'projection-a', 'run', 'session', 'AAPL',
                        'USD', 'SELL', 50, 1)""")
            assertFailsWith<IllegalStateException> {
                market.advanceNext(stream, 0, "source-generation", "projection-a")
            }
            mutate("""DELETE FROM $schema.matching_market_candidate_levels
                WHERE event_stream = ? AND price = 50""")
            mutate("""INSERT INTO $schema.matching_market_candidate_tape
                (event_stream, partition_id, projector_generation, run_id, venue_session_id,
                 instrument_id, currency, trade_id, event_id, execution_id, quantity_units,
                 price, occurred_at_text, source_generation, source_stream_sequence,
                 source_effect_ordinal)
                VALUES (?, 0, 'projection-a', 'run', 'session', 'AAPL', 'USD',
                        'injected', 'event', 'execution', 1, 50, '2026-09-28T00:00:00Z',
                        'source-generation', 1, 0)""")
            assertFailsWith<IllegalStateException> {
                market.readTape(stream, 0, "projection-a", "source-generation",
                    "run", "session", "AAPL", "USD")
            }
            mutate("""DELETE FROM $schema.matching_market_candidate_tape
                WHERE event_stream = ? AND trade_id = 'injected'""")
            assertTrue(market.readBook(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD", requireCurrent = true).asks.isEmpty())
            assertEquals(MatchingMarketAdvance.NO_WORK,
                market.advanceNext(stream, 0, "source-generation", "projection-a"))
            assertEquals(0L, proofFrontierCount(dataSource, schema, stream))
        }
    }

    @Test
    fun changedFrontierBetweenTrustAndReadOrAppendFailsClosed() {
        withDatabase { dataSource, schema, stream ->
            insertSources(dataSource, listOf(maker(stream, 1, "seller-1", "50")))
            val primary = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema)
            assertEquals(MatchingMarketAdvance.APPLIED,
                primary.advanceNext(stream, 0, "source-generation", "projection-a"))
            val savedDigest = primary.readBook(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD").frontier.lastWindowDigest
            fun setDigest(value: String?) {
                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """UPDATE $schema.matching_market_candidate_frontiers
                           SET last_window_digest = ? WHERE event_stream = ?
                             AND projector_generation = 'projection-a'"""
                    ).use { statement ->
                        statement.setString(1, value)
                        statement.setString(2, stream)
                        assertEquals(1, statement.executeUpdate())
                    }
                }
            }
            val changed = "f".repeat(64)
            val readRacer = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema,
                afterTrustBeforeRead = { setDigest(changed) })
            assertFailsWith<IllegalStateException> {
                readRacer.readBook(stream, 0, "projection-a", "source-generation",
                    "run", "session", "AAPL", "USD")
            }
            setDigest(savedDigest)
            val vectorRacer = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema,
                afterTrustBeforeRead = { setDigest(changed) })
            assertFailsWith<IllegalStateException> {
                vectorRacer.readVector(stream, "projection-a", mapOf(0 to "source-generation"))
            }
            setDigest(savedDigest)

            insertSources(dataSource, listOf(maker(stream, 2, "seller-2", "51")))
            val appendRacer = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema,
                afterTrustBeforeApply = { setDigest(changed) })
            assertFailsWith<IllegalStateException> {
                appendRacer.advanceNext(stream, 0, "source-generation", "projection-a",
                    maxOutcomes = 1)
            }
            setDigest(savedDigest)
            val book = primary.readBook(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD", depth = 2)
            assertEquals(1, book.frontier.projectedSequence)
            assertEquals(listOf(BigDecimal("50")), book.asks.map { it.price })
            assertEquals(MatchingMarketAdvance.APPLIED,
                primary.advanceNext(stream, 0, "source-generation", "projection-a",
                    maxOutcomes = 1))
            assertEquals(listOf(BigDecimal("50"), BigDecimal("51")),
                primary.readBook(stream, 0, "projection-a", "source-generation",
                    "run", "session", "AAPL", "USD", depth = 2).asks.map { it.price })
        }
    }

    @Test
    fun normalFrontierAdvanceBetweenTrustAndReadRetriesBookAndVector() {
        withDatabase { dataSource, schema, stream ->
            insertSources(dataSource, listOf(maker(stream, 1, "seller-1", "50")))
            val writer = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema)
            assertEquals(MatchingMarketAdvance.APPLIED,
                writer.advanceNext(stream, 0, "source-generation", "projection-a"))

            insertSources(dataSource, listOf(maker(stream, 2, "seller-2", "51")))
            var bookHooks = 0
            val bookReader = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema,
                afterTrustBeforeRead = {
                    if (bookHooks++ == 0) assertEquals(MatchingMarketAdvance.APPLIED,
                        writer.advanceNext(stream, 0, "source-generation", "projection-a"))
                })
            val book = bookReader.readBook(stream, 0, "projection-a", "source-generation",
                "run", "session", "AAPL", "USD", depth = 2)
            assertEquals(2, bookHooks)
            assertEquals(2L, book.frontier.projectedSequence)
            assertEquals(listOf(BigDecimal("50"), BigDecimal("51")),
                book.asks.map { it.price })

            insertSources(dataSource, listOf(maker(stream, 3, "seller-3", "52")))
            var vectorHooks = 0
            val vectorReader = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema,
                afterTrustBeforeRead = {
                    if (vectorHooks++ == 0) assertEquals(MatchingMarketAdvance.APPLIED,
                        writer.advanceNext(stream, 0, "source-generation", "projection-a"))
                })
            val vector = vectorReader.readVector(stream, "projection-a",
                mapOf(0 to "source-generation"))
            assertEquals(2, vectorHooks)
            assertEquals(3L, vector.frontiers.getValue(0).projectedSequence)
            assertEquals(0L, vector.frontiers.getValue(0).lagPositions)
        }
    }

    @Test
    fun continuouslyAdvancingFrontierStopsAfterBoundedReadRetries() {
        withDatabase { dataSource, schema, stream ->
            insertSources(dataSource, (1..4).map { index ->
                maker(stream, index.toLong(), "seller-$index", "${49 + index}")
            })
            val writer = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema)
            assertEquals(MatchingMarketAdvance.APPLIED,
                writer.advanceNext(stream, 0, "source-generation", "projection-a", maxOutcomes = 1))
            var hooks = 0
            val reader = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema,
                afterTrustBeforeRead = {
                    hooks++
                    assertEquals(MatchingMarketAdvance.APPLIED,
                        writer.advanceNext(stream, 0, "source-generation", "projection-a", maxOutcomes = 1))
                })

            val failure = assertFailsWith<IllegalStateException> {
                reader.readBook(stream, 0, "projection-a", "source-generation",
                    "run", "session", "AAPL", "USD")
            }
            assertTrue(failure.message.orEmpty().contains("market frontier advanced after read trust proof"))
            assertEquals(3, hooks)
        }
    }

    @Test
    fun liveAppendProgressesDuringRecoveryProofWithoutServingStaleSnapshot() {
        withDatabase { dataSource, schema, stream ->
            insertSources(dataSource, listOf(maker(stream, 1, "seller-1", "50")))
            val live = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema)
            assertEquals(MatchingMarketAdvance.APPLIED,
                live.advanceNext(stream, 0, "source-generation", "projection-a"))
            insertSources(dataSource, listOf(maker(stream, 2, "seller-2", "51")))

            val snapshotPinned = CountDownLatch(1)
            val releaseProof = CountDownLatch(1)
            val recovering = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema,
                afterProofSnapshotBeforeReplay = {
                    snapshotPinned.countDown()
                    check(releaseProof.await(10, TimeUnit.SECONDS)) {
                        "market recovery proof test barrier timed out"
                    }
                })
            val executor = Executors.newFixedThreadPool(2)
            try {
                val read = executor.submit<Throwable?> {
                    runCatching {
                        recovering.readBook(stream, 0, "projection-a", "source-generation",
                            "run", "session", "AAPL", "USD")
                    }.exceptionOrNull()
                }
                assertTrue(snapshotPinned.await(5, TimeUnit.SECONDS),
                    "market recovery proof did not pin target snapshot")
                val append = executor.submit<MatchingMarketAdvance> {
                    live.advanceNext(stream, 0, "source-generation", "projection-a",
                        maxOutcomes = 1)
                }
                // This must finish while proof transaction remains open.
                assertEquals(MatchingMarketAdvance.APPLIED, append.get(5, TimeUnit.SECONDS))
                releaseProof.countDown()
                assertTrue(read.get(5, TimeUnit.SECONDS) is IllegalStateException)
                assertEquals(listOf(BigDecimal("50"), BigDecimal("51")),
                    recovering.readBook(stream, 0, "projection-a", "source-generation",
                        "run", "session", "AAPL", "USD", depth = 2,
                        requireCurrent = true).asks.map { it.price })
            } finally {
                releaseProof.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun pausedRecoveryProofDoesNotBlockUnrelatedPartitionOnSameInstance() {
        withDatabase { dataSource, schema, stream ->
            val secondOrigin = CanonicalStreamPosition.origin(1)
            insertSources(dataSource, listOf(maker(stream, 1, "a-seller", "50"),
                maker(stream, secondOrigin + 1, "b-seller-1", "60", partition = 1)))
            val seeder = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema)
            assertEquals(MatchingMarketAdvance.APPLIED,
                seeder.advanceNext(stream, 0, "source-generation", "projection-a"))

            val pauseProof = AtomicBoolean(false)
            val snapshotPinned = CountDownLatch(1)
            val releaseProof = CountDownLatch(1)
            val shared = MatchingOutcomeMarketCandidate(dataSource, dataSource, schema,
                afterProofSnapshotBeforeReplay = {
                    if (pauseProof.get()) {
                        snapshotPinned.countDown()
                        check(releaseProof.await(10, TimeUnit.SECONDS)) {
                            "market recovery proof test barrier timed out"
                        }
                    }
                })
            assertEquals(MatchingMarketAdvance.APPLIED,
                shared.advanceNext(stream, 1, "source-generation", "projection-a"))
            insertSources(dataSource, listOf(maker(stream, secondOrigin + 2,
                "b-seller-2", "61", partition = 1)))
            pauseProof.set(true)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val recovering = executor.submit<MatchingMarketBook> {
                    shared.readBook(stream, 0, "projection-a", "source-generation",
                        "run", "session", "AAPL", "USD", requireCurrent = true)
                }
                assertTrue(snapshotPinned.await(5, TimeUnit.SECONDS),
                    "market recovery proof did not pause for partition 0")
                val unrelated = executor.submit<MatchingMarketBook> {
                    assertEquals(MatchingMarketAdvance.APPLIED,
                        shared.advanceNext(stream, 1, "source-generation", "projection-a",
                            maxOutcomes = 1))
                    shared.readBook(stream, 1, "projection-a", "source-generation",
                        "run", "session", "AAPL", "USD", depth = 2,
                        requireCurrent = true)
                }
                assertEquals(listOf(BigDecimal("60"), BigDecimal("61")),
                    unrelated.get(5, TimeUnit.SECONDS).asks.map { it.price })
                releaseProof.countDown()
                assertEquals(listOf(BigDecimal("50")),
                    recovering.get(5, TimeUnit.SECONDS).asks.map { it.price })
            } finally {
                releaseProof.countDown()
                executor.shutdownNow()
            }
        }
    }

    private fun proofFrontierCount(dataSource: DataSource, schema: String, stream: String): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT count(*) FROM $schema.matching_market_candidate_frontiers
                   WHERE event_stream = ? AND projector_generation LIKE '__market_replay_proof__%'"""
            ).use { statement ->
                statement.setString(1, stream)
                statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
            }
        }

    private fun maker(stream: String, sequence: Long, order: String, price: String,
        orderType: String = "LIMIT", partition: Int = 0):
        CanonicalOutcomeSource {
        val payload = """{"effectVersion":1,"accepted":{"eventId":"accept-$sequence","orderId":"$order","occurredAt":"2026-09-28T00:00:00Z"},"acceptedOrder":${identity(order, "seller", "SELL", price, orderType)},"orderStates":[${state(order, "SELL", "ACCEPTED", "1", price)}]}"""
        return source(stream, sequence, order, payload, partition)
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

    private fun source(stream: String, sequence: Long, order: String, payload: String,
        partition: Int = 0) =
        CanonicalOutcomeSource(stream, partition, sequence, "batch-$sequence", "command-$sequence",
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

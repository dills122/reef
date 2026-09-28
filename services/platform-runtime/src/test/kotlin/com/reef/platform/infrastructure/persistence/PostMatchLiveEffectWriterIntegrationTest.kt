package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalEffect
import com.reef.platform.application.postmatch.CanonicalSourceCoverageVerifier
import com.reef.platform.application.postmatch.CanonicalStreamPosition
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assumptions.assumeTrue
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
        listOf(false, true).forEach { rewrite ->
            val rewrittenUrl = jdbcUrl + (if ('?' in jdbcUrl) "&" else "?") + "reWriteBatchedInserts=$rewrite"
            val dataSource = RuntimeDataSources.dataSource(rewrittenUrl, user, password, "postmatch-live-integration-$rewrite")
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
                    connection.autoCommit = false
                    try {
                        assertFailsWith<IllegalStateException> { writer.apply(connection, opening) }
                    } finally {
                        connection.rollback()
                    }
                }
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
                    connection.prepareStatement(
                        "SELECT quantity_units_text, execution_price_text, occurred_at_text FROM postmatch.live_execution_facts WHERE event_stream = ? AND execution_id = 'match-1-buy'"
                    ).use { statement ->
                        statement.setString(1, stream)
                        statement.executeQuery().use { rows ->
                            check(rows.next())
                            assertEquals("6.00", rows.getString(1))
                            assertEquals("100.00", rows.getString(2))
                            assertEquals("2026-09-26T00:00:01.000Z", rows.getString(3))
                        }
                    }
                    connection.prepareStatement("SELECT COUNT(*) FROM postmatch.live_trade_facts WHERE event_stream = ?").use { statement ->
                        statement.setString(1, stream)
                        statement.executeQuery().use { rows -> check(rows.next()); assertEquals(1L, rows.getLong(1)) }
                    }
                    connection.prepareStatement("SELECT COUNT(*) FROM postmatch.consumer_outcome_receipts WHERE event_stream = ?").use { statement ->
                        statement.setString(1, stream)
                        statement.executeQuery().use { rows -> check(rows.next()); assertEquals(3L, rows.getLong(1)) }
                    }
                }
            } finally {
                clean(dataSource, stream, generation, consumer)
            }
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

    @Test
    fun fixedLiveWindowMeasuresBatchRewriteAndPreservesExactFacts() {
        assumeTrue(System.getenv("POSTMATCH_LIVE_BENCHMARK") == "1")
        val jdbcUrl = System.getenv("POSTMATCH_DB_URL_TEST") ?: return
        val user = System.getenv("POSTMATCH_DB_USER_TEST") ?: return
        val password = System.getenv("POSTMATCH_DB_PASSWORD_TEST") ?: return
        val token = UUID.randomUUID().toString()
        val expectedCounts = mapOf(
            "canonical_order_directory" to 500L,
            "consumer_outcome_receipts" to 500L,
            "live_execution_facts" to 500L,
            "live_trade_facts" to 250L,
            "live_order_state" to 500L,
            "live_market_order_changes" to 250L,
            "consumer_source_coverage" to 1L,
            "consumer_frontiers" to 1L
        )
        listOf(false, true, false, true).forEachIndexed { run, rewrite ->
            val stream = "live-batch-$token-$run"
            val generation = "generation-$token"
            val consumer = "live-batch-$token"
            val source = (1..250).flatMap { index ->
                val maker = "maker-$index"
                val taker = "taker-$index"
                listOf(
                    source(stream, (index * 2 - 1).toLong(), maker, "SubmitOrder",
                        makerSubmit(maker).replace("accept-maker", "accept-maker-$index")
                            .replace("engine-maker", "engine-maker-$index")),
                    source(stream, (index * 2).toLong(), taker, "SubmitOrder",
                        takerSubmit(taker, maker).replace("accept-taker", "accept-taker-$index")
                            .replace("engine-taker", "engine-taker-$index")
                            .replace("exec-buy-event", "exec-buy-event-$index")
                            .replace("exec-sell-event", "exec-sell-event-$index")
                            .replace("match-1", "match-$index")
                            .replace("trade-1", "trade-$index")
                            .replace("trade-event", "trade-event-$index"))
                )
            }
            val window = CanonicalSourceCoverageVerifier().verify(
                consumer, stream, 0, generation, 0, source.size.toLong(), source
            )
            val runUrl = jdbcUrl + (if ('?' in jdbcUrl) "&" else "?") + "reWriteBatchedInserts=$rewrite"
            val dataSource = RuntimeDataSources.dataSource(runUrl, user, password, "live-batch-$run")
            val store = PostMatchOperationalStore(dataSource)
            val writer = PostMatchLiveEffectWriter()
            try {
                val before = liveInsertCalls(dataSource)
                val started = System.nanoTime()
                assertEquals(PostMatchApplyResult.APPLIED, store.apply(window, writer::apply))
                val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                assertEquals(PostMatchApplyResult.DUPLICATE, store.apply(window, writer::apply))
                val calls = liveInsertCalls(dataSource).mapValues { (table, count) -> count - (before[table] ?: 0L) }
                val facts = expectedCounts.mapValues { (table, expected) ->
                    val rows = dataSource.connection.use { connection ->
                        connection.prepareStatement("SELECT COUNT(*) FROM postmatch.$table WHERE event_stream = ?").use { statement ->
                            statement.setString(1, stream)
                            statement.executeQuery().use { result -> check(result.next()); result.getLong(1) }
                        }
                    }
                    assertEquals(expected, rows, "$table count with rewrite=$rewrite")
                    rows
                }
                fun fields(vararg values: Any?): List<String> = values.map { it.toString() }
                val membership = dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """SELECT consumer_name, event_stream, partition_id, source_generation, stream_sequence,
                                  batch_id, command_id, command_payload_hash, result_digest, effect_count
                           FROM postmatch.consumer_outcome_receipts WHERE event_stream = ? ORDER BY stream_sequence"""
                    ).use { statement ->
                        statement.setString(1, stream)
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) add(fields(
                                    rows.getString(1), rows.getString(2), rows.getInt(3), rows.getString(4), rows.getLong(5),
                                    rows.getString(6), rows.getString(7), rows.getString(8), rows.getString(9), rows.getInt(10)
                                ))
                            }
                        }
                    }
                }
                assertEquals(window.outcomes.map { outcome -> fields(
                    consumer, stream, 0, generation, outcome.source.streamSequence, outcome.source.batchId,
                    outcome.source.commandId, outcome.source.payloadHash, outcome.resultDigest, outcome.effects.size
                ) }, membership)
                val expectedExecutions = window.outcomes.flatMap { it.effects }.mapNotNull { envelope ->
                    val effect = envelope.effect as? CanonicalEffect.Execution ?: return@mapNotNull null
                    effect.executionId to fields(
                        stream, generation, effect.executionId, effect.eventId, effect.orderId, effect.instrumentId,
                        effect.quantityUnits, effect.price, effect.currency, effect.liquidityRole,
                        Instant.parse(effect.occurredAt), envelope.position.partitionId,
                        envelope.position.streamSequence, envelope.position.effectOrdinal,
                        effect.quantityUnits, effect.price, effect.occurredAt
                    )
                }.sortedBy { it.first }.map { it.second }
                val executionFacts = dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """SELECT event_stream, source_generation, execution_id, event_id, order_id, instrument_id,
                                  quantity_units, execution_price, currency, liquidity_role, occurred_at,
                                  source_partition_id, source_stream_sequence, source_effect_ordinal,
                                  quantity_units_text, execution_price_text, occurred_at_text
                           FROM postmatch.live_execution_facts WHERE event_stream = ? ORDER BY execution_id"""
                    ).use { statement ->
                        statement.setString(1, stream)
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) add(fields(
                                    rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                                    rows.getString(5), rows.getString(6), rows.getBigDecimal(7).toPlainString(),
                                    rows.getBigDecimal(8).toPlainString(), rows.getString(9), rows.getString(10),
                                    rows.getTimestamp(11).toInstant(), rows.getInt(12), rows.getLong(13), rows.getInt(14),
                                    rows.getString(15), rows.getString(16), rows.getString(17)
                                ))
                            }
                        }
                    }
                }
                assertEquals(expectedExecutions, executionFacts)
                val expectedTrades = window.outcomes.flatMap { it.effects }.mapNotNull { envelope ->
                    val effect = envelope.effect as? CanonicalEffect.Trade ?: return@mapNotNull null
                    effect.tradeId to fields(
                        stream, generation, effect.tradeId, effect.eventId, effect.executionId,
                        effect.buyOrderId, effect.sellOrderId, effect.instrumentId, effect.quantityUnits,
                        effect.price, effect.currency, Instant.parse(effect.occurredAt),
                        envelope.position.partitionId, envelope.position.streamSequence, envelope.position.effectOrdinal
                    )
                }.sortedBy { it.first }.map { it.second }
                val tradeFacts = dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """SELECT event_stream, source_generation, trade_id, event_id, execution_id,
                                  buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency,
                                  occurred_at, source_partition_id, source_stream_sequence, source_effect_ordinal
                           FROM postmatch.live_trade_facts WHERE event_stream = ? ORDER BY trade_id"""
                    ).use { statement ->
                        statement.setString(1, stream)
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) add(fields(
                                    rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                                    rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8),
                                    rows.getBigDecimal(9).toPlainString(), rows.getBigDecimal(10).toPlainString(),
                                    rows.getString(11), rows.getTimestamp(12).toInstant(), rows.getInt(13),
                                    rows.getLong(14), rows.getInt(15)
                                ))
                            }
                        }
                    }
                }
                assertEquals(expectedTrades, tradeFacts)
                println("postmatch_live_benchmark run=$run rewrite=$rewrite outcomes=${source.size} trades=${tradeFacts.size} elapsed_ms=$elapsedMs calls=$calls facts=$facts")
            } finally {
                clean(dataSource, stream, generation, consumer)
            }
        }
    }

    private fun liveInsertCalls(dataSource: javax.sql.DataSource): Map<String, Long> =
        dataSource.connection.use { connection ->
            val available = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements')"
            ).use { statement -> statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) } }
            if (!available) return@use emptyMap()
            connection.prepareStatement(
                "SELECT query, calls FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) AND lower(query) LIKE '%insert into postmatch.%'"
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    val calls = mutableMapOf<String, Long>()
                    while (rows.next()) {
                        val sql = rows.getString(1).lowercase()
                        listOf("canonical_order_directory", "consumer_outcome_receipts", "live_execution_facts",
                            "live_trade_facts", "live_order_state", "live_market_order_changes").forEach { table ->
                            if ("postmatch.$table" in sql) calls[table] = (calls[table] ?: 0L) + rows.getLong(2)
                        }
                    }
                    calls
                }
            }
        }

    @Test
    fun parallelLiveWindowsMeasureCostAndScaling() {
        assumeTrue(System.getenv("POSTMATCH_LIVE_BENCHMARK") == "1")
        val jdbcUrl = System.getenv("POSTMATCH_DB_URL_TEST") ?: return
        val user = System.getenv("POSTMATCH_DB_USER_TEST") ?: return
        val password = System.getenv("POSTMATCH_DB_PASSWORD_TEST") ?: return
        val token = UUID.randomUUID().toString()
        listOf(4, 8, 16).forEach { partitionCount ->
            listOf(false, true).forEach { rewrite ->
                val stream = "live-concurrent-$token-$partitionCount-$rewrite"
                val generation = "generation-$token"
                val consumer = "live-concurrent-$token"
                val url = jdbcUrl + (if ('?' in jdbcUrl) "&" else "?") + "reWriteBatchedInserts=$rewrite"
                val dataSource = RuntimeDataSources.dataSource(url, user, password, "postmatch-operational")
                val workers = Executors.newFixedThreadPool(partitionCount)
                try {
                    val verifier = CanonicalSourceCoverageVerifier()
                    val windowsByPartition = (0 until partitionCount).map { partition ->
                        (0 until 16 / partitionCount).map { windowIndex ->
                            val from = CanonicalStreamPosition.origin(partition) + windowIndex * 500L
                            val outcomes = (1..250).flatMap { index ->
                                val suffix = "$partition-$windowIndex-$index"
                                val maker = "maker-$suffix"
                                val taker = "taker-$suffix"
                                listOf(
                                    source(stream, from + index * 2 - 1, maker, "SubmitOrder",
                                        makerSubmit(maker).replace("accept-maker", "accept-maker-$suffix")
                                            .replace("engine-maker", "engine-maker-$suffix"), partition),
                                    source(stream, from + index * 2, taker, "SubmitOrder",
                                        takerSubmit(taker, maker).replace("accept-taker", "accept-taker-$suffix")
                                            .replace("engine-taker", "engine-taker-$suffix")
                                            .replace("exec-buy-event", "exec-buy-event-$suffix")
                                            .replace("exec-sell-event", "exec-sell-event-$suffix")
                                            .replace("match-1", "match-$suffix")
                                            .replace("trade-1", "trade-$suffix")
                                            .replace("trade-event", "trade-event-$suffix"), partition)
                                )
                            }
                            verifier.verify(consumer, stream, partition, generation, from, from + 500, outcomes)
                        }
                    }
                    val poolMax = (dataSource as com.zaxxer.hikari.HikariDataSource).maximumPoolSize
                    val warmConnections = (1..poolMax).map { dataSource.connection }
                    warmConnections.forEach { it.close() }
                    val before = liveInsertCalls(dataSource)
                    val started = System.nanoTime()
                    val futures = windowsByPartition.map { windows ->
                        workers.submit(Callable {
                            val store = PostMatchOperationalStore(dataSource)
                            val writer = PostMatchLiveEffectWriter()
                            windows.map { window ->
                                var commitNs = 0L
                                val windowStarted = System.nanoTime()
                                assertEquals(PostMatchApplyResult.APPLIED,
                                    store.applyMeasured(window, writer::apply) { phase, nanos ->
                                        if (phase == "commit") commitNs = nanos
                                    })
                                Pair((System.nanoTime() - windowStarted) / 1_000_000.0, commitNs / 1_000_000.0)
                            }
                        })
                    }
                    val samples = futures.flatMap { it.get() }
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                    val target = samples.map { it.first }.sorted()
                    val commits = samples.map { it.second }.sorted()
                    val after = liveInsertCalls(dataSource)
                    val calls = after.mapValues { (table, count) -> count - (before[table] ?: 0L) }
                    dataSource.connection.use { connection ->
                        listOf("consumer_outcome_receipts" to 8_000L, "live_trade_facts" to 4_000L,
                            "consumer_source_coverage" to 16L).forEach { (table, expected) ->
                            connection.prepareStatement("SELECT COUNT(*) FROM postmatch.$table WHERE event_stream = ?").use { statement ->
                                statement.setString(1, stream)
                                statement.executeQuery().use { rows -> check(rows.next()); assertEquals(expected, rows.getLong(1)) }
                            }
                        }
                        connection.prepareStatement(
                            "SELECT partition_id, last_stream_sequence FROM postmatch.consumer_frontiers WHERE event_stream = ? ORDER BY partition_id"
                        ).use { statement ->
                            statement.setString(1, stream)
                            statement.executeQuery().use { rows ->
                                (0 until partitionCount).forEach { partition ->
                                    check(rows.next())
                                    assertEquals(partition, rows.getInt(1))
                                    assertEquals(CanonicalStreamPosition.origin(partition) + (16 / partitionCount) * 500,
                                        rows.getLong(2))
                                }
                                check(!rows.next())
                            }
                        }
                    }
                    println("postmatch_live_concurrent rewrite=$rewrite partitions=$partitionCount pool_max=$poolMax windows=16 outcomes=8000 trades=4000 " +
                        "elapsed_ms=$elapsedMs target_mean_ms=${target.average()} target_p95_ms=${target[15]} " +
                        "commit_mean_ms=${commits.average()} commit_p95_ms=${commits[15]} calls=$calls")
                } finally {
                    workers.shutdownNow()
                    clean(dataSource, stream, generation, consumer)
                }
            }
        }
    }

    private fun clean(dataSource: javax.sql.DataSource, stream: String, generation: String, consumer: String) {
        dataSource.connection.use { connection ->
            listOf("live_market_order_changes", "live_market_change_windows", "live_trade_facts",
                "live_execution_facts", "live_order_state", "canonical_order_directory").forEach { table ->
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

    private fun source(stream: String, sequence: Long, orderId: String, commandType: String, result: String,
                       partition: Int = 0) = CanonicalOutcomeSource(
        eventStream = stream, partitionId = partition, streamSequence = sequence, batchId = "batch-$sequence",
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
           {"eventId":"exec-buy-event","executionId":"match-1-buy","orderId":"$taker","instrumentId":"AAPL","quantityUnits":"6.00","executionPrice":"100.00","currency":"USD","occurredAt":"2026-09-26T00:00:01.000Z","liquidityRole":"TAKER"},
           {"eventId":"exec-sell-event","executionId":"match-1-sell","orderId":"$maker","instrumentId":"AAPL","quantityUnits":"6.00","executionPrice":"100.00","currency":"USD","occurredAt":"2026-09-26T00:00:01Z","liquidityRole":"MAKER"}],
         "trades":[{"eventId":"trade-event","tradeId":"trade-1","executionId":"match-1","buyOrderId":"$taker","sellOrderId":"$maker","instrumentId":"AAPL","quantityUnits":"6.00","price":"100.00","currency":"USD","occurredAt":"2026-09-26T00:00:01Z"}],
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

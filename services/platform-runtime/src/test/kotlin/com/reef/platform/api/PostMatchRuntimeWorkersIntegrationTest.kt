package com.reef.platform.api

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.infrastructure.persistence.PostgresCanonicalOutcomeSourceReader
import com.reef.platform.infrastructure.persistence.PostMatchLiveEffectWriter
import com.reef.platform.infrastructure.persistence.PostMatchMarketMaintainer
import com.reef.platform.infrastructure.persistence.PostMatchOperationalStore
import com.reef.platform.infrastructure.persistence.PostMatchSourceCatalog
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs against both migrated PostgreSQL targets in schema-placement CI. */
class PostMatchRuntimeWorkersIntegrationTest {
    @Test
    fun liveWriterBatchFlagOverridesJdbcUrlWithoutDroppingOtherOptions() {
        val base = "jdbc:postgresql://localhost:5436/reef"
        assertEquals("$base?reWriteBatchedInserts=true",
            PostMatchRuntimeWorkers.liveWriterJdbcUrl(base, true))
        val configured = "$base?sslmode=require&reWriteBatchedInserts=false&ApplicationName=live"
        assertEquals("$base?sslmode=require&ApplicationName=live&reWriteBatchedInserts=true",
            PostMatchRuntimeWorkers.liveWriterJdbcUrl(configured, true))
        val duplicated = "$base?reWriteBatchedInserts=true&sslmode=require&rewritebatchedinserts=false"
        assertEquals("$base?sslmode=require&reWriteBatchedInserts=false",
            PostMatchRuntimeWorkers.liveWriterJdbcUrl(duplicated, false))
    }

    @Test
    fun boundedReaderReturnsContiguousPayloadPrefixAndRejectsOversizedFirstResult() {
        val sourceUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val source = RuntimeDataSources.dataSource(sourceUrl,
            System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return,
            System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return, "postmatch-bounded-reader-test")
        val token = UUID.randomUUID().toString()
        val stream = "postmatch-bounded-$token"
        val reader = PostgresCanonicalOutcomeSourceReader(source)
        val generation = PostMatchSourceCatalog(source).generation()
        try {
            insertRejectedSource(source, stream, token, 0, 1)
            insertRejectedSource(source, stream, token, 0, 2)
            insertRejectedSource(source, stream, token, 0, 3, "x".repeat(1024))
            val sizes = source.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT octet_length(result_payload::text) FROM runtime.canonical_command_outcomes
                       WHERE event_stream = ? ORDER BY stream_sequence"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add(rows.getLong(1))
                        }
                    }
                }
            }
            assertEquals(3, sizes.size)
            val origin = CanonicalStreamPosition.origin(0)
            val firstTwo = reader.readNextWindow("bounded-test", stream, 0, generation, origin, 3,
                sizes[0] + sizes[1]) ?: error("missing bounded source prefix")
            assertEquals(listOf(origin + 1, origin + 2), firstTwo.outcomes.map { it.source.streamSequence })
            val last = reader.readNextWindow("bounded-test", stream, 0, generation, origin + 2, 3,
                sizes[2]) ?: error("missing final bounded source row")
            assertEquals(listOf(origin + 3), last.outcomes.map { it.source.streamSequence })
            val error = assertFailsWith<IllegalStateException> {
                reader.readNextWindow("bounded-test", stream, 0, generation, origin, 3, sizes[0] - 1)
            }
            assertTrue(error.message.orEmpty().contains("single canonical result payload exceeds configured bound"))
        } finally {
            cleanSource(source, stream)
        }
    }

    @Test
    fun liveAndMarketWorkersAdvanceIndependentFrontiersAcrossEncodedPartitions() {
        val sourceUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val targetUrl = System.getenv("POSTMATCH_DB_URL_TEST") ?: return
        val source = RuntimeDataSources.dataSource(sourceUrl,
            System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return,
            System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return, "postmatch-worker-source-test")
        val target = RuntimeDataSources.dataSource(targetUrl,
            System.getenv("POSTMATCH_DB_USER_TEST") ?: return,
            System.getenv("POSTMATCH_DB_PASSWORD_TEST") ?: return, "postmatch-worker-target-test")
        val token = UUID.randomUUID().toString()
        val stream = "postmatch-worker-$token"
        val catalog = PostMatchSourceCatalog(source)
        val generation = catalog.generation()
        assertEquals(generation, catalog.generation())
        val worker = PostMatchRuntimeWorkers(
            catalog, PostgresCanonicalOutcomeSourceReader(source), PostMatchOperationalStore(target),
            PostMatchLiveEffectWriter(), PostMatchMarketMaintainer(target), stream, listOf(0, 1), 1, 10,
            liveTimingsEnabled = true
        )
        try {
            listOf(0, 1).forEach { partition -> insertRejectedSource(source, stream, token, partition) }
            assertEquals(mapOf(
                0 to 1L,
                1 to CanonicalStreamPosition.origin(1) + 1
            ), catalog.partitionHeads(stream, 2))
            assertEquals(0, worker.processMarketOnce())
            assertEquals(2, worker.processLiveOnce())
            assertEquals(0, worker.processLiveOnce())
            assertEquals(2, worker.processMarketOnce())
            assertEquals(0, worker.processMarketOnce())
            target.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT consumer_name, partition_id, source_generation, last_stream_sequence
                       FROM postmatch.consumer_frontiers WHERE event_stream = ? ORDER BY consumer_name, partition_id"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows ->
                        var count = 0
                        while (rows.next()) {
                            count++
                            assertTrue(rows.getString(1) == PostMatchRuntimeWorkers.LIVE_CONSUMER ||
                                rows.getString(1) == PostMatchMarketMaintainer.CONSUMER_NAME)
                            assertEquals(generation, rows.getString(3))
                            assertEquals(CanonicalStreamPosition.origin(rows.getInt(2)) + 1, rows.getLong(4))
                        }
                        assertEquals(4, count)
                    }
                }
                connection.prepareStatement(
                    "SELECT COUNT(*), COALESCE(SUM(change_count), 0) FROM postmatch.live_market_change_windows WHERE event_stream = ?"
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals(2L, rows.getLong(1))
                        assertEquals(0L, rows.getLong(2))
                    }
                }
            }
        } finally {
            cleanTarget(target, stream)
            cleanSource(source, stream)
        }
    }

    @Test
    fun blockedLivePartitionDoesNotStopOtherPartitionsWithBoundedWrites() {
        val sourceUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val targetUrl = System.getenv("POSTMATCH_DB_URL_TEST") ?: return
        val source = RuntimeDataSources.dataSource(sourceUrl,
            System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return,
            System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return, "postmatch-parallel-source-test")
        val target = RuntimeDataSources.dataSource(targetUrl,
            System.getenv("POSTMATCH_DB_USER_TEST") ?: return,
            System.getenv("POSTMATCH_DB_PASSWORD_TEST") ?: return, "postmatch-parallel-target-test")
        val token = UUID.randomUUID().toString()
        val stream = "postmatch-parallel-$token"
        val catalog = PostMatchSourceCatalog(source)
        val generation = catalog.generation()
        val worker = PostMatchRuntimeWorkers(
            catalog, PostgresCanonicalOutcomeSourceReader(source), PostMatchOperationalStore(target),
            PostMatchLiveEffectWriter(), PostMatchMarketMaintainer(target), stream, listOf(0, 1, 2, 3), 1, 10,
            maxConcurrentLiveWrites = 2
        )
        val lock = target.connection
        try {
            listOf(0, 1, 2, 3).forEach { partition -> insertRejectedSource(source, stream, token, partition) }
            target.connection.use { connection ->
                connection.prepareStatement(
                    """INSERT INTO postmatch.consumer_frontiers(
                       consumer_name, event_stream, partition_id, source_generation, last_stream_sequence)
                       VALUES (?, ?, ?, ?, ?)"""
                ).use { statement ->
                    listOf(0, 1, 2, 3).forEach { partition ->
                        statement.setString(1, PostMatchRuntimeWorkers.LIVE_CONSUMER)
                        statement.setString(2, stream)
                        statement.setInt(3, partition)
                        statement.setString(4, generation)
                        statement.setLong(5, CanonicalStreamPosition.origin(partition))
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
            lock.autoCommit = false
            lock.prepareStatement(
                """SELECT last_stream_sequence FROM postmatch.consumer_frontiers
                   WHERE consumer_name = ? AND event_stream = ? AND partition_id = 0 FOR UPDATE"""
            ).use { statement ->
                statement.setString(1, PostMatchRuntimeWorkers.LIVE_CONSUMER)
                statement.setString(2, stream)
                statement.executeQuery().use { rows -> assertTrue(rows.next()) }
            }
            worker.start()
            listOf(1, 2, 3).forEach { partition ->
                assertTrue(awaitFrontier(target, stream, partition, CanonicalStreamPosition.origin(partition) + 1),
                    "partition $partition stayed behind partition 0's blocked transaction")
            }
            assertEquals(CanonicalStreamPosition.origin(0), liveFrontier(target, stream, 0))
            lock.commit()
            assertTrue(awaitFrontier(target, stream, 0, CanonicalStreamPosition.origin(0) + 1))
        } finally {
            lock.rollback()
            lock.close()
            worker.stop()
            cleanTarget(target, stream)
            cleanSource(source, stream)
        }
    }

    private fun awaitFrontier(target: DataSource, stream: String, partition: Int, expected: Long): Boolean {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (liveFrontier(target, stream, partition) == expected) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun fixedFiveHundredOutcomeSourceReadTiming() {
        assumeTrue(System.getenv("POSTMATCH_LIVE_BENCHMARK") == "1")
        val sourceUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val source = RuntimeDataSources.dataSource(sourceUrl,
            System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return,
            System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return, "postmatch-source-read-benchmark")
        val token = UUID.randomUUID().toString()
        val stream = "postmatch-source-read-$token"
        val generation = PostMatchSourceCatalog(source).generation()
        try {
            (1..500).forEach { offset ->
                insertRejectedSource(source, stream, token, 0, offset.toLong(), "x".repeat(1024))
            }
            val reader = PostgresCanonicalOutcomeSourceReader(source)
            val samples = (1..20).map {
                val started = System.nanoTime()
                val window = reader.readNextWindow("source-read-benchmark", stream, 0, generation, 0, 500)
                assertEquals(500, window?.outcomes?.size)
                (System.nanoTime() - started) / 1_000_000.0
            }.sorted()
            println("postmatch_live_source_read outcomes=500 padding_bytes_per_result=1024 samples=20 " +
                "mean_ms=${samples.average()} p95_ms=${samples[18]}")
        } finally {
            cleanSource(source, stream)
        }
    }

    private fun liveFrontier(target: DataSource, stream: String, partition: Int): Long? =
        target.connection.use { connection ->
            connection.prepareStatement(
                """SELECT last_stream_sequence FROM postmatch.consumer_frontiers
                   WHERE consumer_name = ? AND event_stream = ? AND partition_id = ?"""
            ).use { statement ->
                statement.setString(1, PostMatchRuntimeWorkers.LIVE_CONSUMER)
                statement.setString(2, stream)
                statement.setInt(3, partition)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            }
        }

    private fun insertRejectedSource(source: DataSource, stream: String, token: String, partition: Int,
                                     offset: Long = 1, padding: String = "") {
        val sequence = CanonicalStreamPosition.origin(partition) + offset
        val batch = "postmatch-batch-$token-$partition-$offset"
        val command = "postmatch-command-$token-$partition-$offset"
        val result = """{"effectVersion":1,"padding":"$padding","rejected":{"eventId":"reject-$token-$partition-$offset","orderId":"order-$token-$partition-$offset","code":"R","reason":"bad","occurredAt":"2026-09-26T00:00:00Z"}}"""
        source.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO runtime.canonical_venue_event_batches(
                   batch_id, shard_id, partition_id, command_stream, event_stream,
                   first_sequence, last_sequence, command_count, payload_checksum,
                   payload_format, payload_version, payload_json, created_at)
                   VALUES (?, 'test-shard', ?, 'test-commands', ?, ?, ?, 1, ?,
                           'venue-event-batch-json', 'v1', '{}'::jsonb, '2026-09-26T00:00:00Z')"""
            ).use { statement ->
                statement.setString(1, batch)
                statement.setInt(2, partition)
                statement.setString(3, stream)
                statement.setLong(4, sequence)
                statement.setLong(5, sequence)
                statement.setString(6, "checksum-$token-$partition-$offset")
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO runtime.canonical_command_outcomes(
                   command_id, batch_id, shard_id, partition_id, command_stream, event_stream,
                   stream_sequence, delivered_count, command_type, payload_hash, instrument_id,
                   order_id, result_status, reject_code, result_payload)
                   VALUES (?, ?, 'test-shard', ?, 'test-commands', ?, ?, 1, 'SubmitOrder', ?,
                           'AAPL', ?, 'rejected', 'R', ?::jsonb)"""
            ).use { statement ->
                statement.setString(1, command)
                statement.setString(2, batch)
                statement.setInt(3, partition)
                statement.setString(4, stream)
                statement.setLong(5, sequence)
                statement.setString(6, "hash-$token-$partition-$offset")
                statement.setString(7, "order-$token-$partition-$offset")
                statement.setString(8, result)
                statement.executeUpdate()
            }
        }
    }

    private fun cleanTarget(target: DataSource, stream: String) {
        target.connection.use { connection ->
            listOf("live_market_order_changes", "live_market_change_windows", "consumer_outcome_receipts",
                "consumer_source_coverage", "consumer_frontiers").forEach { table ->
                connection.prepareStatement("DELETE FROM postmatch.$table WHERE event_stream = ?").use { statement ->
                    statement.setString(1, stream)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun cleanSource(source: DataSource, stream: String) {
        source.connection.use { connection ->
            listOf("canonical_command_outcomes", "canonical_venue_event_batches").forEach { table ->
                connection.prepareStatement("DELETE FROM runtime.$table WHERE event_stream = ?").use { statement ->
                    statement.setString(1, stream)
                    statement.executeUpdate()
                }
            }
        }
    }
}

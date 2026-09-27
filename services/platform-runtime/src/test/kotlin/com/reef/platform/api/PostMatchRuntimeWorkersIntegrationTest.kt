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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs against both migrated PostgreSQL targets in schema-placement CI. */
class PostMatchRuntimeWorkersIntegrationTest {
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
            PostMatchLiveEffectWriter(), PostMatchMarketMaintainer(target), stream, listOf(0, 1), 1, 10
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

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
import kotlin.test.assertTrue

/** Runs against both migrated PostgreSQL targets in schema-placement CI. */
class PostMatchRuntimeWorkersIntegrationTest {
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

    private fun insertRejectedSource(source: DataSource, stream: String, token: String, partition: Int) {
        val sequence = CanonicalStreamPosition.origin(partition) + 1
        val batch = "postmatch-batch-$token-$partition"
        val command = "postmatch-command-$token-$partition"
        val result = """{"effectVersion":1,"rejected":{"eventId":"reject-$token-$partition","orderId":"order-$token-$partition","code":"R","reason":"bad","occurredAt":"2026-09-26T00:00:00Z"}}"""
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
                statement.setString(6, "checksum-$token-$partition")
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
                statement.setString(6, "hash-$token-$partition")
                statement.setString(7, "order-$token-$partition")
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

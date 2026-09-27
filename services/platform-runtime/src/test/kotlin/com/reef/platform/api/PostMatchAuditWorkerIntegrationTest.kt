package com.reef.platform.api

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.infrastructure.persistence.PostMatchAuditStore
import com.reef.platform.infrastructure.persistence.PostMatchSourceCatalog
import com.reef.platform.infrastructure.persistence.PostgresCanonicalOutcomeSourceReader
import com.reef.platform.infrastructure.persistence.RuntimeDataSources
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs against separate migrated primary and projection PostgreSQL databases. */
class PostMatchAuditWorkerIntegrationTest {
    @Test
    fun verifiedCanonicalSourceAdvancesOnlyAuditTarget() {
        val sourceUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST")
        val sourceUser = System.getenv("RUNTIME_POSTGRES_USER_TEST")
        val sourcePassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST")
        val targetUrl = System.getenv("RUNTIME_PROJECTION_POSTGRES_JDBC_URL_TEST")
        val targetUser = System.getenv("RUNTIME_PROJECTION_POSTGRES_USER_TEST")
        val targetPassword = System.getenv("RUNTIME_PROJECTION_POSTGRES_PASSWORD_TEST")
        assumeTrue(listOf(sourceUrl, sourceUser, sourcePassword, targetUrl, targetUser, targetPassword).all { it != null },
            "separate migrated primary and projection PostgreSQL test databases are required")
        require(sourceUrl != targetUrl) { "audit integration needs separate source and target databases" }
        val source = RuntimeDataSources.dataSource(sourceUrl!!, sourceUser!!, sourcePassword!!, "audit-worker-source-test")
        val target = RuntimeDataSources.dataSource(targetUrl!!, targetUser!!, targetPassword!!, "audit-worker-target-test")
        val token = UUID.randomUUID().toString()
        val stream = "audit-worker-test-$token"
        val batch = "batch-$token"
        val command = "command-$token"
        val partition = 127
        val sequence = CanonicalStreamPosition.origin(partition) + 1
        val store = PostMatchAuditStore(target)

        try {
            source.connection.use { connection ->
                connection.prepareStatement(
                    """INSERT INTO runtime.canonical_venue_event_batches(
                         batch_id, shard_id, partition_id, command_stream, event_stream,
                         first_sequence, last_sequence, command_count, payload_checksum,
                         payload_format, payload_version, payload_json, created_at
                       ) VALUES (?, 'test-shard', ?, 'test-commands', ?, ?, ?, 1, ?,
                                 'venue-event-batch-json', 'v1', '{}'::jsonb, '2026-09-26T00:00:00Z')"""
                ).use { statement ->
                    statement.setString(1, batch)
                    statement.setInt(2, partition)
                    statement.setString(3, stream)
                    statement.setLong(4, sequence)
                    statement.setLong(5, sequence)
                    statement.setString(6, "checksum-$token")
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """INSERT INTO runtime.canonical_command_outcomes(
                         command_id, batch_id, shard_id, partition_id, command_stream,
                         event_stream, stream_sequence, delivered_count, command_type,
                         payload_hash, instrument_id, order_id, result_status, reject_code, result_payload
                       ) VALUES (?, ?, 'test-shard', ?, 'test-commands', ?, ?, 1, 'SubmitOrder',
                                 ?, 'AAPL', ?, 'rejected', 'R', ?::jsonb)"""
                ).use { statement ->
                    statement.setString(1, command)
                    statement.setString(2, batch)
                    statement.setInt(3, partition)
                    statement.setString(4, stream)
                    statement.setLong(5, sequence)
                    statement.setString(6, "hash-$token")
                    statement.setString(7, "order-$token")
                    statement.setString(8,
                        """{"effectVersion":1,"rejected":{"eventId":"event-$token","orderId":"order-$token","code":"R","reason":"bad","occurredAt":"2026-09-26T00:00:00Z"}}""")
                    statement.executeUpdate()
                }
            }
            val worker = PostMatchAuditWorker(
                PostMatchSourceCatalog(source), PostgresCanonicalOutcomeSourceReader(source), store,
                stream, listOf(partition), 100, 10
            )
            assertEquals(1, worker.processOnce())
            assertEquals(0, worker.processOnce())
            val generation = PostMatchSourceCatalog(source).generation()
            assertEquals(sequence, store.lastCommittedSequence(stream, partition, generation))
            target.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT COUNT(*) FROM runtime.canonical_audit_effects
                       WHERE event_stream = ? AND partition_id = ?"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setInt(2, partition)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(1L, rows.getLong(1))
                    }
                }
            }
        } finally {
            target.connection.use { connection ->
                listOf("canonical_audit_effects", "canonical_audit_outcomes", "canonical_audit_coverage",
                    "canonical_audit_frontiers").forEach { table ->
                    connection.prepareStatement("DELETE FROM runtime.$table WHERE event_stream = ?").use { statement ->
                        statement.setString(1, stream)
                        statement.executeUpdate()
                    }
                }
            }
            source.connection.use { connection ->
                connection.prepareStatement("DELETE FROM runtime.canonical_command_outcomes WHERE command_id = ?").use { statement ->
                    statement.setString(1, command)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM runtime.canonical_venue_event_batches WHERE event_stream = ? AND batch_id = ?").use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, batch)
                    statement.executeUpdate()
                }
            }
        }
    }
}

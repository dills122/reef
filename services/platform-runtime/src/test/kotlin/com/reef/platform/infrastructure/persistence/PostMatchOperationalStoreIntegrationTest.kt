package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalSourceCoverageVerifier
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Set POSTMATCH_DB_*_TEST to an isolated database migrated with the postmatch profile. */
class PostMatchOperationalStoreIntegrationTest {
    @Test
    fun canonicalReaderRequiresRetainedBatchMembershipAndExactWindow() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, user, password, "canonical-source-integration")
        val reader = PostgresCanonicalOutcomeSourceReader(dataSource)
        val token = UUID.randomUUID().toString()
        val stream = "test-stream-$token"
        val batchId = "test-batch-$token"
        val commandId = "test-command-$token"
        val sequence = 8_000_000_000_000_000L + (System.nanoTime() and 0xFFFF_FFFFL)
        val result = """{"effectVersion":1,"rejected":{"eventId":"test-event-$token","orderId":"test-order-$token","code":"R","reason":"bad","occurredAt":"2026-09-26T00:00:00Z"}}"""

        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO runtime.canonical_venue_event_batches(
                      batch_id, shard_id, partition_id, command_stream, event_stream,
                      first_sequence, last_sequence, command_count, payload_checksum,
                      payload_format, payload_version, payload_json, created_at
                    ) VALUES (?, 'test-shard', 0, 'test-commands', ?, ?, ?, 1, ?,
                              'venue-event-batch-json', 'v1', '{}'::jsonb, '2026-09-26T00:00:00Z')
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, batchId)
                    statement.setString(2, stream)
                    statement.setLong(3, sequence)
                    statement.setLong(4, sequence)
                    statement.setString(5, "checksum-$token")
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO runtime.canonical_command_outcomes(
                      command_id, batch_id, shard_id, partition_id, command_stream,
                      event_stream, stream_sequence, delivered_count, command_type,
                      payload_hash, instrument_id, order_id, result_status, reject_code, result_payload
                    ) VALUES (?, ?, 'test-shard', 0, 'test-commands', ?, ?, 1, 'SubmitOrder',
                              ?, 'AAPL', ?, 'rejected', 'R', ?::jsonb)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, commandId)
                    statement.setString(2, batchId)
                    statement.setString(3, stream)
                    statement.setLong(4, sequence)
                    statement.setString(5, "hash-$token")
                    statement.setString(6, "test-order-$token")
                    statement.setString(7, result)
                    statement.executeUpdate()
                }
            }

            val window = reader.readVerifiedWindow("test-live", stream, 0, "test-generation", sequence - 1, sequence)
            assertEquals(commandId, window.outcomes.single().source.commandId)
            assertFailsWith<IllegalArgumentException> {
                reader.readVerifiedWindow("test-live", stream, 0, "test-generation", sequence - 1, sequence + 1)
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM runtime.canonical_venue_event_batches WHERE event_stream = ? AND batch_id = ?").use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, batchId)
                    statement.executeUpdate()
                }
            }
            assertFailsWith<IllegalStateException> {
                reader.readVerifiedWindow("test-live", stream, 0, "test-generation", sequence - 1, sequence)
            }
        } finally {
            dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM runtime.canonical_command_outcomes WHERE command_id = ?").use { statement ->
                    statement.setString(1, commandId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM runtime.canonical_venue_event_batches WHERE event_stream = ? AND batch_id = ?").use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, batchId)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun effectsReceiptsAndFrontierCommitTogetherAndReplayIsChecked() {
        val jdbcUrl = System.getenv("POSTMATCH_DB_URL_TEST") ?: return
        val user = System.getenv("POSTMATCH_DB_USER_TEST") ?: return
        val password = System.getenv("POSTMATCH_DB_PASSWORD_TEST") ?: return
        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, user, password, "postmatch-integration")
        val verifier = CanonicalSourceCoverageVerifier()
        val store = PostMatchOperationalStore(dataSource)
        val token = UUID.randomUUID().toString()
        val consumer = "test-$token"
        val stream = "test-stream-$token"
        val generation = "test-generation-$token"
        val first = outcome(stream, 1, "o1", "rejected", """{"effectVersion":1,"rejected":{"eventId":"r1","orderId":"o1","code":"R","reason":"bad","occurredAt":"t"}}""")
        val firstWindow = verifier.verify(consumer, stream, 0, generation, 0, 1, listOf(first))

        try {
            val unprovenOrigin = verifier.verify(consumer, stream, 1, generation, 4, 5,
                listOf(first.copy(partitionId = 1, streamSequence = 5)))
            assertFailsWith<IllegalStateException> { store.apply(unprovenOrigin) { _, _ -> } }
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(firstWindow) { _, _ -> })
            assertEquals(PostMatchApplyResult.DUPLICATE, store.apply(firstWindow) { _, _ -> error("duplicate applied effects") })
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT last_stream_sequence FROM postmatch.consumer_frontiers WHERE consumer_name = ? AND event_stream = ?"
                ).use { statement ->
                    statement.setString(1, consumer)
                    statement.setString(2, stream)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals(1L, rows.getLong(1))
                    }
                }
            }

            val changed = first.copy(resultPayloadJson = first.resultPayloadJson.replace("bad", "different"))
            assertFailsWith<IllegalStateException> {
                store.apply(verifier.verify(consumer, stream, 0, generation, 0, 1, listOf(changed))) { _, _ -> }
            }

            val second = outcome(stream, 2, "order-$token", "accepted", acceptedResult("order-$token"))
            val secondWindow = verifier.verify(consumer, stream, 0, generation, 1, 2, listOf(second))
            assertFailsWith<IllegalStateException> {
                store.apply(secondWindow) { _, _ -> error("injected effect failure") }
            }
            assertEquals(PostMatchApplyResult.APPLIED, store.apply(secondWindow) { _, _ -> })
            assertEquals(PostMatchApplyResult.DUPLICATE, store.apply(firstWindow) { _, _ -> error("late replay applied effects") })
            assertFailsWith<IllegalStateException> {
                store.apply(verifier.verify(consumer, stream, 0, "other-generation", 1, 2, listOf(second))) { _, _ -> }
            }
            val third = outcome(stream, 3, "o3", "rejected", """{"effectVersion":1,"rejected":{"eventId":"r3","orderId":"o3","code":"R","reason":"bad","occurredAt":"t"}}""")
            assertFailsWith<IllegalStateException> {
                store.apply(verifier.verify(consumer, stream, 0, generation, 1, 3, listOf(second, third))) { _, _ -> }
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT last_stream_sequence FROM postmatch.consumer_frontiers WHERE consumer_name = ? AND event_stream = ?"
                ).use { statement ->
                    statement.setString(1, consumer)
                    statement.setString(2, stream)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals(2L, rows.getLong(1))
                    }
                }
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM postmatch.canonical_order_directory WHERE event_stream = ? AND source_generation = ?"
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, generation)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals(1L, rows.getLong(1))
                    }
                }
            }
        } finally {
            dataSource.connection.use { connection ->
                listOf(
                    "DELETE FROM postmatch.canonical_order_directory WHERE event_stream = ? AND source_generation = ?",
                    "DELETE FROM postmatch.consumer_outcome_receipts WHERE consumer_name = ? AND event_stream = ?",
                    "DELETE FROM postmatch.consumer_source_coverage WHERE consumer_name = ? AND event_stream = ?",
                    "DELETE FROM postmatch.consumer_frontiers WHERE consumer_name = ? AND event_stream = ?"
                ).forEach { sql ->
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, if (sql.contains("canonical_order_directory")) stream else consumer)
                        statement.setString(2, if (sql.contains("canonical_order_directory")) generation else stream)
                        statement.executeUpdate()
                    }
                }
            }
        }
    }

    private fun outcome(stream: String, sequence: Long, orderId: String, status: String, result: String) = CanonicalOutcomeSource(
        eventStream = stream, partitionId = 0, streamSequence = sequence,
        batchId = "batch-$sequence", commandId = "command-$sequence", commandType = "SubmitOrder",
        payloadHash = "hash-$sequence", instrumentId = "AAPL", orderId = orderId,
        resultStatus = status, resultPayloadJson = result
    )

    private fun acceptedResult(orderId: String) = """
        {"effectVersion":1,"accepted":{"eventId":"accept-2","orderId":"$orderId","occurredAt":"t"},
         "acceptedOrder":{"orderId":"$orderId","engineOrderId":"engine-2","clientOrderId":"","runId":"",
         "venueSessionId":"session-1","instrumentId":"AAPL","participantId":"participant-1","accountId":"account-1",
         "side":"BUY","orderType":"LIMIT","quantityUnits":"10","limitPrice":"100","currency":"USD","timeInForce":"DAY","acceptedAt":"t"},
         "orderStates":[{"orderId":"$orderId","instrumentId":"AAPL","side":"BUY","status":"OPEN",
         "originalQuantity":"10","remainingQuantity":"10","limitPrice":"100","currency":"USD","lastUpdatedAt":"t"}]}
    """.trimIndent()
}

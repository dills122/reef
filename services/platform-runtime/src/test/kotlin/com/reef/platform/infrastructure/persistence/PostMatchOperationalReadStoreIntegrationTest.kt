package com.reef.platform.infrastructure.persistence

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PostMatchOperationalReadStoreIntegrationTest {
    @Test
    fun participantReadsStayBoundedAndGenerationScopedWithCoverage() {
        val jdbcUrl = System.getenv("POSTMATCH_DB_URL_TEST") ?: return
        val user = System.getenv("POSTMATCH_DB_USER_TEST") ?: return
        val password = System.getenv("POSTMATCH_DB_PASSWORD_TEST") ?: return
        val source = RuntimeDataSources.dataSource(jdbcUrl, user, password, "postmatch-reads-test")
        val store = PostMatchOperationalReadStore(source)
        val token = UUID.randomUUID().toString()
        val stream = "read-stream-$token"
        val generation = "read-generation-$token"
        val participant = "participant-$token"
        val other = "other-$token"
        val first = "first-$token"
        val second = "second-$token"
        val third = "third-$token"
        try {
            source.connection.use { connection ->
                connection.prepareStatement(
                    """INSERT INTO postmatch.consumer_frontiers(
                         consumer_name, event_stream, partition_id, source_generation, last_stream_sequence)
                         VALUES ('live-v1', ?, 0, ?, 3)"""
                ).use { statement ->
                    statement.setString(1, stream)
                    statement.setString(2, generation)
                    statement.executeUpdate()
                }
                listOf(
                    Triple(first, participant, "AAPL"),
                    Triple(second, participant, "MSFT"),
                    Triple(third, other, "AAPL")
                ).forEachIndexed { index, (orderId, owner, instrument) ->
                    connection.prepareStatement(
                        """INSERT INTO postmatch.canonical_order_directory(
                             event_stream, source_generation, order_id, engine_order_id, client_order_id,
                             run_id, venue_session_id, instrument_id, participant_id, account_id,
                             side, order_type, quantity_units, limit_price, currency, time_in_force,
                             accepted_at, source_partition_id, source_stream_sequence, source_effect_ordinal)
                             VALUES (?, ?, ?, ?, ?, 'run-1', 'session-1', ?, ?, ?, 'BUY', 'LIMIT',
                                     '10', '100.00', 'USD', 'DAY', ?, 0, ?, 0)"""
                    ).use { statement ->
                        statement.setString(1, stream)
                        statement.setString(2, generation)
                        statement.setString(3, orderId)
                        statement.setString(4, "engine-$orderId")
                        statement.setString(5, "client-$orderId")
                        statement.setString(6, instrument)
                        statement.setString(7, owner)
                        statement.setString(8, "account-$owner")
                        statement.setString(9, "2026-09-26T00:00:0${index}Z")
                        statement.setLong(10, index + 1L)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(
                        """INSERT INTO postmatch.live_order_state(
                             event_stream, source_generation, order_id, instrument_id, status,
                             original_quantity, remaining_quantity, filled_quantity, limit_price,
                             currency, last_event_at, source_partition_id, source_stream_sequence, source_effect_ordinal)
                             VALUES (?, ?, ?, ?, ?, 10, ?, ?, 100.00, 'USD',
                                     '2026-09-26T00:00:00Z', 0, ?, 0)"""
                    ).use { statement ->
                        statement.setString(1, stream)
                        statement.setString(2, generation)
                        statement.setString(3, orderId)
                        statement.setString(4, instrument)
                        statement.setString(5, when (orderId) {
                            second -> "CANCELLED"
                            third -> "PARTIALLY_FILLED"
                            else -> "ACCEPTED"
                        })
                        statement.setInt(6, when (orderId) {
                            second -> 0
                            third -> 8
                            else -> 10
                        })
                        statement.setInt(7, if (orderId == first) 0 else 2)
                        statement.setLong(8, index + 1L)
                        statement.executeUpdate()
                    }
                }
                listOf(second, third).forEach { orderId ->
                    connection.prepareStatement(
                        """INSERT INTO postmatch.live_execution_facts(
                             event_stream, source_generation, execution_id, event_id, order_id,
                             instrument_id, quantity_units, execution_price, currency, liquidity_role,
                             occurred_at, source_partition_id, source_stream_sequence, source_effect_ordinal)
                             VALUES (?, ?, ?, ?, ?, ?, 2, 100.00, 'USD', 'TAKER',
                                     '2026-09-26T00:01:00Z', 0, ?, 1)"""
                    ).use { statement ->
                        statement.setString(1, stream)
                        statement.setString(2, generation)
                        statement.setString(3, "execution-$orderId")
                        statement.setString(4, "event-$orderId")
                        statement.setString(5, orderId)
                        statement.setString(6, if (orderId == second) "MSFT" else "AAPL")
                        statement.setLong(7, if (orderId == second) 2L else 3L)
                        statement.executeUpdate()
                    }
                }
            }

            val current = store.ordersForParticipant(stream, generation, participant, true, "", 50)
            assertEquals(listOf(first), current.rows.map { it.orderId })
            assertEquals("OPEN", current.rows.single().status)
            assertEquals(mapOf(0 to 3L), current.frontiers)
            assertEquals(listOf(first, second), store.ordersForParticipant(stream, generation, participant, false, "", 50).rows.map { it.orderId })
            assertEquals(listOf(second), store.ordersForParticipant(stream, generation, participant, false, "MSFT", 1).rows.map { it.orderId })
            assertEquals(listOf("execution-$second"), store.executionsForParticipant(stream, generation, participant, "", "run-1", 50).rows.map { it.executionId })
            assertEquals(emptyList(), store.executionsForParticipant(stream, generation, participant, "", "other-run", 50).rows)
            assertEquals(emptyList(), store.ordersForParticipant(stream, "other-generation", participant, false, "", 50).rows)
        } finally {
            source.connection.use { connection ->
                listOf(
                    "DELETE FROM postmatch.live_execution_facts WHERE event_stream = ? AND source_generation = ?",
                    "DELETE FROM postmatch.live_order_state WHERE event_stream = ? AND source_generation = ?",
                    "DELETE FROM postmatch.canonical_order_directory WHERE event_stream = ? AND source_generation = ?",
                    "DELETE FROM postmatch.consumer_frontiers WHERE consumer_name = 'live-v1' AND event_stream = ? AND source_generation = ?"
                ).forEach { sql ->
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, stream)
                        statement.setString(2, generation)
                        statement.executeUpdate()
                    }
                }
            }
        }
    }
}

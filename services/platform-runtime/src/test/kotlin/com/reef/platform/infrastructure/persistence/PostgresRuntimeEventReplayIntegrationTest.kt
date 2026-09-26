package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.RuntimeEvent
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostgresRuntimeEventReplayIntegrationTest {
    @Test
    fun legacyRowsWithoutDigestStillValidateSidePayloadOnReplay() {
        val source = testSource() ?: return
        val eventId = "pre-migration-${UUID.randomUUID()}"
        val traceId = "trace-${UUID.randomUUID()}"
        try {
            source.connection.use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO runtime.runtime_events(
                      event_id, event_type, order_id, trace_id, causation_id, correlation_id,
                      producer, schema_version, sequence_number, payload_json, occurred_at
                    ) VALUES (?, 'OrderAccepted', ?, ?, ?, ?, 'test', '1', 1, '{}'::jsonb, '2026-09-25T00:00:00Z')
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, eventId)
                    ps.setString(2, "order-$eventId")
                    ps.setString(3, traceId)
                    ps.setString(4, "command-$eventId")
                    ps.setString(5, "correlation-$eventId")
                    ps.executeUpdate()
                }
                conn.prepareStatement("INSERT INTO runtime.runtime_event_payloads(event_id, payload_json) VALUES (?, '{\"value\":1}'::jsonb)").use { ps ->
                    ps.setString(1, eventId)
                    ps.executeUpdate()
                }
                conn.prepareStatement("INSERT INTO runtime.runtime_trace_sequences(trace_id, next_sequence) VALUES (?, 1)").use { ps ->
                    ps.setString(1, traceId)
                    ps.executeUpdate()
                }
            }

            persistTimeline(source, outcome(eventId, traceId, """{"value":1}"""))
            val conflict = assertFailsWith<SQLException> {
                persistTimeline(source, outcome(eventId, traceId, """{"value":2}"""))
            }
            assertEquals("23505", conflict.sqlState)
            assertEquals(1L, traceSequence(source, traceId))
        } finally {
            cleanup(source, listOf(eventId), traceId)
            (source as? AutoCloseable)?.close()
        }
    }

    @Test
    fun deterministicTimelineReplayRejectsChangedPayloadWithoutLegacyAllocation() {
        val source = testSource() ?: return
        val eventId = "deterministic-${UUID.randomUUID()}"
        val traceId = "trace-${UUID.randomUUID()}"
        try {
            persistTimeline(source, outcome(eventId, traceId, """{"value":1}""", streamSequence = 7))
            persistTimeline(source, outcome(eventId, traceId, """{"value":1}""", streamSequence = 7))
            val conflict = assertFailsWith<SQLException> {
                persistTimeline(source, outcome(eventId, traceId, """{"value":2}""", streamSequence = 7))
            }
            assertEquals("23505", conflict.sqlState)
            source.connection.use { conn ->
                conn.prepareStatement("SELECT sequence_number FROM runtime.runtime_events WHERE event_id = ?").use { ps ->
                    ps.setString(1, eventId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(701L, rs.getLong(1))
                    }
                }
                conn.prepareStatement("SELECT count(*) FROM runtime.runtime_trace_sequences WHERE trace_id = ?").use { ps ->
                    ps.setString(1, traceId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(0, rs.getInt(1))
                    }
                }
            }
        } finally {
            cleanup(source, listOf(eventId), traceId)
            (source as? AutoCloseable)?.close()
        }
    }

    @Test
    fun concurrentIdenticalLegacyTimelineReplayAllocatesOnce() {
        val source = testSource() ?: return
        val eventId = "concurrent-${UUID.randomUUID()}"
        val traceId = "trace-${UUID.randomUUID()}"
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val payload = outcome(eventId, traceId, "{}")
            val writes = (1..2).map {
                executor.submit {
                    start.await(5, TimeUnit.SECONDS)
                    persistTimeline(source, payload)
                }
            }
            start.countDown()
            writes.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1L, traceSequence(source, traceId))
        } finally {
            executor.shutdownNow()
            cleanup(source, listOf(eventId), traceId)
            (source as? AutoCloseable)?.close()
        }
    }

    @Test
    fun directReplayKeepsSequenceAndRejectsChangedPayload() {
        val source = testSource() ?: return
        val eventId = "direct-${UUID.randomUUID()}"
        val secondEventId = "direct-${UUID.randomUUID()}"
        val traceId = "trace-${UUID.randomUUID()}"
        try {
            val persistence = PostgresRuntimePersistence(source, bootstrapMode = PostgresBootstrapMode.Compat)
            val event = event(eventId, traceId, """{"value":1}""")
            persistence.saveEvent(event)
            persistence.saveEvent(event)

            val conflict = assertFailsWith<SQLException> {
                persistence.saveEvent(event.copy(payloadJson = """{"value":2}"""))
            }
            assertEquals("23505", conflict.sqlState)
            persistence.saveEvents(listOf(event, event(secondEventId, traceId, "{}")))
            assertEquals(listOf(1L, 2L), persistence.eventsForTrace(traceId).map { it.sequenceNumber })
            assertEquals(2L, traceSequence(source, traceId))
        } finally {
            cleanup(source, listOf(eventId, secondEventId), traceId)
            (source as? AutoCloseable)?.close()
        }
    }

    @Test
    fun timelineReplayRejectsChangedHeaderAndDoesNotFillMissingPayload() {
        val source = testSource() ?: return
        val eventId = "timeline-${UUID.randomUUID()}"
        val traceId = "trace-${UUID.randomUUID()}"
        try {
            persistTimeline(source, outcome(eventId, traceId, "{}"))
            persistTimeline(source, outcome(eventId, traceId, "{}"))
            assertEquals(1L, traceSequence(source, traceId))

            val changedPayload = assertFailsWith<SQLException> {
                persistTimeline(source, outcome(eventId, traceId, """{"value":2}"""))
            }
            assertEquals("23505", changedPayload.sqlState)
            val changedHeader = assertFailsWith<SQLException> {
                persistTimeline(source, outcome(eventId, traceId, "{}", orderId = "other-order"))
            }
            assertEquals("23505", changedHeader.sqlState)
            assertEquals(1L, traceSequence(source, traceId))
            source.connection.use { conn ->
                conn.prepareStatement("SELECT count(*) FROM runtime.runtime_event_payloads WHERE event_id = ?").use { ps ->
                    ps.setString(1, eventId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(0, rs.getInt(1))
                    }
                }
            }
        } finally {
            cleanup(source, listOf(eventId), traceId)
            (source as? AutoCloseable)?.close()
        }
    }

    private fun testSource() = run {
        val url = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return@run null
        val user = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return@run null
        val password = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return@run null
        RuntimeDataSources.dataSource(url, user, password, "event-replay-${UUID.randomUUID()}")
    }

    private fun event(eventId: String, traceId: String, payload: String) = RuntimeEvent(
        eventId = eventId,
        eventType = "OrderAccepted",
        orderId = "order-$eventId",
        traceId = traceId,
        causationId = "command-$eventId",
        correlationId = "correlation-$eventId",
        producer = "test",
        schemaVersion = "1",
        occurredAt = "2026-09-25T00:00:00Z",
        payloadJson = payload
    )

    private fun outcome(
        eventId: String,
        traceId: String,
        payload: String,
        orderId: String = "order-$eventId",
        streamSequence: Long? = null
    ) = """
        [{${if (streamSequence == null) "" else "\"streamSequence\":$streamSequence,"}"events":[{
          "eventId":"$eventId","eventType":"OrderAccepted","orderId":"$orderId",
          "traceId":"$traceId","causationId":"command-$eventId",
          "correlationId":"correlation-$eventId","producer":"test","schemaVersion":"1",
          "occurredAt":"2026-09-25T00:00:00Z","payloadJson":$payload
        }]}]
    """.trimIndent()

    private fun persistTimeline(source: javax.sql.DataSource, payload: String) {
        source.connection.use { conn ->
            conn.prepareStatement("SELECT runtime.runtime_persist_submit_outcome_timeline_stage(?::jsonb)").use { ps ->
                ps.setString(1, payload)
                ps.executeQuery().close()
            }
        }
    }

    private fun traceSequence(source: javax.sql.DataSource, traceId: String): Long = source.connection.use { conn ->
        conn.prepareStatement("SELECT next_sequence FROM runtime.runtime_trace_sequences WHERE trace_id = ?").use { ps ->
            ps.setString(1, traceId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun cleanup(source: javax.sql.DataSource, eventIds: List<String>, traceId: String) {
        source.connection.use { conn ->
            conn.autoCommit = false
            try {
                for (table in listOf("runtime.runtime_event_payloads", "runtime.runtime_events")) {
                    conn.prepareStatement("DELETE FROM $table WHERE event_id = ANY (?::text[])").use { ps ->
                        ps.setArray(1, conn.createArrayOf("text", eventIds.toTypedArray()))
                        ps.executeUpdate()
                    }
                }
                conn.prepareStatement("DELETE FROM runtime.runtime_trace_sequences WHERE trace_id = ?").use { ps ->
                    ps.setString(1, traceId)
                    ps.executeUpdate()
                }
                conn.commit()
            } catch (error: Throwable) {
                conn.rollback()
                throw error
            }
        }
    }
}

package com.reef.platform.infrastructure.persistence

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PostgresVenueEventBatchMaterializationIntegrationTest {
    @Test
    fun materializesVenueEventBatchIdempotentlyWhenMigratedPostgresIsAvailable() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return

        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val persistence = PostgresRuntimePersistence(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        val suffix = UUID.randomUUID().toString()
        val batch = venueEventBatch(suffix)

        assertEquals(1, persistence.materializeVenueEventBatch(batch))
        assertEquals(0, persistence.materializeVenueEventBatch(batch))

        val outcome = persistence.canonicalCommandOutcome("cmd-$suffix")
        assertNotNull(outcome)
        assertEquals("batch-$suffix", outcome.batchId)
        assertEquals("engine-0", outcome.shardId)
        assertEquals(4, outcome.partition)
        assertEquals(1001L, outcome.streamSequence)
        assertEquals("CancelOrder", outcome.commandType)
        assertEquals("rejected", outcome.resultStatus)
        assertEquals("ORDER_ALREADY_FILLED", outcome.rejectCode)

        assertFailsWith<Exception> {
            persistence.materializeVenueEventBatch(batch.copy(payloadChecksum = "different-$suffix"))
        }
    }

    @Test
    fun projectsMaterializedSubmitOutcomesWhenMigratedPostgresIsAvailable() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return

        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val persistence = PostgresRuntimePersistence(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        val suffix = UUID.randomUUID().toString()
        val projectionName = "runtime-normalized-venue-outcomes-$suffix"
        val batch = submitVenueEventBatch(suffix)

        insertCommandPayload(dataSource, "submit-cmd-$suffix", submitCommandPayload(suffix))
        assertEquals(1, persistence.materializeVenueEventBatch(batch))
        assertEquals(1, persistence.projectCanonicalCommandOutcomes(projectionName, 10, listOf(5)))
        assertEquals(0, persistence.projectCanonicalCommandOutcomes(projectionName, 10, listOf(5)))

        val result = persistence.submitResult("submit-cmd-$suffix")
        assertNotNull(result)
        assertEquals("submit-event-$suffix", result.accepted?.eventId)
        assertEquals("submit-order-$suffix", result.accepted?.orderId)
        assertEquals("engine-order-$suffix", result.accepted?.engineOrderId)
        assertEquals("2026-07-04T18:01:00Z", result.accepted?.occurredAt)

        val events = persistence.eventsForOrder("submit-order-$suffix")
        assertEquals(1, events.size)
        assertEquals("OrderAccepted", events.first().eventType)
        assertEquals("venue-event-batch-projector", events.first().producer)
        val order = persistence.acceptedOrder("submit-order-$suffix")
        assertNotNull(order)
        assertEquals("AAPL", order.instrumentId)
        assertEquals("participant-$suffix", order.participantId)
        assertEquals("account-$suffix", order.accountId)
        assertEquals("100", order.quantityUnits)

        val status = persistence.projectionStatus(projectionName, listOf(5), source = "venue-event-batch")
        assertEquals(0, status.lag)
        assertEquals(1002L, status.watermarks.single().lastPartitionSequence)
    }

    @Test
    fun projectsExecutionsAndTradesCarriedInResultPayloadWhenMigratedPostgresIsAvailable() {
        val jdbcUrl = System.getenv("RUNTIME_POSTGRES_JDBC_URL_TEST") ?: return
        val dbUser = System.getenv("RUNTIME_POSTGRES_USER_TEST") ?: return
        val dbPassword = System.getenv("RUNTIME_POSTGRES_PASSWORD_TEST") ?: return

        val dataSource = RuntimeDataSources.dataSource(jdbcUrl, dbUser, dbPassword)
        val persistence = PostgresRuntimePersistence(
            dataSource = dataSource,
            bootstrapMode = PostgresBootstrapMode.Validate
        )
        val suffix = UUID.randomUUID().toString()
        val projectionName = "runtime-normalized-venue-fills-$suffix"
        val batch = fillingVenueEventBatch(suffix)

        insertCommandPayload(dataSource, "match-cmd-$suffix", submitCommandPayload(suffix).replace("submit-cmd-$suffix", "match-cmd-$suffix"))
        assertEquals(1, persistence.materializeVenueEventBatch(batch))
        assertEquals(1, persistence.projectCanonicalCommandOutcomes(projectionName, 10, listOf(6)))

        val executions = persistence.executionsForOrder("match-order-$suffix")
        assertEquals(1, executions.size)
        assertEquals("exec-$suffix", executions.single().executionId)
        assertEquals("150250000000", executions.single().executionPrice)

        val trades = persistence.tradesForOrder("match-order-$suffix")
        assertEquals(1, trades.size)
        assertEquals("trade-$suffix", trades.single().tradeId)
        assertEquals("match-order-$suffix", trades.single().buyOrderId)
        assertEquals("resting-order-$suffix", trades.single().sellOrderId)
    }

    private fun fillingVenueEventBatch(suffix: String): VenueEventBatchFact {
        return VenueEventBatchFact(
            batchId = "fill-batch-$suffix",
            shardId = "engine-0",
            partition = 6,
            commandStream = "REEF_COMMANDS",
            eventStream = "REEF_VENUE_EVENTS",
            firstSequence = 2001,
            lastSequence = 2001,
            commandCount = 1,
            createdAt = "2026-07-04T18:02:00Z",
            payloadChecksum = "fill-checksum-$suffix",
            outcomes = listOf(
                VenueCommandOutcomeFact(
                    commandId = "match-cmd-$suffix",
                    commandType = "SubmitOrder",
                    streamSequence = 2001,
                    deliveredCount = 1,
                    payloadHash = "match-payload-hash-$suffix",
                    instrumentId = "AAPL",
                    orderId = "match-order-$suffix",
                    resultStatus = "accepted",
                    resultPayloadJson = """
                        {
                          "accepted":{"eventId":"match-event-$suffix","engineOrderId":"match-engine-order-$suffix","occurredAt":"2026-07-04T18:02:00Z"},
                          "executions":[{"eventId":"exec-evt-$suffix","executionId":"exec-$suffix","orderId":"match-order-$suffix","instrumentId":"AAPL","quantityUnits":"100","executionPrice":"150250000000","currency":"USD","occurredAt":"2026-07-04T18:02:00Z"}],
                          "trades":[{"eventId":"trade-evt-$suffix","tradeId":"trade-$suffix","executionId":"exec-$suffix","buyOrderId":"match-order-$suffix","sellOrderId":"resting-order-$suffix","instrumentId":"AAPL","quantityUnits":"100","price":"150250000000","currency":"USD","occurredAt":"2026-07-04T18:02:00Z"}]
                        }
                    """.trimIndent()
                )
            )
        )
    }

    private fun insertCommandPayload(dataSource: javax.sql.DataSource, commandId: String, payloadJson: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO command_log.command_payloads(command_id, payload_json)
                VALUES (?, ?::jsonb)
                ON CONFLICT (command_id) DO UPDATE SET payload_json = EXCLUDED.payload_json
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, commandId)
                ps.setString(2, payloadJson)
                ps.executeUpdate()
            }
        }
    }

    private fun submitCommandPayload(suffix: String): String {
        return """
            {
              "commandId":"submit-cmd-$suffix",
              "traceId":"trace-$suffix",
              "causationId":"submit-cmd-$suffix",
              "correlationId":"corr-$suffix",
              "actorId":"actor-$suffix",
              "occurredAt":"2026-07-04T18:01:00Z",
              "orderId":"submit-order-$suffix",
              "instrumentId":"AAPL",
              "participantId":"participant-$suffix",
              "accountId":"account-$suffix",
              "side":"BUY",
              "orderType":"LIMIT",
              "quantityUnits":"100",
              "limitPrice":"150250000000",
              "currency":"USD",
              "timeInForce":"DAY"
            }
        """.trimIndent()
    }

    private fun venueEventBatch(suffix: String): VenueEventBatchFact {
        return VenueEventBatchFact(
            batchId = "batch-$suffix",
            shardId = "engine-0",
            partition = 4,
            commandStream = "REEF_COMMANDS",
            eventStream = "REEF_VENUE_EVENTS",
            firstSequence = 1001,
            lastSequence = 1001,
            commandCount = 1,
            createdAt = "2026-07-04T18:00:00Z",
            payloadChecksum = "checksum-$suffix",
            outcomes = listOf(
                VenueCommandOutcomeFact(
                    commandId = "cmd-$suffix",
                    commandType = "CancelOrder",
                    streamSequence = 1001,
                    deliveredCount = 1,
                    payloadHash = "payload-hash-$suffix",
                    instrumentId = "AAPL",
                    orderId = "ord-$suffix",
                    resultStatus = "rejected",
                    rejectCode = "ORDER_ALREADY_FILLED",
                    resultPayloadJson = """{"rejected":{"code":"ORDER_ALREADY_FILLED"}}"""
                )
            )
        )
    }

    private fun submitVenueEventBatch(suffix: String): VenueEventBatchFact {
        return VenueEventBatchFact(
            batchId = "submit-batch-$suffix",
            shardId = "engine-0",
            partition = 5,
            commandStream = "REEF_COMMANDS",
            eventStream = "REEF_VENUE_EVENTS",
            firstSequence = 1002,
            lastSequence = 1002,
            commandCount = 1,
            createdAt = "2026-07-04T18:01:00Z",
            payloadChecksum = "submit-checksum-$suffix",
            outcomes = listOf(
                VenueCommandOutcomeFact(
                    commandId = "submit-cmd-$suffix",
                    commandType = "SubmitOrder",
                    streamSequence = 1002,
                    deliveredCount = 1,
                    payloadHash = "submit-payload-hash-$suffix",
                    instrumentId = "AAPL",
                    orderId = "submit-order-$suffix",
                    resultStatus = "accepted",
                    resultPayloadJson = """{"accepted":{"eventId":"submit-event-$suffix","engineOrderId":"engine-order-$suffix","occurredAt":"2026-07-04T18:01:00Z"}}"""
                )
            )
        )
    }
}

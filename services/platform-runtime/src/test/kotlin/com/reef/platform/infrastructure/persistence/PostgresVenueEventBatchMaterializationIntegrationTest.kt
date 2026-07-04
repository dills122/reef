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
}

package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.SubmitOrderResult
import com.reef.platform.domain.TradeCreated
import com.reef.platform.domain.Instrument
import com.reef.platform.domain.Participant
import com.reef.platform.domain.Account
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class InMemoryRuntimePersistenceTest {
    @Test
    fun storesAndQueriesAcceptedArtifacts() {
        val persistence = InMemoryRuntimePersistence()
        persistence.saveSubmitResult(
            "cmd-1",
            SubmitOrderResult()
        )
        persistence.saveInstrument(Instrument("AAPL", "AAPL"))
        persistence.saveParticipant(Participant("participant-1", "Participant 1"))
        persistence.saveAccount(Account("account-1", "participant-1"))

        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "ord-1",
                engineOrderId = "eng-ord-1",
                instrumentId = "AAPL",
                participantId = "participant-1",
                accountId = "account-1",
                side = "BUY",
                orderType = "LIMIT",
                quantityUnits = "100",
                limitPrice = "150250000000",
                currency = "USD",
                timeInForce = "DAY",
                acceptedAt = "2026-03-14T18:00:00Z"
            )
        )
        persistence.saveExecutions(
            listOf(
                ExecutionCreated(
                    eventId = "evt-exec-1",
                    executionId = "exec-1",
                    orderId = "ord-1",
                    instrumentId = "AAPL",
                    quantityUnits = "100",
                    executionPrice = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-03-14T18:00:00Z"
                )
            )
        )
        persistence.saveTrades(
            listOf(
                TradeCreated(
                    eventId = "evt-trade-1",
                    tradeId = "trade-1",
                    executionId = "exec-1",
                    buyOrderId = "ord-1",
                    sellOrderId = "ord-2",
                    instrumentId = "AAPL",
                    quantityUnits = "100",
                    price = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-03-14T18:00:00Z"
                )
            )
        )
        persistence.saveEvent(
            RuntimeEvent(
                eventId = "evt-order-accepted-1",
                eventType = "OrderAccepted",
                orderId = "ord-1",
                traceId = "trace-1",
                causationId = "cmd-1",
                correlationId = "corr-1",
                actorId = "trader-1",
                producer = "platform-runtime",
                schemaVersion = "v1",
                payloadJson = """{"source":"unit-test"}""",
                occurredAt = "2026-03-14T18:00:00Z"
            )
        )

        assertEquals(SubmitOrderResult(), persistence.submitResult("cmd-1"))
        assertEquals(1, persistence.instruments().size)
        assertEquals(1, persistence.participants().size)
        assertEquals(1, persistence.accounts().size)
        assertEquals(true, persistence.hasInstrument("AAPL"))
        assertEquals(true, persistence.hasParticipant("participant-1"))
        assertEquals(true, persistence.hasAccount("account-1"))
        assertNotNull(persistence.acceptedOrder("ord-1"))
        assertEquals(1, persistence.acceptedOrders().size)
        assertEquals(1, persistence.executionsForOrder("ord-1").size)
        assertEquals(1, persistence.trades().size)
        assertEquals(1, persistence.tradesForOrder("ord-1").size)
        assertEquals(1, persistence.eventsForOrder("ord-1").size)
        assertEquals(1, persistence.eventsForTrace("trace-1").size)
        assertEquals(1, persistence.events().size)
        val event = persistence.eventsForTrace("trace-1").first()
        assertEquals(1L, event.sequenceNumber)
        assertEquals("trader-1", event.actorId)
        assertEquals("""{"source":"unit-test"}""", event.payloadJson)
    }

    @Test
    fun materializesVenueEventBatchIdempotently() {
        val persistence = InMemoryRuntimePersistence()
        val batch = venueEventBatch()

        assertEquals(1, persistence.materializeVenueEventBatch(batch))
        assertEquals(0, persistence.materializeVenueEventBatch(batch))

        val outcome = persistence.canonicalCommandOutcome("cmd-1")
        assertNotNull(outcome)
        assertEquals("batch-1", outcome.batchId)
        assertEquals("engine-0", outcome.shardId)
        assertEquals(3, outcome.partition)
        assertEquals(42L, outcome.streamSequence)
        assertEquals("SubmitOrder", outcome.commandType)
        assertEquals("accepted", outcome.resultStatus)
        assertEquals("""{"accepted":{"eventId":"evt-1","engineOrderId":"eng-ord-1","occurredAt":"2026-07-04T18:00:01Z"}}""", outcome.resultPayloadJson)

        assertFailsWith<IllegalStateException> {
            persistence.materializeVenueEventBatch(batch.copy(payloadChecksum = "different"))
        }
    }

    @Test
    fun projectsMaterializedVenueEventBatchOutcomesIntoCompactLifecycleRows() {
        val persistence = InMemoryRuntimePersistence()

        assertEquals(1, persistence.materializeVenueEventBatch(venueEventBatch()))
        assertEquals(1, persistence.projectCanonicalCommandOutcomes("runtime-normalized-venue-outcomes", 10))
        assertEquals(0, persistence.projectCanonicalCommandOutcomes("runtime-normalized-venue-outcomes", 10))

        val result = persistence.submitResult("cmd-1")
        assertNotNull(result)
        assertEquals("evt-1", result.accepted?.eventId)
        assertEquals("ord-1", result.accepted?.orderId)
        assertEquals("eng-ord-1", result.accepted?.engineOrderId)
        assertEquals("2026-07-04T18:00:01Z", result.accepted?.occurredAt)
        assertEquals(null, persistence.acceptedOrder("ord-1"))

        val events = persistence.eventsForOrder("ord-1")
        assertEquals(1, events.size)
        assertEquals("OrderAccepted", events.first().eventType)
        assertEquals("venue-event-batch-projector", events.first().producer)
        assertEquals("""{"accepted":{"eventId":"evt-1","engineOrderId":"eng-ord-1","occurredAt":"2026-07-04T18:00:01Z"}}""", events.first().payloadJson)

        val status = persistence.projectionStatus("runtime-normalized-venue-outcomes", source = "venue-event-batch")
        assertEquals(0, status.lag)
        assertEquals(42L, status.watermarks.single().lastPartitionSequence)
        assertEquals(42L, status.watermarks.single().canonicalMaxPartitionSequence)
    }

    @Test
    fun projectsRejectedVenueEventBatchOutcomesIntoCompactLifecycleRows() {
        val persistence = InMemoryRuntimePersistence()
        val batch = venueEventBatch().copy(
            batchId = "batch-rejected",
            payloadChecksum = "checksum-rejected",
            outcomes = listOf(
                VenueCommandOutcomeFact(
                    commandId = "cmd-rejected",
                    commandType = "SubmitOrder",
                    streamSequence = 43,
                    deliveredCount = 1,
                    payloadHash = "payload-hash-rejected",
                    instrumentId = "AAPL",
                    orderId = "ord-rejected",
                    resultStatus = "rejected",
                    rejectCode = "UNKNOWN_INSTRUMENT",
                    resultPayloadJson = """{"rejected":{"eventId":"evt-rejected","code":"UNKNOWN_INSTRUMENT","reason":"instrument missing","occurredAt":"2026-07-04T18:00:02Z"}}"""
                )
            )
        )

        assertEquals(1, persistence.materializeVenueEventBatch(batch))
        assertEquals(1, persistence.projectCanonicalCommandOutcomes("runtime-normalized-venue-outcomes", 10))

        val result = persistence.submitResult("cmd-rejected")
        assertNotNull(result)
        assertEquals("evt-rejected", result.rejected?.eventId)
        assertEquals("UNKNOWN_INSTRUMENT", result.rejected?.code)
        assertEquals("instrument missing", result.rejected?.reason)
        assertEquals("2026-07-04T18:00:02Z", result.rejected?.occurredAt)

        val events = persistence.eventsForOrder("ord-rejected")
        assertEquals(1, events.size)
        assertEquals("OrderRejected", events.first().eventType)
    }

    @Test
    fun projectsCancelAndModifyOutcomesIntoLifecycleRowsWithWatermarkReplay() {
        val persistence = InMemoryRuntimePersistence()
        val batch = venueEventBatch().copy(
            batchId = "batch-lifecycle",
            payloadChecksum = "checksum-lifecycle",
            firstSequence = 50,
            lastSequence = 51,
            commandCount = 2,
            outcomes = listOf(
                VenueCommandOutcomeFact(
                    commandId = "cmd-cancel-1",
                    commandType = "CancelOrder",
                    streamSequence = 50,
                    deliveredCount = 1,
                    payloadHash = "payload-hash-cancel-1",
                    instrumentId = "AAPL",
                    orderId = "ord-1",
                    resultStatus = "accepted",
                    resultPayloadJson = """{"accepted":{"eventId":"evt-cancel-1","engineOrderId":"eng-ord-1","occurredAt":"2026-07-04T18:00:03Z"}}"""
                ),
                VenueCommandOutcomeFact(
                    commandId = "cmd-modify-1",
                    commandType = "ModifyOrder",
                    streamSequence = 51,
                    deliveredCount = 1,
                    payloadHash = "payload-hash-modify-1",
                    instrumentId = "AAPL",
                    orderId = "ord-1",
                    resultStatus = "rejected",
                    rejectCode = "ORDER_NOT_OPEN",
                    resultPayloadJson = """{"rejected":{"eventId":"evt-modify-1","code":"ORDER_NOT_OPEN","reason":"order closed","occurredAt":"2026-07-04T18:00:04Z"}}"""
                )
            )
        )

        assertEquals(2, persistence.materializeVenueEventBatch(batch))
        assertEquals(2, persistence.projectCanonicalCommandOutcomes("runtime-normalized-venue-outcomes", 10))
        assertEquals(0, persistence.projectCanonicalCommandOutcomes("runtime-normalized-venue-outcomes", 10))

        assertEquals("evt-cancel-1", persistence.submitResult("cmd-cancel-1")?.accepted?.eventId)
        assertEquals("evt-modify-1", persistence.submitResult("cmd-modify-1")?.rejected?.eventId)
        val events = persistence.eventsForOrder("ord-1")
        assertEquals(listOf("OrderCancelled", "OrderRejected"), events.map { it.eventType })
        assertEquals(listOf("cmd-cancel-1", "cmd-modify-1"), events.map { it.traceId })

        val status = persistence.projectionStatus("runtime-normalized-venue-outcomes", source = "venue-event-batch")
        assertEquals(0, status.lag)
        assertEquals(51L, status.watermarks.single().lastPartitionSequence)
        assertEquals(51L, status.watermarks.single().canonicalMaxPartitionSequence)
    }

    private fun venueEventBatch(): VenueEventBatchFact {
        return VenueEventBatchFact(
            batchId = "batch-1",
            shardId = "engine-0",
            partition = 3,
            commandStream = "REEF_COMMANDS",
            eventStream = "REEF_VENUE_EVENTS",
            firstSequence = 42,
            lastSequence = 42,
            commandCount = 1,
            createdAt = "2026-07-04T18:00:00Z",
            payloadChecksum = "checksum-1",
            outcomes = listOf(
                VenueCommandOutcomeFact(
                    commandId = "cmd-1",
                    commandType = "SubmitOrder",
                    streamSequence = 42,
                    deliveredCount = 1,
                    payloadHash = "payload-hash-1",
                    instrumentId = "AAPL",
                    orderId = "ord-1",
                    resultStatus = "accepted",
                    resultPayloadJson = """{"accepted":{"eventId":"evt-1","engineOrderId":"eng-ord-1","occurredAt":"2026-07-04T18:00:01Z"}}"""
                )
            )
        )
    }
}

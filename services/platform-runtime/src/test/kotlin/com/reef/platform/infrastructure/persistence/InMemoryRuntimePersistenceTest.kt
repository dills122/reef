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
        assertEquals("""{"accepted":{"eventId":"evt-1"}}""", outcome.resultPayloadJson)

        assertFailsWith<IllegalStateException> {
            persistence.materializeVenueEventBatch(batch.copy(payloadChecksum = "different"))
        }
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
                    resultPayloadJson = """{"accepted":{"eventId":"evt-1"}}"""
                )
            )
        )
    }
}

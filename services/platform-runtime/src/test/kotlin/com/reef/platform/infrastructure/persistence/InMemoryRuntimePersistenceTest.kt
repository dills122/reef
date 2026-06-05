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
}

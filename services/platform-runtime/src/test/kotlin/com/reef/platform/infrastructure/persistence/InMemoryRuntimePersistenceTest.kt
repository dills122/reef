package com.reef.platform.infrastructure.persistence

import com.reef.platform.domain.ExecutionCreated
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.TradeCreated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class InMemoryRuntimePersistenceTest {
    @Test
    fun storesAndQueriesAcceptedArtifacts() {
        val persistence = InMemoryRuntimePersistence()

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

        assertNotNull(persistence.acceptedOrder("ord-1"))
        assertEquals(1, persistence.executionsForOrder("ord-1").size)
        assertEquals(1, persistence.tradesForOrder("ord-1").size)
    }
}

package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MarketDataProjectionWorkerTest {
    @Test
    fun processOnceRefreshesLifecycleBackedSnapshots() {
        MarketDataProjectionMetrics.resetForTests()
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "bid-1",
                engineOrderId = "eng-bid-1",
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
        val worker = MarketDataProjectionWorker(
            api = api,
            pollIntervalMs = 1L
        )

        val refreshed = worker.processOnce()

        assertEquals(1, refreshed)
        assertNotNull(persistence.orderLifecycleState("bid-1"))
        assertContains(api.marketDataSnapshot("AAPL"), "\"bestBidPrice\":\"150250000000\"")
        val stats = MarketDataProjectionMetrics.snapshot()
        assertEquals(1, stats.refreshes)
        assertEquals(1, stats.refreshedRows)
        assertEquals(0, stats.failed)
    }
}

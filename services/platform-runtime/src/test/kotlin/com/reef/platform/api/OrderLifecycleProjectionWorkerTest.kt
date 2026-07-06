package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OrderLifecycleProjectionWorkerTest {
    @Test
    fun processOnceRebuildsLifecycleStateForAcceptedOrders() {
        OrderLifecycleProjectionMetrics.resetForTests()
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        persistence.saveAcceptedOrder(
            PersistedOrder(
                orderId = "order-1",
                engineOrderId = "eng-order-1",
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
        val worker = OrderLifecycleProjectionWorker(
            api = api,
            pollIntervalMs = 1L
        )

        val rebuilt = worker.processOnce()

        assertEquals(1, rebuilt)
        val state = persistence.orderLifecycleState("order-1")
        assertNotNull(state)
        assertEquals("OPEN", state.status)
        val stats = OrderLifecycleProjectionMetrics.snapshot()
        assertEquals(1, stats.rebuilds)
        assertEquals(1, stats.rebuiltRows)
        assertEquals(0, stats.failed)
    }
}

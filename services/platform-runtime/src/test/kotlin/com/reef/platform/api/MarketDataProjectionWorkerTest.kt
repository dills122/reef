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
    fun processOnceRefreshesLifecycleBackedSnapshots() = checkProjectionWithInstrumentation(true)

    @Test
    fun disabledInstrumentationStillProjectsWithoutCallerCounters() = checkProjectionWithInstrumentation(false)

    @Test
    fun dedicatedLifecycleWorkerOwnsLifecycleProjection() {
        MarketDataProjectionMetrics.resetForTests()
        DownstreamProjectionCallerMetrics.resetForTests()
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
        val marketWorker = MarketDataProjectionWorker(
            api = api,
            instrumentationEnabled = true,
            projectLifecycleFirst = false
        )

        assertEquals(0, marketWorker.processOnce())
        assertEquals(null, persistence.orderLifecycleState("bid-1"))
        assertEquals(null, DownstreamProjectionCallerMetrics.snapshot(DownstreamProjectionStage.OrderLifecycle).callers["market-data-projector"])
        assertEquals(1L, DownstreamProjectionCallerMetrics.snapshot(DownstreamProjectionStage.MarketData).callers["market-data-projector"])

        assertEquals(1, OrderLifecycleProjectionWorker(api = api, instrumentationEnabled = true).processOnce())
        assertEquals(1, marketWorker.processOnce())
        assertContains(api.marketDataSnapshot("AAPL"), "\"bestBidPrice\":\"150250000000\"")
        assertEquals(1L, DownstreamProjectionCallerMetrics.snapshot(DownstreamProjectionStage.OrderLifecycle).callers["order-lifecycle-projector"])
        assertEquals(null, DownstreamProjectionCallerMetrics.snapshot(DownstreamProjectionStage.OrderLifecycle).callers["market-data-projector"])
    }

    private fun checkProjectionWithInstrumentation(enabled: Boolean) {
        MarketDataProjectionMetrics.resetForTests()
        DownstreamProjectionCallerMetrics.resetForTests()
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
            pollIntervalMs = 1L,
            instrumentationEnabled = enabled
        )

        val processed = worker.processOnce()

        assertEquals(1, processed)
        assertNotNull(persistence.orderLifecycleState("bid-1"))
        assertContains(api.marketDataSnapshot("AAPL"), "\"bestBidPrice\":\"150250000000\"")
        val stats = MarketDataProjectionMetrics.snapshot()
        assertEquals(1, stats.cycles)
        assertEquals(1, stats.processedRows)
        assertEquals(0, stats.failed)
        val lifecycleCallers = DownstreamProjectionCallerMetrics.snapshot(DownstreamProjectionStage.OrderLifecycle)
        val marketDataCallers = DownstreamProjectionCallerMetrics.snapshot(DownstreamProjectionStage.MarketData)
        assertEquals(if (enabled) 1L else null, lifecycleCallers.callers["market-data-projector"])
        assertEquals(if (enabled) 1L else null, marketDataCallers.callers["market-data-projector"])

        val secondCycleProcessed = worker.processOnce()
        assertEquals(0, secondCycleProcessed)
        assertEquals(0, MarketDataProjectionMetrics.snapshot().lastProcessedRows)
    }
}

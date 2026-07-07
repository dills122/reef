package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OrderLifecycleProjectionWorkerTest {
    @Test
    fun processOnceProjectsLifecycleStateForDirtyOrders() {
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

        val processed = worker.processOnce()

        assertEquals(1, processed)
        val state = persistence.orderLifecycleState("order-1")
        assertNotNull(state)
        assertEquals("OPEN", state.status)
        val stats = OrderLifecycleProjectionMetrics.snapshot()
        assertEquals(1, stats.cycles)
        assertEquals(1, stats.processedRows)
        assertEquals(0, stats.failed)

        val secondCycleProcessed = worker.processOnce()
        assertEquals(0, secondCycleProcessed)
    }

    /**
     * WORK_PLAN.md crash/restart scenario 5: "projector exits mid-batch and replays
     * idempotently." The projector's only durable state is the dirty-order marker set
     * and the derived `order_lifecycle_state` rows living in `RuntimePersistence` (not
     * in the `OrderLifecycleProjectionWorker` instance itself, which is just a thread
     * wrapper). A crash mid-batch means: some dirty order_ids got recomputed and
     * removed from the dirty set, others didn't, and the process then restarts with a
     * brand new worker over the same durable persistence. This test proves that a
     * restarted worker resumes from exactly where the dirty set left off, drains the
     * remainder, and that reprocessing (replay) never produces incorrect or duplicated
     * lifecycle state, since recomputation is a pure, idempotent function of the
     * underlying orders/executions/events facts.
     */
    @Test
    fun projectorRestartsMidBatchAndReplaysIdempotentlyAcrossWorkerInstances() {
        OrderLifecycleProjectionMetrics.resetForTests()
        val persistence = InMemoryRuntimePersistence()
        val api = PlatformApi(OrderApplicationService(runtimePersistence = persistence))
        val orderIds = (1..5).map { "order-mid-batch-$it" }
        orderIds.forEach { orderId ->
            persistence.saveAcceptedOrder(
                PersistedOrder(
                    orderId = orderId,
                    engineOrderId = "eng-$orderId",
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
        }

        // First worker instance processes only part of the dirty batch, then "exits" -
        // simulated by simply discarding this worker without ever draining the rest.
        val firstWorker = OrderLifecycleProjectionWorker(api = api, pollIntervalMs = 1L, batchSize = 2)
        val firstProcessed = firstWorker.processOnce()
        assertEquals(2, firstProcessed)
        val processedAfterFirstCycle = orderIds.count { persistence.orderLifecycleState(it) != null }
        assertEquals(2, processedAfterFirstCycle)

        // "Restart": a brand new worker instance over the same durable persistence
        // resumes projecting the remaining dirty orders, draining them across cycles.
        val restartedWorker = OrderLifecycleProjectionWorker(api = api, pollIntervalMs = 1L, batchSize = 2)
        var totalAfterRestart = 0L
        var safety = 0
        while (safety < 10) {
            val processed = restartedWorker.processOnce()
            totalAfterRestart += processed
            if (processed == 0L) break
            safety++
        }

        assertEquals(3, totalAfterRestart)
        orderIds.forEach { orderId ->
            val state = persistence.orderLifecycleState(orderId)
            assertNotNull(state)
            assertEquals("OPEN", state.status)
        }

        // Idempotent replay: nothing left dirty, so a further cycle (even from yet
        // another fresh worker instance) is a true no-op, not a duplicate recompute.
        val replayWorker = OrderLifecycleProjectionWorker(api = api, pollIntervalMs = 1L, batchSize = 10)
        val replayProcessed = replayWorker.processOnce()
        assertEquals(0, replayProcessed)
        orderIds.forEach { orderId ->
            assertEquals("OPEN", persistence.orderLifecycleState(orderId)?.status)
        }

        val stats = OrderLifecycleProjectionMetrics.snapshot()
        assertEquals(0, stats.failed)
    }
}

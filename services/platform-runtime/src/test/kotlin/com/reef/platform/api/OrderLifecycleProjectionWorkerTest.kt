package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.domain.PersistedOrder
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.reef.platform.infrastructure.persistence.RuntimePersistence
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OrderLifecycleProjectionWorkerTest {
    @Test
    fun productiveBatchesContinueImmediatelyUntilZero() {
        OrderLifecycleProjectionMetrics.resetForTests()
        val events = LinkedBlockingQueue<String>()
        val calls = AtomicInteger()
        val worker = worker { batch ->
            events.add("batch:$batch")
            if (calls.incrementAndGet() < 3) 500L else 0L
        }
        val waiting = CountDownLatch(1)
        var runThread: Thread? = null
        worker.waitForNextPoll = { millis ->
            runThread = Thread.currentThread()
            events.add("wait:$millis")
            waiting.countDown()
            CountDownLatch(1).await()
        }
        try {
            worker.start()
            await(waiting)
            assertEquals(listOf("batch:500", "batch:500", "batch:500", "wait:250"), events.toList())
            assertEquals(3L, OrderLifecycleProjectionMetrics.snapshot().cycles)
            assertEquals(1000L, OrderLifecycleProjectionMetrics.snapshot().processedRows)
        } finally { worker.stop(); join(runThread) }
    }

    @Test
    fun zeroAndFailureRetainConfiguredBackoffAndBatchSize() {
        OrderLifecycleProjectionMetrics.resetForTests()
        val waits = LinkedBlockingQueue<Long>()
        val continueWait = LinkedBlockingQueue<Unit>()
        val calls = AtomicInteger()
        var runThread: Thread? = null
        val batches = LinkedBlockingQueue<Int>()
        val worker = worker(batchSize = 37, pollIntervalMs = 251) { batch ->
            batches.add(batch)
            if (calls.incrementAndGet() == 2) error("projection unavailable")
            0L
        }
        worker.waitForNextPoll = { millis ->
            runThread = Thread.currentThread()
            waits.add(millis)
            continueWait.take()
        }
        try {
            worker.start()
            assertEquals(251L, take(waits))
            continueWait.add(Unit)
            assertEquals(251L, take(waits))
            assertEquals(listOf(37, 37), batches.toList())
            val stats = OrderLifecycleProjectionMetrics.snapshot()
            assertEquals(1L, stats.cycles)
            assertEquals(1L, stats.failed)
            assertEquals("projection unavailable", stats.lastError)
        } finally { worker.stop(); join(runThread) }
    }

    @Test
    fun duplicateStartAndStopDuringProgressDoNotCreateAnotherCall() {
        val calls = LinkedBlockingQueue<BlockedCall>()
        val worker = worker { calls.block() }
        val waits = AtomicInteger()
        worker.waitForNextPoll = { waits.incrementAndGet() }
        worker.start()
        val first = take(calls)
        try {
            worker.start()
            assertEquals(null, calls.poll(100, TimeUnit.MILLISECONDS))
            worker.stop()
            first.release.countDown()
            join(first.thread)
            assertTrue(calls.isEmpty())
            assertEquals(0, waits.get())
        } finally { worker.stop(); first.release.countDown(); join(first.thread) }
    }

    @Test
    fun stopInterruptsIdleWaitAndDoesNotRestart() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        var runThread: Thread? = null
        val calls = AtomicInteger()
        val worker = worker { calls.incrementAndGet(); 0L }
        worker.waitForNextPoll = {
            runThread = Thread.currentThread()
            entered.countDown()
            try { CountDownLatch(1).await() } catch (ex: InterruptedException) {
                interrupted.countDown()
                throw ex
            }
        }
        try {
            worker.start()
            await(entered)
            worker.stop()
            await(interrupted)
            join(runThread)
            assertEquals(1, calls.get())
        } finally { worker.stop(); join(runThread) }
    }

    @Test
    fun stoppedBlockedRunDefersRestartUntilOldCallExits() {
        val calls = LinkedBlockingQueue<BlockedCall>()
        val worker = worker { calls.block() }
        worker.start()
        val first = take(calls)
        var second: BlockedCall? = null
        var third: BlockedCall? = null
        try {
            worker.stop()
            worker.start()
            worker.start()
            val premature = calls.poll(100, TimeUnit.MILLISECONDS)
            if (premature != null) { premature.release.countDown(); worker.stop() }
            assertEquals(null, premature, "old and new persistence calls must not overlap")
            first.release.countDown()
            second = take(calls)
            join(first.thread)
            assertTrue(second.thread.isAlive, "old run finalization must not cancel replacement")
            second.release.countDown()
            third = take(calls)
            assertEquals(second.thread, third.thread, "replacement must keep making progress after old finalization")
            worker.stop()
            third.release.countDown()
            join(second.thread)
            assertTrue(calls.isEmpty())
        } finally {
            worker.stop()
            first.release.countDown()
            second?.release?.countDown()
            third?.release?.countDown()
            calls.forEach { it.release.countDown() }
            join(first.thread)
            second?.let { join(it.thread) }
        }
    }

    @Test
    fun stopStartStopCancelsDeferredRestart() {
        val calls = LinkedBlockingQueue<BlockedCall>()
        val worker = worker { calls.block() }
        worker.start()
        val first = take(calls)
        try {
            worker.stop()
            worker.start()
            worker.stop()
            first.release.countDown()
            join(first.thread)
            val unexpected = calls.poll(100, TimeUnit.MILLISECONDS)
            unexpected?.release?.countDown()
            assertEquals(null, unexpected)
        } finally {
            worker.stop()
            first.release.countDown()
            calls.forEach { it.release.countDown() }
            join(first.thread)
        }
    }

    @Test
    fun unsolicitedInterruptExitsWithoutAutomaticRestart() {
        val calls = LinkedBlockingQueue<BlockedCall>()
        val worker = worker { calls.block(preserveInterrupt = true) }
        worker.start()
        val first = take(calls)
        try {
            first.thread.interrupt()
            first.release.countDown()
            join(first.thread)
            assertTrue(calls.isEmpty())
        } finally { worker.stop(); first.release.countDown(); calls.forEach { it.release.countDown() } }
    }

    @Test
    fun interruptedPersistenceStopsWithoutBackoffOrAutomaticRestart() {
        OrderLifecycleProjectionMetrics.resetForTests()
        val entered = LinkedBlockingQueue<Thread>()
        val waits = AtomicInteger()
        val worker = worker {
            entered.add(Thread.currentThread())
            CountDownLatch(1).await()
            0L
        }
        worker.waitForNextPoll = { waits.incrementAndGet(); throw InterruptedException("unexpected backoff") }
        worker.start()
        val first = take(entered)
        try {
            first.interrupt()
            join(first)
            assertEquals(0, waits.get())
            assertEquals(1L, OrderLifecycleProjectionMetrics.snapshot().failed)
            assertTrue(entered.isEmpty())
            worker.start()
            val replacement = take(entered)
            worker.stop()
            join(replacement)
        } finally { worker.stop(); join(first) }
    }

    private data class BlockedCall(val thread: Thread, val release: CountDownLatch = CountDownLatch(1))

    private fun LinkedBlockingQueue<BlockedCall>.block(preserveInterrupt: Boolean = false): Long {
        val call = BlockedCall(Thread.currentThread())
        add(call)
        var interrupted = false
        while (true) {
            try { call.release.await(); break } catch (_: InterruptedException) { interrupted = true }
        }
        if (preserveInterrupt && interrupted) Thread.currentThread().interrupt()
        return 1L
    }

    private fun worker(batchSize: Int = 500, pollIntervalMs: Long = 250, project: (Int) -> Long): OrderLifecycleProjectionWorker {
        val persistence = object : RuntimePersistence by InMemoryRuntimePersistence() {
            override fun projectOrderLifecycleState(batchSize: Int): Long = project(batchSize)
        }
        return OrderLifecycleProjectionWorker(
            api = PlatformApi(OrderApplicationService(runtimePersistence = persistence)),
            batchSize = batchSize, pollIntervalMs = pollIntervalMs, instrumentationEnabled = false
        )
    }

    private fun <T> take(queue: LinkedBlockingQueue<T>): T = assertNotNull(queue.poll(5, TimeUnit.SECONDS))
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(5, TimeUnit.SECONDS), "worker did not reach barrier")
    private fun join(thread: Thread?) {
        assertNotNull(thread)
        thread.join(5000)
        assertFalse(thread.isAlive, "worker did not terminate")
    }

    @Test
    fun processOnceProjectsLifecycleStateForDirtyOrders() = checkProjectionWithInstrumentation(true)

    @Test
    fun disabledInstrumentationStillProjectsWithoutCallerCounters() = checkProjectionWithInstrumentation(false)

    private fun checkProjectionWithInstrumentation(enabled: Boolean) {
        OrderLifecycleProjectionMetrics.resetForTests()
        DownstreamProjectionCallerMetrics.resetForTests()
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
            pollIntervalMs = 1L,
            instrumentationEnabled = enabled
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
        val callerStats = DownstreamProjectionCallerMetrics.snapshot(DownstreamProjectionStage.OrderLifecycle)
        assertEquals(if (enabled) 1L else 0L, callerStats.calls)
        assertEquals(if (enabled) 1L else null, callerStats.callers["order-lifecycle-projector"])

        val secondCycleProcessed = worker.processOnce()
        assertEquals(0, secondCycleProcessed)
        assertEquals(0, OrderLifecycleProjectionMetrics.snapshot().lastProcessedRows)
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
        DownstreamProjectionCallerMetrics.resetForTests()
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

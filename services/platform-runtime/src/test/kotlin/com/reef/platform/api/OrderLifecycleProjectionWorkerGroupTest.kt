package com.reef.platform.api

import com.reef.platform.application.OrderApplicationService
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import com.reef.platform.infrastructure.persistence.RuntimePersistence
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class OrderLifecycleProjectionWorkerGroupTest {
    private data class Call(val thread: Thread, val release: CountDownLatch = CountDownLatch(1))

    @Test
    fun fourLoopsRunConcurrentlyAndRestartWithoutExceedingConfiguredBound() {
        DownstreamProjectionCallerMetrics.resetForTests()
        val calls = LinkedBlockingQueue<Call>()
        val persistence = object : RuntimePersistence by InMemoryRuntimePersistence() {
            override fun projectOrderLifecycleState(batchSize: Int): Long {
                assertEquals(250, batchSize)
                val call = Call(Thread.currentThread())
                calls.add(call)
                // Model JDBC returning only after commit, despite interruption.
                while (true) {
                    try { call.release.await(); break } catch (_: InterruptedException) { }
                }
                return 0
            }
        }
        val group = OrderLifecycleProjectionWorkerGroup(
            PlatformApi(OrderApplicationService(runtimePersistence = persistence)),
            workerCount = 4, batchSize = 250, instrumentationEnabled = true
        )
        val held = mutableListOf<Call>()
        try {
            group.start()
            repeat(4) { held.add(assertNotNull(calls.poll(5, TimeUnit.SECONDS))) }
            group.start()
            assertEquals(null, calls.poll(100, TimeUnit.MILLISECONDS))
            val active = DownstreamProjectionCallerMetrics.snapshot(DownstreamProjectionStage.OrderLifecycle)
            assertEquals(4L, active.active)
            assertEquals(4L, active.maxConcurrent)
            group.stop()
            group.start()
            assertEquals(null, calls.poll(100, TimeUnit.MILLISECONDS))
            held.forEach { it.release.countDown() }
            held.forEach { it.thread.join(5000); assertFalse(it.thread.isAlive) }
            repeat(4) { held.add(assertNotNull(calls.poll(5, TimeUnit.SECONDS))) }
            group.stop()
        } finally {
            group.stop()
            held.forEach { it.release.countDown() }
            held.forEach { it.thread.join(5000); assertFalse(it.thread.isAlive) }
        }
        val final = DownstreamProjectionCallerMetrics.snapshot(DownstreamProjectionStage.OrderLifecycle)
        assertEquals(8L, final.calls)
        assertEquals(8L, final.completed)
        assertEquals(0L, final.failed)
        assertEquals(0L, final.active)
        assertEquals(4L, final.maxConcurrent)
        assertEquals(mapOf("order-lifecycle-projector" to 8L), final.callers)
    }

    @Test
    fun rejectsUnboundedOrEmptyWorkerConfiguration() {
        val api = PlatformApi(OrderApplicationService(runtimePersistence = InMemoryRuntimePersistence()))
        for (count in listOf(0, -1, 33)) {
            assertFailsWith<IllegalArgumentException> { OrderLifecycleProjectionWorkerGroup(api, workerCount = count) }
        }
    }
}

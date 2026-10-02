package com.reef.platform.calcify

import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BoundedSourceAcknowledgementsTest {
    @Test fun laterReadyAcknowledgementCannotPublishAheadOfFirst() {
        val published = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val queue = BoundedSourceAcknowledgements<String, Long>(2) { value, offset -> published.add(value to offset) }
        val first = CompletableFuture<Long>()
        queue.add("first", first)
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val thread = Thread {
            started.countDown()
            queue.add("second", CompletableFuture.completedFuture(8L))
            done.countDown()
        }
        thread.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertFalse(done.await(50, TimeUnit.MILLISECONDS))
        assertTrue(published.isEmpty())
        first.complete(7L)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        thread.join()
        assertEquals(listOf("first" to 7L), published.toList())
        queue.drain()
        assertEquals(listOf("first" to 7L, "second" to 8L), published.toList())
    }

    @Test fun configuredBoundAndFinalDrainCoverEverySend() {
        val published = mutableListOf<Int>()
        val queue = BoundedSourceAcknowledgements<Int, Int>(16) { _, offset -> published += offset }
        repeat(31) { queue.add(it, CompletableFuture.completedFuture(it)) }
        assertEquals(16, queue.peakPending)
        assertEquals((0..15).toList(), published)
        queue.drain()
        assertEquals((0..30).toList(), published)
    }

    @Test fun controlOneWaitsForEverySourceAcknowledgement() {
        val published = mutableListOf<Int>()
        val queue = BoundedSourceAcknowledgements<Int, Int>(1) { _, offset -> published += offset }
        repeat(3) { queue.add(it, CompletableFuture.completedFuture(it)) }
        assertEquals(listOf(0, 1, 2), published)
        assertEquals(1, queue.peakPending)
    }

    @Test fun failedSourceNeverPublishesVerification() {
        var published = false
        val queue = BoundedSourceAcknowledgements<Int, Int>(1) { _, _ -> published = true }
        assertFailsWith<ExecutionException> {
            queue.add(1, CompletableFuture.failedFuture(IllegalStateException("source failed")))
        }
        assertFalse(published)
    }
}

package com.reef.platform.api

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryIdempotencyStoreTest {
    private val initialTime = Instant.parse("2026-10-02T00:00:00Z")
    private val original = IdempotencyResult(202, "original")
    private val renewed = IdempotencyResult(200, "renewed")
    private val retention = object : IdempotencyRetentionPolicy {
        override fun ttlFor(route: String) = IdempotencyTtlClass.SHORT
        override fun durationSeconds(ttlClass: IdempotencyTtlClass) = 60L
    }

    @Test
    fun saveRenewsAtExpiryWithoutLookupOrCleanup() {
        val clock = MutableClock(initialTime)
        val store = InMemoryIdempotencyStore(retention, clock)
        save(store, original)
        clock.now = initialTime.plusSeconds(60)
        save(store, renewed)

        assertEquals(renewed, find(store))
        clock.now = initialTime.plusSeconds(119)
        assertEquals(renewed, find(store))
        clock.now = initialTime.plusSeconds(120)
        assertNull(find(store))
    }

    @Test
    fun liveSavePreservesResultAndExpiry() {
        val clock = MutableClock(initialTime)
        val store = InMemoryIdempotencyStore(retention, clock)
        save(store, original)
        clock.now = initialTime.plusSeconds(59)
        save(store, renewed)
        assertEquals(original, find(store))
        clock.now = initialTime.plusSeconds(60)
        assertNull(find(store))
    }

    @Test
    fun concurrentRenewalsKeepOneLiveWinner() {
        val clock = MutableClock(initialTime)
        val store = InMemoryIdempotencyStore(retention, clock)
        save(store, original)
        clock.now = initialTime.plusSeconds(60)
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val candidates = (1..8).map { IdempotencyResult(200 + it, "renewal-$it") }
        try {
            val futures = candidates.map { candidate ->
                executor.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    save(store, candidate)
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            val winner = find(store)
            assertTrue(winner in candidates)
            candidates.forEach { save(store, it) }
            assertEquals(winner, find(store))
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun expiredReaderCannotDeleteConcurrentRenewal() {
        val clock = MutableClock(initialTime)
        val store = InMemoryIdempotencyStore(retention, clock)
        save(store, original)
        clock.now = initialTime.plusSeconds(60)
        val observed = CountDownLatch(1)
        val resume = CountDownLatch(1)
        clock.beforeRead = {
            if (Thread.currentThread().name == "expired-reader") {
                observed.countDown()
                check(resume.await(10, TimeUnit.SECONDS))
            }
        }
        val executor = Executors.newSingleThreadExecutor { Thread(it, "expired-reader") }
        try {
            val read = executor.submit<IdempotencyResult?> { find(store) }
            assertTrue(observed.await(10, TimeUnit.SECONDS))
            save(store, renewed)
            resume.countDown()
            assertNull(read.get(10, TimeUnit.SECONDS))
            assertEquals(renewed, find(store))
            store.cleanupExpired(initialTime.plusSeconds(60))
            assertEquals(renewed, find(store))
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    private fun save(store: IdempotencyStore, result: IdempotencyResult) =
        store.save("client", "/api/v1/orders/submit", "key", result, IdempotencyTtlClass.SHORT)

    private fun find(store: IdempotencyStore) = store.find("client", "/api/v1/orders/submit", "key")

    private class MutableClock(@Volatile var now: Instant) : Clock() {
        @Volatile var beforeRead: () -> Unit = {}
        override fun instant(): Instant {
            beforeRead()
            return now
        }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
}

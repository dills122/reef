package com.reef.stockdata

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Short-lived cache keyed by provider/symbol/market-date/source-type, per the plan doc's "Resilience" section. */
class TtlCache<K, V>(
    private val ttl: Duration,
    private val clock: () -> Instant = Instant::now,
) {
    private data class Entry<V>(val value: V, val cachedAt: Instant)

    private val entries = ConcurrentHashMap<K, Entry<V>>()

    fun put(key: K, value: V) {
        entries[key] = Entry(value, clock())
    }

    /** Returns the cached value even if expired - callers decide whether stale data is acceptable. */
    fun getEvenIfStale(key: K): V? = entries[key]?.value

    fun getFresh(key: K): V? {
        val entry = entries[key] ?: return null
        return if (Duration.between(entry.cachedAt, clock()) <= ttl) entry.value else null
    }
}

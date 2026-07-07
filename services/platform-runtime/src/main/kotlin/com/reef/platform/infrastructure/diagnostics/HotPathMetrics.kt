package com.reef.platform.infrastructure.diagnostics

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

object HotPathMetrics {
    private val stats = ConcurrentHashMap<String, PhaseStats>()
    private val resetAt = AtomicLong(System.currentTimeMillis())

    inline fun <T> time(phase: String, block: () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            record(phase, System.nanoTime() - started)
        }
    }

    fun record(phase: String, nanos: Long) {
        stats.computeIfAbsent(phase) { PhaseStats() }.record(nanos)
    }

    fun reset() {
        stats.clear()
        resetAt.set(System.currentTimeMillis())
    }

    fun snapshot(): Map<String, Any> {
        val phases = stats.toSortedMap().mapValues { (_, stat) -> stat.snapshot() }
        return mapOf(
            "resetAt" to Instant.ofEpochMilli(resetAt.get()).toString(),
            "phases" to phases
        )
    }
}

private class PhaseStats {
    private val count = LongAdder()
    private val totalNanos = LongAdder()
    private val maxNanos = AtomicLong(0)

    fun record(nanos: Long) {
        count.increment()
        totalNanos.add(nanos)
        maxNanos.accumulateAndGet(nanos, ::maxOf)
    }

    fun snapshot(): Map<String, Any> {
        val currentCount = count.sum()
        val currentTotal = totalNanos.sum()
        val avgNanos = if (currentCount == 0L) 0.0 else currentTotal.toDouble() / currentCount.toDouble()
        return mapOf(
            "count" to currentCount,
            "totalMs" to nanosToMillis(currentTotal.toDouble()),
            "avgMs" to nanosToMillis(avgNanos),
            "maxMs" to nanosToMillis(maxNanos.get().toDouble())
        )
    }

    private fun nanosToMillis(nanos: Double): Double = nanos / 1_000_000.0
}

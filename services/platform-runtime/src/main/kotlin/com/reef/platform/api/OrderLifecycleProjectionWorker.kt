package com.reef.platform.api

import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class OrderLifecycleProjectionWorker(
    private val api: PlatformApi,
    private val pollIntervalMs: Long = 250L,
    private val workerName: String = "reef-order-lifecycle-projector"
) {
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        workerThread = thread(name = workerName, isDaemon = true) {
            while (running.get()) {
                processOnce()
                try {
                    Thread.sleep(pollIntervalMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    running.set(false)
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        workerThread?.interrupt()
    }

    fun processOnce(): Long {
        return try {
            HotPathMetrics.time("orderLifecycleProjector.rebuild") {
                api.rebuildOrderLifecycleStateCount()
            }.also { rebuilt ->
                OrderLifecycleProjectionMetrics.recordRebuilt(rebuilt)
            }
        } catch (ex: Exception) {
            OrderLifecycleProjectionMetrics.recordFailed(ex.message ?: ex::class.simpleName ?: "unknown")
            0
        }
    }
}

data class OrderLifecycleProjectionStats(
    val rebuilds: Long,
    val rebuiltRows: Long,
    val failed: Long,
    val lastRebuiltAt: String,
    val lastFailedAt: String,
    val lastError: String
)

object OrderLifecycleProjectionMetrics {
    private val rebuilds = AtomicLong(0)
    private val rebuiltRows = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val lastRebuiltAtEpochMs = AtomicLong(0)
    private val lastFailedAtEpochMs = AtomicLong(0)
    @Volatile
    private var lastError: String = ""

    fun recordRebuilt(rows: Long) {
        rebuilds.incrementAndGet()
        rebuiltRows.addAndGet(rows)
        lastRebuiltAtEpochMs.set(System.currentTimeMillis())
    }

    fun recordFailed(error: String) {
        failed.incrementAndGet()
        lastFailedAtEpochMs.set(System.currentTimeMillis())
        lastError = error
    }

    fun snapshot(): OrderLifecycleProjectionStats {
        return OrderLifecycleProjectionStats(
            rebuilds = rebuilds.get(),
            rebuiltRows = rebuiltRows.get(),
            failed = failed.get(),
            lastRebuiltAt = instantString(lastRebuiltAtEpochMs.get()),
            lastFailedAt = instantString(lastFailedAtEpochMs.get()),
            lastError = lastError
        )
    }

    fun resetForTests() {
        rebuilds.set(0)
        rebuiltRows.set(0)
        failed.set(0)
        lastRebuiltAtEpochMs.set(0)
        lastFailedAtEpochMs.set(0)
        lastError = ""
    }

    private fun instantString(epochMs: Long): String {
        if (epochMs <= 0) return ""
        return Instant.ofEpochMilli(epochMs).toString()
    }
}

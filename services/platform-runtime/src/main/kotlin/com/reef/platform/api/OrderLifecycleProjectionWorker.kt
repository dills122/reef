package com.reef.platform.api

import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class OrderLifecycleProjectionWorker(
    private val api: PlatformApi,
    private val pollIntervalMs: Long = 250L,
    private val batchSize: Int = 500,
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
            HotPathMetrics.time("orderLifecycleProjector.project") {
                api.projectOrderLifecycleStateCount(batchSize)
            }.also { processed ->
                OrderLifecycleProjectionMetrics.recordProcessed(processed)
            }
        } catch (ex: Exception) {
            OrderLifecycleProjectionMetrics.recordFailed(ex.message ?: ex::class.simpleName ?: "unknown")
            0
        }
    }
}

data class OrderLifecycleProjectionStats(
    val cycles: Long,
    val processedRows: Long,
    val failed: Long,
    val lastProcessedAt: String,
    val lastFailedAt: String,
    val lastError: String
)

object OrderLifecycleProjectionMetrics {
    private val cycles = AtomicLong(0)
    private val processedRows = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val lastProcessedAtEpochMs = AtomicLong(0)
    private val lastFailedAtEpochMs = AtomicLong(0)
    @Volatile
    private var lastError: String = ""

    fun recordProcessed(rows: Long) {
        cycles.incrementAndGet()
        processedRows.addAndGet(rows)
        lastProcessedAtEpochMs.set(System.currentTimeMillis())
    }

    fun recordFailed(error: String) {
        failed.incrementAndGet()
        lastFailedAtEpochMs.set(System.currentTimeMillis())
        lastError = error
    }

    fun snapshot(): OrderLifecycleProjectionStats {
        return OrderLifecycleProjectionStats(
            cycles = cycles.get(),
            processedRows = processedRows.get(),
            failed = failed.get(),
            lastProcessedAt = instantString(lastProcessedAtEpochMs.get()),
            lastFailedAt = instantString(lastFailedAtEpochMs.get()),
            lastError = lastError
        )
    }

    fun resetForTests() {
        cycles.set(0)
        processedRows.set(0)
        failed.set(0)
        lastProcessedAtEpochMs.set(0)
        lastFailedAtEpochMs.set(0)
        lastError = ""
    }

    private fun instantString(epochMs: Long): String {
        if (epochMs <= 0) return ""
        return Instant.ofEpochMilli(epochMs).toString()
    }
}

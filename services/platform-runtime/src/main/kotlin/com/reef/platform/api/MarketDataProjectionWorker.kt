package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class MarketDataProjectionWorker(
    private val api: PlatformApi,
    private val projectionName: String = RuntimeEnv.string("MARKET_DATA_PROJECTOR_PROJECTION_NAME", "market-data-top-of-book"),
    private val sourceProjectionName: String = RuntimeEnv.string("MARKET_DATA_PROJECTOR_SOURCE_PROJECTION_NAME", "runtime-normalized-venue-outcomes"),
    private val pollIntervalMs: Long = RuntimeEnv.long("MARKET_DATA_PROJECTOR_POLL_MS", 250L, min = 1L),
    private val workerName: String = "reef-market-data-projector"
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
            HotPathMetrics.time("marketDataProjector.refreshSnapshots") {
                api.refreshMarketDataSnapshotsCount(projectionName, sourceProjectionName)
            }.also { refreshed ->
                MarketDataProjectionMetrics.recordRefreshed(refreshed)
            }
        } catch (ex: Exception) {
            MarketDataProjectionMetrics.recordFailed(ex.message ?: ex::class.simpleName ?: "unknown")
            0
        }
    }
}

data class MarketDataProjectionStats(
    val refreshes: Long,
    val refreshedRows: Long,
    val failed: Long,
    val lastRefreshedAt: String,
    val lastFailedAt: String,
    val lastError: String
)

object MarketDataProjectionMetrics {
    private val refreshes = AtomicLong(0)
    private val refreshedRows = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val lastRefreshedAtEpochMs = AtomicLong(0)
    private val lastFailedAtEpochMs = AtomicLong(0)
    @Volatile
    private var lastError: String = ""

    fun recordRefreshed(rows: Long) {
        refreshes.incrementAndGet()
        refreshedRows.addAndGet(rows)
        lastRefreshedAtEpochMs.set(System.currentTimeMillis())
    }

    fun recordFailed(error: String) {
        failed.incrementAndGet()
        lastFailedAtEpochMs.set(System.currentTimeMillis())
        lastError = error
    }

    fun snapshot(): MarketDataProjectionStats {
        return MarketDataProjectionStats(
            refreshes = refreshes.get(),
            refreshedRows = refreshedRows.get(),
            failed = failed.get(),
            lastRefreshedAt = instantString(lastRefreshedAtEpochMs.get()),
            lastFailedAt = instantString(lastFailedAtEpochMs.get()),
            lastError = lastError
        )
    }

    fun resetForTests() {
        refreshes.set(0)
        refreshedRows.set(0)
        failed.set(0)
        lastRefreshedAtEpochMs.set(0)
        lastFailedAtEpochMs.set(0)
        lastError = ""
    }

    private fun instantString(epochMs: Long): String {
        if (epochMs <= 0) return ""
        return Instant.ofEpochMilli(epochMs).toString()
    }
}

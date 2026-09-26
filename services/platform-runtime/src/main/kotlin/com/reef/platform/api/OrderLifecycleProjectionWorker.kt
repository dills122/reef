package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class OrderLifecycleProjectionWorker(
    private val api: PlatformApi,
    private val pollIntervalMs: Long = 250L,
    private val batchSize: Int = 500,
    private val workerName: String = "reef-order-lifecycle-projector",
    private val instrumentationEnabled: Boolean = RuntimeEnv.bool("PROJECTION_DOWNSTREAM_INSTRUMENTATION_ENABLED", false)
) {
    internal var waitForNextPoll: (Long) -> Unit = Thread::sleep

    private class Run {
        val cancelled = AtomicBoolean(false)
        lateinit var thread: Thread
    }

    private val lifecycle = Any()
    private var activeRun: Run? = null
    private var restartRequested = false

    fun start() {
        synchronized(lifecycle) {
            val current = activeRun
            if (current == null) {
                launchRun()
            } else if (current.cancelled.get()) {
                restartRequested = true
            }
        }
    }

    fun stop() {
        val stopped = synchronized(lifecycle) {
            restartRequested = false
            activeRun?.also { it.cancelled.set(true) }
        }
        stopped?.thread?.interrupt()
    }

    // Called with lifecycle held; publish before starting so stop always sees the run.
    private fun launchRun() {
        val run = Run()
        run.thread = thread(start = false, name = workerName, isDaemon = true) {
            try {
                while (!run.cancelled.get() && !Thread.currentThread().isInterrupted) {
                    val processed = processOnce()
                    if (run.cancelled.get() || Thread.currentThread().isInterrupted) break
                    if (processed <= 0) waitForNextPoll(pollIntervalMs)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(lifecycle) {
                    if (activeRun === run) {
                        activeRun = null
                        if (restartRequested) {
                            restartRequested = false
                            launchRun()
                        }
                    }
                }
            }
        }
        activeRun = run
        run.thread.start()
    }

    fun processOnce(): Long {
        return try {
            HotPathMetrics.time("orderLifecycleProjector.project") {
                DownstreamProjectionCallerMetrics.record(
                    stage = DownstreamProjectionStage.OrderLifecycle,
                    caller = "order-lifecycle-projector",
                    enabled = instrumentationEnabled
                ) {
                    api.projectOrderLifecycleStateCount(batchSize)
                }
            }.also { processed ->
                OrderLifecycleProjectionMetrics.recordProcessed(processed)
            }
        } catch (ex: Exception) {
            if (ex is InterruptedException) Thread.currentThread().interrupt()
            OrderLifecycleProjectionMetrics.recordFailed(ex.message ?: ex::class.simpleName ?: "unknown")
            0
        }
    }
}

data class OrderLifecycleProjectionStats(
    val cycles: Long,
    val processedRows: Long,
    val lastProcessedRows: Long,
    val failed: Long,
    val lastProcessedAt: String,
    val lastFailedAt: String,
    val lastError: String
)

object OrderLifecycleProjectionMetrics {
    private val cycles = AtomicLong(0)
    private val processedRows = AtomicLong(0)
    private val lastProcessedRows = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val lastProcessedAtEpochMs = AtomicLong(0)
    private val lastFailedAtEpochMs = AtomicLong(0)
    @Volatile
    private var lastError: String = ""

    fun recordProcessed(rows: Long) {
        cycles.incrementAndGet()
        processedRows.addAndGet(rows)
        lastProcessedRows.set(rows)
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
            lastProcessedRows = lastProcessedRows.get(),
            failed = failed.get(),
            lastProcessedAt = instantString(lastProcessedAtEpochMs.get()),
            lastFailedAt = instantString(lastFailedAtEpochMs.get()),
            lastError = lastError
        )
    }

    fun resetForTests() {
        cycles.set(0)
        processedRows.set(0)
        lastProcessedRows.set(0)
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

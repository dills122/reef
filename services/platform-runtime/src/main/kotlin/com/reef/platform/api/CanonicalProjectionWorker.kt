package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class CanonicalProjectionWorker(
    private val api: PlatformApi,
    private val projectionName: String = RuntimeEnv.string("STREAM_ACK_PROJECTION_NAME", "runtime-normalized-submit"),
    private val batchSize: Int = RuntimeEnv.int("STREAM_ACK_PROJECTOR_BATCH_SIZE", 250, min = 1),
    private val pollIntervalMs: Long = RuntimeEnv.long("STREAM_ACK_PROJECTOR_POLL_MS", 50L, min = 1L),
    private val workerName: String = "reef-canonical-projection-worker"
) {
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = workerName, isDaemon = true) {
            while (running.get()) {
                val projected = processOnce()
                if (projected == 0L) {
                    CanonicalProjectionMetrics.recordEmptyPoll()
                    Thread.sleep(pollIntervalMs)
                }
            }
        }
    }

    fun stop() {
        running.set(false)
    }

    fun processOnce(): Long {
        return try {
            HotPathMetrics.time("projector.projectCanonicalSubmitOutcomes") {
                api.projectCanonicalSubmitOutcomes(projectionName, batchSize)
            }.also { projected ->
                if (projected > 0) {
                    CanonicalProjectionMetrics.recordProjected(projected)
                }
            }
        } catch (ex: Exception) {
            CanonicalProjectionMetrics.recordFailed(ex.message ?: ex::class.simpleName ?: "unknown")
            0
        }
    }
}

data class CanonicalProjectionStats(
    val projected: Long,
    val failed: Long,
    val emptyPolls: Long,
    val lastProjectedAt: String,
    val lastFailedAt: String,
    val lastError: String
)

object CanonicalProjectionMetrics {
    private val projected = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val emptyPolls = AtomicLong(0)
    private val lastProjectedAtEpochMs = AtomicLong(0)
    private val lastFailedAtEpochMs = AtomicLong(0)
    @Volatile
    private var lastError: String = ""

    fun recordProjected(count: Long) {
        projected.addAndGet(count)
        lastProjectedAtEpochMs.set(System.currentTimeMillis())
    }

    fun recordFailed(error: String) {
        failed.incrementAndGet()
        lastFailedAtEpochMs.set(System.currentTimeMillis())
        lastError = error
    }

    fun recordEmptyPoll() {
        emptyPolls.incrementAndGet()
    }

    fun snapshot(): CanonicalProjectionStats {
        return CanonicalProjectionStats(
            projected = projected.get(),
            failed = failed.get(),
            emptyPolls = emptyPolls.get(),
            lastProjectedAt = instantString(lastProjectedAtEpochMs.get()),
            lastFailedAt = instantString(lastFailedAtEpochMs.get()),
            lastError = lastError
        )
    }

    fun resetForTests() {
        projected.set(0)
        failed.set(0)
        emptyPolls.set(0)
        lastProjectedAtEpochMs.set(0)
        lastFailedAtEpochMs.set(0)
        lastError = ""
    }

    private fun instantString(epochMs: Long): String {
        if (epochMs <= 0) return ""
        return Instant.ofEpochMilli(epochMs).toString()
    }
}

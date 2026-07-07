package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import com.reef.platform.infrastructure.persistence.VenueCommandOutcomeFact
import com.reef.platform.infrastructure.persistence.VenueEventBatchFact
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

interface VenueEventBatchDelivery {
    val subject: String
    val payloadJson: String
    val streamSequence: Long
    val deliveredCount: Long
    fun ack()
    fun nak()
    fun term()
}

interface VenueEventBatchSource {
    fun fetch(batchSize: Int, timeout: Duration): List<VenueEventBatchDelivery>
}

class VenueEventBatchMaterializer(
    private val source: VenueEventBatchSource,
    private val api: PlatformApi,
    private val batchSize: Int = RuntimeEnv.int("VENUE_EVENT_MATERIALIZER_BATCH_SIZE", 100, min = 1),
    private val pollIntervalMs: Long = RuntimeEnv.long("VENUE_EVENT_MATERIALIZER_POLL_MS", 25L, min = 1L),
    private val fetchTimeout: Duration = Duration.ofMillis(RuntimeEnv.long("VENUE_EVENT_MATERIALIZER_FETCH_TIMEOUT_MS", 200L, min = 1L)),
    private val workerName: String = "reef-venue-event-batch-materializer"
) {
    private val running = AtomicBoolean(false)
    @Volatile
    private var workerThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        workerThread = thread(name = workerName, isDaemon = true) {
            try {
                while (running.get()) {
                    val processed = processOnce()
                    if (processed == 0) {
                        VenueEventBatchMaterializerMetrics.recordEmptyPoll()
                        Thread.sleep(pollIntervalMs)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                running.set(false)
                workerThread = null
            }
        }
    }

    fun stop() {
        running.set(false)
        workerThread?.interrupt()
    }

    fun awaitStopped(timeout: Duration = Duration.ofSeconds(5)): Boolean {
        val thread = workerThread ?: return true
        thread.join(timeout.toMillis())
        return !thread.isAlive
    }

    fun processOnce(): Int {
        val deliveries = HotPathMetrics.time("venueEventMaterializer.fetch") {
            source.fetch(batchSize, fetchTimeout)
        }
        VenueEventBatchMaterializerMetrics.recordFetched(
            deliveries.size.toLong(),
            deliveries.maxOfOrNull { it.streamSequence } ?: 0L
        )
        deliveries.forEach { delivery -> processDelivery(delivery) }
        return deliveries.size
    }

    private fun processDelivery(delivery: VenueEventBatchDelivery) {
        val eventType = delivery.subject.substringAfterLast('.', missingDelimiterValue = "")
        if (eventType != "VenueEventBatch") {
            safeTerm(delivery)
            VenueEventBatchMaterializerMetrics.recordUnsupported()
            return
        }

        val batch = try {
            parseVenueEventBatch(delivery.payloadJson)
        } catch (ex: Exception) {
            safeTerm(delivery)
            VenueEventBatchMaterializerMetrics.recordFailed(ex.message ?: ex::class.simpleName ?: "unknown")
            return
        }

        val materializedOutcomes = try {
            HotPathMetrics.time("venueEventMaterializer.materializeBatch") {
                api.materializeVenueEventBatch(batch)
            }
        } catch (ex: Exception) {
            safeNak(delivery)
            VenueEventBatchMaterializerMetrics.recordFailed(ex.message ?: ex::class.simpleName ?: "unknown")
            return
        }
        VenueEventBatchMaterializerMetrics.recordMaterialized(batch, delivery.streamSequence, materializedOutcomes)

        try {
            delivery.ack()
        } catch (ex: Exception) {
            VenueEventBatchMaterializerMetrics.recordAckFailed(ex.message ?: ex::class.simpleName ?: "unknown")
        }
    }

    private fun parseVenueEventBatch(payloadJson: String): VenueEventBatchFact {
        val root = JsonCodec.parseObject(payloadJson)
        val outcomes = root.objectDocuments("outcomes").map { outcome ->
            val result = outcome.raw("result").ifBlank { "{}" }
            VenueCommandOutcomeFact(
                commandId = outcome.string("commandId"),
                commandType = outcome.string("commandType"),
                streamSequence = outcome.long("streamSequence"),
                deliveredCount = outcome.long("deliveredCount"),
                payloadHash = outcome.string("payloadHash"),
                instrumentId = outcome.string("instrumentId"),
                orderId = outcome.string("orderId"),
                resultStatus = outcome.string("status"),
                rejectCode = outcome.string("rejectCode").ifBlank {
                    outcome.obj("result").obj("rejected").string("code")
                },
                resultPayloadJson = result
            )
        }
        return VenueEventBatchFact(
            batchId = root.string("batchId"),
            shardId = root.string("shardId"),
            partition = root.int("partition"),
            commandStream = root.string("commandStream"),
            eventStream = root.string("eventStream"),
            firstSequence = root.long("firstSequence"),
            lastSequence = root.long("lastSequence"),
            commandCount = root.int("commandCount"),
            createdAt = root.string("createdAt"),
            payloadChecksum = root.string("payloadChecksum"),
            payloadFormat = root.string("payloadFormat").ifBlank { "venue-event-batch-json" },
            payloadVersion = root.string("payloadVersion").ifBlank { "v1" },
            outcomes = outcomes
        )
    }

    private fun JsonDocument.int(key: String): Int {
        return string(key).toIntOrNull() ?: 0
    }

    private fun JsonDocument.long(key: String): Long {
        return string(key).toLongOrNull() ?: 0L
    }

    private fun safeNak(delivery: VenueEventBatchDelivery) {
        try {
            delivery.nak()
        } catch (_: Exception) {
        }
    }

    private fun safeTerm(delivery: VenueEventBatchDelivery) {
        try {
            delivery.term()
        } catch (_: Exception) {
        }
    }
}

data class VenueEventBatchMaterializerStats(
    val fetched: Long,
    val materialized: Long,
    val materializedOutcomes: Long,
    val failed: Long,
    val ackFailed: Long,
    val unsupported: Long,
    val emptyPolls: Long,
    val lastFetchedStreamSequence: Long,
    val lastMaterializedStreamSequence: Long,
    val lastMaterializedBatchId: String,
    val lastMaterializedPartition: Int,
    val lastMaterializedFirstSequence: Long,
    val lastMaterializedLastSequence: Long,
    val materializerLag: Long,
    val lastMaterializedAt: String,
    val lastFailedAt: String,
    val lastError: String
)

object VenueEventBatchMaterializerMetrics {
    private val fetched = AtomicLong(0)
    private val materialized = AtomicLong(0)
    private val materializedOutcomes = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val ackFailed = AtomicLong(0)
    private val unsupported = AtomicLong(0)
    private val emptyPolls = AtomicLong(0)
    private val lastFetchedStreamSequence = AtomicLong(0)
    private val lastMaterializedStreamSequence = AtomicLong(0)
    private val lastMaterializedPartition = AtomicLong(-1)
    private val lastMaterializedFirstSequence = AtomicLong(0)
    private val lastMaterializedLastSequence = AtomicLong(0)
    private val lastMaterializedAtEpochMs = AtomicLong(0)
    private val lastFailedAtEpochMs = AtomicLong(0)
    @Volatile
    private var lastMaterializedBatchId: String = ""
    @Volatile
    private var lastError: String = ""

    fun recordFetched(count: Long, maxStreamSequence: Long) {
        fetched.addAndGet(count)
        if (maxStreamSequence > 0) {
            lastFetchedStreamSequence.set(maxStreamSequence)
        }
    }

    fun recordMaterialized(batch: VenueEventBatchFact, streamSequence: Long, outcomeCount: Long) {
        materialized.incrementAndGet()
        materializedOutcomes.addAndGet(outcomeCount)
        lastMaterializedBatchId = batch.batchId
        lastMaterializedPartition.set(batch.partition.toLong())
        lastMaterializedFirstSequence.set(batch.firstSequence)
        lastMaterializedLastSequence.set(batch.lastSequence)
        lastMaterializedStreamSequence.set(streamSequence)
        lastMaterializedAtEpochMs.set(System.currentTimeMillis())
    }

    fun recordFailed(error: String) {
        failed.incrementAndGet()
        lastFailedAtEpochMs.set(System.currentTimeMillis())
        lastError = error
    }

    fun recordAckFailed(error: String) {
        ackFailed.incrementAndGet()
        lastError = error
    }

    fun recordUnsupported() {
        unsupported.incrementAndGet()
    }

    fun recordEmptyPoll() {
        emptyPolls.incrementAndGet()
    }

    fun snapshot(): VenueEventBatchMaterializerStats {
        return VenueEventBatchMaterializerStats(
            fetched = fetched.get(),
            materialized = materialized.get(),
            materializedOutcomes = materializedOutcomes.get(),
            failed = failed.get(),
            ackFailed = ackFailed.get(),
            unsupported = unsupported.get(),
            emptyPolls = emptyPolls.get(),
            lastFetchedStreamSequence = lastFetchedStreamSequence.get(),
            lastMaterializedStreamSequence = lastMaterializedStreamSequence.get(),
            lastMaterializedBatchId = lastMaterializedBatchId,
            lastMaterializedPartition = lastMaterializedPartition.get().toInt(),
            lastMaterializedFirstSequence = lastMaterializedFirstSequence.get(),
            lastMaterializedLastSequence = lastMaterializedLastSequence.get(),
            materializerLag = sequenceLag(lastFetchedStreamSequence.get(), lastMaterializedStreamSequence.get()),
            lastMaterializedAt = instantString(lastMaterializedAtEpochMs.get()),
            lastFailedAt = instantString(lastFailedAtEpochMs.get()),
            lastError = lastError
        )
    }

    fun resetForTests() {
        fetched.set(0)
        materialized.set(0)
        materializedOutcomes.set(0)
        failed.set(0)
        ackFailed.set(0)
        unsupported.set(0)
        emptyPolls.set(0)
        lastFetchedStreamSequence.set(0)
        lastMaterializedStreamSequence.set(0)
        lastMaterializedPartition.set(-1)
        lastMaterializedFirstSequence.set(0)
        lastMaterializedLastSequence.set(0)
        lastMaterializedAtEpochMs.set(0)
        lastFailedAtEpochMs.set(0)
        lastMaterializedBatchId = ""
        lastError = ""
    }

    private fun sequenceLag(lastFetched: Long, lastMaterialized: Long): Long {
        return if (lastFetched <= lastMaterialized) 0L else lastFetched - lastMaterialized
    }

    private fun instantString(epochMs: Long): String {
        if (epochMs <= 0) return ""
        return Instant.ofEpochMilli(epochMs).toString()
    }
}

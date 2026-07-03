package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import io.nats.client.JetStreamSubscription
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

interface StreamCommandDelivery {
    val subject: String
    val payloadJson: String
    val streamSequence: Long
    val deliveredCount: Long
    fun ack()
    fun nak()
    fun term()
}

interface StreamCommandSource {
    fun fetch(batchSize: Int, timeout: Duration): List<StreamCommandDelivery>
}

interface StreamCommandTelemetrySource {
    fun consumerSnapshot(): StreamCommandConsumerSnapshot
}

class StreamCommandWorker(
    private val source: StreamCommandSource,
    private val api: PlatformApi,
    private val partition: Int = -1,
    private val batchSize: Int = RuntimeEnv.int("STREAM_ACK_WORKER_BATCH_SIZE", 100, min = 1),
    private val pollIntervalMs: Long = RuntimeEnv.long("STREAM_ACK_WORKER_POLL_MS", 25L, min = 1L),
    private val fetchTimeout: Duration = Duration.ofMillis(RuntimeEnv.long("STREAM_ACK_WORKER_FETCH_TIMEOUT_MS", 200L, min = 1L)),
    private val workerName: String = "reef-stream-command-worker"
) {
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = workerName, isDaemon = true) {
            while (running.get()) {
                val processed = processOnce()
                if (processed == 0) {
                    StreamCommandWorkerMetrics.recordEmptyPoll()
                    Thread.sleep(pollIntervalMs)
                }
            }
        }
    }

    fun stop() {
        running.set(false)
    }

    fun processOnce(): Int {
        val deliveries = HotPathMetrics.time("streamWorker.fetch") {
            source.fetch(batchSize, fetchTimeout)
        }
        StreamCommandWorkerMetrics.recordFetched(partition, deliveries)
        deliveries.forEach { delivery -> process(delivery) }
        return deliveries.size
    }

    private fun process(delivery: StreamCommandDelivery) {
        if (commandType(delivery.subject) != "SubmitOrder") {
            delivery.term()
            StreamCommandWorkerMetrics.recordUnsupported(partition)
            return
        }

        try {
            val outcome = HotPathMetrics.time("streamWorker.prepareSubmitOrder") {
                api.prepareSubmitOrder(delivery.payloadJson)
            }
            HotPathMetrics.time("streamWorker.persistSubmitOutcome") {
                api.persistSubmitOutcomes(listOf(outcome))
            }
        } catch (ex: Exception) {
            safeNak(delivery)
            StreamCommandWorkerMetrics.recordFailed(partition, ex.message ?: ex::class.simpleName ?: "unknown")
            return
        }

        try {
            delivery.ack()
            StreamCommandWorkerMetrics.recordCompleted(partition, delivery.streamSequence)
        } catch (ex: Exception) {
            StreamCommandWorkerMetrics.recordAckFailed(partition, ex.message ?: ex::class.simpleName ?: "unknown")
        }
    }

    private fun safeNak(delivery: StreamCommandDelivery) {
        try {
            delivery.nak()
        } catch (_: Exception) {
        }
    }

    private fun commandType(subject: String): String {
        return subject.substringAfterLast('.', missingDelimiterValue = "")
    }
}

class NatsStreamCommandSource(
    private val natsUrl: String = RuntimeEnv.string("STREAM_ACK_NATS_URL", "nats://localhost:4222"),
    private val config: StreamCommandConfig = StreamCommandConfig(),
    private val partition: Int,
    private val filterSubject: String,
    private val durableName: String,
    private val ackWait: Duration = Duration.ofMillis(RuntimeEnv.long("STREAM_ACK_WORKER_ACK_WAIT_MS", 30_000L, min = 1_000L))
) : StreamCommandSource, StreamCommandTelemetrySource {
    private val connection by lazy {
        val options = Options.Builder()
            .server(natsUrl)
            .connectionTimeout(Duration.ofMillis(RuntimeEnv.long("STREAM_ACK_NATS_CONNECT_TIMEOUT_MS", 2_000L, min = 1L)))
            .build()
        Nats.connect(options)
    }
    private val jetStream by lazy { connection.jetStream() }
    private val jetStreamManagement by lazy { connection.jetStreamManagement() }
    @Volatile
    private var subscription: JetStreamSubscription? = null

    override fun fetch(batchSize: Int, timeout: Duration): List<StreamCommandDelivery> {
        val sub = subscription ?: createSubscriptionOrNull() ?: return emptyList()
        return try {
            sub.fetch(batchSize, timeout).map { message -> NatsStreamCommandDelivery(message) }
        } catch (_: Exception) {
            subscription = null
            emptyList()
        }
    }

    private fun createSubscriptionOrNull(): JetStreamSubscription? {
        return try {
            val consumerConfig = ConsumerConfiguration.builder()
                .durable(durableName)
                .name(durableName)
                .filterSubject(filterSubject)
                .ackPolicy(AckPolicy.Explicit)
                .ackWait(ackWait)
                .build()
            val options = PullSubscribeOptions.builder()
                .stream(config.streamName)
                .configuration(consumerConfig)
                .build()
            jetStream.subscribe(filterSubject, options).also { subscription = it }
        } catch (_: Exception) {
            null
        }
    }

    override fun consumerSnapshot(): StreamCommandConsumerSnapshot {
        return try {
            val streamInfo = jetStreamManagement.getStreamInfo(config.streamName)
            val streamLastSequence = streamInfo.streamState.lastSequence
            val info = jetStreamManagement.getConsumerInfo(config.streamName, durableName)
            val delivered = info.delivered
            val ackFloor = info.ackFloor
            val pending = info.numPending
            val ackPending = info.numAckPending
            StreamCommandConsumerSnapshot(
                partition = partition,
                durableName = durableName,
                filterSubject = filterSubject,
                pending = pending,
                waiting = info.numWaiting,
                ackPending = ackPending,
                redelivered = info.redelivered,
                deliveredConsumerSequence = delivered.consumerSequence,
                deliveredStreamSequence = delivered.streamSequence,
                ackFloorConsumerSequence = ackFloor.consumerSequence,
                ackFloorStreamSequence = ackFloor.streamSequence,
                streamLastSequence = streamLastSequence,
                streamLag = pending + ackPending,
                lastActiveAt = delivered.lastActive?.toInstant()?.toString() ?: "",
                sampledAt = Instant.now().toString(),
                error = ""
            )
        } catch (ex: Exception) {
            StreamCommandConsumerSnapshot(
                partition = partition,
                durableName = durableName,
                filterSubject = filterSubject,
                error = ex.message ?: ex::class.simpleName ?: "unknown"
            )
        }
    }
}

private class NatsStreamCommandDelivery(
    private val message: io.nats.client.Message
) : StreamCommandDelivery {
    override val subject: String = message.subject
    override val payloadJson: String = String(message.data, Charsets.UTF_8)
    override val streamSequence: Long = message.metaData().streamSequence()
    override val deliveredCount: Long = message.metaData().deliveredCount()

    override fun ack() {
        message.ack()
    }

    override fun nak() {
        message.nak()
    }

    override fun term() {
        message.term()
    }
}

object StreamCommandWorkerFactory {
    fun sourceForPartition(config: StreamCommandConfig, partition: Int): StreamCommandSource {
        val width = maxOf(2, (config.partitionCount - 1).toString().length)
        val partitionToken = "p${partition.toString().padStart(width, '0')}"
        val filterSubject = "${config.subjectPrefix.trim('.')}.$partitionToken.>"
        val durableName = RuntimeEnv.string(
            "STREAM_ACK_WORKER_DURABLE_PREFIX",
            "reef-stream-worker"
        ) + "-$partitionToken"
        return NatsStreamCommandSource(
            config = config,
            partition = partition,
            filterSubject = filterSubject,
            durableName = durableName
        )
    }
}

data class StreamCommandConsumerSnapshot(
    val partition: Int,
    val durableName: String,
    val filterSubject: String,
    val pending: Long = 0,
    val waiting: Long = 0,
    val ackPending: Long = 0,
    val redelivered: Long = 0,
    val deliveredConsumerSequence: Long = 0,
    val deliveredStreamSequence: Long = 0,
    val ackFloorConsumerSequence: Long = 0,
    val ackFloorStreamSequence: Long = 0,
    val streamLastSequence: Long = 0,
    val streamLag: Long = 0,
    val lastActiveAt: String = "",
    val sampledAt: String = "",
    val error: String = ""
)

data class StreamCommandWorkerPartitionStats(
    val partition: Int,
    val fetched: Long,
    val completed: Long,
    val failed: Long,
    val ackFailed: Long,
    val unsupported: Long,
    val localInFlight: Long,
    val maxDeliveredCount: Long,
    val lastFetchedStreamSequence: Long,
    val lastCompletedStreamSequence: Long,
    val lastFetchedAt: String,
    val lastCompletedAt: String,
    val lastFailedAt: String,
    val lastAckFailedAt: String,
    val oldestLocalInFlightAt: String,
    val oldestLocalInFlightAgeMs: Long,
    val lastError: String
)

data class StreamCommandWorkerStats(
    val fetched: Long,
    val completed: Long,
    val failed: Long,
    val ackFailed: Long,
    val unsupported: Long,
    val emptyPolls: Long,
    val lastFetchedAt: String,
    val lastCompletedAt: String,
    val lastFailedAt: String,
    val lastAckFailedAt: String,
    val lastError: String,
    val partitions: List<StreamCommandWorkerPartitionStats>,
    val consumers: List<StreamCommandConsumerSnapshot>
)

object StreamCommandWorkerMetrics {
    private data class PartitionMetrics(
        val fetched: AtomicLong = AtomicLong(0),
        val completed: AtomicLong = AtomicLong(0),
        val failed: AtomicLong = AtomicLong(0),
        val ackFailed: AtomicLong = AtomicLong(0),
        val unsupported: AtomicLong = AtomicLong(0),
        val localInFlight: AtomicLong = AtomicLong(0),
        val maxDeliveredCount: AtomicLong = AtomicLong(0),
        val lastFetchedStreamSequence: AtomicLong = AtomicLong(0),
        val lastCompletedStreamSequence: AtomicLong = AtomicLong(0),
        val lastFetchedAtEpochMs: AtomicLong = AtomicLong(0),
        val lastCompletedAtEpochMs: AtomicLong = AtomicLong(0),
        val lastFailedAtEpochMs: AtomicLong = AtomicLong(0),
        val lastAckFailedAtEpochMs: AtomicLong = AtomicLong(0),
        val oldestLocalInFlightAtEpochMs: AtomicLong = AtomicLong(0)
    ) {
        @Volatile
        var lastError: String = ""
    }

    private val fetched = AtomicLong(0)
    private val completed = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val ackFailed = AtomicLong(0)
    private val unsupported = AtomicLong(0)
    private val emptyPolls = AtomicLong(0)
    private val lastFetchedAtEpochMs = AtomicLong(0)
    private val lastCompletedAtEpochMs = AtomicLong(0)
    private val lastFailedAtEpochMs = AtomicLong(0)
    private val lastAckFailedAtEpochMs = AtomicLong(0)
    @Volatile
    private var lastError: String = ""
    private val partitions = ConcurrentHashMap<Int, PartitionMetrics>()
    private val consumerTelemetrySources = ConcurrentHashMap<Int, StreamCommandTelemetrySource>()

    fun registerConsumerTelemetry(partition: Int, source: StreamCommandTelemetrySource) {
        consumerTelemetrySources[partition] = source
    }

    fun recordFetched(partition: Int, deliveries: List<StreamCommandDelivery>) {
        if (deliveries.isEmpty()) return
        val count = deliveries.size
        lastFetchedAtEpochMs.set(System.currentTimeMillis())
        fetched.addAndGet(count.toLong())
        partitionMetrics(partition).also { metrics ->
            val now = System.currentTimeMillis()
            metrics.fetched.addAndGet(count.toLong())
            metrics.localInFlight.addAndGet(count.toLong())
            setIfZero(metrics.oldestLocalInFlightAtEpochMs, now)
            metrics.lastFetchedAtEpochMs.set(now)
            deliveries.forEach { delivery ->
                metrics.lastFetchedStreamSequence.updateAndGet { current -> maxOf(current, delivery.streamSequence) }
                metrics.maxDeliveredCount.updateAndGet { current -> maxOf(current, delivery.deliveredCount) }
            }
        }
    }

    fun recordCompleted(partition: Int, streamSequence: Long) {
        completed.incrementAndGet()
        lastCompletedAtEpochMs.set(System.currentTimeMillis())
        partitionMetrics(partition).also { metrics ->
            metrics.completed.incrementAndGet()
            metrics.localInFlight.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
            if (metrics.localInFlight.get() == 0L) {
                metrics.oldestLocalInFlightAtEpochMs.set(0)
            }
            metrics.lastCompletedStreamSequence.updateAndGet { current -> maxOf(current, streamSequence) }
            metrics.lastCompletedAtEpochMs.set(System.currentTimeMillis())
        }
    }

    fun recordFailed(partition: Int, error: String) {
        failed.incrementAndGet()
        lastFailedAtEpochMs.set(System.currentTimeMillis())
        lastError = error
        partitionMetrics(partition).also { metrics ->
            metrics.failed.incrementAndGet()
            metrics.localInFlight.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
            if (metrics.localInFlight.get() == 0L) {
                metrics.oldestLocalInFlightAtEpochMs.set(0)
            }
            metrics.lastFailedAtEpochMs.set(System.currentTimeMillis())
            metrics.lastError = error
        }
    }

    fun recordAckFailed(partition: Int, error: String) {
        ackFailed.incrementAndGet()
        lastAckFailedAtEpochMs.set(System.currentTimeMillis())
        lastError = error
        partitionMetrics(partition).also { metrics ->
            metrics.ackFailed.incrementAndGet()
            metrics.localInFlight.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
            if (metrics.localInFlight.get() == 0L) {
                metrics.oldestLocalInFlightAtEpochMs.set(0)
            }
            metrics.lastAckFailedAtEpochMs.set(System.currentTimeMillis())
            metrics.lastError = error
        }
    }

    fun recordUnsupported(partition: Int) {
        unsupported.incrementAndGet()
        lastFailedAtEpochMs.set(System.currentTimeMillis())
        lastError = "unsupported stream command type"
        partitionMetrics(partition).also { metrics ->
            metrics.unsupported.incrementAndGet()
            metrics.localInFlight.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
            if (metrics.localInFlight.get() == 0L) {
                metrics.oldestLocalInFlightAtEpochMs.set(0)
            }
            metrics.lastFailedAtEpochMs.set(System.currentTimeMillis())
            metrics.lastError = "unsupported stream command type"
        }
    }

    fun recordEmptyPoll() {
        emptyPolls.incrementAndGet()
    }

    fun snapshot(): StreamCommandWorkerStats {
        return StreamCommandWorkerStats(
            fetched = fetched.get(),
            completed = completed.get(),
            failed = failed.get(),
            ackFailed = ackFailed.get(),
            unsupported = unsupported.get(),
            emptyPolls = emptyPolls.get(),
            lastFetchedAt = instantString(lastFetchedAtEpochMs.get()),
            lastCompletedAt = instantString(lastCompletedAtEpochMs.get()),
            lastFailedAt = instantString(lastFailedAtEpochMs.get()),
            lastAckFailedAt = instantString(lastAckFailedAtEpochMs.get()),
            lastError = lastError,
            partitions = partitions.entries
                .map { (partition, metrics) -> partitionSnapshot(partition, metrics) }
                .sortedBy { it.partition },
            consumers = consumerTelemetrySources.entries
                .map { (partition, source) ->
                    try {
                        source.consumerSnapshot()
                    } catch (ex: Exception) {
                        StreamCommandConsumerSnapshot(
                            partition = partition,
                            durableName = "",
                            filterSubject = "",
                            error = ex.message ?: ex::class.simpleName ?: "unknown"
                        )
                    }
                }
                .sortedBy { it.partition }
        )
    }

    fun resetForTests() {
        fetched.set(0)
        completed.set(0)
        failed.set(0)
        ackFailed.set(0)
        unsupported.set(0)
        emptyPolls.set(0)
        lastFetchedAtEpochMs.set(0)
        lastCompletedAtEpochMs.set(0)
        lastFailedAtEpochMs.set(0)
        lastAckFailedAtEpochMs.set(0)
        lastError = ""
        partitions.clear()
        consumerTelemetrySources.clear()
    }

    private fun partitionMetrics(partition: Int): PartitionMetrics {
        return partitions.computeIfAbsent(partition) { PartitionMetrics() }
    }

    private fun partitionSnapshot(partition: Int, metrics: PartitionMetrics): StreamCommandWorkerPartitionStats {
        val oldest = metrics.oldestLocalInFlightAtEpochMs.get()
        val now = System.currentTimeMillis()
        return StreamCommandWorkerPartitionStats(
            partition = partition,
            fetched = metrics.fetched.get(),
            completed = metrics.completed.get(),
            failed = metrics.failed.get(),
            ackFailed = metrics.ackFailed.get(),
            unsupported = metrics.unsupported.get(),
            localInFlight = metrics.localInFlight.get(),
            maxDeliveredCount = metrics.maxDeliveredCount.get(),
            lastFetchedStreamSequence = metrics.lastFetchedStreamSequence.get(),
            lastCompletedStreamSequence = metrics.lastCompletedStreamSequence.get(),
            lastFetchedAt = instantString(metrics.lastFetchedAtEpochMs.get()),
            lastCompletedAt = instantString(metrics.lastCompletedAtEpochMs.get()),
            lastFailedAt = instantString(metrics.lastFailedAtEpochMs.get()),
            lastAckFailedAt = instantString(metrics.lastAckFailedAtEpochMs.get()),
            oldestLocalInFlightAt = instantString(oldest),
            oldestLocalInFlightAgeMs = if (oldest > 0) (now - oldest).coerceAtLeast(0) else 0,
            lastError = metrics.lastError
        )
    }

    private fun setIfZero(value: AtomicLong, update: Long) {
        while (value.get() == 0L && !value.compareAndSet(0, update)) {
            // retry while another thread races to set the first in-flight timestamp
        }
    }

    private fun instantString(epochMs: Long): String {
        if (epochMs <= 0) return ""
        return Instant.ofEpochMilli(epochMs).toString()
    }
}

package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.diagnostics.HotPathMetrics
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.impl.Headers
import io.nats.client.impl.NatsMessage
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

data class StreamPublishAck(val streamName: String, val streamSequence: Long)

interface StreamCommandPublisher {
    fun publish(envelope: StreamCommandEnvelope): StreamPublishAck
}

interface AsyncStreamCommandPublisher {
    fun publishAsync(envelope: StreamCommandEnvelope): CompletableFuture<StreamPublishAck>
}

interface BatchAsyncStreamCommandPublisher {
    fun publishAsyncBatch(envelopes: List<StreamCommandEnvelope>): List<CompletableFuture<StreamPublishAck>>
}

class StreamCommandPublishBackpressureException(message: String) : RuntimeException(message)

class StreamCommandPublishTimeoutException(message: String) : RuntimeException(message)

data class StreamCommandHealthSnapshot(
    val available: Boolean,
    val streamName: String,
    val messageCount: Long = 0L,
    val byteCount: Long = 0L,
    val maxBytes: Long = 0L,
    val storageUtilization: Double = 0.0,
    val publishMode: String = "sync",
    val publishInFlight: Int = 0,
    val publishMaxInFlight: Int = 0,
    val publishQueueDepth: Int = 0,
    val publishMaxQueueDepth: Int = 0,
    val publishLaneCount: Int = 0,
    val publishAccepted: Long = 0L,
    val publishCompleted: Long = 0L,
    val publishFailed: Long = 0L,
    val publishRejected: Long = 0L,
    val publishQueueWaitLastMs: Long = 0L,
    val publishQueueWaitMaxMs: Long = 0L,
    val publishSlotWaitLastMs: Long = 0L,
    val publishSlotWaitMaxMs: Long = 0L,
    val publishDelegateAckLastMs: Long = 0L,
    val publishDelegateAckMaxMs: Long = 0L,
    val publishPipelineTotalLastMs: Long = 0L,
    val publishPipelineTotalMaxMs: Long = 0L,
    val publishLaneSnapshots: List<StreamCommandPublishLaneSnapshot> = emptyList(),
    val publishAckLastMs: Long = 0L,
    val publishAckMaxMs: Long = 0L,
    val checkedAt: Instant = Instant.now(),
    val error: String = ""
)

data class StreamCommandPublishLaneSnapshot(
    val partition: Int,
    val accepted: Long,
    val completed: Long,
    val failed: Long,
    val rejected: Long,
    val queueDepth: Int,
    val maxQueueDepthObserved: Int,
    val inFlight: Int,
    val maxInFlightObserved: Int,
    val queueWaitLastMs: Long,
    val queueWaitMaxMs: Long,
    val slotWaitLastMs: Long,
    val slotWaitMaxMs: Long,
    val delegateAckLastMs: Long,
    val delegateAckMaxMs: Long,
    val totalLastMs: Long,
    val totalMaxMs: Long
)

interface StreamCommandHealthCheck {
    fun snapshot(): StreamCommandHealthSnapshot
}

class NoopStreamCommandPublisher(
    private val config: StreamCommandConfig = StreamCommandConfig()
) : StreamCommandPublisher, AsyncStreamCommandPublisher, StreamCommandHealthCheck {
    private val sequence = AtomicLong(0L)

    override fun publish(envelope: StreamCommandEnvelope): StreamPublishAck {
        return nextAck()
    }

    override fun publishAsync(envelope: StreamCommandEnvelope): CompletableFuture<StreamPublishAck> {
        return CompletableFuture.completedFuture(nextAck())
    }

    override fun snapshot(): StreamCommandHealthSnapshot {
        return StreamCommandHealthSnapshot(
            available = true,
            streamName = config.streamName,
            messageCount = sequence.get(),
            publishMode = "noop"
        )
    }

    private fun nextAck(): StreamPublishAck {
        return StreamPublishAck(config.streamName, sequence.incrementAndGet())
    }
}

class NatsJetStreamCommandPublisher(
    private val natsUrl: String = RuntimeEnv.string("STREAM_ACK_NATS_URL", "nats://localhost:4222"),
    private val ackTimeout: Duration = Duration.ofMillis(RuntimeEnv.long("STREAM_ACK_PUBLISH_ACK_TIMEOUT_MS", 2_000L, min = 1L)),
    private val config: StreamCommandConfig = StreamCommandConfig(),
    private val publishMode: String = RuntimeEnv.string("STREAM_ACK_PUBLISH_MODE", "sync").trim().lowercase(),
    private val maxInFlight: Int = RuntimeEnv.int("STREAM_ACK_PUBLISH_MAX_IN_FLIGHT", 4_096, min = 1)
) : StreamCommandPublisher, AsyncStreamCommandPublisher, StreamCommandHealthCheck {
    private val lastPublishAckMs = AtomicLong(0L)
    private val maxPublishAckMs = AtomicLong(0L)
    private val inFlight = Semaphore(maxInFlight)
    private val connection by lazy {
        val options = Options.Builder()
            .server(natsUrl)
            .connectionTimeout(ackTimeout)
            .build()
        Nats.connect(options)
    }
    private val jetStream by lazy { connection.jetStream() }

    override fun publish(envelope: StreamCommandEnvelope): StreamPublishAck {
        val headers = Headers().put("Nats-Msg-Id", envelope.natsMessageId)
        val message = NatsMessage.builder()
            .subject(envelope.subject)
            .headers(headers)
            .data(envelope.payloadJson.toByteArray(Charsets.UTF_8))
            .build()
        val started = System.nanoTime()
        val ack = publishAndWait(message)
        recordPublishAckElapsed(started)
        return StreamPublishAck(
            streamName = ack.stream ?: config.streamName,
            streamSequence = ack.seqno
        )
    }

    override fun publishAsync(envelope: StreamCommandEnvelope): CompletableFuture<StreamPublishAck> {
        val headers = Headers().put("Nats-Msg-Id", envelope.natsMessageId)
        val message = NatsMessage.builder()
            .subject(envelope.subject)
            .headers(headers)
            .data(envelope.payloadJson.toByteArray(Charsets.UTF_8))
            .build()
        if (!inFlight.tryAcquire()) {
            return CompletableFuture.failedFuture(StreamCommandPublishBackpressureException("JetStream publish in-flight window is full"))
        }
        val started = System.nanoTime()
        val result = CompletableFuture<StreamPublishAck>()
        try {
            jetStream.publishAsync(message)
                .orTimeout(ackTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete { ack, failure ->
                    inFlight.release()
                    recordPublishAckElapsed(started)
                    if (failure != null) {
                        result.completeExceptionally(failure)
                    } else {
                        result.complete(StreamPublishAck(ack.stream ?: config.streamName, ack.seqno))
                    }
                }
        } catch (ex: Exception) {
            inFlight.release()
            recordPublishAckElapsed(started)
            result.completeExceptionally(ex)
        }
        return result
    }

    private fun publishAndWait(message: NatsMessage): io.nats.client.api.PublishAck {
        return when (publishMode) {
            "async", "pipeline", "pipelined" -> publishAsyncAndWait(message)
            "sync", "synchronous" -> jetStream.publish(message)
            else -> throw IllegalArgumentException("Unsupported STREAM_ACK_PUBLISH_MODE '$publishMode'; expected sync or async")
        }
    }

    private fun publishAsyncAndWait(message: NatsMessage): io.nats.client.api.PublishAck {
        if (!inFlight.tryAcquire(ackTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("timed out waiting for JetStream publish in-flight slot")
        }
        return try {
            jetStream.publishAsync(message).get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } finally {
            inFlight.release()
        }
    }

    private fun recordPublishAckElapsed(started: Long) {
        val elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis()
        lastPublishAckMs.set(elapsedMs)
        maxPublishAckMs.accumulateAndGet(elapsedMs, ::maxOf)
    }

    override fun snapshot(): StreamCommandHealthSnapshot {
        return try {
            val info = connection.jetStreamManagement().getStreamInfo(config.streamName)
            val state = info.streamState
            val maxBytes = info.configuration.maxBytes
            val byteCount = state.byteCount
            StreamCommandHealthSnapshot(
                available = true,
                streamName = config.streamName,
                messageCount = state.msgCount,
                byteCount = byteCount,
                maxBytes = maxBytes,
                storageUtilization = storageUtilization(byteCount, maxBytes),
                publishMode = publishMode,
                publishInFlight = maxInFlight - inFlight.availablePermits(),
                publishMaxInFlight = maxInFlight,
                publishAckLastMs = lastPublishAckMs.get(),
                publishAckMaxMs = maxPublishAckMs.get()
            )
        } catch (ex: Exception) {
            StreamCommandHealthSnapshot(
                available = false,
                streamName = config.streamName,
                publishMode = publishMode,
                publishInFlight = maxInFlight - inFlight.availablePermits(),
                publishMaxInFlight = maxInFlight,
                publishAckLastMs = lastPublishAckMs.get(),
                publishAckMaxMs = maxPublishAckMs.get(),
                error = ex.message ?: "unknown"
            )
        }
    }

    private fun storageUtilization(byteCount: Long, maxBytes: Long): Double {
        if (maxBytes <= 0L) return 0.0
        return byteCount.toDouble() / maxBytes.toDouble()
    }
}

class PartitionedStreamCommandPublisher(
    private val delegate: StreamCommandPublisher,
    private val config: StreamCommandConfig = StreamCommandConfig(),
    private val queueCapacityPerLane: Int = RuntimeEnv.int("STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY", 4_096, min = 1),
    private val maxInFlightPerLane: Int = RuntimeEnv.int("STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE", 256, min = 1),
    private val batchSizePerLane: Int = RuntimeEnv.int("STREAM_ACK_PUBLISH_PIPELINE_BATCH_SIZE", 1, min = 1),
    private val batchLinger: Duration = Duration.ofMillis(RuntimeEnv.long("STREAM_ACK_PUBLISH_PIPELINE_BATCH_LINGER_MS", 0L, min = 0L)),
    private val ackTimeout: Duration = Duration.ofMillis(RuntimeEnv.long("STREAM_ACK_PUBLISH_ACK_TIMEOUT_MS", 2_000L, min = 1L))
) : StreamCommandPublisher, AsyncStreamCommandPublisher, StreamCommandHealthCheck {
    private val asyncDelegate = delegate as? AsyncStreamCommandPublisher
    private val batchAsyncDelegate = delegate as? BatchAsyncStreamCommandPublisher
    private val healthCheck = delegate as? StreamCommandHealthCheck
    private val lanes = (0 until config.partitionCount).map { partition ->
        PublishLane(partition)
    }

    override fun publish(envelope: StreamCommandEnvelope): StreamPublishAck {
        return publishAsync(envelope).get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun publishAsync(envelope: StreamCommandEnvelope): CompletableFuture<StreamPublishAck> {
        val lane = lanes[envelope.partition.coerceIn(0, lanes.lastIndex)]
        return lane.enqueue(envelope)
    }

    override fun snapshot(): StreamCommandHealthSnapshot {
        val base = healthCheck?.snapshot() ?: StreamCommandHealthSnapshot(
            available = true,
            streamName = config.streamName
        )
        val laneSnapshots = lanes.map { it.snapshot() }
        val pipelineMode = if (batchSizePerLane > 1) "partitioned-batch" else "partitioned"
        return base.copy(
            publishMode = "$pipelineMode-${if (asyncDelegate != null) "async" else "blocking"}-delegate:${base.publishMode}",
            publishInFlight = laneSnapshots.sumOf { it.inFlight },
            publishMaxInFlight = lanes.size * maxInFlightPerLane,
            publishQueueDepth = laneSnapshots.sumOf { it.queueDepth },
            publishMaxQueueDepth = lanes.size * queueCapacityPerLane,
            publishLaneCount = lanes.size,
            publishAccepted = laneSnapshots.sumOf { it.accepted },
            publishCompleted = laneSnapshots.sumOf { it.completed },
            publishFailed = laneSnapshots.sumOf { it.failed },
            publishRejected = laneSnapshots.sumOf { it.rejected },
            publishQueueWaitLastMs = laneSnapshots.maxOfOrNull { it.queueWaitLastMs } ?: 0L,
            publishQueueWaitMaxMs = laneSnapshots.maxOfOrNull { it.queueWaitMaxMs } ?: 0L,
            publishSlotWaitLastMs = laneSnapshots.maxOfOrNull { it.slotWaitLastMs } ?: 0L,
            publishSlotWaitMaxMs = laneSnapshots.maxOfOrNull { it.slotWaitMaxMs } ?: 0L,
            publishDelegateAckLastMs = laneSnapshots.maxOfOrNull { it.delegateAckLastMs } ?: 0L,
            publishDelegateAckMaxMs = laneSnapshots.maxOfOrNull { it.delegateAckMaxMs } ?: 0L,
            publishPipelineTotalLastMs = laneSnapshots.maxOfOrNull { it.totalLastMs } ?: 0L,
            publishPipelineTotalMaxMs = laneSnapshots.maxOfOrNull { it.totalMaxMs } ?: 0L,
            publishLaneSnapshots = laneSnapshots
        )
    }

    private inner class PublishLane(private val partition: Int) {
        private val queue = ArrayBlockingQueue<PublishRequest>(queueCapacityPerLane)
        val queueDepth = AtomicInteger(0)
        val inFlight = AtomicInteger(0)
        private val slots = Semaphore(maxInFlightPerLane)
        private val accepted = AtomicLong(0L)
        private val completed = AtomicLong(0L)
        private val failed = AtomicLong(0L)
        private val rejected = AtomicLong(0L)
        private val maxQueueDepthObserved = AtomicInteger(0)
        private val maxInFlightObserved = AtomicInteger(0)
        private val queueWaitLastMs = AtomicLong(0L)
        private val queueWaitMaxMs = AtomicLong(0L)
        private val slotWaitLastMs = AtomicLong(0L)
        private val slotWaitMaxMs = AtomicLong(0L)
        private val delegateAckLastMs = AtomicLong(0L)
        private val delegateAckMaxMs = AtomicLong(0L)
        private val totalLastMs = AtomicLong(0L)
        private val totalMaxMs = AtomicLong(0L)

        init {
            thread(name = "reef-stream-publish-p$partition", isDaemon = true) {
                run()
            }
        }

        fun enqueue(envelope: StreamCommandEnvelope): CompletableFuture<StreamPublishAck> {
            val request = PublishRequest(envelope)
            if (!queue.offer(request)) {
                rejected.incrementAndGet()
                request.future.completeExceptionally(
                    StreamCommandPublishBackpressureException("stream publish lane $partition queue is full")
                )
                return request.future
            }
            accepted.incrementAndGet()
            val depth = queueDepth.incrementAndGet()
            maxQueueDepthObserved.accumulateAndGet(depth, ::maxOf)
            return request.future
        }

        private fun run() {
            while (true) {
                val batch = nextBatch(queue.take())
                val dequeuedAt = System.nanoTime()
                val acquired = ArrayList<AcquiredPublishRequest>(batch.size)
                for (request in batch) {
                    recordElapsed("api.streamAck.publishPipeline.queueWait", request.enqueuedAtNanos, dequeuedAt, queueWaitLastMs, queueWaitMaxMs)
                    slots.acquire()
                    val acquiredAt = System.nanoTime()
                    recordElapsed("api.streamAck.publishPipeline.slotWait", dequeuedAt, acquiredAt, slotWaitLastMs, slotWaitMaxMs)
                    val currentInFlight = inFlight.incrementAndGet()
                    maxInFlightObserved.accumulateAndGet(currentInFlight, ::maxOf)
                    acquired.add(AcquiredPublishRequest(request, acquiredAt))
                }
                submitBatch(acquired)
            }
        }

        private fun nextBatch(first: PublishRequest): List<PublishRequest> {
            queueDepth.decrementAndGet()
            if (batchSizePerLane <= 1) return listOf(first)

            val batch = ArrayList<PublishRequest>(batchSizePerLane)
            batch.add(first)
            val lingerNanos = batchLinger.toNanos()
            if (lingerNanos > 0L) {
                val deadline = System.nanoTime() + lingerNanos
                while (batch.size < batchSizePerLane) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) break
                    val next = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                    queueDepth.decrementAndGet()
                    batch.add(next)
                }
            }
            if (batch.size < batchSizePerLane) {
                val drained = ArrayList<PublishRequest>(batchSizePerLane - batch.size)
                queue.drainTo(drained, batchSizePerLane - batch.size)
                if (drained.isNotEmpty()) {
                    queueDepth.addAndGet(-drained.size)
                    batch.addAll(drained)
                }
            }
            return batch
        }

        private fun submitBatch(batch: List<AcquiredPublishRequest>) {
            try {
                val futures = if (batchAsyncDelegate != null && batch.size > 1) {
                    batchAsyncDelegate.publishAsyncBatch(batch.map { it.request.envelope })
                } else {
                    batch.map { acquired -> publishSingle(acquired.request) }
                }
                if (futures.size != batch.size) {
                    throw IllegalStateException("batch publisher returned ${futures.size} futures for ${batch.size} envelopes")
                }
                batch.zip(futures).forEach { (acquired, future) ->
                    attachCompletion(acquired, future)
                }
            } catch (ex: Exception) {
                batch.forEach { acquired ->
                    releaseFailed(acquired, ex)
                }
            }
        }

        private fun publishSingle(request: PublishRequest): CompletableFuture<StreamPublishAck> {
            return if (asyncDelegate != null) {
                asyncDelegate.publishAsync(request.envelope)
            } else {
                try {
                    CompletableFuture.completedFuture(delegate.publish(request.envelope))
                } catch (ex: Exception) {
                    CompletableFuture.failedFuture(ex)
                }
            }
        }

        private fun attachCompletion(acquired: AcquiredPublishRequest, future: CompletableFuture<StreamPublishAck>) {
            val request = acquired.request
            future.whenComplete { ack, failure ->
                val completedAt = System.nanoTime()
                recordElapsed("api.streamAck.publishPipeline.delegateAck", acquired.submittedAtNanos, completedAt, delegateAckLastMs, delegateAckMaxMs)
                recordElapsed("api.streamAck.publishPipeline.total", request.enqueuedAtNanos, completedAt, totalLastMs, totalMaxMs)
                inFlight.decrementAndGet()
                slots.release()
                if (failure != null) {
                    failed.incrementAndGet()
                    request.future.completeExceptionally(failure)
                } else {
                    completed.incrementAndGet()
                    request.future.complete(ack)
                }
            }
        }

        private fun releaseFailed(acquired: AcquiredPublishRequest, ex: Exception) {
            inFlight.decrementAndGet()
            slots.release()
            failed.incrementAndGet()
            acquired.request.future.completeExceptionally(ex)
        }

        fun snapshot(): StreamCommandPublishLaneSnapshot {
            return StreamCommandPublishLaneSnapshot(
                partition = partition,
                accepted = accepted.get(),
                completed = completed.get(),
                failed = failed.get(),
                rejected = rejected.get(),
                queueDepth = queueDepth.get(),
                maxQueueDepthObserved = maxQueueDepthObserved.get(),
                inFlight = inFlight.get(),
                maxInFlightObserved = maxInFlightObserved.get(),
                queueWaitLastMs = queueWaitLastMs.get(),
                queueWaitMaxMs = queueWaitMaxMs.get(),
                slotWaitLastMs = slotWaitLastMs.get(),
                slotWaitMaxMs = slotWaitMaxMs.get(),
                delegateAckLastMs = delegateAckLastMs.get(),
                delegateAckMaxMs = delegateAckMaxMs.get(),
                totalLastMs = totalLastMs.get(),
                totalMaxMs = totalMaxMs.get()
            )
        }

        private fun recordElapsed(
            phase: String,
            startedAtNanos: Long,
            endedAtNanos: Long,
            lastMs: AtomicLong,
            maxMs: AtomicLong
        ) {
            val elapsed = endedAtNanos - startedAtNanos
            val elapsedMs = Duration.ofNanos(elapsed).toMillis()
            lastMs.set(elapsedMs)
            maxMs.accumulateAndGet(elapsedMs, ::maxOf)
            HotPathMetrics.record(phase, elapsed)
        }
    }

    private class PublishRequest(
        val envelope: StreamCommandEnvelope,
        val future: CompletableFuture<StreamPublishAck> = CompletableFuture(),
        val enqueuedAtNanos: Long = System.nanoTime()
    )

    private class AcquiredPublishRequest(
        val request: PublishRequest,
        val submittedAtNanos: Long
    )
}

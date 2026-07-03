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

class StreamCommandWorker(
    private val source: StreamCommandSource,
    private val api: PlatformApi,
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
        StreamCommandWorkerMetrics.recordFetched(deliveries.size)
        deliveries.forEach { delivery -> process(delivery) }
        return deliveries.size
    }

    private fun process(delivery: StreamCommandDelivery) {
        if (commandType(delivery.subject) != "SubmitOrder") {
            delivery.term()
            StreamCommandWorkerMetrics.recordUnsupported()
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
            StreamCommandWorkerMetrics.recordFailed(ex.message ?: ex::class.simpleName ?: "unknown")
            return
        }

        try {
            delivery.ack()
            StreamCommandWorkerMetrics.recordCompleted()
        } catch (ex: Exception) {
            StreamCommandWorkerMetrics.recordAckFailed(ex.message ?: ex::class.simpleName ?: "unknown")
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
    private val filterSubject: String,
    private val durableName: String,
    private val ackWait: Duration = Duration.ofMillis(RuntimeEnv.long("STREAM_ACK_WORKER_ACK_WAIT_MS", 30_000L, min = 1_000L))
) : StreamCommandSource {
    private val connection by lazy {
        val options = Options.Builder()
            .server(natsUrl)
            .connectionTimeout(Duration.ofMillis(RuntimeEnv.long("STREAM_ACK_NATS_CONNECT_TIMEOUT_MS", 2_000L, min = 1L)))
            .build()
        Nats.connect(options)
    }
    private val jetStream by lazy { connection.jetStream() }
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
            filterSubject = filterSubject,
            durableName = durableName
        )
    }
}

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
    val lastError: String
)

object StreamCommandWorkerMetrics {
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

    fun recordFetched(count: Int) {
        if (count <= 0) return
        fetched.addAndGet(count.toLong())
        lastFetchedAtEpochMs.set(System.currentTimeMillis())
    }

    fun recordCompleted() {
        completed.incrementAndGet()
        lastCompletedAtEpochMs.set(System.currentTimeMillis())
    }

    fun recordFailed(error: String) {
        failed.incrementAndGet()
        lastFailedAtEpochMs.set(System.currentTimeMillis())
        lastError = error
    }

    fun recordAckFailed(error: String) {
        ackFailed.incrementAndGet()
        lastAckFailedAtEpochMs.set(System.currentTimeMillis())
        lastError = error
    }

    fun recordUnsupported() {
        unsupported.incrementAndGet()
        lastFailedAtEpochMs.set(System.currentTimeMillis())
        lastError = "unsupported stream command type"
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
            lastError = lastError
        )
    }

    private fun instantString(epochMs: Long): String {
        if (epochMs <= 0) return ""
        return Instant.ofEpochMilli(epochMs).toString()
    }
}

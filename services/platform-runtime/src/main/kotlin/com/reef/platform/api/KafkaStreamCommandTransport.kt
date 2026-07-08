package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewPartitions
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.InvalidPartitionsException
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

private const val STREAM_SUBJECT_HEADER = "reef-stream-subject"
private const val KAFKA_OFFSET_SEQUENCE_BITS = 48
private const val KAFKA_OFFSET_SEQUENCE_MASK = (1L shl KAFKA_OFFSET_SEQUENCE_BITS) - 1L

class KafkaStreamCommandPublisher(
    private val bootstrapServers: String = RuntimeEnv.string("STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
    private val ackTimeout: Duration = Duration.ofMillis(RuntimeEnv.long("STREAM_ACK_PUBLISH_ACK_TIMEOUT_MS", 2_000L, min = 1L)),
    private val config: StreamCommandConfig = StreamCommandConfig(),
    private val maxInFlight: Int = RuntimeEnv.int("STREAM_ACK_KAFKA_PUBLISH_MAX_IN_FLIGHT", 16_384, min = 1)
) : StreamCommandPublisher, AsyncStreamCommandPublisher, BatchAsyncStreamCommandPublisher, StreamCommandHealthCheck {
    private val topic = config.streamName
    private val topicReady = AtomicBoolean(false)
    private val lastPublishAckMs = AtomicLong(0L)
    private val maxPublishAckMs = AtomicLong(0L)
    private val inFlightSlots = Semaphore(maxInFlight)
    private val inFlight = AtomicInteger(0)
    private val producer by lazy {
        val publishAckTimeoutMs = ackTimeout.toMillis().coerceAtLeast(1L)
        val lingerMs = RuntimeEnv.long("STREAM_ACK_KAFKA_LINGER_MS", 1L, min = 0L)
        val requestTimeoutMs = RuntimeEnv.long(
            "STREAM_ACK_KAFKA_REQUEST_TIMEOUT_MS",
            minOf(1_000L, publishAckTimeoutMs).coerceAtLeast(1L),
            min = 1L
        )
        val deliveryTimeoutMs = RuntimeEnv.long(
            "STREAM_ACK_KAFKA_DELIVERY_TIMEOUT_MS",
            maxOf(publishAckTimeoutMs, requestTimeoutMs + lingerMs + 1L),
            min = 1L
        )
        KafkaProducer<String, String>(
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.ACKS_CONFIG, "all")
                put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
                put(ProducerConfig.RETRIES_CONFIG, Int.MAX_VALUE.toString())
                put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, RuntimeEnv.int("STREAM_ACK_KAFKA_MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION", 5, min = 1).coerceAtMost(5).toString())
                put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs.toString())
                put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs.toString())
                put(ProducerConfig.LINGER_MS_CONFIG, lingerMs.toString())
                put(ProducerConfig.BATCH_SIZE_CONFIG, RuntimeEnv.int("STREAM_ACK_KAFKA_BATCH_SIZE", 65_536, min = 1).toString())
                put(ProducerConfig.COMPRESSION_TYPE_CONFIG, RuntimeEnv.string("STREAM_ACK_KAFKA_COMPRESSION_TYPE", "lz4"))
                put(ProducerConfig.BUFFER_MEMORY_CONFIG, RuntimeEnv.long("STREAM_ACK_KAFKA_BUFFER_MEMORY_BYTES", 67_108_864L, min = 1L).toString())
                put(ProducerConfig.MAX_BLOCK_MS_CONFIG, RuntimeEnv.long("STREAM_ACK_KAFKA_MAX_BLOCK_MS", 250L, min = 1L).toString())
                put(ProducerConfig.CLIENT_ID_CONFIG, RuntimeEnv.string("STREAM_ACK_KAFKA_CLIENT_ID", "reef-platform-runtime-command-producer"))
            }
        )
    }

    override fun publish(envelope: StreamCommandEnvelope): StreamPublishAck {
        return publishAsync(envelope).get(ackTimeout.toMillis().coerceAtLeast(1L), TimeUnit.MILLISECONDS)
    }

    override fun publishAsync(envelope: StreamCommandEnvelope): CompletableFuture<StreamPublishAck> {
        ensureTopic()
        return publishAsyncReady(envelope)
    }

    override fun publishAsyncBatch(envelopes: List<StreamCommandEnvelope>): List<CompletableFuture<StreamPublishAck>> {
        if (envelopes.isEmpty()) return emptyList()
        ensureTopic()
        return envelopes.map { envelope -> publishAsyncReady(envelope) }
    }

    private fun publishAsyncReady(envelope: StreamCommandEnvelope): CompletableFuture<StreamPublishAck> {
        if (!inFlightSlots.tryAcquire()) {
            return CompletableFuture.failedFuture(StreamCommandPublishBackpressureException("Kafka publish in-flight window is full"))
        }
        val record = ProducerRecord(
            topic,
            envelope.partition,
            envelope.natsMessageId,
            envelope.payloadJson,
            listOf(RecordHeader(STREAM_SUBJECT_HEADER, envelope.subject.toByteArray(Charsets.UTF_8)))
        )
        val started = System.nanoTime()
        inFlight.incrementAndGet()
        val result = CompletableFuture<StreamPublishAck>()
        try {
            producer.send(record) { metadata, exception ->
                inFlight.decrementAndGet()
                inFlightSlots.release()
                recordPublishAckElapsed(started)
                if (exception != null) {
                    result.completeExceptionally(exception)
                } else {
                    result.complete(
                        StreamPublishAck(
                            streamName = topic,
                            streamSequence = kafkaStreamSequence(metadata.partition(), metadata.offset())
                        )
                    )
                }
            }
        } catch (ex: Exception) {
            inFlight.decrementAndGet()
            inFlightSlots.release()
            recordPublishAckElapsed(started)
            result.completeExceptionally(ex)
        }
        return result
    }

    override fun snapshot(): StreamCommandHealthSnapshot {
        return try {
            ensureTopic()
            val admin = adminClient()
            admin.use {
                val partitions = it.describeTopics(listOf(topic)).allTopicNames().get().getValue(topic).partitions()
                val topicPartitions = partitions.map { partition -> TopicPartition(topic, partition.partition()) }
                val starts = it.listOffsets(topicPartitions.associateWith {
                    org.apache.kafka.clients.admin.OffsetSpec.earliest()
                }).all().get()
                val ends = it.listOffsets(topicPartitions.associateWith {
                    org.apache.kafka.clients.admin.OffsetSpec.latest()
                }).all().get()
                val messageCount = topicPartitions.sumOf { partition ->
                    val end = ends.getValue(partition).offset()
                    val start = starts.getValue(partition).offset()
                    (end - start).coerceAtLeast(0L)
                }
                StreamCommandHealthSnapshot(
                    available = true,
                    streamName = topic,
                    messageCount = messageCount,
                    publishMode = "kafka-async",
                    publishInFlight = inFlight.get(),
                    publishMaxInFlight = maxInFlight,
                    publishAckLastMs = lastPublishAckMs.get(),
                    publishAckMaxMs = maxPublishAckMs.get(),
                    producerMetrics = producerMetricsSnapshot()
                )
            }
        } catch (ex: Exception) {
            StreamCommandHealthSnapshot(
                available = false,
                streamName = topic,
                publishMode = "kafka-async",
                publishInFlight = inFlight.get(),
                publishMaxInFlight = maxInFlight,
                publishAckLastMs = lastPublishAckMs.get(),
                publishAckMaxMs = maxPublishAckMs.get(),
                producerMetrics = producerMetricsSnapshot(),
                error = ex.message ?: "unknown"
            )
        }
    }

    private fun ensureTopic() {
        if (topicReady.get()) return
        synchronized(topicReady) {
            if (topicReady.get()) return
            adminClient().use { admin ->
                try {
                    admin.createTopics(listOf(NewTopic(topic, config.partitionCount, 1))).all().get()
                } catch (ex: Exception) {
                    if (!isTopicExists(ex)) throw ex
                    ensureTopicPartitionCount(admin)
                }
            }
            topicReady.set(true)
        }
    }

    private fun ensureTopicPartitionCount(admin: AdminClient) {
        val existingPartitionCount = topicPartitionCount(admin)
        if (existingPartitionCount >= config.partitionCount) return

        try {
            admin.createPartitions(mapOf(topic to NewPartitions.increaseTo(config.partitionCount))).all().get()
        } catch (ex: Exception) {
            if (!isPartitionIncreaseRace(ex)) throw ex
            val latestPartitionCount = topicPartitionCount(admin)
            if (latestPartitionCount < config.partitionCount) throw ex
        }
    }

    private fun topicPartitionCount(admin: AdminClient): Int {
        return admin.describeTopics(listOf(topic)).allTopicNames().get().getValue(topic).partitions().size
    }

    private fun isTopicExists(ex: Exception): Boolean {
        return ex is TopicExistsException || ex.cause is TopicExistsException
    }

    private fun isPartitionIncreaseRace(ex: Exception): Boolean {
        return ex is InvalidPartitionsException || ex.cause is InvalidPartitionsException
    }

    private fun adminClient(): AdminClient {
        return AdminClient.create(Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        })
    }

    private fun recordPublishAckElapsed(started: Long) {
        val elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis()
        lastPublishAckMs.set(elapsedMs)
        maxPublishAckMs.accumulateAndGet(elapsedMs, ::maxOf)
    }

    private fun producerMetricsSnapshot(): Map<String, Double> {
        return try {
            producer.metrics()
                .filterKeys { metricName -> metricName.name() in KAFKA_PRODUCER_METRIC_NAMES }
                .mapKeys { (metricName, _) ->
                    if (metricName.tags().isEmpty()) {
                        metricName.name()
                    } else {
                        val tagToken = metricName.tags().entries
                            .sortedBy { it.key }
                            .joinToString(",") { "${it.key}=${it.value}" }
                        "${metricName.name()}[$tagToken]"
                    }
                }
                .mapValues { (_, metric) ->
                    metric.metricValue().toString().toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
                }
                .toSortedMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

private val KAFKA_PRODUCER_METRIC_NAMES = setOf(
    "batch-size-avg",
    "batch-size-max",
    "batch-split-rate",
    "batch-split-total",
    "buffer-available-bytes",
    "buffer-exhausted-rate",
    "buffer-exhausted-total",
    "bufferpool-wait-ratio",
    "bufferpool-wait-time-ns-total",
    "compression-rate-avg",
    "metadata-age",
    "produce-throttle-time-avg",
    "produce-throttle-time-max",
    "record-error-rate",
    "record-error-total",
    "record-queue-time-avg",
    "record-queue-time-max",
    "record-retry-rate",
    "record-retry-total",
    "record-send-rate",
    "record-send-total",
    "record-size-avg",
    "record-size-max",
    "records-per-request-avg",
    "request-latency-avg",
    "request-latency-max",
    "requests-in-flight",
    "waiting-threads"
)

class KafkaStreamCommandSource(
    private val bootstrapServers: String = RuntimeEnv.string("STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
    private val config: StreamCommandConfig = StreamCommandConfig(),
    private val partition: Int,
    private val durableName: String
) : StreamCommandSource, StreamCommandTelemetrySource {
    private val topic = config.streamName
    private val topicPartition = TopicPartition(topic, partition)
    private val ackedOffsets = sortedSetOf<Long>()
    private var nextCommitOffset: Long? = null
    private var rewindOffset: Long? = null
    private val consumer by lazy { openConsumer() }

    override fun fetch(batchSize: Int, timeout: Duration): List<StreamCommandDelivery> {
        synchronized(this) {
            rewindOffset?.let { offset ->
                consumer.seek(topicPartition, offset)
                rewindOffset = null
            }
            return consumer.poll(timeout).records(topicPartition).take(batchSize).map { record ->
                val subject = record.headers().lastHeader(STREAM_SUBJECT_HEADER)?.value()?.toString(Charsets.UTF_8)
                    ?: StreamCommandWorkerFactory.subjectForPartitionCommand(config, partition, "SubmitOrder")
                KafkaStreamCommandDelivery(
                    subject = subject,
                    payloadJson = record.value(),
                    streamSequence = kafkaStreamSequence(record.partition(), record.offset()),
                    onAck = { recordAck(record.offset()) },
                    onNak = { recordNak(record.offset()) }
                )
            }
        }
    }

    override fun consumerSnapshot(): StreamCommandConsumerSnapshot {
        return try {
            synchronized(this) {
                val committed = consumer.committed(setOf(topicPartition))[topicPartition]?.offset()
                    ?: beginningOffset()
                val end = consumer.endOffsets(listOf(topicPartition)).getValue(topicPartition)
                StreamCommandConsumerSnapshot(
                    partition = partition,
                    durableName = durableName,
                    filterSubject = topic,
                    pending = (end - committed).coerceAtLeast(0L),
                    deliveredStreamSequence = consumer.position(topicPartition),
                    ackFloorStreamSequence = committed,
                    streamLastSequence = end,
                    streamLag = (end - committed).coerceAtLeast(0L),
                    sampledAt = Instant.now().toString()
                )
            }
        } catch (ex: Exception) {
            StreamCommandConsumerSnapshot(
                partition = partition,
                durableName = durableName,
                filterSubject = topic,
                error = ex.message ?: ex::class.simpleName ?: "unknown"
            )
        }
    }

    private fun openConsumer(): KafkaConsumer<String, String> {
        val consumer = KafkaConsumer<String, String>(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, durableName)
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, RuntimeEnv.int("STREAM_ACK_KAFKA_MAX_POLL_RECORDS", 1_000, min = 1).toString())
            }
        )
        consumer.assign(listOf(topicPartition))
        nextCommitOffset = consumer.committed(setOf(topicPartition))[topicPartition]?.offset()
            ?: consumer.beginningOffsets(listOf(topicPartition)).getValue(topicPartition)
        return consumer
    }

    private fun recordAck(offset: Long) {
        synchronized(this) {
            ackedOffsets.add(offset)
            var next = nextCommitOffset ?: beginningOffset()
            while (ackedOffsets.remove(next)) {
                next += 1
            }
            if (next != nextCommitOffset) {
                consumer.commitSync(mapOf(topicPartition to org.apache.kafka.clients.consumer.OffsetAndMetadata(next)))
                nextCommitOffset = next
            }
        }
    }

    private fun recordNak(offset: Long) {
        synchronized(this) {
            rewindOffset = minOf(rewindOffset ?: offset, offset)
        }
    }

    private fun beginningOffset(): Long {
        return consumer.beginningOffsets(listOf(topicPartition)).getValue(topicPartition)
    }
}

class KafkaVenueEventBatchSource(
    private val bootstrapServers: String = RuntimeEnv.string("STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
    private val topic: String = RuntimeEnv.string(
        "VENUE_EVENT_MATERIALIZER_TOPIC",
        RuntimeEnv.string("MATCHING_ENGINE_EVENT_STREAM", "REEF_VENUE_EVENTS")
    ),
    private val groupId: String = RuntimeEnv.string("VENUE_EVENT_MATERIALIZER_GROUP_ID", "reef-venue-event-batch-materializer"),
    private val clientId: String = RuntimeEnv.string("VENUE_EVENT_MATERIALIZER_KAFKA_CLIENT_ID", "reef-platform-runtime-venue-event-materializer")
) : VenueEventBatchSource {
    private val ackedOffsets = mutableMapOf<TopicPartition, java.util.TreeSet<Long>>()
    private val nextCommitOffsets = mutableMapOf<TopicPartition, Long>()
    private val rewindOffsets = mutableMapOf<TopicPartition, Long>()
    private val consumer by lazy { openConsumer() }

    override fun fetch(batchSize: Int, timeout: Duration): List<VenueEventBatchDelivery> {
        synchronized(this) {
            rewindOffsets.forEach { (topicPartition, offset) ->
                consumer.seek(topicPartition, offset)
            }
            rewindOffsets.clear()

            return consumer.poll(timeout).records(topic).take(batchSize).map { record ->
                val topicPartition = TopicPartition(record.topic(), record.partition())
                ensureNextCommitOffset(topicPartition)
                val subject = record.headers().lastHeader(STREAM_SUBJECT_HEADER)?.value()?.toString(Charsets.UTF_8)
                    ?: defaultVenueEventSubject(record.partition())
                KafkaVenueEventBatchDelivery(
                    subject = subject,
                    payloadJson = record.value(),
                    streamSequence = kafkaStreamSequence(record.partition(), record.offset()),
                    deliveredCount = 1L,
                    onAck = { recordAck(topicPartition, record.offset()) },
                    onNak = { recordNak(topicPartition, record.offset()) }
                )
            }
        }
    }

    private fun openConsumer(): KafkaConsumer<String, String> {
        val consumer = KafkaConsumer<String, String>(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
                put(ConsumerConfig.CLIENT_ID_CONFIG, clientId)
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(
                    ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                    RuntimeEnv.int("VENUE_EVENT_MATERIALIZER_KAFKA_MAX_POLL_RECORDS", RuntimeEnv.int("VENUE_EVENT_MATERIALIZER_BATCH_SIZE", 100, min = 1), min = 1).toString()
                )
            }
        )
        consumer.subscribe(listOf(topic))
        return consumer
    }

    private fun recordAck(topicPartition: TopicPartition, offset: Long) {
        synchronized(this) {
            val acked = ackedOffsets.getOrPut(topicPartition) { sortedSetOf<Long>() }
            acked.add(offset)
            var next = ensureNextCommitOffset(topicPartition)
            while (acked.remove(next)) {
                next += 1
            }
            if (next != nextCommitOffsets[topicPartition]) {
                consumer.commitSync(mapOf(topicPartition to OffsetAndMetadata(next)))
                nextCommitOffsets[topicPartition] = next
            }
        }
    }

    private fun recordNak(topicPartition: TopicPartition, offset: Long) {
        synchronized(this) {
            rewindOffsets[topicPartition] = minOf(rewindOffsets[topicPartition] ?: offset, offset)
        }
    }

    private fun ensureNextCommitOffset(topicPartition: TopicPartition): Long {
        return nextCommitOffsets.getOrPut(topicPartition) {
            consumer.committed(setOf(topicPartition))[topicPartition]?.offset()
                ?: consumer.beginningOffsets(listOf(topicPartition)).getValue(topicPartition)
        }
    }

    private fun defaultVenueEventSubject(partition: Int): String {
        val prefix = RuntimeEnv.string("MATCHING_ENGINE_EVENT_SUBJECT_PREFIX", "reef.venue.events.v1").trim('.')
        return "$prefix.${partitionToken(partition)}.VenueEventBatch"
    }
}

private class KafkaStreamCommandDelivery(
    override val subject: String,
    override val payloadJson: String,
    override val streamSequence: Long,
    private val onAck: () -> Unit,
    private val onNak: () -> Unit
) : StreamCommandDelivery {
    override val deliveredCount: Long = 1L

    override fun ack() {
        onAck()
    }

    override fun nak() {
        onNak()
    }

    override fun term() {
        onAck()
    }
}

private class KafkaVenueEventBatchDelivery(
    override val subject: String,
    override val payloadJson: String,
    override val streamSequence: Long,
    override val deliveredCount: Long,
    private val onAck: () -> Unit,
    private val onNak: () -> Unit
) : VenueEventBatchDelivery {
    override fun ack() {
        onAck()
    }

    override fun nak() {
        onNak()
    }

    override fun term() {
        onAck()
    }
}

private fun kafkaStreamSequence(partition: Int, offset: Long): Long {
    val logicalOffset = offset + 1L
    require(partition >= 0) { "Kafka partition must be non-negative: $partition" }
    require(logicalOffset in 1L..KAFKA_OFFSET_SEQUENCE_MASK) {
        "Kafka offset $offset is too large to encode in $KAFKA_OFFSET_SEQUENCE_BITS bits"
    }
    return (partition.toLong() shl KAFKA_OFFSET_SEQUENCE_BITS) or logicalOffset
}

private fun partitionToken(partition: Int): String {
    return "p" + partition.toString().padStart(2, '0')
}

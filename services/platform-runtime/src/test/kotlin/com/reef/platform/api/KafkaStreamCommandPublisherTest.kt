package com.reef.platform.api

import org.apache.kafka.clients.producer.Callback
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.Metric
import org.apache.kafka.common.MetricName
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KafkaStreamCommandPublisherTest {
    @Test
    fun kafkaPublisherBuildsPartitionedRecordAndReturnsDurableSequence() {
        var sentRecord: ProducerRecord<String, String>? = null
        val producer = ScriptedKafkaProducer(
            listOf(
                { record, callback ->
                    sentRecord = record
                    callback.onCompletion(metadata(partition = 2, offset = 41L), null)
                }
            )
        )
        val publisher = KafkaStreamCommandPublisher(
            ackTimeout = Duration.ofSeconds(2),
            config = StreamCommandConfig(streamName = "REEF_COMMANDS", partitionCount = 4),
            maxInFlight = 2,
            producer = producer
        )

        val ack = publisher.publish(envelope(partition = 2))

        assertEquals("REEF_COMMANDS", ack.streamName)
        assertEquals((2L shl 48) or 42L, ack.streamSequence)
        assertEquals("REEF_COMMANDS", sentRecord?.topic())
        assertEquals(2, sentRecord?.partition())
        assertEquals("client-1|/api/v1/orders/submit|idem-1", sentRecord?.key())
        assertEquals("""{"commandId":"cmd-1"}""", sentRecord?.value())
        assertEquals(
            "reef.cmd.v1.p02.session-1.AAPL.SubmitOrder",
            sentRecord?.headers()?.lastHeader("reef-stream-subject")?.value()?.toString(Charsets.UTF_8)
        )
    }

    @Test
    fun kafkaPublisherSupportsBatchesWithoutSendingEmptyBatch() {
        val records = mutableListOf<ProducerRecord<String, String>>()
        val producer = ScriptedKafkaProducer(
            listOf(
                { record, callback ->
                    records += record
                    callback.onCompletion(metadata(partition = 0, offset = 10L), null)
                },
                { record, callback ->
                    records += record
                    callback.onCompletion(metadata(partition = 1, offset = 11L), null)
                }
            )
        )
        val publisher = KafkaStreamCommandPublisher(
            ackTimeout = Duration.ofSeconds(2),
            config = StreamCommandConfig(streamName = "REEF_COMMANDS", partitionCount = 2),
            maxInFlight = 2,
            producer = producer
        )

        assertTrue(publisher.publishAsyncBatch(emptyList()).isEmpty())
        val acknowledgements = publisher.publishAsyncBatch(
            listOf(envelope(partition = 0), envelope(partition = 1, commandId = "cmd-2"))
        ).map { it.get(2, TimeUnit.SECONDS) }

        assertEquals(2, producer.sendCount.get())
        assertEquals(listOf(0, 1), records.map { it.partition() })
        assertEquals(11L, acknowledgements[0].streamSequence)
        assertEquals((1L shl 48) or 12L, acknowledgements[1].streamSequence)
    }

    @Test
    fun kafkaPublisherBackpressuresAtConfiguredWindowAndReleasesSlotAfterAck() {
        var heldCallback: Callback? = null
        val producer = ScriptedKafkaProducer(
            listOf(
                { _, callback -> heldCallback = callback },
                { _, callback -> callback.onCompletion(metadata(partition = 0, offset = 2L), null) }
            )
        )
        val publisher = KafkaStreamCommandPublisher(
            ackTimeout = Duration.ofSeconds(2),
            config = StreamCommandConfig(streamName = "REEF_COMMANDS", partitionCount = 1),
            maxInFlight = 1,
            producer = producer
        )

        val first = publisher.publishAsync(envelope())
        val rejected = assertFailsWith<ExecutionException> {
            publisher.publishAsync(envelope(commandId = "cmd-2")).get(2, TimeUnit.SECONDS)
        }
        assertTrue(rejected.cause is StreamCommandPublishBackpressureException)
        assertEquals(1, producer.sendCount.get())

        heldCallback!!.onCompletion(metadata(partition = 0, offset = 1L), null)
        assertEquals(2L, first.get(2, TimeUnit.SECONDS).streamSequence)
        assertEquals(3L, publisher.publishAsync(envelope(commandId = "cmd-3")).get(2, TimeUnit.SECONDS).streamSequence)
        assertEquals(2, producer.sendCount.get())
    }

    @Test
    fun kafkaPublisherReleasesSlotWhenCallbackOmitsMetadata() {
        val producer = ScriptedKafkaProducer(
            listOf(
                { _, callback -> callback.onCompletion(null, null) },
                { _, callback -> callback.onCompletion(metadata(partition = 0, offset = 2L), null) }
            )
        )
        val publisher = KafkaStreamCommandPublisher(
            ackTimeout = Duration.ofSeconds(2),
            config = StreamCommandConfig(streamName = "REEF_COMMANDS", partitionCount = 1),
            maxInFlight = 1,
            producer = producer
        )

        val failure = assertFailsWith<ExecutionException> {
            publisher.publishAsync(envelope()).get(2, TimeUnit.SECONDS)
        }
        assertEquals("Kafka publish callback completed without metadata", failure.cause?.message)
        assertEquals(3L, publisher.publishAsync(envelope(commandId = "cmd-2")).get(2, TimeUnit.SECONDS).streamSequence)
    }

    @Test
    fun kafkaPublisherDoesNotApplicationResendRetriableCallbackFailure() {
        val producer = ScriptedKafkaProducer(
            listOf(
                { _, callback -> callback.onCompletion(null, TimeoutException("metadata refresh in progress")) }
            )
        )
        val publisher = KafkaStreamCommandPublisher(
            ackTimeout = Duration.ofSeconds(2),
            config = StreamCommandConfig(streamName = "REEF_COMMANDS", partitionCount = 1),
            maxInFlight = 1,
            producer = producer
        )

        val failure = assertFailsWith<ExecutionException> {
            publisher.publishAsync(envelope()).get(2, TimeUnit.SECONDS)
        }

        assertEquals(1, producer.sendCount.get())
        assertEquals("metadata refresh in progress", failure.cause?.message)
    }

    @Test
    fun kafkaPublisherDoesNotRetryNonRetriablePublishFailure() {
        val producer = ScriptedKafkaProducer(
            listOf(
                { _, callback -> callback.onCompletion(null, IllegalArgumentException("bad record")) }
            )
        )
        val publisher = KafkaStreamCommandPublisher(
            ackTimeout = Duration.ofSeconds(2),
            config = StreamCommandConfig(streamName = "REEF_COMMANDS", partitionCount = 1),
            maxInFlight = 1,
            producer = producer
        )

        val failure = assertFailsWith<ExecutionException> {
            publisher.publishAsync(envelope()).get(2, TimeUnit.SECONDS)
        }

        assertEquals(1, producer.sendCount.get())
        assertEquals("bad record", failure.cause?.message)
    }

    private fun envelope(partition: Int = 0, commandId: String = "cmd-1"): StreamCommandEnvelope {
        return StreamCommandEnvelope(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            payloadHash = "payload-hash",
            commandId = commandId,
            commandType = "SubmitOrder",
            runId = "run-1",
            venueSessionId = "session-1",
            instrumentId = "AAPL",
            participantId = "participant-1",
            orderId = "order-1",
            clientOrderId = "clord-1",
            actorId = "actor-1",
            traceId = "trace-1",
            correlationId = "corr-1",
            causationId = "",
            botId = "",
            botVersion = "",
            subject = "reef.cmd.v1.p${partition.toString().padStart(2, '0')}.session-1.AAPL.SubmitOrder",
            partition = partition,
            payloadJson = """{"commandId":"$commandId"}"""
        )
    }

    private fun metadata(partition: Int, offset: Long): RecordMetadata =
        RecordMetadata(TopicPartition("REEF_COMMANDS", partition), offset, 0, 0L, 0, 0)
}

private class ScriptedKafkaProducer(
    steps: List<(ProducerRecord<String, String>, Callback) -> Unit>
) : KafkaCommandProducer {
    private val queue = ConcurrentLinkedQueue(steps)
    val sendCount = AtomicInteger(0)

    override fun send(record: ProducerRecord<String, String>, callback: Callback) {
        sendCount.incrementAndGet()
        val step = queue.poll() ?: error("unexpected Kafka send")
        step(record, callback)
    }

    override fun metrics(): Map<MetricName, Metric> = emptyMap()
}

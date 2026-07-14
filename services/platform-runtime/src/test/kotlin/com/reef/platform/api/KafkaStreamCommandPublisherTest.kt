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

class KafkaStreamCommandPublisherTest {
    @Test
    fun kafkaPublisherRetriesRetriableCallbackFailureBeforeSurfacing503ToBoundary() {
        val producer = ScriptedKafkaProducer(
            listOf(
                { _, callback -> callback.onCompletion(null, TimeoutException("metadata refresh in progress")) },
                { record, callback -> callback.onCompletion(recordMetadata(record, offset = 41L), null) }
            )
        )
        val publisher = KafkaStreamCommandPublisher(
            ackTimeout = Duration.ofSeconds(2),
            config = StreamCommandConfig(streamName = "REEF_COMMANDS", partitionCount = 1),
            maxInFlight = 1,
            producer = producer,
            publishRetryAttempts = 1,
            publishRetryDelay = Duration.ZERO
        )

        val ack = publisher.publishAsync(envelope()).get(2, TimeUnit.SECONDS)

        assertEquals(2, producer.sendCount.get())
        assertEquals("REEF_COMMANDS", ack.streamName)
        assertEquals(42L, ack.streamSequence)
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
            producer = producer,
            publishRetryAttempts = 3,
            publishRetryDelay = Duration.ZERO
        )

        val failure = assertFailsWith<ExecutionException> {
            publisher.publishAsync(envelope()).get(2, TimeUnit.SECONDS)
        }

        assertEquals(1, producer.sendCount.get())
        assertEquals("bad record", failure.cause?.message)
    }

    private fun recordMetadata(record: ProducerRecord<String, String>, offset: Long): RecordMetadata {
        return RecordMetadata(
            TopicPartition(record.topic(), record.partition() ?: 0),
            offset,
            0,
            System.currentTimeMillis(),
            0,
            0
        )
    }

    private fun envelope(): StreamCommandEnvelope {
        return StreamCommandEnvelope(
            clientId = "client-1",
            route = "/api/v1/orders/submit",
            idempotencyKey = "idem-1",
            payloadHash = "payload-hash",
            commandId = "cmd-1",
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
            subject = "reef.cmd.v1.p00.session-1.AAPL.SubmitOrder",
            partition = 0,
            payloadJson = """{"commandId":"cmd-1"}"""
        )
    }
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

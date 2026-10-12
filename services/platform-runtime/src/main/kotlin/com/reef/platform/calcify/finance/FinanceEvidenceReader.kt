package com.reef.platform.calcify.finance

import com.reef.platform.calcify.MemoryLifecycleKv
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

/**
 * Reads the broker-side facts [FinanceRestoreCut] needs: the read-committed decision log, the finance changelog contents, the finance
 * group's committed offsets, the read-committed ends and, for each input topic, the first record a read-committed consumer can see
 * after a given offset. Read-only: it needs Describe on all topics and the group and Read on the decision and changelog topics and the
 * two input topics (the `observer` principal of the isolation matrix), and it never writes or joins a group.
 */
internal class FinanceEvidenceReader(
    private val clientProperties: Properties, private val applicationId: String, private val bindingDigest: String,
    private val captureTopic: String, private val controlTopic: String, private val decisionsTopic: String, private val changelogTopic: String,
    private val readTimeout: Duration = Duration.ofSeconds(30),
) {
    private fun consumer() = KafkaConsumer<ByteArray, ByteArray>(Properties().apply {
        putAll(clientProperties)
        put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none"); put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
    })

    private fun <T> withConsumer(topic: String, block: (KafkaConsumer<ByteArray, ByteArray>, TopicPartition) -> T): T = consumer().use { c ->
        val tp = TopicPartition(topic, 0); c.assign(listOf(tp)); block(c, tp)
    }

    fun readCommittedEnd(topic: String): Long = withConsumer(topic) { c, tp -> c.endOffsets(listOf(tp), readTimeout).getValue(tp) }

    /** Every read-committed record from offset 0 to the read-committed end. A topic that is shorter than its end after the timeout is a failure, not a partial read. */
    fun readAll(topic: String): List<Pair<ByteArray?, ByteArray?>> = withConsumer(topic) { c, tp ->
        c.seekToBeginning(listOf(tp))
        val end = c.endOffsets(listOf(tp), readTimeout).getValue(tp)
        val out = ArrayList<Pair<ByteArray?, ByteArray?>>()
        val deadline = System.nanoTime() + readTimeout.toNanos()
        while (c.position(tp, readTimeout) < end) {
            c.poll(Duration.ofMillis(200)).forEach { out += it.key() to it.value() }
            check(System.nanoTime() < deadline) { "timed out reading $topic" }
        }
        out
    }

    fun firstVisibleAtOrAfter(topic: String, offset: Long): Long? = withConsumer(topic) { c, tp ->
        c.seek(tp, offset)
        val end = c.endOffsets(listOf(tp), readTimeout).getValue(tp)
        val deadline = System.nanoTime() + readTimeout.toNanos()
        while (c.position(tp, readTimeout) < end && System.nanoTime() < deadline) c.poll(Duration.ofMillis(200)).firstOrNull()?.let { return@withConsumer it.offset() }
        null
    }

    fun read(): FinanceRestoreEvidence {
        val committed = AdminClient.create(Properties().apply { putAll(clientProperties); put("request.timeout.ms", 15000); put("default.api.timeout.ms", 30000) }).use { admin ->
            admin.listConsumerGroupOffsets(applicationId).partitionsToOffsetAndMetadata().get(30, TimeUnit.SECONDS)
        }
        val store = MemoryLifecycleKv()
        for ((k, v) in readAll(changelogTopic)) {
            val key = String(requireNotNull(k) { "changelog record without key" }, Charsets.UTF_8)
            if (v != null) store.entries[key] = v else store.entries.remove(key)
        }
        return FinanceRestoreEvidence(bindingDigest, store, readAll(decisionsTopic).map { requireNotNull(it.second) { "decision record without value" } },
            committed[TopicPartition(captureTopic, 0)]?.offset(), committed[TopicPartition(controlTopic, 0)]?.offset(),
            readCommittedEnd(captureTopic), readCommittedEnd(controlTopic),
            firstVisibleAtOrAfter = { role, offset ->
                firstVisibleAtOrAfter(when (role) { "CAPTURE" -> captureTopic; "CONTROL" -> controlTopic; else -> throw IllegalArgumentException("unknown input role $role") }, offset)
            })
    }
}

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
        put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5_000)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none"); put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
    })

    private fun <T> withConsumer(topic: String, block: (KafkaConsumer<ByteArray, ByteArray>, TopicPartition) -> T): T = consumer().use { c ->
        val tp = TopicPartition(topic, 0); c.assign(listOf(tp)); block(c, tp)
    }

    fun readCommittedEnd(topic: String): Long = withConsumer(topic) { c, tp -> c.endOffsets(listOf(tp), readTimeout).getValue(tp) }

    /** Drains every read-committed record from offset 0 to the read-committed end on an already assigned consumer. A topic shorter than its end after the timeout is a failure, not a partial read. */
    private fun drain(c: KafkaConsumer<ByteArray, ByteArray>, tp: TopicPartition, topic: String): List<Pair<ByteArray?, ByteArray?>> {
        c.seekToBeginning(listOf(tp))
        val end = c.endOffsets(listOf(tp), readTimeout).getValue(tp)
        val out = ArrayList<Pair<ByteArray?, ByteArray?>>()
        val deadline = System.nanoTime() + readTimeout.toNanos()
        while (c.position(tp, readTimeout) < end) {
            c.poll(Duration.ofMillis(200)).forEach { out += it.key() to it.value() }
            check(System.nanoTime() < deadline) { "timed out reading $topic" }
        }
        return out
    }

    /** Every read-committed record from offset 0 to the read-committed end, on a fresh consumer (activation-time use). */
    fun readAll(topic: String): List<Pair<ByteArray?, ByteArray?>> = withConsumer(topic) { c, tp -> drain(c, tp, topic) }

    /**
     * One consumer kept for the life of the projection loop, so each pass costs a fetch instead of a new connection and SASL handshake (review 3,
     * P3-1 / F6). [LogReader.readAll] re-reads the whole log (the bounded finite profile's pass); [LogReader.readNext] reads only what is new.
     * Not thread-safe: the projection loop is its only user; close it after the loop has stopped.
     */
    inner class LogReader(private val topic: String, private val partition: Int = 0) : AutoCloseable {
        private val tp = TopicPartition(topic, partition)
        private val c = consumer().also { it.assign(listOf(tp)) }
        fun readAll(): List<Pair<ByteArray?, ByteArray?>> = drain(c, tp, topic)

        private var started = false

        /**
         * The next read-committed records after the ones this reader has already returned (the first call starts at offset 0), at most about [max] per call
         * and none once it has caught up with the read-committed end. The incremental projection pass uses this so a pass costs the new records, not the
         * whole log. A topic that does not reach its end within the timeout is a failure, not a short read.
         */
        fun readNext(max: Int = 5_000): List<Pair<ByteArray?, ByteArray?>> {
            if (!started) { c.seekToBeginning(listOf(tp)); started = true }
            val end = c.endOffsets(listOf(tp), readTimeout).getValue(tp)
            val out = ArrayList<Pair<ByteArray?, ByteArray?>>()
            val deadline = System.nanoTime() + readTimeout.toNanos()
            while (c.position(tp, readTimeout) < end && out.size < max) {
                c.poll(Duration.ofMillis(200)).forEach { out += it.key() to it.value() }
                check(System.nanoTime() < deadline) { "timed out reading $topic" }
            }
            return out
        }
        override fun close() = c.close()
    }
    fun logReader(topic: String, partition: Int = 0): LogReader = LogReader(topic, partition)

    /**
     * The last read-committed data record of one partition, or null for an empty partition. Transaction markers occupy offsets without being records, so the read starts a
     * little before the end and backs off until it finds a record or reaches the start; a timeout is a failure, never "empty".
     */
    fun lastValue(topic: String, partition: Int): ByteArray? = consumer().use { c ->
        val tp = TopicPartition(topic, partition); c.assign(listOf(tp))
        val start = c.beginningOffsets(listOf(tp), readTimeout).getValue(tp)
        val end = c.endOffsets(listOf(tp), readTimeout).getValue(tp)
        var back = 64L
        while (end > start) {
            val from = maxOf(start, end - back)
            c.seek(tp, from)
            var last: ByteArray? = null
            val deadline = System.nanoTime() + readTimeout.toNanos()
            while (c.position(tp, readTimeout) < end) {
                c.poll(Duration.ofMillis(200)).forEach { last = it.value() }
                check(System.nanoTime() < deadline) { "timed out reading the tail of $topic-$partition" }
            }
            if (last != null) return@use last
            if (from == start) break
            back *= 8
        }
        null
    }

    /** First record a read-committed consumer sees at or after [offset], or null when there is none before the end. A timeout is a failure, never "none". */
    fun firstVisibleAtOrAfter(topic: String, offset: Long): Long? = withConsumer(topic) { c, tp ->
        c.seek(tp, offset)
        val end = c.endOffsets(listOf(tp), readTimeout).getValue(tp)
        val deadline = System.nanoTime() + readTimeout.toNanos()
        while (c.position(tp, readTimeout) < end) {
            c.poll(Duration.ofMillis(200)).firstOrNull()?.let { return@withConsumer it.offset() }
            check(System.nanoTime() < deadline) { "timed out probing $topic for a visible record at or after $offset" }
        }
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

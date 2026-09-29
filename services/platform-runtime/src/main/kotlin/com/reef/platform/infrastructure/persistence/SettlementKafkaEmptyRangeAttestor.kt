package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.Uuid
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.serialization.StringDeserializer

/** Absence evidence must come from a retained, read-committed command-topic interval. */
fun interface SettlementEmptyRangeAttestor {
    fun verify(eventStream: String, window: SettlementJournalSourceWindow): Boolean

    /** Read-committed last stable offset, mapped into canonical source sequence space. */
    fun stableEndSequence(eventStream: String, sourceGeneration: String,
        partitionId: Int, fromExclusiveSequence: Long): Long? = null
}

/** A durable source-generation binding is required before a Kafka topic can prove absence. */
fun interface SettlementCommandTopicIdentity {
    fun topicId(eventStream: String, sourceGeneration: String, commandTopic: String): String?
}

/**
 * Default-off broker verifier. Caller supplies a durable generation-to-topic-ID binding.
 * Committed command records are matching's source positions, unlike venue-event batch offsets.
 */
class SettlementKafkaEmptyRangeAttestor(
    private val eventStream: String,
    private val commandTopic: String,
    private val bootstrapServers: String,
    private val topicIdentity: SettlementCommandTopicIdentity,
    private val timeout: Duration = Duration.ofSeconds(5),
    private val broker: SettlementKafkaEmptyRangeBroker =
        LiveSettlementKafkaEmptyRangeBroker(bootstrapServers, timeout)
) : SettlementEmptyRangeAttestor {
    init {
        require(eventStream.isNotBlank() && commandTopic.isNotBlank() && bootstrapServers.isNotBlank() &&
            timeout.toMillis() in 1..60_000)
    }

    override fun verify(eventStream: String, window: SettlementJournalSourceWindow): Boolean {
        if (eventStream != this.eventStream || window.sourceGeneration.isBlank() ||
            window.partitionId !in 0..32767 || window.members.isNotEmpty()
        ) return false
        val origin = CanonicalStreamPosition.origin(window.partitionId)
        val startOffset = window.fromExclusiveSequence - origin
        val endOffsetExclusive = window.throughInclusiveSequence - origin
        if (startOffset < 0 || endOffsetExclusive <= startOffset ||
            endOffsetExclusive - startOffset > 5000 || endOffsetExclusive >= (1L shl 48)
        ) return false
        return try {
            val pinnedTopicId = topicIdentity.topicId(eventStream, window.sourceGeneration, commandTopic)
                ?: return false
            if (Uuid.fromString(pinnedTopicId) in Uuid.RESERVED) return false
            broker.hasNoCommittedRecords(commandTopic, pinnedTopicId, window.partitionId,
                startOffset, endOffsetExclusive) &&
                topicIdentity.topicId(eventStream, window.sourceGeneration, commandTopic) == pinnedTopicId
        } catch (_: Exception) {
            false
        }
    }

    override fun stableEndSequence(eventStream: String, sourceGeneration: String,
        partitionId: Int, fromExclusiveSequence: Long): Long? {
        require(eventStream == this.eventStream && sourceGeneration.isNotBlank() &&
            partitionId in 0..32767)
        val origin = CanonicalStreamPosition.origin(partitionId)
        val fromOffset = fromExclusiveSequence - origin
        require(fromOffset in 0 until (1L shl 48))
        val pinnedTopicId = topicIdentity.topicId(eventStream, sourceGeneration, commandTopic)
            ?: error("settlement source topic binding is missing")
        check(Uuid.fromString(pinnedTopicId) !in Uuid.RESERVED) {
            "settlement source topic ID is reserved"
        }
        val stableEnd = broker.stableEndOffset(commandTopic, pinnedTopicId, partitionId, fromOffset)
            ?: return null
        check(stableEnd in fromOffset until (1L shl 48) &&
            topicIdentity.topicId(eventStream, sourceGeneration, commandTopic) == pinnedTopicId) {
            "settlement command topic stable end or binding changed"
        }
        return origin + stableEnd
    }
}

/** Small broker boundary permits deterministic hole, retention and topic-replacement tests. */
interface SettlementKafkaEmptyRangeBroker {
    fun hasNoCommittedRecords(topic: String, pinnedTopicId: String, partitionId: Int,
        startOffset: Long, endOffsetExclusive: Long): Boolean

    fun stableEndOffset(topic: String, pinnedTopicId: String, partitionId: Int,
        fromOffset: Long): Long? = null
}

private class LiveSettlementKafkaEmptyRangeBroker(
    bootstrapServers: String,
    private val timeout: Duration
) : SettlementKafkaEmptyRangeBroker {
    private val adminProperties = Properties().apply { put("bootstrap.servers", bootstrapServers) }
    private val consumerProperties = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "reef-settlement-empty-range-proof")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none")
        put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
        put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeout.toMillis().toString())
    }
    // Candidate writer polls every 50 ms; retain one client pair instead of recreating both
    // across each of 16 idle partitions. KafkaConsumer is not thread-safe, so both uses lock.
    private val adminClient by lazy { AdminClient.create(adminProperties) }
    private val consumerClient by lazy { KafkaConsumer<String, String>(consumerProperties) }

    @Synchronized
    override fun hasNoCommittedRecords(topic: String, pinnedTopicId: String, partitionId: Int,
        startOffset: Long, endOffsetExclusive: Long): Boolean =
        if (!topicMatches(adminClient, topic, pinnedTopicId, partitionId)) false else {
            val partition = TopicPartition(topic, partitionId)
            consumerClient.assign(listOf(partition))
            scanReadCommittedCommandOffsets(consumerClient, partition, startOffset,
                endOffsetExclusive, timeout) &&
                consumerClient.beginningOffsets(listOf(partition)).getValue(partition) <= startOffset &&
                topicMatches(adminClient, topic, pinnedTopicId, partitionId)
        }

    @Synchronized
    override fun stableEndOffset(topic: String, pinnedTopicId: String, partitionId: Int,
        fromOffset: Long): Long {
        check(topicMatches(adminClient, topic, pinnedTopicId, partitionId)) {
            "settlement command topic identity or retention changed"
        }
        val partition = TopicPartition(topic, partitionId)
        consumerClient.assign(listOf(partition))
        val beginning = consumerClient.beginningOffsets(listOf(partition)).getValue(partition)
        val stableEnd = consumerClient.endOffsets(listOf(partition)).getValue(partition)
        check(beginning <= fromOffset && stableEnd >= fromOffset) {
            "settlement command topic lost required source offsets"
        }
        check(topicMatches(adminClient, topic, pinnedTopicId, partitionId)) {
            "settlement command topic identity or retention changed during stable-end read"
        }
        return stableEnd
    }

    private fun topicMatches(admin: AdminClient, topic: String, pinnedTopicId: String,
        partitionId: Int): Boolean {
        val description = admin.describeTopics(listOf(topic)).allTopicNames()
            .get(timeout.toMillis(), TimeUnit.MILLISECONDS)[topic] ?: return false
        if (description.topicId().toString() != pinnedTopicId ||
            description.partitions().none { it.partition() == partitionId }
        ) return false
        val resource = ConfigResource(ConfigResource.Type.TOPIC, topic)
        val config = admin.describeConfigs(listOf(resource)).all()
            .get(timeout.toMillis(), TimeUnit.MILLISECONDS)[resource] ?: return false
        // Compaction can remove a committed record without moving the beginning offset.
        return config.get("cleanup.policy")?.value() == "delete"
    }
}

/** Consumer must be configured read_committed; caller separately pins topic ID and delete-only retention. */
internal fun scanReadCommittedCommandOffsets(
    consumer: Consumer<String, String>, partition: TopicPartition,
    startOffset: Long, endOffsetExclusive: Long, timeout: Duration
): Boolean {
    if (consumer.beginningOffsets(listOf(partition)).getValue(partition) > startOffset ||
        consumer.endOffsets(listOf(partition)).getValue(partition) < endOffsetExclusive
    ) return false
    consumer.seek(partition, startOffset)
    val deadline = System.nanoTime() + timeout.toNanos()
    while (consumer.position(partition) < endOffsetExclusive) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) return false
        val records = consumer.poll(Duration.ofNanos(remaining))
        if (records.records(partition).any { it.offset() in startOffset until endOffsetExclusive }) {
            return false
        }
    }
    return true
}

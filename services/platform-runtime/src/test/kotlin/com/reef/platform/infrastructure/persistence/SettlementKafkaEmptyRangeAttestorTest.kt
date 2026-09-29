package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.Uuid

class SettlementKafkaEmptyRangeAttestorTest {
    private val partition = TopicPartition("REEF_COMMANDS", 3)

    @Test
    fun retainedReadCommittedHoleCanBeProvedEmpty() {
        val consumer = consumer(beginning = 0, end = 10)
        consumer.schedulePollTask { consumer.seek(partition, 5) }
        consumer.use {
            assertTrue(scanReadCommittedCommandOffsets(it, partition, 3, 5,
                Duration.ofMillis(100)))
        }
    }

    @Test
    fun visibleCommandInRangeRejectsAbsence() {
        val consumer = consumer(beginning = 0, end = 10)
        consumer.addRecord(ConsumerRecord("REEF_COMMANDS", 3, 4, "command", "payload"))
        consumer.use {
            assertFalse(scanReadCommittedCommandOffsets(it, partition, 3, 5,
                Duration.ofMillis(100)))
        }
    }

    @Test
    fun retentionAndUnstableEndRejectAbsence() {
        consumer(beginning = 4, end = 10).use {
            assertFalse(scanReadCommittedCommandOffsets(it, partition, 3, 5,
                Duration.ofMillis(100)))
        }
        consumer(beginning = 0, end = 4).use {
            assertFalse(scanReadCommittedCommandOffsets(it, partition, 3, 5,
                Duration.ofMillis(100)))
        }
    }

    @Test
    fun noReadProgressRejectsAbsence() {
        consumer(beginning = 0, end = 10).use {
            assertFalse(scanReadCommittedCommandOffsets(it, partition, 3, 5,
                Duration.ofMillis(1)))
        }
    }

    @Test
    fun stableEndMapsReadCommittedOffsetThroughPinnedTopicIdentity() {
        val id = Uuid.randomUuid().toString()
        var currentId = id
        val broker = object : SettlementKafkaEmptyRangeBroker {
            override fun hasNoCommittedRecords(topic: String, pinnedTopicId: String,
                partitionId: Int, startOffset: Long, endOffsetExclusive: Long) = true
            override fun stableEndOffset(topic: String, pinnedTopicId: String,
                partitionId: Int, fromOffset: Long): Long? {
                assertEquals(3, partitionId)
                assertEquals(5L, fromOffset)
                assertEquals(id, pinnedTopicId)
                return 7L
            }
        }
        val attestor = SettlementKafkaEmptyRangeAttestor("stream", "REEF_COMMANDS",
            "unused-bootstrap", SettlementCommandTopicIdentity { _, _, _ -> currentId },
            broker = broker)
        val origin = CanonicalStreamPosition.origin(3)
        assertEquals(origin + 7, attestor.stableEndSequence("stream", "generation", 3,
            origin + 5))
        val racing = object : SettlementKafkaEmptyRangeBroker by broker {
            override fun stableEndOffset(topic: String, pinnedTopicId: String,
                partitionId: Int, fromOffset: Long): Long? {
                currentId = Uuid.randomUuid().toString()
                return 7L
            }
        }
        val changed = SettlementKafkaEmptyRangeAttestor("stream", "REEF_COMMANDS",
            "unused-bootstrap", SettlementCommandTopicIdentity { _, _, _ -> currentId },
            broker = racing)
        currentId = id
        assertFailsWith<IllegalStateException> {
            changed.stableEndSequence("stream", "generation", 3, origin + 5)
        }
    }

    private fun consumer(beginning: Long, end: Long): MockConsumer<String, String> =
        MockConsumer<String, String>(OffsetResetStrategy.NONE).also {
            it.assign(listOf(partition))
            it.updateBeginningOffsets(mapOf(partition to beginning))
            it.updateEndOffsets(mapOf(partition to end))
        }
}

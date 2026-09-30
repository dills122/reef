package com.reef.platform.calcify

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import kotlin.test.Test
import kotlin.test.assertEquals

class CalcifyReceiptBatchTest {
    @Test
    fun validReceiptsKeepPartitionOrderInOnePollPlan() {
        val blocked = mutableSetOf<TopicPartition>()
        val records = (0L until 100L).map { offset ->
            record(3, offset, CommitmentId(7, 3, 12, offset.toInt()))
        }

        val plan = CalcifyPipeline.planReceiptPoll(records, blocked)

        assertEquals((0L until 100L).toList(), plan.valid.getValue(TopicPartition("verified", 3)).map { it.first })
        assertEquals(0, plan.poisoned.size)
        assertEquals(emptySet(), blocked)
    }

    @Test
    fun malformedReceiptStopsOnlyItsPartitionAndKeepsValidPrefix() {
        val blocked = mutableSetOf<TopicPartition>()
        val records = listOf(
            record(1, 5, CommitmentId(7, 1, 12, 0)),
            record(1, 6, CommitmentId(7, 2, 12, 1)),
            record(1, 7, CommitmentId(7, 1, 12, 2)),
            record(2, 8, CommitmentId(7, 2, 13, 0)),
        )

        val plan = CalcifyPipeline.planReceiptPoll(records, blocked)

        assertEquals(listOf(5L), plan.valid.getValue(TopicPartition("verified", 1)).map { it.first })
        assertEquals(listOf(8L), plan.valid.getValue(TopicPartition("verified", 2)).map { it.first })
        assertEquals(setOf(TopicPartition("verified", 1)), blocked)
        assertEquals(listOf(6L), plan.poisoned.map { it.second })
    }

    private fun record(partition: Int, offset: Long, id: CommitmentId) =
        ConsumerRecord<ByteArray, ByteArray>(
            "verified", partition, offset, null,
            CalcifyWire.passed(CommitmentVerificationPassed(id, 1))
        )
}

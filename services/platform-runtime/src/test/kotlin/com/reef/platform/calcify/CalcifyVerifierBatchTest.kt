package com.reef.platform.calcify

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import kotlin.test.Test
import kotlin.test.assertEquals

class CalcifyVerifierBatchTest {
    @Test
    fun validLinksShareOneCheckpointPlanPerPoll() {
        val blocked = mutableSetOf<TopicPartition>()
        val records = (0L until 100L).map { offset ->
            record(3, offset, CommitmentId(7, 3, 12, offset.toInt()))
        }

        val plan = CalcifyPipeline.planVerifierPoll(records, "verified", blocked)

        assertEquals(100, plan.outputs.size)
        assertEquals(100L, plan.offsets.getValue(TopicPartition("commitments", 3)).offset())
        assertEquals(0, plan.poisoned.size)
        assertEquals((0 until 100).toList(), plan.outputs.map {
            CalcifyWire.readPassed(it.value()).commitmentId.tradeOrdinal
        })
    }

    @Test
    fun malformedLinkStopsOnlyItsPartitionWithoutSkippingValidPrefix() {
        val blocked = mutableSetOf<TopicPartition>()
        val records = listOf(
            record(1, 5, CommitmentId(7, 1, 12, 0)),
            record(1, 6, CommitmentId(7, 2, 12, 1)),
            record(1, 7, CommitmentId(7, 1, 12, 2)),
            record(2, 8, CommitmentId(7, 2, 13, 0)),
        )

        val plan = CalcifyPipeline.planVerifierPoll(records, "verified", blocked)

        assertEquals(2, plan.outputs.size)
        assertEquals(setOf(TopicPartition("commitments", 1)), blocked)
        assertEquals(setOf(TopicPartition("commitments", 1)), plan.poisoned.map { it.first }.toSet())
        assertEquals(6L, plan.offsets.getValue(TopicPartition("commitments", 1)).offset())
        assertEquals(9L, plan.offsets.getValue(TopicPartition("commitments", 2)).offset())
        assertEquals(listOf(1, 2), plan.outputs.map { it.partition() })
    }

    private fun record(partition: Int, offset: Long, id: CommitmentId) =
        ConsumerRecord<ByteArray, ByteArray>(
            "commitments", partition, offset, null, CalcifyWire.commitment(id)
        )
}

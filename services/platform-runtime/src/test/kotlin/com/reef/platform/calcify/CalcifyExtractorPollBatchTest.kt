package com.reef.platform.calcify

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import kotlin.test.*

class CalcifyExtractorPollBatchTest {
    private val mapper = JsonMapper.builder().build()
    private val fixtures = CalcifySourceFixtures.currentBodies()

    @Test fun hundredSourceRecordsShareExactPartitionCheckpointsAndReplayBytes() {
        val records = (0L until 100L).map { offset -> record(0, offset, body(1, 0)) }
        val plan = CalcifyPipeline.planExtractorPoll(records, "source", "commitments", 7, mutableSetOf())
        assertEquals(100, plan.outputs.size)
        assertEquals(mapOf(TopicPartition("source", 0) to 100L), plan.offsets.mapValues { it.value.offset() })
        assertTrue(plan.poisoned.isEmpty())
        assertEquals((0L until 100L).map { CommitmentId(7, 0, it, 0) }, plan.outputs.map { CalcifyWire.readCommitment(it.value()) })
        assertTrue(plan.outputs.all { it.topic() == "commitments" && it.partition() == 0 && it.key() == null })
        val replay = CalcifyPipeline.planExtractorPoll(records, "source", "commitments", 7, mutableSetOf())
        plan.outputs.zip(replay.outputs).forEach { (first, second) -> assertContentEquals(first.value(), second.value()) }
    }

    @Test fun multipleTradesResetOrdinalPerSourceOffsetAndRestingBatchStillCheckpoints() {
        val plan = CalcifyPipeline.planExtractorPoll(listOf(
            record(0, 40, body(0, 0)), record(0, 41, body(4, 0)), record(0, 42, body(1, 0)),
        ), "source", "commitments", 7, mutableSetOf())
        assertEquals((0 until 128).map { CommitmentId(7, 0, 41, it) } + CommitmentId(7, 0, 42, 0),
            plan.outputs.map { CalcifyWire.readCommitment(it.value()) })
        assertEquals(43L, plan.offsets.getValue(TopicPartition("source", 0)).offset())
        val resting = CalcifyPipeline.planExtractorPoll(listOf(record(0, 50, body(0, 0))), "source", "commitments", 7, mutableSetOf())
        assertTrue(resting.outputs.isEmpty())
        assertEquals(51L, resting.offsets.getValue(TopicPartition("source", 0)).offset())
    }

    @Test fun poisonDoesNotLeakPartialBatchOrSkipValidPrefixAndOtherPartition() {
        // Checksum remains valid, but final nested trade lacks a required field.
        // Earlier trades in that source record must not become partial output.
        val poison = body(4, 1) { root ->
            val trades = root.get("outcomes").flatMap { it.get("result").get("trades")?.toList() ?: emptyList() }
            (trades.last() as ObjectNode).remove("executionId")
        }
        val blocked = mutableSetOf<TopicPartition>()
        val plan = CalcifyPipeline.planExtractorPoll(listOf(
            record(1, 5, body(1, 1)), record(1, 6, poison), record(1, 7, body(1, 1)),
            record(2, 8, body(1, 2)), record(2, 9, body(0, 2)),
        ), "source", "commitments", 7, blocked)
        assertEquals(listOf(CommitmentId(7, 1, 5, 0), CommitmentId(7, 2, 8, 0)), plan.outputs.map { CalcifyWire.readCommitment(it.value()) })
        assertEquals(mapOf(TopicPartition("source", 1) to 6L, TopicPartition("source", 2) to 10L), plan.offsets.mapValues { it.value.offset() })
        assertEquals(setOf(TopicPartition("source", 1)), blocked)
        assertEquals(listOf(TopicPartition("source", 1) to 6L), plan.poisoned.map { it.first to it.second })
        val followup = CalcifyPipeline.planExtractorPoll(listOf(record(1, 8, body(1, 1))), "source", "commitments", 7, blocked)
        assertTrue(followup.outputs.isEmpty()); assertTrue(followup.offsets.isEmpty())
    }

    @Test fun corruptFirstRecordAndTombstoneNeverAdvancePoisonedPartition() {
        val blocked = mutableSetOf<TopicPartition>()
        val plan = CalcifyPipeline.planExtractorPoll(listOf(
            record(1, 10, "not-json".toByteArray()), record(1, 11, body(1, 1)),
            record(2, 20, null), record(2, 21, body(1, 2)), record(3, 30, body(0, 3)),
        ), "source", "commitments", 7, blocked)
        assertTrue(plan.outputs.isEmpty())
        assertEquals(mapOf(TopicPartition("source", 3) to 31L), plan.offsets.mapValues { it.value.offset() })
        assertEquals(setOf(TopicPartition("source", 1), TopicPartition("source", 2)), blocked)
        assertEquals(listOf(10L, 20L), plan.poisoned.map { it.second })
    }

    private fun body(index: Int, partition: Int, change: (ObjectNode) -> Unit = {}): ByteArray {
        val root = mapper.readTree(fixtures[index]) as ObjectNode
        root.put("partition", partition)
        change(root)
        return CalcifySourceFixtures.rechecksum(root.toString()).toByteArray(Charsets.UTF_8)
    }

    private fun record(partition: Int, offset: Long, payload: ByteArray?) =
        ConsumerRecord<ByteArray, ByteArray>("source", partition, offset, null, payload)
}

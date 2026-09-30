package com.reef.platform.calcify

import com.reef.platform.api.JsonCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CalcifyContractTest {
    private val excluded = setOf(
        "createdAt", "workFinishedAt", "timingChecksum", "payloadChecksum", "payloadChecksumAlgorithm"
    )

    private fun sourceBatch(vararg tradeCounts: Int, corrupt: Boolean = false): String {
        val outcomes = tradeCounts.mapIndexed { index, count ->
            mapOf(
                "commandId" to "command-" + index,
                "streamSequence" to index + 1,
                "result" to mapOf(
                    "trades" to (0 until count).map { trade ->
                        mapOf("tradeId" to "trade-" + index + "-" + trade, "executionId" to "execution-" + trade)
                    }
                )
            )
        }
        val fields = arrayOf(
            "batchId" to "batch-1",
            "partition" to 2,
            "eventStream" to "REEF_VENUE_EVENTS",
            "commandCount" to tradeCounts.size,
            "firstSequence" to if (tradeCounts.isEmpty()) 0 else 1,
            "lastSequence" to tradeCounts.size,
            "outcomes" to outcomes
        )
        val body = JsonCodec.writeObject(*fields)
        val checksum = JsonCodec.parseObject(body).semanticSha256(excluded)
        return JsonCodec.writeObject(
            *fields,
            "payloadChecksumAlgorithm" to "sha256-reef-canonical-v1",
            "payloadChecksum" to if (corrupt) "bad" else checksum
        )
    }

    @Test
    fun compactWireAndStubPolicy() {
        val id = CommitmentId(7, 2, 123456789L, 42)
        val bytes = CalcifyWire.commitment(id)
        assertEquals(21, bytes.size)
        assertEquals("01000000070000000200000000075bcd150000002a", bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        assertEquals(id, CalcifyWire.readCommitment(bytes))
        val passed = CalcifyWire.stubVerify(bytes)
        assertEquals(CalcifyWire.STUB_POLICY_VERSION, passed.policyVersion)
        assertEquals(23, CalcifyWire.passed(passed).size)
        assertEquals(passed, CalcifyWire.readPassed(CalcifyWire.passed(passed)))
        assertFailsWith<IllegalArgumentException> { CalcifyWire.readCommitment(bytes.copyOf(20)) }
        assertFailsWith<IllegalArgumentException> { CalcifyWire.readCommitment(bytes.also { it[0] = 2 }) }
    }

    @Test
    fun zeroOneAndManyTradesFlattenInSourceOrder() {
        val zero = CalcifySourceBatch.extract(sourceBatch(), "REEF_VENUE_EVENTS", 1, 2, 80)
        val one = CalcifySourceBatch.extract(sourceBatch(1), "REEF_VENUE_EVENTS", 1, 2, 81)
        val many = CalcifySourceBatch.extract(sourceBatch(2, 0, 3), "REEF_VENUE_EVENTS", 1, 2, 82)
        assertTrue(zero.isEmpty())
        assertEquals(listOf(CommitmentId(1, 2, 81, 0)), one)
        assertEquals((0..4).map { CommitmentId(1, 2, 82, it) }, many)
        val assembled = many.map(CalcifyWire::commitment).map(CalcifyWire::stubVerify)
            .map(CalcifyWire::passed).map(CalcifyWire::readPassed)
        assertEquals(many, assembled.map { it.commitmentId })
    }

    @Test
    fun corruptOrMisroutedSourceFailsClosed() {
        assertFailsWith<IllegalArgumentException> {
            CalcifySourceBatch.extract(sourceBatch(1, corrupt = true), "REEF_VENUE_EVENTS", 1, 2, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            CalcifySourceBatch.extract(sourceBatch(1), "REEF_VENUE_EVENTS", 1, 3, 1)
        }
        val original = sourceBatch(1)
        val altered = original.replace("\"executionId\":\"execution-0\"", "\"executionId\":\"\"")
        val malformed = altered.replace(
            JsonCodec.parseObject(original).string("payloadChecksum"),
            JsonCodec.parseObject(altered).semanticSha256(excluded)
        )
        assertFailsWith<IllegalArgumentException> {
            CalcifySourceBatch.extract(malformed, "REEF_VENUE_EVENTS", 1, 2, 1)
        }
    }
}

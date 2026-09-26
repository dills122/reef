package com.reef.platform.application.postmatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalSourceCoverageVerifierTest {
    private val verifier = CanonicalSourceCoverageVerifier()

    @Test
    fun verifiesEveryPositionAndProducesStableSemanticDigests() {
        val first = outcome(1, """{"effectVersion":1,"rejected":{"eventId":"r1","orderId":"o1","code":"R","reason":"bad","occurredAt":"t"}}""")
        val second = outcome(2, """{"effectVersion":1,"rejected":{"code":"POISON","reason":"bad"}}""", "failed")
        val verified = verifier.verify("live", "venue-commands", 0, "generation-1", 0, 2, listOf(first, second))
        val reformatted = first.copy(resultPayloadJson = """{ "rejected": { "reason":"bad", "occurredAt":"t", "code":"R", "orderId":"o1", "eventId":"r1" }, "effectVersion":1 }""")
        val replay = verifier.verify("live", "venue-commands", 0, "generation-1", 0, 2, listOf(reformatted, second))

        assertEquals(listOf(1L, 2L), verified.outcomes.map { it.source.streamSequence })
        assertEquals(verified.sourceDigest, replay.sourceDigest)
        assertEquals(verified.outcomes.map { it.resultDigest }, replay.outcomes.map { it.resultDigest })
        assertEquals(64, verified.sourceDigest.length)
    }

    @Test
    fun rejectsMissingDuplicateAndOutOfOrderCanonicalPositions() {
        val first = outcome(1, failedResult, "failed")
        val third = outcome(3, failedResult, "failed")
        assertFailsWith<IllegalArgumentException> {
            verifier.verify("live", "venue-commands", 0, "generation-1", 0, 3, listOf(first, third))
        }
        assertFailsWith<IllegalArgumentException> {
            verifier.verify("live", "venue-commands", 0, "generation-1", 0, 2, listOf(first, first))
        }
        assertFailsWith<IllegalArgumentException> {
            verifier.verify("live", "venue-commands", 0, "generation-1", 0, 2, listOf(third.copy(streamSequence = 2), first))
        }
    }

    @Test
    fun rejectsOtherStreamsAndUnversionedOutcomes() {
        val first = outcome(1, failedResult, "failed")
        assertFailsWith<IllegalArgumentException> {
            verifier.verify("live", "venue-commands", 0, "generation-1", 0, 1, listOf(first.copy(eventStream = "other")))
        }
        assertFailsWith<IllegalArgumentException> {
            verifier.verify("live", "venue-commands", 0, "generation-1", 0, 1, listOf(first.copy(resultPayloadJson = "{}")))
        }
    }

    @Test
    fun normalizesExactNumbersAndRejectsDuplicateJsonKeys() {
        val first = outcome(1, """{"effectVersion":1,"measure":9007199254740993.00,"rejected":{"eventId":"r1","orderId":"o1","code":"R","reason":"bad","occurredAt":"t"}}""")
        val equivalent = first.copy(resultPayloadJson = """{"rejected":{"occurredAt":"t","reason":"bad","code":"R","orderId":"o1","eventId":"r1"},"measure":9007199254740993e0,"effectVersion":1}""")
        val a = verifier.verify("live", "venue-commands", 0, "generation-1", 0, 1, listOf(first))
        val b = verifier.verify("live", "venue-commands", 0, "generation-1", 0, 1, listOf(equivalent))
        assertEquals(a.outcomes.single().resultDigest, b.outcomes.single().resultDigest)
        val largeExponent = first.copy(resultPayloadJson = first.resultPayloadJson.replace("9007199254740993.00", "1e1000"))
        val equivalentExponent = first.copy(resultPayloadJson = first.resultPayloadJson.replace("9007199254740993.00", "10e999"))
        assertEquals(
            verifier.verify("live", "venue-commands", 0, "generation-1", 0, 1, listOf(largeExponent)).sourceDigest,
            verifier.verify("live", "venue-commands", 0, "generation-1", 0, 1, listOf(equivalentExponent)).sourceDigest
        )

        val duplicate = first.copy(resultPayloadJson = first.resultPayloadJson.replace("\"measure\":", "\"measure\":1,\"measure\":"))
        assertFailsWith<IllegalArgumentException> {
            verifier.verify("live", "venue-commands", 0, "generation-1", 0, 1, listOf(duplicate))
        }
    }

    private fun outcome(sequence: Long, result: String, status: String = "rejected") = CanonicalOutcomeSource(
        eventStream = "venue-commands",
        partitionId = 0,
        streamSequence = sequence,
        batchId = "batch-$sequence",
        commandId = if (status == "failed") "" else "command-$sequence",
        commandType = "SubmitOrder",
        payloadHash = "hash-$sequence",
        instrumentId = "AAPL",
        orderId = "o$sequence",
        resultStatus = status,
        resultPayloadJson = result
    )

    private val failedResult = """{"effectVersion":1,"rejected":{"code":"POISON","reason":"bad"}}"""
}

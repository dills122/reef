package com.reef.platform.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettlementCandidatePollTimingTest {
    @Test
    fun aggregatesPollsAndStagesOncePerIntervalThenResets() {
        var now = 0L
        val lines = mutableListOf<String>()
        val timing = SettlementCandidatePollTiming(1_000_000_000L, { now }, lines::add)

        val idle = timing.begin()
        idle.record(SettlementCandidatePollTiming.Stage.SOURCE_HEADS, 2_000_000L)
        now = 500_000_000L
        idle.complete(durable = false, failed = false, results = 0)
        assertTrue(lines.isEmpty())

        val committed = timing.begin()
        committed.record(SettlementCandidatePollTiming.Stage.SOURCE_HEADS, 3_000_000L)
        committed.record(SettlementCandidatePollTiming.Stage.APPEND_FINALITY, 8_000_000L)
        now = 1_200_000_000L
        committed.complete(durable = true, failed = false, results = 2)

        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("polls=2 committed=1 idle=1 failures=0 results=2"))
        assertTrue(lines.single().contains("source_heads_calls=2 source_heads_total_us=5000 source_heads_max_us=3000"))
        assertTrue(lines.single().contains("append_finality_calls=1 append_finality_total_us=8000"))

        val failure = timing.begin()
        now = 1_300_000_000L
        failure.complete(durable = false, failed = true, results = 0)
        assertEquals(2, lines.size)
        assertTrue(lines.last().contains("polls=1 committed=0 idle=0 failures=1 results=0"))
        assertTrue(lines.last().contains("source_heads_calls=0 source_heads_total_us=0"))
    }

    @Test
    fun diagnosticOutputFailureCannotFailAnAcknowledgedPoll() {
        var now = 0L
        val timing = SettlementCandidatePollTiming(1L, { now }) {
            throw IllegalStateException("log sink unavailable")
        }
        val poll = timing.begin()
        now = 2L
        poll.complete(durable = true, failed = false, results = 1)
    }
}

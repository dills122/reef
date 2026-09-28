package com.reef.platform.infrastructure.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettlementJournalExternalAnchorGateTest {
    private val stream = "settlement-stream"
    private val state = SettlementJournalReplayState(
        SettlementJournalHead(3, "batch-2", 7, "incarnation-2", 4, "control-4"),
        emptyMap(), emptySet(), 0)
    private val anchor = SettlementJournalFinalityAnchor(
        stream, "incarnation-2", 2, "batch-2", 4, "control-4")

    @Test
    fun onlyExactExternallyAcknowledgedFrontierPasses() {
        SettlementJournalExternalAnchorGate.verify(stream, state, anchor)
        listOf(
            anchor.copy(eventStream = "other-stream"),
            anchor.copy(incarnationId = "incarnation-1"),
            anchor.copy(acknowledgedBatchSequence = 1),
            anchor.copy(acknowledgedBatchSequence = 3),
            anchor.copy(acknowledgedBatchDigest = "other-batch"),
            anchor.copy(controlSequence = 3),
            anchor.copy(controlDigest = "other-control")
        ).forEach { changed ->
            assertFailsWith<IllegalStateException> {
                SettlementJournalExternalAnchorGate.verify(stream, state, changed)
            }
        }
    }

    @Test
    fun readsStableAnchorAcrossReplayAndRejectsMissingOrChangedAnchor() {
        var reads = 0
        val stable = SettlementJournalFinalityAnchorReader { reads++; anchor }
        assertEquals(state, SettlementJournalExternalAnchorGate.proveWith(stream, stable) { state })
        assertEquals(2, reads)

        var replayed = false
        assertFailsWith<IllegalStateException> {
            SettlementJournalExternalAnchorGate.proveWith(stream,
                SettlementJournalFinalityAnchorReader { null }) {
                replayed = true
                state
            }
        }
        assertEquals(false, replayed)

        reads = 0
        val changing = SettlementJournalFinalityAnchorReader {
            reads++
            if (reads == 1) anchor else anchor.copy(acknowledgedBatchSequence = 3)
        }
        assertFailsWith<IllegalStateException> {
            SettlementJournalExternalAnchorGate.proveWith(stream, changing) { state }
        }
        assertEquals(2, reads)

        reads = 0
        val disappearing = SettlementJournalFinalityAnchorReader {
            reads++
            if (reads == 1) anchor else null
        }
        assertFailsWith<IllegalStateException> {
            SettlementJournalExternalAnchorGate.proveWith(stream, disappearing) { state }
        }
        assertEquals(2, reads)

        assertFailsWith<IllegalStateException> {
            SettlementJournalExternalAnchorGate.proveWith(stream, stable) {
                error("source replay rejected changed retained facts")
            }
        }
    }
}

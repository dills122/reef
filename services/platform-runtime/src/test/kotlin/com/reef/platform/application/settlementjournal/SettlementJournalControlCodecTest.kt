package com.reef.platform.application.settlementjournal

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettlementJournalControlCodecTest {
    private val account = ReferenceAccountKey("run", "participant", "account", "CASH", "USD")

    @Test
    fun policyOpeningAndFundingRoundTripWithExactBytes() {
        val policy = ReferencePolicyActivation(controlSequence = 1, controlId = "policy",
            runId = "run", venueSessionId = "session",
            effectiveAfterSourceFrontiers = linkedMapOf(
                ReferenceStreamPartition("stream", "generation", 3) to (3L shl 48),
                ReferenceStreamPartition("stream", "generation", 0) to 0L),
            profileId = "instant-post-trade-v1", policyVersion = 1,
            mode = "instant-post-trade", settlementCycle = "T+0",
            nettingMode = "gross", ledgerPostingMode = "gross-dvp",
            selectionSource = "fixture")
        val controls: List<ReferenceControl> = listOf(policy,
            ReferenceOpening(controlSequence = 2, controlId = "opening", account = account,
                amount = BigDecimal("100.00")),
            ReferenceFunding(controlSequence = 3, controlId = "funding", account = account,
                amount = BigDecimal("25.50"), retryTradeIds = listOf("trade-1", "trade-2")))
        controls.forEach { control ->
            val encoded = SettlementJournalControlCodec.encode(control)
            val decoded = SettlementJournalControlCodec.decode(encoded)
            assertEquals(control, decoded)
            assertContentEquals(encoded, SettlementJournalControlCodec.encode(decoded))
        }
        val reordered = policy.copy(effectiveAfterSourceFrontiers = policy.effectiveAfterSourceFrontiers
            .entries.reversed().associate { it.key to it.value })
        assertContentEquals(SettlementJournalControlCodec.encode(policy),
            SettlementJournalControlCodec.encode(reordered))
    }

    @Test
    fun changedOrTruncatedControlBytesFailClosed() {
        val encoded = SettlementJournalControlCodec.encode(ReferenceOpening(controlSequence = 1,
            controlId = "opening", account = account, amount = BigDecimal("1")))
        assertFailsWith<IllegalArgumentException> {
            SettlementJournalControlCodec.decode(encoded + byteArrayOf(0))
        }
        assertFailsWith<IllegalArgumentException> {
            SettlementJournalControlCodec.decode(encoded.copyOf(encoded.size - 1))
        }
        assertFailsWith<IllegalArgumentException> {
            SettlementJournalControlCodec.decode(byteArrayOf(0, 0, 0, -1))
        }
    }
}

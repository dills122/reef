package com.reef.platform.infrastructure.persistence

import com.reef.platform.application.postmatch.CanonicalOrderIdentity
import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceObligation
import com.reef.platform.application.settlementjournal.ReferenceSourcePosition
import com.reef.platform.application.settlementjournal.ReferenceStreamPartition
import com.reef.platform.application.settlementjournal.SettlementEvaluatorHead
import com.reef.platform.application.settlementjournal.SettlementEvaluatorRecoveryState
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettlementJournalWriterRecoveryCodecTest {
    @Test
    fun roundTripPreservesOpenObligationAndActiveOrderState() {
        val stream = ReferenceStreamPartition("venue-commands", "source-generation", 0)
        val buyer = ReferenceAccountKey("run", "buyer", "buyer-account", "CASH", "USD")
        val seller = ReferenceAccountKey("run", "seller", "seller-account", "SECURITY", "AAPL")
        val obligation = ReferenceObligation("trade-1", "event-1",
            ReferenceSourcePosition(stream, 7, 2), "a".repeat(64), "run", "session",
            buyer, seller, "AAPL", "USD", BigDecimal.ONE, BigDecimal("50.00"),
            Instant.parse("2026-09-28T00:00:00Z"), "policy-1", 1, "instant-post-trade")
        val order = CanonicalOrderIdentity("order-1", "engine-1", "client-1", "run",
            "session", "AAPL", "buyer", "buyer-account", "BUY", "LIMIT", "1",
            "50", "USD", "DAY", "2026-09-28T00:00:00Z")
        val state = SettlementJournalWriterRecoveryState(
            SettlementEvaluatorRecoveryState(
                SettlementEvaluatorHead(3, "b".repeat(64), 4, "journal-incarnation"),
                emptyList(), emptyMap(), emptyMap(), emptyMap(),
                mapOf(buyer to BigDecimal("0.00")),
                mapOf(obligation.tradeId to obligation), mapOf(obligation.tradeId to 2),
                mapOf(stream to 7L)),
            mapOf(order.orderId to order), setOf(order.orderId, "closed-order"),
            "c".repeat(64), "control-incarnation", mapOf(stream to 7L))
        val bytes = SettlementJournalWriterRecoveryCodec.encode(state)
        val decoded = SettlementJournalWriterRecoveryCodec.decode(bytes)
        assertEquals(state, decoded)
        assertContentEquals(bytes, SettlementJournalWriterRecoveryCodec.encode(decoded))
        assertFailsWith<IllegalStateException> {
            SettlementJournalWriterRecoveryCodec.decode(bytes + byteArrayOf(1))
        }
        assertFailsWith<IllegalStateException> {
            SettlementJournalWriterRecoveryCodec.decode(bytes.copyOf().also { it[7] = 0 })
        }
    }
}

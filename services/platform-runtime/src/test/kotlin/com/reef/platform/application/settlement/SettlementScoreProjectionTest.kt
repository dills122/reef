package com.reef.platform.application.settlement

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SettlementScoreProjectionTest {
    @Test
    fun projectsParticipantScoreFromSettledAndFailedObligations() {
        val score = SettlementScoreProjection.project(SettlementScenarioProofProjectionTest.settlementFacts())

        assertEquals("run-proof", score.scenarioRunId)
        assertEquals(2, score.participants.size)

        val buyer = score.participants.single { it.participantId == "buyer-1" }
        assertEquals("500", buyer.cashBalances.single { it.assetId == "USD" }.availableQuantity)
        assertEquals("10", buyer.securityBalances.single { it.assetId == "AAPL" }.availableQuantity)
        assertEquals("200", buyer.pendingValue)
        assertEquals("100", buyer.haircutAdjustedPendingValue)
        assertEquals("200", buyer.blockedUnsettledValue)
        assertEquals(100, buyer.scorePenaltyPoints)
        assertEquals(1, buyer.settledObligationCount)
        assertEquals(1, buyer.pendingObligationCount)
        assertEquals(1, buyer.failedObligationCount)
        assertEquals(0, buyer.agedFailCount)
        assertEquals(1, buyer.openBreakCount)
        assertEquals(1, buyer.repairPendingCount)

        val seller = score.participants.single { it.participantId == "seller-1" }
        assertEquals("500", seller.cashBalances.single { it.assetId == "USD" }.availableQuantity)
        assertEquals("10", seller.securityBalances.single { it.assetId == "AAPL" }.availableQuantity)
        assertEquals("200", seller.pendingValue)
        assertEquals("100", seller.haircutAdjustedPendingValue)
        assertEquals("200", seller.blockedUnsettledValue)
        assertEquals(100, seller.scorePenaltyPoints)
    }

    @Test
    fun projectsAgedFailsFromScenarioClock() {
        val score = SettlementScoreProjection.project(
            facts = SettlementScenarioProofProjectionTest.settlementFacts(),
            options = SettlementScoreProjectionOptions(
                asOf = Instant.parse("2026-01-03T00:00:06Z"),
                agedFailAfterSeconds = 86_400
            )
        )

        val buyer = score.participants.single { it.participantId == "buyer-1" }
        assertEquals(Instant.parse("2026-01-03T00:00:06Z"), score.asOf)
        assertEquals(86_400, score.agedFailAfterSeconds)
        assertEquals(1, buyer.agedFailCount)
        assertEquals(350, buyer.scorePenaltyPoints)
    }
}

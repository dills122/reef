package com.reef.platform.application.settlement

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SettlementObligationProjectionTest {
    @Test
    fun projectsCurrentObligationStateFromFactBundle() {
        val facts = SettlementFactBundle(
            scenarioRunId = "run-1",
            obligations = listOf(obligation()),
            breaks = listOf(breakOpened()),
            repairs = listOf(repairPosted()),
            resolutions = listOf(resolved())
        )

        val view = SettlementObligationProjection.project(facts).single()

        assertEquals("obl-1", view.settlementObligationId)
        assertEquals("RESOLVED", view.settlementState)
        assertEquals("RESOLVED", view.exceptionState)
        assertEquals("", view.settlementAttemptId)
        assertEquals(0, view.settlementAttemptNumber)
        assertEquals("break-1", view.settlementBreakId)
        assertEquals("repair-1", view.settlementRepairId)
        assertEquals("resolution-1", view.settlementResolutionId)
        assertEquals(Instant.parse("2026-01-01T00:00:03Z"), view.updatedAt)
    }

    @Test
    fun projectsUnbrokenObligationWithNoExceptionState() {
        val view = SettlementObligationProjection.project(
            SettlementFactBundle(
                scenarioRunId = "run-1",
                obligations = listOf(obligation())
            )
        ).single()

        assertEquals("OBLIGATION_CREATED", view.settlementState)
        assertEquals("NONE", view.exceptionState)
        assertEquals("", view.settlementAttemptId)
        assertEquals(0, view.settlementAttemptNumber)
        assertEquals("", view.settlementBreakId)
    }

    @Test
    fun projectsAttemptStartedStateBeforeBreaksOrResolution() {
        val view = SettlementObligationProjection.project(
            SettlementFactBundle(
                scenarioRunId = "run-1",
                obligations = listOf(obligation()),
                attempts = listOf(attemptStarted())
            )
        ).single()

        assertEquals("ATTEMPT_STARTED", view.settlementState)
        assertEquals("NONE", view.exceptionState)
        assertEquals("attempt-1", view.settlementAttemptId)
        assertEquals(1, view.settlementAttemptNumber)
        assertEquals(Instant.parse("2026-01-01T00:00:01Z"), view.updatedAt)
    }

    private fun obligation(): SettlementObligationCreatedFact {
        return SettlementObligationCreatedFact(
            settlementObligationId = "obl-1",
            scenarioRunId = "run-1",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "trade-1",
            tradeId = "trade-1",
            buyerParticipantId = "buyer-1",
            sellerParticipantId = "seller-1",
            instrumentId = "AAPL",
            quantity = "100",
            cashAmount = "15025000000000",
            currency = "USD",
            occurredAt = Instant.parse("2026-01-01T00:00:00Z")
        )
    }

    private fun attemptStarted(): SettlementAttemptStartedFact {
        return SettlementAttemptStartedFact(
            settlementAttemptId = "attempt-1",
            settlementObligationId = "obl-1",
            scenarioRunId = "run-1",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "obl-1",
            occurredAt = Instant.parse("2026-01-01T00:00:01Z")
        )
    }

    private fun breakOpened(): SettlementBreakOpenedFact {
        return SettlementBreakOpenedFact(
            settlementBreakId = "break-1",
            settlementObligationId = "obl-1",
            scenarioRunId = "run-1",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "trade-1",
            occurredAt = Instant.parse("2026-01-01T00:00:01Z")
        )
    }

    private fun repairPosted(): SettlementRepairPostedFact {
        return SettlementRepairPostedFact(
            settlementRepairId = "repair-1",
            settlementBreakId = "break-1",
            settlementObligationId = "obl-1",
            scenarioRunId = "run-1",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "trade-1",
            actorId = "ops-1",
            occurredAt = Instant.parse("2026-01-01T00:00:02Z")
        )
    }

    private fun resolved(): SettlementResolvedFact {
        return SettlementResolvedFact(
            settlementResolutionId = "resolution-1",
            settlementObligationId = "obl-1",
            settlementBreakId = "break-1",
            settlementRepairId = "repair-1",
            scenarioRunId = "run-1",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "trade-1",
            occurredAt = Instant.parse("2026-01-01T00:00:03Z")
        )
    }
}

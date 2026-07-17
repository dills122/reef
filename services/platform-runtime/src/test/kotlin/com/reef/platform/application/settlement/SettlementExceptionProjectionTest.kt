package com.reef.platform.application.settlement

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SettlementExceptionProjectionTest {
    @Test
    fun projectsClearingRejectionsAndSettlementBreaksIntoOpsQueue() {
        val projection = SettlementExceptionProjection.project(
            SettlementFactBundle(
                scenarioRunId = "run-exceptions",
                obligations = listOf(obligation("obl-clearing", "trade-clearing"), obligation("obl-break", "trade-break")),
                clearingSubmissions = listOf(clearingSubmission()),
                clearingRejections = listOf(clearingRejection()),
                breaks = listOf(breakOpened()),
                repairs = listOf(repairPosted())
            )
        )

        assertEquals("run-exceptions", projection.scenarioRunId)
        assertEquals(2, projection.exceptionsCount)
        assertEquals(1, projection.openCount)
        assertEquals(1, projection.repairPostedCount)
        assertEquals(0, projection.resolvedCount)
        assertEquals(1, projection.clearingRejectedCount)
        assertEquals(1, projection.settlementBreakCount)

        val clearing = projection.exceptions.single { it.exceptionType == SettlementExceptionTypeClearingRejected }
        assertEquals("clearing-rejection-1", clearing.settlementExceptionId)
        assertEquals(SettlementExceptionOpenState, clearing.exceptionState)
        assertEquals(SettlementExceptionSeverityHigh, clearing.severity)
        assertEquals(SettlementExceptionOwnerRole, clearing.ownerRole)
        assertEquals(SettlementExceptionActionClearingReview, clearing.actionRequired)
        assertEquals(SettlementClearingRejectedReason, clearing.reason)
        assertEquals("clearing-submission-1", clearing.settlementClearingSubmissionId)
        assertEquals("corr-1", clearing.correlationId)
        assertEquals(Instant.parse("2026-01-01T00:00:02Z"), clearing.openedAt)
        assertEquals(Instant.parse("2026-01-01T00:00:02Z"), clearing.lastUpdatedAt)
        assertEquals(null, clearing.resolvedAt)
        assertEquals("trade-clearing", clearing.tradeId)

        val settlementBreak = projection.exceptions.single { it.exceptionType == SettlementExceptionTypeSettlementBreak }
        assertEquals("break-1", settlementBreak.settlementExceptionId)
        assertEquals(SettlementRepairPostedState, settlementBreak.exceptionState)
        assertEquals(SettlementExceptionSeverityMedium, settlementBreak.severity)
        assertEquals(SettlementExceptionOwnerRole, settlementBreak.ownerRole)
        assertEquals(SettlementExceptionActionAwaitingRetry, settlementBreak.actionRequired)
        assertEquals(SettlementRepairPostedActionSecurity, settlementBreak.repairAction)
        assertEquals("repair-1", settlementBreak.settlementRepairId)
        assertEquals("ops-1", settlementBreak.actorId)
        assertEquals("corr-1", settlementBreak.correlationId)
        assertEquals(Instant.parse("2026-01-01T00:00:03Z"), settlementBreak.openedAt)
        assertEquals(Instant.parse("2026-01-01T00:00:04Z"), settlementBreak.lastUpdatedAt)
        assertEquals("trade-break", settlementBreak.tradeId)
    }

    @Test
    fun projectsResolvedSettlementBreakAsClosedException() {
        val projection = SettlementExceptionProjection.project(
            SettlementFactBundle(
                scenarioRunId = "run-exceptions",
                obligations = listOf(obligation("obl-break", "trade-break")),
                breaks = listOf(breakOpened()),
                repairs = listOf(repairPosted()),
                resolutions = listOf(resolved())
            )
        )

        assertEquals(1, projection.exceptionsCount)
        assertEquals(0, projection.openCount)
        assertEquals(0, projection.repairPostedCount)
        assertEquals(1, projection.resolvedCount)
        assertEquals(SettlementExceptionResolvedState, projection.exceptions.single().exceptionState)
        assertEquals(SettlementExceptionActionNone, projection.exceptions.single().actionRequired)
        assertEquals("resolution-1", projection.exceptions.single().settlementResolutionId)
        assertEquals(Instant.parse("2026-01-01T00:00:05Z"), projection.exceptions.single().resolvedAt)
    }

    private fun obligation(id: String, tradeId: String): SettlementObligationCreatedFact {
        return SettlementObligationCreatedFact(
            settlementObligationId = id,
            scenarioRunId = "run-exceptions",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = tradeId,
            tradeId = tradeId,
            buyerParticipantId = "buyer-1",
            sellerParticipantId = "seller-1",
            instrumentId = "AAPL",
            quantity = "100",
            cashAmount = "15025000000000",
            currency = "USD",
            occurredAt = Instant.parse("2026-01-01T00:00:00Z")
        )
    }

    private fun clearingSubmission(): SettlementClearingSubmittedFact {
        return SettlementClearingSubmittedFact(
            settlementClearingSubmissionId = "clearing-submission-1",
            settlementObligationId = "obl-clearing",
            settlementAffirmationId = "affirmation-1",
            scenarioRunId = "run-exceptions",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "affirmation-1",
            occurredAt = Instant.parse("2026-01-01T00:00:01Z")
        )
    }

    private fun clearingRejection(): SettlementClearingRejectedFact {
        return SettlementClearingRejectedFact(
            settlementClearingRejectionId = "clearing-rejection-1",
            settlementClearingSubmissionId = "clearing-submission-1",
            settlementObligationId = "obl-clearing",
            scenarioRunId = "run-exceptions",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "clearing-submission-1",
            occurredAt = Instant.parse("2026-01-01T00:00:02Z")
        )
    }

    private fun breakOpened(): SettlementBreakOpenedFact {
        return SettlementBreakOpenedFact(
            settlementBreakId = "break-1",
            settlementObligationId = "obl-break",
            scenarioRunId = "run-exceptions",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "attempt-1",
            reason = SettlementBreakOpenedReasonSecurity,
            occurredAt = Instant.parse("2026-01-01T00:00:03Z")
        )
    }

    private fun repairPosted(): SettlementRepairPostedFact {
        return SettlementRepairPostedFact(
            settlementRepairId = "repair-1",
            settlementBreakId = "break-1",
            settlementObligationId = "obl-break",
            scenarioRunId = "run-exceptions",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "break-1",
            repairAction = SettlementRepairPostedActionSecurity,
            actorId = "ops-1",
            occurredAt = Instant.parse("2026-01-01T00:00:04Z")
        )
    }

    private fun resolved(): SettlementResolvedFact {
        return SettlementResolvedFact(
            settlementResolutionId = "resolution-1",
            settlementObligationId = "obl-break",
            settlementBreakId = "break-1",
            settlementRepairId = "repair-1",
            scenarioRunId = "run-exceptions",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "attempt-2",
            occurredAt = Instant.parse("2026-01-01T00:00:05Z")
        )
    }
}

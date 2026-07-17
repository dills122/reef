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
        assertEquals("BROKEN", view.settlementState)
        assertEquals("RESOLVED", view.exceptionState)
        assertEquals("", view.settlementInstructionId)
        assertEquals("", view.settlementAttemptId)
        assertEquals(0, view.settlementAttemptNumber)
        assertEquals("", view.settlementId)
        assertEquals("", view.cashLegState)
        assertEquals("", view.securityLegState)
        assertEquals(0, view.ledgerEntryCount)
        assertEquals("break-1", view.settlementBreakId)
        assertEquals("repair-1", view.settlementRepairId)
        assertEquals("resolution-1", view.settlementResolutionId)
        assertEquals(Instant.parse("2026-01-01T00:00:03Z"), view.updatedAt)
    }

    @Test
    fun projectsSettledFinalityAndResolvedExceptionIndependently() {
        val view = SettlementObligationProjection.project(
            SettlementFactBundle(
                scenarioRunId = "run-1",
                obligations = listOf(obligation()),
                instructions = listOf(instructionCreated()),
                attempts = listOf(attemptStarted()),
                legOutcomes = listOf(legOutcome("cash-leg-1", SettlementLegTypeCash), legOutcome("security-leg-1", SettlementLegTypeSecurity)),
                ledgerEntries = listOf(
                    ledgerEntry("ledger-buyer-cash-debit", SettlementLedgerEntryTypeCash, SettlementLedgerDirectionDebit),
                    ledgerEntry("ledger-seller-cash-credit", SettlementLedgerEntryTypeCash, SettlementLedgerDirectionCredit),
                    ledgerEntry("ledger-seller-security-debit", SettlementLedgerEntryTypeSecurity, SettlementLedgerDirectionDebit),
                    ledgerEntry("ledger-buyer-security-credit", SettlementLedgerEntryTypeSecurity, SettlementLedgerDirectionCredit)
                ),
                settlements = listOf(settled()),
                breaks = listOf(breakOpened()),
                repairs = listOf(repairPosted()),
                resolutions = listOf(resolved())
            )
        ).single()

        assertEquals("SETTLED", view.settlementState)
        assertEquals("RESOLVED", view.exceptionState)
        assertEquals("settlement-1", view.settlementId)
        assertEquals("resolution-1", view.settlementResolutionId)
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
        assertEquals("", view.settlementInstructionId)
        assertEquals("", view.settlementAttemptId)
        assertEquals(0, view.settlementAttemptNumber)
        assertEquals("", view.settlementId)
        assertEquals("", view.settlementBreakId)
    }

    @Test
    fun projectsAttemptStartedStateBeforeBreaksOrResolution() {
        val view = SettlementObligationProjection.project(
            SettlementFactBundle(
                scenarioRunId = "run-1",
                obligations = listOf(obligation()),
                instructions = listOf(instructionCreated()),
                attempts = listOf(attemptStarted())
            )
        ).single()

        assertEquals("ATTEMPT_STARTED", view.settlementState)
        assertEquals("NONE", view.exceptionState)
        assertEquals("instruction-1", view.settlementInstructionId)
        assertEquals("attempt-1", view.settlementAttemptId)
        assertEquals(1, view.settlementAttemptNumber)
        assertEquals("", view.settlementId)
        assertEquals(Instant.parse("2026-01-01T00:00:01Z"), view.updatedAt)
    }

    @Test
    fun projectsSettledStateOnlyFromFinalityProof() {
        val view = SettlementObligationProjection.project(
            SettlementFactBundle(
                scenarioRunId = "run-1",
                obligations = listOf(obligation()),
                instructions = listOf(instructionCreated()),
                attempts = listOf(attemptStarted()),
                legOutcomes = listOf(legOutcome("cash-leg-1", SettlementLegTypeCash), legOutcome("security-leg-1", SettlementLegTypeSecurity)),
                ledgerEntries = listOf(
                    ledgerEntry("ledger-buyer-cash-debit", SettlementLedgerEntryTypeCash, SettlementLedgerDirectionDebit),
                    ledgerEntry("ledger-seller-cash-credit", SettlementLedgerEntryTypeCash, SettlementLedgerDirectionCredit),
                    ledgerEntry("ledger-seller-security-debit", SettlementLedgerEntryTypeSecurity, SettlementLedgerDirectionDebit),
                    ledgerEntry("ledger-buyer-security-credit", SettlementLedgerEntryTypeSecurity, SettlementLedgerDirectionCredit)
                ),
                settlements = listOf(settled())
            )
        ).single()

        assertEquals("SETTLED", view.settlementState)
        assertEquals("NONE", view.exceptionState)
        assertEquals("settlement-1", view.settlementId)
        assertEquals("LEG_SUCCEEDED", view.cashLegState)
        assertEquals("LEG_SUCCEEDED", view.securityLegState)
        assertEquals(4, view.ledgerEntryCount)
        assertEquals(Instant.parse("2026-01-01T00:00:02Z"), view.updatedAt)
    }

    @Test
    fun projectsInstructionCreatedStateBeforeAttempt() {
        val view = SettlementObligationProjection.project(
            SettlementFactBundle(
                scenarioRunId = "run-1",
                obligations = listOf(obligation()),
                instructions = listOf(instructionCreated())
            )
        ).single()

        assertEquals("INSTRUCTION_CREATED", view.settlementState)
        assertEquals("NONE", view.exceptionState)
        assertEquals("instruction-1", view.settlementInstructionId)
        assertEquals("", view.settlementAttemptId)
        assertEquals(Instant.parse("2026-01-01T00:00:01Z"), view.updatedAt)
    }

    @Test
    fun projectsClearingAndNovationStateBeforeInstruction() {
        val view = SettlementObligationProjection.project(
            SettlementFactBundle(
                scenarioRunId = "run-1",
                obligations = listOf(obligation()),
                clearingSubmissions = listOf(clearingSubmitted()),
                clearingAcceptances = listOf(clearingAccepted()),
                novations = listOf(novation())
            )
        ).single()

        assertEquals("NOVATION_RECORDED", view.settlementState)
        assertEquals("NONE", view.exceptionState)
        assertEquals("NOVATION_RECORDED", view.clearingState)
        assertEquals("clearing-submission-1", view.settlementClearingSubmissionId)
        assertEquals("clearing-acceptance-1", view.settlementClearingAcceptanceId)
        assertEquals("", view.settlementClearingRejectionId)
        assertEquals("novation-1", view.settlementNovationId)
        assertEquals(Instant.parse("2026-01-01T00:00:03Z"), view.updatedAt)
    }

    @Test
    fun projectsClearingRejectionAsOpenException() {
        val view = SettlementObligationProjection.project(
            SettlementFactBundle(
                scenarioRunId = "run-1",
                obligations = listOf(obligation()),
                clearingSubmissions = listOf(clearingSubmitted()),
                clearingRejections = listOf(clearingRejected())
            )
        ).single()

        assertEquals("CLEARING_REJECTED", view.settlementState)
        assertEquals("OPEN", view.exceptionState)
        assertEquals("CLEARING_REJECTED", view.clearingState)
        assertEquals("clearing-rejection-1", view.settlementClearingRejectionId)
        assertEquals("", view.settlementNovationId)
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
            settlementInstructionId = "instruction-1",
            scenarioRunId = "run-1",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "instruction-1",
            occurredAt = Instant.parse("2026-01-01T00:00:01Z")
        )
    }

    private fun instructionCreated(): SettlementInstructionCreatedFact {
        return SettlementInstructionCreatedFact(
            settlementInstructionId = "instruction-1",
            settlementObligationId = "obl-1",
            scenarioRunId = "run-1",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "obl-1",
            occurredAt = Instant.parse("2026-01-01T00:00:01Z")
        )
    }

    private fun clearingSubmitted(): SettlementClearingSubmittedFact {
        return SettlementClearingSubmittedFact(
            settlementClearingSubmissionId = "clearing-submission-1",
            settlementObligationId = "obl-1",
            settlementAffirmationId = "affirmation-1",
            scenarioRunId = "run-1",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "affirmation-1",
            occurredAt = Instant.parse("2026-01-01T00:00:01Z")
        )
    }

    private fun clearingAccepted(): SettlementClearingAcceptedFact {
        return SettlementClearingAcceptedFact(
            settlementClearingAcceptanceId = "clearing-acceptance-1",
            settlementClearingSubmissionId = "clearing-submission-1",
            settlementObligationId = "obl-1",
            scenarioRunId = "run-1",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "clearing-submission-1",
            occurredAt = Instant.parse("2026-01-01T00:00:02Z")
        )
    }

    private fun clearingRejected(): SettlementClearingRejectedFact {
        return SettlementClearingRejectedFact(
            settlementClearingRejectionId = "clearing-rejection-1",
            settlementClearingSubmissionId = "clearing-submission-1",
            settlementObligationId = "obl-1",
            scenarioRunId = "run-1",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "clearing-submission-1",
            occurredAt = Instant.parse("2026-01-01T00:00:02Z")
        )
    }

    private fun novation(): SettlementNovationRecordedFact {
        return SettlementNovationRecordedFact(
            settlementNovationId = "novation-1",
            settlementClearingAcceptanceId = "clearing-acceptance-1",
            settlementObligationId = "obl-1",
            scenarioRunId = "run-1",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "clearing-acceptance-1",
            occurredAt = Instant.parse("2026-01-01T00:00:03Z")
        )
    }

    private fun legOutcome(id: String, legType: String): SettlementLegOutcomeFact {
        return SettlementLegOutcomeFact(
            settlementLegOutcomeId = id,
            settlementObligationId = "obl-1",
            settlementInstructionId = "instruction-1",
            settlementAttemptId = "attempt-1",
            scenarioRunId = "run-1",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "attempt-1",
            legType = legType,
            occurredAt = Instant.parse("2026-01-01T00:00:01Z")
        )
    }

    private fun ledgerEntry(id: String, assetType: String, direction: String): SettlementLedgerEntryFact {
        return SettlementLedgerEntryFact(
            ledgerEntryId = id,
            settlementObligationId = "obl-1",
            settlementInstructionId = "instruction-1",
            settlementAttemptId = "attempt-1",
            scenarioRunId = "run-1",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "attempt-1",
            participantId = "participant-1",
            accountId = "account-1",
            assetType = assetType,
            assetId = if (assetType == SettlementLedgerEntryTypeCash) "USD" else "AAPL",
            direction = direction,
            quantity = "100",
            occurredAt = Instant.parse("2026-01-01T00:00:01Z")
        )
    }

    private fun settled(): SettlementSettledFact {
        return SettlementSettledFact(
            settlementId = "settlement-1",
            settlementObligationId = "obl-1",
            settlementInstructionId = "instruction-1",
            settlementAttemptId = "attempt-1",
            scenarioRunId = "run-1",
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "attempt-1",
            occurredAt = Instant.parse("2026-01-01T00:00:02Z")
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

package com.reef.platform.application.settlement

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettlementLedgerProjectionTest {
    @Test
    fun projectsLedgerBalancesFromDebitAndCreditEntries() {
        val projection = SettlementLedgerProjection.project(finalityFacts("run-ledger"))

        assertEquals("run-ledger", projection.scenarioRunId)
        assertEquals(4, projection.balances.size)
        assertEquals(1, projection.settlementProofs.size)

        val buyerCash = projection.balances.single {
            it.participantId == "buyer-1" && it.assetType == SettlementLedgerEntryTypeCash
        }
        val buyerSecurity = projection.balances.single {
            it.participantId == "buyer-1" && it.assetType == SettlementLedgerEntryTypeSecurity
        }
        val sellerCash = projection.balances.single {
            it.participantId == "seller-1" && it.assetType == SettlementLedgerEntryTypeCash
        }
        val sellerSecurity = projection.balances.single {
            it.participantId == "seller-1" && it.assetType == SettlementLedgerEntryTypeSecurity
        }

        assertEquals("15000", buyerCash.debitQuantity)
        assertEquals("0", buyerCash.creditQuantity)
        assertEquals("-15000", buyerCash.netQuantity)
        assertEquals("100", buyerSecurity.netQuantity)
        assertEquals("15000", sellerCash.netQuantity)
        assertEquals("-100", sellerSecurity.netQuantity)
    }

    @Test
    fun projectsSettlementLedgerProofFromSettledAttempt() {
        val proof = SettlementLedgerProjection.project(finalityFacts("run-ledger")).settlementProofs.single()

        assertEquals("settlement-1", proof.settlementId)
        assertEquals("SETTLED", proof.settlementState)
        assertEquals("PROVEN", proof.proofState)
        assertEquals("15000", proof.cashDebitQuantity)
        assertEquals("15000", proof.cashCreditQuantity)
        assertEquals("100", proof.securityDebitQuantity)
        assertEquals("100", proof.securityCreditQuantity)
        assertTrue(proof.cashBalanced)
        assertTrue(proof.securityBalanced)
        assertEquals(2, proof.legOutcomeCount)
        assertEquals(4, proof.ledgerEntryCount)
    }

    private fun finalityFacts(scenarioRunId: String): SettlementFactBundle {
        return SettlementFactBundle(
            scenarioRunId = scenarioRunId,
            obligations = listOf(obligation(scenarioRunId)),
            instructions = listOf(instructionCreated(scenarioRunId)),
            attempts = listOf(attemptStarted(scenarioRunId)),
            legOutcomes = listOf(
                legOutcome(scenarioRunId, "cash-leg-1", SettlementLegTypeCash),
                legOutcome(scenarioRunId, "security-leg-1", SettlementLegTypeSecurity)
            ),
            ledgerEntries = listOf(
                ledgerEntry(scenarioRunId, "ledger-buyer-cash-debit", "buyer-1", "account-buyer-1", SettlementLedgerEntryTypeCash, "USD", SettlementLedgerDirectionDebit, "15000.00"),
                ledgerEntry(scenarioRunId, "ledger-seller-cash-credit", "seller-1", "account-seller-1", SettlementLedgerEntryTypeCash, "USD", SettlementLedgerDirectionCredit, "15000.00"),
                ledgerEntry(scenarioRunId, "ledger-seller-security-debit", "seller-1", "account-seller-1", SettlementLedgerEntryTypeSecurity, "AAPL", SettlementLedgerDirectionDebit, "100"),
                ledgerEntry(scenarioRunId, "ledger-buyer-security-credit", "buyer-1", "account-buyer-1", SettlementLedgerEntryTypeSecurity, "AAPL", SettlementLedgerDirectionCredit, "100")
            ),
            settlements = listOf(settled(scenarioRunId))
        )
    }

    private fun obligation(scenarioRunId: String): SettlementObligationCreatedFact {
        return SettlementObligationCreatedFact(
            settlementObligationId = "obl-1",
            scenarioRunId = scenarioRunId,
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "trade-1",
            tradeId = "trade-1",
            buyerParticipantId = "buyer-1",
            sellerParticipantId = "seller-1",
            instrumentId = "AAPL",
            quantity = "100",
            cashAmount = "15000.00",
            currency = "USD",
            occurredAt = Instant.parse("2026-01-01T00:00:00Z")
        )
    }

    private fun instructionCreated(scenarioRunId: String): SettlementInstructionCreatedFact {
        return SettlementInstructionCreatedFact(
            settlementInstructionId = "instruction-1",
            settlementObligationId = "obl-1",
            scenarioRunId = scenarioRunId,
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "obl-1",
            occurredAt = Instant.parse("2026-01-01T00:00:00Z")
        )
    }

    private fun attemptStarted(scenarioRunId: String): SettlementAttemptStartedFact {
        return SettlementAttemptStartedFact(
            settlementAttemptId = "attempt-1",
            settlementObligationId = "obl-1",
            settlementInstructionId = "instruction-1",
            scenarioRunId = scenarioRunId,
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "instruction-1",
            occurredAt = Instant.parse("2026-01-01T00:00:00Z")
        )
    }

    private fun legOutcome(scenarioRunId: String, id: String, legType: String): SettlementLegOutcomeFact {
        return SettlementLegOutcomeFact(
            settlementLegOutcomeId = id,
            settlementObligationId = "obl-1",
            settlementInstructionId = "instruction-1",
            settlementAttemptId = "attempt-1",
            scenarioRunId = scenarioRunId,
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "attempt-1",
            legType = legType,
            occurredAt = Instant.parse("2026-01-01T00:00:01Z")
        )
    }

    private fun ledgerEntry(
        scenarioRunId: String,
        ledgerEntryId: String,
        participantId: String,
        accountId: String,
        assetType: String,
        assetId: String,
        direction: String,
        quantity: String
    ): SettlementLedgerEntryFact {
        return SettlementLedgerEntryFact(
            ledgerEntryId = ledgerEntryId,
            settlementObligationId = "obl-1",
            settlementInstructionId = "instruction-1",
            settlementAttemptId = "attempt-1",
            scenarioRunId = scenarioRunId,
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "attempt-1",
            participantId = participantId,
            accountId = accountId,
            assetType = assetType,
            assetId = assetId,
            direction = direction,
            quantity = quantity,
            occurredAt = Instant.parse("2026-01-01T00:00:01Z")
        )
    }

    private fun settled(scenarioRunId: String): SettlementSettledFact {
        return SettlementSettledFact(
            settlementId = "settlement-1",
            settlementObligationId = "obl-1",
            settlementInstructionId = "instruction-1",
            settlementAttemptId = "attempt-1",
            scenarioRunId = scenarioRunId,
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 2,
            correlationId = "corr-1",
            causationId = "attempt-1",
            occurredAt = Instant.parse("2026-01-01T00:00:02Z")
        )
    }
}

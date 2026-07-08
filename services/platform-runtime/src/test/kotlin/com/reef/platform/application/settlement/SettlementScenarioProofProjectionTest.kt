package com.reef.platform.application.settlement

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettlementScenarioProofProjectionTest {
    @Test
    fun projectsDeterministicProofReportFromFactBundle() {
        val proof = SettlementScenarioProofProjection.project(settlementFacts())

        assertEquals("run-proof", proof.scenarioRunId)
        assertEquals("CLEAN", proof.proofStatus)
        assertEquals("SHA-256", proof.checksumAlgorithm)
        assertEquals(64, proof.checksum.length)
        assertEquals(15, proof.factsCount)
        assertEquals(1, proof.profilePolicies.size)
        assertEquals("instant-post-trade-v1", proof.profilePolicies.single().postTradeProfileId)
        assertEquals(2, proof.profilePolicies.single().postTradePolicyVersion)
        assertEquals(15, proof.profilePolicies.single().factCount)
        assertEquals(2, proof.obligationsCount)
        assertEquals(0, proof.causationGaps.size)
        assertEquals(2, proof.obligations.size)
        assertEquals(4, proof.balances.size)
        assertEquals(1, proof.settlementProofs.size)
        assertTrue(proof.settlementProofs.single().cashBalanced)
        assertTrue(proof.settlementProofs.single().securityBalanced)

        val settled = proof.obligations.first()
        assertEquals(listOf("instruction-1"), settled.settlementInstructionIds)
        assertEquals(listOf("attempt-1"), settled.settlementAttemptIds)
        assertEquals(4, settled.ledgerEntryIds.size)
        assertEquals(listOf("settlement-1"), settled.settlementIds)
    }

    @Test
    fun reportsCausationGapsForBrokenReplayChains() {
        val proof = SettlementScenarioProofProjection.project(
            SettlementFactBundle(
                scenarioRunId = "run-proof",
                attempts = listOf(attempt("attempt-missing", "missing-obligation", "missing-instruction"))
            )
        )

        assertEquals(2, proof.causationGaps.size)
        assertEquals("GAPPED", proof.proofStatus)
        assertTrue(proof.causationGaps.any { it.missingReferenceType == "SettlementObligationCreated" })
        assertTrue(proof.causationGaps.any { it.missingReferenceType == "SettlementInstructionCreated" })
    }

    companion object {
        fun settlementFacts(): SettlementFactBundle {
            return SettlementFactBundle(
                scenarioRunId = "run-proof",
                resourcePositions = listOf(
                    resource("resource-buyer-cash", "buyer-1", "account-buyer-1", SettlementLedgerEntryTypeCash, "USD", "1000"),
                    resource("resource-seller-security", "seller-1", "account-seller-1", SettlementLedgerEntryTypeSecurity, "AAPL", "20")
                ),
                obligations = listOf(
                    obligation("obl-1", "trade-1", "buyer-1", "seller-1", "10", "500"),
                    obligation("obl-2", "trade-2", "buyer-1", "seller-1", "4", "200")
                ),
                instructions = listOf(instruction("instruction-1", "obl-1")),
                attempts = listOf(attempt("attempt-1", "obl-1", "instruction-1")),
                legOutcomes = listOf(
                    leg("cash-leg-1", "obl-1", "instruction-1", "attempt-1", SettlementLegTypeCash),
                    leg("security-leg-1", "obl-1", "instruction-1", "attempt-1", SettlementLegTypeSecurity)
                ),
                ledgerEntries = listOf(
                    ledger("ledger-buyer-cash-debit", "obl-1", "instruction-1", "attempt-1", "buyer-1", "account-buyer-1", SettlementLedgerEntryTypeCash, "USD", SettlementLedgerDirectionDebit, "500"),
                    ledger("ledger-seller-cash-credit", "obl-1", "instruction-1", "attempt-1", "seller-1", "account-seller-1", SettlementLedgerEntryTypeCash, "USD", SettlementLedgerDirectionCredit, "500"),
                    ledger("ledger-seller-security-debit", "obl-1", "instruction-1", "attempt-1", "seller-1", "account-seller-1", SettlementLedgerEntryTypeSecurity, "AAPL", SettlementLedgerDirectionDebit, "10"),
                    ledger("ledger-buyer-security-credit", "obl-1", "instruction-1", "attempt-1", "buyer-1", "account-buyer-1", SettlementLedgerEntryTypeSecurity, "AAPL", SettlementLedgerDirectionCredit, "10")
                ),
                settlements = listOf(settled("settlement-1", "obl-1", "instruction-1", "attempt-1")),
                breaks = listOf(breakOpened("break-2", "obl-2")),
                repairs = listOf(repair("repair-2", "break-2", "obl-2"))
            )
        }

        fun resource(
            id: String,
            participantId: String,
            accountId: String,
            assetType: String,
            assetId: String,
            quantity: String
        ): SettlementResourcePositionFact {
            return SettlementResourcePositionFact(
                resourcePositionId = id,
                scenarioRunId = "run-proof",
                postTradeProfileId = "instant-post-trade-v1",
                postTradePolicyVersion = 2,
                correlationId = "corr-1",
                causationId = "seed",
                participantId = participantId,
                accountId = accountId,
                assetType = assetType,
                assetId = assetId,
                quantity = quantity,
                occurredAt = Instant.parse("2026-01-01T00:00:00Z")
            )
        }

        fun obligation(
            id: String,
            tradeId: String,
            buyerParticipantId: String,
            sellerParticipantId: String,
            quantity: String,
            cashAmount: String
        ): SettlementObligationCreatedFact {
            return SettlementObligationCreatedFact(
                settlementObligationId = id,
                scenarioRunId = "run-proof",
                postTradeProfileId = "instant-post-trade-v1",
                postTradePolicyVersion = 2,
                correlationId = "corr-1",
                causationId = tradeId,
                tradeId = tradeId,
                buyerParticipantId = buyerParticipantId,
                sellerParticipantId = sellerParticipantId,
                instrumentId = "AAPL",
                quantity = quantity,
                cashAmount = cashAmount,
                currency = "USD",
                occurredAt = Instant.parse("2026-01-01T00:00:01Z")
            )
        }

        fun instruction(id: String, obligationId: String): SettlementInstructionCreatedFact {
            return SettlementInstructionCreatedFact(
                settlementInstructionId = id,
                settlementObligationId = obligationId,
                scenarioRunId = "run-proof",
                postTradeProfileId = "instant-post-trade-v1",
                postTradePolicyVersion = 2,
                correlationId = "corr-1",
                causationId = obligationId,
                occurredAt = Instant.parse("2026-01-01T00:00:02Z")
            )
        }

        fun attempt(id: String, obligationId: String, instructionId: String): SettlementAttemptStartedFact {
            return SettlementAttemptStartedFact(
                settlementAttemptId = id,
                settlementObligationId = obligationId,
                settlementInstructionId = instructionId,
                scenarioRunId = "run-proof",
                postTradeProfileId = "instant-post-trade-v1",
                postTradePolicyVersion = 2,
                correlationId = "corr-1",
                causationId = instructionId,
                occurredAt = Instant.parse("2026-01-01T00:00:03Z")
            )
        }

        fun leg(id: String, obligationId: String, instructionId: String, attemptId: String, legType: String): SettlementLegOutcomeFact {
            return SettlementLegOutcomeFact(
                settlementLegOutcomeId = id,
                settlementObligationId = obligationId,
                settlementInstructionId = instructionId,
                settlementAttemptId = attemptId,
                scenarioRunId = "run-proof",
                postTradeProfileId = "instant-post-trade-v1",
                postTradePolicyVersion = 2,
                correlationId = "corr-1",
                causationId = attemptId,
                legType = legType,
                occurredAt = Instant.parse("2026-01-01T00:00:04Z")
            )
        }

        fun ledger(
            id: String,
            obligationId: String,
            instructionId: String,
            attemptId: String,
            participantId: String,
            accountId: String,
            assetType: String,
            assetId: String,
            direction: String,
            quantity: String
        ): SettlementLedgerEntryFact {
            return SettlementLedgerEntryFact(
                ledgerEntryId = id,
                settlementObligationId = obligationId,
                settlementInstructionId = instructionId,
                settlementAttemptId = attemptId,
                scenarioRunId = "run-proof",
                postTradeProfileId = "instant-post-trade-v1",
                postTradePolicyVersion = 2,
                correlationId = "corr-1",
                causationId = attemptId,
                participantId = participantId,
                accountId = accountId,
                assetType = assetType,
                assetId = assetId,
                direction = direction,
                quantity = quantity,
                occurredAt = Instant.parse("2026-01-01T00:00:04Z")
            )
        }

        fun settled(id: String, obligationId: String, instructionId: String, attemptId: String): SettlementSettledFact {
            return SettlementSettledFact(
                settlementId = id,
                settlementObligationId = obligationId,
                settlementInstructionId = instructionId,
                settlementAttemptId = attemptId,
                scenarioRunId = "run-proof",
                postTradeProfileId = "instant-post-trade-v1",
                postTradePolicyVersion = 2,
                correlationId = "corr-1",
                causationId = attemptId,
                occurredAt = Instant.parse("2026-01-01T00:00:05Z")
            )
        }

        fun breakOpened(id: String, obligationId: String): SettlementBreakOpenedFact {
            return SettlementBreakOpenedFact(
                settlementBreakId = id,
                settlementObligationId = obligationId,
                scenarioRunId = "run-proof",
                postTradeProfileId = "instant-post-trade-v1",
                postTradePolicyVersion = 2,
                correlationId = "corr-1",
                causationId = obligationId,
                occurredAt = Instant.parse("2026-01-01T00:00:06Z")
            )
        }

        fun repair(id: String, breakId: String, obligationId: String): SettlementRepairPostedFact {
            return SettlementRepairPostedFact(
                settlementRepairId = id,
                settlementBreakId = breakId,
                settlementObligationId = obligationId,
                scenarioRunId = "run-proof",
                postTradeProfileId = "instant-post-trade-v1",
                postTradePolicyVersion = 2,
                correlationId = "corr-1",
                causationId = breakId,
                actorId = "operator-1",
                occurredAt = Instant.parse("2026-01-01T00:00:07Z")
            )
        }
    }
}

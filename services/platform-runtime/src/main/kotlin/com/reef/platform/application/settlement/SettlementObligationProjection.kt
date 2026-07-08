package com.reef.platform.application.settlement

import java.time.Instant

const val SettlementExceptionNoneState = "NONE"
const val SettlementRepairPostedState = "REPAIR_POSTED"

data class SettlementObligationView(
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String,
    val postTradePolicyVersion: Int,
    val tradeId: String,
    val buyerParticipantId: String,
    val sellerParticipantId: String,
    val instrumentId: String,
    val quantity: String,
    val cashAmount: String,
    val currency: String,
    val obligationState: String,
    val settlementState: String,
    val exceptionState: String,
    val settlementInstructionId: String,
    val settlementAttemptId: String,
    val settlementAttemptNumber: Int,
    val settlementId: String,
    val cashLegState: String,
    val securityLegState: String,
    val ledgerEntryCount: Int,
    val settlementBreakId: String,
    val settlementRepairId: String,
    val settlementResolutionId: String,
    val occurredAt: Instant,
    val updatedAt: Instant
)

object SettlementObligationProjection {
    fun project(facts: SettlementFactBundle): List<SettlementObligationView> {
        val breaksByObligation = facts.breaks.groupBy { it.settlementObligationId }
        val instructionsByObligation = facts.instructions.groupBy { it.settlementObligationId }
        val attemptsByObligation = facts.attempts.groupBy { it.settlementObligationId }
        val legOutcomesByObligation = facts.legOutcomes.groupBy { it.settlementObligationId }
        val ledgerEntriesByObligation = facts.ledgerEntries.groupBy { it.settlementObligationId }
        val settlementsByObligation = facts.settlements.groupBy { it.settlementObligationId }
        val repairsByObligation = facts.repairs.groupBy { it.settlementObligationId }
        val resolutionsByObligation = facts.resolutions.groupBy { it.settlementObligationId }
        return facts.obligations
            .sortedWith(compareBy<SettlementObligationCreatedFact> { it.occurredAt }.thenBy { it.settlementObligationId })
            .map { obligation ->
                val breakFact = breaksByObligation[obligation.settlementObligationId]
                    .orEmpty()
                    .maxWithOrNull(compareBy<SettlementBreakOpenedFact> { it.occurredAt }.thenBy { it.settlementBreakId })
                val instruction = instructionsByObligation[obligation.settlementObligationId]
                    .orEmpty()
                    .maxWithOrNull(compareBy<SettlementInstructionCreatedFact> { it.occurredAt }.thenBy { it.settlementInstructionId })
                val attempt = attemptsByObligation[obligation.settlementObligationId]
                    .orEmpty()
                    .maxWithOrNull(compareBy<SettlementAttemptStartedFact> { it.occurredAt }.thenBy { it.settlementAttemptId })
                val legOutcomes = legOutcomesByObligation[obligation.settlementObligationId].orEmpty()
                val cashLeg = legOutcomes
                    .filter { it.legType == SettlementLegTypeCash }
                    .maxWithOrNull(compareBy<SettlementLegOutcomeFact> { it.occurredAt }.thenBy { it.settlementLegOutcomeId })
                val securityLeg = legOutcomes
                    .filter { it.legType == SettlementLegTypeSecurity }
                    .maxWithOrNull(compareBy<SettlementLegOutcomeFact> { it.occurredAt }.thenBy { it.settlementLegOutcomeId })
                val settlement = settlementsByObligation[obligation.settlementObligationId]
                    .orEmpty()
                    .maxWithOrNull(compareBy<SettlementSettledFact> { it.occurredAt }.thenBy { it.settlementId })
                val repair = repairsByObligation[obligation.settlementObligationId]
                    .orEmpty()
                    .maxWithOrNull(compareBy<SettlementRepairPostedFact> { it.occurredAt }.thenBy { it.settlementRepairId })
                val resolution = resolutionsByObligation[obligation.settlementObligationId]
                    .orEmpty()
                    .maxWithOrNull(compareBy<SettlementResolvedFact> { it.occurredAt }.thenBy { it.settlementResolutionId })
                val updatedAt = listOfNotNull(
                    obligation.occurredAt,
                    instruction?.occurredAt,
                    attempt?.occurredAt,
                    cashLeg?.occurredAt,
                    securityLeg?.occurredAt,
                    settlement?.occurredAt,
                    breakFact?.occurredAt,
                    repair?.occurredAt,
                    resolution?.occurredAt
                ).maxOrNull() ?: obligation.occurredAt
                SettlementObligationView(
                    settlementObligationId = obligation.settlementObligationId,
                    scenarioRunId = obligation.scenarioRunId,
                    postTradeProfileId = obligation.postTradeProfileId,
                    postTradePolicyVersion = obligation.postTradePolicyVersion,
                    tradeId = obligation.tradeId,
                    buyerParticipantId = obligation.buyerParticipantId,
                    sellerParticipantId = obligation.sellerParticipantId,
                    instrumentId = obligation.instrumentId,
                    quantity = obligation.quantity,
                    cashAmount = obligation.cashAmount,
                    currency = obligation.currency,
                    obligationState = obligation.state,
                    settlementState = resolution?.settlementState
                        ?: breakFact?.state
                        ?: settlement?.settlementState
                        ?: attempt?.state
                        ?: instruction?.state
                        ?: obligation.state,
                    exceptionState = resolution?.exceptionState
                        ?: repair?.let { SettlementRepairPostedState }
                        ?: breakFact?.state
                        ?: SettlementExceptionNoneState,
                    settlementInstructionId = instruction?.settlementInstructionId.orEmpty(),
                    settlementAttemptId = attempt?.settlementAttemptId.orEmpty(),
                    settlementAttemptNumber = attempt?.attemptNumber ?: 0,
                    settlementId = settlement?.settlementId.orEmpty(),
                    cashLegState = cashLeg?.state.orEmpty(),
                    securityLegState = securityLeg?.state.orEmpty(),
                    ledgerEntryCount = ledgerEntriesByObligation[obligation.settlementObligationId].orEmpty().size,
                    settlementBreakId = breakFact?.settlementBreakId.orEmpty(),
                    settlementRepairId = repair?.settlementRepairId.orEmpty(),
                    settlementResolutionId = resolution?.settlementResolutionId.orEmpty(),
                    occurredAt = obligation.occurredAt,
                    updatedAt = updatedAt
                )
            }
    }
}

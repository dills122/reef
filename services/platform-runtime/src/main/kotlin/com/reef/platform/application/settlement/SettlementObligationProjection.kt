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
    val settlementBreakId: String,
    val settlementRepairId: String,
    val settlementResolutionId: String,
    val occurredAt: Instant,
    val updatedAt: Instant
)

object SettlementObligationProjection {
    fun project(facts: SettlementFactBundle): List<SettlementObligationView> {
        val breaksByObligation = facts.breaks.groupBy { it.settlementObligationId }
        val repairsByObligation = facts.repairs.groupBy { it.settlementObligationId }
        val resolutionsByObligation = facts.resolutions.groupBy { it.settlementObligationId }
        return facts.obligations
            .sortedWith(compareBy<SettlementObligationCreatedFact> { it.occurredAt }.thenBy { it.settlementObligationId })
            .map { obligation ->
                val breakFact = breaksByObligation[obligation.settlementObligationId]
                    .orEmpty()
                    .maxWithOrNull(compareBy<SettlementBreakOpenedFact> { it.occurredAt }.thenBy { it.settlementBreakId })
                val repair = repairsByObligation[obligation.settlementObligationId]
                    .orEmpty()
                    .maxWithOrNull(compareBy<SettlementRepairPostedFact> { it.occurredAt }.thenBy { it.settlementRepairId })
                val resolution = resolutionsByObligation[obligation.settlementObligationId]
                    .orEmpty()
                    .maxWithOrNull(compareBy<SettlementResolvedFact> { it.occurredAt }.thenBy { it.settlementResolutionId })
                val updatedAt = listOfNotNull(
                    obligation.occurredAt,
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
                    settlementState = resolution?.settlementState ?: obligation.state,
                    exceptionState = resolution?.exceptionState
                        ?: repair?.let { SettlementRepairPostedState }
                        ?: breakFact?.state
                        ?: SettlementExceptionNoneState,
                    settlementBreakId = breakFact?.settlementBreakId.orEmpty(),
                    settlementRepairId = repair?.settlementRepairId.orEmpty(),
                    settlementResolutionId = resolution?.settlementResolutionId.orEmpty(),
                    occurredAt = obligation.occurredAt,
                    updatedAt = updatedAt
                )
            }
    }
}

package com.reef.platform.application.settlement

import java.time.Instant

const val SettlementExceptionOpenState = "OPEN"
const val SettlementExceptionResolvedState = "RESOLVED"
const val SettlementExceptionTypeClearingRejected = "CLEARING_REJECTED"
const val SettlementExceptionTypeSettlementBreak = "SETTLEMENT_BREAK"
const val SettlementExceptionActionClearingReview = "CLEARING_REVIEW"
const val SettlementExceptionActionPostCashRepair = "POST_CASH_LEG_REPAIR"
const val SettlementExceptionActionPostSecurityRepair = "POST_SECURITY_LEG_REPAIR"
const val SettlementExceptionActionAwaitingRetry = "AWAITING_RETRY"
const val SettlementExceptionActionNone = "NONE"

data class SettlementExceptionQueueView(
    val scenarioRunId: String,
    val exceptionsCount: Int,
    val openCount: Int,
    val repairPostedCount: Int,
    val resolvedCount: Int,
    val clearingRejectedCount: Int,
    val settlementBreakCount: Int,
    val exceptions: List<SettlementExceptionView>
)

data class SettlementExceptionView(
    val settlementExceptionId: String,
    val exceptionType: String,
    val exceptionState: String,
    val actionRequired: String,
    val reason: String,
    val settlementObligationId: String,
    val tradeId: String,
    val buyerParticipantId: String,
    val sellerParticipantId: String,
    val instrumentId: String,
    val quantity: String,
    val cashAmount: String,
    val currency: String,
    val settlementClearingSubmissionId: String,
    val settlementClearingRejectionId: String,
    val settlementBreakId: String,
    val settlementRepairId: String,
    val settlementResolutionId: String,
    val postTradeProfileId: String,
    val postTradePolicyVersion: Int,
    val occurredAt: Instant,
    val updatedAt: Instant
)

object SettlementExceptionProjection {
    fun project(facts: SettlementFactBundle): SettlementExceptionQueueView {
        val obligations = facts.obligations.associateBy { it.settlementObligationId }
        val repairsByBreak = facts.repairs.groupBy { it.settlementBreakId }
        val resolutionsByBreak = facts.resolutions.groupBy { it.settlementBreakId }
        val clearingExceptions = facts.clearingRejections.mapNotNull { rejection ->
            val obligation = obligations[rejection.settlementObligationId] ?: return@mapNotNull null
            SettlementExceptionView(
                settlementExceptionId = rejection.settlementClearingRejectionId,
                exceptionType = SettlementExceptionTypeClearingRejected,
                exceptionState = SettlementExceptionOpenState,
                actionRequired = SettlementExceptionActionClearingReview,
                reason = rejection.reason,
                settlementObligationId = obligation.settlementObligationId,
                tradeId = obligation.tradeId,
                buyerParticipantId = obligation.buyerParticipantId,
                sellerParticipantId = obligation.sellerParticipantId,
                instrumentId = obligation.instrumentId,
                quantity = obligation.quantity,
                cashAmount = obligation.cashAmount,
                currency = obligation.currency,
                settlementClearingSubmissionId = rejection.settlementClearingSubmissionId,
                settlementClearingRejectionId = rejection.settlementClearingRejectionId,
                settlementBreakId = "",
                settlementRepairId = "",
                settlementResolutionId = "",
                postTradeProfileId = rejection.postTradeProfileId,
                postTradePolicyVersion = rejection.postTradePolicyVersion,
                occurredAt = rejection.occurredAt,
                updatedAt = rejection.occurredAt
            )
        }
        val settlementBreakExceptions = facts.breaks.mapNotNull { breakFact ->
            val obligation = obligations[breakFact.settlementObligationId] ?: return@mapNotNull null
            val repair = repairsByBreak[breakFact.settlementBreakId]
                .orEmpty()
                .maxWithOrNull(compareBy<SettlementRepairPostedFact> { it.occurredAt }.thenBy { it.settlementRepairId })
            val resolution = resolutionsByBreak[breakFact.settlementBreakId]
                .orEmpty()
                .maxWithOrNull(compareBy<SettlementResolvedFact> { it.occurredAt }.thenBy { it.settlementResolutionId })
            SettlementExceptionView(
                settlementExceptionId = breakFact.settlementBreakId,
                exceptionType = SettlementExceptionTypeSettlementBreak,
                exceptionState = resolution?.exceptionState
                    ?: repair?.let { SettlementRepairPostedState }
                    ?: SettlementExceptionOpenState,
                actionRequired = when {
                    resolution != null -> SettlementExceptionActionNone
                    repair != null -> SettlementExceptionActionAwaitingRetry
                    breakFact.reason == SettlementBreakOpenedReason -> SettlementExceptionActionPostCashRepair
                    breakFact.reason == SettlementBreakOpenedReasonSecurity -> SettlementExceptionActionPostSecurityRepair
                    else -> SettlementExceptionActionNone
                },
                reason = breakFact.reason,
                settlementObligationId = obligation.settlementObligationId,
                tradeId = obligation.tradeId,
                buyerParticipantId = obligation.buyerParticipantId,
                sellerParticipantId = obligation.sellerParticipantId,
                instrumentId = obligation.instrumentId,
                quantity = obligation.quantity,
                cashAmount = obligation.cashAmount,
                currency = obligation.currency,
                settlementClearingSubmissionId = "",
                settlementClearingRejectionId = "",
                settlementBreakId = breakFact.settlementBreakId,
                settlementRepairId = repair?.settlementRepairId.orEmpty(),
                settlementResolutionId = resolution?.settlementResolutionId.orEmpty(),
                postTradeProfileId = breakFact.postTradeProfileId,
                postTradePolicyVersion = breakFact.postTradePolicyVersion,
                occurredAt = breakFact.occurredAt,
                updatedAt = listOfNotNull(breakFact.occurredAt, repair?.occurredAt, resolution?.occurredAt).maxOrNull()
                    ?: breakFact.occurredAt
            )
        }
        val exceptions = (clearingExceptions + settlementBreakExceptions)
            .sortedWith(compareBy<SettlementExceptionView> { it.updatedAt }.thenBy { it.settlementExceptionId })
        return SettlementExceptionQueueView(
            scenarioRunId = facts.scenarioRunId,
            exceptionsCount = exceptions.size,
            openCount = exceptions.count { it.exceptionState == SettlementExceptionOpenState },
            repairPostedCount = exceptions.count { it.exceptionState == SettlementRepairPostedState },
            resolvedCount = exceptions.count { it.exceptionState == SettlementExceptionResolvedState },
            clearingRejectedCount = exceptions.count { it.exceptionType == SettlementExceptionTypeClearingRejected },
            settlementBreakCount = exceptions.count { it.exceptionType == SettlementExceptionTypeSettlementBreak },
            exceptions = exceptions
        )
    }
}

package com.reef.platform.application.settlement

import java.time.Instant

const val SettlementObligationCreatedState = "OBLIGATION_CREATED"
const val SettlementAllocationProposedState = "ALLOCATION_PROPOSED"
const val SettlementConfirmationGeneratedState = "CONFIRMATION_GENERATED"
const val SettlementAffirmationAcceptedState = "AFFIRMATION_ACCEPTED"
const val SettlementAffirmationActorType = "SYSTEM"
const val SettlementAffirmationActorId = "post-trade-auto-affirmer"
const val SettlementClearingSubmittedState = "CLEARING_SUBMITTED"
const val SettlementClearingAcceptedState = "CLEARING_ACCEPTED"
const val SettlementClearingRejectedState = "CLEARING_REJECTED"
const val SettlementClearingRejectedReason = "CLEARING_REJECT"
const val SettlementNovationRecordedState = "NOVATION_RECORDED"
const val SettlementInstructionCreatedState = "INSTRUCTION_CREATED"
const val SettlementInstructionTypeDvp = "DVP"
const val SettlementAttemptStartedState = "ATTEMPT_STARTED"
const val SettlementLegTypeCash = "CASH"
const val SettlementLegTypeSecurity = "SECURITY"
const val SettlementLegSucceededState = "LEG_SUCCEEDED"
const val SettlementLegFailedState = "LEG_FAILED"
const val SettlementLedgerEntryTypeCash = "CASH"
const val SettlementLedgerEntryTypeSecurity = "SECURITY"
const val SettlementLedgerDirectionDebit = "DEBIT"
const val SettlementLedgerDirectionCredit = "CREDIT"
const val SettlementSettledState = "SETTLED"
const val SettlementBreakOpenedReason = "CASH_LEG_FAILED"
const val SettlementBreakOpenedReasonSecurity = "SECURITY_LEG_FAILED"
const val SettlementBreakOpenedState = "BROKEN"
const val SettlementRepairPostedActionCash = "POST_CASH_LEG_REPAIR"
const val SettlementRepairPostedActionSecurity = "POST_SECURITY_LEG_REPAIR"
const val SettlementRepairPostedAction = SettlementRepairPostedActionCash
const val SettlementRepairPostedActorType = "USER"
const val SettlementResolvedState = "RESOLVED"
const val SettlementOperatorActionForceSettle = "FORCE_SETTLE"
const val SettlementOperatorActionReverseLedgerEntry = "REVERSE_LEDGER_ENTRY"
const val DefaultPostTradeProfileId = "ops-realistic-v1"
const val DefaultPostTradePolicyVersion = 1

data class SettlementObligationCreatedFact(
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val tradeId: String,
    val buyerParticipantId: String,
    val sellerParticipantId: String,
    val instrumentId: String,
    val quantity: String,
    val cashAmount: String,
    val currency: String,
    val state: String = SettlementObligationCreatedState,
    val occurredAt: Instant
)

data class SettlementAllocationProposedFact(
    val settlementAllocationId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val tradeId: String,
    val buyOrderId: String,
    val sellOrderId: String,
    val buyerAccountId: String,
    val sellerAccountId: String,
    val quantity: String,
    val state: String = SettlementAllocationProposedState,
    val occurredAt: Instant
)

data class SettlementConfirmationGeneratedFact(
    val settlementConfirmationId: String,
    val settlementAllocationId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val tradeId: String,
    val state: String = SettlementConfirmationGeneratedState,
    val occurredAt: Instant
)

data class SettlementAffirmationAcceptedFact(
    val settlementAffirmationId: String,
    val settlementConfirmationId: String,
    val settlementAllocationId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val tradeId: String,
    val actorType: String = SettlementAffirmationActorType,
    val actorId: String = SettlementAffirmationActorId,
    val state: String = SettlementAffirmationAcceptedState,
    val occurredAt: Instant
)

data class SettlementClearingSubmittedFact(
    val settlementClearingSubmissionId: String,
    val settlementObligationId: String,
    val settlementAffirmationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val state: String = SettlementClearingSubmittedState,
    val occurredAt: Instant
)

data class SettlementClearingAcceptedFact(
    val settlementClearingAcceptanceId: String,
    val settlementClearingSubmissionId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val state: String = SettlementClearingAcceptedState,
    val occurredAt: Instant
)

data class SettlementClearingRejectedFact(
    val settlementClearingRejectionId: String,
    val settlementClearingSubmissionId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val reason: String = SettlementClearingRejectedReason,
    val state: String = SettlementClearingRejectedState,
    val occurredAt: Instant
)

data class SettlementNovationRecordedFact(
    val settlementNovationId: String,
    val settlementClearingAcceptanceId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val state: String = SettlementNovationRecordedState,
    val occurredAt: Instant
)

data class SettlementInstructionCreatedFact(
    val settlementInstructionId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val instructionType: String = SettlementInstructionTypeDvp,
    val state: String = SettlementInstructionCreatedState,
    val occurredAt: Instant
)

data class SettlementAttemptStartedFact(
    val settlementAttemptId: String,
    val settlementObligationId: String,
    val settlementInstructionId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val attemptNumber: Int = 1,
    val state: String = SettlementAttemptStartedState,
    val occurredAt: Instant
)

data class SettlementResourcePositionFact(
    val resourcePositionId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val participantId: String,
    val accountId: String,
    val assetType: String,
    val assetId: String,
    val quantity: String,
    val occurredAt: Instant
)

data class SettlementLegOutcomeFact(
    val settlementLegOutcomeId: String,
    val settlementObligationId: String,
    val settlementInstructionId: String,
    val settlementAttemptId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val legType: String,
    val state: String = SettlementLegSucceededState,
    val occurredAt: Instant
)

data class SettlementLedgerEntryFact(
    val ledgerEntryId: String,
    val settlementObligationId: String,
    val settlementInstructionId: String,
    val settlementAttemptId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val participantId: String,
    val accountId: String,
    val assetType: String,
    val assetId: String,
    val direction: String,
    val quantity: String,
    val occurredAt: Instant
)

data class SettlementSettledFact(
    val settlementId: String,
    val settlementObligationId: String,
    val settlementInstructionId: String,
    val settlementAttemptId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val settlementState: String = SettlementSettledState,
    val occurredAt: Instant
)

data class SettlementBreakOpenedFact(
    val settlementBreakId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val reason: String = SettlementBreakOpenedReason,
    val state: String = SettlementBreakOpenedState,
    val occurredAt: Instant
)

data class SettlementRepairPostedFact(
    val settlementRepairId: String,
    val settlementBreakId: String,
    val settlementObligationId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val repairAction: String = SettlementRepairPostedAction,
    val actorType: String = SettlementRepairPostedActorType,
    val actorId: String,
    val occurredAt: Instant
)

data class SettlementResolvedFact(
    val settlementResolutionId: String,
    val settlementObligationId: String,
    val settlementBreakId: String,
    val settlementRepairId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val settlementState: String = SettlementResolvedState,
    val exceptionState: String = SettlementResolvedState,
    val occurredAt: Instant
)

data class SettlementOperatorActionFact(
    val settlementOperatorActionId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String = DefaultPostTradeProfileId,
    val postTradePolicyVersion: Int = DefaultPostTradePolicyVersion,
    val correlationId: String,
    val causationId: String,
    val action: String,
    val targetId: String,
    val reasonNote: String,
    val actorType: String = SettlementRepairPostedActorType,
    val actorId: String,
    val occurredAt: Instant
)

data class SettlementFactBundle(
    val scenarioRunId: String,
    val resourcePositions: List<SettlementResourcePositionFact> = emptyList(),
    val obligations: List<SettlementObligationCreatedFact> = emptyList(),
    val allocations: List<SettlementAllocationProposedFact> = emptyList(),
    val confirmations: List<SettlementConfirmationGeneratedFact> = emptyList(),
    val affirmations: List<SettlementAffirmationAcceptedFact> = emptyList(),
    val clearingSubmissions: List<SettlementClearingSubmittedFact> = emptyList(),
    val clearingAcceptances: List<SettlementClearingAcceptedFact> = emptyList(),
    val clearingRejections: List<SettlementClearingRejectedFact> = emptyList(),
    val novations: List<SettlementNovationRecordedFact> = emptyList(),
    val instructions: List<SettlementInstructionCreatedFact> = emptyList(),
    val attempts: List<SettlementAttemptStartedFact> = emptyList(),
    val legOutcomes: List<SettlementLegOutcomeFact> = emptyList(),
    val ledgerEntries: List<SettlementLedgerEntryFact> = emptyList(),
    val settlements: List<SettlementSettledFact> = emptyList(),
    val breaks: List<SettlementBreakOpenedFact> = emptyList(),
    val repairs: List<SettlementRepairPostedFact> = emptyList(),
    val resolutions: List<SettlementResolvedFact> = emptyList(),
    val operatorActions: List<SettlementOperatorActionFact> = emptyList()
) {
    fun isEmpty(): Boolean =
        resourcePositions.isEmpty() && obligations.isEmpty() && allocations.isEmpty() &&
            confirmations.isEmpty() && affirmations.isEmpty() && clearingSubmissions.isEmpty() &&
            clearingAcceptances.isEmpty() && clearingRejections.isEmpty() && novations.isEmpty() &&
            instructions.isEmpty() && attempts.isEmpty() &&
            legOutcomes.isEmpty() && ledgerEntries.isEmpty() && settlements.isEmpty() &&
            breaks.isEmpty() && repairs.isEmpty() && resolutions.isEmpty() && operatorActions.isEmpty()
}

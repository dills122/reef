package com.reef.platform.application.settlement

import java.sql.ResultSet

internal fun ResultSet.toResourcePosition(): SettlementResourcePositionFact {
    return SettlementResourcePositionFact(
        resourcePositionId = getString(1),
        scenarioRunId = getString(2),
        postTradeProfileId = getString(3),
        postTradePolicyVersion = getInt(4),
        correlationId = getString(5),
        causationId = getString(6),
        participantId = getString(7),
        accountId = getString(8),
        assetType = getString(9),
        assetId = getString(10),
        quantity = getString(11),
        occurredAt = getTimestamp(12).toInstant()
    )
}

internal fun ResultSet.toObligation(): SettlementObligationCreatedFact {
    return SettlementObligationCreatedFact(
        settlementObligationId = getString(1),
        scenarioRunId = getString(2),
        postTradeProfileId = getString(14),
        postTradePolicyVersion = getInt(15),
        correlationId = getString(3),
        causationId = getString(4),
        tradeId = getString(5),
        buyerParticipantId = getString(6),
        sellerParticipantId = getString(7),
        instrumentId = getString(8),
        quantity = getString(9),
        cashAmount = getString(10),
        currency = getString(11),
        state = getString(12),
        occurredAt = getTimestamp(13).toInstant()
    )
}

internal fun ResultSet.toAllocation(): SettlementAllocationProposedFact {
    return SettlementAllocationProposedFact(
        settlementAllocationId = getString(1),
        settlementObligationId = getString(2),
        scenarioRunId = getString(3),
        postTradeProfileId = getString(4),
        postTradePolicyVersion = getInt(5),
        correlationId = getString(6),
        causationId = getString(7),
        tradeId = getString(8),
        buyOrderId = getString(9),
        sellOrderId = getString(10),
        buyerAccountId = getString(11),
        sellerAccountId = getString(12),
        quantity = getString(13),
        state = getString(14),
        occurredAt = getTimestamp(15).toInstant()
    )
}

internal fun ResultSet.toConfirmation(): SettlementConfirmationGeneratedFact {
    return SettlementConfirmationGeneratedFact(
        settlementConfirmationId = getString(1),
        settlementAllocationId = getString(2),
        settlementObligationId = getString(3),
        scenarioRunId = getString(4),
        postTradeProfileId = getString(5),
        postTradePolicyVersion = getInt(6),
        correlationId = getString(7),
        causationId = getString(8),
        tradeId = getString(9),
        state = getString(10),
        occurredAt = getTimestamp(11).toInstant()
    )
}

internal fun ResultSet.toAffirmation(): SettlementAffirmationAcceptedFact {
    return SettlementAffirmationAcceptedFact(
        settlementAffirmationId = getString(1),
        settlementConfirmationId = getString(2),
        settlementAllocationId = getString(3),
        settlementObligationId = getString(4),
        scenarioRunId = getString(5),
        postTradeProfileId = getString(6),
        postTradePolicyVersion = getInt(7),
        correlationId = getString(8),
        causationId = getString(9),
        tradeId = getString(10),
        actorType = getString(11),
        actorId = getString(12),
        state = getString(13),
        occurredAt = getTimestamp(14).toInstant()
    )
}

internal fun ResultSet.toClearingSubmission(): SettlementClearingSubmittedFact {
    return SettlementClearingSubmittedFact(
        settlementClearingSubmissionId = getString(1),
        settlementObligationId = getString(2),
        settlementAffirmationId = getString(3),
        scenarioRunId = getString(4),
        postTradeProfileId = getString(5),
        postTradePolicyVersion = getInt(6),
        correlationId = getString(7),
        causationId = getString(8),
        state = getString(9),
        occurredAt = getTimestamp(10).toInstant()
    )
}

internal fun ResultSet.toClearingAcceptance(): SettlementClearingAcceptedFact {
    return SettlementClearingAcceptedFact(
        settlementClearingAcceptanceId = getString(1),
        settlementClearingSubmissionId = getString(2),
        settlementObligationId = getString(3),
        scenarioRunId = getString(4),
        postTradeProfileId = getString(5),
        postTradePolicyVersion = getInt(6),
        correlationId = getString(7),
        causationId = getString(8),
        state = getString(9),
        occurredAt = getTimestamp(10).toInstant()
    )
}

internal fun ResultSet.toClearingRejection(): SettlementClearingRejectedFact {
    return SettlementClearingRejectedFact(
        settlementClearingRejectionId = getString(1),
        settlementClearingSubmissionId = getString(2),
        settlementObligationId = getString(3),
        scenarioRunId = getString(4),
        postTradeProfileId = getString(5),
        postTradePolicyVersion = getInt(6),
        correlationId = getString(7),
        causationId = getString(8),
        reason = getString(9),
        state = getString(10),
        occurredAt = getTimestamp(11).toInstant()
    )
}

internal fun ResultSet.toNovation(): SettlementNovationRecordedFact {
    return SettlementNovationRecordedFact(
        settlementNovationId = getString(1),
        settlementClearingAcceptanceId = getString(2),
        settlementObligationId = getString(3),
        scenarioRunId = getString(4),
        postTradeProfileId = getString(5),
        postTradePolicyVersion = getInt(6),
        correlationId = getString(7),
        causationId = getString(8),
        state = getString(9),
        occurredAt = getTimestamp(10).toInstant()
    )
}

internal fun ResultSet.toAttempt(): SettlementAttemptStartedFact {
    return SettlementAttemptStartedFact(
        settlementAttemptId = getString(1),
        settlementObligationId = getString(2),
        scenarioRunId = getString(3),
        settlementInstructionId = getString(4),
        postTradeProfileId = getString(5),
        postTradePolicyVersion = getInt(6),
        correlationId = getString(7),
        causationId = getString(8),
        attemptNumber = getInt(9),
        state = getString(10),
        occurredAt = getTimestamp(11).toInstant()
    )
}

internal fun ResultSet.toInstruction(): SettlementInstructionCreatedFact {
    return SettlementInstructionCreatedFact(
        settlementInstructionId = getString(1),
        settlementObligationId = getString(2),
        scenarioRunId = getString(3),
        postTradeProfileId = getString(4),
        postTradePolicyVersion = getInt(5),
        correlationId = getString(6),
        causationId = getString(7),
        instructionType = getString(8),
        state = getString(9),
        occurredAt = getTimestamp(10).toInstant()
    )
}

internal fun ResultSet.toLegOutcome(): SettlementLegOutcomeFact {
    return SettlementLegOutcomeFact(
        settlementLegOutcomeId = getString(1),
        settlementObligationId = getString(2),
        settlementInstructionId = getString(3),
        settlementAttemptId = getString(4),
        scenarioRunId = getString(5),
        postTradeProfileId = getString(6),
        postTradePolicyVersion = getInt(7),
        correlationId = getString(8),
        causationId = getString(9),
        legType = getString(10),
        state = getString(11),
        occurredAt = getTimestamp(12).toInstant()
    )
}

internal fun ResultSet.toLedgerEntry(): SettlementLedgerEntryFact {
    return SettlementLedgerEntryFact(
        ledgerEntryId = getString(1),
        settlementObligationId = getString(2),
        settlementInstructionId = getString(3),
        settlementAttemptId = getString(4),
        scenarioRunId = getString(5),
        postTradeProfileId = getString(6),
        postTradePolicyVersion = getInt(7),
        correlationId = getString(8),
        causationId = getString(9),
        participantId = getString(10),
        accountId = getString(11),
        assetType = getString(12),
        assetId = getString(13),
        direction = getString(14),
        quantity = getString(15),
        occurredAt = getTimestamp(16).toInstant()
    )
}

internal fun ResultSet.toSettlement(): SettlementSettledFact {
    return SettlementSettledFact(
        settlementId = getString(1),
        settlementObligationId = getString(2),
        settlementInstructionId = getString(3),
        settlementAttemptId = getString(4),
        scenarioRunId = getString(5),
        postTradeProfileId = getString(6),
        postTradePolicyVersion = getInt(7),
        correlationId = getString(8),
        causationId = getString(9),
        settlementState = getString(10),
        occurredAt = getTimestamp(11).toInstant()
    )
}

internal fun ResultSet.toBreak(): SettlementBreakOpenedFact {
    return SettlementBreakOpenedFact(
        settlementBreakId = getString(1),
        settlementObligationId = getString(2),
        scenarioRunId = getString(3),
        postTradeProfileId = getString(9),
        postTradePolicyVersion = getInt(10),
        correlationId = getString(4),
        causationId = getString(5),
        reason = getString(6),
        state = getString(7),
        occurredAt = getTimestamp(8).toInstant()
    )
}

internal fun ResultSet.toRepair(): SettlementRepairPostedFact {
    return SettlementRepairPostedFact(
        settlementRepairId = getString(1),
        settlementBreakId = getString(2),
        settlementObligationId = getString(3),
        scenarioRunId = getString(4),
        postTradeProfileId = getString(11),
        postTradePolicyVersion = getInt(12),
        correlationId = getString(5),
        causationId = getString(6),
        repairAction = getString(7),
        actorType = getString(8),
        actorId = getString(9),
        occurredAt = getTimestamp(10).toInstant()
    )
}

internal fun ResultSet.toResolution(): SettlementResolvedFact {
    return SettlementResolvedFact(
        settlementResolutionId = getString(1),
        settlementObligationId = getString(2),
        settlementBreakId = getString(3),
        settlementRepairId = getString(4),
        scenarioRunId = getString(5),
        postTradeProfileId = getString(11),
        postTradePolicyVersion = getInt(12),
        correlationId = getString(6),
        causationId = getString(7),
        settlementState = getString(8),
        exceptionState = getString(9),
        occurredAt = getTimestamp(10).toInstant()
    )
}

internal fun ResultSet.toOperatorAction(): SettlementOperatorActionFact {
    return SettlementOperatorActionFact(
        settlementOperatorActionId = getString(1),
        scenarioRunId = getString(2),
        postTradeProfileId = getString(3),
        postTradePolicyVersion = getInt(4),
        correlationId = getString(5),
        causationId = getString(6),
        action = getString(7),
        targetId = getString(8),
        reasonNote = getString(9),
        actorType = getString(10),
        actorId = getString(11),
        occurredAt = getTimestamp(12).toInstant()
    )
}

package com.reef.platform.application.settlement

internal fun validateSettlementFacts(facts: SettlementFactBundle) {
    require(facts.scenarioRunId.isNotBlank()) { "scenarioRunId is required" }
    val obligations = facts.obligations.associateBy { it.settlementObligationId }
    val allocations = facts.allocations.associateBy { it.settlementAllocationId }
    val confirmations = facts.confirmations.associateBy { it.settlementConfirmationId }
    val affirmations = facts.affirmations.associateBy { it.settlementAffirmationId }
    val clearingSubmissions = facts.clearingSubmissions.associateBy { it.settlementClearingSubmissionId }
    val clearingAcceptances = facts.clearingAcceptances.associateBy { it.settlementClearingAcceptanceId }
    val instructions = facts.instructions.associateBy { it.settlementInstructionId }
    val attempts = facts.attempts.associateBy { it.settlementAttemptId }
    val legOutcomesByAttempt = facts.legOutcomes.groupBy { it.settlementAttemptId }
    val ledgerEntriesByAttempt = facts.ledgerEntries.groupBy { it.settlementAttemptId }
    val breaks = facts.breaks.associateBy { it.settlementBreakId }
    val repairs = facts.repairs.associateBy { it.settlementRepairId }
    val profileEvidence = (
        facts.resourcePositions.map { it.postTradeProfileId to it.postTradePolicyVersion } +
        facts.obligations.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.allocations.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.confirmations.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.affirmations.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.clearingSubmissions.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.clearingAcceptances.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.clearingRejections.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.novations.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.instructions.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.attempts.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.legOutcomes.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.ledgerEntries.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.settlements.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.breaks.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.repairs.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.resolutions.map { it.postTradeProfileId to it.postTradePolicyVersion } +
            facts.operatorActions.map { it.postTradeProfileId to it.postTradePolicyVersion }
        ).toSet()
    require(profileEvidence.size <= 1) { "settlement facts must use one post-trade profile per scenarioRunId" }

    facts.resourcePositions.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.resourcePositionId.isNotBlank()) { "resourcePositionId is required" }
        require(it.participantId.isNotBlank()) { "resource position participantId is required" }
        require(it.accountId.isNotBlank()) { "resource position accountId is required" }
        require(it.assetType in setOf(SettlementLedgerEntryTypeCash, SettlementLedgerEntryTypeSecurity)) {
            "resource position assetType must be CASH or SECURITY"
        }
        require(it.assetId.isNotBlank()) { "resource position assetId is required" }
        require(it.quantity.isNotBlank()) { "resource position quantity is required" }
        it.quantity.toSettlementQuantity()
    }

    facts.obligations.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.tradeId.isNotBlank()) { "tradeId is required" }
        require(it.buyerParticipantId.isNotBlank()) { "buyerParticipantId is required" }
        require(it.sellerParticipantId.isNotBlank()) { "sellerParticipantId is required" }
        require(it.instrumentId.isNotBlank()) { "instrumentId is required" }
        require(it.quantity.isNotBlank()) { "quantity is required" }
        require(it.cashAmount.isNotBlank()) { "cashAmount is required" }
        require(it.currency.isNotBlank()) { "currency is required" }
        require(it.state == SettlementObligationCreatedState) { "obligation state must be $SettlementObligationCreatedState" }
    }

    facts.allocations.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementAllocationId.isNotBlank()) { "settlementAllocationId is required" }
        val obligation = obligations[it.settlementObligationId]
        require(obligation != null) { "allocation must reference existing obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, obligation)) {
            "allocation post-trade profile must match obligation"
        }
        require(it.tradeId.isNotBlank()) { "allocation tradeId is required" }
        require(obligation.tradeId == it.tradeId) { "allocation tradeId must match obligation tradeId" }
        require(it.buyOrderId.isNotBlank()) { "allocation buyOrderId is required" }
        require(it.sellOrderId.isNotBlank()) { "allocation sellOrderId is required" }
        require(it.buyerAccountId.isNotBlank()) { "allocation buyerAccountId is required" }
        require(it.sellerAccountId.isNotBlank()) { "allocation sellerAccountId is required" }
        require(it.quantity.isNotBlank()) { "allocation quantity is required" }
        it.quantity.toSettlementQuantity()
        require(it.state == SettlementAllocationProposedState) { "allocation state must be $SettlementAllocationProposedState" }
    }

    facts.confirmations.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementConfirmationId.isNotBlank()) { "settlementConfirmationId is required" }
        val allocation = allocations[it.settlementAllocationId]
        require(allocation != null) { "confirmation must reference existing allocation" }
        require(allocation.settlementObligationId == it.settlementObligationId) {
            "confirmation obligation must match allocation obligation"
        }
        require(allocation.tradeId == it.tradeId) { "confirmation tradeId must match allocation tradeId" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, allocation)) {
            "confirmation post-trade profile must match allocation"
        }
        val obligation = obligations[it.settlementObligationId]
        require(obligation != null) { "confirmation must reference existing obligation" }
        require(it.state == SettlementConfirmationGeneratedState) {
            "confirmation state must be $SettlementConfirmationGeneratedState"
        }
    }

    facts.affirmations.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementAffirmationId.isNotBlank()) { "settlementAffirmationId is required" }
        val confirmation = confirmations[it.settlementConfirmationId]
        require(confirmation != null) { "affirmation must reference existing confirmation" }
        require(confirmation.settlementAllocationId == it.settlementAllocationId) {
            "affirmation allocation must match confirmation allocation"
        }
        require(confirmation.settlementObligationId == it.settlementObligationId) {
            "affirmation obligation must match confirmation obligation"
        }
        require(confirmation.tradeId == it.tradeId) { "affirmation tradeId must match confirmation tradeId" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, confirmation)) {
            "affirmation post-trade profile must match confirmation"
        }
        require(it.actorType == SettlementAffirmationActorType) { "affirmation actorType must be $SettlementAffirmationActorType" }
        require(it.actorId.isNotBlank()) { "affirmation actorId is required" }
        require(it.state == SettlementAffirmationAcceptedState) {
            "affirmation state must be $SettlementAffirmationAcceptedState"
        }
    }

    facts.clearingSubmissions.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementClearingSubmissionId.isNotBlank()) { "settlementClearingSubmissionId is required" }
        val affirmation = affirmations[it.settlementAffirmationId]
        require(affirmation != null) { "clearing submission must reference existing affirmation" }
        require(affirmation.settlementObligationId == it.settlementObligationId) {
            "clearing submission obligation must match affirmation obligation"
        }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, affirmation)) {
            "clearing submission post-trade profile must match affirmation"
        }
        require(obligations[it.settlementObligationId] != null) { "clearing submission must reference existing obligation" }
        require(it.state == SettlementClearingSubmittedState) {
            "clearing submission state must be $SettlementClearingSubmittedState"
        }
    }

    facts.clearingAcceptances.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementClearingAcceptanceId.isNotBlank()) { "settlementClearingAcceptanceId is required" }
        val submission = clearingSubmissions[it.settlementClearingSubmissionId]
        require(submission != null) { "clearing acceptance must reference existing clearing submission" }
        require(submission.settlementObligationId == it.settlementObligationId) {
            "clearing acceptance obligation must match clearing submission obligation"
        }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, submission)) {
            "clearing acceptance post-trade profile must match clearing submission"
        }
        require(obligations[it.settlementObligationId] != null) { "clearing acceptance must reference existing obligation" }
        require(it.state == SettlementClearingAcceptedState) {
            "clearing acceptance state must be $SettlementClearingAcceptedState"
        }
    }

    facts.clearingRejections.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementClearingRejectionId.isNotBlank()) { "settlementClearingRejectionId is required" }
        val submission = clearingSubmissions[it.settlementClearingSubmissionId]
        require(submission != null) { "clearing rejection must reference existing clearing submission" }
        require(submission.settlementObligationId == it.settlementObligationId) {
            "clearing rejection obligation must match clearing submission obligation"
        }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, submission)) {
            "clearing rejection post-trade profile must match clearing submission"
        }
        require(obligations[it.settlementObligationId] != null) { "clearing rejection must reference existing obligation" }
        require(it.reason == SettlementClearingRejectedReason) {
            "clearing rejection reason must be $SettlementClearingRejectedReason"
        }
        require(it.state == SettlementClearingRejectedState) {
            "clearing rejection state must be $SettlementClearingRejectedState"
        }
    }

    facts.novations.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementNovationId.isNotBlank()) { "settlementNovationId is required" }
        val acceptance = clearingAcceptances[it.settlementClearingAcceptanceId]
        require(acceptance != null) { "novation must reference existing clearing acceptance" }
        require(acceptance.settlementObligationId == it.settlementObligationId) {
            "novation obligation must match clearing acceptance obligation"
        }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, acceptance)) {
            "novation post-trade profile must match clearing acceptance"
        }
        require(obligations[it.settlementObligationId] != null) { "novation must reference existing obligation" }
        require(it.state == SettlementNovationRecordedState) { "novation state must be $SettlementNovationRecordedState" }
    }

    facts.attempts.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementAttemptId.isNotBlank()) { "settlementAttemptId is required" }
        val obligation = obligations[it.settlementObligationId]
        require(obligation != null) { "attempt must reference existing obligation" }
        val instruction = instructions[it.settlementInstructionId]
        require(instruction != null) { "attempt must reference existing instruction" }
        require(instruction.settlementObligationId == it.settlementObligationId) { "attempt obligation must match instruction obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, instruction)) {
            "attempt post-trade profile must match instruction"
        }
        require(it.attemptNumber > 0) { "attemptNumber must be positive" }
        require(it.state == SettlementAttemptStartedState) { "attempt state must be $SettlementAttemptStartedState" }
    }

    facts.instructions.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementInstructionId.isNotBlank()) { "settlementInstructionId is required" }
        val obligation = obligations[it.settlementObligationId]
        require(obligation != null) { "instruction must reference existing obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, obligation)) {
            "instruction post-trade profile must match obligation"
        }
        require(it.instructionType == SettlementInstructionTypeDvp) { "instructionType must be $SettlementInstructionTypeDvp" }
        require(it.state == SettlementInstructionCreatedState) { "instruction state must be $SettlementInstructionCreatedState" }
    }

    facts.legOutcomes.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementLegOutcomeId.isNotBlank()) { "settlementLegOutcomeId is required" }
        val attempt = attempts[it.settlementAttemptId]
        require(attempt != null) { "leg outcome must reference existing attempt" }
        require(attempt.settlementObligationId == it.settlementObligationId) { "leg outcome obligation must match attempt obligation" }
        require(attempt.settlementInstructionId == it.settlementInstructionId) { "leg outcome instruction must match attempt instruction" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, attempt)) {
            "leg outcome post-trade profile must match attempt"
        }
        require(it.legType in setOf(SettlementLegTypeCash, SettlementLegTypeSecurity)) { "legType must be CASH or SECURITY" }
        require(it.state in setOf(SettlementLegSucceededState, SettlementLegFailedState)) {
            "leg outcome state must be $SettlementLegSucceededState or $SettlementLegFailedState"
        }
    }

    facts.ledgerEntries.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.ledgerEntryId.isNotBlank()) { "ledgerEntryId is required" }
        val attempt = attempts[it.settlementAttemptId]
        require(attempt != null) { "ledger entry must reference existing attempt" }
        require(attempt.settlementObligationId == it.settlementObligationId) { "ledger entry obligation must match attempt obligation" }
        require(attempt.settlementInstructionId == it.settlementInstructionId) { "ledger entry instruction must match attempt instruction" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, attempt)) {
            "ledger entry post-trade profile must match attempt"
        }
        require(it.participantId.isNotBlank()) { "ledger entry participantId is required" }
        require(it.accountId.isNotBlank()) { "ledger entry accountId is required" }
        require(it.assetType in setOf(SettlementLedgerEntryTypeCash, SettlementLedgerEntryTypeSecurity)) {
            "ledger entry assetType must be CASH or SECURITY"
        }
        require(it.assetId.isNotBlank()) { "ledger entry assetId is required" }
        require(it.direction in setOf(SettlementLedgerDirectionDebit, SettlementLedgerDirectionCredit)) {
            "ledger entry direction must be DEBIT or CREDIT"
        }
        require(it.quantity.isNotBlank()) { "ledger entry quantity is required" }
        it.quantity.toSettlementQuantity()
    }

    facts.settlements.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementId.isNotBlank()) { "settlementId is required" }
        val attempt = attempts[it.settlementAttemptId]
        require(attempt != null) { "settlement must reference existing attempt" }
        require(attempt.settlementObligationId == it.settlementObligationId) { "settlement obligation must match attempt obligation" }
        require(attempt.settlementInstructionId == it.settlementInstructionId) { "settlement instruction must match attempt instruction" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, attempt)) {
            "settlement post-trade profile must match attempt"
        }
        val legTypes = legOutcomesByAttempt[it.settlementAttemptId].orEmpty()
            .filter { leg -> leg.state == SettlementLegSucceededState }
            .map { leg -> leg.legType }
            .toSet()
        require(legTypes.containsAll(setOf(SettlementLegTypeCash, SettlementLegTypeSecurity))) {
            "settlement requires cash and security leg outcomes"
        }
        val ledgerProof = ledgerEntriesByAttempt[it.settlementAttemptId].orEmpty()
        require(ledgerProof.count { entry -> entry.assetType == SettlementLedgerEntryTypeCash } == 2) {
            "settlement requires two cash ledger entries"
        }
        require(ledgerProof.count { entry -> entry.assetType == SettlementLedgerEntryTypeSecurity } == 2) {
            "settlement requires two security ledger entries"
        }
        require(ledgerProof.any { entry -> entry.assetType == SettlementLedgerEntryTypeCash && entry.direction == SettlementLedgerDirectionDebit }) {
            "settlement requires cash debit ledger entry"
        }
        require(ledgerProof.any { entry -> entry.assetType == SettlementLedgerEntryTypeCash && entry.direction == SettlementLedgerDirectionCredit }) {
            "settlement requires cash credit ledger entry"
        }
        require(ledgerProof.any { entry -> entry.assetType == SettlementLedgerEntryTypeSecurity && entry.direction == SettlementLedgerDirectionDebit }) {
            "settlement requires security debit ledger entry"
        }
        require(ledgerProof.any { entry -> entry.assetType == SettlementLedgerEntryTypeSecurity && entry.direction == SettlementLedgerDirectionCredit }) {
            "settlement requires security credit ledger entry"
        }
        val cashDebit = ledgerProof.sumByAssetAndDirection(SettlementLedgerEntryTypeCash, SettlementLedgerDirectionDebit)
        val cashCredit = ledgerProof.sumByAssetAndDirection(SettlementLedgerEntryTypeCash, SettlementLedgerDirectionCredit)
        require(cashDebit.compareTo(cashCredit) == 0) { "settlement cash ledger entries must balance" }
        val securityDebit = ledgerProof.sumByAssetAndDirection(SettlementLedgerEntryTypeSecurity, SettlementLedgerDirectionDebit)
        val securityCredit = ledgerProof.sumByAssetAndDirection(SettlementLedgerEntryTypeSecurity, SettlementLedgerDirectionCredit)
        require(securityDebit.compareTo(securityCredit) == 0) { "settlement security ledger entries must balance" }
        require(it.settlementState == SettlementSettledState) { "settlementState must be $SettlementSettledState" }
    }

    facts.breaks.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        val obligation = obligations[it.settlementObligationId]
        require(obligation != null) { "break must reference existing obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, obligation)) {
            "break post-trade profile must match obligation"
        }
        require(it.reason in setOf(SettlementBreakOpenedReason, SettlementBreakOpenedReasonSecurity)) {
            "break reason must be $SettlementBreakOpenedReason or $SettlementBreakOpenedReasonSecurity"
        }
        require(it.state == SettlementBreakOpenedState) { "break state must be $SettlementBreakOpenedState" }
    }

    facts.repairs.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        val breakFact = breaks[it.settlementBreakId]
        require(breakFact != null) { "repair must reference existing break" }
        require(breakFact.settlementObligationId == it.settlementObligationId) { "repair obligation must match break obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, breakFact)) {
            "repair post-trade profile must match break"
        }
        require(it.repairAction in setOf(SettlementRepairPostedActionCash, SettlementRepairPostedActionSecurity)) {
            "repair action must be $SettlementRepairPostedActionCash or $SettlementRepairPostedActionSecurity"
        }
        require(
            when (breakFact.reason) {
                SettlementBreakOpenedReason -> it.repairAction == SettlementRepairPostedActionCash
                SettlementBreakOpenedReasonSecurity -> it.repairAction == SettlementRepairPostedActionSecurity
                else -> false
            }
        ) { "repair action must match break reason" }
        require(it.actorType == SettlementRepairPostedActorType) { "repair actorType must be $SettlementRepairPostedActorType" }
        require(it.actorId.isNotBlank()) { "actorId is required" }
    }

    facts.resolutions.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        val repair = repairs[it.settlementRepairId]
        require(repair != null) { "resolution must reference existing repair" }
        require(repair.settlementBreakId == it.settlementBreakId) { "resolution break must match repair break" }
        require(repair.settlementObligationId == it.settlementObligationId) { "resolution obligation must match repair obligation" }
        require(profileMatchesParent(it.postTradeProfileId, it.postTradePolicyVersion, repair)) {
            "resolution post-trade profile must match repair"
        }
        require(it.settlementState == SettlementResolvedState) { "settlementState must be $SettlementResolvedState" }
        require(it.exceptionState == SettlementResolvedState) { "exceptionState must be $SettlementResolvedState" }
    }

    facts.operatorActions.forEach {
        requireCommon(it.scenarioRunId, facts.scenarioRunId, it.correlationId, it.causationId)
        requirePostTradeProfileEvidence(it.postTradeProfileId, it.postTradePolicyVersion)
        require(it.settlementOperatorActionId.isNotBlank()) { "settlementOperatorActionId is required" }
        require(it.action in setOf(SettlementOperatorActionForceSettle, SettlementOperatorActionReverseLedgerEntry)) {
            "operator action must be $SettlementOperatorActionForceSettle or $SettlementOperatorActionReverseLedgerEntry"
        }
        require(it.targetId.isNotBlank()) { "operator action targetId is required" }
        require(it.reasonNote.isNotBlank()) { "operator action reasonNote is required" }
        require(it.actorType == SettlementRepairPostedActorType) { "operator action actorType must be $SettlementRepairPostedActorType" }
        require(it.actorId.isNotBlank()) { "operator action actorId is required" }
    }
}

private fun requirePostTradeProfileEvidence(postTradeProfileId: String, postTradePolicyVersion: Int) {
    require(postTradeProfileId.isNotBlank()) { "postTradeProfileId is required" }
    require(postTradePolicyVersion > 0) { "postTradePolicyVersion must be positive" }
}

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementObligationCreatedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementAllocationProposedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementConfirmationGeneratedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementAffirmationAcceptedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementClearingSubmittedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementClearingAcceptedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementInstructionCreatedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementAttemptStartedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementBreakOpenedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun profileMatchesParent(
    postTradeProfileId: String,
    postTradePolicyVersion: Int,
    parent: SettlementRepairPostedFact?
): Boolean = parent != null &&
    parent.postTradeProfileId == postTradeProfileId &&
    parent.postTradePolicyVersion == postTradePolicyVersion

private fun requireCommon(
    factScenarioRunId: String,
    expectedScenarioRunId: String,
    correlationId: String,
    causationId: String
) {
    require(factScenarioRunId == expectedScenarioRunId) { "fact scenarioRunId must match bundle scenarioRunId" }
    require(correlationId.isNotBlank()) { "correlationId is required" }
    require(causationId.isNotBlank()) { "causationId is required" }
}

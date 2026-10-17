package com.reef.platform.application.settlement

import java.util.concurrent.ConcurrentHashMap

class InMemorySettlementFactStore : SettlementFactStore {
    private val resourcePositions = ConcurrentHashMap<String, SettlementResourcePositionFact>()
    private val obligations = ConcurrentHashMap<String, SettlementObligationCreatedFact>()
    private val allocations = ConcurrentHashMap<String, SettlementAllocationProposedFact>()
    private val confirmations = ConcurrentHashMap<String, SettlementConfirmationGeneratedFact>()
    private val affirmations = ConcurrentHashMap<String, SettlementAffirmationAcceptedFact>()
    private val clearingSubmissions = ConcurrentHashMap<String, SettlementClearingSubmittedFact>()
    private val clearingAcceptances = ConcurrentHashMap<String, SettlementClearingAcceptedFact>()
    private val clearingRejections = ConcurrentHashMap<String, SettlementClearingRejectedFact>()
    private val novations = ConcurrentHashMap<String, SettlementNovationRecordedFact>()
    private val instructions = ConcurrentHashMap<String, SettlementInstructionCreatedFact>()
    private val attempts = ConcurrentHashMap<String, SettlementAttemptStartedFact>()
    private val legOutcomes = ConcurrentHashMap<String, SettlementLegOutcomeFact>()
    private val ledgerEntries = ConcurrentHashMap<String, SettlementLedgerEntryFact>()
    private val settlements = ConcurrentHashMap<String, SettlementSettledFact>()
    private val breaks = ConcurrentHashMap<String, SettlementBreakOpenedFact>()
    private val repairs = ConcurrentHashMap<String, SettlementRepairPostedFact>()
    private val resolutions = ConcurrentHashMap<String, SettlementResolvedFact>()
    private val operatorActions = ConcurrentHashMap<String, SettlementOperatorActionFact>()

    @Synchronized
    override fun appendFacts(facts: SettlementFactBundle): SettlementFactBundle {
        if (facts.isEmpty()) return facts
        val existing = factsByScenarioRunId(facts.scenarioRunId)
        validateSettlementFacts(existing.merge(facts))
        fun <T : Any> verifyIdentity(
            stored: ConcurrentHashMap<String, T>,
            candidates: List<T>,
            id: (T) -> String
        ) {
            candidates.forEach { candidate ->
                val previous = stored[id(candidate)]
                require(previous == null || previous == candidate) { "Conflicting settlement fact ${id(candidate)}" }
            }
        }
        verifyIdentity(resourcePositions, facts.resourcePositions) { it.resourcePositionId }
        verifyIdentity(obligations, facts.obligations) { it.settlementObligationId }
        verifyIdentity(allocations, facts.allocations) { it.settlementAllocationId }
        verifyIdentity(confirmations, facts.confirmations) { it.settlementConfirmationId }
        verifyIdentity(affirmations, facts.affirmations) { it.settlementAffirmationId }
        verifyIdentity(clearingSubmissions, facts.clearingSubmissions) { it.settlementClearingSubmissionId }
        verifyIdentity(clearingAcceptances, facts.clearingAcceptances) { it.settlementClearingAcceptanceId }
        verifyIdentity(clearingRejections, facts.clearingRejections) { it.settlementClearingRejectionId }
        verifyIdentity(novations, facts.novations) { it.settlementNovationId }
        verifyIdentity(instructions, facts.instructions) { it.settlementInstructionId }
        verifyIdentity(attempts, facts.attempts) { it.settlementAttemptId }
        verifyIdentity(legOutcomes, facts.legOutcomes) { it.settlementLegOutcomeId }
        verifyIdentity(ledgerEntries, facts.ledgerEntries) { it.ledgerEntryId }
        verifyIdentity(settlements, facts.settlements) { it.settlementId }
        verifyIdentity(breaks, facts.breaks) { it.settlementBreakId }
        verifyIdentity(repairs, facts.repairs) { it.settlementRepairId }
        verifyIdentity(resolutions, facts.resolutions) { it.settlementResolutionId }
        verifyIdentity(operatorActions, facts.operatorActions) { it.settlementOperatorActionId }
        facts.resourcePositions.forEach { resourcePositions.putIfAbsent(it.resourcePositionId, it) }
        facts.obligations.forEach { obligations.putIfAbsent(it.settlementObligationId, it) }
        facts.allocations.forEach { allocations.putIfAbsent(it.settlementAllocationId, it) }
        facts.confirmations.forEach { confirmations.putIfAbsent(it.settlementConfirmationId, it) }
        facts.affirmations.forEach { affirmations.putIfAbsent(it.settlementAffirmationId, it) }
        facts.clearingSubmissions.forEach { clearingSubmissions.putIfAbsent(it.settlementClearingSubmissionId, it) }
        facts.clearingAcceptances.forEach { clearingAcceptances.putIfAbsent(it.settlementClearingAcceptanceId, it) }
        facts.clearingRejections.forEach { clearingRejections.putIfAbsent(it.settlementClearingRejectionId, it) }
        facts.novations.forEach { novations.putIfAbsent(it.settlementNovationId, it) }
        facts.instructions.forEach { instructions.putIfAbsent(it.settlementInstructionId, it) }
        facts.attempts.forEach { attempts.putIfAbsent(it.settlementAttemptId, it) }
        facts.legOutcomes.forEach { legOutcomes.putIfAbsent(it.settlementLegOutcomeId, it) }
        facts.ledgerEntries.forEach { ledgerEntries.putIfAbsent(it.ledgerEntryId, it) }
        facts.settlements.forEach { settlements.putIfAbsent(it.settlementId, it) }
        facts.breaks.forEach { breaks.putIfAbsent(it.settlementBreakId, it) }
        facts.repairs.forEach { repairs.putIfAbsent(it.settlementRepairId, it) }
        facts.resolutions.forEach { resolutions.putIfAbsent(it.settlementResolutionId, it) }
        facts.operatorActions.forEach { operatorActions.putIfAbsent(it.settlementOperatorActionId, it) }
        return facts
    }

    @Synchronized
    override fun factsByScenarioRunId(scenarioRunId: String): SettlementFactBundle {
        require(scenarioRunId.isNotBlank()) { "scenarioRunId is required" }
        return SettlementFactBundle(
            scenarioRunId = scenarioRunId,
            resourcePositions = resourcePositions.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            obligations = obligations.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            allocations = allocations.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            confirmations = confirmations.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            affirmations = affirmations.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            clearingSubmissions = clearingSubmissions.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            clearingAcceptances = clearingAcceptances.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            clearingRejections = clearingRejections.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            novations = novations.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            instructions = instructions.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            attempts = attempts.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            legOutcomes = legOutcomes.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            ledgerEntries = ledgerEntries.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            settlements = settlements.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            breaks = breaks.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            repairs = repairs.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            resolutions = resolutions.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt },
            operatorActions = operatorActions.values.filter { it.scenarioRunId == scenarioRunId }.sortedBy { it.occurredAt }
        )
    }
}

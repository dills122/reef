package com.reef.platform.application.settlement

import java.security.MessageDigest
import java.time.Instant

data class SettlementScenarioProofView(
    val scenarioRunId: String,
    val proofStatus: String,
    val checksumAlgorithm: String,
    val checksum: String,
    val factsCount: Int,
    val obligationsCount: Int,
    val instructionsCount: Int,
    val attemptsCount: Int,
    val legOutcomesCount: Int,
    val ledgerEntriesCount: Int,
    val settlementsCount: Int,
    val breaksCount: Int,
    val repairsCount: Int,
    val resolutionsCount: Int,
    val operatorActionsCount: Int,
    val profilePolicies: List<SettlementProofProfilePolicyView>,
    val causationGaps: List<SettlementProofGapView>,
    val obligations: List<SettlementProofObligationView>,
    val balances: List<SettlementLedgerBalanceView>,
    val settlementProofs: List<SettlementLedgerProofView>,
    val updatedAt: Instant
)

data class SettlementProofProfilePolicyView(
    val postTradeProfileId: String,
    val postTradePolicyVersion: Int,
    val factCount: Int
)

data class SettlementProofGapView(
    val factType: String,
    val factId: String,
    val missingReferenceType: String,
    val missingReferenceId: String
)

data class SettlementProofObligationView(
    val settlementObligationId: String,
    val tradeId: String,
    val buyerParticipantId: String,
    val sellerParticipantId: String,
    val instrumentId: String,
    val quantity: String,
    val cashAmount: String,
    val currency: String,
    val settlementInstructionIds: List<String>,
    val settlementAttemptIds: List<String>,
    val ledgerEntryIds: List<String>,
    val settlementIds: List<String>,
    val settlementBreakIds: List<String>,
    val settlementRepairIds: List<String>,
    val settlementResolutionIds: List<String>
)

object SettlementScenarioProofProjection {
    const val ProofStatusClean = "CLEAN"
    const val ProofStatusGapped = "GAPPED"

    fun project(facts: SettlementFactBundle): SettlementScenarioProofView {
        val ledger = SettlementLedgerProjection.project(facts)
        val causationGaps = causationGaps(facts)
        val profilePolicies = profilePolicies(facts)
        val obligations = facts.obligations
            .sortedWith(compareBy<SettlementObligationCreatedFact> { it.occurredAt }.thenBy { it.settlementObligationId })
            .map { obligation ->
                SettlementProofObligationView(
                    settlementObligationId = obligation.settlementObligationId,
                    tradeId = obligation.tradeId,
                    buyerParticipantId = obligation.buyerParticipantId,
                    sellerParticipantId = obligation.sellerParticipantId,
                    instrumentId = obligation.instrumentId,
                    quantity = obligation.quantity,
                    cashAmount = obligation.cashAmount,
                    currency = obligation.currency,
                    settlementInstructionIds = facts.instructions
                        .filter { it.settlementObligationId == obligation.settlementObligationId }
                        .sortedBy { it.settlementInstructionId }
                        .map { it.settlementInstructionId },
                    settlementAttemptIds = facts.attempts
                        .filter { it.settlementObligationId == obligation.settlementObligationId }
                        .sortedBy { it.settlementAttemptId }
                        .map { it.settlementAttemptId },
                    ledgerEntryIds = facts.ledgerEntries
                        .filter { it.settlementObligationId == obligation.settlementObligationId }
                        .sortedBy { it.ledgerEntryId }
                        .map { it.ledgerEntryId },
                    settlementIds = facts.settlements
                        .filter { it.settlementObligationId == obligation.settlementObligationId }
                        .sortedBy { it.settlementId }
                        .map { it.settlementId },
                    settlementBreakIds = facts.breaks
                        .filter { it.settlementObligationId == obligation.settlementObligationId }
                        .sortedBy { it.settlementBreakId }
                        .map { it.settlementBreakId },
                    settlementRepairIds = facts.repairs
                        .filter { it.settlementObligationId == obligation.settlementObligationId }
                        .sortedBy { it.settlementRepairId }
                        .map { it.settlementRepairId },
                    settlementResolutionIds = facts.resolutions
                        .filter { it.settlementObligationId == obligation.settlementObligationId }
                        .sortedBy { it.settlementResolutionId }
                        .map { it.settlementResolutionId }
                )
            }
        val checksumInput = checksumInput(facts, obligations, ledger, causationGaps, profilePolicies)
        return SettlementScenarioProofView(
            scenarioRunId = facts.scenarioRunId,
            proofStatus = if (causationGaps.isEmpty()) ProofStatusClean else ProofStatusGapped,
            checksumAlgorithm = "SHA-256",
            checksum = sha256(checksumInput),
            factsCount = facts.resourcePositions.size + facts.obligations.size + facts.instructions.size +
            facts.attempts.size + facts.legOutcomes.size + facts.ledgerEntries.size + facts.settlements.size +
                facts.breaks.size + facts.repairs.size + facts.resolutions.size + facts.operatorActions.size,
            obligationsCount = facts.obligations.size,
            instructionsCount = facts.instructions.size,
            attemptsCount = facts.attempts.size,
            legOutcomesCount = facts.legOutcomes.size,
            ledgerEntriesCount = facts.ledgerEntries.size,
            settlementsCount = facts.settlements.size,
            breaksCount = facts.breaks.size,
            repairsCount = facts.repairs.size,
            resolutionsCount = facts.resolutions.size,
            operatorActionsCount = facts.operatorActions.size,
            profilePolicies = profilePolicies,
            causationGaps = causationGaps,
            obligations = obligations,
            balances = ledger.balances,
            settlementProofs = ledger.settlementProofs,
            updatedAt = updatedAt(facts)
        )
    }

    private fun causationGaps(facts: SettlementFactBundle): List<SettlementProofGapView> {
        val obligationIds = facts.obligations.map { it.settlementObligationId }.toSet()
        val instructionIds = facts.instructions.map { it.settlementInstructionId }.toSet()
        val attemptIds = facts.attempts.map { it.settlementAttemptId }.toSet()
        val breakIds = facts.breaks.map { it.settlementBreakId }.toSet()
        val repairIds = facts.repairs.map { it.settlementRepairId }.toSet()
        return buildList {
            facts.instructions.forEach {
                if (it.settlementObligationId !in obligationIds) {
                    add(
                        gap(
                            "SettlementInstructionCreated",
                            it.settlementInstructionId,
                            "SettlementObligationCreated",
                            it.settlementObligationId
                        )
                    )
                }
            }
            facts.attempts.forEach {
                if (it.settlementObligationId !in obligationIds) {
                    add(
                        gap(
                            "SettlementAttemptStarted",
                            it.settlementAttemptId,
                            "SettlementObligationCreated",
                            it.settlementObligationId
                        )
                    )
                }
                if (it.settlementInstructionId !in instructionIds) {
                    add(
                        gap(
                            "SettlementAttemptStarted",
                            it.settlementAttemptId,
                            "SettlementInstructionCreated",
                            it.settlementInstructionId
                        )
                    )
                }
            }
            facts.legOutcomes.forEach {
                if (it.settlementObligationId !in obligationIds) {
                    add(
                        gap(
                            "SettlementLegOutcome",
                            it.settlementLegOutcomeId,
                            "SettlementObligationCreated",
                            it.settlementObligationId
                        )
                    )
                }
                if (it.settlementInstructionId !in instructionIds) {
                    add(
                        gap(
                            "SettlementLegOutcome",
                            it.settlementLegOutcomeId,
                            "SettlementInstructionCreated",
                            it.settlementInstructionId
                        )
                    )
                }
                if (it.settlementAttemptId !in attemptIds) {
                    add(
                        gap(
                            "SettlementLegOutcome",
                            it.settlementLegOutcomeId,
                            "SettlementAttemptStarted",
                            it.settlementAttemptId
                        )
                    )
                }
            }
            facts.ledgerEntries.forEach {
                if (it.settlementObligationId !in obligationIds) {
                    add(
                        gap(
                            "SettlementLedgerEntry",
                            it.ledgerEntryId,
                            "SettlementObligationCreated",
                            it.settlementObligationId
                        )
                    )
                }
                if (it.settlementInstructionId !in instructionIds) {
                    add(
                        gap(
                            "SettlementLedgerEntry",
                            it.ledgerEntryId,
                            "SettlementInstructionCreated",
                            it.settlementInstructionId
                        )
                    )
                }
                if (it.settlementAttemptId !in attemptIds) {
                    add(
                        gap(
                            "SettlementLedgerEntry",
                            it.ledgerEntryId,
                            "SettlementAttemptStarted",
                            it.settlementAttemptId
                        )
                    )
                }
            }
            facts.settlements.forEach {
                if (it.settlementObligationId !in obligationIds) {
                    add(
                        gap(
                            "SettlementSettled",
                            it.settlementId,
                            "SettlementObligationCreated",
                            it.settlementObligationId
                        )
                    )
                }
                if (it.settlementInstructionId !in instructionIds) {
                    add(
                        gap(
                            "SettlementSettled",
                            it.settlementId,
                            "SettlementInstructionCreated",
                            it.settlementInstructionId
                        )
                    )
                }
                if (it.settlementAttemptId !in attemptIds) {
                    add(
                        gap(
                            "SettlementSettled",
                            it.settlementId,
                            "SettlementAttemptStarted",
                            it.settlementAttemptId
                        )
                    )
                }
            }
            facts.breaks.forEach {
                if (it.settlementObligationId !in obligationIds) {
                    add(
                        gap(
                            "SettlementBreakOpened",
                            it.settlementBreakId,
                            "SettlementObligationCreated",
                            it.settlementObligationId
                        )
                    )
                }
            }
            facts.repairs.forEach {
                if (it.settlementObligationId !in obligationIds) {
                    add(
                        gap(
                            "SettlementRepairPosted",
                            it.settlementRepairId,
                            "SettlementObligationCreated",
                            it.settlementObligationId
                        )
                    )
                }
                if (it.settlementBreakId !in breakIds) {
                    add(
                        gap(
                            "SettlementRepairPosted",
                            it.settlementRepairId,
                            "SettlementBreakOpened",
                            it.settlementBreakId
                        )
                    )
                }
            }
            facts.resolutions.forEach {
                if (it.settlementObligationId !in obligationIds) {
                    add(
                        gap(
                            "SettlementResolved",
                            it.settlementResolutionId,
                            "SettlementObligationCreated",
                            it.settlementObligationId
                        )
                    )
                }
                if (it.settlementBreakId !in breakIds) {
                    add(
                        gap(
                            "SettlementResolved",
                            it.settlementResolutionId,
                            "SettlementBreakOpened",
                            it.settlementBreakId
                        )
                    )
                }
                if (it.settlementRepairId !in repairIds) {
                    add(
                        gap(
                            "SettlementResolved",
                            it.settlementResolutionId,
                            "SettlementRepairPosted",
                            it.settlementRepairId
                        )
                    )
                }
            }
        }.sortedWith(compareBy<SettlementProofGapView> { it.factType }.thenBy { it.factId })
    }

    private fun gap(
        factType: String,
        factId: String,
        missingReferenceType: String,
        missingReferenceId: String
    ): SettlementProofGapView {
        return SettlementProofGapView(
            factType = factType,
            factId = factId,
            missingReferenceType = missingReferenceType,
            missingReferenceId = missingReferenceId
        )
    }

    private fun checksumInput(
        facts: SettlementFactBundle,
        obligations: List<SettlementProofObligationView>,
        ledger: SettlementLedgerProjectionView,
        causationGaps: List<SettlementProofGapView>,
        profilePolicies: List<SettlementProofProfilePolicyView>
    ): String {
        return buildString {
            appendLine("scenarioRunId=${facts.scenarioRunId}")
            profilePolicies.forEach {
                appendLine("profile|${it.postTradeProfileId}|${it.postTradePolicyVersion}|${it.factCount}")
            }
            causationGaps.forEach {
                appendLine(
                    listOf(
                        "gap",
                        it.factType,
                        it.factId,
                        it.missingReferenceType,
                        it.missingReferenceId
                    ).joinToString("|")
                )
            }
            facts.operatorActions
                .sortedWith(compareBy<SettlementOperatorActionFact> { it.occurredAt }.thenBy { it.settlementOperatorActionId })
                .forEach {
                    appendLine(
                        listOf(
                            "operatorAction",
                            it.settlementOperatorActionId,
                            it.action,
                            it.targetId,
                            it.reasonNote,
                            it.actorId
                        ).joinToString("|")
                    )
                }
            obligations.forEach {
                appendLine(
                    listOf(
                        "obligation",
                        it.settlementObligationId,
                        it.tradeId,
                        it.buyerParticipantId,
                        it.sellerParticipantId,
                        it.instrumentId,
                        it.quantity,
                        it.cashAmount,
                        it.currency,
                        it.settlementInstructionIds.joinToString(","),
                        it.settlementAttemptIds.joinToString(","),
                        it.ledgerEntryIds.joinToString(","),
                        it.settlementIds.joinToString(","),
                        it.settlementBreakIds.joinToString(","),
                        it.settlementRepairIds.joinToString(","),
                        it.settlementResolutionIds.joinToString(",")
                    ).joinToString("|")
                )
            }
            ledger.balances.forEach {
                appendLine(
                    listOf(
                        "balance",
                        it.participantId,
                        it.accountId,
                        it.assetType,
                        it.assetId,
                        it.openingQuantity,
                        it.debitQuantity,
                        it.creditQuantity,
                        it.availableQuantity
                    ).joinToString("|")
                )
            }
            ledger.settlementProofs.forEach {
                appendLine(
                    listOf(
                        "proof",
                        it.settlementId,
                        it.settlementAttemptId,
                        it.cashDebitQuantity,
                        it.cashCreditQuantity,
                        it.securityDebitQuantity,
                        it.securityCreditQuantity,
                        it.cashBalanced,
                        it.securityBalanced
                    ).joinToString("|")
                )
            }
        }
    }

    private fun profilePolicies(facts: SettlementFactBundle): List<SettlementProofProfilePolicyView> {
        return (
            facts.resourcePositions.map { it.postTradeProfileId to it.postTradePolicyVersion } +
                facts.obligations.map { it.postTradeProfileId to it.postTradePolicyVersion } +
                facts.instructions.map { it.postTradeProfileId to it.postTradePolicyVersion } +
                facts.attempts.map { it.postTradeProfileId to it.postTradePolicyVersion } +
                facts.legOutcomes.map { it.postTradeProfileId to it.postTradePolicyVersion } +
                facts.ledgerEntries.map { it.postTradeProfileId to it.postTradePolicyVersion } +
                facts.settlements.map { it.postTradeProfileId to it.postTradePolicyVersion } +
                facts.breaks.map { it.postTradeProfileId to it.postTradePolicyVersion } +
                facts.repairs.map { it.postTradeProfileId to it.postTradePolicyVersion } +
                facts.resolutions.map { it.postTradeProfileId to it.postTradePolicyVersion } +
                facts.operatorActions.map { it.postTradeProfileId to it.postTradePolicyVersion }
            )
            .groupingBy { it }
            .eachCount()
            .map { (profile, count) ->
                SettlementProofProfilePolicyView(
                    postTradeProfileId = profile.first,
                    postTradePolicyVersion = profile.second,
                    factCount = count
                )
            }
            .sortedWith(compareBy<SettlementProofProfilePolicyView> { it.postTradeProfileId }.thenBy { it.postTradePolicyVersion })
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun updatedAt(facts: SettlementFactBundle): Instant {
        return (
            facts.resourcePositions.map { it.occurredAt } +
                facts.obligations.map { it.occurredAt } +
                facts.instructions.map { it.occurredAt } +
                facts.attempts.map { it.occurredAt } +
                facts.legOutcomes.map { it.occurredAt } +
                facts.ledgerEntries.map { it.occurredAt } +
                facts.settlements.map { it.occurredAt } +
                facts.breaks.map { it.occurredAt } +
                facts.repairs.map { it.occurredAt } +
                facts.resolutions.map { it.occurredAt } +
                facts.operatorActions.map { it.occurredAt }
            ).maxOrNull() ?: Instant.EPOCH
    }
}

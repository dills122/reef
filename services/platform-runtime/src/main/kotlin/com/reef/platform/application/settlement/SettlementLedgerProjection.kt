package com.reef.platform.application.settlement

import java.math.BigDecimal
import java.time.Instant

const val SettlementLedgerProofProvenState = "PROVEN"

data class SettlementLedgerProjectionView(
    val scenarioRunId: String,
    val balances: List<SettlementLedgerBalanceView>,
    val settlementProofs: List<SettlementLedgerProofView>
)

data class SettlementLedgerBalanceView(
    val scenarioRunId: String,
    val participantId: String,
    val accountId: String,
    val assetType: String,
    val assetId: String,
    val debitQuantity: String,
    val creditQuantity: String,
    val netQuantity: String,
    val ledgerEntryCount: Int,
    val updatedAt: Instant
)

data class SettlementLedgerProofView(
    val settlementId: String,
    val settlementObligationId: String,
    val settlementInstructionId: String,
    val settlementAttemptId: String,
    val scenarioRunId: String,
    val postTradeProfileId: String,
    val postTradePolicyVersion: Int,
    val settlementState: String,
    val proofState: String,
    val cashDebitQuantity: String,
    val cashCreditQuantity: String,
    val securityDebitQuantity: String,
    val securityCreditQuantity: String,
    val cashBalanced: Boolean,
    val securityBalanced: Boolean,
    val legOutcomeCount: Int,
    val ledgerEntryCount: Int,
    val updatedAt: Instant
)

object SettlementLedgerProjection {
    fun project(facts: SettlementFactBundle): SettlementLedgerProjectionView {
        return SettlementLedgerProjectionView(
            scenarioRunId = facts.scenarioRunId,
            balances = projectBalances(facts),
            settlementProofs = projectProofs(facts)
        )
    }

    private fun projectBalances(facts: SettlementFactBundle): List<SettlementLedgerBalanceView> {
        return facts.ledgerEntries
            .groupBy { BalanceKey(it.participantId, it.accountId, it.assetType, it.assetId) }
            .map { (key, entries) ->
                val debit = entries
                    .filter { it.direction == SettlementLedgerDirectionDebit }
                    .sumQuantities()
                val credit = entries
                    .filter { it.direction == SettlementLedgerDirectionCredit }
                    .sumQuantities()
                SettlementLedgerBalanceView(
                    scenarioRunId = facts.scenarioRunId,
                    participantId = key.participantId,
                    accountId = key.accountId,
                    assetType = key.assetType,
                    assetId = key.assetId,
                    debitQuantity = debit.toSettlementQuantityString(),
                    creditQuantity = credit.toSettlementQuantityString(),
                    netQuantity = (credit - debit).toSettlementQuantityString(),
                    ledgerEntryCount = entries.size,
                    updatedAt = entries.maxOf { it.occurredAt }
                )
            }
            .sortedWith(
                compareBy<SettlementLedgerBalanceView> { it.participantId }
                    .thenBy { it.accountId }
                    .thenBy { it.assetType }
                    .thenBy { it.assetId }
            )
    }

    private fun projectProofs(facts: SettlementFactBundle): List<SettlementLedgerProofView> {
        val legOutcomesByAttempt = facts.legOutcomes.groupBy { it.settlementAttemptId }
        val ledgerEntriesByAttempt = facts.ledgerEntries.groupBy { it.settlementAttemptId }
        return facts.settlements
            .sortedWith(compareBy<SettlementSettledFact> { it.occurredAt }.thenBy { it.settlementId })
            .map { settlement ->
                val legOutcomes = legOutcomesByAttempt[settlement.settlementAttemptId].orEmpty()
                val ledgerEntries = ledgerEntriesByAttempt[settlement.settlementAttemptId].orEmpty()
                val cashDebit = ledgerEntries.sumByAssetAndDirection(
                    assetType = SettlementLedgerEntryTypeCash,
                    direction = SettlementLedgerDirectionDebit
                )
                val cashCredit = ledgerEntries.sumByAssetAndDirection(
                    assetType = SettlementLedgerEntryTypeCash,
                    direction = SettlementLedgerDirectionCredit
                )
                val securityDebit = ledgerEntries.sumByAssetAndDirection(
                    assetType = SettlementLedgerEntryTypeSecurity,
                    direction = SettlementLedgerDirectionDebit
                )
                val securityCredit = ledgerEntries.sumByAssetAndDirection(
                    assetType = SettlementLedgerEntryTypeSecurity,
                    direction = SettlementLedgerDirectionCredit
                )
                SettlementLedgerProofView(
                    settlementId = settlement.settlementId,
                    settlementObligationId = settlement.settlementObligationId,
                    settlementInstructionId = settlement.settlementInstructionId,
                    settlementAttemptId = settlement.settlementAttemptId,
                    scenarioRunId = settlement.scenarioRunId,
                    postTradeProfileId = settlement.postTradeProfileId,
                    postTradePolicyVersion = settlement.postTradePolicyVersion,
                    settlementState = settlement.settlementState,
                    proofState = SettlementLedgerProofProvenState,
                    cashDebitQuantity = cashDebit.toSettlementQuantityString(),
                    cashCreditQuantity = cashCredit.toSettlementQuantityString(),
                    securityDebitQuantity = securityDebit.toSettlementQuantityString(),
                    securityCreditQuantity = securityCredit.toSettlementQuantityString(),
                    cashBalanced = cashDebit.compareTo(cashCredit) == 0,
                    securityBalanced = securityDebit.compareTo(securityCredit) == 0,
                    legOutcomeCount = legOutcomes.size,
                    ledgerEntryCount = ledgerEntries.size,
                    updatedAt = listOf(
                        settlement.occurredAt,
                        legOutcomes.maxOfOrNull { it.occurredAt },
                        ledgerEntries.maxOfOrNull { it.occurredAt }
                    ).filterNotNull().maxOrNull() ?: settlement.occurredAt
                )
            }
    }

    private data class BalanceKey(
        val participantId: String,
        val accountId: String,
        val assetType: String,
        val assetId: String
    )
}

internal fun List<SettlementLedgerEntryFact>.sumByAssetAndDirection(assetType: String, direction: String): BigDecimal {
    return filter { it.assetType == assetType && it.direction == direction }.sumQuantities()
}

internal fun List<SettlementLedgerEntryFact>.sumQuantities(): BigDecimal {
    return fold(BigDecimal.ZERO) { sum, entry -> sum + entry.quantity.toSettlementQuantity() }
}

internal fun String.toSettlementQuantity(): BigDecimal {
    val quantity = try {
        BigDecimal(trim())
    } catch (_: Exception) {
        throw IllegalArgumentException("ledger entry quantity must be numeric")
    }
    require(quantity > BigDecimal.ZERO) { "ledger entry quantity must be positive" }
    return quantity
}

internal fun BigDecimal.toSettlementQuantityString(): String {
    if (compareTo(BigDecimal.ZERO) == 0) return "0"
    return stripTrailingZeros().toPlainString()
}

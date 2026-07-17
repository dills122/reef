package com.reef.platform.application.settlement

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

const val SettlementScoreSameTickPendingHaircut = "1"
const val SettlementScoreRepairPendingHaircut = "0.5"
const val SettlementScoreRejectedHaircut = "0"
const val SettlementScoreFailPenaltyPoints = 100
const val SettlementScoreAgedFailPenaltyPoints = 250
const val SettlementScoreDefaultAgedFailAfterSeconds = 86_400L

data class SettlementScoreProjectionOptions(
    val asOf: Instant? = null,
    val agedFailAfterSeconds: Long = SettlementScoreDefaultAgedFailAfterSeconds
)

data class SettlementScoreProjectionView(
    val scenarioRunId: String,
    val asOf: Instant,
    val agedFailAfterSeconds: Long,
    val participants: List<SettlementParticipantScoreView>,
    val updatedAt: Instant
)

data class SettlementParticipantScoreView(
    val scenarioRunId: String,
    val participantId: String,
    val cashBalances: List<SettlementScoreAssetBalanceView>,
    val securityBalances: List<SettlementScoreAssetBalanceView>,
    val pendingValue: String,
    val haircutAdjustedPendingValue: String,
    val blockedUnsettledValue: String,
    val scorePenaltyPoints: Int,
    val settledObligationCount: Int,
    val pendingObligationCount: Int,
    val failedObligationCount: Int,
    val agedFailCount: Int,
    val openBreakCount: Int,
    val repairPendingCount: Int,
    val updatedAt: Instant
)

data class SettlementScoreAssetBalanceView(
    val assetId: String,
    val availableQuantity: String
)

object SettlementScoreProjection {
    fun project(
        facts: SettlementFactBundle,
        options: SettlementScoreProjectionOptions = SettlementScoreProjectionOptions()
    ): SettlementScoreProjectionView {
        val obligations = SettlementObligationProjection.project(facts)
        val ledger = SettlementLedgerProjection.project(facts)
        val asOf = options.asOf ?: latestFactTime(facts)
        val agedFailAfterSeconds = options.agedFailAfterSeconds.coerceAtLeast(0L)
        val participantIds = (
            obligations.flatMap { listOf(it.buyerParticipantId, it.sellerParticipantId) } +
                ledger.balances.map { it.participantId }
            ).filter { it.isNotBlank() }.toSet()

        val participants = participantIds
            .map { participantId ->
                participantView(
                    scenarioRunId = facts.scenarioRunId,
                    participantId = participantId,
                    obligations = obligations,
                    balances = ledger.balances,
                    facts = facts,
                    asOf = asOf,
                    agedFailAfterSeconds = agedFailAfterSeconds
                )
            }
            .sortedBy { it.participantId }
        return SettlementScoreProjectionView(
            scenarioRunId = facts.scenarioRunId,
            asOf = asOf,
            agedFailAfterSeconds = agedFailAfterSeconds,
            participants = participants,
            updatedAt = participants.maxOfOrNull { it.updatedAt } ?: Instant.EPOCH
        )
    }

    private fun participantView(
        scenarioRunId: String,
        participantId: String,
        obligations: List<SettlementObligationView>,
        balances: List<SettlementLedgerBalanceView>,
        facts: SettlementFactBundle,
        asOf: Instant,
        agedFailAfterSeconds: Long
    ): SettlementParticipantScoreView {
        val participantObligations = obligations.filter {
            it.buyerParticipantId == participantId || it.sellerParticipantId == participantId
        }
        val unsettled = participantObligations.filter { it.settlementState != SettlementSettledState }
        val failed = unsettled.filter {
            it.settlementState == SettlementBreakOpenedState ||
                it.exceptionState == SettlementRepairPostedState
        }
        val repairPending = unsettled.filter { it.exceptionState == SettlementRepairPostedState }
        val agedFailCount = agedFailCount(
            facts = facts,
            participantObligations = participantObligations,
            asOf = asOf,
            agedFailAfterSeconds = agedFailAfterSeconds
        )
        val pendingValue = unsettled.sumOfCashAmount()
        val haircutAdjustedPendingValue = unsettled.fold(BigDecimal.ZERO) { sum, obligation ->
            sum + obligation.cashAmount.toSettlementQuantity() * haircutFor(obligation)
        }
        val blockedUnsettledValue = failed.sumOfCashAmount()
        val participantBalances = balances.filter { it.participantId == participantId }
        return SettlementParticipantScoreView(
            scenarioRunId = scenarioRunId,
            participantId = participantId,
            cashBalances = participantBalances
                .filter { it.assetType == SettlementLedgerEntryTypeCash }
                .sortedBy { it.assetId }
                .map { SettlementScoreAssetBalanceView(it.assetId, it.availableQuantity) },
            securityBalances = participantBalances
                .filter { it.assetType == SettlementLedgerEntryTypeSecurity }
                .sortedBy { it.assetId }
                .map { SettlementScoreAssetBalanceView(it.assetId, it.availableQuantity) },
            pendingValue = pendingValue.toSettlementQuantityString(),
            haircutAdjustedPendingValue = haircutAdjustedPendingValue.toSettlementQuantityString(),
            blockedUnsettledValue = blockedUnsettledValue.toSettlementQuantityString(),
            scorePenaltyPoints = (failed.size * SettlementScoreFailPenaltyPoints) +
                (agedFailCount * SettlementScoreAgedFailPenaltyPoints),
            settledObligationCount = participantObligations.count { it.settlementState == SettlementSettledState },
            pendingObligationCount = unsettled.size,
            failedObligationCount = failed.size,
            agedFailCount = agedFailCount,
            openBreakCount = failed.size,
            repairPendingCount = repairPending.size,
            updatedAt = (
                participantObligations.map { it.updatedAt } +
                    participantBalances.map { it.updatedAt }
                ).maxOrNull() ?: Instant.EPOCH
        )
    }

    private fun agedFailCount(
        facts: SettlementFactBundle,
        participantObligations: List<SettlementObligationView>,
        asOf: Instant,
        agedFailAfterSeconds: Long
    ): Int {
        val obligationIds = participantObligations
            .filter { it.settlementState != SettlementSettledState }
            .map { it.settlementObligationId }
            .toSet()
        val resolvedBreakIds = facts.resolutions.map { it.settlementBreakId }.toSet()
        return facts.breaks
            .filter { it.settlementObligationId in obligationIds }
            .filter { it.settlementBreakId !in resolvedBreakIds }
            .count { Duration.between(it.occurredAt, asOf).seconds >= agedFailAfterSeconds }
    }

    private fun haircutFor(obligation: SettlementObligationView): BigDecimal {
        return when {
            obligation.settlementState == SettlementBreakOpenedState ||
                obligation.exceptionState == SettlementRepairPostedState -> BigDecimal(SettlementScoreRepairPendingHaircut)
            obligation.settlementState == SettlementResolvedState -> BigDecimal(SettlementScoreRejectedHaircut)
            else -> BigDecimal(SettlementScoreSameTickPendingHaircut)
        }
    }

    private fun List<SettlementObligationView>.sumOfCashAmount(): BigDecimal {
        return fold(BigDecimal.ZERO) { sum, obligation -> sum + obligation.cashAmount.toSettlementQuantity() }
    }

    private fun latestFactTime(facts: SettlementFactBundle): Instant {
        return (
            facts.resourcePositions.map { it.occurredAt } +
                facts.obligations.map { it.occurredAt } +
                facts.allocations.map { it.occurredAt } +
                facts.confirmations.map { it.occurredAt } +
                facts.affirmations.map { it.occurredAt } +
                facts.clearingSubmissions.map { it.occurredAt } +
                facts.clearingAcceptances.map { it.occurredAt } +
                facts.clearingRejections.map { it.occurredAt } +
                facts.novations.map { it.occurredAt } +
                facts.instructions.map { it.occurredAt } +
                facts.attempts.map { it.occurredAt } +
                facts.legOutcomes.map { it.occurredAt } +
                facts.ledgerEntries.map { it.occurredAt } +
                facts.settlements.map { it.occurredAt } +
                facts.breaks.map { it.occurredAt } +
                facts.repairs.map { it.occurredAt } +
                facts.resolutions.map { it.occurredAt }
            ).maxOrNull() ?: Instant.EPOCH
    }
}

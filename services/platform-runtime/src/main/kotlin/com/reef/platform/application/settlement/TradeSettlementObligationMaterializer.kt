package com.reef.platform.application.settlement

import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.TradeCreated
import com.reef.platform.infrastructure.persistence.RuntimePersistence
import java.math.BigInteger
import java.time.Instant

data class SettlementObligationMaterializationResult(
    val scenarioRunId: String,
    val scannedTrades: Int,
    val materializedObligations: Int,
    val materializedInstructions: Int,
    val materializedAttempts: Int,
    val materializedLegOutcomes: Int,
    val materializedLedgerEntries: Int,
    val materializedSettlements: Int,
    val materializedBreaks: Int,
    val materializedResolutions: Int,
    val skippedTrades: Int
)

class TradeSettlementObligationMaterializer(
    private val runtimePersistence: RuntimePersistence,
    private val settlementFactStore: SettlementFactStore,
    private val postTradeProfileResolver: PostTradeProfileResolver = PostTradeProfileResolver.fromPersistence(runtimePersistence)
) {
    fun materialize(scenarioRunId: String, venueSessionId: String = ""): SettlementObligationMaterializationResult {
        require(scenarioRunId.isNotBlank()) { "scenarioRunId is required" }
        val trades = runtimePersistence.trades().sortedWith(compareBy<TradeCreated> { it.occurredAt }.thenBy { it.tradeId })
        val eventsById = runtimePersistence.events().associateBy { it.eventId }
        val obligations = mutableListOf<SettlementObligationCreatedFact>()
        val instructions = mutableListOf<SettlementInstructionCreatedFact>()
        val attempts = mutableListOf<SettlementAttemptStartedFact>()
        val legOutcomes = mutableListOf<SettlementLegOutcomeFact>()
        val ledgerEntries = mutableListOf<SettlementLedgerEntryFact>()
        val settlements = mutableListOf<SettlementSettledFact>()
        val breaks = mutableListOf<SettlementBreakOpenedFact>()
        val resolutions = mutableListOf<SettlementResolvedFact>()
        val existingFacts = settlementFactStore.factsByScenarioRunId(scenarioRunId)
        val resourceChecksEnabled = existingFacts.resourcePositions.isNotEmpty()
        var skipped = 0

        trades.forEach { trade ->
            val buyOrder = runtimePersistence.acceptedOrder(trade.buyOrderId)
            val sellOrder = runtimePersistence.acceptedOrder(trade.sellOrderId)
            if (buyOrder == null || sellOrder == null) {
                skipped += 1
                return@forEach
            }
            val tradeRunId = sharedMetadata(buyOrder.runId, sellOrder.runId)
            if (tradeRunId == null) {
                skipped += 1
                return@forEach
            }
            val effectiveScenarioRunId = tradeRunId.ifBlank { scenarioRunId }
            if (effectiveScenarioRunId != scenarioRunId) {
                skipped += 1
                return@forEach
            }
            val orderVenueSessionId = sharedMetadata(buyOrder.venueSessionId, sellOrder.venueSessionId)
            if (orderVenueSessionId == null) {
                skipped += 1
                return@forEach
            }
            if (venueSessionId.isNotBlank() && orderVenueSessionId.isNotBlank() && orderVenueSessionId != venueSessionId) {
                skipped += 1
                return@forEach
            }
            val tradeVenueSessionId = venueSessionId.ifBlank { orderVenueSessionId }
            val selection = postTradeProfileResolver.resolve(
                scenarioRunProfileId = runtimePersistence.scenarioRunPostTradeProfileId(scenarioRunId).orEmpty(),
                venueSessionProfileId = tradeVenueSessionId.takeIf { it.isNotBlank() }
                    ?.let { runtimePersistence.venueSessionPostTradeProfileId(it) }
                    .orEmpty()
            )
            val event = eventsById[trade.eventId]
            val obligation = obligationFact(
                trade = trade,
                buyOrder = buyOrder,
                sellOrder = sellOrder,
                event = event,
                scenarioRunId = scenarioRunId,
                profileId = selection.profileId,
                policyVersion = selection.policyVersion
            )
            if (existingFacts.obligations.none { it.settlementObligationId == obligation.settlementObligationId }) {
                obligations += obligation
            }
            if (selection.mode == InstantPostTradeMode) {
                val plan = instantSettlementPlan(existingFacts, obligation) ?: return@forEach
                val instruction = instructionFact(obligation, plan.attemptNumber, plan.occurredAt)
                val attempt = attemptFact(obligation, instruction, plan.attemptNumber, plan.occurredAt)
                val proposedLedgerEntries = ledgerEntryFacts(obligation, instruction, attempt, buyOrder, sellOrder)
                val availability = if (resourceChecksEnabled) {
                    resourceAvailability(
                        facts = existingFacts,
                        pendingLedgerEntries = ledgerEntries,
                        obligation = obligation,
                        buyOrder = buyOrder,
                        sellOrder = sellOrder
                    )
                } else {
                    SettlementResourceAvailability(cashAvailable = true, securityAvailable = true)
                }
                instructions += instruction
                attempts += attempt
                legOutcomes += legOutcomeFacts(
                    obligation = obligation,
                    instruction = instruction,
                    attempt = attempt,
                    cashState = if (availability.cashAvailable) SettlementLegSucceededState else SettlementLegFailedState,
                    securityState = if (availability.securityAvailable) SettlementLegSucceededState else SettlementLegFailedState
                )
                if (availability.cashAvailable && availability.securityAvailable) {
                    ledgerEntries += proposedLedgerEntries
                    settlements += settlementFact(obligation, instruction, attempt)
                    plan.repair?.let {
                        resolutions += resolutionFact(
                            obligation = obligation,
                            breakFact = plan.breakFact!!,
                            repair = it,
                            attempt = attempt
                        )
                    }
                } else {
                    breaks += breakFact(
                        obligation = obligation,
                        attempt = attempt,
                        reason = if (!availability.cashAvailable) {
                            SettlementBreakOpenedReason
                        } else {
                            SettlementBreakOpenedReasonSecurity
                        }
                    )
                }
            }
        }

        if (
            obligations.isNotEmpty() || instructions.isNotEmpty() || attempts.isNotEmpty() ||
            legOutcomes.isNotEmpty() || ledgerEntries.isNotEmpty() || settlements.isNotEmpty() ||
            breaks.isNotEmpty() || resolutions.isNotEmpty()
        ) {
            settlementFactStore.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = scenarioRunId,
                    obligations = obligations,
                    instructions = instructions,
                    attempts = attempts,
                    legOutcomes = legOutcomes,
                    ledgerEntries = ledgerEntries,
                    settlements = settlements,
                    breaks = breaks,
                    resolutions = resolutions
                )
            )
        }
        return SettlementObligationMaterializationResult(
            scenarioRunId = scenarioRunId,
            scannedTrades = trades.size,
            materializedObligations = obligations.size,
            materializedInstructions = instructions.size,
            materializedAttempts = attempts.size,
            materializedLegOutcomes = legOutcomes.size,
            materializedLedgerEntries = ledgerEntries.size,
            materializedSettlements = settlements.size,
            materializedBreaks = breaks.size,
            materializedResolutions = resolutions.size,
            skippedTrades = skipped
        )
    }

    private fun obligationFact(
        trade: TradeCreated,
        buyOrder: PersistedOrder,
        sellOrder: PersistedOrder,
        event: RuntimeEvent?,
        scenarioRunId: String,
        profileId: String,
        policyVersion: Int
    ): SettlementObligationCreatedFact {
        return SettlementObligationCreatedFact(
            settlementObligationId = "settlement-obligation-${trade.tradeId}",
            scenarioRunId = buyOrder.runId.ifBlank { sellOrder.runId }.ifBlank { scenarioRunId },
            postTradeProfileId = profileId,
            postTradePolicyVersion = policyVersion,
            correlationId = event?.correlationId?.ifBlank { null } ?: trade.eventId,
            causationId = event?.causationId?.ifBlank { null } ?: trade.eventId,
            tradeId = trade.tradeId,
            buyerParticipantId = buyOrder.participantId,
            sellerParticipantId = sellOrder.participantId,
            instrumentId = trade.instrumentId,
            quantity = trade.quantityUnits,
            cashAmount = cashAmount(trade),
            currency = trade.currency,
            occurredAt = Instant.parse(trade.occurredAt)
        )
    }

    private fun instructionFact(
        obligation: SettlementObligationCreatedFact,
        attemptNumber: Int = 1,
        occurredAt: Instant = obligation.occurredAt
    ): SettlementInstructionCreatedFact {
        return SettlementInstructionCreatedFact(
            settlementInstructionId = "settlement-instruction-${obligation.settlementObligationId}-$attemptNumber",
            settlementObligationId = obligation.settlementObligationId,
            scenarioRunId = obligation.scenarioRunId,
            postTradeProfileId = obligation.postTradeProfileId,
            postTradePolicyVersion = obligation.postTradePolicyVersion,
            correlationId = obligation.correlationId,
            causationId = obligation.settlementObligationId,
            occurredAt = occurredAt
        )
    }

    private fun attemptFact(
        obligation: SettlementObligationCreatedFact,
        instruction: SettlementInstructionCreatedFact,
        attemptNumber: Int = 1,
        occurredAt: Instant = obligation.occurredAt
    ): SettlementAttemptStartedFact {
        return SettlementAttemptStartedFact(
            settlementAttemptId = "settlement-attempt-${obligation.settlementObligationId}-$attemptNumber",
            settlementObligationId = obligation.settlementObligationId,
            settlementInstructionId = instruction.settlementInstructionId,
            scenarioRunId = obligation.scenarioRunId,
            postTradeProfileId = obligation.postTradeProfileId,
            postTradePolicyVersion = obligation.postTradePolicyVersion,
            correlationId = obligation.correlationId,
            causationId = instruction.settlementInstructionId,
            attemptNumber = attemptNumber,
            occurredAt = occurredAt
        )
    }

    private fun legOutcomeFacts(
        obligation: SettlementObligationCreatedFact,
        instruction: SettlementInstructionCreatedFact,
        attempt: SettlementAttemptStartedFact,
        cashState: String = SettlementLegSucceededState,
        securityState: String = SettlementLegSucceededState
    ): List<SettlementLegOutcomeFact> {
        return listOf(
            SettlementLegOutcomeFact(
                settlementLegOutcomeId = "settlement-leg-${attempt.settlementAttemptId}-cash",
                settlementObligationId = obligation.settlementObligationId,
                settlementInstructionId = instruction.settlementInstructionId,
                settlementAttemptId = attempt.settlementAttemptId,
                scenarioRunId = obligation.scenarioRunId,
                postTradeProfileId = obligation.postTradeProfileId,
                postTradePolicyVersion = obligation.postTradePolicyVersion,
                correlationId = obligation.correlationId,
                causationId = attempt.settlementAttemptId,
                legType = SettlementLegTypeCash,
                state = cashState,
                occurredAt = attempt.occurredAt
            ),
            SettlementLegOutcomeFact(
                settlementLegOutcomeId = "settlement-leg-${attempt.settlementAttemptId}-security",
                settlementObligationId = obligation.settlementObligationId,
                settlementInstructionId = instruction.settlementInstructionId,
                settlementAttemptId = attempt.settlementAttemptId,
                scenarioRunId = obligation.scenarioRunId,
                postTradeProfileId = obligation.postTradeProfileId,
                postTradePolicyVersion = obligation.postTradePolicyVersion,
                correlationId = obligation.correlationId,
                causationId = attempt.settlementAttemptId,
                legType = SettlementLegTypeSecurity,
                state = securityState,
                occurredAt = attempt.occurredAt
            )
        )
    }

    private fun ledgerEntryFacts(
        obligation: SettlementObligationCreatedFact,
        instruction: SettlementInstructionCreatedFact,
        attempt: SettlementAttemptStartedFact,
        buyOrder: PersistedOrder,
        sellOrder: PersistedOrder
    ): List<SettlementLedgerEntryFact> {
        return listOf(
            ledgerEntry(
                suffix = "buyer-cash-debit",
                obligation = obligation,
                instruction = instruction,
                attempt = attempt,
                participantId = buyOrder.participantId,
                accountId = buyOrder.accountId,
                assetType = SettlementLedgerEntryTypeCash,
                assetId = obligation.currency,
                direction = SettlementLedgerDirectionDebit,
                quantity = obligation.cashAmount
            ),
            ledgerEntry(
                suffix = "seller-cash-credit",
                obligation = obligation,
                instruction = instruction,
                attempt = attempt,
                participantId = sellOrder.participantId,
                accountId = sellOrder.accountId,
                assetType = SettlementLedgerEntryTypeCash,
                assetId = obligation.currency,
                direction = SettlementLedgerDirectionCredit,
                quantity = obligation.cashAmount
            ),
            ledgerEntry(
                suffix = "seller-security-debit",
                obligation = obligation,
                instruction = instruction,
                attempt = attempt,
                participantId = sellOrder.participantId,
                accountId = sellOrder.accountId,
                assetType = SettlementLedgerEntryTypeSecurity,
                assetId = obligation.instrumentId,
                direction = SettlementLedgerDirectionDebit,
                quantity = obligation.quantity
            ),
            ledgerEntry(
                suffix = "buyer-security-credit",
                obligation = obligation,
                instruction = instruction,
                attempt = attempt,
                participantId = buyOrder.participantId,
                accountId = buyOrder.accountId,
                assetType = SettlementLedgerEntryTypeSecurity,
                assetId = obligation.instrumentId,
                direction = SettlementLedgerDirectionCredit,
                quantity = obligation.quantity
            )
        )
    }

    private fun ledgerEntry(
        suffix: String,
        obligation: SettlementObligationCreatedFact,
        instruction: SettlementInstructionCreatedFact,
        attempt: SettlementAttemptStartedFact,
        participantId: String,
        accountId: String,
        assetType: String,
        assetId: String,
        direction: String,
        quantity: String
    ): SettlementLedgerEntryFact {
        return SettlementLedgerEntryFact(
            ledgerEntryId = "settlement-ledger-${attempt.settlementAttemptId}-$suffix",
            settlementObligationId = obligation.settlementObligationId,
            settlementInstructionId = instruction.settlementInstructionId,
            settlementAttemptId = attempt.settlementAttemptId,
            scenarioRunId = obligation.scenarioRunId,
            postTradeProfileId = obligation.postTradeProfileId,
            postTradePolicyVersion = obligation.postTradePolicyVersion,
            correlationId = obligation.correlationId,
            causationId = attempt.settlementAttemptId,
            participantId = participantId,
            accountId = accountId,
            assetType = assetType,
            assetId = assetId,
            direction = direction,
            quantity = quantity,
            occurredAt = attempt.occurredAt
        )
    }

    private fun settlementFact(
        obligation: SettlementObligationCreatedFact,
        instruction: SettlementInstructionCreatedFact,
        attempt: SettlementAttemptStartedFact
    ): SettlementSettledFact {
        return SettlementSettledFact(
            settlementId = "settlement-final-${obligation.settlementObligationId}",
            settlementObligationId = obligation.settlementObligationId,
            settlementInstructionId = instruction.settlementInstructionId,
            settlementAttemptId = attempt.settlementAttemptId,
            scenarioRunId = obligation.scenarioRunId,
            postTradeProfileId = obligation.postTradeProfileId,
            postTradePolicyVersion = obligation.postTradePolicyVersion,
            correlationId = obligation.correlationId,
            causationId = attempt.settlementAttemptId,
            occurredAt = attempt.occurredAt
        )
    }

    private fun breakFact(
        obligation: SettlementObligationCreatedFact,
        attempt: SettlementAttemptStartedFact,
        reason: String
    ): SettlementBreakOpenedFact {
        return SettlementBreakOpenedFact(
            settlementBreakId = "settlement-break-${obligation.settlementObligationId}-${attempt.attemptNumber}",
            settlementObligationId = obligation.settlementObligationId,
            scenarioRunId = obligation.scenarioRunId,
            postTradeProfileId = obligation.postTradeProfileId,
            postTradePolicyVersion = obligation.postTradePolicyVersion,
            correlationId = obligation.correlationId,
            causationId = attempt.settlementAttemptId,
            reason = reason,
            occurredAt = attempt.occurredAt
        )
    }

    private fun resolutionFact(
        obligation: SettlementObligationCreatedFact,
        breakFact: SettlementBreakOpenedFact,
        repair: SettlementRepairPostedFact,
        attempt: SettlementAttemptStartedFact
    ): SettlementResolvedFact {
        return SettlementResolvedFact(
            settlementResolutionId = "settlement-resolution-${breakFact.settlementBreakId}-${repair.settlementRepairId}",
            settlementObligationId = obligation.settlementObligationId,
            settlementBreakId = breakFact.settlementBreakId,
            settlementRepairId = repair.settlementRepairId,
            scenarioRunId = obligation.scenarioRunId,
            postTradeProfileId = obligation.postTradeProfileId,
            postTradePolicyVersion = obligation.postTradePolicyVersion,
            correlationId = obligation.correlationId,
            causationId = attempt.settlementAttemptId,
            occurredAt = attempt.occurredAt
        )
    }

    private fun instantSettlementPlan(
        facts: SettlementFactBundle,
        obligation: SettlementObligationCreatedFact
    ): SettlementAttemptPlan? {
        val existingSettled = facts.settlements.any { it.settlementObligationId == obligation.settlementObligationId }
        val latestBreak = facts.breaks
            .filter { it.settlementObligationId == obligation.settlementObligationId }
            .maxWithOrNull(compareBy<SettlementBreakOpenedFact> { it.occurredAt }.thenBy { it.settlementBreakId })
        val latestResolution = latestBreak?.let { breakFact ->
            facts.resolutions
                .filter { it.settlementBreakId == breakFact.settlementBreakId }
                .maxWithOrNull(compareBy<SettlementResolvedFact> { it.occurredAt }.thenBy { it.settlementResolutionId })
        }
        if (existingSettled && latestBreak == null) return null
        if (latestResolution != null) return null
        if (latestBreak == null) {
            return SettlementAttemptPlan(attemptNumber = 1, occurredAt = obligation.occurredAt)
        }

        val latestRepair = facts.repairs
            .filter { it.settlementBreakId == latestBreak.settlementBreakId }
            .maxWithOrNull(compareBy<SettlementRepairPostedFact> { it.occurredAt }.thenBy { it.settlementRepairId })
            ?: return null
        val latestAttempt = facts.attempts
            .filter { it.settlementObligationId == obligation.settlementObligationId }
            .maxWithOrNull(compareBy<SettlementAttemptStartedFact> { it.attemptNumber }.thenBy { it.settlementAttemptId })
        if (latestAttempt != null && !latestAttempt.occurredAt.isBefore(latestRepair.occurredAt)) {
            return null
        }
        return SettlementAttemptPlan(
            attemptNumber = (latestAttempt?.attemptNumber ?: 1) + 1,
            occurredAt = latestRepair.occurredAt,
            breakFact = latestBreak,
            repair = latestRepair
        )
    }

    private fun resourceAvailability(
        facts: SettlementFactBundle,
        pendingLedgerEntries: List<SettlementLedgerEntryFact>,
        obligation: SettlementObligationCreatedFact,
        buyOrder: PersistedOrder,
        sellOrder: PersistedOrder
    ): SettlementResourceAvailability {
        val buyerCash = SettlementLedgerProjection.availableQuantity(
            facts = facts,
            participantId = buyOrder.participantId,
            accountId = buyOrder.accountId,
            assetType = SettlementLedgerEntryTypeCash,
            assetId = obligation.currency,
            additionalLedgerEntries = pendingLedgerEntries
        )
        val sellerSecurity = SettlementLedgerProjection.availableQuantity(
            facts = facts,
            participantId = sellOrder.participantId,
            accountId = sellOrder.accountId,
            assetType = SettlementLedgerEntryTypeSecurity,
            assetId = obligation.instrumentId,
            additionalLedgerEntries = pendingLedgerEntries
        )
        return SettlementResourceAvailability(
            cashAvailable = buyerCash >= obligation.cashAmount.toSettlementQuantity(),
            securityAvailable = sellerSecurity >= obligation.quantity.toSettlementQuantity()
        )
    }

    private fun cashAmount(trade: TradeCreated): String {
        val quantity = trade.quantityUnits.toBigIntegerOrNull()
            ?: throw IllegalArgumentException("trade quantityUnits must be an integer")
        val price = trade.price.toBigIntegerOrNull()
            ?: throw IllegalArgumentException("trade price must be an integer")
        return quantity.multiply(price).toString()
    }

    private fun String.toBigIntegerOrNull(): BigInteger? {
        return try {
            BigInteger(this)
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun sharedMetadata(first: String, second: String): String? {
        val values = listOf(first, second).filter { it.isNotBlank() }.toSet()
        return when (values.size) {
            0 -> ""
            1 -> values.single()
            else -> null
        }
    }

    private companion object {
        const val InstantPostTradeMode = "instant-post-trade"
    }
}

private data class SettlementResourceAvailability(
    val cashAvailable: Boolean,
    val securityAvailable: Boolean
)

private data class SettlementAttemptPlan(
    val attemptNumber: Int,
    val occurredAt: Instant,
    val breakFact: SettlementBreakOpenedFact? = null,
    val repair: SettlementRepairPostedFact? = null
)

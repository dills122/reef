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
    val materializedAttempts: Int,
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
        val attempts = mutableListOf<SettlementAttemptStartedFact>()
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
            obligations += obligation
            if (selection.mode == InstantPostTradeMode) {
                attempts += attemptFact(obligation)
            }
        }

        if (obligations.isNotEmpty() || attempts.isNotEmpty()) {
            settlementFactStore.appendFacts(
                SettlementFactBundle(
                    scenarioRunId = scenarioRunId,
                    obligations = obligations,
                    attempts = attempts
                )
            )
        }
        return SettlementObligationMaterializationResult(
            scenarioRunId = scenarioRunId,
            scannedTrades = trades.size,
            materializedObligations = obligations.size,
            materializedAttempts = attempts.size,
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

    private fun attemptFact(obligation: SettlementObligationCreatedFact): SettlementAttemptStartedFact {
        return SettlementAttemptStartedFact(
            settlementAttemptId = "settlement-attempt-${obligation.settlementObligationId}-1",
            settlementObligationId = obligation.settlementObligationId,
            scenarioRunId = obligation.scenarioRunId,
            postTradeProfileId = obligation.postTradeProfileId,
            postTradePolicyVersion = obligation.postTradePolicyVersion,
            correlationId = obligation.correlationId,
            causationId = obligation.settlementObligationId,
            attemptNumber = 1,
            occurredAt = obligation.occurredAt
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

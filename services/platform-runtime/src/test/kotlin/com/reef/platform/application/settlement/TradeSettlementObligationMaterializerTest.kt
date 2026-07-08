package com.reef.platform.application.settlement

import com.reef.platform.domain.PersistedOrder
import com.reef.platform.domain.PostTradeProfile
import com.reef.platform.domain.RuntimeEvent
import com.reef.platform.domain.ScenarioRunPostTradeProfile
import com.reef.platform.domain.TradeCreated
import com.reef.platform.domain.VenueSessionPostTradeProfile
import com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistence
import kotlin.test.Test
import kotlin.test.assertEquals

class TradeSettlementObligationMaterializerTest {
    @Test
    fun materializesTradeObligationsWithScenarioRunProfileEvidence() {
        val persistence = InMemoryRuntimePersistence()
        val store = InMemorySettlementFactStore()
        seedTrade(persistence, runId = "run-1", venueSessionId = "session-1")
        persistence.savePostTradeProfile(
            PostTradeProfile(
                profileId = "scenario-instant-v1",
                mode = "instant-post-trade",
                settlementCycle = "T+0",
                nettingMode = "gross-or-microbatch",
                ledgerPostingMode = "near-instant-finality",
                policyVersion = 8
            )
        )
        persistence.saveScenarioRunPostTradeProfile(
            ScenarioRunPostTradeProfile("run-1", "scenario-instant-v1")
        )
        val materializer = TradeSettlementObligationMaterializer(persistence, store)

        val result = materializer.materialize("run-1")
        materializer.materialize("run-1")
        val facts = store.factsByScenarioRunId("run-1")
        val obligation = facts.obligations.single()

        assertEquals(1, result.scannedTrades)
        assertEquals(1, result.materializedObligations)
        assertEquals(0, result.skippedTrades)
        assertEquals("settlement-obligation-trade-1", obligation.settlementObligationId)
        assertEquals("scenario-instant-v1", obligation.postTradeProfileId)
        assertEquals(8, obligation.postTradePolicyVersion)
        assertEquals("buyer-1", obligation.buyerParticipantId)
        assertEquals("seller-1", obligation.sellerParticipantId)
        assertEquals("15025000000000", obligation.cashAmount)
        assertEquals(1, facts.obligations.size)
    }

    @Test
    fun fallsBackToVenueSessionProfileWhenScenarioRunProfileIsAbsent() {
        val persistence = InMemoryRuntimePersistence()
        val store = InMemorySettlementFactStore()
        seedTrade(persistence, runId = "run-venue", venueSessionId = "session-fast")
        persistence.savePostTradeProfile(
            PostTradeProfile(
                profileId = "venue-instant-v1",
                mode = "instant-post-trade",
                settlementCycle = "T+0",
                nettingMode = "gross-or-microbatch",
                ledgerPostingMode = "near-instant-finality",
                policyVersion = 6
            )
        )
        persistence.saveVenueSessionPostTradeProfile(
            VenueSessionPostTradeProfile("session-fast", "venue-instant-v1")
        )
        val materializer = TradeSettlementObligationMaterializer(persistence, store)

        materializer.materialize("run-venue")
        val obligation = store.factsByScenarioRunId("run-venue").obligations.single()

        assertEquals("venue-instant-v1", obligation.postTradeProfileId)
        assertEquals(6, obligation.postTradePolicyVersion)
    }

    private fun seedTrade(
        persistence: InMemoryRuntimePersistence,
        runId: String,
        venueSessionId: String
    ) {
        persistence.saveAcceptedOrder(order("buy-order-1", "buyer-1", runId, venueSessionId))
        persistence.saveAcceptedOrder(order("sell-order-1", "seller-1", runId, venueSessionId))
        persistence.saveTrades(
            listOf(
                TradeCreated(
                    eventId = "evt-trade-1",
                    tradeId = "trade-1",
                    executionId = "exec-1",
                    buyOrderId = "buy-order-1",
                    sellOrderId = "sell-order-1",
                    instrumentId = "AAPL",
                    quantityUnits = "100",
                    price = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-01-01T00:00:00Z"
                )
            )
        )
        persistence.saveEvent(
            RuntimeEvent(
                eventId = "evt-trade-1",
                eventType = "TradeCreated",
                orderId = "buy-order-1",
                traceId = "trace-1",
                causationId = "evt-accepted-1",
                correlationId = "corr-1",
                producer = "platform-runtime",
                schemaVersion = "v1",
                occurredAt = "2026-01-01T00:00:00Z"
            )
        )
    }

    private fun order(
        orderId: String,
        participantId: String,
        runId: String,
        venueSessionId: String
    ): PersistedOrder {
        return PersistedOrder(
            orderId = orderId,
            engineOrderId = "eng-$orderId",
            instrumentId = "AAPL",
            participantId = participantId,
            accountId = "account-$participantId",
            side = if (orderId.startsWith("buy")) "BUY" else "SELL",
            orderType = "LIMIT",
            quantityUnits = "100",
            limitPrice = "150250000000",
            currency = "USD",
            timeInForce = "DAY",
            acceptedAt = "2026-01-01T00:00:00Z",
            runId = runId,
            venueSessionId = venueSessionId
        )
    }
}

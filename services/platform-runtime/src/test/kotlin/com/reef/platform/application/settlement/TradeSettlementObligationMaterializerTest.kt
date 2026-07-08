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
        assertEquals(1, result.materializedInstructions)
        assertEquals(1, result.materializedAttempts)
        assertEquals(0, result.skippedTrades)
        assertEquals("settlement-obligation-trade-1", obligation.settlementObligationId)
        assertEquals("scenario-instant-v1", obligation.postTradeProfileId)
        assertEquals(8, obligation.postTradePolicyVersion)
        assertEquals("buyer-1", obligation.buyerParticipantId)
        assertEquals("seller-1", obligation.sellerParticipantId)
        assertEquals("15025000000000", obligation.cashAmount)
        assertEquals(1, facts.obligations.size)
        assertEquals("settlement-instruction-settlement-obligation-trade-1-1", facts.instructions.single().settlementInstructionId)
        assertEquals("settlement-attempt-settlement-obligation-trade-1-1", facts.attempts.single().settlementAttemptId)
        assertEquals("settlement-obligation-trade-1", facts.attempts.single().settlementObligationId)
        assertEquals(facts.instructions.single().settlementInstructionId, facts.attempts.single().settlementInstructionId)
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
        val facts = store.factsByScenarioRunId("run-venue")
        val obligation = facts.obligations.single()

        assertEquals("venue-instant-v1", obligation.postTradeProfileId)
        assertEquals(6, obligation.postTradePolicyVersion)
        assertEquals(1, facts.instructions.size)
        assertEquals(1, facts.attempts.size)
    }

    @Test
    fun realisticDefaultMaterializesObligationsWithoutStartingAttempts() {
        val persistence = InMemoryRuntimePersistence()
        val store = InMemorySettlementFactStore()
        seedTrade(persistence, runId = "run-realistic", venueSessionId = "session-realistic")
        val materializer = TradeSettlementObligationMaterializer(persistence, store)

        val result = materializer.materialize("run-realistic")
        val facts = store.factsByScenarioRunId("run-realistic")

        assertEquals(1, result.scannedTrades)
        assertEquals(1, result.materializedObligations)
        assertEquals(0, result.materializedInstructions)
        assertEquals(0, result.materializedAttempts)
        assertEquals(DefaultPostTradeProfileId, facts.obligations.single().postTradeProfileId)
        assertEquals(emptyList(), facts.instructions)
        assertEquals(emptyList(), facts.attempts)
    }

    @Test
    fun environmentInstantProfileStartsAttempts() {
        val persistence = InMemoryRuntimePersistence()
        val store = InMemorySettlementFactStore()
        seedTrade(persistence, runId = "run-env-instant", venueSessionId = "session-env")
        val materializer = TradeSettlementObligationMaterializer(
            runtimePersistence = persistence,
            settlementFactStore = store,
            postTradeProfileResolver = PostTradeProfileResolver.envOnly(
                profileId = "instant-post-trade-v1",
                policyVersion = 4
            )
        )

        val result = materializer.materialize("run-env-instant")
        val facts = store.factsByScenarioRunId("run-env-instant")

        assertEquals(1, result.materializedInstructions)
        assertEquals(1, result.materializedAttempts)
        assertEquals("instant-post-trade-v1", facts.instructions.single().postTradeProfileId)
        assertEquals("instant-post-trade-v1", facts.attempts.single().postTradeProfileId)
        assertEquals(4, facts.attempts.single().postTradePolicyVersion)
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

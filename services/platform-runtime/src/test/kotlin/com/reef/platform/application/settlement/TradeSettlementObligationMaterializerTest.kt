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
        assertEquals(1, result.materializedAllocations)
        assertEquals(1, result.materializedConfirmations)
        assertEquals(1, result.materializedAffirmations)
        assertEquals(1, result.materializedInstructions)
        assertEquals(1, result.materializedAttempts)
        assertEquals(2, result.materializedLegOutcomes)
        assertEquals(4, result.materializedLedgerEntries)
        assertEquals(1, result.materializedSettlements)
        assertEquals(0, result.skippedTrades)
        assertEquals("settlement-obligation-trade-1", obligation.settlementObligationId)
        assertEquals("scenario-instant-v1", obligation.postTradeProfileId)
        assertEquals(8, obligation.postTradePolicyVersion)
        assertEquals("buyer-1", obligation.buyerParticipantId)
        assertEquals("seller-1", obligation.sellerParticipantId)
        assertEquals("15025000000000", obligation.cashAmount)
        assertEquals(1, facts.obligations.size)
        assertEquals("settlement-allocation-settlement-obligation-trade-1", facts.allocations.single().settlementAllocationId)
        assertEquals("buy-order-1", facts.allocations.single().buyOrderId)
        assertEquals("sell-order-1", facts.allocations.single().sellOrderId)
        assertEquals("settlement-confirmation-settlement-obligation-trade-1", facts.confirmations.single().settlementConfirmationId)
        assertEquals("settlement-affirmation-settlement-obligation-trade-1", facts.affirmations.single().settlementAffirmationId)
        assertEquals("settlement-instruction-settlement-obligation-trade-1-1", facts.instructions.single().settlementInstructionId)
        assertEquals(facts.affirmations.single().settlementAffirmationId, facts.instructions.single().causationId)
        assertEquals("settlement-attempt-settlement-obligation-trade-1-1", facts.attempts.single().settlementAttemptId)
        assertEquals("settlement-obligation-trade-1", facts.attempts.single().settlementObligationId)
        assertEquals(facts.instructions.single().settlementInstructionId, facts.attempts.single().settlementInstructionId)
        assertEquals(setOf(SettlementLegTypeCash, SettlementLegTypeSecurity), facts.legOutcomes.map { it.legType }.toSet())
        assertEquals(
            setOf(
                "settlement-ledger-settlement-attempt-settlement-obligation-trade-1-1-buyer-cash-debit",
                "settlement-ledger-settlement-attempt-settlement-obligation-trade-1-1-seller-cash-credit",
                "settlement-ledger-settlement-attempt-settlement-obligation-trade-1-1-seller-security-debit",
                "settlement-ledger-settlement-attempt-settlement-obligation-trade-1-1-buyer-security-credit"
            ),
            facts.ledgerEntries.map { it.ledgerEntryId }.toSet()
        )
        assertEquals("settlement-final-settlement-obligation-trade-1", facts.settlements.single().settlementId)
    }

    @Test
    fun materializationScansOnlyCandidateTradesForScenarioRun() {
        val persistence = InMemoryRuntimePersistence()
        val store = InMemorySettlementFactStore()
        seedTrade(persistence, runId = "run-1", venueSessionId = "session-1")
        persistence.saveAcceptedOrder(order("buy-order-other", "buyer-1", "run-other", "session-other"))
        persistence.saveAcceptedOrder(order("sell-order-other", "seller-1", "run-other", "session-other"))
        persistence.saveTrades(
            listOf(
                TradeCreated(
                    eventId = "evt-trade-other",
                    tradeId = "trade-other",
                    executionId = "exec-other",
                    buyOrderId = "buy-order-other",
                    sellOrderId = "sell-order-other",
                    instrumentId = "AAPL",
                    quantityUnits = "100",
                    price = "150250000000",
                    currency = "USD",
                    occurredAt = "2026-01-01T00:00:01Z"
                )
            )
        )
        val materializer = TradeSettlementObligationMaterializer(persistence, store)

        val result = materializer.materialize("run-1")
        val facts = store.factsByScenarioRunId("run-1")

        assertEquals(1, result.scannedTrades)
        assertEquals(listOf("trade-1"), facts.obligations.map { it.tradeId })
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
        assertEquals(1, facts.allocations.size)
        assertEquals(1, facts.confirmations.size)
        assertEquals(1, facts.affirmations.size)
        assertEquals(1, facts.instructions.size)
        assertEquals(1, facts.attempts.size)
        assertEquals(2, facts.legOutcomes.size)
        assertEquals(4, facts.ledgerEntries.size)
        assertEquals(1, facts.settlements.size)
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
        assertEquals(0, result.materializedAllocations)
        assertEquals(0, result.materializedConfirmations)
        assertEquals(0, result.materializedAffirmations)
        assertEquals(0, result.materializedInstructions)
        assertEquals(0, result.materializedAttempts)
        assertEquals(0, result.materializedLegOutcomes)
        assertEquals(0, result.materializedLedgerEntries)
        assertEquals(0, result.materializedSettlements)
        assertEquals(DefaultPostTradeProfileId, facts.obligations.single().postTradeProfileId)
        assertEquals(emptyList(), facts.allocations)
        assertEquals(emptyList(), facts.confirmations)
        assertEquals(emptyList(), facts.affirmations)
        assertEquals(emptyList(), facts.instructions)
        assertEquals(emptyList(), facts.attempts)
        assertEquals(emptyList(), facts.legOutcomes)
        assertEquals(emptyList(), facts.ledgerEntries)
        assertEquals(emptyList(), facts.settlements)
    }

    @Test
    fun instantProfileCreatesCashBreakWhenBuyerCashIsUnavailable() {
        val persistence = InMemoryRuntimePersistence()
        val store = InMemorySettlementFactStore()
        seedTrade(persistence, runId = "run-cash-fail", venueSessionId = "session-env")
        seedResources(store, runId = "run-cash-fail", sellerSecurity = "100")
        val materializer = TradeSettlementObligationMaterializer(
            runtimePersistence = persistence,
            settlementFactStore = store,
            postTradeProfileResolver = PostTradeProfileResolver.envOnly(
                profileId = "instant-post-trade-v1",
                policyVersion = 4
            )
        )

        val result = materializer.materialize("run-cash-fail")
        val facts = store.factsByScenarioRunId("run-cash-fail")

        assertEquals(1, result.materializedObligations)
        assertEquals(1, result.materializedAllocations)
        assertEquals(1, result.materializedConfirmations)
        assertEquals(1, result.materializedAffirmations)
        assertEquals(2, result.materializedLegOutcomes)
        assertEquals(0, result.materializedLedgerEntries)
        assertEquals(0, result.materializedSettlements)
        assertEquals(1, result.materializedBreaks)
        assertEquals(SettlementLegFailedState, facts.legOutcomes.single { it.legType == SettlementLegTypeCash }.state)
        assertEquals(SettlementLegSucceededState, facts.legOutcomes.single { it.legType == SettlementLegTypeSecurity }.state)
        assertEquals(SettlementBreakOpenedReason, facts.breaks.single().reason)
    }

    @Test
    fun instantProfileCreatesSecurityBreakWhenSellerSecurityIsUnavailable() {
        val persistence = InMemoryRuntimePersistence()
        val store = InMemorySettlementFactStore()
        seedTrade(persistence, runId = "run-security-fail", venueSessionId = "session-env")
        seedResources(store, runId = "run-security-fail", buyerCash = "15025000000000")
        val materializer = TradeSettlementObligationMaterializer(
            runtimePersistence = persistence,
            settlementFactStore = store,
            postTradeProfileResolver = PostTradeProfileResolver.envOnly(
                profileId = "instant-post-trade-v1",
                policyVersion = 4
            )
        )

        val result = materializer.materialize("run-security-fail")
        val facts = store.factsByScenarioRunId("run-security-fail")

        assertEquals(1, result.materializedObligations)
        assertEquals(1, result.materializedAllocations)
        assertEquals(1, result.materializedConfirmations)
        assertEquals(1, result.materializedAffirmations)
        assertEquals(2, result.materializedLegOutcomes)
        assertEquals(0, result.materializedLedgerEntries)
        assertEquals(0, result.materializedSettlements)
        assertEquals(1, result.materializedBreaks)
        assertEquals(SettlementLegSucceededState, facts.legOutcomes.single { it.legType == SettlementLegTypeCash }.state)
        assertEquals(SettlementLegFailedState, facts.legOutcomes.single { it.legType == SettlementLegTypeSecurity }.state)
        assertEquals(SettlementBreakOpenedReasonSecurity, facts.breaks.single().reason)
    }

    @Test
    fun repairedCashBreakReattemptsSettlementAndResolvesException() {
        val persistence = InMemoryRuntimePersistence()
        val store = InMemorySettlementFactStore()
        seedTrade(persistence, runId = "run-cash-repair", venueSessionId = "session-env")
        seedResources(store, runId = "run-cash-repair", sellerSecurity = "100")
        val materializer = TradeSettlementObligationMaterializer(
            runtimePersistence = persistence,
            settlementFactStore = store,
            postTradeProfileResolver = PostTradeProfileResolver.envOnly(
                profileId = "instant-post-trade-v1",
                policyVersion = 4
            )
        )

        val failed = materializer.materialize("run-cash-repair")
        val beforeRepair = materializer.materialize("run-cash-repair")
        seedResources(store, runId = "run-cash-repair", buyerCash = "15025000000000", suffix = "repair")
        store.appendFacts(
            SettlementFactBundle(
                scenarioRunId = "run-cash-repair",
                repairs = listOf(repair("run-cash-repair", "settlement-break-settlement-obligation-trade-1-1"))
            )
        )
        val repaired = materializer.materialize("run-cash-repair")
        val afterDuplicate = materializer.materialize("run-cash-repair")
        val facts = store.factsByScenarioRunId("run-cash-repair")

        assertEquals(1, failed.materializedBreaks)
        assertEquals(0, beforeRepair.materializedAttempts)
        assertEquals(1, repaired.materializedAttempts)
        assertEquals(4, repaired.materializedLedgerEntries)
        assertEquals(1, repaired.materializedSettlements)
        assertEquals(1, repaired.materializedResolutions)
        assertEquals(0, afterDuplicate.materializedAttempts)
        assertEquals(2, facts.attempts.size)
        assertEquals(setOf(1, 2), facts.attempts.map { it.attemptNumber }.toSet())
        assertEquals("settlement-attempt-settlement-obligation-trade-1-2", facts.attempts.maxBy { it.attemptNumber }.settlementAttemptId)
        assertEquals(4, facts.ledgerEntries.size)
        assertEquals(1, facts.settlements.size)
        assertEquals(1, facts.resolutions.size)
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
        assertEquals(1, result.materializedAllocations)
        assertEquals(1, result.materializedConfirmations)
        assertEquals(1, result.materializedAffirmations)
        assertEquals(1, result.materializedAttempts)
        assertEquals(2, result.materializedLegOutcomes)
        assertEquals(4, result.materializedLedgerEntries)
        assertEquals(1, result.materializedSettlements)
        assertEquals("instant-post-trade-v1", facts.instructions.single().postTradeProfileId)
        assertEquals("instant-post-trade-v1", facts.attempts.single().postTradeProfileId)
        assertEquals("instant-post-trade-v1", facts.settlements.single().postTradeProfileId)
        assertEquals(4, facts.attempts.single().postTradePolicyVersion)
    }

    private fun seedResources(
        store: SettlementFactStore,
        runId: String,
        buyerCash: String = "",
        sellerSecurity: String = "",
        suffix: String = "initial"
    ) {
        val positions = listOfNotNull(
            buyerCash.ifBlank { null }?.let {
                resourcePosition(
                    runId = runId,
                    id = "resource-$runId-$suffix-buyer-cash",
                    participantId = "buyer-1",
                    accountId = "account-buyer-1",
                    assetType = SettlementLedgerEntryTypeCash,
                    assetId = "USD",
                    quantity = it
                )
            },
            sellerSecurity.ifBlank { null }?.let {
                resourcePosition(
                    runId = runId,
                    id = "resource-$runId-$suffix-seller-security",
                    participantId = "seller-1",
                    accountId = "account-seller-1",
                    assetType = SettlementLedgerEntryTypeSecurity,
                    assetId = "AAPL",
                    quantity = it
                )
            }
        )
        store.appendFacts(SettlementFactBundle(scenarioRunId = runId, resourcePositions = positions))
    }

    private fun repair(runId: String, breakId: String): SettlementRepairPostedFact {
        return SettlementRepairPostedFact(
            settlementRepairId = "repair-$runId-1",
            settlementBreakId = breakId,
            settlementObligationId = "settlement-obligation-trade-1",
            scenarioRunId = runId,
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 4,
            correlationId = "corr-repair-$runId",
            causationId = breakId,
            actorId = "ops-1",
            occurredAt = java.time.Instant.parse("2026-01-01T00:00:01Z")
        )
    }

    private fun resourcePosition(
        runId: String,
        id: String,
        participantId: String,
        accountId: String,
        assetType: String,
        assetId: String,
        quantity: String
    ): SettlementResourcePositionFact {
        return SettlementResourcePositionFact(
            resourcePositionId = id,
            scenarioRunId = runId,
            postTradeProfileId = "instant-post-trade-v1",
            postTradePolicyVersion = 4,
            correlationId = "corr-resource-$runId",
            causationId = "seed-resource-$runId",
            participantId = participantId,
            accountId = accountId,
            assetType = assetType,
            assetId = assetId,
            quantity = quantity,
            occurredAt = java.time.Instant.parse("2025-12-31T23:59:59Z")
        )
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

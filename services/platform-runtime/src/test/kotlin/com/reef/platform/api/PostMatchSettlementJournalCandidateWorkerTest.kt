package com.reef.platform.api

import com.reef.platform.application.postmatch.CanonicalOutcomeSource
import com.reef.platform.application.postmatch.CanonicalSourceCoverageVerifier
import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.application.settlementjournal.ReferenceFunding
import com.reef.platform.application.settlementjournal.ReferenceOpening
import com.reef.platform.application.settlementjournal.ReferencePolicyActivation
import com.reef.platform.application.settlementjournal.ReferenceResultKind
import com.reef.platform.application.settlementjournal.ReferenceSettlementInterpreter
import com.reef.platform.application.settlementjournal.ReferenceSourceWindow
import com.reef.platform.application.settlementjournal.ReferenceStep
import com.reef.platform.application.settlementjournal.ReferenceStreamPartition
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostMatchSettlementJournalCandidateWorkerTest {
    @Test
    fun futureControlWaitsForDeterministicSourceOrOutstandingTrade() {
        val partition = ReferenceStreamPartition("settlement-future-test", "generation-1", 0)
        val policy = controls(partition).first() as ReferencePolicyActivation
        val future = policy.copy(effectiveAfterSourceFrontiers = mapOf(partition to 2L))
        assertFalse(settlementControlReady(future, mapOf(partition to 1L), emptyMap()))
        assertTrue(settlementControlReady(future, mapOf(partition to 2L), emptyMap()))
        val funding = ReferenceFunding(controlSequence = 4, controlId = "funding-1",
            account = ReferenceAccountKey("run-1", "buyer", "buyer-account", "CASH", "USD"),
            amount = BigDecimal.ONE, retryTradeIds = listOf("trade-1"))
        assertFalse(settlementControlReady(funding, emptyMap(), emptyMap()))
        assertTrue(settlementControlReady(funding.copy(retryTradeIds = emptyList()),
            emptyMap(), emptyMap()))
    }

    @Test
    fun independentlyPreparedTradeManifestMatchesReferenceInterpreter() {
        val stream = "settlement-candidate-test"
        val partition = ReferenceStreamPartition(stream, "generation-1", 0)
        val controls = controls(partition)
        val sources = listOf(
            source(stream, 1, "seller-order", "seller", "SELL", false),
            source(stream, 2, "buyer-order", "buyer", "BUY", true)
        )
        val verified = CanonicalSourceCoverageVerifier().verify("candidate-test", stream, 0,
            partition.sourceGeneration, 0, 2, sources)
        val decoder = SettlementCandidateTradeManifest()
        val state = SettlementCandidateManifestState().withControls(controls)
        val prepared = decoder.prepare(controls.size, state, verified)
        val referenceWindow = ReferenceSourceWindow(stream = partition,
            fromExclusiveSequence = 0, throughInclusiveSequence = 2,
            outcomes = sources, evidence = prepared.evidence)
        val reference = ReferenceSettlementInterpreter({ _, _ -> true }, { _, _ -> true })
            .evaluate(controls.map(ReferenceStep::Control) + ReferenceStep.Source(referenceWindow))

        assertEquals(reference.coverageDigests.getValue(prepared.key),
            prepared.journalWindow.coverageDigest)
        controls.forEach { control -> assertEquals(reference.controlDigests.getValue(control.controlId),
            settlementReferenceControlDigest(control)) }
        assertEquals(reference.results.map { it.trade }, prepared.trades)
        assertEquals(listOf(ReferenceResultKind.SETTLED), reference.results.map { it.kind })
        assertEquals(2, prepared.journalWindow.members.size)
        assertEquals(emptyMap(), prepared.nextManifestState.orders)
        assertEquals(setOf("seller-order", "buyer-order"), prepared.nextManifestState.seenOrderIds)
    }

    @Test
    fun emptyRangeDigestMatchesReferenceAndContainsNoTrade() {
        val partition = ReferenceStreamPartition("settlement-empty-test", "generation-1", 0)
        val prepared = SettlementCandidateTradeManifest().prepareEmpty(0,
            SettlementCandidateManifestState(), partition, 0, 2, "broker-bound-absence-proof")
        val reference = ReferenceSettlementInterpreter({ _, _ -> true }, { _, _ -> true })
            .evaluate(listOf(ReferenceStep.Source(ReferenceSourceWindow(stream = partition,
                fromExclusiveSequence = 0, throughInclusiveSequence = 2,
                outcomes = emptyList(), evidence = prepared.evidence))))

        assertEquals(reference.coverageDigests.getValue(prepared.key),
            prepared.journalWindow.coverageDigest)
        assertEquals(emptyList(), prepared.trades)
        assertEquals(emptyList(), prepared.journalWindow.members)
    }

    private fun controls(partition: ReferenceStreamPartition) = listOf(
        ReferencePolicyActivation(controlSequence = 1, controlId = "policy-1", runId = "run-1",
            venueSessionId = "session-1", effectiveAfterSourceFrontiers = mapOf(partition to 0),
            profileId = "instant-post-trade-v1", policyVersion = 1,
            mode = "instant-post-trade", settlementCycle = "T+0", nettingMode = "gross",
            ledgerPostingMode = "gross-dvp", selectionSource = "fixture"),
        ReferenceOpening(controlSequence = 2, controlId = "buyer-cash",
            account = ReferenceAccountKey("run-1", "buyer", "buyer-account", "CASH", "USD"),
            amount = BigDecimal("50")),
        ReferenceOpening(controlSequence = 3, controlId = "seller-security",
            account = ReferenceAccountKey("run-1", "seller", "seller-account", "SECURITY", "AAPL"),
            amount = BigDecimal.ONE)
    )

    internal fun source(stream: String, sequence: Long, orderId: String, owner: String,
        side: String, trade: Boolean): CanonicalOutcomeSource {
        val at = "2026-09-28T00:00:00Z"
        val identity = """{"orderId":"$orderId","engineOrderId":"engine-$orderId",
            "clientOrderId":"client-$orderId","runId":"run-1","venueSessionId":"session-1",
            "instrumentId":"AAPL","participantId":"$owner","accountId":"$owner-account",
            "side":"$side","orderType":"LIMIT","quantityUnits":"1","limitPrice":"50",
            "currency":"USD","timeInForce":"DAY","acceptedAt":"$at"}"""
        val executions = if (trade) """, "executions":[
            {"eventId":"buy-execution-1","executionId":"execution-1-buy",
             "orderId":"buyer-order","instrumentId":"AAPL","quantityUnits":"1",
             "executionPrice":"50","currency":"USD","occurredAt":"$at",
             "liquidityRole":"TAKER"},
            {"eventId":"sell-execution-1","executionId":"execution-1-sell",
             "orderId":"seller-order","instrumentId":"AAPL","quantityUnits":"1",
             "executionPrice":"50","currency":"USD","occurredAt":"$at",
             "liquidityRole":"MAKER"}]""" else ""
        val trades = if (trade) """, "trades":[{"eventId":"trade-event-1","tradeId":"trade-1",
            "executionId":"execution-1","buyOrderId":"buyer-order",
            "sellOrderId":"seller-order","instrumentId":"AAPL","quantityUnits":"1",
            "price":"50","currency":"USD","occurredAt":"$at"}]""" else ""
        val incomingState = """{"orderId":"$orderId","instrumentId":"AAPL","side":"$side",
            "status":"${if (trade) "FILLED" else "OPEN"}","originalQuantity":"1",
            "remainingQuantity":"${if (trade) "0" else "1"}","limitPrice":"50",
            "currency":"USD","lastUpdatedAt":"$at"}"""
        val makerState = """,{"orderId":"seller-order","instrumentId":"AAPL",
            "side":"SELL","status":"FILLED","originalQuantity":"1",
            "remainingQuantity":"0","limitPrice":"50","currency":"USD",
            "lastUpdatedAt":"$at"}"""
        val payload = """{"effectVersion":1,"accepted":{"eventId":"accepted-$sequence",
            "orderId":"$orderId","occurredAt":"$at"},"acceptedOrder":$identity
            $executions$trades,"orderStates":[$incomingState${if (trade) makerState else ""}]}"""
        return CanonicalOutcomeSource(stream, 0, sequence, "batch-$sequence",
            "command-$sequence", "SubmitOrder", "hash-$sequence", "AAPL", orderId,
            "accepted", payload)
    }
}

package com.reef.platform.api

import com.reef.platform.application.settlementjournal.ReferenceAccountKey
import com.reef.platform.infrastructure.persistence.MatchingMarketBook
import com.reef.platform.infrastructure.persistence.MatchingMarketFrontier
import com.reef.platform.infrastructure.persistence.MatchingMarketLevel
import com.reef.platform.infrastructure.persistence.MatchingMarketTape
import com.reef.platform.infrastructure.persistence.MatchingMarketTrade
import com.reef.platform.infrastructure.persistence.MatchingMarketVector
import com.reef.platform.infrastructure.persistence.PostMatchReadSourceCatalog
import com.reef.platform.infrastructure.persistence.SettlementProjectionAccountRead
import com.reef.platform.infrastructure.persistence.SettlementProjectionFrontier
import com.reef.platform.infrastructure.persistence.SettlementProjectionTrade
import com.reef.platform.infrastructure.persistence.SettlementProjectionTradeRead
import com.reef.platform.infrastructure.persistence.SettlementJournalFinalityAnchor
import com.reef.platform.infrastructure.persistence.SettlementJournalFinalityAnchorReader
import com.reef.platform.infrastructure.persistence.SettlementJournalStore
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostMatchCandidateReadGatewayTest {
    private val scope = CandidateMarketScope(0, "run", "session", "AAPL", "USD")
    private val account = ReferenceAccountKey("run", "buyer", "account", "CASH", "USD")
    private val sourceCatalog = FakeCatalog()
    private val market = FakeMarket()
    private val settlement = FakeSettlement()
    private val finality = FakeFinality()
    private val execution = FakeExecution()

    @Test
    fun executedTapeSurvivesSettlementBreakAndKeepsIndependentFrontiers() {
        settlement.trade = SettlementProjectionTrade("run", "trade-1", 1,
            "BREAK", "SECURITY_LEG_FAILED", 4, 0)
        val gateway = gateway()
        val tape = gateway.tape(scope)
        val status = gateway.tradeStatus("run", "trade-1")
        assertEquals("MATCHING_OUTCOME", tape["executionAuthority"])
        assertEquals("trade-1", (tape["trades"] as List<*>).single().let {
            (it as Map<*, *>)["tradeId"]
        })
        assertEquals("BREAK", (status["settlement"] as Map<*, *>)["outcome"])
        assertEquals("SECURITY_LEG_FAILED", (status["settlement"] as Map<*, *>)["breakReason"])
        assertEquals("EXECUTED", (status["execution"] as Map<*, *>)["status"])
        assertEquals("MATCHING_OUTCOME", (status["execution"] as Map<*, *>)["authority"])
        assertEquals(3L, (status["executionAsOf"] as Map<*, *>)["sourceSequence"])
        assertEquals(3L, (tape["asOf"] as Map<*, *>)["projectedSourceSequence"])
        assertEquals(4L, (status["asOf"] as Map<*, *>)["batchSequence"])
        assertEquals(1, market.tapeCalls)
        assertEquals(1, settlement.tradeCalls)
    }

    @Test
    fun financialReadsRejectLagWhenCurrentIsRequiredAndDiscloseItOtherwise() {
        settlement.frontier = settlement.frontier.copy(observedJournalSequence = 5, lagBatches = 1)
        val gateway = gateway()
        assertFailsWith<IllegalStateException> { gateway.balance(account) }
        val stale = gateway.balance(account, requireCurrent = false)
        assertEquals("50.00", stale["balance"])
        assertEquals(1L, (stale["asOf"] as Map<*, *>)["journalLagBatches"])
        assertFalse((stale["asOf"] as Map<*, *>)["currentAtObservation"] as Boolean)
    }

    @Test
    fun financialReadsRejectUnacknowledgedProjectionOrMissingAnchor() {
        finality.unavailable = true
        assertFailsWith<IllegalStateException> { gateway().balance(account, false) }
        finality.unavailable = false
        finality.anchor = null
        assertFailsWith<IllegalStateException> { gateway().balance(account, false) }
        assertFailsWith<IllegalStateException> { gateway().tradeStatus("run", "trade-1", false) }
        finality.anchor = finality.default.copy(acknowledgedBatchSequence = 3,
            acknowledgedBatchDigest = "prior-digest")
        assertFailsWith<IllegalStateException> { gateway().balance(account, false) }
        assertFailsWith<IllegalStateException> { gateway().tradeStatus("run", "trade-1", false) }
        finality.anchor = finality.default.copy(acknowledgedBatchDigest = "wrong-digest")
        assertFailsWith<IllegalStateException> { gateway().balance(account, false) }
        settlement.frontier = settlement.frontier.copy(journalIncarnationId = "restored-incarnation")
        assertFailsWith<IllegalStateException> { gateway().tradeStatus("run", "trade-1", false) }
    }

    @Test
    fun unacknowledgedJournalTailCannotClaimCurrent() {
        settlement.frontier = settlement.frontier.copy(observedJournalSequence = 5,
            observedJournalDigest = "unacknowledged-digest", lagBatches = 1)
        val asOf = gateway().balance(account, false)["asOf"] as Map<*, *>
        assertEquals(4L, asOf["acknowledgedBatchSequence"])
        assertFalse(asOf["currentAtObservation"] as Boolean)
        assertFailsWith<IllegalStateException> { gateway().balance(account) }
    }

    @Test
    fun advancingAnchorDuringBalanceReadRetriesWholeRead() {
        settlement.afterRead = {
            finality.anchor = finality.default.copy(
                acknowledgedBatchSequence = 5, acknowledgedBatchDigest = "new-digest")
            settlement.frontier = settlement.frontier.copy(batchSequence = 5,
                batchDigest = "new-digest", observedJournalSequence = 5,
                observedJournalDigest = "new-digest")
        }
        val current = gateway().balance(account)
        assertEquals(5L, (current["asOf"] as Map<*, *>)["acknowledgedBatchSequence"])
        assertTrue((current["asOf"] as Map<*, *>)["currentAtObservation"] as Boolean)
        assertEquals(2, settlement.accountCalls)
    }

    @Test
    fun sameSequenceFinalityMutationFailsClosedWithoutRetry() {
        settlement.afterRead = { finality.anchor = finality.default.copy(
            acknowledgedBatchDigest = "wrong-digest") }
        assertFailsWith<IllegalStateException> { gateway().balance(account, false) }
        assertEquals(1, settlement.accountCalls)
    }

    @Test
    fun tradeStatusRetriesAdvancingAnchorAndKeepsExecutionFact() {
        settlement.trade = SettlementProjectionTrade("run", "trade-1", 1,
            "BREAK", "CASH_LEG_FAILED", 4, 0)
        settlement.afterRead = {
            finality.anchor = finality.default.copy(
                acknowledgedBatchSequence = 5, acknowledgedBatchDigest = "new-digest")
            settlement.frontier = settlement.frontier.copy(batchSequence = 5,
                batchDigest = "new-digest", observedJournalSequence = 5,
                observedJournalDigest = "new-digest")
        }
        val status = gateway().tradeStatus("run", "trade-1", false)
        assertEquals("EXECUTED", (status["execution"] as Map<*, *>)["status"])
        assertEquals("BREAK", (status["settlement"] as Map<*, *>)["outcome"])
        assertEquals(5L, (status["asOf"] as Map<*, *>)["batchSequence"])
        assertEquals(2, settlement.tradeCalls)
    }

    @Test
    fun matchedTradeWithNoSettlementFactStillReportsExecuted() {
        val status = gateway().tradeStatus("run", "trade-1", false)
        assertEquals("EXECUTED", (status["execution"] as Map<*, *>)["status"])
        assertNull(status["settlement"])
    }

    @Test
    fun missingOrMismatchedExecutionFailsClosed() {
        execution.fact = null
        assertFailsWith<IllegalStateException> { gateway().tradeStatus("run", "trade-1", false) }
        execution.fact = execution.default.copy(runId = "other-run")
        assertFailsWith<IllegalStateException> { gateway().tradeStatus("run", "trade-1", false) }
    }

    @Test
    fun diagnosticAgeIsExplicitUpperBoundNotActualProjectionLatency() {
        val gateway = gateway(CandidateSourceTimestampReader { _, _, _ ->
            Instant.parse("2026-09-28T00:00:00Z")
        })
        val asOf = gateway.book(scope, includeAge = true)["asOf"] as Map<*, *>
        assertEquals(2_000L, asOf["sourceAgeUpperBoundMs"])
        assertEquals("UPPER_BOUND_AT_READ", asOf["sourceAgeMethod"])
        val ordinary = gateway.book(scope)["asOf"] as Map<*, *>
        assertNull(ordinary["sourceAgeUpperBoundMs"])
        assertEquals("NOT_REQUESTED", ordinary["sourceAgeMethod"])
        assertEquals("50", ((gateway.depth(scope, 5)["asks"] as List<*>).single()
            as Map<*, *>)["price"])
    }

    @Test
    fun sourceGenerationChangeDuringReadFailsClosed() {
        market.afterRead = { sourceCatalog.value = "new-source-generation" }
        assertFailsWith<IllegalStateException> { gateway().book(scope) }
    }

    @Test
    fun vectorRequiresEveryConfiguredPartition() {
        val gateway = gateway(partitionCount = 2)
        assertFailsWith<IllegalStateException> { gateway.vector() }
        market.vectorFrontiers = mapOf(0 to market.frontier,
            1 to market.frontier.copy(partitionId = 1, projectedSequence = 1L shl 48,
                observedSourceSequence = 1L shl 48))
        val vector = gateway.vector()
        assertEquals(2, (vector["partitions"] as List<*>).size)
    }

    private fun gateway(ageReader: CandidateSourceTimestampReader? = null,
        partitionCount: Int = 1) = PostMatchCandidateReadGateway(sourceCatalog, market,
        settlement, SettlementJournalFinalityAnchorReader(finality::read),
        "stream", "market-gen", "settlement-gen", partitionCount,
        ageReader, Clock.fixed(Instant.parse("2026-09-28T00:00:02Z"), ZoneOffset.UTC), execution)

    private class FakeExecution : CandidateExecutionSourceReadPort {
        val default = CandidateExecutedTrade("run", "trade-1", "event-1", "execution-1",
            "source-gen", 0, 3, 0)
        var fact: CandidateExecutedTrade? = default
        override fun readExecution(eventStream: String, marketGeneration: String,
            sourceGeneration: String, partitionCount: Int, runId: String,
            tradeId: String): CandidateExecutedTrade = fact
                ?: error("candidate execution position is not yet visible")
    }

    private class FakeCatalog : PostMatchReadSourceCatalog {
        var value = "source-gen"
        override fun generation() = value
        override fun partitionHeads(eventStream: String, partitionCount: Int): Map<Int, Long> =
            (0 until partitionCount).associateWith { 3L }
    }

    private class FakeMarket : CandidateMarketReadPort {
        val frontier = MatchingMarketFrontier("stream", 0, "market-gen", "source-gen",
            3, "digest", 3, 0)
        var vectorFrontiers = mapOf(0 to frontier)
        var tapeCalls = 0
        var afterRead: () -> Unit = {}
        override fun readBook(stream: String, partition: Int, marketGeneration: String,
            sourceGeneration: String, scope: CandidateMarketScope, depth: Int,
            requireCurrent: Boolean): MatchingMarketBook {
            afterRead()
            return MatchingMarketBook(emptyList(), listOf(MatchingMarketLevel(
                BigDecimal("50"), BigDecimal.ONE)), frontier)
        }
        override fun readTape(stream: String, partition: Int, marketGeneration: String,
            sourceGeneration: String, scope: CandidateMarketScope, limit: Int,
            beforeSequence: Long?, beforeOrdinal: Int?, requireCurrent: Boolean): MatchingMarketTape {
            tapeCalls++
            return MatchingMarketTape(listOf(MatchingMarketTrade("trade-1", "event-1",
                "execution-1", BigDecimal.ONE, BigDecimal("50"),
                "2026-09-28T00:00:00Z", 3, 0)), frontier)
        }
        override fun readVector(stream: String, marketGeneration: String,
            sourceGenerations: Map<Int, String>, requireCurrent: Boolean): MatchingMarketVector =
            MatchingMarketVector(vectorFrontiers)
    }

    private class FakeSettlement : CandidateSettlementReadPort {
        var frontier = SettlementProjectionFrontier("stream", "settlement-gen", "incarnation",
            4, "digest", 4, "digest", 0)
        var trade: SettlementProjectionTrade? = null
        var tradeCalls = 0
        var accountCalls = 0
        var afterRead: () -> Unit = {}
        override fun readAccount(stream: String, generation: String, account: ReferenceAccountKey,
            requireCurrent: Boolean): SettlementProjectionAccountRead {
            accountCalls++
            val observed = frontier
            afterRead()
            return SettlementProjectionAccountRead(account, BigDecimal("50.00"), observed)
        }
        override fun readTrade(stream: String, generation: String, runId: String, tradeId: String,
            requireCurrent: Boolean): SettlementProjectionTradeRead {
            tradeCalls++
            val observed = frontier
            afterRead()
            return SettlementProjectionTradeRead(runId, tradeId, trade, observed)
        }
    }

    private class FakeFinality {
        val default = SettlementJournalFinalityAnchor("stream", "incarnation", 4,
            "digest", 0, SettlementJournalStore.ORIGIN_DIGEST, "a".repeat(64))
        var anchor: SettlementJournalFinalityAnchor? = default
        var unavailable = false
        fun read(stream: String): SettlementJournalFinalityAnchor? {
            if (unavailable) error("finality database unavailable")
            return anchor.takeIf { stream == "stream" }
        }
    }
}

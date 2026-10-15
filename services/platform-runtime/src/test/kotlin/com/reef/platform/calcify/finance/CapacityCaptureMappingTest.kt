package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleContract
import com.reef.platform.calcify.FiniteLifecycleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import reef.contracts.calcify.v1.FiniteLifecycleCaptureV1
import reef.contracts.calcify.v1.LifecycleDispositionV1
import reef.contracts.calcify.v1.LifecycleMemberV1

class CapacityCaptureMappingTest {
    private val binding = FiniteLifecycleFixtures.binding()
    private val twoTrades = FiniteLifecycleFixtures.sequence().completedRecordsList.single { it.source.sourceOffset == 2L }
    private val plan = CapacityPlan("plan", "p3-run", "p3-session", AssetId("USD"), AccountId("opening"), listOf(setOf("INS0", "INS1"), setOf("INS2", "INS3")), 1_000, 1_000, 2_000, 100)
    private val decoders = CapacityInputDecoders(plan, binding.digest)

    private fun readyIndexes(e: FiniteLifecycleCaptureV1) = e.membersList.withIndex().filter { it.value.disposition == LifecycleDispositionV1.LIFECYCLE_READY_EXECUTION }.map { it.index }
    /** Rewrites every ready member (instrument, accounts), re-seals the content digest. */
    private fun rewrite(e: FiniteLifecycleCaptureV1, instrument: (Int) -> String = { "INS0" }, buyer: (Int) -> String = { "u$it-b" }, seller: (Int) -> String = { "u$it-s" }, edit: (Int, LifecycleMemberV1.Builder) -> Unit = { _, _ -> }): FiniteLifecycleCaptureV1 {
        val b = e.toBuilder()
        for ((n, i) in readyIndexes(e).withIndex()) {
            val m = e.getMembers(i).toBuilder()
            m.tradeBuilder.factBuilder.setInstrumentId(instrument(n))
            m.getDependenciesBuilder(0).acceptanceBuilder.factBuilder.setAccountId(buyer(n))
            m.getDependenciesBuilder(1).acceptanceBuilder.factBuilder.setAccountId(seller(n))
            edit(n, m); b.setMembers(i, m)
        }
        val unsealed = b.build()
        return unsealed.toBuilder().setContentDigest(FiniteLifecycleContract.digest(unsealed)).build()
    }
    private fun decode(e: FiniteLifecycleCaptureV1, lane: Int = 0, d: CapacityInputDecoders = decoders) = d.decode("CAPTURE", lane, 0, e.toByteArray())

    @Test fun readyMembersBecomeACaptureAndTheirFirstSettleWithAccountsFromTheAcceptanceFacts() {
        val items = decode(rewrite(twoTrades, instrument = { if (it == 0) "INS0" else "INS1" }))
        assertEquals(4, items.size)
        val inputs = items.map { (it as CapacityItem.Run).input }
        val c0 = inputs[0] as FinanceInput.Capture; val s0 = inputs[1] as FinanceInput.Settle; val c1 = inputs[2] as FinanceInput.Capture
        assertEquals(AccountId("u0-b"), c0.buyer); assertEquals(AccountId("u0-s"), c0.seller)
        assertEquals(ExecutionKey("p3-run", "p3-session", "INS0", c0.execution.executionId), c0.execution)
        assertEquals("INS1", c1.execution.instrumentId)
        assertEquals(c0.execution, s0.execution); assertEquals(1, s0.attempt); assertEquals(Authority.OPERATOR, s0.authority)
        assertEquals(ActionKey("calcify-finance", "settle", "${c0.execution.executionId}/1"), s0.action)
    }

    @Test fun withoutAutoSettleOnlyCapturesAreProduced() {
        val manual = CapacityInputDecoders(CapacityPlan("plan", "p3-run", "p3-session", AssetId("USD"), AccountId("opening"), listOf(setOf("INS0")), 10, 10, 10, 10, autoSettle = false), binding.digest)
        assertTrue(decode(rewrite(twoTrades), d = manual).all { ((it as CapacityItem.Run).input) is FinanceInput.Capture })
    }

    @Test fun aMemberOutsideTheLaneIsBadAndTheRestOfTheRecordStillRuns() {
        val e = rewrite(twoTrades, instrument = { if (it == 0) "INS0" else "INS2" })
        val items = decode(e, lane = 0)
        assertEquals(3, items.size, "lane 0: capture+settle for INS0, one bad member for INS2")
        assertTrue(items[2] is CapacityItem.Bad)
        assertEquals(2, decode(e, lane = 1).count { it is CapacityItem.Run } , "lane 1 runs INS2 only")
    }

    @Test fun malformedAndForeignRecordsAreBadAndRecordsWithoutTradesAreSkipped() {
        assertTrue(decoders.decode("CAPTURE", 0, 0, byteArrayOf(1, 2, 3)).single() is CapacityItem.Bad)
        assertTrue(decode(rewrite(twoTrades).toBuilder().setFiniteBindingDigest("f".repeat(64)).build()).single() is CapacityItem.Bad)
        assertTrue(decode(rewrite(twoTrades).toBuilder().setContentDigest("0".repeat(64)).build()).single() is CapacityItem.Bad)
        assertTrue(decode(FiniteLifecycleCaptureV1.getDefaultInstance()).single() is CapacityItem.Bad, "an empty envelope fails the schema check")
        val noReady = FiniteLifecycleFixtures.sequence().completedRecordsList.first { e -> e.membersList.none { it.disposition == LifecycleDispositionV1.LIFECYCLE_READY_EXECUTION } }
        assertEquals(listOf(CapacityItem.Skip), decode(noReady))
        val noCheck = CapacityInputDecoders(plan, binding.digest, verifyContentDigest = false)
        assertTrue(decode(rewrite(twoTrades).toBuilder().setContentDigest("0".repeat(64)).build(), d = noCheck).first() is CapacityItem.Run)
    }

    @Test fun everyMalformedReadyMemberIsBadNotAStall() {
        val bad: Map<String, (LifecycleMemberV1.Builder) -> Unit> = mapOf(
            "other run" to { it.tradeBuilder.setRunId("other-run") },
            "instrument outside the plan" to { it.tradeBuilder.factBuilder.setInstrumentId("MSFT") },
            "other currency" to { it.tradeBuilder.factBuilder.priceBuilder.setCurrency("EUR") },
            "missing trade" to { it.clearTrade() },
            "one dependency" to { it.removeDependencies(1) },
            "buy order mismatch" to { it.tradeBuilder.factBuilder.setBuyOrderId("p0-9") },
            "same order both sides" to { it.tradeBuilder.factBuilder.setSellOrderId(it.tradeBuilder.factBuilder.buyOrderId) },
            "blank buyer account" to { it.getDependenciesBuilder(0).acceptanceBuilder.factBuilder.setAccountId(" ") },
            "zero quantity" to { it.tradeBuilder.factBuilder.quantityBuilder.setUnits("0") },
            "leading zero" to { it.tradeBuilder.factBuilder.quantityBuilder.setUnits("03") },
            "negative price" to { it.tradeBuilder.factBuilder.priceBuilder.setNanos("-1") },
            "int64 overflow" to { it.tradeBuilder.factBuilder.priceBuilder.setNanos("9223372036854775808") },
        )
        for ((name, change) in bad) {
            val items = decode(rewrite(twoTrades, edit = { n, m -> if (n == 0) change(m) }))
            assertTrue(items[0] is CapacityItem.Bad, name)
            assertEquals(3, items.size, "$name: the other member still produces its capture and settle")
        }
    }

    @Test fun controlRecordsAreFundOrSettleOnly() {
        fun ctl(i: FinanceInput) = decoders.decode("CONTROL", 0, 0, FinanceWire.toProto(i).toByteArray())
        val fund = FinanceInput.Fund(ActionKey("n", "f", "1"), Authority.OPERATOR, AccountId("u1"), AssetId("USD"), 5)
        assertEquals(listOf<CapacityItem>(CapacityItem.Run(fund)), ctl(fund))
        val capture = FinanceInput.Capture(ActionKey("n", "c", "1"), ExecutionKey("r", "s", "INS0", "e"), AccountId("a"), AccountId("b"), 1, 1)
        assertTrue(ctl(capture).single() is CapacityItem.Bad)
        assertTrue(decoders.decode("CONTROL", 0, 0, byteArrayOf(9, 9)).single() is CapacityItem.Bad)
    }

    @Test fun planLanesAreDisjointAndBindDistinctly() {
        assertEquals(0, plan.laneOf("INS1")); assertEquals(1, plan.laneOf("INS3")); assertEquals(null, plan.laneOf("MSFT"))
        assertTrue(plan.bindingDigest(0) != plan.bindingDigest(1))
        assertTrue(plan.bindingDigest(0) != CapacityPlan("other", "p3-run", "p3-session", AssetId("USD"), AccountId("opening"), plan.laneInstruments, 1_000, 1_000, 2_000, 100).bindingDigest(0))
        assertEquals(CapacityChain.genesisOf(plan.capacity(0)), CapacityLedger(plan.capacity(0)).genesisChain)
        kotlin.test.assertFailsWith<IllegalArgumentException> { CapacityPlan("p", "r", "s", AssetId("USD"), AccountId("o"), listOf(setOf("A"), setOf("A")), 1, 1, 1, 1) }
    }
}

package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleContract
import com.reef.platform.calcify.FiniteLifecycleFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import reef.contracts.calcify.finance.v1.FinanceInputV1
import reef.contracts.calcify.v1.FiniteLifecycleCaptureV1
import reef.contracts.calcify.v1.LifecycleDispositionV1
import reef.contracts.calcify.v1.MatchContextResolvedV1

/**
 * The decision mode of the capacity plan (docs/work/CALCIFY_CAPACITY_RUNTIME.md): `two-step` (the default, unchanged digests) or `capture-settle-v1`. The mode is part of
 * the lane binding, the plan file accepts it as the one optional field and validates it, and both capture-source decoders emit one CaptureSettle per trade in the new mode.
 */
class CaptureSettleModeTest {
    private val usd = AssetId("USD"); private val opening = AccountId("opening")
    private fun plan(mode: DecisionMode, autoSettle: Boolean = true, instruments: List<Set<String>> = listOf(setOf("INS0", "INS1"), setOf("INS2", "INS3"))) =
        CapacityPlan("plan", "p3-run", "p3-session", usd, opening, instruments, 1_000, 1_000, 2_000, 100, autoSettle = autoSettle, decisionMode = mode)

    // ---- the binding ----

    @Test fun theModeChangesEveryLaneBindingAndTheLaneConfigurationButTheDefaultDoesNot() {
        val two = plan(DecisionMode.TWO_STEP); val one = plan(DecisionMode.CAPTURE_SETTLE)
        for (lane in 0..1) {
            assertNotEquals(two.bindingDigest(lane), one.bindingDigest(lane), "lane $lane: a live lane is never switched in place")
            assertNotEquals(two.capacity(lane).digest(), one.capacity(lane).digest())
            assertEquals(DecisionMode.CAPTURE_SETTLE, one.spec(lane).capacity.decisionMode)
        }
        assertNotEquals(one.bindingDigest(0), one.bindingDigest(1))
        // a plan that never heard of the mode is the two-step plan, to the bit
        val legacy = CapacityPlan("plan", "p3-run", "p3-session", usd, opening, two.laneInstruments, 1_000, 1_000, 2_000, 100)
        assertEquals(DecisionMode.TWO_STEP, legacy.decisionMode)
        for (lane in 0..1) assertEquals(legacy.bindingDigest(lane), two.bindingDigest(lane))
    }

    @Test fun theTwoStepBindingDigestIsTheOneThatWasDerivedBeforeTheModeExisted() {
        // The formulas of the binding and of the configuration digest as they were before decisionMode existed, re-derived here independently of the production code.
        val p = plan(DecisionMode.TWO_STEP)
        val configDigest = Canon.sha(Canon.bytes {
            str("calcify-finance-config-v2-capacity"); str(StateCommitment.LABEL); str("accounts-admitted-by-funding")
            str("USD"); val secs = listOf("INS0", "INS1"); int(secs.size); secs.forEach { str(it) }; str("opening")
            int(1_000); int(1_000); int(2_000); int(100)
        })
        val binding = Canon.sha(Canon.bytes { str("calcify-capacity-lane-binding-v0"); str("plan"); str("p3-run"); str("p3-session"); int(2); int(0); long(8_000_000L); str(configDigest) })
        assertEquals(binding, p.bindingDigest(0))
    }

    @Test fun captureSettleNeedsAutoSettle() {
        assertFailsWith<IllegalArgumentException> { plan(DecisionMode.CAPTURE_SETTLE, autoSettle = false) }
        plan(DecisionMode.TWO_STEP, autoSettle = false)
    }

    // ---- the plan file ----

    private val digest = "a".repeat(64)
    private fun json(edit: (String) -> String = { it }) = edit("""{"schema":"calcify-capacity-plan-v1","planId":"arena-1","run":"run-1","session":"session-1","cashAsset":"USD","openingAccount":"opening","laneRouting":"explicit",
        "laneInstruments":[["INS0","INS1"],["INS2"]],"maxObligations":"1000000","maxCaptures":"1000000","maxSettleAttempts":"2000000","maxControls":"50000","sequenceBound":"8000000","autoSettle":true,
        "captureSource":{"kind":"finite-lifecycle-capture-v1","bindingDigest":"$digest","verifyContentDigest":true},"applicationId":"capacity-app","topics":{"capture":"CAP","control":"CTL","decisions":"DEC"}}""".replace(Regex("\\s*\n\\s*"), "")).toByteArray()
    private fun withMode(mode: String) = json { it.replace("\"autoSettle\":true,", "\"autoSettle\":true,\"decisionMode\":$mode,") }

    @Test fun thePlanFileAcceptsTheOptionalModeAndValidatesIt() {
        assertEquals(DecisionMode.TWO_STEP, CapacityRuntime.parsePlan(json()).plan.decisionMode, "absent means two-step")
        val explicitTwo = CapacityRuntime.parsePlan(withMode("\"two-step\"")); val absent = CapacityRuntime.parsePlan(json())
        assertEquals(DecisionMode.TWO_STEP, explicitTwo.plan.decisionMode)
        for (lane in 0..1) assertEquals(absent.plan.bindingDigest(lane), explicitTwo.plan.bindingDigest(lane), "spelling the default does not move the binding")
        val combined = CapacityRuntime.parsePlan(withMode("\"capture-settle-v1\""))
        assertEquals(DecisionMode.CAPTURE_SETTLE, combined.plan.decisionMode); assertNotEquals(absent.plan.bindingDigest(0), combined.plan.bindingDigest(0))
        assertEquals(combined.plan.bindingDigest(1), CapacityRuntime.parsePlan(withMode("\"capture-settle-v1\"")).plan.bindingDigest(1))
        assertTrue(combined.decoder is CapacityInputDecoders)
        val bad: Map<String, ByteArray> = mapOf(
            "unknown mode" to withMode("\"capture-settle-v2\""), "blank mode" to withMode("\"\""), "mode as boolean" to withMode("true"), "mode as number" to withMode("1"),
            "wrong case" to withMode("\"Capture-Settle-V1\""), "combined without auto settle" to json { it.replace("\"autoSettle\":true,", "\"autoSettle\":false,\"decisionMode\":\"capture-settle-v1\",") },
            "mode twice" to json { it.replace("\"autoSettle\":true,", "\"autoSettle\":true,\"decisionMode\":\"two-step\",\"decisionMode\":\"two-step\",") },
        )
        for ((name, bytes) in bad) assertFailsWith<IllegalArgumentException>(name) { CapacityRuntime.parsePlan(bytes) }
        // two-step without auto settle stays a valid plan (deferred settlement later)
        assertEquals(false, CapacityRuntime.parsePlan(json { it.replace("\"autoSettle\":true,", "\"autoSettle\":false,\"decisionMode\":\"two-step\",") }).plan.autoSettle)
    }

    // ---- the lifecycle-capture source ----

    private val binding = FiniteLifecycleFixtures.binding()
    private val twoTrades = FiniteLifecycleFixtures.sequence().completedRecordsList.single { it.source.sourceOffset == 2L }
    private fun readyIndexes(e: FiniteLifecycleCaptureV1) = e.membersList.withIndex().filter { it.value.disposition == LifecycleDispositionV1.LIFECYCLE_READY_EXECUTION }.map { it.index }
    private fun sealed(e: FiniteLifecycleCaptureV1, instrument: (Int) -> String): FiniteLifecycleCaptureV1 {
        val b = e.toBuilder()
        for ((n, i) in readyIndexes(e).withIndex()) {
            val m = e.getMembers(i).toBuilder()
            m.tradeBuilder.factBuilder.setInstrumentId(instrument(n))
            m.getDependenciesBuilder(0).acceptanceBuilder.factBuilder.setAccountId("u$n-b"); m.getDependenciesBuilder(1).acceptanceBuilder.factBuilder.setAccountId("u$n-s")
            b.setMembers(i, m)
        }
        val unsealed = b.build()
        return unsealed.toBuilder().setContentDigest(FiniteLifecycleContract.digest(unsealed)).build()
    }

    @Test fun theLifecycleSourceEmitsOneCaptureSettlePerReadyTradeInTheCombinedMode() {
        val e = sealed(twoTrades) { if (it == 0) "INS0" else "INS1" }
        val two = CapacityInputDecoders(plan(DecisionMode.TWO_STEP), binding.digest).decode("CAPTURE", 0, 0, e.toByteArray()).map { (it as CapacityItem.Run).input }
        val one = CapacityInputDecoders(plan(DecisionMode.CAPTURE_SETTLE), binding.digest).decode("CAPTURE", 0, 0, e.toByteArray()).map { (it as CapacityItem.Run).input }
        assertEquals(4, two.size); assertEquals(2, one.size)
        val captures = two.filterIsInstance<FinanceInput.Capture>()
        assertEquals(2, captures.size)
        for ((c, s) in captures.zip(one)) {
            s as FinanceInput.CaptureSettle
            assertEquals(ActionKey("calcify-finance", "capture-settle", c.execution.executionId), s.action)
            assertEquals(listOf(c.execution, c.buyer, c.seller, c.quantity, c.priceNanos), listOf(s.execution, s.buyer, s.seller, s.quantity, s.priceNanos), "the same trade")
        }
    }

    @Test fun theLifecycleSourceKeepsBadMembersAndRefusesTheCombinedBodyOnControl() {
        val d = CapacityInputDecoders(plan(DecisionMode.CAPTURE_SETTLE), binding.digest)
        val items = d.decode("CAPTURE", 0, 0, sealed(twoTrades) { if (it == 0) "INS0" else "INS2" }.toByteArray())
        assertEquals(2, items.size, "one CaptureSettle for INS0 and one bad member for INS2"); assertTrue(items[0] is CapacityItem.Run && items[1] is CapacityItem.Bad)
        val cs = FinanceInput.CaptureSettle(ActionKey("calcify-finance", "capture-settle", "e1"), ExecutionKey("p3-run", "p3-session", "INS0", "e1"), AccountId("a"), AccountId("b"), 1, 1)
        val raw = FinanceWire.toProto(cs).toByteArray()
        assertEquals(cs, FinanceWire.fromProto(FinanceInputV1.parseFrom(raw)))
        assertTrue(d.decode("CONTROL", 0, 0, raw).single() is CapacityItem.Bad, "a CaptureSettle body on the operator topic is Bad, as a Capture body is")
        assertTrue(CapacityResolvedContextDecoder(plan(DecisionMode.CAPTURE_SETTLE)).decode("CONTROL", 0, 0, raw).single() is CapacityItem.Bad)
    }

    // ---- the resolved-context source ----

    private val instruments = (0 until 9).map { "INS$it" }
    private fun resolvedPlan(mode: DecisionMode) = CapacityPlan("p", "run-1", "session-1", usd, opening, CapacityRouting.engineLanes("run-1", "session-1", instruments, 3), 100, 100, 200, 50, decisionMode = mode)
    private fun context(instrument: String, executionId: String = "exec-1"): MatchContextResolvedV1 {
        val b = MatchContextResolvedV1.newBuilder().setPolicyVersion(1).setRunId("run-1")
        b.tradeBuilder.setRunId("run-1").factBuilder.setExecutionId(executionId).setInstrumentId(instrument).setBuyOrderId("b1").setSellOrderId("s1").quantityBuilder.setUnits("5")
        b.tradeBuilder.factBuilder.priceBuilder.setNanos("1200").setCurrency("USD")
        b.buyAcceptedOrderBuilder.factBuilder.setOrderId("b1").setVenueSessionId("session-1").setInstrumentId(instrument).setAccountId("alice").setParticipantId("pa")
        b.sellAcceptedOrderBuilder.factBuilder.setOrderId("s1").setVenueSessionId("session-1").setInstrumentId(instrument).setAccountId("bob").setParticipantId("pb")
        return b.build()
    }

    @Test fun theResolvedSourceEmitsOneCaptureSettleWithTheSameEconomicsAsTheTwoStepCapture() {
        val two = resolvedPlan(DecisionMode.TWO_STEP); val one = resolvedPlan(DecisionMode.CAPTURE_SETTLE); val instrument = instruments.first(); val lane = two.laneOf(instrument)!!
        assertEquals(lane, one.laneOf(instrument))
        val a = CapacityResolvedContextDecoder(two).decode("CAPTURE", lane, 0, context(instrument).toByteArray()).map { (it as CapacityItem.Run).input }
        val b = CapacityResolvedContextDecoder(one).decode("CAPTURE", lane, 0, context(instrument).toByteArray()).map { (it as CapacityItem.Run).input }
        assertEquals(2, a.size); assertEquals(1, b.size)
        val c = a[0] as FinanceInput.Capture; val s = b.single() as FinanceInput.CaptureSettle
        assertEquals(ActionKey("calcify-finance", "capture-settle", "exec-1"), s.action)
        assertEquals(listOf(c.execution, c.buyer, c.seller, c.quantity, c.priceNanos), listOf(s.execution, s.buyer, s.seller, s.quantity, s.priceNanos))
        assertEquals(b.map { it.digest() }, CapacityResolvedContextDecoder(one).decode("CAPTURE", lane, 7, context(instrument).toByteArray()).map { (it as CapacityItem.Run).input.digest() }, "a redelivered context decodes to the same input")
        // every routing or consistency fault is still Bad in the new mode
        val dec = CapacityResolvedContextDecoder(one)
        assertTrue(dec.decode("CAPTURE", (lane + 1) % 3, 0, context(instrument).toByteArray()).single() is CapacityItem.Bad)
        assertTrue(dec.decode("CAPTURE", lane, 0, byteArrayOf(1, 2, 3)).single() is CapacityItem.Bad)
        assertTrue(dec.decode("CAPTURE", lane, 0, context("MSFT").toByteArray()).single() is CapacityItem.Bad)
    }

    @Test fun aTwoStepPlanWithoutAutoSettleStillEmitsTheCaptureOnly() {
        val p = CapacityPlan("p", "run-1", "session-1", usd, opening, CapacityRouting.engineLanes("run-1", "session-1", instruments, 3), 100, 100, 200, 50, autoSettle = false)
        val lane = p.laneOf(instruments.first())!!
        val items = CapacityResolvedContextDecoder(p).decode("CAPTURE", lane, 0, context(instruments.first()).toByteArray())
        assertTrue(items.single().let { it is CapacityItem.Run && it.input is FinanceInput.Capture })
    }
}

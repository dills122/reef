package com.reef.platform.calcify.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import reef.contracts.calcify.v1.MatchContextResolvedV1

class CapacityResolvedContextDecoderTest {
    private val instruments = (0 until 9).map { "INS$it" }
    private val plan = CapacityPlan("p", "run-1", "session-1", AssetId("USD"), AccountId("opening"), CapacityRouting.engineLanes("run-1", "session-1", instruments, 3), 100, 100, 200, 50)
    private val decoder = CapacityResolvedContextDecoder(plan)
    private val laneOf = instruments.associateWith { plan.laneOf(it)!! }
    private val anInstrument = instruments.first()

    private fun context(instrument: String = anInstrument, edit: (MatchContextResolvedV1.Builder) -> Unit = {}): MatchContextResolvedV1 {
        val b = MatchContextResolvedV1.newBuilder().setPolicyVersion(1).setRunId("run-1")
        b.tradeBuilder.setRunId("run-1").factBuilder.setExecutionId("exec-1").setInstrumentId(instrument).setBuyOrderId("b1").setSellOrderId("s1").quantityBuilder.setUnits("5")
        b.tradeBuilder.factBuilder.priceBuilder.setNanos("1200").setCurrency("USD")
        b.buyAcceptedOrderBuilder.factBuilder.setOrderId("b1").setVenueSessionId("session-1").setInstrumentId(instrument).setAccountId("alice").setParticipantId("pa")
        b.sellAcceptedOrderBuilder.factBuilder.setOrderId("s1").setVenueSessionId("session-1").setInstrumentId(instrument).setAccountId("bob").setParticipantId("pb")
        edit(b); return b.build()
    }
    private fun decode(c: MatchContextResolvedV1, lane: Int = laneOf.getValue(anInstrument)) = decoder.decode("CAPTURE", lane, 0, c.toByteArray())

    @Test fun aResolvedContextBecomesACaptureAndItsFirstSettleWithAccountsFromTheAcceptances() {
        val items = decode(context())
        assertEquals(2, items.size)
        val capture = (items[0] as CapacityItem.Run).input as FinanceInput.Capture; val settle = (items[1] as CapacityItem.Run).input as FinanceInput.Settle
        assertEquals(AccountId("alice"), capture.buyer); assertEquals(AccountId("bob"), capture.seller); assertEquals(5L, capture.quantity); assertEquals(1200L, capture.priceNanos)
        assertEquals(ExecutionKey("run-1", "session-1", anInstrument, "exec-1"), capture.execution)
        assertEquals(ActionKey("calcify-finance", "capture", "exec-1"), capture.action); assertEquals(capture.execution, settle.execution); assertEquals(1, settle.attempt)
        assertEquals(decode(context()).map { (it as CapacityItem.Run).input.digest() }, items.map { (it as CapacityItem.Run).input.digest() }, "a redelivered context decodes to the same inputs")
    }

    @Test fun everyRoutingOrConsistencyFaultIsBadAndNeverRecorded() {
        val lane = laneOf.getValue(anInstrument)
        val bad: Map<String, () -> List<CapacityItem>> = mapOf(
            "arrived on another lane" to { decode(context(), (lane + 1) % 3) },
            "instrument outside the plan" to { decode(context("MSFT")) },
            "other run" to { decode(context { it.setRunId("other") }) },
            "trade of another run" to { decode(context { it.tradeBuilder.setRunId("other") }) },
            "other session" to { decode(context { it.buyAcceptedOrderBuilder.factBuilder.setVenueSessionId("other") }) },
            "policy version" to { decode(context { it.setPolicyVersion(2) }) },
            "missing buy acceptance" to { decode(context { it.clearBuyAcceptedOrder() }) },
            "acceptance for another order" to { decode(context { it.sellAcceptedOrderBuilder.factBuilder.setOrderId("s9") }) },
            "acceptance for another instrument" to { decode(context { it.buyAcceptedOrderBuilder.factBuilder.setInstrumentId("INS8") }) },
            "same order both sides" to { decode(context { it.tradeBuilder.factBuilder.setSellOrderId("b1"); it.sellAcceptedOrderBuilder.factBuilder.setOrderId("b1") }) },
            "other currency" to { decode(context { it.tradeBuilder.factBuilder.priceBuilder.setCurrency("EUR") }) },
            "zero quantity" to { decode(context { it.tradeBuilder.factBuilder.quantityBuilder.setUnits("0") }) },
            "leading zero price" to { decode(context { it.tradeBuilder.factBuilder.priceBuilder.setNanos("012") }) },
            "blank account" to { decode(context { it.buyAcceptedOrderBuilder.factBuilder.setAccountId(" ") }) },
            "garbage" to { decoder.decode("CAPTURE", lane, 0, byteArrayOf(1, 2, 3)) },
        )
        for ((name, run) in bad) assertTrue(run().single() is CapacityItem.Bad, name)
    }

    @Test fun controlRecordsAreFundOrSettleOnlyAsForTheOtherSource() {
        val fund = FinanceInput.Fund(ActionKey("n", "f", "1"), Authority.OPERATOR, AccountId("alice"), AssetId("USD"), 5)
        assertEquals(listOf<CapacityItem>(CapacityItem.Run(fund)), decoder.decode("CONTROL", 0, 0, FinanceWire.toProto(fund).toByteArray()))
        assertTrue(decoder.decode("CONTROL", 0, 0, byteArrayOf(9, 9)).single() is CapacityItem.Bad)
    }

    @Test fun theRoutingIsTheOneTheRealEngineStackUsed() {
        // Observed in the compose stack (make dev-smoke-calcify-full-path, DEV_CALCIFY_PHASE2=1, id capsmoke1, four partitions): the real trade of AAPL-capsmoke1 was published
        // to venue event partition 2 and its resolved context to resolved partition 2.
        assertEquals(2, com.reef.platform.api.StreamCommandEnvelopeBuilder.partition("capsmoke1", "session-capsmoke1", "AAPL-capsmoke1", 4))
        val observed = CapacityPlan("p", "capsmoke1", "session-capsmoke1", AssetId("USD"), AccountId("opening"), CapacityRouting.engineLanes("capsmoke1", "session-capsmoke1", listOf("AAPL-capsmoke1", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K"), 4), 100, 100, 200, 50)
        assertEquals(2, observed.laneOf("AAPL-capsmoke1"))
    }

    @Test fun enginePartitioningGivesEveryLaneInstrumentsAndPlansAreCheckedAgainstIt() {
        plan.requireEngineAligned()
        val misaligned = CapacityPlan("p", "run-1", "session-1", AssetId("USD"), AccountId("opening"), plan.laneInstruments.reversed(), 100, 100, 200, 50)
        kotlin.test.assertFailsWith<IllegalArgumentException> { misaligned.requireEngineAligned() }
        kotlin.test.assertFailsWith<IllegalArgumentException> { CapacityRouting.engineLanes("run-1", "session-1", listOf("ONLY"), 3) }
    }
}

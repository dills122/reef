package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleContract
import com.reef.platform.calcify.MemoryLifecycleKv
import com.reef.platform.calcify.StreamsLifecycleKv
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.processor.api.MockProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import reef.contracts.calcify.finance.v1.FinanceDecisionV1
import reef.contracts.calcify.finance.v1.FinanceInputV1
import reef.contracts.calcify.v1.MatchContextResolvedV1

/**
 * The capacity processor in decision mode `capture-settle-v1`: one decision is forwarded per booked trade, the store restores to the ledger's state (including a trade
 * that settled at once and so left no attempt leaf), the redeliveries are benign and every other refusal of a captured input still stops the lane.
 */
class CaptureSettleProcessorTest {
    private val usd = AssetId("USD"); private val aapl = AssetId("AAPL"); private val opening = AccountId("opening")
    private val accounts = (0 until 6).map { AccountId("acct-$it") }.toSet()
    private val capacity = CapacityConfig(accounts, usd, setOf(aapl), opening, 80, 80, 160, 40, DecisionMode.CAPTURE_SETTLE)
    private val binding = "b".repeat(64)
    private val cfg = CapacityProcessorConfig(binding, capacity, "CAPTURE", "CONTROL", "DECISIONS", "capacity-cs-test")
    private val strict = CapacityProcessorConfig(1, { CapacityLaneSpec(binding, capacity) }, "CAPTURE", "CONTROL", "DECISIONS", "capacity-cs-test", failOnBadCapture = true)
    private val keys = (0 until 15).map { ExecutionKey("r", "s", "AAPL", "e$it") }

    /** A record's bytes are one FinanceInputV1 (any body: the CONTROL restriction is the real decoders', tested elsewhere). */
    private val decoder = CapacityInputDecoder { _, _, _, raw ->
        try { listOf(CapacityItem.Run(FinanceWire.fromProto(FinanceInputV1.parseFrom(raw)))) } catch (e: Exception) { listOf(CapacityItem.Bad(FiniteLifecycleContract.sha(raw))) }
    }
    private fun bytes(i: FinanceInput) = FinanceWire.toProto(i).toByteArray()
    private fun trade(n: Int, buyer: String = "acct-0", seller: String = "acct-1", qty: Long = 1, px: Long = 2) =
        FinanceInput.CaptureSettle(ActionKey("calcify-finance", "capture-settle", "e$n"), keys[n], AccountId(buyer), AccountId(seller), qty, px)

    private fun input(rnd: Random): FinanceInput {
        val who = { AccountId(if (rnd.nextInt(15) == 0) "ghost" else "acct-${rnd.nextInt(6)}") }; val n = rnd.nextInt(keys.size)
        return when (rnd.nextInt(3)) {
            0 -> FinanceInput.CaptureSettle(ActionKey("calcify-finance", "capture-settle", "e${rnd.nextInt(keys.size)}"), keys[n], who(), who(), 1L + rnd.nextInt(3), 1L + rnd.nextInt(6))
            1 -> FinanceInput.Fund(ActionKey("n", "d", "a${rnd.nextInt(90)}"), if (rnd.nextInt(10) == 0) Authority.TRADING else Authority.OPERATOR, who(), if (rnd.nextBoolean()) usd else aapl, 1L + rnd.nextInt(30))
            else -> FinanceInput.Settle(ActionKey("n", "d", "a${rnd.nextInt(90)}"), Authority.OPERATOR, keys[n], rnd.nextInt(3))
        }
    }
    private fun direct(inputs: List<FinanceInput>): Pair<CapacityLedger, List<Decision>> { val l = CapacityLedger(capacity); return l to inputs.mapNotNull { l.decide(it).decision } }

    private inner class Harness(config: CapacityProcessorConfig = cfg, preload: Map<String, ByteArray> = emptyMap()) {
        val context = MockProcessorContext<String, ByteArray>()
        val store: KeyValueStore<String, ByteArray> = CapacityTopology.storeBuilder(config, persistent = false).withLoggingDisabled().build()
        val processor: CapacityProcessor
        init { store.init(context.getStateStoreContext(), store); context.addStateStore(store); preload.forEach { (k, v) -> store.put(k, v) }; processor = CapacityProcessor(config, decoder); processor.init(context) }
        private var offset = 0L
        fun send(i: FinanceInput, topic: String = "CAPTURE") { context.setRecordMetadata(topic, 0, offset++); processor.process(Record(ByteArray(0), bytes(i), 0L)) }
        fun decisions(): List<Decision> = context.forwarded().map { FinanceWire.fromProto(FinanceDecisionV1.parseFrom(it.record().value())) }
        fun snapshot(): Map<String, ByteArray> = store.all().use { it.asSequence().associate { kv -> kv.key to kv.value } }
        fun restored() = CapacityStore.restore(capacity, StreamsLifecycleKv(store), binding)
    }

    @Test fun theProcessorForwardsOneDecisionPerRecordedInputAndTheStoreRestoresToTheLedgersState() {
        var settledAtOnce = 0
        for (seed in 1..20L) {
            val rnd = Random(seed); val inputs = (0 until 150).map { input(rnd) }
            val h = Harness(); inputs.forEachIndexed { n, i -> h.send(i, if (i is FinanceInput.Fund || n % 5 == 0) "CONTROL" else "CAPTURE") }
            val (ledger, expected) = direct(inputs)
            assertEquals(expected, h.decisions(), "seed $seed")
            assertEquals(ledger.snapshot(), h.restored()?.snapshot() ?: ledger.snapshot(), "seed $seed: the store holds exactly the ledger's state")
            assertEquals(ledger.snapshot(), h.processor.currentLedger().snapshot())
            h.decisions().forEach { d -> assertEquals(1, h.decisions().count { it.action == d.action }, "one decision per action key") }
            val ch = CapacityChain(capacity); var prior = ch.genesisChain
            h.decisions().forEach { d -> assertTrue(ch.verifyLink(prior, d.sequence, d)); prior = d.checksum }
            settledAtOnce += ledger.snapshot().obligations.values.count { it.status == ObligationStatus.SETTLED } - ledger.snapshot().attempts.values.count { it == Disposition.SETTLED }
            assertEquals(inputs.size - expected.size, h.processor.refusals.toInt(), "every input that was not recorded was counted as a refusal")
        }
        assertTrue(settledAtOnce > 20, "the schedules include trades that settled at once and so have no attempt leaf: $settledAtOnce")
    }

    @Test fun aRestartRestoresFromTheStoreAndContinuesAsIfItNeverStopped() {
        for (seed in 1..25L) {
            val rnd = Random(seed); val inputs = (0 until 120).map { input(rnd) }; val cut = rnd.nextInt(1, inputs.size)
            val first = Harness(); inputs.take(cut).forEach { first.send(it) }
            val second = Harness(preload = first.snapshot()); inputs.drop(cut).forEach { second.send(it) }
            val (_, expected) = direct(inputs)
            assertEquals(expected, first.decisions() + second.decisions(), "seed $seed cut $cut")
        }
    }

    @Test fun aShortfallIsARecordedDecisionNotARefusalAndTheLaneKeepsRunning() {
        val h = Harness(strict)
        h.send(FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-1"), aapl, 10), "CONTROL")
        h.send(trade(0))                                                                       // the buyer has no cash: recorded INSUFFICIENT_CASH, obligation pending
        assertEquals(listOf(Disposition.FUNDED, Disposition.INSUFFICIENT_CASH), h.decisions().map { it.disposition })
        h.send(FinanceInput.Fund(ActionKey("n", "c", "f2"), Authority.OPERATOR, AccountId("acct-0"), usd, 100), "CONTROL")
        h.send(FinanceInput.Settle(ActionKey("n", "r", "r2"), Authority.OPERATOR, keys[0], 2), "CONTROL")        // the operator retry, attempt 2
        assertEquals(Disposition.SETTLED, h.decisions().last().disposition)
        val restored = assertNotNull(h.restored()); assertEquals(ObligationStatus.SETTLED, restored.snapshot().obligations.getValue(keys[0]).status)
        assertEquals(0L, h.processor.refusals)
    }

    @Test fun redeliveriesAreBenignAndEveryOtherRefusalOfACapturedTradeStopsTheLane() {
        val h = Harness(strict)
        h.send(FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 100), "CONTROL")
        h.send(FinanceInput.Fund(ActionKey("n", "c", "f2"), Authority.OPERATOR, AccountId("acct-1"), aapl, 100), "CONTROL")
        h.send(trade(0))
        val recorded = h.decisions().size
        h.send(trade(0))                                                                                            // PRIOR_RESULT
        h.send(trade(0).copy(action = ActionKey("calcify-finance", "capture-settle", "other")))                       // EXECUTION_ALREADY_CAPTURED
        h.send(FinanceInput.Capture(ActionKey("calcify-finance", "capture", "e0"), keys[0], AccountId("acct-0"), AccountId("acct-1"), 1, 2))   // a two-step history's redelivery: EXECUTION_ALREADY_CAPTURED
        assertEquals(recorded, h.decisions().size, "redeliveries record and forward nothing"); assertEquals(3L, h.processor.refusals)
        val stops: Map<String, FinanceInput> = mapOf(
            "UNKNOWN_ACCOUNT" to trade(1, buyer = "ghost"), "EXECUTION_CONFLICT" to trade(0, px = 3).copy(action = ActionKey("calcify-finance", "capture-settle", "other2")),
            "ACTION_CONFLICT" to trade(0, px = 3), "INVALID_INPUT" to trade(2, buyer = "acct-1", seller = "acct-1"), "AMOUNT_OVERFLOW" to trade(3, qty = Long.MAX_VALUE, px = 2),
        )
        for ((name, i) in stops) {
            val failure = assertFailsWith<IllegalStateException>(name) { h.send(i) }
            assertTrue(failure.message!!.contains(name) && failure.message!!.contains("the lane stops"), failure.message)
        }
        assertEquals(recorded, h.decisions().size)
    }

    @Test fun anExhaustedCaptureBudgetStopsTheLaneAndRecordsNothingOfThatTrade() {
        val small = CapacityConfig(accounts, usd, setOf(aapl), opening, 2, 2, 160, 40, DecisionMode.CAPTURE_SETTLE)
        val config = CapacityProcessorConfig(1, { CapacityLaneSpec(binding, small) }, "CAPTURE", "CONTROL", "DECISIONS", "capacity-cs-test", failOnBadCapture = true)
        val h = Harness(config)
        h.send(FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 100), "CONTROL")
        h.send(FinanceInput.Fund(ActionKey("n", "c", "f2"), Authority.OPERATOR, AccountId("acct-1"), aapl, 100), "CONTROL")
        h.send(trade(0)); h.send(trade(1))
        val before = h.decisions().size; val store = h.snapshot()
        val failure = assertFailsWith<IllegalStateException> { h.send(trade(2)) }
        assertTrue(failure.message!!.contains("BUDGET_EXCEEDED"), failure.message)
        assertEquals(before, h.decisions().size); assertEquals(store.keys, h.snapshot().keys, "no half-booked trade")
    }

    @Test fun aLaneInTheTwoStepModeStopsOnTheCombinedInput() {
        val twoStep = CapacityConfig(accounts, usd, setOf(aapl), opening, 80, 80, 160, 40)
        val config = CapacityProcessorConfig(1, { CapacityLaneSpec(binding, twoStep) }, "CAPTURE", "CONTROL", "DECISIONS", "capacity-cs-test", failOnBadCapture = true)
        val h = Harness(config)
        val failure = assertFailsWith<IllegalStateException> { h.send(trade(0)) }
        assertTrue(failure.message!!.contains("INVALID_INPUT"), failure.message)
        assertTrue(h.decisions().isEmpty())
    }

    // ---- end to end on the topology with the real resolved-context decoder ----

    private val instruments = (0 until 6).map { "INS$it" }
    private fun context(instrument: String, executionId: String, buyer: String, seller: String): MatchContextResolvedV1 {
        val b = MatchContextResolvedV1.newBuilder().setPolicyVersion(1).setRunId("run-1")
        b.tradeBuilder.setRunId("run-1").factBuilder.setExecutionId(executionId).setInstrumentId(instrument).setBuyOrderId("b-$executionId").setSellOrderId("s-$executionId").quantityBuilder.setUnits("5")
        b.tradeBuilder.factBuilder.priceBuilder.setNanos("1200").setCurrency("USD")
        b.buyAcceptedOrderBuilder.factBuilder.setOrderId("b-$executionId").setVenueSessionId("session-1").setInstrumentId(instrument).setAccountId(buyer).setParticipantId("pa")
        b.sellAcceptedOrderBuilder.factBuilder.setOrderId("s-$executionId").setVenueSessionId("session-1").setInstrumentId(instrument).setAccountId(seller).setParticipantId("pb")
        return b.build()
    }

    @Test fun topologyOnTheTestDriverTurnsEachResolvedTradeIntoOneVerifiedDecisionAndTwoStepTurnsItIntoTwo() {
        fun run(mode: DecisionMode): List<Pair<Decision, FinanceInput>> {
            val plan = CapacityPlan("p", "run-1", "session-1", usd, opening, listOf(instruments.toSet()), 1_000, 1_000, 2_000, 100, decisionMode = mode)
            val config = CapacityProcessorConfig(plan, "CAPTURE", "CONTROL", "DECISIONS", "capacity-cs-e2e")
            val props = Properties().apply { put(StreamsConfig.APPLICATION_ID_CONFIG, "capacity-cs-e2e"); put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234") }
            TopologyTestDriver(CapacityTopology.build(config, CapacityResolvedContextDecoder(plan), persistent = false), props).use { driver ->
                val capture = driver.createInputTopic("CAPTURE", ByteArraySerializer(), ByteArraySerializer()); val control = driver.createInputTopic("CONTROL", ByteArraySerializer(), ByteArraySerializer())
                for (a in 0..2) control.pipeInput(ByteArray(0), bytes(FinanceInput.Fund(ActionKey("op", "fund", "c$a"), Authority.OPERATOR, AccountId("acct-$a"), usd, 10_000_000)))
                for (a in 0..2) for (s in instruments) control.pipeInput(ByteArray(0), bytes(FinanceInput.Fund(ActionKey("op", "fund", "s$a-$s"), Authority.OPERATOR, AccountId("acct-$a"), AssetId(s), 1_000)))
                for (t in 0 until 40) capture.pipeInput(ByteArray(0), context(instruments[t % instruments.size], "x$t", "acct-${t % 3}", "acct-${(t + 1) % 3}").toByteArray())
                capture.pipeInput(ByteArray(0), context(instruments[0], "x0", "acct-0", "acct-1").toByteArray())          // a redelivery of the first trade
                val out = driver.createOutputTopic("DECISIONS", StringDeserializer(), ByteArrayDeserializer()).readKeyValuesToList()
                val records = out.map { FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(it.value)) }
                val chain = CapacityChain(plan.capacity(0)); var prior = chain.genesisChain
                records.forEach { (d, i) -> assertTrue(chain.verifyLink(prior, d.sequence, d)); prior = d.checksum; assertEquals(d.inputDigest, i.digest()) }
                return records
            }
        }
        val one = run(DecisionMode.CAPTURE_SETTLE); val two = run(DecisionMode.TWO_STEP)
        val funds = 3 + 3 * instruments.size
        assertEquals(funds + 40, one.size, "one decision per booked trade after the funding decisions"); assertEquals(funds + 80, two.size)
        assertEquals(40, one.count { it.second is FinanceInput.CaptureSettle }); assertTrue(one.none { it.second is FinanceInput.Settle || it.second is FinanceInput.Capture })
        assertTrue(one.filter { it.second is FinanceInput.CaptureSettle }.all { it.first.disposition == Disposition.SETTLED })
        // the same positions at the end: the ledger the log replays to is the one the trades booked (replay through a fresh ledger of each mode)
        fun replay(records: List<Pair<Decision, FinanceInput>>, mode: DecisionMode): FinanceState {
            val l = CapacityLedger(CapacityConfig(null, usd, instruments.map { AssetId(it) }.toSet(), opening, 1_000, 1_000, 2_000, 100, mode))
            records.forEach { (d, i) -> assertEquals(d, l.decide(i).decision, "replay of ${d.sequence}") }
            return l.snapshot()
        }
        assertEquals(replay(one, DecisionMode.CAPTURE_SETTLE).positions.filterValues { it != 0L }, replay(two, DecisionMode.TWO_STEP).positions.filterValues { it != 0L })
    }

    @Test fun theStoreOfOneModeIsRefusedByTheOther() {
        val h = Harness(); h.send(FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 5), "CONTROL")
        val kv = MemoryLifecycleKv(); h.snapshot().forEach { (k, v) -> kv.apply(mapOf(k to v)) }
        val twoStep = CapacityConfig(accounts, usd, setOf(aapl), opening, 80, 80, 160, 40)
        assertFailsWith<IllegalArgumentException> { CapacityStore.restore(twoStep, kv, binding) }
        assertNotNull(CapacityStore.restore(capacity, kv, binding))
    }
}

package com.reef.platform.calcify.finance

import com.reef.platform.calcify.FiniteLifecycleContract
import com.reef.platform.calcify.StreamsLifecycleKv
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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

/** The capacity processor on a mock context (restart, refusals, batching) and on TopologyTestDriver (end to end), with the decoder injected. */
class CapacityProcessorTest {
    private val usd = AssetId("USD"); private val aapl = AssetId("AAPL"); private val opening = AccountId("opening")
    private val accounts = (0 until 6).map { AccountId("acct-$it") }.toSet()
    private val capacity = CapacityConfig(accounts, usd, setOf(aapl), opening, 80, 80, 160, 40)
    private val binding = "b".repeat(64)
    private val cfg = CapacityProcessorConfig(binding, capacity, "CAPTURE", "CONTROL", "DECISIONS", "capacity-test")
    private val keys = (0 until 15).map { ExecutionKey("r", "s", "AAPL", "e$it") }

    /** A record's bytes are one FinanceInputV1; a record that does not parse is unusable; the byte 0x00 alone is a record with nothing to do. */
    private val decoder = CapacityInputDecoder { _, _, _, raw ->
        if (raw.size == 1 && raw[0] == 0.toByte()) listOf(CapacityItem.Skip)
        else try { listOf(CapacityItem.Run(FinanceWire.fromProto(FinanceInputV1.parseFrom(raw)))) } catch (e: Exception) { listOf(CapacityItem.Bad(FiniteLifecycleContract.sha(raw))) }
    }
    private fun bytes(i: FinanceInput) = FinanceWire.toProto(i).toByteArray()

    private fun input(rnd: Random): FinanceInput {
        val a = ActionKey("n", "d", "a${rnd.nextInt(90)}"); val who = { AccountId(if (rnd.nextInt(15) == 0) "ghost" else "acct-${rnd.nextInt(6)}") }
        return when (rnd.nextInt(3)) {
            0 -> FinanceInput.Capture(a, keys[rnd.nextInt(keys.size)], who(), who(), 1L + rnd.nextInt(3), 1L + rnd.nextInt(6))
            1 -> FinanceInput.Fund(a, if (rnd.nextInt(10) == 0) Authority.TRADING else Authority.OPERATOR, who(), if (rnd.nextBoolean()) usd else aapl, 1L + rnd.nextInt(8))
            else -> FinanceInput.Settle(a, Authority.OPERATOR, keys[rnd.nextInt(keys.size)], rnd.nextInt(3))
        }
    }

    private fun direct(inputs: List<FinanceInput>): Pair<CapacityLedger, List<Decision>> {
        val l = CapacityLedger(capacity); return l to inputs.mapNotNull { l.decide(it).decision }
    }

    private inner class Harness(preload: Map<String, ByteArray> = emptyMap()) {
        val context = MockProcessorContext<String, ByteArray>()
        val store: KeyValueStore<String, ByteArray> = CapacityTopology.storeBuilder(cfg, persistent = false).withLoggingDisabled().build()
        val processor: CapacityProcessor
        init {
            store.init(context.getStateStoreContext(), store); context.addStateStore(store)
            preload.forEach { (k, v) -> store.put(k, v) }
            processor = CapacityProcessor(cfg, decoder); processor.init(context)
        }
        private var offset = 0L
        fun send(raw: ByteArray, topic: String = "CAPTURE") { context.setRecordMetadata(topic, 0, offset++); processor.process(Record(ByteArray(0), raw, 0L)) }
        fun decisions(): List<Decision> = context.forwarded().map { FinanceWire.fromProto(FinanceDecisionV1.parseFrom(it.record().value())) }
        fun snapshot(): Map<String, ByteArray> = store.all().use { it.asSequence().associate { kv -> kv.key to kv.value } }
        fun restored() = CapacityStore.restore(capacity, StreamsLifecycleKv(store), binding)
    }

    @Test fun theProcessorForwardsExactlyTheDecisionsTheLedgerRecordsAndTheStoreRestoresToItsState() {
        val rnd = Random(1); val inputs = (0 until 150).map { input(rnd) }
        val h = Harness(); inputs.forEachIndexed { n, i -> h.send(bytes(i), if (n % 2 == 0) "CAPTURE" else "CONTROL") }
        val (ledger, expected) = direct(inputs)
        assertTrue(expected.size > 40, "the schedule records decisions")
        assertEquals(expected, h.decisions())
        assertEquals(ledger.snapshot(), h.restored()!!.snapshot(), "the store holds exactly the ledger's state")
        assertEquals(ledger.snapshot(), h.processor.currentLedger().snapshot())
        h.decisions().forEach { d -> assertTrue(FinanceLedger(FinanceConfig(setOf(AccountId("a"), AccountId("b")), usd, aapl, opening)).verifyLink(d.priorChain, d.sequence, d)) }
        assertEquals(inputs.size - expected.size, (h.processor.refusals).toInt(), "every input that was not recorded was counted as a refusal")
    }

    @Test fun aRestartRestoresFromTheStoreAndContinuesAsIfItNeverStopped() {
        for (seed in 1..25L) {
            val rnd = Random(seed); val inputs = (0 until 120).map { input(rnd) }; val cut = rnd.nextInt(1, inputs.size)
            val first = Harness(); inputs.take(cut).forEach { first.send(bytes(it)) }
            val second = Harness(preload = first.snapshot()); inputs.drop(cut).forEach { second.send(bytes(it)) }
            val (_, expected) = direct(inputs)
            assertEquals(expected, first.decisions() + second.decisions(), "seed $seed cut $cut")
        }
    }

    @Test fun refusedUnusableAndEmptyRecordsWriteAndForwardNothing() {
        val h = Harness()
        h.send(bytes(FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 10)))
        val before = h.snapshot(); val forwarded = h.decisions().size
        h.send(bytes(FinanceInput.Fund(ActionKey("n", "c", "f2"), Authority.TRADING, AccountId("acct-0"), usd, 10)))           // refused by the ledger
        h.send(byteArrayOf(1, 2, 3))                                                                                            // unusable
        h.send(byteArrayOf(0))                                                                                                  // nothing to do
        assertEquals(forwarded, h.decisions().size); assertEquals(before.keys, h.snapshot().keys)
        for ((k, v) in before) assertTrue(v.contentEquals(h.snapshot().getValue(k)), "unchanged $k")
        assertEquals(1L, h.processor.refusals); assertEquals(1L, h.processor.badInputs)
    }

    @Test fun anUnusableCaptureRecordStopsTheLaneWhenConfiguredAndWritesNothingWhileABadControlIsOnlyCounted() {
        val strict = CapacityProcessorConfig(1, { CapacityLaneSpec(binding, capacity) }, "CAPTURE", "CONTROL", "DECISIONS", "capacity-test", failOnBadCapture = true)
        val context = MockProcessorContext<String, ByteArray>(); val store = CapacityTopology.storeBuilder(strict, persistent = false).withLoggingDisabled().build()
        store.init(context.getStateStoreContext(), store); context.addStateStore(store)
        val processor = CapacityProcessor(strict, decoder); processor.init(context)
        fun send(topic: String, raw: ByteArray) { context.setRecordMetadata(topic, 0, 0); processor.process(Record(ByteArray(0), raw, 0L)) }
        send("CONTROL", bytes(FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 10)))
        val before = store.all().use { it.asSequence().associate { kv -> kv.key to kv.value.toList() } }
        send("CONTROL", byteArrayOf(1, 2, 3))                                       // an operator's malformed control: counted, nothing else
        assertEquals(1L, processor.badInputs)
        val failure = kotlin.test.assertFailsWith<IllegalStateException> { send("CAPTURE", byteArrayOf(1, 2, 3)) }
        assertTrue(failure.message!!.contains("the lane stops"), failure.message)
        assertEquals(before, store.all().use { it.asSequence().associate { kv -> kv.key to kv.value.toList() } }, "the failed record wrote nothing")
        assertEquals(1, context.forwarded().size, "and forwarded nothing")
    }

    @Test fun aRefusedCapturedInputStopsTheLaneUnlessItIsOnlyARedeliveryTheLedgerAlreadyHolds() {
        val strict = CapacityProcessorConfig(1, { CapacityLaneSpec(binding, capacity) }, "CAPTURE", "CONTROL", "DECISIONS", "capacity-test", failOnBadCapture = true)
        val context = MockProcessorContext<String, ByteArray>(); val store = CapacityTopology.storeBuilder(strict, persistent = false).withLoggingDisabled().build()
        store.init(context.getStateStoreContext(), store); context.addStateStore(store)
        val processor = CapacityProcessor(strict, decoder); processor.init(context)
        fun send(topic: String, i: FinanceInput) { context.setRecordMetadata(topic, 0, 0); processor.process(Record(ByteArray(0), bytes(i), 0L)) }
        send("CONTROL", FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 100))
        send("CONTROL", FinanceInput.Fund(ActionKey("n", "c", "f2"), Authority.OPERATOR, AccountId("acct-1"), aapl, 100))
        val capture = FinanceInput.Capture(ActionKey("n", "cap", "c1"), keys[0], AccountId("acct-0"), AccountId("acct-1"), 1, 2)
        send("CAPTURE", capture)
        send("CAPTURE", capture)                                                                    // a redelivery: PRIOR_RESULT, benign
        val settled = forwardedCount(context)
        val unknown = FinanceInput.Capture(ActionKey("n", "cap", "c2"), keys[1], AccountId("ghost"), AccountId("acct-1"), 1, 2)
        val failure = kotlin.test.assertFailsWith<IllegalStateException> { send("CAPTURE", unknown) }
        assertTrue(failure.message!!.contains("UNKNOWN_ACCOUNT") && failure.message!!.contains("the lane stops"), failure.message)
        assertEquals(settled, forwardedCount(context), "nothing was forwarded for the refused trade")
        send("CONTROL", FinanceInput.Fund(ActionKey("n", "c", "f3"), Authority.TRADING, AccountId("acct-0"), usd, 1))      // a refused CONTROL is only counted
        assertEquals(3L, processor.refusals, "the redelivery, the refused trade and the refused control were all counted")
    }
    private fun forwardedCount(context: MockProcessorContext<String, ByteArray>) = context.forwarded().size

    @Test fun aRecordWithSeveralInputsIsDecidedInOrderAndWrittenAsOneBatch() {
        val twin = CapacityInputDecoder { _, _, _, raw -> val a = FinanceWire.fromProto(FinanceInputV1.parseFrom(raw)) as FinanceInput.Fund
            listOf(CapacityItem.Run(a), CapacityItem.Run(a.copy(action = ActionKey("n", "c", a.action.actionId + "-b"), account = AccountId("acct-1")))) }
        val context = MockProcessorContext<String, ByteArray>(); val store = CapacityTopology.storeBuilder(cfg, persistent = false).withLoggingDisabled().build()
        store.init(context.getStateStoreContext(), store); context.addStateStore(store)
        val processor = CapacityProcessor(cfg, twin); processor.init(context)
        context.setRecordMetadata("CONTROL", 0, 0)
        processor.process(Record(ByteArray(0), bytes(FinanceInput.Fund(ActionKey("n", "c", "f1"), Authority.OPERATOR, AccountId("acct-0"), usd, 7)), 0L))
        val decisions = context.forwarded().map { FinanceWire.fromProto(FinanceDecisionV1.parseFrom(it.record().value())) }
        assertEquals(listOf(1L, 2L), decisions.map { it.sequence }); assertEquals(decisions[0].checksum, decisions[1].priorChain)
        val restored = assertNotNull(CapacityStore.restore(capacity, StreamsLifecycleKv(store), binding)); assertEquals(2L, restored.sequence)
        assertEquals(7L, restored.position(AccountId("acct-1"), usd))
    }

    @Test fun topologyEndToEndOnTheTestDriverProducesTheVerifiedSelfDescribingDecisionLog() {
        val props = Properties().apply { put(StreamsConfig.APPLICATION_ID_CONFIG, "capacity-e2e"); put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234") }
        val rnd = Random(8); val inputs = (0 until 100).map { input(rnd) }
        TopologyTestDriver(CapacityTopology.build(cfg, decoder, persistent = false), props).use { driver ->
            val capture = driver.createInputTopic("CAPTURE", ByteArraySerializer(), ByteArraySerializer()); val control = driver.createInputTopic("CONTROL", ByteArraySerializer(), ByteArraySerializer())
            inputs.forEachIndexed { n, i -> (if (n % 2 == 0) capture else control).pipeInput(ByteArray(0), bytes(i)) }
            val out = driver.createOutputTopic("DECISIONS", StringDeserializer(), ByteArrayDeserializer()).readKeyValuesToList()
            val records = out.map { FinanceWire.recordFromProto(FinanceDecisionV1.parseFrom(it.value)) }
            val (ledger, expected) = direct(inputs)
            assertEquals(expected, records.map { it.first }); assertEquals(expected.map { LaneByKey.key(0, it.sequence) }, out.map { it.key })
            records.forEach { (d, i) -> assertEquals(d.inputDigest, i.digest(), "the decision carries the input it answers") }
            assertEquals(StateCommitment.reference(ledger.snapshot()), records.last().first.stateDigest, "the last decision commits to the reference state")
        }
    }
}

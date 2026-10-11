package com.reef.platform.calcify.finance

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.Stores
import reef.contracts.calcify.finance.v1.FinanceDecisionV1
import reef.contracts.calcify.finance.v1.FinanceInputV1

/**
 * S1-B2 spike (option B): a processor over two input topics that assigns each consumed input the next position in its OWN
 * transactional history store, decides it with the O3a ledger, and forwards the decision. Proves, at the topology level:
 * arrival interleaving changes the result only through the recorded history, each interleaving is reproducible from its own
 * history, and restart rebuilds from the history rather than from re-consumption. Atomicity of (offset commit + history append)
 * is a Kafka EOS property that TopologyTestDriver cannot exercise; that part must be proven on the real broker in O3b.
 */
class FinanceOrderingSpikeTest {
    private val buyer = AccountId("buyer-account"); private val seller = AccountId("seller-account")
    private val usd = AssetId("USD"); private val aapl = AssetId("fixture-base-AAPL")
    private val cfg = FinanceConfig(setOf(buyer, seller), usd, aapl, AccountId("opening"))
    private val ledger = FinanceLedger(cfg)
    private val price = 100_000_000_000L

    private fun ek(id: String) = ExecutionKey("p3-run", "p3-session", "AAPL", id)
    private fun fund(id: String, account: AccountId, asset: AssetId, amount: Long) =
        FinanceInput.Fund(ActionKey("p3", "control", id), Authority.OPERATOR, account, asset, amount)
    private fun capture(id: String, qty: Long) = FinanceInput.Capture(ActionKey("p3", "capture", "cap-$id"), ek(id), buyer, seller, qty, price)
    private fun settle(id: String, attempt: Int) = FinanceInput.Settle(ActionKey("p3", "repair", "settle-$id-$attempt"), Authority.OPERATOR, ek(id), attempt)

    private class FinanceProcessor(val ledger: FinanceLedger) : Processor<String, ByteArray, String, ByteArray> {
        lateinit var ctx: ProcessorContext<String, ByteArray>
        lateinit var history: KeyValueStore<String, ByteArray>
        var state = ledger.genesis()
        private fun pos(n: Long) = "h%020d".format(n)

        override fun init(context: ProcessorContext<String, ByteArray>) {
            ctx = context
            history = context.getStateStore("history")
        }

        private var restored = false
        /** Restore = replay the recorded history, never re-consumption. Lazy here only because the test driver cannot preload a store before init(). */
        private fun restoreOnce() {
            if (restored) return
            restored = true
            var n = 1L
            while (true) {
                val bytes = history.get(pos(n)) ?: break
                state = ledger.decide(state, FinanceWire.fromProto(FinanceInputV1.parseFrom(bytes))).state
                n++
            }
        }

        override fun process(record: Record<String, ByteArray>) {
            restoreOnce()
            val input = FinanceWire.fromProto(FinanceInputV1.parseFrom(record.value()))
            val next = (history.approximateNumEntries()) + 1                  // position in OWN history, independent of arrival topic
            history.put(pos(next), record.value())
            val outcome = ledger.decide(state, input); state = outcome.state
            outcome.decision?.let { ctx.forward(record.withValue(FinanceWire.toProto(it).toByteArray())) }
        }
    }

    private fun driver(processor: (FinanceProcessor) -> Unit = {}): TopologyTestDriver {
        val topology = Topology()
        topology.addSource("cap", "capture").addSource("ctl", "control")
        topology.addProcessor("fin", { FinanceProcessor(ledger).also(processor) }, "cap", "ctl")
        topology.addStateStore(Stores.keyValueStoreBuilder(Stores.inMemoryKeyValueStore("history"), Serdes.String(), Serdes.ByteArray()), "fin")
        topology.addSink("out", "decisions", StringSerializer(), ByteArraySerializer(), "fin")
        val props = Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, "finance-spike"); put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234")
            put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde::class.java.name)
            put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.ByteArraySerde::class.java.name)
        }
        return TopologyTestDriver(topology, props)
    }

    private fun TopologyTestDriver.feed(topic: String, input: FinanceInput) {
        createInputTopic(topic, StringSerializer(), ByteArraySerializer()).pipeInput("k", FinanceWire.toProto(input).toByteArray())
    }
    private fun TopologyTestDriver.decisions(): List<Decision> =
        createOutputTopic("decisions", org.apache.kafka.common.serialization.StringDeserializer(), ByteArrayDeserializer())
            .readValuesToList().map { FinanceWire.fromProto(FinanceDecisionV1.parseFrom(it)) }
    private fun TopologyTestDriver.historyInputs(): List<FinanceInput> {
        val store = getKeyValueStore<String, ByteArray>("history")
        return store.all().asSequence().map { FinanceWire.fromProto(FinanceInputV1.parseFrom(it.value)) }.toList()
    }

    @Test fun twoInterleavingsAreBothValidAndEachReproducibleFromItsOwnHistory() {
        // Same logical inputs, different arrival interleaving across the two topics.
        val a = driver().use { d ->
            d.feed("control", fund("open-seller", seller, aapl, 3)); d.feed("control", fund("open-buyer", buyer, usd, 3 * price))
            d.feed("capture", capture("e1", 3)); d.feed("control", settle("e1", 1))
            Triple(d.decisions(), d.historyInputs(), d.getKeyValueStore<String, ByteArray>("history").approximateNumEntries())
        }
        val b = driver().use { d ->
            d.feed("capture", capture("e1", 3)); d.feed("control", settle("e1", 1))   // arrives before funding: refused attempt 1
            d.feed("control", fund("open-seller", seller, aapl, 3)); d.feed("control", fund("open-buyer", buyer, usd, 3 * price))
            d.feed("control", settle("e1", 2))
            Triple(d.decisions(), d.historyInputs(), d.getKeyValueStore<String, ByteArray>("history").approximateNumEntries())
        }
        assertEquals(listOf(Disposition.FUNDED, Disposition.FUNDED, Disposition.CAPTURED, Disposition.SETTLED), a.first.map { it.disposition })
        assertEquals(listOf(Disposition.CAPTURED, Disposition.INSUFFICIENT_CASH, Disposition.FUNDED, Disposition.FUNDED, Disposition.SETTLED), b.first.map { it.disposition })
        for ((decisions, history, _) in listOf(a, b)) {
            assertTrue(ledger.verifyChain(decisions), "decision chain verifies")
            // Replaying the recorded history through a fresh ledger reproduces every decision byte for byte.
            var s = ledger.genesis(); val replayed = mutableListOf<Decision>()
            for (input in history) ledger.decide(s, input).also { s = it.state; it.decision?.let(replayed::add) }
            assertEquals(decisions, replayed)
        }
        assertTrue(a.first.map { it.checksum } != b.first.map { it.checksum }, "different recorded orders are different histories")
    }

    @Test fun restartRebuildsFromHistoryAndContinuesIdentically() {
        val prefix = listOf("control" to fund("open-seller", seller, aapl, 3), "control" to fund("open-buyer", buyer, usd, 3 * price), "capture" to capture("e1", 3))
        val straight = driver().use { d -> prefix.forEach { d.feed(it.first, it.second) }; d.feed("control", settle("e1", 1)); d.decisions() }
        val restarted = driver().use { first ->
            prefix.forEach { first.feed(it.first, it.second) }
            val before = first.decisions()
            val saved = first.getKeyValueStore<String, ByteArray>("history").all().asSequence().map { it.key to it.value }.toList()
            val second = driver { /* fresh processor instance */ }
            // Preload the history store of the new driver (what a restored changelog gives), then restart processing.
            second.getKeyValueStore<String, ByteArray>("history").let { store -> saved.forEach { store.put(it.first, it.second) } }
            second.use { d ->
                d.feed("control", settle("e1", 1))
                before + d.decisions()
            }
        }
        assertEquals(straight.map { it.checksum }, restarted.map { it.checksum })
    }

    @Test fun redeliveredInputIsIdempotentAndAddsNoDecision() {
        driver().use { d ->
            val f = fund("open-seller", seller, aapl, 3)
            d.feed("control", f); d.feed("control", f)      // redelivery after a crash before the offset commit
            val out = d.decisions()
            assertEquals(1, out.size)
            assertEquals(listOf(Disposition.FUNDED), out.map { it.disposition })
            assertEquals(2L, d.getKeyValueStore<String, ByteArray>("history").approximateNumEntries(), "the redelivery is positioned in history too")
        }
    }
}

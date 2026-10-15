package com.reef.platform.calcify.finance

import java.util.Random
import kotlin.test.Test
import org.apache.kafka.streams.processor.api.MockProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore

/**
 * What one booked trade writes to the lane's store (and therefore to its changelog), without a broker: the real [CapacityProcessor] with the production decoder
 * ([CapacityResolvedContextDecoder], so one consumed record is a capture plus its instant first settlement, as on the real path) over a counting store. Per key class it
 * reports puts, key bytes and value bytes per trade, and the processing thread's CPU per decision. A changelog record is the key and value as written plus a small
 * per-record batch overhead (counted separately by the real-stack run). Research only (-PresearchTests); env: CAP_TRADES, CAP_ACCOUNTS, CAP_INSTRUMENTS.
 */
@org.junit.jupiter.api.Tag("research")
class CapacityStoreWritesTest {
    private fun env(name: String, default: Long) = System.getenv(name)?.toLong() ?: default

    private class CountingStore(val inner: KeyValueStore<String, ByteArray>) : KeyValueStore<String, ByteArray> by inner {
        val puts = HashMap<String, LongArray>()   // class -> [puts, key bytes, value bytes]
        var total = 0L
        override fun put(key: String, value: ByteArray) {
            val cls = when { key == "head" -> "HEAD"; key == "meta" -> "META"; key.startsWith("e/") -> "e/" + key[2]; key.startsWith("x/") -> "x (X+O+A1)"; else -> "other" }
            puts.getOrPut(cls) { LongArray(3) }.also { it[0]++; it[1] += key.toByteArray().size.toLong(); it[2] += value.size.toLong() }
            total++
            inner.put(key, value)
        }
        fun reset() { puts.clear(); total = 0 }
    }

    @Test fun writesPerBookedTrade() {
        val trades = env("CAP_TRADES", 50_000).toInt(); val accountCount = env("CAP_ACCOUNTS", 200).toInt(); val instrumentCount = env("CAP_INSTRUMENTS", 64).toInt()
        val instruments = (0 until instrumentCount).map { "INS$it" }
        val plan = CapacityPlan("rate-plan", "p3-run", "p3-session", AssetId("USD"), AccountId("opening"), listOf(instruments.toSet()), trades + 100_000, trades + 100_000, 2 * trades + 100_000, accountCount * (1 + instrumentCount) + 10, sequenceBound = 1_000_000_000L)
        val accounts = (0 until accountCount).map { AccountId("u$it") }
        val cfg = CapacityProcessorConfig(plan, "CAPTURE", "CONTROL", "DECISIONS", "capacity-rate", checkpointEvery = env("CAP_CHECKPOINT_EVERY", 1).toInt())
        val context = MockProcessorContext<String, ByteArray>()
        val inner = CapacityTopology.storeBuilder(cfg, persistent = false).withLoggingDisabled().build()
        inner.init(context.getStateStoreContext(), inner)
        val store = CountingStore(inner); context.addStateStore(store)
        val processor = CapacityProcessor(cfg, CapacityResolvedContextDecoder(plan)); processor.init(context)
        var offset = 0L
        fun send(topic: String, raw: ByteArray) { context.setRecordMetadata(topic, 0, offset++); processor.process(Record(ByteArray(0), raw, 0L)) }
        for (a in accounts) {
            send("CONTROL", FinanceWire.toProto(FinanceInput.Fund(ActionKey("operator", "fund", "seed-${a.value}/cash"), Authority.OPERATOR, a, plan.cashAsset, 1_000_000_000_000L)).toByteArray())
            for (ins in instruments) send("CONTROL", FinanceWire.toProto(FinanceInput.Fund(ActionKey("operator", "fund", "seed-${a.value}/$ins"), Authority.OPERATOR, a, AssetId(ins), 1_000_000L)).toByteArray())
        }
        context.resetForwards()
        val rnd = Random(3)
        fun trade(i: Int): ByteArray {
            val ins = instruments[rnd.nextInt(instrumentCount)]; val b = accounts[rnd.nextInt(accountCount)]; var s = accounts[rnd.nextInt(accountCount)]; if (s == b) s = accounts[(accounts.indexOf(b) + 1) % accountCount]
            val c = reef.contracts.calcify.v1.MatchContextResolvedV1.newBuilder().setPolicyVersion(1).setRunId("p3-run")
            c.tradeBuilder.setRunId("p3-run").factBuilder.setExecutionId("exec-$i").setInstrumentId(ins).setBuyOrderId("b-$i").setSellOrderId("s-$i").quantityBuilder.setUnits((1 + rnd.nextInt(4)).toString())
            c.tradeBuilder.factBuilder.priceBuilder.setNanos((1000 + rnd.nextInt(50)).toString()).setCurrency("USD")
            c.buyAcceptedOrderBuilder.factBuilder.setOrderId("b-$i").setVenueSessionId("p3-session").setInstrumentId(ins).setAccountId(b.value).setParticipantId("pb")
            c.sellAcceptedOrderBuilder.factBuilder.setOrderId("s-$i").setVenueSessionId("p3-session").setInstrumentId(ins).setAccountId(s.value).setParticipantId("ps")
            return c.build().toByteArray()
        }
        val records = (0 until 20_000 + trades).map { trade(it) }
        for (i in 0 until 20_000) send("CAPTURE", records[i])
        context.resetForwards(); store.reset()
        val mx = java.lang.management.ManagementFactory.getThreadMXBean()
        val cpu0 = mx.currentThreadCpuTime; val t0 = System.nanoTime()
        for (i in 0 until trades) { send("CAPTURE", records[20_000 + i]); if (i % 5_000 == 0) context.resetForwards() }
        val cpu = mx.currentThreadCpuTime - cpu0; val wall = System.nanoTime() - t0
        val decisions = 2.0 * trades
        println("STORE-WRITES trades=$trades accounts=$accountCount instruments=$instrumentCount checkpointEvery=${cfg.checkpointEvery}: puts per trade ${"%.2f".format(store.total / trades.toDouble())}; cpu ${"%.0f".format(cpu / decisions)} ns/decision wall ${"%.0f".format(wall / decisions)} ns/decision (in-memory store)")
        var sumK = 0.0; var sumV = 0.0
        for ((cls, v) in store.puts.entries.sortedBy { it.key }) {
            println("STORE-WRITES   %-6s puts/trade %5.2f  key %6.1f B  value %6.1f B  (per put: key %5.1f, value %5.1f)".format(cls, v[0] / trades.toDouble(), v[1] / trades.toDouble(), v[2] / trades.toDouble(), v[1].toDouble() / v[0], v[2].toDouble() / v[0]))
            sumK += v[1] / trades.toDouble(); sumV += v[2] / trades.toDouble()
        }
        println("STORE-WRITES   total  key+value bytes per trade ${"%.0f".format(sumK + sumV)} (key ${"%.0f".format(sumK)}, value ${"%.0f".format(sumV)}); decisions-topic bytes per trade ${"%.0f".format(context.forwarded().take(2000).sumOf { it.record().value().size + it.record().key().length }.toDouble() / minOf(2000, context.forwarded().size) * 2)}")
    }
}

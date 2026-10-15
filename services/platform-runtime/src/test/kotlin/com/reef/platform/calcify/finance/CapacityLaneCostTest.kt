package com.reef.platform.calcify.finance

import java.util.Random
import kotlin.test.Test
import org.apache.kafka.streams.processor.api.MockProcessorContext
import org.apache.kafka.streams.processor.api.Record

/**
 * Where one capacity lane spends its CPU, without a broker: the real [CapacityProcessor] on a mock context and an in-memory
 * store, fed captured trades (capture plus instant first settlement, as the decoder emits them). It excludes RocksDB, the
 * changelog and the Kafka client, so it bounds the processor's own cost from below.
 *
 * A sampler thread records the processing thread's stack every millisecond and reports inclusive time by function, so the
 * answer to "what is the lane's cost" is a measurement. Research only (-PresearchTests); env: CAP_TRADES, CAP_ACCOUNTS, CAP_INSTRUMENTS.
 */
@org.junit.jupiter.api.Tag("research")
class CapacityLaneCostTest {
    private fun env(name: String, default: Long) = System.getenv(name)?.toLong() ?: default

    private val usd = AssetId("USD"); private val opening = AccountId("opening")

    private class Run(val rate: Double, val samples: Map<String, Int>, val total: Int)

    @Test fun laneProcessorCostBreakdown() {
        val trades = env("CAP_TRADES", 200_000).toInt(); val accountCount = env("CAP_ACCOUNTS", 2_000).toInt(); val instrumentCount = env("CAP_INSTRUMENTS", 8).toInt()
        val accounts = (0 until accountCount).map { AccountId("acct-$it") }; val securities = (0 until instrumentCount).map { AssetId("I$it") }
        val capacity = CapacityConfig(null, usd, securities.toSet(), opening, 100_000_000, 100_000_000, 100_000_000, 100_000_000)
        val cfg = CapacityProcessorConfig("b".repeat(64), capacity, "CAPTURE", "CONTROL", "DECISIONS", "lane-cost")
        val decoder = CapacityInputDecoder { _, _, _, raw -> listOf(CapacityItem.Run(FinanceWire.fromProto(reef.contracts.calcify.finance.v1.FinanceInputV1.parseFrom(raw)))) }
        val context = MockProcessorContext<String, ByteArray>()
        val store = CapacityTopology.storeBuilder(cfg, persistent = false).withLoggingDisabled().build()
        store.init(context.getStateStoreContext(), store); context.addStateStore(store)
        val processor = CapacityProcessor(cfg, decoder); processor.init(context)
        var offset = 0L
        fun send(topic: String, input: FinanceInput) { context.setRecordMetadata(topic, 0, offset++); processor.process(Record(ByteArray(0), FinanceWire.toProto(input).toByteArray(), 0L)) }

        // Fund every account with cash and every security once (control records), as an operator would.
        var action = 0L
        fun key() = ActionKey("calcify-finance", "fund", "a${action++}")
        for (a in accounts) send("CONTROL", FinanceInput.Fund(key(), Authority.OPERATOR, a, usd, 1_000_000_000_000L))
        for (a in accounts) for (s in securities) send("CONTROL", FinanceInput.Fund(key(), Authority.OPERATOR, a, s, 1_000_000L))
        context.resetForwards()

        val rnd = Random(7)
        val stateBefore = offset
        fun trade(i: Int) {
            val s = securities[i % instrumentCount]
            val execution = ExecutionKey("run", "session", s.value, "e$i")
            val buyer = accounts[rnd.nextInt(accountCount)]; var seller = accounts[rnd.nextInt(accountCount)]; if (seller == buyer) seller = accounts[(rnd.nextInt(accountCount - 1) + 1 + accounts.indexOf(buyer)) % accountCount]
            send("CAPTURE", FinanceInput.Capture(ActionKey("calcify-finance", "capture", "e$i"), execution, buyer, seller, 1L + rnd.nextInt(5), 100L + rnd.nextInt(20)))
            send("CAPTURE", FinanceInput.Settle(ActionKey("calcify-finance", "settle", "e$i/1"), Authority.OPERATOR, execution, 1))
        }
        // warm up, then measure
        repeat(20_000) { trade(it) }; context.resetForwards()

        val thread = Thread.currentThread(); val samples = HashMap<String, Int>(); var total = 0; var running = true
        val sampler = Thread {
            while (running) {
                val stack = thread.stackTrace; total++
                val seen = HashSet<String>()
                for (frame in stack) {
                    val name = frame.className.substringAfterLast('.') + "." + frame.methodName
                    if (seen.add(name)) samples.merge(name, 1, Int::plus)
                }
                Thread.sleep(1)
            }
        }.also { it.isDaemon = true; it.start() }
        val t0 = System.nanoTime()
        val base = 20_000
        for (i in 0 until trades) { trade(base + i); if (i % 5_000 == 0) context.resetForwards() }
        val seconds = (System.nanoTime() - t0) / 1e9; running = false; sampler.join()
        println("LANE-COST $trades trades ($accountCount accounts, $instrumentCount instruments) in ${"%.2f".format(seconds)} s = ${"%.0f".format(trades / seconds)} trades/s, ${"%.0f".format(2 * trades / seconds)} decisions/s; ${total} samples; ledger entities started at $stateBefore records")
        val top = samples.entries.sortedByDescending { it.value }.filter { it.key.contains("Capacity") || it.key.contains("State") || it.key.contains("Canon") || it.key.contains("Finance") || it.key.contains("Message") || it.key.contains("Digest") || it.key.contains("Proto") || it.key.contains("HashMap") || it.key.contains("Lifecycle") || it.key.contains("update") || it.key.contains("digest") }.take(40)
        for ((name, n) in top) println("LANE-COST %5.1f%%  %s".format(100.0 * n / total, name))
    }
}

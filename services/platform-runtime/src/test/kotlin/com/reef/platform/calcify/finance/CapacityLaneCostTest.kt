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
 * answer to "what is the lane's cost" is a measurement. Research only (-PresearchTests); env: CAP_TRADES, CAP_ACCOUNTS, CAP_INSTRUMENTS, CAP_DECISION_MODE (`two-step` by
 * default, or `capture-settle-v1`: one CaptureSettle input and one decision per trade). Compare the modes per trade (thread CPU per trade, branch hashes per trade), not per decision.
 */
@org.junit.jupiter.api.Tag("research")
class CapacityLaneCostTest {
    private fun env(name: String, default: Long) = System.getenv(name)?.toLong() ?: default

    private val usd = AssetId("USD"); private val opening = AccountId("opening")

    private class Run(val rate: Double, val samples: Map<String, Int>, val total: Int)

    @Test fun laneProcessorCostBreakdown() {
        val trades = env("CAP_TRADES", 200_000).toInt(); val accountCount = env("CAP_ACCOUNTS", 2_000).toInt(); val instrumentCount = env("CAP_INSTRUMENTS", 8).toInt()
        val accounts = (0 until accountCount).map { AccountId("acct-$it") }; val securities = (0 until instrumentCount).map { AssetId("I$it") }
        val mode = DecisionMode.ofWire(System.getenv("CAP_DECISION_MODE") ?: DecisionMode.TWO_STEP.wire); val perTrade = if (mode == DecisionMode.CAPTURE_SETTLE) 1.0 else 2.0
        val capacity = CapacityConfig(null, usd, securities.toSet(), opening, 100_000_000, 100_000_000, 100_000_000, 100_000_000, mode)
        val evict = System.getenv("CAP_EVICT") == "true"
        val cfg = CapacityProcessorConfig("b".repeat(64), capacity, "CAPTURE", "CONTROL", "DECISIONS", "lane-cost", evictSettled = evict)
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
            val capture = FinanceInput.Capture(ActionKey("calcify-finance", "capture", "e$i"), execution, buyer, seller, 1L + rnd.nextInt(5), 100L + rnd.nextInt(20))
            if (mode == DecisionMode.CAPTURE_SETTLE) send("CAPTURE", CapacityCaptureMapping.captureSettle(capture))
            else { send("CAPTURE", capture); send("CAPTURE", CapacityCaptureMapping.firstSettle(execution)) }
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
        fun branchHashes(): Long = processor.currentLedger().branchHashes
        val hashesBefore = branchHashes()
        // Wall-clock rate is noisy when other processes share the machine; the processing thread's own CPU time and allocation per decision are not.
        val threadBean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val cpu0 = threadBean.currentThreadCpuTime; val alloc0 = threadBean.getThreadAllocatedBytes(thread.id)
        val t0 = System.nanoTime()
        val base = 20_000
        for (i in 0 until trades) { trade(base + i); if (i % 5_000 == 0) context.resetForwards() }
        val seconds = (System.nanoTime() - t0) / 1e9; running = false; sampler.join()
        val cpu1 = threadBean.currentThreadCpuTime; val alloc1 = threadBean.getThreadAllocatedBytes(thread.id)
        println("LANE-COST mode=${mode.wire} thread CPU ${"%.2f".format((cpu1 - cpu0) / 1000.0 / (perTrade * trades))} us/decision = ${"%.2f".format((cpu1 - cpu0) / 1000.0 / trades)} us/trade, allocated ${"%.0f".format((alloc1 - alloc0) / (perTrade * trades))} B/decision = ${"%.0f".format((alloc1 - alloc0).toDouble() / trades)} B/trade (sampler thread excluded from the CPU figure)")
        println("LANE-COST $trades trades ($accountCount accounts, $instrumentCount instruments) in ${"%.2f".format(seconds)} s = ${"%.0f".format(trades / seconds)} trades/s, ${"%.0f".format(perTrade * trades / seconds)} decisions/s; ${total} samples; ledger entities started at $stateBefore records")
        println("LANE-COST trie branch hashes per decision: ${"%.1f".format((branchHashes() - hashesBefore) / (perTrade * trades))}, per trade: ${"%.1f".format((branchHashes() - hashesBefore).toDouble() / trades)}")
        val top = samples.entries.sortedByDescending { it.value }.filter { it.key.contains("Capacity") || it.key.contains("State") || it.key.contains("Canon") || it.key.contains("Finance") || it.key.contains("Message") || it.key.contains("Digest") || it.key.contains("Proto") || it.key.contains("HashMap") || it.key.contains("Lifecycle") || it.key.contains("update") || it.key.contains("digest") }.take(40)
        for ((name, n) in top) println("LANE-COST %5.1f%%  %s".format(100.0 * n / total, name))

        // CAP_RESTORE=true: what a restart costs at this state size. The processor's ledger stays live, so this is the peak with both copies resident, which is the case that matters when a lane restarts in a JVM that already holds other state.
        if (System.getenv("CAP_RESTORE") == "true") {
            val memory = java.lang.management.ManagementFactory.getMemoryMXBean()
            fun usedGb(): Double { repeat(3) { System.gc() }; return memory.heapMemoryUsage.used / 1e9 }
            val liveBefore = usedGb()
            val entries = store.approximateNumEntries()
            java.lang.management.ManagementFactory.getMemoryPoolMXBeans().forEach { it.resetPeakUsage() }
            val r0 = System.nanoTime()
            val restored = CapacityStore.restore(capacity, com.reef.platform.calcify.StreamsLifecycleKv(store), "b".repeat(64), evict)
            val seconds2 = (System.nanoTime() - r0) / 1e9
            val peak = java.lang.management.ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }.sumOf { it.peakUsage.used } / 1e9
            check(restored != null) { "restore returned nothing" }
            println("RESTORE-COST evict=$evict mode=${mode.wire} trades=$trades store entries=$entries restore ${"%.2f".format(seconds2)} s, live heap before ${"%.2f".format(liveBefore)} GB, peak heap during restore ${"%.2f".format(peak)} GB, restored entities=${restored.entityCount}")
        }
    }
}

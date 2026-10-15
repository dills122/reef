package com.reef.platform.calcify.finance

import java.lang.management.ManagementFactory
import javax.management.ObjectName

/**
 * Heap footprint harness for the capacity ledger (docs/work/CALCIFY_HEAP_FOOTPRINT_SPIKE.md). A `main`, not a JUnit test, so it runs in a clean JVM with the flags under study
 * (`java -Xmx.. -XX:+UseParallelGC -cp <test runtime classpath> com.reef.platform.calcify.finance.CapacityHeapFootprintKt`).
 *
 * It drives a ledger directly (no broker, no Streams store: the production store is RocksDB, off heap, and an in-memory store would add the entities twice) with trades built the way
 * [CapacityResolvedContextDecoder] builds them: the execution id, the instrument id and the two account ids are FRESH String objects per trade (protobuf decoding makes a new String per
 * field; none is interned), the run and session strings are the plan's shared ones, the action id is the execution id String itself. At each checkpoint it forces full collections, reads
 * the live heap, subtracts the live heap measured after funding (so a figure is bytes per trade), and prints the top classes of the class histogram taken after a full GC.
 *
 * Env: HF_MODE (two-step | capture-settle-v1), HF_CHECKPOINTS (comma list of trade counts), HF_ACCOUNTS (200), HF_INSTRUMENTS (64), HF_IMPL (current), HF_TOP (histogram rows, 14),
 * HF_CPU (1: also print processing-thread CPU per trade of each interval, decide plus input construction).
 */
internal interface HeapLedger {
    fun decide(input: FinanceInput): CapacityOutcome
    val entityCount: Int
    val triePayloadBytes: Long
}

internal object HeapImpls {
    fun make(impl: String, config: CapacityConfig): HeapLedger = when (impl) {
        "current" -> CapacityLedger(config).let { l -> object : HeapLedger { override fun decide(input: FinanceInput) = l.decide(input); override val entityCount get() = l.entityCount; override val triePayloadBytes get() = CapacityHeapFootprint.triePayloadBytes(l) } }
        else -> throw IllegalArgumentException("unknown HF_IMPL $impl")
    }
}

internal class TradeFeed(private val mode: DecisionMode, private val accounts: Int, private val instruments: Int, seed: Long = 7) {
    private val rnd = java.util.SplittableRandom(seed)
    private val run = "run-1"; private val session = "session-1"          // the plan's strings: shared by every key
    var made = 0L; private set

    /** The inputs of trade number [i]: one CaptureSettle, or a Capture then the first Settle. Fresh Strings, as a protobuf decode produces. */
    fun inputsOf(i: Long, sink: (FinanceInput) -> Unit) {
        val executionId = "exec-" + i                                                       // fresh String
        val instrument = "INS" + (rnd.nextInt(instruments))                                   // fresh String
        val b = rnd.nextInt(accounts); var s = rnd.nextInt(accounts - 1); if (s >= b) s++
        val buyer = AccountId("u" + b); val seller = AccountId("u" + s)                       // fresh Strings
        val quantity = 1L + rnd.nextInt(4); val price = 1000L + rnd.nextInt(50)
        val execution = ExecutionKey(run, session, instrument, executionId)
        if (mode == DecisionMode.CAPTURE_SETTLE) sink(FinanceInput.CaptureSettle(ActionKey("calcify-finance", "capture-settle", executionId), execution, buyer, seller, quantity, price))
        else { sink(FinanceInput.Capture(ActionKey("calcify-finance", "capture", executionId), execution, buyer, seller, quantity, price)); sink(CapacityCaptureMapping.firstSettle(execution)) }
        made++
    }
}

internal object CapacityHeapFootprint {
    private fun env(name: String, default: String) = System.getenv(name) ?: default

    fun triePayloadBytes(ledger: CapacityLedger): Long {
        val trie = CapacityLedger::class.java.getDeclaredField("trie").apply { isAccessible = true }.get(ledger)
        return listOf("keyChunks", "metaChunks", "digChunks").sumOf { name ->
            val chunks = FlatStateTrie::class.java.getDeclaredField(name).apply { isAccessible = true }.get(trie) as Array<*>
            chunks.sumOf { when (it) { is ByteArray -> it.size.toLong(); is IntArray -> it.size.toLong() * 4; else -> 0L } }
        }
    }

    fun usedAfterGc(): Long {
        val mem = ManagementFactory.getMemoryMXBean()
        var last = Long.MAX_VALUE
        repeat(6) { System.gc(); Thread.sleep(150); val u = mem.heapMemoryUsage.used; if (u >= last && it >= 2) return u; last = minOf(last, u) }
        return mem.heapMemoryUsage.used
    }

    /** `jcmd GC.class_histogram` in process: runs a full collection, returns (class name, instances, bytes) rows. */
    fun histogram(): List<Triple<String, Long, Long>> {
        val text = ManagementFactory.getPlatformMBeanServer().invoke(ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram", arrayOf<Any>(arrayOf<String>()), arrayOf("[Ljava.lang.String;")) as String
        return text.lines().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+")); if (p.size >= 4 && p[0].endsWith(":")) Triple(p[3], p[1].toLong(), p[2].toLong()) else null
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val mode = DecisionMode.ofWire(env("HF_MODE", "capture-settle-v1")); val impl = env("HF_IMPL", "current")
        val checkpoints = env("HF_CHECKPOINTS", "250000,500000,1000000,2000000").split(",").map { it.trim().toLong() }
        val accounts = env("HF_ACCOUNTS", "200").toInt(); val instruments = env("HF_INSTRUMENTS", "64").toInt(); val top = env("HF_TOP", "14").toInt(); val cpu = env("HF_CPU", "1") == "1"
        val usd = AssetId("USD"); val opening = AccountId("opening")
        val securities = (0 until instruments).map { AssetId("INS$it") }
        val config = CapacityConfig(null, usd, securities.toSet(), opening, 1_000_000_000, 1_000_000_000, 1_000_000_000, 1_000_000_000, mode)
        val ledger = HeapImpls.make(impl, config)
        var n = 0L
        fun fund(account: String, asset: AssetId, amount: Long) { val r = ledger.decide(FinanceInput.Fund(ActionKey("calcify-finance", "fund", "a${n++}"), Authority.OPERATOR, AccountId(account), asset, amount)); check(r.decision != null) { r.disposition } }
        for (a in 0 until accounts) fund("u$a", usd, 1_000_000_000_000_000L)
        for (a in 0 until accounts) for (s in securities) fund("u$a", s, 1_000_000_000L)
        val baseline = usedAfterGc()
        val trieBaseline = ledger.triePayloadBytes
        println("HEAP-CONFIG impl=$impl mode=${mode.wire} accounts=$accounts instruments=$instruments maxHeap=${Runtime.getRuntime().maxMemory() shr 20} MiB gc=${ManagementFactory.getGarbageCollectorMXBeans().joinToString("+") { it.name }} jvm=${System.getProperty("java.vm.version")} compressedOops=${ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean::class.java).getVMOption("UseCompressedOops").value}")
        println("HEAP-BASELINE after funding: used=${baseline shr 10} KiB entities=${ledger.entityCount}")
        val feed = TradeFeed(mode, accounts, instruments)
        val threadBean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        var done = 0L
        for (target in checkpoints) {
            val cpu0 = threadBean.currentThreadCpuTime; val t0 = System.nanoTime(); val trades = target - done
            while (done < target) { feed.inputsOf(done) { input -> val r = ledger.decide(input); check(r.decision != null) { "${r.disposition} at trade $done" } }; done++ }
            val cpuNs = threadBean.currentThreadCpuTime - cpu0; val wall = (System.nanoTime() - t0) / 1e9
            val used = usedAfterGc(); val live = used - baseline
            println("HEAP-TRIE trades=$target payloadBytes=${ledger.triePayloadBytes - trieBaseline} payloadPerTrade=${(ledger.triePayloadBytes - trieBaseline).toDouble() / target}")
            println("HEAP-POINT impl=$impl mode=${mode.wire} trades=$target entities=${ledger.entityCount} live=${live shr 20} MiB perTrade=${"%.1f".format(live.toDouble() / target)} B" +
                (if (cpu) " | interval thread CPU ${"%.2f".format(cpuNs / 1000.0 / trades)} us/trade (decide + input construction), wall ${"%.1f".format(wall)} s" else ""))
            if (top > 0) {
                val rows = histogram()
                for ((name, count, bytes) in rows.take(top)) println("HEAP-CLASS trades=$target %-48s instances/trade=%.3f bytes/trade=%.1f".format(name, count.toDouble() / target, bytes.toDouble() / target))
            }
            java.lang.ref.Reference.reachabilityFence(ledger)
        }
    }
}

internal fun main() = CapacityHeapFootprint.main(emptyArray())

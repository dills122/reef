package com.reef.platform.calcify.finance

import com.reef.platform.calcify.LifecycleKv
import com.reef.platform.calcify.StreamsLifecycleKv
import java.io.File
import java.util.Properties
import java.util.Random
import kotlin.test.Test
import org.apache.kafka.streams.processor.TaskId
import org.apache.kafka.streams.processor.api.MockProcessorContext
import org.apache.kafka.streams.processor.api.Record

/**
 * What a restart costs against a real RocksDB store (the Streams persistent store), and what a trie snapshot costs to write and read, at several state sizes.
 * The ledger that built the state is dropped before anything is measured, so the heap numbers are a fresh JVM's restart, not a second copy beside a live ledger.
 *
 * Three restores of the same store: the materialized scan (every entry in one list first), the streamed scan (one entry at a time), and the trie
 * snapshot's own write and read. Research only (-PresearchTests -PtestHeap=NNg); env: CAP_TRADES (default 650000), CAP_DECISION_MODE (default capture-settle-v1).
 */
@org.junit.jupiter.api.Tag("research")
class CapacityRestoreCostTest {
    private fun env(name: String, default: String) = System.getenv(name) ?: default

    private val usd = AssetId("USD"); private val opening = AccountId("opening")
    private val memory = java.lang.management.ManagementFactory.getMemoryMXBean()
    private fun usedGb(): Double { repeat(3) { System.gc() }; return memory.heapMemoryUsage.used / 1e9 }
    private fun peakGb() = java.lang.management.ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }.sumOf { it.peakUsage.used } / 1e9
    private fun resetPeaks() = java.lang.management.ManagementFactory.getMemoryPoolMXBeans().forEach { it.resetPeakUsage() }

    @Test fun restoreAndSnapshotCost() {
        val trades = env("CAP_TRADES", "650000").toInt(); val accountCount = 2_000; val instrumentCount = 8
        val mode = DecisionMode.ofWire(env("CAP_DECISION_MODE", DecisionMode.CAPTURE_SETTLE.wire))
        val accounts = (0 until accountCount).map { AccountId("acct-$it") }; val securities = (0 until instrumentCount).map { AssetId("I$it") }
        val capacity = CapacityConfig(null, usd, securities.toSet(), opening, 100_000_000, 100_000_000, 100_000_000, 100_000_000, mode)
        val cfg = CapacityProcessorConfig("b".repeat(64), capacity, "CAPTURE", "CONTROL", "DECISIONS", "restore-cost")
        val dir = java.nio.file.Files.createTempDirectory("capacity-restore-cost").toFile()
        try {
            val props = Properties().also { it["application.id"] = "restore-cost"; it["bootstrap.servers"] = "localhost:1"; it["state.dir"] = dir.path }
            val context = MockProcessorContext<String, ByteArray>(props, TaskId(0, 0), File(dir, "state").also { it.mkdirs() })
            val store = CapacityTopology.storeBuilder(cfg, persistent = true).withLoggingDisabled().build()
            store.init(context.getStateStoreContext(), store); context.addStateStore(store)

            val build0 = System.nanoTime()
            val head = build(cfg, context, accounts, securities, mode, trades)
            println("RESTORE-RUN built $trades trades in ${"%.1f".format((System.nanoTime() - build0) / 1e9)} s through the real store; sequence ${head.first}, commitment ${head.second.take(12)}")

            val kv = StreamsLifecycleKv(store)
            val materialized = object : LifecycleKv { override fun get(key: String) = kv.get(key); override fun scan(prefix: String) = kv.scan(prefix) }   // the default scanStreaming materializes
            val liveBase = usedGb()
            val entries = store.approximateNumEntries()
            for (label in listOf("materialized", "streamed", "materialized", "streamed")) {
                val source: LifecycleKv = if (label == "materialized") materialized else kv
                usedGb(); resetPeaks()
                val t0 = System.nanoTime()
                val restored = CapacityStore.restore(capacity, source, "b".repeat(64)) ?: error("empty store")
                val seconds = (System.nanoTime() - t0) / 1e9
                val peak = peakGb()
                check(restored.sequence == head.first && restored.commitment() == head.second) { "restored state is not the head the run produced" }
                println("RESTORE-RUN mode=${mode.wire} trades=$trades entries=$entries $label scan: ${"%.2f".format(seconds)} s, peak heap ${"%.2f".format(peak)} GB, live before ${"%.2f".format(liveBase)} GB, live after ${"%.2f".format(usedGb())} GB")
                if (label == "streamed") snapshotCost(restored, dir)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun snapshotCost(ledger: CapacityLedger, dir: File) {
        val file = File(dir, "trie.snapshot")
        val expected = ledger.commitment()
        val w0 = System.nanoTime()
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(file), 1 shl 20)).use { ledger.writeTrieSnapshot(it) }
        val write = (System.nanoTime() - w0) / 1e9
        resetPeaks()
        val r0 = System.nanoTime()
        val trie = java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(file), 1 shl 20)).use { FlatStateTrie.readFrom(it) }
        val read = (System.nanoTime() - r0) / 1e9
        val rootAt = System.nanoTime(); val root = trie.root(); val rootSeconds = (System.nanoTime() - rootAt) / 1e9
        check(StateCommitment.commitment(ledger.sequence, ledger.controls, root) == expected) { "snapshot trie does not give the ledger's commitment" }
        println("RESTORE-RUN trie snapshot: ${trie.size} leaves, ${"%.2f".format(file.length() / 1e9)} GB (${"%.0f".format(file.length().toDouble() / trie.size)} B/entity), write ${"%.2f".format(write)} s, read ${"%.2f".format(read)} s, then root() ${"%.3f".format(rootSeconds)} s, peak heap during read ${"%.2f".format(peakGb())} GB")
        file.delete()
    }

    /** Fund, then run [trades] through the real processor on [context]; returns the head sequence and commitment. The processor and ledger are unreachable afterwards. */
    private fun build(cfg: CapacityProcessorConfig, context: MockProcessorContext<String, ByteArray>, accounts: List<AccountId>, securities: List<AssetId>, mode: DecisionMode, trades: Int): Pair<Long, String> {
        val decoder = CapacityInputDecoder { _, _, _, raw -> listOf(CapacityItem.Run(FinanceWire.fromProto(reef.contracts.calcify.finance.v1.FinanceInputV1.parseFrom(raw)))) }
        val processor = CapacityProcessor(cfg, decoder); processor.init(context)
        var offset = 0L
        fun send(topic: String, input: FinanceInput) { context.setRecordMetadata(topic, 0, offset++); processor.process(Record(ByteArray(0), FinanceWire.toProto(input).toByteArray(), 0L)) }
        var action = 0L
        fun key() = ActionKey("calcify-finance", "fund", "a${action++}")
        for (a in accounts) send("CONTROL", FinanceInput.Fund(key(), Authority.OPERATOR, a, usd, 1_000_000_000_000L))
        for (a in accounts) for (s in securities) send("CONTROL", FinanceInput.Fund(key(), Authority.OPERATOR, a, s, 1_000_000L))
        context.resetForwards()
        val rnd = Random(7); val n = accounts.size
        for (i in 0 until trades) {
            val s = securities[i % securities.size]
            val execution = ExecutionKey("run", "session", s.value, "e$i")
            val buyer = accounts[rnd.nextInt(n)]; var seller = accounts[rnd.nextInt(n)]; if (seller == buyer) seller = accounts[(rnd.nextInt(n - 1) + 1 + accounts.indexOf(buyer)) % n]
            val capture = FinanceInput.Capture(ActionKey("calcify-finance", "capture", "e$i"), execution, buyer, seller, 1L + rnd.nextInt(5), 100L + rnd.nextInt(20))
            if (mode == DecisionMode.CAPTURE_SETTLE) send("CAPTURE", CapacityCaptureMapping.captureSettle(capture))
            else { send("CAPTURE", capture); send("CAPTURE", CapacityCaptureMapping.firstSettle(execution)) }
            if (i % 5_000 == 0) context.resetForwards()
        }
        val ledger = processor.currentLedger()
        return ledger.sequence to ledger.commitment()
    }
}

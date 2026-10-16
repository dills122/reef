package com.reef.platform.calcify.finance

import com.reef.platform.calcify.StreamsLifecycleKv
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.Random
import kotlin.test.Test
import org.apache.kafka.streams.processor.TaskId
import org.apache.kafka.streams.processor.api.MockProcessorContext
import org.apache.kafka.streams.processor.api.Record

/**
 * What a clean restart of an evicting lane costs from its lane snapshot against the scan restore, on a real RocksDB store (the Streams persistent store). The lane is built
 * through the real processor, closed (which writes the snapshot, timed) and dropped; then the scan restore and the snapshot restore of the same store alternate, twice each,
 * with peak heap per restore. Every snapshot restore must be used (not fall back) and give the scan's sequence and commitment.
 * Research only (-PresearchTests -PtestHeap=NNg); env: CAP_TRADES (default 1000000), CAP_DECISION_MODE (default capture-settle-v1).
 */
@org.junit.jupiter.api.Tag("research")
class CapacitySnapshotRestoreCostTest {
    private fun env(name: String, default: String) = System.getenv(name) ?: default

    private val usd = AssetId("USD"); private val opening = AccountId("opening")
    private val memory = java.lang.management.ManagementFactory.getMemoryMXBean()
    private fun usedGb(): Double { repeat(3) { System.gc() }; return memory.heapMemoryUsage.used / 1e9 }
    private fun peakGb() = java.lang.management.ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }.sumOf { it.peakUsage.used } / 1e9
    private fun resetPeaks() = java.lang.management.ManagementFactory.getMemoryPoolMXBeans().forEach { it.resetPeakUsage() }

    @Test fun snapshotRestoreAgainstScanRestore() {
        val trades = env("CAP_TRADES", "1000000").toInt(); val accountCount = 2_000; val instrumentCount = 8
        val mode = DecisionMode.ofWire(env("CAP_DECISION_MODE", DecisionMode.CAPTURE_SETTLE.wire))
        val accounts = (0 until accountCount).map { AccountId("acct-$it") }; val securities = (0 until instrumentCount).map { AssetId("I$it") }
        val capacity = CapacityConfig(null, usd, securities.toSet(), opening, 100_000_000, 100_000_000, 100_000_000, 100_000_000, mode)
        val binding = "b".repeat(64)
        val cfg = CapacityProcessorConfig(binding, capacity, "CAPTURE", "CONTROL", "DECISIONS", "snapshot-cost", evictSettled = true)
        val dir = Files.createTempDirectory("capacity-snapshot-cost").toFile()
        try {
            val taskDir = File(dir, "task").also { it.mkdirs() }
            val props = Properties().also { it["application.id"] = "snapshot-cost"; it["bootstrap.servers"] = "localhost:1"; it["state.dir"] = dir.path }
            val context = MockProcessorContext<String, ByteArray>(props, TaskId(0, 0), taskDir)
            val store = CapacityTopology.storeBuilder(cfg, persistent = true).withLoggingDisabled().build()
            store.init(context.getStateStoreContext(), store); context.addStateStore(store)

            val build0 = System.nanoTime()
            val head = build(cfg, context, accounts, securities, mode, trades)
            println("SNAPSHOT-RUN built $trades trades in ${"%.1f".format((System.nanoTime() - build0) / 1e9)} s through the real store; sequence ${head.first}, commitment ${head.second.take(12)}, close (snapshot write) ${"%.2f".format(head.third)} s")
            val file = File(taskDir, "calcify-${cfg.storeName}.lane-snapshot").toPath()
            check(Files.exists(file)) { "close wrote no snapshot" }
            val backup = File(dir, "backup.snapshot").toPath(); Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING); Files.delete(file)
            val kv = StreamsLifecycleKv(store)
            val entries = store.approximateNumEntries()
            println("SNAPSHOT-RUN snapshot file ${"%.3f".format(Files.size(backup) / 1e9)} GB (${"%.0f".format(Files.size(backup).toDouble() / trades)} B/trade), store entries $entries")
            val liveBase = usedGb()
            for (label in listOf("scan", "snapshot", "scan", "snapshot")) {
                if (label == "snapshot") Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING)
                usedGb(); resetPeaks()
                var why: String? = "not reported"
                val t0 = System.nanoTime()
                var restored: CapacityLedger? = CapacityStore.restore(capacity, kv, binding, evictSettled = true, snapshot = if (label == "snapshot") file else null) { why = it } ?: error("empty store")
                val seconds = (System.nanoTime() - t0) / 1e9
                val peak = peakGb()
                check(restored!!.sequence == head.first && restored.commitment() == head.second) { "restored state is not the head the run produced" }
                check(label == "scan" || why == null) { "the snapshot was not used: $why" }
                check(!Files.exists(file)) { "the snapshot outlived the start" }
                val resident = restored.residentObjects
                val after = usedGb()
                restored = null
                println("SNAPSHOT-RUN mode=${mode.wire} trades=$trades $label restore (resident objects $resident): ${"%.2f".format(seconds)} s, peak heap ${"%.2f".format(peak)} GB, live before ${"%.2f".format(liveBase)} GB, live after ${"%.2f".format(after)} GB")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Fund, then run [trades] through the real evicting processor on [context] and close it (the timed snapshot write); returns head sequence, commitment and close seconds. */
    private fun build(cfg: CapacityProcessorConfig, context: MockProcessorContext<String, ByteArray>, accounts: List<AccountId>, securities: List<AssetId>, mode: DecisionMode, trades: Int): Triple<Long, String, Double> {
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
        println("SNAPSHOT-RUN running lane heap after $trades trades: ${"%.2f".format(usedGb())} GB, resident objects ${ledger.residentObjects}")
        val head = ledger.sequence to ledger.commitment()
        val c0 = System.nanoTime(); processor.close(); val close = (System.nanoTime() - c0) / 1e9
        return Triple(head.first, head.second, close)
    }
}
